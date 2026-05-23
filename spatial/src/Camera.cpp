#include "uxspace/spatial/Camera.h"

namespace uxspace::spatial {

using namespace DirectX;
using tracking::ViewMode;

XMMATRIX StereoCamera::view(Eye eye) const {
    // Per-eye offset, expressed as the inverse translation (camera at
    // ±ipd/2 → view shifts by ∓ipd/2 in row-vector math).
    const float eyeX     = (eye == Eye::Left ? -1.0f : 1.0f) * (ipdMeters * 0.5f);
    const XMMATRIX eyeXf = XMMatrixTranslation(-eyeX, 0.0f, 0.0f);

    if (mode == ViewMode::PINNED) return eyeXf;

    // FREE: world is locked, camera (head) moves. View = inverse(headWorld) ∘ inverse(eyeOffset).
    // For row-vector convention with headWorld = rot(q) * trans(p):
    //   inverse(headWorld) = trans(-p) * rot(conj(q))
    // and the final view ordering is:
    //   P_view = P * trans(-p) * rot(conj(q)) * eyeXf
    const XMVECTOR q     = XMLoadFloat4(&headPose.orientation);
    const XMVECTOR qConj = XMQuaternionConjugate(q);  // unit quaternion → conjugate = inverse
    const XMVECTOR p     = XMLoadFloat3(&headPose.position);
    const XMMATRIX invTr = XMMatrixTranslationFromVector(XMVectorNegate(p));
    const XMMATRIX invRo = XMMatrixRotationQuaternion(qConj);
    return invTr * invRo * eyeXf;
}

XMMATRIX StereoCamera::projection() const {
    return XMMatrixPerspectiveFovLH(fovYRadians, aspect, nearZ, farZ);
}

} // namespace uxspace::spatial
