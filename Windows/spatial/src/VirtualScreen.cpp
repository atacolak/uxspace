#include "uxspace/spatial/VirtualScreen.h"

#include <dxgi.h>
#include <string_view>

#include <uxspace/log.h>

namespace uxspace::spatial {

using Microsoft::WRL::ComPtr;

namespace {

// EnumDisplayDevices Level 0 (adapters). The UxSpace IddCx driver names
// its adapter "UxSpace ..." which is exactly what we match here. For a
// physical monitor on a real GPU this returns the GPU name (we don't
// match those by design — VirtualScreen is for IddCx outputs).
//
// In display "duplicate" mode Windows can drop the
// DISPLAY_DEVICE_ATTACHED_TO_DESKTOP flag on the IddCx adapter even
// though the device is still present and DDA-capturable, so the
// attached-to-desktop check is a soft preference: we'd rather match an
// attached adapter, but we'll accept an unattached one as fallback.
// Every enumerated adapter is logged so a "couldn't find it" report
// always carries enough info to diagnose without rerunning.
bool findOutputByAdapterName(std::wstring_view sub,
                             IDXGIOutput** outputOut,
                             std::wstring& friendlyName) {
    DISPLAY_DEVICEW dd{};
    dd.cb = sizeof(dd);
    std::wstring targetAttachedName;
    std::wstring targetUnattachedName;
    std::wstring attachedFriendly;
    std::wstring unattachedFriendly;
    for (DWORD i = 0; EnumDisplayDevicesW(nullptr, i, &dd, 0); ++i) {
        const bool attached = (dd.StateFlags & DISPLAY_DEVICE_ATTACHED_TO_DESKTOP) != 0;
        const bool matches  = std::wstring_view(dd.DeviceString).find(sub) != std::wstring_view::npos;
        uxspace::log::info("vscreen:   adapter[%lu] DeviceName='%ls' DeviceString='%ls' "
                           "attached=%d matches='%.*ls'=%d StateFlags=0x%08X",
                           i, dd.DeviceName, dd.DeviceString,
                           attached ? 1 : 0,
                           static_cast<int>(sub.size()), sub.data(),
                           matches ? 1 : 0,
                           dd.StateFlags);
        if (!matches) continue;
        if (attached && targetAttachedName.empty()) {
            targetAttachedName = dd.DeviceName;
            attachedFriendly   = dd.DeviceString;
        } else if (!attached && targetUnattachedName.empty()) {
            targetUnattachedName = dd.DeviceName;
            unattachedFriendly   = dd.DeviceString;
        }
    }

    std::wstring targetDeviceName;
    if (!targetAttachedName.empty()) {
        targetDeviceName = targetAttachedName;
        friendlyName     = attachedFriendly;
    } else if (!targetUnattachedName.empty()) {
        targetDeviceName = targetUnattachedName;
        friendlyName     = unattachedFriendly;
        uxspace::log::warn("vscreen: accepting UNATTACHED UxSpace adapter '%ls' "
                           "(likely display duplicate / mirror mode).",
                           friendlyName.c_str());
    } else {
        return false;
    }

    ComPtr<IDXGIFactory1> factory;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) return false;
    for (UINT a = 0; ; ++a) {
        ComPtr<IDXGIAdapter1> adapter;
        if (factory->EnumAdapters1(a, &adapter) == DXGI_ERROR_NOT_FOUND) break;
        for (UINT o = 0; ; ++o) {
            ComPtr<IDXGIOutput> output;
            if (adapter->EnumOutputs(o, &output) == DXGI_ERROR_NOT_FOUND) break;
            DXGI_OUTPUT_DESC od{};
            output->GetDesc(&od);
            if (targetDeviceName == od.DeviceName) {
                *outputOut = output.Detach();
                return true;
            }
        }
    }
    uxspace::log::warn("vscreen: matched adapter '%ls' (%ls) but DXGI could not "
                       "enumerate a matching output. Capture not initialised.",
                       friendlyName.c_str(), targetDeviceName.c_str());
    return false;
}

} // namespace

VirtualScreen::VirtualScreen(std::wstring nameSub) : nameSubstring_(std::move(nameSub)) {}

void VirtualScreen::reset() {
    duplication_.Reset();
    dst_.Reset();
    srv_.Reset();
    width_       = 0;
    height_      = 0;
    desktopRect_ = {};
    present_     = false;
    // monitorName_ + frameCount_ preserved for UI continuity.
}

