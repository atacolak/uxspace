package com.viturekit.model

import kotlin.math.abs

/**
 * IMU sample rates the VITURE SDK can be asked to deliver.
 *
 * The device produces callbacks at roughly the requested rate; [HZ_120] is the practical
 * maximum on current hardware. Higher rates trade battery and CPU for lower motion latency.
 */
enum class ImuFrequency(val hz: Int) {
    HZ_60(60),
    HZ_90(90),
    HZ_120(120),
    HZ_240(240);

    /** Nominal interval between samples, in nanoseconds. */
    val periodNanos: Long
        get() = 1_000_000_000L / hz

    companion object {
        /** The rate used when a [com.viturekit.VitureConfig] does not specify one. */
        val DEFAULT: ImuFrequency = HZ_60

        /** The supported frequency closest to [hz]. */
        fun nearest(hz: Int): ImuFrequency = entries.minByOrNull { abs(it.hz - hz) } ?: DEFAULT
    }
}
