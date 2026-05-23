#include "uxspace/spatial/GlassesOutput.h"

#include <windows.h>
#include <wingdi.h>
#include <dxgi.h>

#include <algorithm>
#include <vector>

namespace uxspace::spatial {

using Microsoft::WRL::ComPtr;

namespace {

// Walk active DisplayConfig paths and, for each, resolve its
//   source name (\\.\DISPLAYn)  via DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME
//   target name (EDID friendly) via DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME
// This is the cleanest way to get the EDID friendly name on Windows;
// EnumDisplayDevices Level 1 has historically returned "Generic PnP
// Monitor" instead of the real name on many setups.
std::vector<GlassesOutput::DetectedMonitor> enumerateActiveMonitors() {
    std::vector<GlassesOutput::DetectedMonitor> out;

    UINT32 pathCount = 0;
    UINT32 modeCount = 0;
    if (GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, &pathCount, &modeCount) != ERROR_SUCCESS) {
        return out;
    }
    std::vector<DISPLAYCONFIG_PATH_INFO> paths(pathCount);
    std::vector<DISPLAYCONFIG_MODE_INFO> modes(modeCount);
    if (QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, &pathCount, paths.data(),
                           &modeCount, modes.data(), nullptr) != ERROR_SUCCESS) {
        return out;
    }
    paths.resize(pathCount);

    for (const auto& path : paths) {
        DISPLAYCONFIG_SOURCE_DEVICE_NAME src{};
        src.header.size       = sizeof(src);
        src.header.type       = DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME;
        src.header.adapterId  = path.sourceInfo.adapterId;
        src.header.id         = path.sourceInfo.id;
        if (DisplayConfigGetDeviceInfo(&src.header) != ERROR_SUCCESS) continue;

        DISPLAYCONFIG_TARGET_DEVICE_NAME tgt{};
        tgt.header.size       = sizeof(tgt);
        tgt.header.type       = DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME;
        tgt.header.adapterId  = path.targetInfo.adapterId;
        tgt.header.id         = path.targetInfo.id;
        if (DisplayConfigGetDeviceInfo(&tgt.header) != ERROR_SUCCESS) continue;

        GlassesOutput::DetectedMonitor m;
        m.deviceName   = src.viewGdiDeviceName;
        m.friendlyName = tgt.monitorFriendlyDeviceName;
        out.push_back(std::move(m));
    }
    return out;
}

// Creates a fresh DXGI factory so hotplugged outputs are visible. The
// device's cached factory (device->QueryInterface(IDXGIDevice)->GetAdapter
// ->GetParent(IDXGIFactory2)) is a snapshot from D3D11CreateDevice time;
// outputs added after device creation aren't always enumerated through it.
bool createFreshFactory(ComPtr<IDXGIFactory2>& factoryOut) {
    return SUCCEEDED(CreateDXGIFactory1(IID_PPV_ARGS(&factoryOut)));
}

bool findDxgiOutputByDeviceName(std::wstring_view deviceName,
                                ComPtr<IDXGIOutput>& outOutput) {
    ComPtr<IDXGIFactory2> factory;
    if (!createFreshFactory(factory)) return false;

    for (UINT a = 0; ; ++a) {
        ComPtr<IDXGIAdapter1> ad;
        if (factory->EnumAdapters1(a, &ad) == DXGI_ERROR_NOT_FOUND) break;
        for (UINT o = 0; ; ++o) {
            ComPtr<IDXGIOutput> out;
            if (ad->EnumOutputs(o, &out) == DXGI_ERROR_NOT_FOUND) break;
            DXGI_OUTPUT_DESC od{};
            out->GetDesc(&od);
            if (deviceName == od.DeviceName) {
                outOutput = out;
                return true;
            }
        }
    }
    return false;
}

// Pick the highest-refresh mode at the highest resolution. The Viture
// glasses' "native SBS" mode is wider than mono (3840xH) when 3D is
// enabled; here we just take whatever's tallest+widest+fastest and let
// the caller branch on aspect.
DXGI_MODE_DESC pickBestMode(IDXGIOutput* output) {
    UINT count = 0;
    output->GetDisplayModeList(DXGI_FORMAT_R8G8B8A8_UNORM, 0, &count, nullptr);
    std::vector<DXGI_MODE_DESC> all(count);
    output->GetDisplayModeList(DXGI_FORMAT_R8G8B8A8_UNORM, 0, &count, all.data());

    DXGI_MODE_DESC best{};
    best.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    auto pixels = [](const DXGI_MODE_DESC& m) {
        return static_cast<std::uint64_t>(m.Width) * m.Height;
    };
    auto refresh = [](const DXGI_MODE_DESC& m) {
        return m.RefreshRate.Denominator
                 ? static_cast<double>(m.RefreshRate.Numerator) / m.RefreshRate.Denominator
                 : 0.0;
    };
    for (const auto& m : all) {
        if (pixels(m) > pixels(best)
            || (pixels(m) == pixels(best) && refresh(m) > refresh(best))) {
            best = m;
        }
    }
    return best;
}

