// Stereo camera with two view modes (PINNED / FREE) per
// docs/ARCHITECTURE.md §8.
//
//   PINNED: head pose is ignored. Quads are head-locked.
//   FREE:   the inverse of the head pose is applied so quads stay in
//           world space while the wearer looks around them.
//
// The per-eye view always pre-multiplies an eye-offset translation
// (±ipdMeters/2 in X). headPose is in metres + DirectXMath quaternion
// (XMFLOAT4 = x, y, z, w) in the same left-handed convention as the
// rest of the scene.
//
// Coordinate convention: left-handed (D3D default). +X right, +Y up,
// +Z forward. A surface at world (0,0,2) is two metres in front of the
// camera at identity pose.

#pragma once

#include <cstdint>
#include <DirectXMath.h>

#include <uxspace/tracking/HeadPose.h>
#include <uxspace/tracking/ViewMode.h>

namespace uxspace::spatial {

enum class Eye : std::uint8_t { Left = 0, Right = 1 };

struct StereoCamera {
    float ipdMeters    = 0.063f;
    float fovYRadians  = DirectX::XMConvertToRadians(45.0f);
    float aspect       = 16.0f / 9.0f;
    float nearZ        = 0.05f;
    float farZ         = 100.0f;

    uxspace::tracking::ViewMode mode     = uxspace::tracking::ViewMode::PINNED;
    uxspace::tracking::HeadPose headPose;  // identity unless explicitly set

    DirectX::XMMATRIX view(Eye eye) const;
    DirectX::XMMATRIX projection() const;
};

} // namespace uxspace::spatial
