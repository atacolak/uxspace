// A textured quad in world space. Position is the quad centre; size is
// width/height in metres; the quad lies in the XY plane and faces -Z
// (toward the camera at the origin).

#pragma once

#include <d3d11.h>
#include <DirectXMath.h>

namespace uxspace::spatial {

struct Surface3D {
    DirectX::XMFLOAT3         position = { 0.0f, 0.0f, 2.0f };
    DirectX::XMFLOAT2         size     = { 1.6f, 0.9f };
    // Sub-rect of the source texture to sample from, as (minU, minV, maxU,
    // maxV). Default is the full texture; a zoom feature shrinks the rect
    // around a focal point.
    DirectX::XMFLOAT4         uvRect   = { 0.0f, 0.0f, 1.0f, 1.0f };
    ID3D11ShaderResourceView* texture  = nullptr;

    DirectX::XMMATRIX worldMatrix() const {
        return DirectX::XMMatrixScaling(size.x, size.y, 1.0f)
             * DirectX::XMMatrixTranslation(position.x, position.y, position.z);
    }
};

} // namespace uxspace::spatial
