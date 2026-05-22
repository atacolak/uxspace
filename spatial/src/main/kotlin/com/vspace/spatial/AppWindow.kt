package com.vspace.spatial

/**
 * One app window in the workspace: the launched app's content surface ([content]) framed by
 * its window [chrome] — a light-grey border with a title bar.
 *
 * The window *is* the frame. [chrome] is a full-window-sized surface (border + title bar),
 * and the app's [content] is composited on top of it — inset below the title bar and within
 * the border — so the app is rendered *inside* the window. [layout] turns the window's state
 * and centre into world geometry for both quads; [WorkspaceRenderer] draws the frame, then
 * the content on top.
 */
class AppWindow(
    /** The launched app's surface — an Android VirtualDisplay textured onto a quad. */
    val content: VirtualScreen,
    /** The window frame — border + title bar — on its own UI surface, drawn behind [content]. */
    val chrome: UiScreen,
    /** The app's display name, shown in the title bar. */
    val label: String,
) {
    enum class State { NORMAL, MAXIMIZED }

    /** Windowed, or filling the desktop area. */
    var state: State = State.NORMAL

    /** Window centre in desktop-plane metres; a drag moves it (NORMAL state only). */
    var centerX: Float = 0f
    var centerY: Float = 0f

    /** False until [layout] has first placed the window, so it opens at its cascade slot. */
    var placed: Boolean = false

    /** Cascade slot — a new window opens offset down-right from the centre by this many steps. */
    var cascadeIndex: Int = 0

    /** Minimised — hidden from the workspace but still running; restored from the taskbar. */
    var minimized: Boolean = false

    // The window frame quad (the chrome surface). The content quad's geometry lives on [content].
    var frameX: Float = 0f
    var frameY: Float = 0f
    var frameZ: Float = -SCREEN_DISTANCE
    var frameW: Float = 0f
    var frameH: Float = 0f

    /** Package of the app in this window — force-stopped when the window is closed. */
    val packageName: String get() = content.packageName

    /**
     * Compute the frame and content quads' world geometry from the window's state and centre,
     * given the desktop plane's half-extents. The frame is kept inside the desktop area above
     * the taskbar; the content is placed inside the frame from its fixed pixel layout.
     */
    fun layout(desktopHalfWidth: Float, desktopHalfHeight: Float) {
        val topY = desktopHalfHeight
        val bottomY = -desktopHalfHeight + TASKBAR_RESERVE * (2f * desktopHalfHeight)

        if (state == State.MAXIMIZED) {
            // The app content fills the desktop area above the taskbar — no border, no title
            // bar. Kept at the content's own aspect so the app is not distorted.
            val areaW = 2f * desktopHalfWidth
            val areaH = topY - bottomY
            val aspect = CONTENT_WIDTH_PX.toFloat() / CONTENT_HEIGHT_PX
            var cw = areaW
            if (cw / aspect > areaH) cw = areaH * aspect
            content.worldX = 0f
            content.worldY = (topY + bottomY) / 2f
            content.worldZ = -SCREEN_DISTANCE
            content.worldYawDeg = 0f
            content.worldWidth = cw
            frameW = 0f
            frameH = 0f
            return
        }

        // NORMAL — a framed window.
        val availW = (2f * desktopHalfWidth) * WINDOW_MARGIN
        val availH = (topY - bottomY) * WINDOW_MARGIN
        val frameAspect = FRAME_WIDTH_PX.toFloat() / FRAME_HEIGHT_PX
        var fw = availW * NORMAL_FRACTION
        var fh = fw / frameAspect
        if (fh > availH) {
            fh = availH
            fw = fh * frameAspect
        }

        // Opens at its cascade slot — offset down-right from the centre; a drag then moves it.
        if (!placed) {
            centerX = cascadeIndex * (2f * desktopHalfWidth) * CASCADE_FRACTION
            centerY = (topY + bottomY) / 2f -
                cascadeIndex * (2f * desktopHalfHeight) * CASCADE_FRACTION
            placed = true
        }
        centerX = centerX.coerceIn(-desktopHalfWidth + fw / 2f, desktopHalfWidth - fw / 2f)
        centerY = centerY.coerceIn(bottomY + fh / 2f, topY - fh / 2f)

        frameX = centerX
        frameY = centerY
        frameZ = -SCREEN_DISTANCE
        frameW = fw
        frameH = fh

        // The content quad sits inside the frame, scaled from the frame's pixel layout. Its
        // pixel centre is (TITLE_BAR_PX - BORDER_PX) / 2 below the frame's centre.
        val scale = fw / FRAME_WIDTH_PX
        content.worldX = centerX
        content.worldY = centerY - (TITLE_BAR_PX - BORDER_PX) / 2f * scale
        content.worldZ = -SCREEN_DISTANCE
        content.worldYawDeg = 0f
        content.worldWidth = CONTENT_WIDTH_PX * scale
    }

    /** Release both surfaces. Call on the GL thread. */
    fun release() {
        content.release()
        chrome.release()
    }

    companion object {
        /** Pixel resolution of the launched-app content surface (16:9). */
        const val CONTENT_WIDTH_PX = 1600
        const val CONTENT_HEIGHT_PX = 900

        /** Window-frame metrics, in chrome-surface pixels: a thin border, and the title bar. */
        const val BORDER_PX = 3
        const val TITLE_BAR_PX = 80

        /** Pixel resolution of the whole window frame — the chrome surface wraps the content. */
        const val FRAME_WIDTH_PX = CONTENT_WIDTH_PX + 2 * BORDER_PX
        const val FRAME_HEIGHT_PX = TITLE_BAR_PX + CONTENT_HEIGHT_PX + BORDER_PX

        /** Distance of the window plane from the viewer, in metres. */
        const val SCREEN_DISTANCE = 4.0f

        /** Fraction of the desktop height reserved at the bottom for the taskbar. */
        private const val TASKBAR_RESERVE = 0.085f

        /** Windows shrink slightly so they never touch the desktop edges. */
        private const val WINDOW_MARGIN = 0.98f

        /** A NORMAL (un-maximised) window's frame width, as a fraction of the available width. */
        private const val NORMAL_FRACTION = 0.66f

        /** Each cascade step offsets a new window by this fraction of the desktop size. */
        private const val CASCADE_FRACTION = 0.045f
    }
}
