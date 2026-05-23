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

// Continuous slider range for screen size. Values <= 1.0 letterbox the
// content (smaller = more FoV unused, more comfortable). Values > 1.0
// overflow the framebuffer — the central crop is what the wearer sees,
// so the displayed quad appears LARGER / CLOSER.
inline constexpr float kScreenBandMin  = 0.70f;
inline constexpr float kScreenBandMax  = 2.00f;
inline constexpr float kScreenBandStep = 0.10f;

} // namespace uxspace::spatial
