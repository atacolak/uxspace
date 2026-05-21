package com.viturekit.model

/**
 * A unit quaternion describing head orientation, component order `w, x, y, z`.
 *
 * Prefer this over [EulerAngles] for rendering math — it is free of gimbal lock and
 * composes cleanly. It is populated on an [ImuReading] only when the SDK payload carries
 * quaternion data.
 */
data class Quaternion(
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float,
) {
    companion object {
        /** The identity rotation. */
        val IDENTITY: Quaternion = Quaternion(1f, 0f, 0f, 0f)
    }
}
