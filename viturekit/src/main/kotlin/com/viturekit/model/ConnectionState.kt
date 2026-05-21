package com.viturekit.model

/**
 * The state of the link between the host device and the VITURE glasses.
 *
 * Exposed as a `StateFlow` by [com.viturekit.VitureSession.connectionState]; it is the
 * single source of truth for whether IMU and control calls will have any effect.
 */
enum class ConnectionState {
    /** No glasses attached, or the session has not been started. */
    DISCONNECTED,

    /** A USB device is present; waiting for the user to grant the permission dialog. */
    PERMISSION_REQUIRED,

    /** The SDK handshake is in progress. */
    CONNECTING,

    /** Glasses are connected — IMU streaming and device control are available. */
    CONNECTED,

    /** The connection attempt failed or the active link dropped unexpectedly. */
    ERROR,
}
