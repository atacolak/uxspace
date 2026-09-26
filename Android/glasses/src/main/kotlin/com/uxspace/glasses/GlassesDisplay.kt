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
     * Prefix every UxSpace-owned virtual display name shares. The per-slot UI
     * Presentations are named `uxspace-desktop` / `uxspace-desktop-screenN`, and the
     * per-window bare trusted displays are `uxspace-app-N`. We must filter out *all*
     * of them — picking one of our own as "the glasses" was the bug that put the
     * workspace Presentation on top of our own app display, recursing. The prefix
     * has shifted twice now (was `uxspace-screen`, then `uxspace-desktop`); using
     * the broader `uxspace-` removes the need for future updates if more display
     * categories are added.
     */
    private const val UXSPACE_SCREEN_PREFIX = "uxspace-"

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
        glasses?.let { dumpModes(it) }
        return glasses
    }

    /**
     * Log the current Android mode and every supported mode on the glasses display.
     * Diagnostic only — does not request 1920x1200@120 globally.
     */
    private fun dumpModes(display: Display) {
        val cur = display.mode
        Log.i(
            TAG,
            "current mode: ${cur.physicalWidth}x${cur.physicalHeight} " +
                "@ ${cur.refreshRate}Hz id=${cur.modeId}",
        )
        var saw1920x1200_120 = false
        display.supportedModes.forEach { m ->
            val isTarget = m.physicalWidth == 1920 &&
                m.physicalHeight == 1200 &&
                m.refreshRate >= 119.5f
            if (isTarget) saw1920x1200_120 = true
            Log.i(
                TAG,
                "  supported: ${m.physicalWidth}x${m.physicalHeight} " +
                    "@ ${"%.2f".format(m.refreshRate)}Hz id=${m.modeId}" +
                    if (isTarget) "  <-- 1920x1200@120" else "",
            )
        }
        Log.i(TAG, "1920x1200@120 available via Android Display: $saw1920x1200_120")
    }
}
