// Locates the Viture glasses display (by EDID friendly-name substring —
// "VITURE" by default — resolved via the DisplayConfig API so we get the
// monitor name, not the GPU adapter name) and owns the fullscreen-
// exclusive swap chain on it.
//
// W1 caveat: the Viture only advertises an SBS-stereo mode (3840xH) once
// its 3D mode has been toggled on via the Viture SDK; that toggle is W2
// (:viture). Until then the highest native mode is mono (e.g. 1920x1200
// @90Hz) and the caller should renderMono — see Scene.h. `pickedMode`
// records what we actually got so :app can branch on it.

#pragma once

#include <d3d11.h>
#include <dxgi1_2.h>
#include <wrl/client.h>

#include <string>
#include <string_view>
#include <vector>

namespace uxspace::spatial {

class GlassesOutput {
public:
    struct DetectedMonitor {
        std::wstring deviceName;     // \\.\DISPLAY<n>
        std::wstring friendlyName;   // EDID monitor name, e.g. "VITURE"
    };

    // Walks the active DisplayConfig paths. Fills `all` (if non-null) with
    // every active display's source+friendly name; returns the first whose
    // friendly name contains `nameSubstring`.
    static bool find(std::wstring_view nameSubstring,
                     DetectedMonitor& match,
                     std::vector<DetectedMonitor>* all = nullptr);

    // Opens the matched output fullscreen-exclusive on its highest-Hz
    // native mode. Returns false on any failure; caller can fall back to
    // dev-window preview.
    bool open(ID3D11Device* device, const DetectedMonitor& monitor);

    void close();

    bool                    opened()       const { return swap_ != nullptr; }
    ID3D11RenderTargetView* rtv()          const { return rtv_.Get(); }
    IDXGISwapChain1*        swap()         const { return swap_.Get(); }
    UINT                    width()        const { return width_; }
    UINT                    height()       const { return height_; }
    UINT                    refreshNum()   const { return refreshNum_; }
    UINT                    refreshDen()   const { return refreshDen_; }
    bool                    isStereoMode() const { return width_ >= 2 * height_; }
    const std::wstring&     deviceName()   const { return deviceName_; }
    const std::wstring&     friendlyName() const { return friendlyName_; }

private:
    Microsoft::WRL::ComPtr<IDXGIOutput>            output_;
    Microsoft::WRL::ComPtr<IDXGISwapChain1>        swap_;
    Microsoft::WRL::ComPtr<ID3D11RenderTargetView> rtv_;
    HWND          host_       = nullptr;
    UINT          width_      = 0;
    UINT          height_     = 0;
    UINT          refreshNum_ = 0;
    UINT          refreshDen_ = 1;
    std::wstring  deviceName_;
    std::wstring  friendlyName_;
};

} // namespace uxspace::spatial
