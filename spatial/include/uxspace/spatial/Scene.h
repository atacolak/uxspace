// A collection of Surface3Ds rendered through a StereoCamera. The scene
// owns no D3D resources — it just orchestrates per-eye draw calls into a
// caller-bound render target.
//
// `renderStereo` splits the target horizontally and draws each eye into
// its half. `renderMono` skips the split and draws once at the eye-zero
// (head-origin) view — used when the framebuffer aspect indicates a non-
// SBS display mode (e.g. Viture in default mono until :viture toggles 3D
// mode in W2).

#pragma once

#include <vector>

#include "Camera.h"
#include "Surface3D.h"

struct ID3D11DeviceContext;

namespace uxspace::spatial {

class Renderer;

struct Scene {
    std::vector<Surface3D> surfaces;

    // Height fraction of the centred 16:9 render band. The glasses' field-
    // of-view edges are uncomfortable to view, so we render into a band
    // and let the framebuffer borders show through.
    float screenBand = 0.90f;

    void renderStereo(ID3D11DeviceContext* ctx,
                      Renderer& renderer,
                      StereoCamera camera,
                      UINT framebufferWidth,
                      UINT framebufferHeight) const;

    void renderMono(ID3D11DeviceContext* ctx,
                    Renderer& renderer,
                    StereoCamera camera,
                    UINT framebufferWidth,
                    UINT framebufferHeight) const;
};

// Preset cycle for the screen-band button. Tighter range than the Android
// client (which uses 0.70 / 0.83 / 1.00); on Viture these three are the
// useful comfort sweet spot — anything below ~0.75 wastes too much FoV.
inline constexpr float kScreenBandPresets[] = { 0.80f, 0.85f, 0.90f };
inline constexpr float kScreenBandMin = 0.5f;

} // namespace uxspace::spatial
