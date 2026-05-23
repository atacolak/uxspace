// Stereo camera. W1 is PINNED-only: head pose is identity; the per-eye
// view is purely the inverse of the eye-offset translation. W2 will plug
// in an inverse head pose for `Camera.FREE`; see docs/ROADMAP.md.
//
// Coordinate convention: left-handed (D3D default). +X right, +Y up,
// +Z forward. A surface at world (0,0,2) is two metres in front of the
// camera.

#pragma once

#include <cstdint>
#include <DirectXMath.h>

namespace uxspace::spatial {

enum class Eye : std::uint8_t { Left = 0, Right = 1 };

struct StereoCamera {
    float ipdMeters    = 0.063f;
    float fovYRadians  = DirectX::XMConvertToRadians(45.0f);
    float aspect       = 16.0f / 9.0f;
    float nearZ        = 0.05f;
    float farZ         = 100.0f;

    DirectX::XMMATRIX view(Eye eye) const;
    DirectX::XMMATRIX projection() const;
};

} // namespace uxspace::spatial
