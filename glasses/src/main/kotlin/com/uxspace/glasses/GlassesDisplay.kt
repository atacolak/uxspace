package com.uxspace.glasses

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

/**
 * Finds the external display the VITURE glasses present themselves as.
 *
 * The glasses are a presentation-category display; UxSpace shows a `Presentation` on it so the
 * glasses show the workspace while the phone keeps its own screen. UxSpace's own virtual
 * screens (the off-screen buffers that host launched apps) are *also* presentation displays,
 * so they are filtered out by name — picking one of those was the bug that broke this before.
 */
object GlassesDisplay {

    private const val TAG = "UxSpace/Display"

    /**
     * Display-name prefix the per-slot trusted VirtualDisplays use ("uxspace-desktop"
     * for slot 0, "uxspace-desktop-screen<i>" for the rest — see syncScreens in
     * WorkspaceRenderer). Excluded from the glasses-display search so we never pick
     * our own off-screen buffer as the glasses target. The previous value was
     * "uxspace-screen", from an older naming convention; once the slot displays were
     * renamed to "uxspace-desktop*" the filter stopped matching and find() would fall
     * back to one of our own trusted displays whenever the real glasses dropped off
     * the bus, which then triggered a chain of relayout / app-kill.
     */
    private const val UXSPACE_SCREEN_PREFIX = "uxspace-desktop"

    /** The glasses' display, or `null` when they are not connected. */
    fun find(context: Context): Display? {
        val displayManager =
            context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        Log.i(TAG, "displays visible to UxSpace:")
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
                !it.name.startsWith(UXSPACE_SCREEN_PREFIX) && it.state != Display.STATE_OFF
            }
        Log.i(TAG, "glasses display: ${glasses?.let { "id=${it.displayId} '${it.name}'" } ?: "none"}")
        return glasses
    }
}
