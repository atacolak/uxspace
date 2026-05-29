package com.uxspace.glasses

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import java.util.concurrent.Executors

/**
 * Drives head tracking: finds the glasses on USB, runs the native SDK lifecycle, polls head
 * pose, and delivers a recentred orientation quaternion via [onPose] so the workspace camera
 * can stay world-fixed.
 *
 * Capability is detected at runtime — on-glasses native DOF, Carina VIO, or Gen1/2 host IMU —
 * and the matching tracking path is used. Recentre is done in UxSpace: the first pose becomes
 * the reference, so wherever the user is looking when tracking starts becomes "straight ahead".
 *
 * It knows nothing of the renderer or the rest of UxSpace — `:app` wires [onPose] to the
 * camera — so the module is a self-contained, swappable head-tracking provider.
 */
class HeadTracking(
    context: Context,
    /** Receives each recentred head-orientation quaternion as (w, x, y, z). */
    private val onPose: (Float, Float, Float, Float) -> Unit,
) {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "uxspace-tracking") }

    private var usb: GlassesUsb? = null
    private var connection: UsbDeviceConnection? = null

    @Volatile private var polling = false
    private var pollThread: Thread? = null

    // Guards against a double start(): one HeadTracking owns one SDK session, and the native
    // SDK handle is a process-wide singleton.
    @Volatile private var started = false

    // Pose-stream watchdog: tracks the wall-clock time of the last [feedPose] callback and
    // declares the stream dead when it goes silent for [STREAM_STALL_TIMEOUT_MS]. The
    // glasses USB endpoint can be killed externally (e.g. Bluetooth radio activation
    // racing the USB host on some OEMs) — Carina logs LIBUSB_ERROR_TIMEOUT but keeps
    // its poll thread alive, so without this watchdog the app keeps claiming DOF is live.
    @Volatile private var lastPoseAtMs = 0L
    @Volatile private var streaming = false

    /** Fires when the pose stream starts or stops. Posted from the poll thread. */
    var onStreamingChanged: ((streaming: Boolean) -> Unit)? = null

    // Reference orientation for recentre; the first pose fills it in.
    @Volatile private var haveRef = false
    private var refW = 1f
    private var refX = 0f
    private var refY = 0f
    private var refZ = 0f

    /**
     * Begin tracking: locate the glasses on USB and request permission. Idempotent — if
     * tracking is already up this is a no-op; if a previous call missed the device because
     * USB enumeration lagged behind the display, calling again retries. The expected retry
     * trigger is `USB_DEVICE_ATTACHED` in [com.uxspace.MainActivity].
     */
    fun start() {
        if (started) {
            Log.d(TAG, "start() ignored — head tracking already started")
            return
        }
        val glassesUsb = usb ?: GlassesUsb(appContext, ::onUsbOpened, ::onUsbDenied).also { usb = it }
        val device = glassesUsb.find()
        if (device == null) {
            // Don't latch — leave [started] false so a later USB_DEVICE_ATTACHED
            // re-entry can succeed. The display side of the glasses can come up before
            // the USB IMU endpoint enumerates; without retry that brief race kills DOF
            // for the whole session.
            Log.i(TAG, "no VITURE glasses found on USB — head tracking off (will retry on USB attach)")
            return
        }
        started = true
        Log.i(TAG, "found glasses, pid=0x${device.productId.toString(16)}")
        glassesUsb.open(device)
    }

    /** Whether [start] has successfully claimed the glasses' USB device for this instance. */
    fun isStarted(): Boolean = started

    /** Stop tracking and release the SDK + USB. */
    fun stop() {
        started = false
        polling = false
        pollThread?.interrupt()
        pollThread = null
        worker.execute {
            runCatching { NativeGlasses.stopCarinaPollThread() }
            runCatching { NativeGlasses.stop() }
            runCatching { NativeGlasses.shutdown() }
            runCatching { NativeGlasses.destroy() }
            runCatching { connection?.close() }
            connection = null
        }
        usb?.release()
        usb = null
    }

    private fun onUsbDenied() {
        Log.w(TAG, "USB permission denied — head tracking off")
    }

    private fun onUsbOpened(device: UsbDevice, connection: UsbDeviceConnection) {
        this.connection = connection
        val pid = device.productId
        val fd = connection.fileDescriptor
        worker.execute { startSdk(pid, fd) }
    }

    /** Runs on [worker]: the SDK lifecycle, then the capability-specific tracking path. */
    private fun startSdk(pid: Int, fd: Int) {
        Log.i(TAG, "libglasses ${runCatching { NativeGlasses.getVersion() }.getOrDefault("?")}")
        if (!NativeGlasses.create(pid, fd)) {
            Log.e(TAG, "NativeGlasses.create failed")
            return
        }
        val nativeDof = NativeGlasses.isProductSupportNativeDof(pid)
        val type = NativeGlasses.getDeviceType()
        val carina = type == NativeGlasses.DEVICE_TYPE_CARINA

        // Carina needs its DOF type set after create and before initialize; 3DOF is enough
        // for orientation-only head tracking.
        if (carina && !nativeDof) {
            NativeGlasses.setDofTypeCarina(false)
        }
        NativeGlasses.registerStateCallback()
        NativeGlasses.initialize()
        NativeGlasses.start()

        when {
            nativeDof -> {
                Log.i(TAG, "tracking path: native on-glasses DOF")
                NativeGlasses.setupNativeDofDevice()
            }
            carina -> {
                Log.i(TAG, "tracking path: Carina VIO (deviceType=$type)")
                NativeGlasses.startCarinaPollThread()
            }
            else -> {
                Log.i(TAG, "tracking path: Gen1/2 host IMU (deviceType=$type)")
                NativeGlasses.openImu()
            }
        }
        startPolling()
    }

    private fun startPolling() {
        polling = true
        lastPoseAtMs = 0L
        streaming = false
        pollThread = Thread {
            while (polling) {
                if (NativeGlasses.isPoseFresh()) {
                    val pose = NativeGlasses.getPose()
                    // pose[3..6] is the orientation quaternion (w, x, y, z) for every device.
                    if (pose.size >= 7) feedPose(pose[3], pose[4], pose[5], pose[6])
                }
                checkStreamWatchdog()
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            name = "uxspace-pose"
            start()
        }
    }

    /**
     * Detect a stalled pose stream. Once [streaming] has been latched true (first pose
     * arrived), look for a quiet gap of [STREAM_STALL_TIMEOUT_MS] and flip it back to
     * false — the USB endpoint or the SDK has stopped delivering data. The corresponding
     * recover transition fires from [feedPose] the next time a pose arrives.
     */
    private fun checkStreamWatchdog() {
        if (!streaming) return
        val now = System.currentTimeMillis()
        if (lastPoseAtMs == 0L) return
        val sinceMs = now - lastPoseAtMs
        if (sinceMs > STREAM_STALL_TIMEOUT_MS) {
            streaming = false
            Log.w(TAG, "pose stream stalled — no pose for ${sinceMs}ms (DOF lost)")
            runCatching { onStreamingChanged?.invoke(false) }
        }
    }

    /** Recentre against the reference orientation, then deliver the result via [onPose]. */
    private fun feedPose(w: Float, x: Float, y: Float, z: Float) {
        lastPoseAtMs = System.currentTimeMillis()
        if (!streaming) {
            streaming = true
            Log.i(TAG, "pose stream live (DOF up)")
            runCatching { onStreamingChanged?.invoke(true) }
        }
        if (!haveRef) {
            refW = w; refX = x; refY = y; refZ = z
            haveRef = true
        }
        // effective = conjugate(reference) * raw
        val cw = refW; val cx = -refX; val cy = -refY; val cz = -refZ
        val ew = cw * w - cx * x - cy * y - cz * z
        val ex = cw * x + cx * w + cy * z - cz * y
        val ey = cw * y - cx * z + cy * w + cz * x
        val ez = cw * z + cx * y - cy * x + cz * w
        onPose(ew, ex, ey, ez)
    }

    private companion object {
        const val TAG = "UxSpace/Tracking"
        const val POLL_INTERVAL_MS = 8L  // ~120 Hz
        /** A live pose stream emits at >=60 Hz; a 1.5 s gap is far past any normal lull
         *  and is the threshold for declaring DOF dead. */
        const val STREAM_STALL_TIMEOUT_MS = 1500L
    }
}
