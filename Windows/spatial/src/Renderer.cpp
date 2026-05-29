#include "uxspace/spatial/Renderer.h"

#include <d3dcompiler.h>

#include <cstdint>
#include <cstring>

namespace uxspace::spatial {

using Microsoft::WRL::ComPtr;
using namespace DirectX;

namespace {

constexpr const char* kShaderHLSL = R"hlsl(
cbuffer Constants : register(b0)
{
    row_major float4x4 worldViewProj;
    // (minU, minV, maxU, maxV) of the texture region to sample. The unit
    // quad's (0..1, 0..1) UVs are remapped into this sub-rect — used by
    // the zoom feature; identity is (0, 0, 1, 1).
    float4 uvRect;
};

struct VSIn
{
    float3 pos : POSITION;
    float2 uv  : TEXCOORD0;
};

struct PSIn
{
    float4 pos : SV_Position;
    float2 uv  : TEXCOORD0;
};

PSIn VSMain(VSIn i)
{
    PSIn o;
    o.pos = mul(float4(i.pos, 1.0f), worldViewProj);
    o.uv  = lerp(uvRect.xy, uvRect.zw, i.uv);
    return o;
}

Texture2D    tex  : register(t0);
SamplerState samp : register(s0);

float4 PSMain(PSIn i) : SV_Target
{
    return tex.Sample(samp, i.uv);
}
)hlsl";

struct Vertex {
    float pos[3];
    float uv[2];
};

// Unit quad in the XY plane, centred at origin, facing -Z. UV (0,0) is the
// top-left of the source texture, matching the convention in D3D.
constexpr Vertex kQuadVerts[4] = {
    { { -0.5f, -0.5f, 0.0f }, { 0.0f, 1.0f } }, // bottom-left
    { {  0.5f, -0.5f, 0.0f }, { 1.0f, 1.0f } }, // bottom-right
    { {  0.5f,  0.5f, 0.0f }, { 1.0f, 0.0f } }, // top-right
    { { -0.5f,  0.5f, 0.0f }, { 0.0f, 0.0f } }, // top-left
};
constexpr std::uint16_t kQuadIndices[6] = { 0, 1, 2,  0, 2, 3 };

} // namespace

