package com.viturekit.internal

import com.viturekit.model.EulerAngles
import com.viturekit.model.ImuReading
import com.viturekit.model.Quaternion
import com.viturekit.model.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteOrder

class ImuParserTest {

    private val parser = ImuParser(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun parsesEulerOnlyPayload() {
        val reading = ImuReading(timestampNanos = 123L, euler = EulerAngles(1f, 2f, 3f))
        val bytes = ImuParser.encode(reading)

        assertEquals("euler-only payload is 12 bytes", 12, bytes.size)
        val decoded = parser.parse(123L, bytes)
        assertNotNull(decoded)
        assertEquals(EulerAngles(1f, 2f, 3f), decoded!!.euler)
        assertEquals(123L, decoded.timestampNanos)
        assertNull("no quaternion block was encoded", decoded.quaternion)
        assertNull(decoded.accel)
        assertNull(decoded.gyro)
    }

    @Test
    fun roundTripsFullPayload() {
        val reading = ImuReading(
            timestampNanos = 9L,
            euler = EulerAngles(-10f, 20f, 45f),
            quaternion = Quaternion(w = 0.5f, x = 0.5f, y = 0.5f, z = 0.5f),
            accel = Vector3(0f, 0f, -9.81f),
            gyro = Vector3(0.1f, -0.2f, 0.3f),
        )

        val decoded = parser.parse(9L, ImuParser.encode(reading))
        assertNotNull(decoded)
        assertEquals(reading.euler, decoded!!.euler)
        assertEquals(reading.quaternion, decoded.quaternion)
        assertEquals(reading.accel, decoded.accel)
        assertEquals(reading.gyro, decoded.gyro)
    }

    @Test
    fun rejectsPayloadTooShortForEuler() {
        assertNull(parser.parse(0L, ByteArray(11)))
        assertNull(parser.parse(0L, ByteArray(0)))
    }

    @Test
    fun honoursByteOrder() {
        val reading = ImuReading(timestampNanos = 0L, euler = EulerAngles(1f, 0f, 0f))
        val bigEndianBytes = ImuParser.encode(reading, ByteOrder.BIG_ENDIAN)

        val matched = ImuParser(ByteOrder.BIG_ENDIAN).parse(0L, bigEndianBytes)
        assertEquals(1f, matched!!.euler.rollDeg, 1e-6f)

        // Decoding big-endian bytes with a little-endian parser must NOT recover 1.0.
        val mismatched = ImuParser(ByteOrder.LITTLE_ENDIAN).parse(0L, bigEndianBytes)
        assertTrue(mismatched!!.euler.rollDeg != 1f)
    }
}
