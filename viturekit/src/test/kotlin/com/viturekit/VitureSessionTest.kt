package com.viturekit

import com.viturekit.internal.NoOpDisplayWatcher
import com.viturekit.model.ConnectionState
import com.viturekit.model.DisplayMode
import com.viturekit.stub.StubVitureGlasses
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Drives [VitureSession] against [StubVitureGlasses] — exercising the full pipeline from a
 * synthetic backend through payload parsing to the public Flows, with no Android framework.
 *
 * Each test body is wrapped in `runBlocking` inside a block-body function so the method
 * return type stays `Unit`, as JUnit requires.
 */
class VitureSessionTest {

    private fun newSession(config: VitureConfig = VitureConfig()): VitureSession =
        VitureSession(StubVitureGlasses(), config, NoOpDisplayWatcher())

    @Test
    fun connectReachesConnectedAndStreamsImu() {
        runBlocking {
            val session = newSession()
            try {
                session.connect()
                withTimeout(TIMEOUT_MS) {
                    session.connectionState.first { it == ConnectionState.CONNECTED }
                }
                val reading = withTimeout(TIMEOUT_MS) { session.imu.first() }
                assertNotNull("stub provides a quaternion block", reading.quaternion)
                assertNotNull("stub provides an accelerometer block", reading.accel)
                assertNotNull("stub provides a gyroscope block", reading.gyro)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun disconnectReturnsToDisconnected() {
        runBlocking {
            val session = newSession()
            try {
                session.connect()
                withTimeout(TIMEOUT_MS) {
                    session.connectionState.first { it == ConnectionState.CONNECTED }
                }
                session.disconnect()
                withTimeout(TIMEOUT_MS) {
                    session.connectionState.first { it == ConnectionState.DISCONNECTED }
                }
                assertFalse(session.imuEnabled.value)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun setDisplayModeIsReflectedInState() {
        runBlocking {
            val session = newSession()
            try {
                session.connect()
                withTimeout(TIMEOUT_MS) {
                    session.connectionState.first { it == ConnectionState.CONNECTED }
                }
                session.setDisplayMode(DisplayMode.MODE_3D)
                withTimeout(TIMEOUT_MS) {
                    session.displayMode.first { it == DisplayMode.MODE_3D }
                }
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun imuStaysOffWhenAutoEnableDisabled() {
        runBlocking {
            val session = newSession(VitureConfig(autoEnableImu = false))
            try {
                session.connect()
                withTimeout(TIMEOUT_MS) {
                    session.connectionState.first { it == ConnectionState.CONNECTED }
                }
                delay(QUIESCE_MS)
                assertFalse("autoEnableImu=false must leave the IMU off", session.imuEnabled.value)
            } finally {
                session.close()
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 4_000L
        const val QUIESCE_MS = 400L
    }
}
