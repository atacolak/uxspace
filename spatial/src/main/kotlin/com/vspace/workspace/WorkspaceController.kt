package com.vspace.workspace

import android.app.Presentation
import android.content.Context
import android.view.Display

/**
 * Process-wide handle to the workspace running inside the glasses' `Presentation`, so the
 * phone-side control panel can launch apps into it and switch its view mode.
 *
 * It also holds the workspace's injectable hooks ([desktopContent], [appLauncher]) — the
 * app wires these at startup, which keeps the rendering layer free of any dependency on the
 * desktop UI or on Shizuku.
 */
object WorkspaceController {

    @Volatile
    private var renderer: WorkspaceRenderer? = null

    @Volatile
    private var viewMode = WorkspaceRenderer.ViewMode.PINNED

    /** Builds the desktop UI shown on the workspace's UiScreen. Set by the app at startup. */
    @Volatile
    var desktopContent: ((Context, Display) -> Presentation)? = null

    /** Launches an app onto a virtual display. Set by the app at startup. */
    @Volatile
    var appLauncher: ((displayId: Int, packageName: String, activityName: String) -> Unit)? = null

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

    /** Scroll the screen under the cursor by a fraction of the touchpad's height. */
    fun scroll(dyFraction: Float) {
        renderer?.requestScroll(dyFraction)
    }

    /** Register a cursor click in the workspace. */
    fun click() {
        renderer?.cursorClick()
    }
}
