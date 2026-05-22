package com.vspace.spatial

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

    /** Injects a tap into a launched app's display. Set by the app at startup. */
    @Volatile
    var appTap: ((displayId: Int, x: Int, y: Int) -> Unit)? = null

    /**
     * Sends Back to a launched app; [onEmptied] is run if Back closed it (its display went
     * empty), so the window can be closed. Set by the app at startup.
     */
    @Volatile
    var appBack: ((displayId: Int, onEmptied: () -> Unit) -> Unit)? = null

    /** Force-stops a launched app when its window closes. Set by the app at startup. */
    @Volatile
    var closeApp: ((packageName: String) -> Unit)? = null

    /** Notified (on the main thread) when a launched app is closed — e.g. for the taskbar. */
    @Volatile
    var onAppClosed: ((packageName: String) -> Unit)? = null

    @Volatile
    private var appsHiddenState = false

    /** Whether a workspace is currently shown on the glasses. */
    val isRunning: Boolean get() = renderer != null

    /** Whether the launched app windows are currently minimised. */
    val appsHidden: Boolean get() = appsHiddenState

    /** The current view mode — pinned to the head, or free in the world. */
    val currentViewMode: WorkspaceRenderer.ViewMode get() = viewMode

    internal fun register(renderer: WorkspaceRenderer) {
        this.renderer = renderer
        appsHiddenState = false
        renderer.setViewMode(viewMode)
    }

    internal fun unregister(renderer: WorkspaceRenderer) {
        if (this.renderer === renderer) this.renderer = null
    }

    /**
     * Open an app in a window on the workspace.
     *
     * @return `true` if a workspace was running, `false` if the glasses are not connected.
     */
    fun launchApp(packageName: String, activityName: String, label: String): Boolean {
        val current = renderer ?: return false
        current.requestApp(packageName, activityName, label)
        return true
    }

    /** Hide or restore the launched app windows (minimise / restore). */
    fun setAppsHidden(hidden: Boolean) {
        appsHiddenState = hidden
        renderer?.setAppsHidden(hidden)
    }

    /** Tell the renderer the app drawer is open, so it lifts above the app windows. */
    fun setDrawerOpen(open: Boolean) {
        renderer?.setDrawerOpen(open)
    }

    /** Restore a maximised app window to its normal framed size. */
    fun restoreWindow() {
        renderer?.restoreWindow()
    }

    /** Begin / end a window drag — the touchpad reports a press-and-hold as a drag. */
    fun beginDrag() {
        renderer?.beginDrag()
    }

    fun endDrag() {
        renderer?.endDrag()
    }

    /** Called by the renderer when a launched app's window is closed. */
    internal fun notifyAppClosed(packageName: String) {
        onAppClosed?.invoke(packageName)
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
