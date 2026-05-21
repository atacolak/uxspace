package com.viturekit

import com.viturekit.model.EulerAngles
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class OrientationMathTest {

    @Test
    fun identityEulerMapsToIdentityQuaternion() {
        val q = OrientationMath.eulerToQuaternion(EulerAngles.ZERO)
        assertEquals(1f, q.w, TOL)
        assertEquals(0f, q.x, TOL)
        assertEquals(0f, q.y, TOL)
        assertEquals(0f, q.z, TOL)
    }

    @Test
    fun eulerSurvivesRoundTripThroughQuaternion() {
        // Pitch is kept well clear of ±90° to avoid the gimbal-lock singularity.
        val samples = listOf(
            EulerAngles(0f, 0f, 0f),
            EulerAngles(10f, 20f, 30f),
            EulerAngles(-15f, -40f, 80f),
            EulerAngles(30f, 45f, -60f),
            EulerAngles(5f, -10f, 170f),
        )
        for (euler in samples) {
            val back = OrientationMath.quaternionToEuler(OrientationMath.eulerToQuaternion(euler))
            assertEquals("roll of $euler", euler.rollDeg, back.rollDeg, TOL_DEG)
            assertEquals("pitch of $euler", euler.pitchDeg, back.pitchDeg, TOL_DEG)
            assertEquals("yaw of $euler", euler.yawDeg, back.yawDeg, TOL_DEG)
        }
    }

    @Test
    fun producedQuaternionsAreUnitLength() {
        val euler = EulerAngles(33f, -21f, 142f)
        val q = OrientationMath.eulerToQuaternion(euler)
        val norm = sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z)
        assertEquals(1f, norm, TOL)
    }

    private companion object {
        const val TOL = 1e-5f
        const val TOL_DEG = 1e-2f
    }
}
