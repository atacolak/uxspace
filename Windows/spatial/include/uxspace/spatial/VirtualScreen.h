// DDA capture of an attached output, addressed by an EnumDisplayDevices
// friendly-name substring. The capture transparently re-initialises after
// ACCESS_LOST / mode-change tear-downs; that recycle is what keeps the OS
// from wedging cursor routing through an IddCx output (see CLAUDE.md).
//
// Architecture: docs/ARCHITECTURE.md §2 — `VirtualScreen` is :spatial's
// view of a virtual monitor's framebuffer as a D3D11 texture.

#pragma once

#include <d3d11.h>
#include <dxgi1_2.h>
#include <wrl/client.h>

#include <cstdint>
#include <string>

namespace uxspace::spatial {

class VirtualScreen {
public:
    explicit VirtualScreen(std::wstring friendlyNameSubstring = L"UxSpace");

    // Call once per frame on the same device the texture must be visible to.
    void tick(ID3D11Device* device, ID3D11DeviceContext* ctx);

    bool                       present()      const { return present_; }
    ID3D11ShaderResourceView*  srv()          const { return srv_.Get(); }
    UINT                       width()        const { return width_; }
    UINT                       height()       const { return height_; }
    // Desktop coordinates of the captured monitor; valid while present().
    // Used by the zoom feature to test "cursor is on the UxSpace display"
    // and to convert a global cursor position to normalised texture UV.
    RECT                       desktopRect()  const { return desktopRect_; }
    const std::wstring&        monitorName()  const { return monitorName_; }
    std::uint64_t              frameCount()   const { return frameCount_; }
    HRESULT                    lastError()    const { return lastError_; }

private:
    bool initCapture(ID3D11Device* device);
    void reset();

    std::wstring                                     nameSubstring_;
    std::wstring                                     monitorName_;
    Microsoft::WRL::ComPtr<IDXGIOutputDuplication>   duplication_;
    Microsoft::WRL::ComPtr<ID3D11Texture2D>          dst_;
    Microsoft::WRL::ComPtr<ID3D11ShaderResourceView> srv_;
    UINT          width_           = 0;
    UINT          height_          = 0;
    RECT          desktopRect_     = {};
    std::uint64_t frameCount_      = 0;
    HRESULT       lastError_       = S_OK;
    bool          present_         = false;
    int           retryCountdown_  = 0;
};

} // namespace uxspace::spatial
