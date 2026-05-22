package com.vspace.glasses

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

/**
 * Finds the external display the VITURE glasses present themselves as.
 *
 * The glasses are a presentation-category display; VSpace shows a `Presentation` on it so the
 * glasses show the workspace while the phone keeps its own screen. VSpace's own virtual
 * screens (the off-screen buffers that host launched apps) are *also* presentation displays,
 * so they are filtered out by name — picking one of those was the bug that broke this before.
 */
object GlassesDisplay {

    private const val TAG = "VSpace/Display"
    private const val VSPACE_SCREEN_PREFIX = "vspace-screen"

    /** The glasses' display, or `null` when they are not connected. */
    fun find(context: Context): Display? {
        val displayManager =
            context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        Log.i(TAG, "displays visible to VSpace:")
        displayManager.displays.forEach {
            Log.i(
                TAG,
                "  id=${it.displayId} '${it.name}' " +
                    "${it.mode.physicalWidth}x${it.mode.physicalHeight} state=${it.state}",
            )
        }

        val glasses = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull {
                !it.name.startsWith(VSPACE_SCREEN_PREFIX) && it.state != Display.STATE_OFF
            }
        Log.i(TAG, "glasses display: ${glasses?.let { "id=${it.displayId} '${it.name}'" } ?: "none"}")
        return glasses
    }
}
