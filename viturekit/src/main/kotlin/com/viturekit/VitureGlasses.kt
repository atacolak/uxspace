package com.viturekit

import com.viturekit.model.DisplayMode
import com.viturekit.model.ImuFrequency

/**
 * The integration seam between VitureKit and a backend that actually talks to the glasses.
 *
 * VitureKit never references the closed-source VITURE SDK directly. Instead [VitureSession]
 * drives this interface, and a concrete implementation bridges it to a real backend:
 *
 *  - [com.viturekit.stub.StubVitureGlasses] — a synthetic implementation for development,
 *    unit tests, and running the sample apps without hardware.
 *  - A hand-written adapter over the official VITURE `ArManager`. See
 *    `viturekit/integration/RealVitureGlasses.kt.template` for a ready-to-fill skeleton;
 *    drop in the official `.aar`, copy the template to `src/main`, and rename it to `.kt`.
 *
 * Implementations need not be thread-safe: [VitureSession] confines every call to a single
 * background dispatcher. Callbacks delivered through [VitureGlassesListener] may, however,
 * arrive on any thread.
 */
interface VitureGlasses {

    /**
     * Initialise the backend and begin observing USB attach/detach.
     *
     * @return an SDK status code; `0` ([STATUS_OK]) means success.
     */
    fun init(): Int

    /** Release all native resources and USB handles. Must be safe to call repeatedly. */
    fun release()

    /**
     * Enable or disable IMU callbacks.
     *
     * @return an SDK status code; `0` ([STATUS_OK]) means success.
     */
    fun setImuEnabled(enabled: Boolean): Int

    /**
     * Request an IMU sample rate.
     *
     * @return an SDK status code; `0` ([STATUS_OK]) means success.
     */
    fun setImuFrequency(frequency: ImuFrequency): Int

    /**
     * Switch the glasses display mode.
     *
     * @return an SDK status code; `0` ([STATUS_OK]) means success.
     */
    fun setDisplayMode(mode: DisplayMode): Int

    /** Register (or, with `null`, clear) the listener that receives samples and events. */
    fun setListener(listener: VitureGlassesListener?)

    companion object {
        /** The status code every [VitureGlasses] call returns on success. */
        const val STATUS_OK: Int = 0
    }
}

/**
 * Callbacks delivered by a [VitureGlasses] backend.
 *
 * Implementations of [VitureGlasses] invoke these from their own callback thread;
 * [VitureSession] is responsible for republishing them onto its `Flow`s safely.
 */
interface VitureGlassesListener {

    /**
     * A raw IMU payload arrived.
     *
     * [payload] is the SDK's native byte layout — VitureKit decodes it internally with
     * [com.viturekit.internal.ImuParser]. [timestampNanos] is a monotonic device timestamp.
     */
    fun onImuPayload(timestampNanos: Long, payload: ByteArray)

    /** A device-side event occurred (attach, detach, permission, mode change, error). */
    fun onDeviceEvent(event: GlassesEvent)
}

/**
 * Low-level device events emitted by a [VitureGlasses] backend.
 *
 * [VitureSession] folds these into its [com.viturekit.model.ConnectionState] machine and,
 * where relevant, re-publishes them as higher-level [com.viturekit.model.VitureEvent]s.
 */
sealed interface GlassesEvent {
    /** A VITURE USB device was attached. */
    data object Attached : GlassesEvent

    /** The USB device was detached. */
    data object Detached : GlassesEvent

    /** The OS is showing (or about to show) the USB permission dialog. */
    data object PermissionRequired : GlassesEvent

    /** The user granted USB permission; the backend is ready to use. */
    data object PermissionGranted : GlassesEvent

    /** The user denied USB permission. */
    data object PermissionDenied : GlassesEvent

    /** The display mode changed (API call or hardware button). */
    data class DisplayModeChanged(val mode: DisplayMode) : GlassesEvent

    /** IMU streaming was turned on or off. */
    data class ImuStateChanged(val enabled: Boolean) : GlassesEvent

    /** A non-fatal backend error. */
    data class Error(val message: String, val code: Int) : GlassesEvent
}
