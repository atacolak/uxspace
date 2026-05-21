package com.viturekit

import com.viturekit.model.ImuFrequency
import java.nio.ByteOrder

/**
 * Tunables for a [VitureSession].
 *
 * Every field has a sensible default, so `VitureConfig()` is a valid starting point.
 * Construct with named arguments from Kotlin, or `copy()` an existing instance.
 */
data class VitureConfig(

    /** IMU sample rate requested from the device once connected. */
    val imuFrequency: ImuFrequency = ImuFrequency.DEFAULT,

    /** Enable the IMU stream automatically as soon as the glasses connect. */
    val autoEnableImu: Boolean = true,

    /**
     * Extra buffer slots on the IMU `SharedFlow`.
     *
     * With the default [dropOldestImuSamples] policy a slow collector drops the oldest
     * pending sample instead of stalling the SDK callback thread. Larger buffers smooth
     * bursts at the cost of added latency. Must be `>= 0`.
     */
    val imuBufferCapacity: Int = 1,

    /**
     * How to handle a full IMU buffer when a collector cannot keep up with the device rate.
     *
     * `true` (default) drops the oldest buffered sample, so a renderer always sees the
     * freshest pose. `false` drops the just-arrived sample instead, preserving an older but
     * uninterrupted run of samples. Either way the SDK callback thread is never blocked.
     */
    val dropOldestImuSamples: Boolean = true,

    /**
     * Pause IMU streaming while the bound `Lifecycle` is stopped.
     *
     * When `true` (default), [VitureSession.bindToLifecycle] disables the IMU on `onStop`
     * and re-enables it on `onStart`, so a backgrounded app neither drains battery nor
     * leaks the device handle.
     */
    val pauseImuWhenStopped: Boolean = true,

    /**
     * Byte order of the SDK's raw IMU payload.
     *
     * Must match whatever the active [VitureGlasses] backend emits.
     * [com.viturekit.stub.StubVitureGlasses] uses little-endian.
     */
    val imuByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
) {
    init {
        require(imuBufferCapacity >= 0) { "imuBufferCapacity must be >= 0, was $imuBufferCapacity" }
    }
}
