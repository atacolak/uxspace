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
    band = std::clamp(band, kScreenBandMin, 1.0f);
    UINT areaH = static_cast<UINT>(availH * band);
    UINT areaW = areaH * 16u / 9u;
    if (areaW > availW) {
        areaW = availW;
        areaH = areaW * 9u / 16u;
    }
    areaW = std::max<UINT>(areaW, 1u);
    areaH = std::max<UINT>(areaH, 1u);

    D3D11_VIEWPORT vp{};
    vp.TopLeftX = static_cast<FLOAT>(originX + (availW - areaW) / 2);
    vp.TopLeftY = static_cast<FLOAT>((availH - areaH) / 2);
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
