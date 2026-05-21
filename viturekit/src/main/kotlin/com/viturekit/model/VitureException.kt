package com.viturekit.model

/**
 * Thrown when a [com.viturekit.VitureSession] operation cannot be completed.
 *
 * Routine, recoverable problems (a failed mode switch, a transient SDK error) are reported
 * through [VitureEvent.Error] instead; this exception is reserved for programming errors
 * and unrecoverable backend failures.
 */
class VitureException @JvmOverloads constructor(
    message: String,
    /** SDK-specific status code, or [NO_CODE] when not applicable. */
    val code: Int = NO_CODE,
    cause: Throwable? = null,
) : Exception(message, cause) {

    companion object {
        /** Sentinel used when no SDK status code is available. */
        const val NO_CODE: Int = Int.MIN_VALUE
    }
}
