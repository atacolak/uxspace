package com.viturekit.model

/**
 * Head orientation expressed as intrinsic Tait-Bryan angles, in degrees.
 *
 * In the VITURE IMU frame:
 *  - [yawDeg] turns the head left (negative) / right (positive),
 *  - [pitchDeg] tilts it down (negative) / up (positive),
 *  - [rollDeg] tips it counter-clockwise (negative) / clockwise (positive).
 *
 * This is a 3DoF orientation only — the IMU reports no position.
 */
data class EulerAngles(
    val rollDeg: Float,
    val pitchDeg: Float,
    val yawDeg: Float,
) {
    companion object {
        /** The identity orientation (looking straight ahead, level). */
        val ZERO: EulerAngles = EulerAngles(0f, 0f, 0f)
    }
}
