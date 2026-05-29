#include "uxspace/spatial/Scene.h"
#include "uxspace/spatial/Renderer.h"

#include <d3d11.h>

#include <algorithm>

namespace uxspace::spatial {

using namespace DirectX;

namespace {

// Largest 16:9 viewport that is `band` of `availH` and fits within `availW`,
// offset by `originX` along X. Mirrors WorkspaceRenderer.applyBand() from
// the Android counterpart so the perceived screen size matches across
// platforms.
D3D11_VIEWPORT bandViewport(UINT originX, UINT availW, UINT availH, float band) {
    // Clamp to [min, max]. Above 1.0 the rendered area is larger than
    // the framebuffer half; the central crop is what the wearer sees,
    // which appears closer / bigger. D3D handles viewports extending
    // beyond the framebuffer correctly (no error, content clipped at
    // the framebuffer boundary).
    band = std::clamp(band, kScreenBandMin, kScreenBandMax);
    const int areaH_i = static_cast<int>(availH * band);
    int areaH = areaH_i;
    int areaW = areaH * 16 / 9;
    // Cap by aspect only when band <= 1.0 (we want the 16:9 region to
    // fit inside the per-eye half-framebuffer width). When band > 1.0
    // we deliberately overflow.
    if (band <= 1.0f && areaW > static_cast<int>(availW)) {
        areaW = static_cast<int>(availW);
        areaH = areaW * 9 / 16;
    }
    areaW = std::max(areaW, 1);
    areaH = std::max(areaH, 1);

    D3D11_VIEWPORT vp{};
    // Negative top-left is valid for D3D11; content outside framebuffer
    // is clipped during rasterisation.
    vp.TopLeftX = static_cast<FLOAT>(static_cast<int>(originX) + (static_cast<int>(availW) - areaW) / 2);
    vp.TopLeftY = static_cast<FLOAT>((static_cast<int>(availH) - areaH) / 2);
    vp.Width    = static_cast<FLOAT>(areaW);
    vp.Height   = static_cast<FLOAT>(areaH);
    vp.MinDepth = 0.0f;
    vp.MaxDepth = 1.0f;
    return vp;
}

void renderSurfaces(ID3D11DeviceContext* ctx,
                    Renderer& renderer,
                    const std::vector<Surface3D>& surfaces,
                    const XMMATRIX& viewProj) {
    for (const auto& s : surfaces) {
        if (!s.texture) continue;
        const XMMATRIX wvp = s.worldMatrix() * viewProj;
        renderer.drawQuad(ctx, wvp, s.uvRect, s.texture);
    }
}

} // namespace

void Scene::renderStereo(ID3D11DeviceContext* ctx,
                         Renderer& renderer,
                         StereoCamera camera,
                         UINT framebufferWidth,
                         UINT framebufferHeight) const {
    const UINT eyeW = framebufferWidth / 2;
    // The band is always 16:9, so per-eye projection aspect is fixed.
    camera.aspect = 16.0f / 9.0f;
    const XMMATRIX proj = camera.projection();

    // Left eye
    {
        const D3D11_VIEWPORT vp = bandViewport(0, eyeW, framebufferHeight, screenBand);
        ctx->RSSetViewports(1, &vp);
        renderSurfaces(ctx, renderer, surfaces, camera.view(Eye::Left) * proj);
    }
    // Right eye
    {
        const D3D11_VIEWPORT vp = bandViewport(eyeW, eyeW, framebufferHeight, screenBand);
        ctx->RSSetViewports(1, &vp);
        renderSurfaces(ctx, renderer, surfaces, camera.view(Eye::Right) * proj);
    }
}

void Scene::renderMono(ID3D11DeviceContext* ctx,
                       Renderer& renderer,
                       StereoCamera camera,
                       UINT framebufferWidth,
                       UINT framebufferHeight) const {
    camera.aspect = 16.0f / 9.0f;
    const D3D11_VIEWPORT vp = bandViewport(0, framebufferWidth, framebufferHeight, screenBand);
    ctx->RSSetViewports(1, &vp);
    // No eye offset in mono — both eyes see the same image. Use the
    // head-origin view directly via Eye::Left with ipdMeters=0 effective
    // by clearing IPD on the copy.
    StereoCamera monoCam = camera;
    monoCam.ipdMeters = 0.0f;
    renderSurfaces(ctx, renderer, surfaces, monoCam.view(Eye::Left) * monoCam.projection());
}

} // namespace uxspace::spatial
