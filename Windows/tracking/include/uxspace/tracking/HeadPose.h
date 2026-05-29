// Pose emitted by a HeadTracker. Position is metres; orientation is a
// unit quaternion in DirectXMath convention (XMFLOAT4 = (x, y, z, w)).
// `valid` is false when the tracker has not produced a stable pose yet
// (e.g. IMU warming up, VIO not yet localised). Implementations write
// the pose into an atomic double-buffer; consumers always read the
// latest fully-written sample via HeadTracker::latestPose().

#pragma once

#include <cstdint>

#include <DirectXMath.h>

namespace uxspace::tracking {

struct HeadPose {
    DirectX::XMFLOAT3 position    = { 0.0f, 0.0f, 0.0f };
    DirectX::XMFLOAT4 orientation = { 0.0f, 0.0f, 0.0f, 1.0f }; // identity (x,y,z,w)
    std::uint64_t     timestampNs = 0;
    bool              valid       = false;
};

} // namespace uxspace::tracking