constexpr const wchar_t* kHostClass = L"UxSpaceGlassesHost";

void ensureHostClassRegistered() {
    static bool registered = false;
    if (registered) return;
    WNDCLASSEXW wc{ sizeof(wc) };
    wc.lpfnWndProc   = DefWindowProcW;
    wc.hInstance     = GetModuleHandleW(nullptr);
    wc.lpszClassName = kHostClass;
    wc.hCursor       = LoadCursor(nullptr, IDC_ARROW);
    RegisterClassExW(&wc);
    registered = true;
}

} // namespace

bool GlassesOutput::find(std::wstring_view nameSubstring,
                         DetectedMonitor& match,
                         std::vector<DetectedMonitor>* all) {
    auto monitors = enumerateActiveMonitors();
    if (all) *all = monitors;
    for (const auto& m : monitors) {
        if (m.friendlyName.find(nameSubstring) != std::wstring::npos) {
            match = m;
            return true;
        }
    }
    return false;
}

bool GlassesOutput::open(ID3D11Device* device, const DetectedMonitor& monitor) {
    close();

    if (!findDxgiOutputByDeviceName(monitor.deviceName, output_)) {
        return false;
    }

    const DXGI_MODE_DESC mode = pickBestMode(output_.Get());
    if (mode.Width == 0 || mode.Height == 0) return false;

    DXGI_OUTPUT_DESC od{};
    output_->GetDesc(&od);
    const RECT& r = od.DesktopCoordinates;

    ensureHostClassRegistered();
    host_ = CreateWindowExW(
        WS_EX_TOPMOST | WS_EX_NOACTIVATE,
        kHostClass, L"UxSpace glasses",
        WS_POPUP,
        r.left, r.top, r.right - r.left, r.bottom - r.top,
        nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
    if (!host_) return false;

    // Fresh factory for the same reason as findDxgiOutputByDeviceName.
    ComPtr<IDXGIFactory2> factory;
    if (!createFreshFactory(factory)) { DestroyWindow(host_); host_ = nullptr; return false; }

    DXGI_SWAP_CHAIN_DESC1 sd{};
    sd.Width       = mode.Width;
    sd.Height      = mode.Height;
    sd.Format      = mode.Format;
    sd.SampleDesc  = { 1, 0 };
    sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    sd.BufferCount = 2;
    sd.SwapEffect  = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    sd.AlphaMode   = DXGI_ALPHA_MODE_IGNORE;

    DXGI_SWAP_CHAIN_FULLSCREEN_DESC fd{};
    fd.RefreshRate      = mode.RefreshRate;
    fd.Scaling          = mode.Scaling;
    fd.ScanlineOrdering = mode.ScanlineOrdering;
    fd.Windowed         = TRUE;

    // Borderless fullscreen on the target output via a WS_POPUP HWND that
    // exactly covers the output's desktop rectangle. We skip the legacy
    // SetFullscreenState(TRUE, output) path: with FLIP_DISCARD the modern
    // fullscreen-flip optimisation kicks in automatically when the HWND
    // covers a single output, and we avoid the SetFullscreenState
    // fragility (Alt-Tab, mode-change loops, focus-loss restore).
    if (FAILED(factory->CreateSwapChainForHwnd(device, host_, &sd, &fd,
                                               output_.Get(), &swap_))) {
        DestroyWindow(host_);
        host_ = nullptr;
        return false;
    }

    ComPtr<ID3D11Texture2D> back;
    if (FAILED(swap_->GetBuffer(0, IID_PPV_ARGS(&back)))) { close(); return false; }
    if (FAILED(device->CreateRenderTargetView(back.Get(), nullptr, &rtv_))) {
        close();
        return false;
    }

    ShowWindow(host_, SW_SHOWNORMAL);

    width_        = mode.Width;
    height_       = mode.Height;
    refreshNum_   = mode.RefreshRate.Numerator;
    refreshDen_   = mode.RefreshRate.Denominator ? mode.RefreshRate.Denominator : 1;
    deviceName_   = monitor.deviceName;
    friendlyName_ = monitor.friendlyName;
    return true;
}

void GlassesOutput::close() {
    rtv_.Reset();
    swap_.Reset();
    output_.Reset();
    if (host_) {
        DestroyWindow(host_);
        host_ = nullptr;
    }
    width_      = 0;
    height_     = 0;
    refreshNum_ = 0;
    refreshDen_ = 1;
}

} // namespace uxspace::spatial
