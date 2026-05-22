package com.vspace.workspace

/**
 * Process-wide handle to the workspace running inside the glasses' `Presentation`, so the
 * phone-side control panel can launch apps into it and switch its view mode.
 */
object WorkspaceController {

    @Volatile
    private var renderer: WorkspaceRenderer? = null

    @Volatile
    private var viewMode = WorkspaceRenderer.ViewMode.PINNED

    /** Whether a workspace is currently shown on the glasses. */
    val isRunning: Boolean get() = renderer != null

    /** The current view mode — pinned to the head, or free in the world. */
    val currentViewMode: WorkspaceRenderer.ViewMode get() = viewMode

    internal fun register(renderer: WorkspaceRenderer) {
        this.renderer = renderer
        renderer.setViewMode(viewMode)
    }

    internal fun unregister(renderer: WorkspaceRenderer) {
        if (this.renderer === renderer) this.renderer = null
    }

    /**
     * Place an app on the workspace screen.
     *
     * @return `true` if a workspace was running, `false` if the glasses are not connected.
     */
    fun launchApp(packageName: String, activityName: String): Boolean {
        val current = renderer ?: return false
        current.requestApp(packageName, activityName)
        return true
    }

    /** Switch how the screen tracks the head; remembered across workspace restarts. */
    fun setViewMode(mode: WorkspaceRenderer.ViewMode) {
        viewMode = mode
        renderer?.setViewMode(mode)
    }

    /** Save a PNG snapshot of the current workspace frame to the device's storage. */
    fun capture() {
        renderer?.requestCapture()
    }

    /** Move the workspace cursor by a fraction of the touchpad's width. */
    fun moveCursor(dxFraction: Float, dyFraction: Float) {
        renderer?.moveCursor(dxFraction, dyFraction)
    }

    /** Register a cursor click in the workspace. */
    fun click() {
        renderer?.cursorClick()
    }
}
