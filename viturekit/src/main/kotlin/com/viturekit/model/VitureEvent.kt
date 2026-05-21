package com.viturekit.model

/**
 * A discrete device-side event, surfaced by [com.viturekit.VitureSession.events].
 *
 * Continuous state (connection, display mode, IMU on/off) is better observed through the
 * dedicated `StateFlow`s on [com.viturekit.VitureSession]; this stream is for one-shot
 * notifications a UI may want to react to once.
 */
sealed interface VitureEvent {

    /** Glasses were attached and the SDK handshake completed. */
    data object Connected : VitureEvent

    /** Glasses were detached, or the link dropped. */
    data object Disconnected : VitureEvent

    /** The display mode changed — by an API call or a hardware button on the glasses. */
    data class DisplayModeChanged(val mode: DisplayMode) : VitureEvent

    /** The IMU stream was enabled or disabled. */
    data class ImuStateChanged(val enabled: Boolean) : VitureEvent

    /**
     * The set of system displays changed while connected — most commonly when the user
     * enters or leaves a Samsung DeX session. Consumers should re-query display metrics
     * and per-eye viewports. [dexActive] is the DeX state at the moment of the event.
     */
    data class DisplaysChanged(val dexActive: Boolean) : VitureEvent

    /** A non-fatal error reported by the SDK. The connection is not necessarily lost. */
    data class Error(val message: String, val code: Int) : VitureEvent
}
