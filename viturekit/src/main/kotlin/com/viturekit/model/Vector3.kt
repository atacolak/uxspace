package com.viturekit.model

/**
 * An immutable 3-component vector.
 *
 * Used for raw accelerometer samples (m/s²) and gyroscope samples (rad/s) carried by
 * an [ImuReading].
 */
data class Vector3(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    /** Euclidean length of the vector. */
    val magnitude: Float
        get() = kotlin.math.sqrt(x * x + y * y + z * z)

    companion object {
        /** The zero vector. */
        val ZERO: Vector3 = Vector3(0f, 0f, 0f)
    }
}
