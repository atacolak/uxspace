package com.viturekit.model

/**
 * The glasses display mode.
 *
 * [MODE_3D] is required for stereo (per-eye) rendering: the glasses split the incoming
 * frame side-by-side and route each half to one eye.
 */
enum class DisplayMode {
    /** Standard flat 2D mirroring — the same image to both eyes. */
    MODE_2D,

    /** Side-by-side 3D — left half to the left eye, right half to the right eye. */
    MODE_3D,
}