bool VirtualScreen::initCapture(ID3D11Device* device) {
    ComPtr<IDXGIOutput> output;
    if (!findOutputByAdapterName(nameSubstring_, &output, monitorName_)) {
        if (lastError_ != E_FAIL) {
            uxspace::log::warn("vscreen: no adapter with friendly name containing '%ls' "
                               "(UxSpace driver not installed, or not yet enumerated).",
                               nameSubstring_.c_str());
        }
        lastError_ = E_FAIL;
        return false;
    }
    uxspace::log::info("vscreen: found adapter '%ls' for substring '%ls'.",
                       monitorName_.c_str(), nameSubstring_.c_str());

    ComPtr<IDXGIOutput1> output1;
    if (FAILED(output.As(&output1))) {
        uxspace::log::error("vscreen: IDXGIOutput1 query failed.");
        lastError_ = E_NOINTERFACE;
        return false;
    }

    HRESULT hr = output1->DuplicateOutput(device, &duplication_);
    if (FAILED(hr)) {
        uxspace::log::error("vscreen: DuplicateOutput failed: hr=0x%08X.",
                            static_cast<unsigned>(hr));
        lastError_ = hr;
        return false;
    }

    DXGI_OUTDUPL_DESC od{};
    duplication_->GetDesc(&od);
    width_  = od.ModeDesc.Width;
    height_ = od.ModeDesc.Height;

    DXGI_OUTPUT_DESC outDesc{};
    output->GetDesc(&outDesc);
    desktopRect_ = outDesc.DesktopCoordinates;

    uxspace::log::info("vscreen: capture initialized %ux%u (desktop rect %ld,%ld %ldx%ld).",
                       width_, height_,
                       desktopRect_.left, desktopRect_.top,
                       desktopRect_.right - desktopRect_.left,
                       desktopRect_.bottom - desktopRect_.top);

    D3D11_TEXTURE2D_DESC td{};
    td.Width      = width_;
    td.Height     = height_;
    td.MipLevels  = 1;
    td.ArraySize  = 1;
    td.Format     = od.ModeDesc.Format;
    td.SampleDesc = { 1, 0 };
    td.Usage      = D3D11_USAGE_DEFAULT;
    td.BindFlags  = D3D11_BIND_SHADER_RESOURCE;
    if (FAILED(device->CreateTexture2D(&td, nullptr, &dst_))) {
        lastError_ = E_FAIL;
        return false;
    }
    if (FAILED(device->CreateShaderResourceView(dst_.Get(), nullptr, &srv_))) {
        lastError_ = E_FAIL;
        return false;
    }
    lastError_ = S_OK;
    present_   = true;
    return true;
}

void VirtualScreen::tick(ID3D11Device* device, ID3D11DeviceContext* ctx) {
    // Throttle re-init attempts so the EnumDisplayDevices + DXGI walk
    // doesn't hammer every frame when no UxSpace monitor exists.
    if (!duplication_) {
        if (retryCountdown_ > 0) { --retryCountdown_; return; }
        if (!initCapture(device)) {
            retryCountdown_ = 60; // ~1s @60Hz before next attempt
        }
        return;
    }

    DXGI_OUTDUPL_FRAME_INFO info{};
    ComPtr<IDXGIResource> res;
    HRESULT hr = duplication_->AcquireNextFrame(0, &info, &res);

    if (hr == DXGI_ERROR_WAIT_TIMEOUT) return;

    // Layout / mode change invalidates the duplication: tear down so the
    // next tick re-acquires. Holding a stale duplication wedges cursor
    // routing through the IddCx output (the OS still considers it
    // "claimed"), which presents as mouse-glide being blocked at the
    // boundary. See CLAUDE.md "Architecture invariants".
    if (hr == DXGI_ERROR_ACCESS_LOST || hr == DXGI_ERROR_INVALID_CALL) {
        uxspace::log::warn("vscreen: duplication invalidated (hr=0x%08X), resetting.",
                           static_cast<unsigned>(hr));
        lastError_ = hr;
        reset();
        return;
    }
    if (FAILED(hr)) { lastError_ = hr; return; }

    ComPtr<ID3D11Texture2D> srcTex;
    if (SUCCEEDED(res.As(&srcTex)) && dst_) {
        ctx->CopyResource(dst_.Get(), srcTex.Get());
        ++frameCount_;
    }
    duplication_->ReleaseFrame();
}

} // namespace uxspace::spatial
