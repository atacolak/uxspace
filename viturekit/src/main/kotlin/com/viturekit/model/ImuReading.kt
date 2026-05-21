package com.viturekit.model

/**
 * A single IMU sample, published by [com.viturekit.VitureSession.imu].
 *
 * [euler] is always present. [quaternion], [accel] and [gyro] are populated only when the
 * underlying SDK payload carries them — which blocks are present depends on the device and
 * the active [com.viturekit.VitureGlasses] backend.
 */
data class ImuReading(
    /** Device timestamp of the sample, in nanoseconds (monotonic, not wall-clock). */
    val timestampNanos: Long,
    /** 3DoF orientation as Euler angles. Always present. */
    val euler: EulerAngles,
    /** 3DoF orientation as a quaternion, or `null` when the payload carried none. */
    val quaternion: Quaternion? = null,
    /** Raw accelerometer sample in m/s², or `null` when unavailable. */
    val accel: Vector3? = null,
    /** Raw gyroscope sample in rad/s, or `null` when unavailable. */
    val gyro: Vector3? = null,
)
