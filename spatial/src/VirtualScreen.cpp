#include "uxspace/spatial/VirtualScreen.h"

#include <dxgi.h>
#include <string_view>

namespace uxspace::spatial {

using Microsoft::WRL::ComPtr;

namespace {

// EnumDisplayDevices Level 0 (adapters). The UxSpace IddCx driver names
// its adapter "UxSpace ..." which is exactly what we match here. For a
// physical monitor on a real GPU this returns the GPU name (we don't
// match those by design — VirtualScreen is for IddCx outputs).
bool findOutputByAdapterName(std::wstring_view sub,
                             IDXGIOutput** outputOut,
                             std::wstring& friendlyName) {
    DISPLAY_DEVICEW dd{};
    dd.cb = sizeof(dd);
    std::wstring targetDeviceName;
    for (DWORD i = 0; EnumDisplayDevicesW(nullptr, i, &dd, 0); ++i) {
        if (std::wstring_view(dd.DeviceString).find(sub) != std::wstring_view::npos
            && (dd.StateFlags & DISPLAY_DEVICE_ATTACHED_TO_DESKTOP)) {
            targetDeviceName = dd.DeviceName;
            friendlyName     = dd.DeviceString;
            break;
        }
    }
    if (targetDeviceName.empty()) return false;

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
        lastError_ = E_FAIL;
        return false;
    }
    ComPtr<IDXGIOutput1> output1;
    if (FAILED(output.As(&output1))) { lastError_ = E_NOINTERFACE; return false; }

    HRESULT hr = output1->DuplicateOutput(device, &duplication_);
    if (FAILED(hr)) { lastError_ = hr; return false; }

    DXGI_OUTDUPL_DESC od{};
    duplication_->GetDesc(&od);
    width_  = od.ModeDesc.Width;
    height_ = od.ModeDesc.Height;

    DXGI_OUTPUT_DESC outDesc{};
    output->GetDesc(&outDesc);
    desktopRect_ = outDesc.DesktopCoordinates;

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