bool Renderer::init(ID3D11Device* device) {
    UINT flags = D3DCOMPILE_ENABLE_STRICTNESS;
#ifdef _DEBUG
    flags |= D3DCOMPILE_DEBUG;
#else
    flags |= D3DCOMPILE_OPTIMIZATION_LEVEL3;
#endif

    ComPtr<ID3DBlob> vsBlob, psBlob, errBlob;
    if (FAILED(D3DCompile(kShaderHLSL, std::strlen(kShaderHLSL),
                          "spatial/quad.hlsl", nullptr, nullptr,
                          "VSMain", "vs_5_0", flags, 0, &vsBlob, &errBlob))) return false;
    if (FAILED(D3DCompile(kShaderHLSL, std::strlen(kShaderHLSL),
                          "spatial/quad.hlsl", nullptr, nullptr,
                          "PSMain", "ps_5_0", flags, 0, &psBlob, &errBlob))) return false;

    if (FAILED(device->CreateVertexShader(vsBlob->GetBufferPointer(), vsBlob->GetBufferSize(),
                                          nullptr, &vs_))) return false;
    if (FAILED(device->CreatePixelShader(psBlob->GetBufferPointer(), psBlob->GetBufferSize(),
                                         nullptr, &ps_))) return false;

    const D3D11_INPUT_ELEMENT_DESC layout[] = {
        { "POSITION", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 0,  D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT,    0, 12, D3D11_INPUT_PER_VERTEX_DATA, 0 },
    };
    if (FAILED(device->CreateInputLayout(layout, _countof(layout),
                                         vsBlob->GetBufferPointer(), vsBlob->GetBufferSize(),
                                         &layout_))) return false;

    D3D11_BUFFER_DESC vbd{};
    vbd.ByteWidth = sizeof(kQuadVerts);
    vbd.Usage     = D3D11_USAGE_IMMUTABLE;
    vbd.BindFlags = D3D11_BIND_VERTEX_BUFFER;
    D3D11_SUBRESOURCE_DATA vinit{ kQuadVerts, 0, 0 };
    if (FAILED(device->CreateBuffer(&vbd, &vinit, &vbuf_))) return false;

    D3D11_BUFFER_DESC ibd{};
    ibd.ByteWidth = sizeof(kQuadIndices);
    ibd.Usage     = D3D11_USAGE_IMMUTABLE;
    ibd.BindFlags = D3D11_BIND_INDEX_BUFFER;
    D3D11_SUBRESOURCE_DATA iinit{ kQuadIndices, 0, 0 };
    if (FAILED(device->CreateBuffer(&ibd, &iinit, &ibuf_))) return false;

    // cbuffer must match the HLSL layout: float4x4 + float4 = 80 bytes
    // (multiple of 16, as D3D11 requires).
    D3D11_BUFFER_DESC cbd{};
    cbd.ByteWidth      = sizeof(XMMATRIX) + sizeof(XMFLOAT4);
    cbd.Usage          = D3D11_USAGE_DYNAMIC;
    cbd.BindFlags      = D3D11_BIND_CONSTANT_BUFFER;
    cbd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    if (FAILED(device->CreateBuffer(&cbd, nullptr, &cbuf_))) return false;

    D3D11_SAMPLER_DESC sd{};
    sd.Filter   = D3D11_FILTER_MIN_MAG_LINEAR_MIP_POINT;
    sd.AddressU = D3D11_TEXTURE_ADDRESS_CLAMP;
    sd.AddressV = D3D11_TEXTURE_ADDRESS_CLAMP;
    sd.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
    sd.MaxLOD   = D3D11_FLOAT32_MAX;
    if (FAILED(device->CreateSamplerState(&sd, &sampler_))) return false;

    D3D11_RASTERIZER_DESC rd{};
    rd.FillMode        = D3D11_FILL_SOLID;
    rd.CullMode        = D3D11_CULL_NONE;  // Quads are double-sided for W1.
    rd.DepthClipEnable = TRUE;
    if (FAILED(device->CreateRasterizerState(&rd, &raster_))) return false;

    D3D11_BLEND_DESC bd{};
    bd.RenderTarget[0].BlendEnable           = FALSE;
    bd.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
    if (FAILED(device->CreateBlendState(&bd, &blend_))) return false;

    return true;
}

void Renderer::drawQuad(ID3D11DeviceContext* ctx,
                        const XMMATRIX& worldViewProj,
                        const XMFLOAT4& uvRect,
                        ID3D11ShaderResourceView* texture) {
    if (!vs_ || !ps_ || !texture) return;

    D3D11_MAPPED_SUBRESOURCE m{};
    if (SUCCEEDED(ctx->Map(cbuf_.Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &m))) {
        auto* dst = static_cast<std::uint8_t*>(m.pData);
        std::memcpy(dst,                       &worldViewProj, sizeof(XMMATRIX));
        std::memcpy(dst + sizeof(XMMATRIX),    &uvRect,        sizeof(XMFLOAT4));
        ctx->Unmap(cbuf_.Get(), 0);
    }

    const UINT stride  = sizeof(Vertex);
    const UINT offset  = 0;
    ID3D11Buffer* vbuf = vbuf_.Get();
    ID3D11Buffer* cbuf = cbuf_.Get();
    ID3D11SamplerState* samp = sampler_.Get();

    ctx->IASetInputLayout(layout_.Get());
    ctx->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    ctx->IASetVertexBuffers(0, 1, &vbuf, &stride, &offset);
    ctx->IASetIndexBuffer(ibuf_.Get(), DXGI_FORMAT_R16_UINT, 0);

    ctx->VSSetShader(vs_.Get(), nullptr, 0);
    ctx->VSSetConstantBuffers(0, 1, &cbuf);

    ctx->RSSetState(raster_.Get());

    ctx->PSSetShader(ps_.Get(), nullptr, 0);
    ctx->PSSetShaderResources(0, 1, &texture);
    ctx->PSSetSamplers(0, 1, &samp);

    const float blendFactor[4] = { 0, 0, 0, 0 };
    ctx->OMSetBlendState(blend_.Get(), blendFactor, 0xFFFFFFFF);

    ctx->DrawIndexed(6, 0, 0);
}

} // namespace uxspace::spatial
