package com.vspace.glasses

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
 * and the matching tracking path is used. Recentre is done in VSpace: the first pose becomes
 * the reference, so wherever the user is looking when tracking starts becomes "straight ahead".
 *
 * It knows nothing of the renderer or the rest of VSpace — `:app` wires [onPose] to the
 * camera — so the module is a self-contained, swappable head-tracking provider.
 */
class HeadTracking(
    context: Context,
    /** Receives each recentred head-orientation quaternion as (w, x, y, z). */
    private val onPose: (Float, Float, Float, Float) -> Unit,
) {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vspace-tracking") }

    private var usb: GlassesUsb? = null
    private var connection: UsbDeviceConnection? = null

    @Volatile private var polling = false
    private var pollThread: Thread? = null

    // Guards against a double start(): one HeadTracking owns one SDK session, and the native
    // SDK handle is a process-wide singleton.
    @Volatile private var started = false

    // Reference orientation for recentre; the first pose fills it in.
    @Volatile private var haveRef = false
    private var refW = 1f
    private var refX = 0f
    private var refY = 0f
    private var refZ = 0f

    /** Begin tracking: locate the glasses on USB and request permission. */
    fun start() {
        if (started) {
            Log.w(TAG, "start() ignored — head tracking already started")
            return
        }
        started = true
        val glassesUsb = GlassesUsb(appContext, ::onUsbOpened, ::onUsbDenied)
        usb = glassesUsb
        val device = glassesUsb.find()
        if (device == null) {
            Log.i(TAG, "no VITURE glasses found on USB — head tracking off")
            return
        }
        Log.i(TAG, "found glasses, pid=0x${device.productId.toString(16)}")
        glassesUsb.open(device)
    }

    /** Make the current head direction the new centre. */
    fun recenter() {
        haveRef = false
    }

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
        pollThread = Thread {
            while (polling) {
                if (NativeGlasses.isPoseFresh()) {
                    val pose = NativeGlasses.getPose()
                    // pose[3..6] is the orientation quaternion (w, x, y, z) for every device.
                    if (pose.size >= 7) feedPose(pose[3], pose[4], pose[5], pose[6])
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            name = "vspace-pose"
            start()
        }
    }

    /** Recentre against the reference orientation, then deliver the result via [onPose]. */
    private fun feedPose(w: Float, x: Float, y: Float, z: Float) {
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
        const val TAG = "VSpace/Tracking"
        const val POLL_INTERVAL_MS = 8L  // ~120 Hz
    }
}
