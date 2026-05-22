package com.vspace.spatial

import android.app.Presentation
import android.content.Context
import android.view.Display
import android.view.Surface
import kotlin.math.abs

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

    /** How much of the glasses display the scene fills, centred (1.0 = full). */
    @Volatile
    private var screenBandState = 0.83f

    /** Screen-size presets the toolbar button cycles through: comfortable, full, compact. */
    private val screenBandPresets = floatArrayOf(0.83f, 1.0f, 0.70f)

    /** Builds the desktop UI shown on the workspace's UiScreen. Set by the app at startup. */
    @Volatile
    var desktopContent: ((Context, Display) -> Presentation)? = null

    /** Builds the app-drawer overlay shown in front of the windows. Set by the app at startup. */
    @Volatile
    var drawerContent: ((Context, Display) -> Presentation)? = null

    /** Launches an app onto a virtual display. Set by the app at startup. */
    @Volatile
    var appLauncher: ((displayId: Int, packageName: String, activityName: String) -> Unit)? = null

    /**
     * Creates a *trusted* virtual display rendering into the given surface and returns its id
     * (or `null` on failure). A trusted display is created through Shizuku's shell-uid helper;
     * it is what lets a launched app keep its splash-screen / new-task launches on the
     * workspace instead of escaping to the phone. Set by the app at startup.
     */
    @Volatile
    var createVirtualDisplay: (
        (name: String, width: Int, height: Int, densityDpi: Int, surface: Surface) -> Int?
    )? = null

    /** Releases a virtual display created via [createVirtualDisplay]. Set by the app at startup. */
    @Volatile
    var releaseVirtualDisplay: ((displayId: Int) -> Unit)? = null

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

    /** Notified (on the main thread) when an app is launched — e.g. for the taskbar. */
    @Volatile
    var onAppLaunched: ((packageName: String, label: String) -> Unit)? = null

    /** Notified (on the main thread) when a launched app is closed — e.g. for the taskbar. */
    @Volatile
    var onAppClosed: ((packageName: String) -> Unit)? = null

    @Volatile
    private var drawerOpenState = false

    /** Whether a workspace is currently shown on the glasses. */
    val isRunning: Boolean get() = renderer != null

    /** Whether the app drawer is currently open. */
    val isDrawerOpen: Boolean get() = drawerOpenState

    /** The current view mode — pinned to the head, or free in the world. */
    val currentViewMode: WorkspaceRenderer.ViewMode get() = viewMode

    internal fun register(renderer: WorkspaceRenderer) {
        this.renderer = renderer
        drawerOpenState = false
        renderer.setViewMode(viewMode)
        renderer.setScreenBand(screenBandState)
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
        onAppLaunched?.invoke(packageName, label)
        return true
    }

    /** Open or close the app-drawer overlay. */
    fun setDrawerOpen(open: Boolean) {
        drawerOpenState = open
        renderer?.setDrawerOpen(open)
    }

    /**
     * Show-desktop toggle for the taskbar button: if any window is visible, minimise
     * everything; if everything is already minimised, restore them all.
     */
    fun toggleShowDesktop() {
        renderer?.toggleShowDesktop()
    }

    /** Bring an app's window to the front, restoring it if minimised — a taskbar-icon tap. */
    fun focusApp(packageName: String) {
        renderer?.focusApp(packageName)
    }

    /** Un-maximise an app's window and raise it — a taskbar-icon double-tap. */
    fun restoreApp(packageName: String) {
        renderer?.restoreApp(packageName)
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

    /** Set the render band — the fraction of the display the scene fills, centred. */
    fun setScreenBand(fraction: Float) {
        screenBandState = fraction
        renderer?.setScreenBand(fraction)
    }

    /** Cycle to the next screen-size preset; returns the new fraction (0..1). */
    fun cycleScreenBand(): Float {
        val index = screenBandPresets.indexOfFirst { abs(it - screenBandState) < 0.01f }
        val next = screenBandPresets[(index + 1).mod(screenBandPresets.size)]
        setScreenBand(next)
        return next
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
