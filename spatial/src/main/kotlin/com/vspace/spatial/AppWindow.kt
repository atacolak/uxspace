package com.vspace.spatial

/**
 * One app window in the workspace: the launched app's content surface ([content]) together
 * with its title-bar [chrome], plus the placement and state that make it a window rather
 * than a full-screen surface.
 *
 * [layout] turns the window's state and centre into world geometry for both quads — the
 * chrome strip sits directly above the content. [WorkspaceRenderer] owns the instance and
 * calls [layout] whenever the window is moved, maximised, or the view resizes.
 */
class AppWindow(
    /** The launched app's surface — an Android VirtualDisplay textured onto a quad. */
    val content: VirtualScreen,
    /** The title bar — its own UI surface, drawn just above [content]. */
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

    /** False until [layout] has first placed the window, so it opens centred. */
    var placed: Boolean = false

    // Chrome quad geometry in world space; the content quad's geometry lives on [content].
    var chromeX: Float = 0f
    var chromeY: Float = 0f
    var chromeZ: Float = -SCREEN_DISTANCE
    var chromeW: Float = 0f
    var chromeH: Float = 0f

    /** Package of the app in this window — force-stopped when the window is closed. */
    val packageName: String get() = content.packageName

    /**
     * Compute both quads' world geometry from the window's state and centre, given the
     * desktop plane's half-extents. The window is kept inside the desktop area above the
     * taskbar.
     */
    fun layout(desktopHalfWidth: Float, desktopHalfHeight: Float) {
        val topY = desktopHalfHeight
        val bottomY = -desktopHalfHeight + TASKBAR_RESERVE * (2f * desktopHalfHeight)
        val availW = (2f * desktopHalfWidth) * WINDOW_MARGIN
        val availH = (topY - bottomY) * WINDOW_MARGIN

        val contentAspect = CONTENT_WIDTH_PX.toFloat() / CONTENT_HEIGHT_PX
        val chromeAspect = CHROME_WIDTH_PX.toFloat() / CHROME_HEIGHT_PX
        // Total window height per unit of (shared) width: content below, chrome above.
        val heightPerWidth = 1f / contentAspect + 1f / chromeAspect

        var contentW = if (state == State.MAXIMIZED) availW else availW * NORMAL_FRACTION
        if (contentW * heightPerWidth > availH) contentW = availH / heightPerWidth
        val contentH = contentW / contentAspect
        val chH = contentW / chromeAspect
        val windowH = contentH + chH

        // Opens centred; once placed, only a drag (or maximise) moves it.
        if (!placed || state == State.MAXIMIZED) {
            centerX = 0f
            centerY = (topY + bottomY) / 2f
            placed = true
        }
        val halfW = contentW / 2f
        val halfH = windowH / 2f
        centerX = centerX.coerceIn(-desktopHalfWidth + halfW, desktopHalfWidth - halfW)
        centerY = centerY.coerceIn(bottomY + halfH, topY - halfH)

        chromeW = contentW
        chromeH = chH
        chromeX = centerX
        chromeY = centerY + windowH / 2f - chH / 2f
        chromeZ = -SCREEN_DISTANCE

        content.worldX = centerX
        content.worldY = centerY - windowH / 2f + contentH / 2f
        content.worldZ = -SCREEN_DISTANCE
        content.worldYawDeg = 0f
        content.worldWidth = contentW
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

        /** Pixel resolution of the title-bar surface — full width, a thin strip. */
        const val CHROME_WIDTH_PX = 1600
        const val CHROME_HEIGHT_PX = 84

        /** Distance of the window plane from the viewer, in metres. */
        const val SCREEN_DISTANCE = 4.0f

        /** Fraction of the desktop height reserved at the bottom for the taskbar. */
        private const val TASKBAR_RESERVE = 0.085f

        /** Windows shrink slightly so they never touch the desktop edges. */
        private const val WINDOW_MARGIN = 0.98f

        /** A NORMAL (un-maximised) window's width, as a fraction of the available width. */
        private const val NORMAL_FRACTION = 0.64f
    }
}
