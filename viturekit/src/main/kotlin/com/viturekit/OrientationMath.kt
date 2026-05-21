package com.viturekit

import com.viturekit.model.EulerAngles
import com.viturekit.model.Quaternion
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Conversions between the orientation representations VitureKit exposes.
 *
 * The convention is an intrinsic Tait-Bryan **Z-Y-X** sequence: yaw about Z, then pitch
 * about Y, then roll about X. [eulerToQuaternion] and [quaternionToEuler] are exact
 * inverses of each other (away from the pitch = ±90° gimbal-lock singularity).
 */
object OrientationMath {

    private const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()
    private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()

    /** Convert [EulerAngles] (degrees) to a unit [Quaternion]. */
    fun eulerToQuaternion(euler: EulerAngles): Quaternion {
        val halfRoll = euler.rollDeg * DEG_TO_RAD * 0.5f
        val halfPitch = euler.pitchDeg * DEG_TO_RAD * 0.5f
        val halfYaw = euler.yawDeg * DEG_TO_RAD * 0.5f

        val cr = cos(halfRoll); val sr = sin(halfRoll)
        val cp = cos(halfPitch); val sp = sin(halfPitch)
        val cy = cos(halfYaw); val sy = sin(halfYaw)

        return Quaternion(
            w = cr * cp * cy + sr * sp * sy,
            x = sr * cp * cy - cr * sp * sy,
            y = cr * sp * cy + sr * cp * sy,
            z = cr * cp * sy - sr * sp * cy,
        )
    }

    /** Convert a [Quaternion] to [EulerAngles] (degrees). */
    fun quaternionToEuler(q: Quaternion): EulerAngles {
        // Roll (X axis).
        val sinrCosp = 2f * (q.w * q.x + q.y * q.z)
        val cosrCosp = 1f - 2f * (q.x * q.x + q.y * q.y)
        val roll = atan2(sinrCosp, cosrCosp)

        // Pitch (Y axis), clamped to guard against asin() going out of domain.
        val sinp = (2f * (q.w * q.y - q.z * q.x)).coerceIn(-1f, 1f)
        val pitch = asin(sinp)

        // Yaw (Z axis).
        val sinyCosp = 2f * (q.w * q.z + q.x * q.y)
        val cosyCosp = 1f - 2f * (q.y * q.y + q.z * q.z)
        val yaw = atan2(sinyCosp, cosyCosp)

        return EulerAngles(
            rollDeg = roll * RAD_TO_DEG,
            pitchDeg = pitch * RAD_TO_DEG,
            yawDeg = yaw * RAD_TO_DEG,
        )
    }
}
