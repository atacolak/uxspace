package com.uxspace.spatial

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

    /**
     * Zoom layouts the toolbar zoom button cycles through — bigger value = scene
     * appears bigger / closer (matches the pinch direction). 120% = startup default.
     */
    private val zoomPresets = floatArrayOf(1.2f, 1.0f, 1.5f, 1.8f, 0.8f)

    /** Active multi-screen layout layout. The taskbar layout button cycles through these. */
    @Volatile
    private var layoutState: Layout = Layout.SINGLE

    /** Builds the desktop UI shown on the workspace's UiScreen. Set by the app at startup. */
    @Volatile
    /**
     * Per-screen desktop factory — given the host context, the screen's VirtualDisplay, the
     * screen index (0..N-1 of the active layout), and whether the screen should render its
     * taskbar/status row, returns the screen's [com.uxspace.desktop.DesktopPresentation].
     * `screenIdx` lets each Presentation filter [onAppLaunched] events to only its screen.
     */
    var desktopContent: (
        (Context, Display, screenIdx: Int, showTaskbar: Boolean) -> Presentation
    )? = null

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
     * Injects a vertical scroll into a launched app's display at the cursor's content
     * coordinates — [vScroll] is the same sign convention as a mouse wheel (positive →
     * scroll up / content moves down). Implemented through the privileged helper as a
     * quick touch swipe, since `input` doesn't expose `ACTION_SCROLL` directly.
     * Set by the app at startup.
     */
    @Volatile
    var appScroll: ((displayId: Int, x: Int, y: Int, vScroll: Float) -> Unit)? = null

    /** Forward a pinch scale factor (1.0 = identity) into the workspace zoom. */
    fun pinch(scaleFactor: Float) {
        renderer?.requestPinch(scaleFactor)
    }

    /** Force-stops a launched app when its screen is torn down. Set by the app at startup. */
    @Volatile
    var closeApp: ((packageName: String) -> Unit)? = null

    /**
     * Whether the given VirtualDisplay has any activity stacked on it. The persistence
     * save uses this to skip screens whose app self-closed (e.g. user backed out) so the
     * layout memory doesn't relaunch a dead app on the next switch. Set by the app at
     * startup; AIDL-backed, so call off the GL thread.
     */
    @Volatile
    var displayHasActivity: ((displayId: Int) -> Boolean)? = null

    /**
     * Listeners notified (on the main thread) when an app is launched onto a screen —
     * each per-screen DesktopPresentation registers its own listener so it can filter to
     * its screen. A list (not a single callback) because there are N concurrent screen
     * presentations.
     */
    private val appLaunchedListeners =
        java.util.concurrent.CopyOnWriteArrayList<(packageName: String, label: String, screenIdx: Int) -> Unit>()

    fun addAppLaunchedListener(listener: (packageName: String, label: String, screenIdx: Int) -> Unit) {
        appLaunchedListeners.add(listener)
    }

    fun removeAppLaunchedListener(listener: (packageName: String, label: String, screenIdx: Int) -> Unit) {
        appLaunchedListeners.remove(listener)
    }

    /** Listeners notified when a launched app is closed — same multi-listener pattern. */
    private val appClosedListeners =
        java.util.concurrent.CopyOnWriteArrayList<(packageName: String) -> Unit>()

    fun addAppClosedListener(listener: (packageName: String) -> Unit) {
        appClosedListeners.add(listener)
    }

    fun removeAppClosedListener(listener: (packageName: String) -> Unit) {
        appClosedListeners.remove(listener)
    }

    /**
     * Listeners notified (on the main thread) every time the active view-mode's
     * workspace zoom changes — pinch-driven or button-driven. The phone-side trackpad
     * uses this to surface a live "Zoom NN%" HUD during a pinch.
     */
    private val zoomListeners =
        java.util.concurrent.CopyOnWriteArrayList<(zoom: Float) -> Unit>()

    fun addZoomListener(listener: (zoom: Float) -> Unit) { zoomListeners.add(listener) }
    fun removeZoomListener(listener: (zoom: Float) -> Unit) { zoomListeners.remove(listener) }

    /** Internal: the renderer fires this after every zoom change. */
    internal fun notifyZoomChanged(zoom: Float) {
        zoomListeners.forEach { runCatching { it(zoom) } }
    }

    /**
     * Routes typed text from the phone control panel into the drawer's search field. The
     * panel's keyboard is the only IME path in the workspace; the drawer lives on a
     * secondary display and can't receive a system IME directly. Wired by the drawer when
     * it is shown; called by `MainActivity`'s keyboardField text watcher while the drawer
     * is open.
     */
    @Volatile
    var onDrawerSearchQuery: ((query: String) -> Unit)? = null

    /** Forward [query] into the drawer's search box. No-op if the drawer isn't listening. */
    fun setDrawerSearchQuery(query: String) {
        onDrawerSearchQuery?.invoke(query)
    }

    @Volatile
    private var drawerOpenState = false

    /** Which view the drawer is showing — every-installed-app, or only recently launched. */
    enum class DrawerMode { ALL, RECENT }

    @Volatile
    var drawerMode: DrawerMode = DrawerMode.ALL
        private set

    /**
     * Notified (on the main thread) when the drawer mode changes — every DrawerView
     * registers one, so all per-screen drawers re-style their tabs and re-filter the
     * grid in sync. Multi-listener (not a single var) because V/H/V has three
     * DrawerViews and the single-slot version would leave two of them stale.
     */
    private val drawerModeListeners =
        java.util.concurrent.CopyOnWriteArrayList<(DrawerMode) -> Unit>()

    fun addDrawerModeListener(listener: (DrawerMode) -> Unit) {
        drawerModeListeners.add(listener)
    }

    fun removeDrawerModeListener(listener: (DrawerMode) -> Unit) {
        drawerModeListeners.remove(listener)
    }

    /** Most-recent-first package names of apps launched into the workspace. */
    private val recentAppsList = ArrayList<String>()

    /** A snapshot of the workspace's recent-app launch history. */
    val recentApps: List<String> get() = synchronized(recentAppsList) { recentAppsList.toList() }

    /** Whether a workspace is currently shown on the glasses. */
    val isRunning: Boolean get() = renderer != null

    /** Whether the app drawer is currently open. */
    val isDrawerOpen: Boolean get() = drawerOpenState

    /**
     * Set the drawer's filter mode. Always notifies listeners — even if the stored
     * mode is unchanged — so the App-drawer button's "force to ALL" semantics can't
     * be defeated by a stale tab state on a DrawerView that was constructed while
     * the mode was something else (e.g. RECENT left over from a prior unlocked
     * session).
     */
    fun setDrawerMode(mode: DrawerMode) {
        drawerMode = mode
        drawerModeListeners.forEach { runCatching { it(mode) } }
    }

    /** The current view mode — pinned to the head, or free in the world. */
    val currentViewMode: WorkspaceRenderer.ViewMode get() = viewMode

    /**
     * The layout actually rendered right now. PINNED mode always shows a single display
     * (focus mode); FREE mode shows the user's chosen layout. [layoutState] holds the
     * FREE-mode choice across lock/unlock cycles.
     */
    private fun effectiveLayout(): Layout =
        if (viewMode == WorkspaceRenderer.ViewMode.PINNED) Layout.SINGLE else layoutState

    internal fun register(renderer: WorkspaceRenderer) {
        this.renderer = renderer
        drawerOpenState = false
        renderer.setViewMode(viewMode)
        renderer.setScreenBand(screenBandState)
        renderer.applyLayout(effectiveLayout())
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
        synchronized(recentAppsList) {
            recentAppsList.remove(packageName)
            recentAppsList.add(0, packageName)
            while (recentAppsList.size > MAX_RECENT_APPS) recentAppsList.removeAt(MAX_RECENT_APPS)
        }
        // onAppLaunched fires later from the renderer (per-screen), once the launch target
        // screen has been picked — so each screen's DesktopPresentation can filter by screenIdx.
        return true
    }

    /** Internal: the renderer fires this once it knows which screen received the launch. */
    internal fun notifyAppLaunchedOnScreen(packageName: String, label: String, screenIdx: Int) {
        appLaunchedListeners.forEach { runCatching { it(packageName, label, screenIdx) } }
    }

    /**
     * Move a launched app from its current screen to the next screen of the active layout
     * (wraps around). Closes the activity on the source screen and relaunches it on the
     * target — currentSlotApps + persistence are updated through the normal paths.
     * No-op when the layout has only one screen or when the app isn't on any screen.
     */
    fun moveAppToNextScreen(packageName: String) {
        renderer?.moveAppToNextScreen(packageName)
    }

    /** Internal: notify listeners that an app has been closed. */
    internal fun notifyAppClosed(packageName: String) {
        appClosedListeners.forEach { runCatching { it(packageName) } }
    }

    /**
     * Open or close the app-drawer. `screenIdx` is the index of the screen whose
     * drawer button triggered this — the drawer is a view embedded in *that* screen's
     * [com.uxspace.desktop.DesktopPresentation], not a separate 3D quad. Closing
     * ignores `screenIdx` (every drawer view hides). Fires every registered
     * [drawerStateListeners] entry on the main thread.
     */
    fun setDrawerOpen(open: Boolean, screenIdx: Int = 0) {
        drawerOpenState = open
        drawerOnScreen = if (open) screenIdx else -1
        drawerStateListeners.forEach { runCatching { it(open, screenIdx) } }
    }

    /** Index of the screen that owns the currently-open drawer, or −1 when closed. */
    @Volatile
    var drawerOnScreen: Int = -1
        private set

    /**
     * Listeners notified (main thread) when the drawer opens/closes. Each per-screen
     * DesktopPresentation registers one and toggles its embedded DrawerView's
     * visibility iff `screenIdx == own slotIdx` (open) or unconditionally (close).
     */
    private val drawerStateListeners =
        java.util.concurrent.CopyOnWriteArrayList<(open: Boolean, screenIdx: Int) -> Unit>()

    fun addDrawerStateListener(listener: (open: Boolean, screenIdx: Int) -> Unit) {
        drawerStateListeners.add(listener)
    }

    fun removeDrawerStateListener(listener: (open: Boolean, screenIdx: Int) -> Unit) {
        drawerStateListeners.remove(listener)
    }

    /**
     * Show-desktop toggle for the taskbar button: if any window is visible, minimise
     * everything; if everything is already minimised, restore them all.
     */
    fun toggleShowDesktop() {
        renderer?.toggleShowDesktop()
    }

    /** Bring an app's activity to the front of its screen — a taskbar-icon tap. */
    fun focusApp(packageName: String) {
        renderer?.focusApp(packageName)
    }

    /** Close an app — a taskbar-icon double-tap. */
    fun closeAppByPackage(packageName: String) {
        renderer?.closeAppByPackage(packageName)
    }

    /** Begin / end a window drag — the touchpad reports a press-and-hold as a drag. */
    fun beginDrag() {
        renderer?.beginDrag()
    }

    fun endDrag() {
        renderer?.endDrag()
    }

    /**
     * Switch how the screen tracks the head, and reshape the workspace to match. PINNED
     * always shows a single display (focus mode); FREE restores the user's chosen layout.
     * Closes the drawer if it was open — switching modes makes the previous drawer's
     * anchor screen meaningless.
     */
    fun setViewMode(mode: WorkspaceRenderer.ViewMode) {
        if (viewMode == mode) return
        viewMode = mode
        if (drawerOpenState) setDrawerOpen(false)
        renderer?.setViewMode(mode)
        renderer?.applyLayout(effectiveLayout())
    }

    /** Set the render band — the fraction of the display the scene fills, centred. */
    fun setScreenBand(fraction: Float) {
        screenBandState = fraction
        renderer?.setScreenBand(fraction)
    }

    /** Recenter every window's vertical position on the user's current head pitch. */
    fun alignVerticalToHead() {
        val r = renderer
        android.util.Log.i(
            "UxSpace/Renderer",
            "alignVerticalToHead() controller call — renderer=${r != null}",
        )
        r?.alignVerticalToHead()
    }

    /**
     * Cycle the active view-mode's workspace zoom through the [zoomPresets]; returns
     * the new zoom (1.0 = 100% = default size, bigger = closer / larger). Replaces the
     * old screen-band cycle whose letterbox semantics felt backwards (smaller % looked
     * "zoomed in" because the rendered scene shrank into a centred box).
     */
    fun cycleScreenBand(): Float {
        val r = renderer ?: return 1.0f
        val current = r.workspaceZoom()
        val index = zoomPresets.indexOfFirst { abs(it - current) < 0.01f }
        val next = zoomPresets[(index + 1).mod(zoomPresets.size)]
        r.setWorkspaceZoom(next)
        return next
    }

    /** The user's saved layout — what's rendered in FREE mode. */
    val layout: Layout get() = layoutState

    /**
     * Update the FREE-mode layout. In PINNED the choice is *stored* but not rendered
     * (lock always shows SINGLE); the new layout takes effect on next unlock.
     */
    fun setLayout(layout: Layout) {
        layoutState = layout
        if (viewMode == WorkspaceRenderer.ViewMode.FREE) {
            renderer?.applyLayout(layout)
        }
    }

    /**
     * Cycle to the next [Layout]; only meaningful in FREE — PINNED is single-display
     * focus. Returns the new (or unchanged) layout.
     */
    fun cycleLayout(): Layout {
        if (viewMode != WorkspaceRenderer.ViewMode.FREE) return layoutState
        val values = Layout.values()
        val next = values[(layoutState.ordinal + 1).mod(values.size)]
        setLayout(next)
        return next
    }

    /** Save a PNG snapshot of the current workspace frame to the device's storage. */
    fun capture() {
        renderer?.requestCapture()
    }

    /** Toggle frame-sequence recording — saves a PNG every Nth render frame until stopped. */
    fun toggleRecording(): Boolean {
        val r = renderer ?: return false
        val next = !r.isRecording()
        r.setRecording(next)
        return next
    }

    /** Whether a recording is currently in progress. */
    val isRecording: Boolean get() = renderer?.isRecording() == true

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

    /** Cancel an in-flight press (second finger lands, scroll/pinch takes over). */
    fun cancelDrag() {
        renderer?.cancelDrag()
    }

    /**
     * Display ids of the workspace's currently-live trusted virtual displays — the
     * displays UxSpace has launched apps onto. Used by [UxSpaceAccessibilityService]
     * to filter focus events: typing-into-app only fires for these displays, not the
     * phone's own screen.
     */
    private val uxspaceDisplayIds = java.util.concurrent.CopyOnWriteArraySet<Int>()

    /** Register a virtual display as UxSpace-owned. Called by the renderer when an
     *  app window is created. */
    fun registerUxSpaceDisplay(displayId: Int) {
        if (displayId >= 0) uxspaceDisplayIds.add(displayId)
    }

    /** Unregister when the display is released. */
    fun unregisterUxSpaceDisplay(displayId: Int) {
        uxspaceDisplayIds.remove(displayId)
    }

    /** True if [displayId] is one of UxSpace's own virtual displays. Any thread. */
    fun isUxSpaceDisplay(displayId: Int): Boolean = uxspaceDisplayIds.contains(displayId)

    /**
     * Fires when an [AccessibilityService]-detected text-input field on one of our
     * virtual displays gains focus — the phone's keyboard should pop up. The
     * [displayId] argument is the display where the field lives, so text typed on
     * the phone can be forwarded back to that display via `PrivilegedService.text`.
     */
    @Volatile
    var onAppTextFieldFocused: ((displayId: Int) -> Unit)? = null

    /** Fires when focus leaves the previously-focused text field on [displayId]. */
    @Volatile
    var onAppTextFieldUnfocused: ((displayId: Int) -> Unit)? = null


    /** How many distinct apps to remember in [recentApps]. */
    private const val MAX_RECENT_APPS = 32
}
