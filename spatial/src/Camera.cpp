#include "uxspace/spatial/Camera.h"

namespace uxspace::spatial {

using namespace DirectX;

XMMATRIX StereoCamera::view(Eye eye) const {
    // Camera world transform for each eye = translate(eyeX, 0, 0). View
    // matrix = inverse = translate(-eyeX, 0, 0). PINNED means head pose
    // is identity, so this *is* the view; W2 will pre-multiply by the
    // inverse head pose.
    const float eyeX = (eye == Eye::Left ? -1.0f : 1.0f) * (ipdMeters * 0.5f);
    return XMMatrixTranslation(-eyeX, 0.0f, 0.0f);
}

XMMATRIX StereoCamera::projection() const {
    return XMMatrixPerspectiveFovLH(fovYRadians, aspect, nearZ, farZ);
}

} // namespace uxspace::spatial
