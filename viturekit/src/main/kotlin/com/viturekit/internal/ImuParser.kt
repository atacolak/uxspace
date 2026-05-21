package com.viturekit.internal

import com.viturekit.model.EulerAngles
import com.viturekit.model.ImuReading
import com.viturekit.model.Quaternion
import com.viturekit.model.Vector3
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes raw VITURE IMU byte payloads into [ImuReading]s.
 *
 * The payload is a packed sequence of 32-bit floats; trailing blocks are simply omitted on
 * devices or modes that do not provide them:
 *
 * | offset | bytes | block        | fields              |
 * |--------|-------|--------------|---------------------|
 * | 0      | 12    | euler        | roll, pitch, yaw    |
 * | 12     | 16    | quaternion   | x, y, z, w          |
 * | 28     | 12    | accelerometer| x, y, z             |
 * | 40     | 12    | gyroscope    | x, y, z             |
 *
 * The byte order is configurable because it must match whatever the concrete
 * [com.viturekit.VitureGlasses] backend emits.
 */
internal class ImuParser(private val byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN) {

    /**
     * Decode [payload] captured at [timestampNanos].
     *
     * @return the decoded reading, or `null` if [payload] is too short to even hold the
     *   mandatory euler block.
     */
    fun parse(timestampNanos: Long, payload: ByteArray): ImuReading? {
        if (payload.size < EULER_BYTES) return null
        val buffer = ByteBuffer.wrap(payload).order(byteOrder)

        val euler = EulerAngles(
            rollDeg = buffer.float,
            pitchDeg = buffer.float,
            yawDeg = buffer.float,
        )

        val quaternion = if (payload.size >= EULER_BYTES + QUAT_BYTES) {
            val x = buffer.float
            val y = buffer.float
            val z = buffer.float
            val w = buffer.float
            Quaternion(w = w, x = x, y = y, z = z)
        } else {
            null
        }

        val accel = if (quaternion != null && payload.size >= EULER_BYTES + QUAT_BYTES + VEC_BYTES) {
            Vector3(buffer.float, buffer.float, buffer.float)
        } else {
            null
        }

        val gyro = if (accel != null && payload.size >= EULER_BYTES + QUAT_BYTES + 2 * VEC_BYTES) {
            Vector3(buffer.float, buffer.float, buffer.float)
        } else {
            null
        }

        return ImuReading(timestampNanos, euler, quaternion, accel, gyro)
    }

    companion object {
        private const val EULER_BYTES = 12
        private const val QUAT_BYTES = 16
        private const val VEC_BYTES = 12

        /**
         * Encode an [ImuReading] back into the wire layout described above.
         *
         * Used by [com.viturekit.stub.StubVitureGlasses] and by tests. A block is written
         * only when it and every block before it is present, keeping the layout contiguous.
         */
        fun encode(
            reading: ImuReading,
            byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        ): ByteArray {
            val hasQuat = reading.quaternion != null
            val hasAccel = hasQuat && reading.accel != null
            val hasGyro = hasAccel && reading.gyro != null

            var size = EULER_BYTES
            if (hasQuat) size += QUAT_BYTES
            if (hasAccel) size += VEC_BYTES
            if (hasGyro) size += VEC_BYTES

            val buffer = ByteBuffer.allocate(size).order(byteOrder)
            buffer.putFloat(reading.euler.rollDeg)
            buffer.putFloat(reading.euler.pitchDeg)
            buffer.putFloat(reading.euler.yawDeg)
            if (hasQuat) {
                val q = reading.quaternion!!
                buffer.putFloat(q.x)
                buffer.putFloat(q.y)
                buffer.putFloat(q.z)
                buffer.putFloat(q.w)
            }
            if (hasAccel) {
                val a = reading.accel!!
                buffer.putFloat(a.x)
                buffer.putFloat(a.y)
                buffer.putFloat(a.z)
            }
            if (hasGyro) {
                val g = reading.gyro!!
                buffer.putFloat(g.x)
                buffer.putFloat(g.y)
                buffer.putFloat(g.z)
            }
            return buffer.array()
        }
    }
}
