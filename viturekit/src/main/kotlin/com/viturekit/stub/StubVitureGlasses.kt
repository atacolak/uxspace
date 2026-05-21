package com.viturekit.stub

import com.viturekit.GlassesEvent
import com.viturekit.OrientationMath
import com.viturekit.VitureGlasses
import com.viturekit.VitureGlassesListener
import com.viturekit.internal.ImuParser
import com.viturekit.model.DisplayMode
import com.viturekit.model.EulerAngles
import com.viturekit.model.ImuFrequency
import com.viturekit.model.ImuReading
import com.viturekit.model.Vector3
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

/**
 * A synthetic [VitureGlasses] backend that needs neither hardware nor the VITURE SDK.
 *
 * It simulates the full device lifecycle — attach, permission grant, IMU streaming, display
 * mode switches — and generates a gentle, continuous "head sway": yaw, pitch and roll trace
 * slow sine curves. Encoded payloads go through the real [ImuParser], so the entire pipeline
 * up to [com.viturekit.VitureSession.imu] is exercised end to end.
 *
 * Use it to develop against VitureKit, to run the sample apps without glasses, and as the
 * fake backend in unit tests. Swap in a real-SDK adapter (see
 * `viturekit/integration/RealVitureGlasses.kt.template`) for production.
 */
class StubVitureGlasses(
    private val byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
) : VitureGlasses {

    private val lock = Any()

    @Volatile
    private var listener: VitureGlassesListener? = null

    private var executor: ScheduledExecutorService? = null
    private var imuTask: ScheduledFuture<*>? = null
    private var frequency: ImuFrequency = ImuFrequency.DEFAULT
    private var displayMode: DisplayMode = DisplayMode.MODE_2D

    @Volatile
    private var imuEnabled: Boolean = false

    private val startNanos: Long = System.nanoTime()

    override fun init(): Int {
        synchronized(lock) {
            val exec = freshExecutorLocked()
            exec.execute { listener?.onDeviceEvent(GlassesEvent.Attached) }
            // A short delay mimics the asynchronous USB permission handshake.
            exec.schedule(
                { listener?.onDeviceEvent(GlassesEvent.PermissionGranted) },
                HANDSHAKE_DELAY_MS,
                TimeUnit.MILLISECONDS,
            )
        }
        return VitureGlasses.STATUS_OK
    }

    override fun release() {
        synchronized(lock) {
            imuTask?.cancel(false)
            imuTask = null
            imuEnabled = false
            executor?.shutdownNow()
            executor = null
        }
    }

    override fun setImuEnabled(enabled: Boolean): Int {
        synchronized(lock) {
            val exec = executor ?: return STATUS_NOT_READY
            if (enabled != imuEnabled) {
                imuEnabled = enabled
                if (enabled) {
                    imuTask = exec.scheduleAtFixedRate(
                        ::emitSampleSafely, 0L, frequency.periodNanos, TimeUnit.NANOSECONDS,
                    )
                } else {
                    imuTask?.cancel(false)
                    imuTask = null
                }
            }
        }
        postEvent(GlassesEvent.ImuStateChanged(enabled))
        return VitureGlasses.STATUS_OK
    }

    override fun setImuFrequency(frequency: ImuFrequency): Int {
        synchronized(lock) {
            this.frequency = frequency
            if (imuEnabled) {
                val exec = executor ?: return STATUS_NOT_READY
                imuTask?.cancel(false)
                imuTask = exec.scheduleAtFixedRate(
                    ::emitSampleSafely, 0L, frequency.periodNanos, TimeUnit.NANOSECONDS,
                )
            }
        }
        return VitureGlasses.STATUS_OK
    }

    override fun setDisplayMode(mode: DisplayMode): Int {
        synchronized(lock) { displayMode = mode }
        postEvent(GlassesEvent.DisplayModeChanged(mode))
        return VitureGlasses.STATUS_OK
    }

    override fun setListener(listener: VitureGlassesListener?) {
        this.listener = listener
    }

    /** Caller must hold [lock]. Returns a live executor, recreating it if needed. */
    private fun freshExecutorLocked(): ScheduledExecutorService {
        var exec = executor
        if (exec == null || exec.isShutdown) {
            exec = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "StubVitureGlasses").apply { isDaemon = true }
            }
            executor = exec
        }
        return exec
    }

    private fun postEvent(event: GlassesEvent) {
        val exec = executor ?: return
        if (exec.isShutdown) return
        try {
            exec.execute { listener?.onDeviceEvent(event) }
        } catch (_: RejectedExecutionException) {
            // Executor was shut down between the check and the submit — drop the event.
        }
    }

    private fun emitSampleSafely() {
        try {
            val listener = this.listener ?: return
            val now = System.nanoTime()
            val elapsedSeconds = (now - startNanos) / NANOS_PER_SECOND
            val reading = syntheticReading(now, elapsedSeconds)
            listener.onImuPayload(now, ImuParser.encode(reading, byteOrder))
        } catch (t: Throwable) {
            // A throw here would silently cancel the scheduled task; report instead.
            postEvent(GlassesEvent.Error(t.message ?: "stub IMU failure", code = -1))
        }
    }

    /** A continuous, smooth synthetic head pose at elapsed time [t] seconds. */
    private fun syntheticReading(timestampNanos: Long, t: Double): ImuReading {
        val yaw = YAW_AMPLITUDE * sin(t * YAW_RATE)
        val pitch = PITCH_AMPLITUDE * sin(t * PITCH_RATE + 1.0)
        val roll = ROLL_AMPLITUDE * sin(t * ROLL_RATE + 2.0)
        val euler = EulerAngles(roll.toFloat(), pitch.toFloat(), yaw.toFloat())

        // Gyroscope = analytic derivative of the pose curves, converted to rad/s.
        val gyro = Vector3(
            x = ((PITCH_AMPLITUDE * PITCH_RATE * cos(t * PITCH_RATE + 1.0)) * DEG_TO_RAD).toFloat(),
            y = ((YAW_AMPLITUDE * YAW_RATE * cos(t * YAW_RATE)) * DEG_TO_RAD).toFloat(),
            z = ((ROLL_AMPLITUDE * ROLL_RATE * cos(t * ROLL_RATE + 2.0)) * DEG_TO_RAD).toFloat(),
        )

        // Accelerometer = gravity expressed in the tilted head frame.
        val pitchRad = pitch * DEG_TO_RAD
        val rollRad = roll * DEG_TO_RAD
        val accel = Vector3(
            x = (-GRAVITY * sin(pitchRad)).toFloat(),
            y = (GRAVITY * sin(rollRad)).toFloat(),
            z = (-GRAVITY * cos(pitchRad) * cos(rollRad)).toFloat(),
        )

        return ImuReading(
            timestampNanos = timestampNanos,
            euler = euler,
            quaternion = OrientationMath.eulerToQuaternion(euler),
            accel = accel,
            gyro = gyro,
        )
    }

    companion object {
        private const val HANDSHAKE_DELAY_MS = 150L
        private const val STATUS_NOT_READY = -1

        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val DEG_TO_RAD = Math.PI / 180.0

        // Amplitudes in degrees; rates in radians per second.
        private const val YAW_AMPLITUDE = 30.0
        private const val PITCH_AMPLITUDE = 15.0
        private const val ROLL_AMPLITUDE = 8.0
        private const val YAW_RATE = 0.60
        private const val PITCH_RATE = 0.90
        private const val ROLL_RATE = 0.40
        private const val GRAVITY = 9.81
    }
}
