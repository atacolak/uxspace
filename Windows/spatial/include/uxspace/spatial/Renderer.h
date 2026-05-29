// Shared D3D11 pipeline for drawing a textured unit quad in world space.
// One instance per app; surfaces just call drawQuad() with a transform.
//
// The mesh is a unit quad in local XY at z=0; world matrices place / scale
// it. Backface culling is disabled so quads are visible from either side
// (we'll tighten this up when we have real layouts in W3).

#pragma once

#include <d3d11.h>
#include <DirectXMath.h>
#include <wrl/client.h>

namespace uxspace::spatial {

class Renderer {
public:
    bool init(ID3D11Device* device);

    void drawQuad(ID3D11DeviceContext* ctx,
                  const DirectX::XMMATRIX& worldViewProj,
                  const DirectX::XMFLOAT4& uvRect,
                  ID3D11ShaderResourceView* texture);

private:
    Microsoft::WRL::ComPtr<ID3D11VertexShader>    vs_;
    Microsoft::WRL::ComPtr<ID3D11PixelShader>     ps_;
    Microsoft::WRL::ComPtr<ID3D11InputLayout>     layout_;
    Microsoft::WRL::ComPtr<ID3D11Buffer>          vbuf_;
    Microsoft::WRL::ComPtr<ID3D11Buffer>          ibuf_;
    Microsoft::WRL::ComPtr<ID3D11Buffer>          cbuf_;
    Microsoft::WRL::ComPtr<ID3D11SamplerState>    sampler_;
    Microsoft::WRL::ComPtr<ID3D11RasterizerState> raster_;
    Microsoft::WRL::ComPtr<ID3D11BlendState>      blend_;
};

} // namespace uxspace::spatial
