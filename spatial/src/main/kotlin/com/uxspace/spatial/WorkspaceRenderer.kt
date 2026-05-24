package com.uxspace.spatial

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Renders the virtual space as a 3D scene of textured quads — one quad per [Screen]
 * of the active [Layout]. Each screen is a fully independent [UiScreen] hosting its
 * own [com.uxspace.desktop.DesktopPresentation] + app stack on a trusted
 * VirtualDisplay; the workspace also draws the app-drawer overlay and touchpad cursor.
 *
 * Each screen's quad samples an external-OES texture fed by a VirtualDisplay;
 * switching the [Layout] tears down and rebuilds the screen list. The camera comes
 * from [setHeadPose]: PINNED locks the scene to the view, FREE world-fixes it as the
 * head turns. App launches arrive on any thread via [requestApp] and land on the
 * screen under the cursor in [addWindow] (GL thread).
 */
class WorkspaceRenderer(
    private val context: Context,
    private val mainHandler: Handler,
) : GLSurfaceView.Renderer {

    private val appContext: Context = context.applicationContext

    private data class AppRequest(
        val packageName: String,
        val activityName: String,
        val label: String,
        /**
         * When non-null, forces the launch onto this specific screen index (used by
         * layout-restore paths). Null means pick the screen under the cursor at launch.
         */
        val screenIdx: Int? = null,
    )

    /** Apps currently launched per screen of the active layout, keyed by screen index. */
    private val currentScreenApps = mutableMapOf<Int, AppRequest>()

    /**
     * Per-layout memory of which app sits on which screen. When the user cycles away from
     * a layout, its current allocation is stashed here; cycling back relaunches each saved
     * app onto its remembered screen — so Two SBS → Three SBS → Two SBS restores the
     * Two-SBS apps to the screens they were on before the detour.
     */
    private val layoutAppMemory = mutableMapOf<Layout, Map<Int, AppRequest>>()

    private val pendingApps = ConcurrentLinkedQueue<AppRequest>()
    /** Window-control actions posted from the main thread to run on the GL thread. */
    private val glTasks = ConcurrentLinkedQueue<() -> Unit>()

    /** Screen 0's UiScreen — UxSpace's own home, hosted on its own VirtualDisplay. */
    private var desktop: UiScreen? = null

    /**
     * Extra screens — one [UiScreen] per [Screen] of the active layout beyond screen 0.
     * Each is a fully independent UxSpace environment (its own VirtualDisplay, its own
     * [com.uxspace.desktop.DesktopPresentation] with wallpaper + drawer + taskbar).
     * Screen 0 uses [desktop]; screens 1..N-1 use these. Recreated on layout switch.
     */
    private val extraScreens = mutableListOf<UiScreen>()

    /** The app-drawer overlay — drawn in front of the windows, behind a scrim, while open. */
    private var drawer: UiScreen? = null

    // Head-orientation quaternion (w, x, y, z); identity means looking straight ahead.
    @Volatile private var headW = 1f
    @Volatile private var headX = 0f
    @Volatile private var headY = 0f
    @Volatile private var headZ = 0f

    /**
     * "Recenter" anchor — a rotation post-multiplied onto the head pose before it drives the
     * FREE-mode view matrix. Identity by default; [recenterScene] sets it to the inverse of
     * the current head pose, so the scene snaps to "straight ahead" of wherever the user is
     * currently looking (e.g. switching from sitting to lying down). All windows rotate as
     * one rigid scene — they keep their relative spatial layout.
     */
    @Volatile private var anchorW = 1f
    @Volatile private var anchorX = 0f
    @Volatile private var anchorY = 0f
    @Volatile private var anchorZ = 0f

    /** How the scene tracks the head. */
    @Volatile private var viewMode = ViewMode.PINNED

    /** Whether the scene follows the head ([PINNED]) or stays put in the world ([FREE]). */
    enum class ViewMode { PINNED, FREE }

    // Screen-quad program — samples a SurfaceTexture as an external-OES texture.
    private var screenProgram = 0
    private var screenAPosition = 0
    private var screenATexCoord = 0
    private var screenUMvp = 0
    private var screenUTexMatrix = 0
    private var screenUTexture = 0

    private var cursorProgram = 0
    private var cursorAPosition = 0
    private var cursorUCenter = 0
    private var cursorUHalfSize = 0
    private var cursorUColor = 0
    @Volatile private var cursorX = 0f
    @Volatile private var cursorY = 0f
    @Volatile private var cursorClickPending = false
    private var cursorFlashFrames = 0

    /** Last time the touchpad sent any cursor-affecting input; drives the idle hide. */
    @Volatile private var lastInputAtMs: Long = android.os.SystemClock.uptimeMillis()

    /**
     * Bottom-centre in-view toolbar. Idle = a 5-px peek line; on cursor hover or button
     * click the toolbar expands to 4 buttons (lock/unlock, zoom, recenter, layout) and
     * stays visible for [TOOLBAR_AUTOHIDE_MS] after the last interaction.
     */
    @Volatile private var toolbarExpanded = false
    @Volatile private var toolbarLastShownMs = 0L

    /** One in-view toolbar button — NDC centre x, half-width, RGBA, and click action. */
    private data class HudButton(
        val label: String,
        val cx: Float,
        val halfW: Float,
        val color: FloatArray,
        val action: () -> Unit,
    )

    private val toolbarButtons: Array<HudButton> by lazy {
        arrayOf(
            HudButton(
                "lock", cx = -0.30f, halfW = 0.075f,
                color = floatArrayOf(0.24f, 0.73f, 0.85f, 0.92f),
            ) {
                val next = if (viewMode == ViewMode.PINNED) ViewMode.FREE else ViewMode.PINNED
                WorkspaceController.setViewMode(next)
            },
            HudButton(
                "zoom", cx = -0.10f, halfW = 0.075f,
                color = floatArrayOf(0.49f, 0.85f, 0.34f, 0.92f),
            ) {
                WorkspaceController.cycleScreenBand()
            },
            HudButton(
                "recenter", cx = 0.10f, halfW = 0.075f,
                color = floatArrayOf(0.96f, 0.64f, 0.38f, 0.92f),
            ) {
                WorkspaceController.alignVerticalToHead()
            },
            HudButton(
                "layout", cx = 0.30f, halfW = 0.075f,
                color = floatArrayOf(0.65f, 0.55f, 0.98f, 0.92f),
            ) {
                if (viewMode == ViewMode.FREE) WorkspaceController.cycleLayout()
            },
        )
    }

    /**
     * Active multi-screen layout. New windows pick a screen from this layout on
     * launch; [applyLayout] reflows the existing ones. Mirrors [WorkspaceController.layout].
     */
    @Volatile private var layout: Layout = Layout.SINGLE

    /** Accumulated pinch scale (1.0 = identity); applied once per frame. */
    @Volatile private var pendingPinch = 1f
    private var surfaceAspect = 1.78f

    @Volatile private var captureRequested = false
    @Volatile private var recording = false
    private var recordingFrameCounter = 0
    @Volatile private var pendingScroll = 0f
    @Volatile private var drawerOpen = false

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** Height of the centred 16:9 render area, as a fraction of the display height. */
    @Volatile private var screenBand = DEFAULT_SCREEN_BAND
    @Volatile private var bandDirty = false

    /**
     * Pinch-driven projection zoom, anchored at the cursor's NDC position at the moment
     * of the pinch. 1.0 = identity; 2.0 = everything twice as large. Kept *per view mode*
     * — switching FREE ↔ PINNED restores that mode's saved zoom + cursor anchor — so a
     * mode toggle never blows the user's chosen scale away. Re-applied to [projection]
     * on the GL thread every frame the value changes.
     */
    @Volatile private var freeZoom = DEFAULT_WORKSPACE_ZOOM
    @Volatile private var freeTx = 0f
    @Volatile private var freeTy = 0f
    @Volatile private var pinnedZoom = DEFAULT_WORKSPACE_ZOOM
    @Volatile private var pinnedTx = 0f
    @Volatile private var pinnedTy = 0f

    /** Desktop quad half-extents (metres), sized in [onSurfaceChanged] to fill the view. */
    private var desktopHalfWidth = 3.7f
    private var desktopHalfHeight = 2.1f

    private lateinit var screenQuad: FloatBuffer
    private lateinit var cursorArrow: FloatBuffer
    private lateinit var scrimQuad: FloatBuffer

    private val projection = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Scratch for unprojecting the cursor onto the desktop plane.
    private val invViewProjection = FloatArray(16)
    private val clipPoint = FloatArray(4)
    private val worldPoint = FloatArray(4)

    /** Queue an app to be opened in a window. Safe to call from any thread. */
    fun requestApp(packageName: String, activityName: String, label: String) {
        pendingApps.add(AppRequest(packageName, activityName, label))
    }

    /** Feed a head-orientation quaternion for the camera. Safe to call from any thread. */
    fun setHeadPose(w: Float, x: Float, y: Float, z: Float) {
        headW = w
        headX = x
        headY = y
        headZ = z
    }

    /** Switch how the scene tracks the head. Safe to call from any thread. */
    fun setViewMode(mode: ViewMode) {
        if (viewMode == mode) return
        val prev = viewMode
        viewMode = mode
        when {
            // Lock from FREE → snap the locked view onto the screen the user was looking at.
            prev == ViewMode.FREE && mode == ViewMode.PINNED -> anchorPinnedOnProminentScreen()
            // Unlock from PINNED → reset the FREE scene anchor so it appears centred on
            // wherever the user is currently looking (rather than drifting off-axis from
            // wherever it happened to be world-fixed before).
            prev == ViewMode.PINNED && mode == ViewMode.FREE -> alignVerticalToHead()
        }
        // Each mode keeps its own (zoom, tx, ty); rebuild the projection so the new
        // mode's saved zoom takes effect immediately on swap.
        bandDirty = true
        Log.i(
            TAG,
            "setViewMode $mode — zoom=${"%.3f".format(activeZoom())} " +
                "tx=${"%.3f".format(activeTx())} ty=${"%.3f".format(activeTy())}",
        )
    }

    /**
     * Recenter every window vertically on the user's current head pitch — useful when
     * the workspace has drifted above / below the natural eye line in FREE mode (or
     * when the user wants to sit/stand and have the screens follow). Computes the
     * head-forward Y component and shifts each window so a quad at the workspace
     * distance lands on that line. PINNED mode: still applies, since the head pose
     * is fed even though the view matrix is identity.
     */
    /**
     * Recenter the scene to the current head pose — VR-style "reset view". The whole
     * workspace (every open window, the desktop, the taskbar) rotates as one rigid scene so
     * that the part the user was looking at the moment they pressed the button is now
     * straight ahead. Switches sitting ↔ lying-down without having to drag every screen.
     *
     * No-op in PINNED mode (the scene is already locked to the viewport — there's nothing
     * to recenter against).
     */
    /**
     * Switch the active layout: stash the outgoing layout's slot→app allocation,
     * rebuild the per-screen desktops, then restore the incoming layout's saved apps onto
     * their remembered screens (if any). Force-closes apps on the outgoing layout.
     */
    fun applyLayout(next: Layout) {
        glTasks.add {
            // Save the outgoing layout's slot→app allocation so cycling back to it
            // restores those apps onto the same screens. Filter out entries whose
            // VirtualDisplay no longer has an activity (user backed out of the app),
            // so a relaunch on return doesn't bring back something the user already
            // closed.
            val hasActivity = WorkspaceController.displayHasActivity
            val live = if (hasActivity == null) {
                currentScreenApps.toMap()
            } else {
                currentScreenApps.filter { (screenIdx, _) ->
                    val id = screenDisplayId(screenIdx)
                    id != null && hasActivity(id)
                }
            }
            if (live.isNotEmpty()) {
                layoutAppMemory[layout] = live
                Log.i(
                    TAG,
                    "applyLayout: saved ${live.size}/${currentScreenApps.size} app(s) for ${layout.displayName}",
                )
            } else {
                layoutAppMemory.remove(layout)
            }
            // Force-stop the outgoing apps — their screens are about to be released.
            val outgoingPkgs = currentScreenApps.values.map { it.packageName }
            currentScreenApps.clear()
            outgoingPkgs.forEach { closeAndNotify(it) }

            layout = next
            val screens = next.screens
            Log.i(TAG, "applyLayout ${next.displayName} screens=${screens.size}")
            // Bring the screen list up to the layout's screen count — screen 0 reuses
            // the primary `desktop`, screens 1..N-1 are independent UiScreens.
            syncScreens(screens.size)

            // Restore previously-saved apps for the incoming layout — schedule them so
            // they fire after the screens have had time to come up on the main
            // thread (UiScreen.startTrusted is posted async).
            val saved = layoutAppMemory[next] ?: emptyMap()
            saved.forEach { (screenIdx, req) ->
                if (screenIdx < screens.size) {
                    Log.i(
                        TAG,
                        "applyLayout: scheduling restore of ${req.packageName} on screen $screenIdx",
                    )
                    mainHandler.postDelayed(
                        { pendingApps.add(req.copy(screenIdx = screenIdx)) },
                        300L,
                    )
                }
            }
        }
        noteInput()
    }

    /**
     * Make the screen list have exactly `screenCount` UiScreens (screen 0 = the primary
     * `desktop`; screens 1..N-1 = independents in `extraScreens`). Creating an extra
     * UiScreen also kicks off the per-screen DesktopPresentation on its own VirtualDisplay
     * via [WorkspaceController.desktopContent] — each screen is a fully independent UxSpace
     * environment (own wallpaper, drawer, taskbar). GL thread only.
     */
    private fun syncScreens(screenCount: Int) {
        val factory = WorkspaceController.desktopContent
        if (factory == null) {
            Log.w(TAG, "syncScreens: no desktop factory registered yet — skipping")
            return
        }
        // Rebuild every screen desktop on layout switch — screen.showTaskbar is baked into
        // the DesktopPresentation at construction, so config changes per screen need a
        // fresh Presentation. Cheap (just tears down the per-screen Presentation).
        desktop?.release()
        desktop = null
        extraScreens.forEach { it.release() }
        extraScreens.clear()

        layout.screens.forEachIndexed { i, screen ->
            // The backing VirtualDisplay matches the screen's declared resolution, so an
            // app launched onto a wide / vertical screen actually lays out for that shape
            // (true 3840×1200 ultrawide, 1080×1920 portrait) instead of getting a 1080p
            // surface stretched onto the screen's quad.
            val ui = UiScreen(
                createExternalTexture(),
                screen.contentWidthPx, screen.contentHeightPx,
                mainHandler,
                if (i == 0) "uxspace-desktop" else "uxspace-desktop-screen$i",
            )
            if (i == 0) desktop = ui else extraScreens.add(ui)
            val showTb = screen.showTaskbar
            val screenIdx = i
            mainHandler.post {
                // Trusted display so apps can also be launched onto this same display —
                // the per-screen Presentation (wallpaper + taskbar) sits underneath any
                // activity stacked on top by launchOnDisplay.
                ui.startTrusted(context) { ctx, disp -> factory(ctx, disp, screenIdx, showTb) }
            }
            Log.i(TAG, "syncScreens: screen $screenIdx ready (showTaskbar=$showTb)")
        }
    }

    fun alignVerticalToHead() {
        Log.i(TAG, "alignVerticalToHead() queued — viewMode=$viewMode screens=${layout.screens.size}")
        glTasks.add {
            if (viewMode == ViewMode.PINNED) {
                Log.i(TAG, "alignVerticalToHead: PINNED mode — recenter is a no-op")
                return@add
            }
            val w0 = headW; val x0 = headX; val y0 = headY; val z0 = headZ
            val norm = w0 * w0 + x0 * x0 + y0 * y0 + z0 * z0
            if (norm < 1e-6f) {
                Log.w(TAG, "alignVerticalToHead: head pose is zero — no anchor set")
                return@add
            }
            // anchor = head⁻¹ (conjugate / |head|² for a unit quaternion). Effective view
            // rotation = head * anchor, so right now that product is identity → scene
            // straight ahead. As the user moves, the offset is preserved.
            val inv = 1f / norm
            anchorW = w0 * inv
            anchorX = -x0 * inv
            anchorY = -y0 * inv
            anchorZ = -z0 * inv
            Log.i(
                TAG,
                "alignVerticalToHead: head=(${"%.3f".format(w0)},${"%.3f".format(x0)}," +
                    "${"%.3f".format(y0)},${"%.3f".format(z0)}) -> " +
                    "anchor=(${"%.3f".format(anchorW)},${"%.3f".format(anchorX)}," +
                    "${"%.3f".format(anchorY)},${"%.3f".format(anchorZ)})",
            )
        }
        noteInput()
    }

    /** Request a PNG snapshot of the next rendered frame. Safe to call from any thread. */
    fun requestCapture() {
        captureRequested = true
    }

    /**
     * Toggle a capture-every-Nth-frame "video" recording — the workspace surface is
     * saved as a PNG sequence under the same captures directory as [requestCapture],
     * one file per [RECORDING_FRAME_INTERVAL]th GL frame. For ad-hoc debugging of
     * cursor / touch behaviour: start, perform the gesture, stop, browse the frames.
     */
    fun setRecording(on: Boolean) {
        recording = on
        recordingFrameCounter = 0
    }

    /** Whether the workspace is currently being recorded. */
    fun isRecording(): Boolean = recording

    /** Accumulate a scroll delta (fraction of the touchpad height). Safe from any thread. */
    fun requestScroll(dyFraction: Float) {
        pendingScroll += dyFraction
        noteInput()
    }

    /** Accumulate a pinch scale factor (1.0 = identity). Safe from any thread. */
    fun requestPinch(scaleFactor: Float) {
        pendingPinch *= scaleFactor
        Log.d(TAG, "requestPinch scale=${"%.4f".format(scaleFactor)} pending=${"%.4f".format(pendingPinch)}")
        noteInput()
    }

    /**
     * Begin / end / cancel a long-press gesture. In the old per-window model these were
     * the entry points for a window-drag or app touch-injection sequence. In the new
     * per-screen model both gestures are gone: window frames don't exist (apps fill screens),
     * and taps are dispatched per-frame via [handleClick] / [handleScroll]. Kept as
     * no-ops so the trackpad's existing wiring still compiles; safe to remove from
     * TrackpadView and the controller in a follow-up cleanup pass.
     */
    fun beginDrag() {
        noteInput()
    }

    fun endDrag() {
        noteInput()
    }

    fun cancelDrag() {
        noteInput()
    }

    /**
     * Lift the app windows out of the way while the app drawer is open. The drawer lives on
     * the desktop plane behind the windows, so it would otherwise be occluded by them.
     */
    fun setDrawerOpen(open: Boolean) {
        drawerOpen = open
    }

    /** Move the cursor by a fraction of the touchpad's width. Safe to call from any thread. */
    fun moveCursor(dxFraction: Float, dyFraction: Float) {
        cursorX = (cursorX + dxFraction * CURSOR_SENSITIVITY).coerceIn(-1f, 1f)
        cursorY = (cursorY - dyFraction * CURSOR_SENSITIVITY).coerceIn(-1f, 1f)
        noteInput()
    }

    /** Register a cursor click. Safe to call from any thread. */
    fun cursorClick() {
        cursorClickPending = true
        noteInput()
    }

    /** Bump the idle timer — any touchpad activity wakes the cursor. */
    private fun noteInput() {
        lastInputAtMs = android.os.SystemClock.uptimeMillis()
    }

    /**
     * Force-stop every launched app — called when the workspace is torn down (the glasses
     * are unplugged), so the apps close instead of being relocated onto the phone's screen.
     */
    fun closeAllWindows() {
        val pkgs = currentScreenApps.values.map { it.packageName }
        currentScreenApps.clear()
        pkgs.forEach { closeAndNotify(it) }
    }

    /** Force-stop an app and notify taskbar listeners so they drop its icon. */
    private fun closeAndNotify(packageName: String) {
        WorkspaceController.closeApp?.invoke(packageName)
        mainHandler.post { WorkspaceController.notifyAppClosed(packageName) }
    }

    /** Release the desktop, every screen, and their GL resources. Call on the GL thread. */
    fun releaseAll() {
        desktop?.release()
        desktop = null
        extraScreens.forEach { it.release() }
        extraScreens.clear()
        drawer?.release()
        drawer = null
        currentScreenApps.clear()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.03f, 0.04f, 0.06f, 1f)

        // A fresh GL context — discard anything bound to a previous one.
        releaseAll()

        screenProgram = buildProgram(SCREEN_VERTEX_SHADER, SCREEN_FRAGMENT_SHADER)
        screenAPosition = GLES20.glGetAttribLocation(screenProgram, "aPosition")
        screenATexCoord = GLES20.glGetAttribLocation(screenProgram, "aTexCoord")
        screenUMvp = GLES20.glGetUniformLocation(screenProgram, "uMvp")
        screenUTexMatrix = GLES20.glGetUniformLocation(screenProgram, "uTexMatrix")
        screenUTexture = GLES20.glGetUniformLocation(screenProgram, "uTexture")

        cursorProgram = buildProgram(CURSOR_VERTEX_SHADER, CURSOR_FRAGMENT_SHADER)
        cursorAPosition = GLES20.glGetAttribLocation(cursorProgram, "aPosition")
        cursorUCenter = GLES20.glGetUniformLocation(cursorProgram, "uCenter")
        cursorUHalfSize = GLES20.glGetUniformLocation(cursorProgram, "uHalfSize")
        cursorUColor = GLES20.glGetUniformLocation(cursorProgram, "uColor")

        screenQuad = directBufferOf(SCREEN_QUAD_VERTICES)
        cursorArrow = directBufferOf(CURSOR_ARROW_VERTICES)
        scrimQuad = directBufferOf(SCRIM_QUAD_VERTICES)

        // Slot desktops are built later by syncScreens (triggered by the queued
        // applyLayout task from WorkspaceController.register) — screen 0 included, so
        // each per-screen Presentation gets its own showTaskbar config baked in.

        // The app-drawer overlay — its own UI surface, drawn in front of the windows.
        WorkspaceController.drawerContent?.let { drawerFactory ->
            val du = UiScreen(
                createExternalTexture(), DRAWER_WIDTH_PX, DRAWER_HEIGHT_PX, mainHandler,
                "uxspace-drawer",
            )
            drawer = du
            mainHandler.post { du.start(context, drawerFactory) }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        applyBand()
    }

    /**
     * Render the workspace into a centred 16:9 screen sized to [fraction] of the display
     * height. The glasses cover a ~52° field of view; using the whole panel — especially an
     * ultra-wide shape — is tiring, so the scene is a normal 16:9 screen, smaller and
     * centred. Safe to call from any thread.
     */
    fun setScreenBand(fraction: Float) {
        screenBand = fraction.coerceIn(MIN_SCREEN_BAND, MAX_SCREEN_BAND)
        bandDirty = true
    }

    /**
     * Apply the render band — a centred 16:9 viewport sized to the band fraction. 16:9 is the
     * comfortable screen shape and matches the desktop and app surfaces. GL thread only.
     */
    private fun applyBand() {
        bandDirty = false
        if (surfaceWidth == 0 || surfaceHeight == 0) return
        // Largest 16:9 rectangle that is `screenBand` of the display height and fits its width.
        var areaH = (surfaceHeight * screenBand).toInt()
        var areaW = areaH * 16 / 9
        if (areaW > surfaceWidth) {
            areaW = surfaceWidth
            areaH = areaW * 9 / 16
        }
        areaW = areaW.coerceAtLeast(1)
        areaH = areaH.coerceAtLeast(1)
        GLES20.glViewport((surfaceWidth - areaW) / 2, (surfaceHeight - areaH) / 2, areaW, areaH)

        surfaceAspect = areaW.toFloat() / areaH.toFloat()
        Matrix.perspectiveM(projection, 0, FOV_Y_DEGREES, surfaceAspect, NEAR_PLANE, FAR_PLANE)
        // Pinch zoom on top — scale projection X/Y to magnify around the saved cursor
        // anchor for the active view mode. NDC math: x_ndc = zoom * x_ndc_orig + tx.
        //   projection[0,5] *= rawZoom .......... scales NDC around (0,0)
        //   projection[8,9] = -tx, -ty .......... NDC translation so the anchor stays put
        // rawZoom = displayedZoom × layout.zoomBaseScale — the per-layout multiplier
        // hides the fact that some presets (Wide, V) have smaller default world-size, so
        // the displayed % means the same visual scale across all presets.
        val rawZoom = activeZoom() * layout.zoomBaseScale
        val tx = activeTx()
        val ty = activeTy()
        projection[0] *= rawZoom
        projection[5] *= rawZoom
        projection[8] = -tx
        projection[9] = -ty

        // Size the desktop quad to exactly fill the field of view at its distance.
        val halfFov = Math.toRadians(FOV_Y_DEGREES / 2.0)
        desktopHalfHeight = (DESKTOP_DISTANCE * Math.tan(halfFov)).toFloat()
        desktopHalfWidth = desktopHalfHeight * surfaceAspect
    }

    override fun onDrawFrame(gl: GL10?) {
        if (bandDirty) applyBand()
        drainGlTasks()
        drainPendingApps()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (surfaceWidth == 0 || surfaceHeight == 0) return

        buildView(viewMatrix)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, viewMatrix, 0)

        GLES20.glUseProgram(screenProgram)

        // Slot desktops — one *independent* UxSpace environment per layout screen, drawn
        // at the screen's geometry. Screen 0 = the primary `desktop`; screens 1..N-1 =
        // `extraScreens`. Each one is its own VirtualDisplay-hosted Presentation
        // (own wallpaper / drawer / taskbar). The layout is "how many of these
        // are stitched together in 3D and where."
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        layout.screens.forEachIndexed { i, screen ->
            val d = if (i == 0) desktop else extraScreens.getOrNull(i - 1)
            if (d == null) return@forEachIndexed
            d.updateTexture()
            val r = Screen.worldRect(screen, desktopHalfWidth, desktopHalfHeight)
            if (screen.curveDeg > 0f) {
                drawCurvedScreen(d, screen, r[0], r[1], r[2])
            } else {
                buildModelRectYawed(modelMatrix, r[0], r[1], r[2], r[3], r[4], screen.yawDeg)
                Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
                drawExternalQuad(d.textureId, d.textureMatrix)
            }
        }

        // The app drawer — a dimming scrim over everything, then the drawer panel on top.
        if (drawerOpen) {
            drawScrim()
            drawDrawerPanel()
        }

        if (cursorClickPending) {
            cursorClickPending = false
            cursorFlashFrames = CURSOR_FLASH_FRAMES
            handleClick()
        }
        if (pendingScroll != 0f) {
            handleScroll(pendingScroll)
            pendingScroll = 0f
        }
        if (pendingPinch != 1f) {
            handlePinch(pendingPinch)
            pendingPinch = 1f
        }
        drawToolbar()
        drawCursor()

        if (captureRequested) {
            captureRequested = false
            saveFrameCapture()
        }
        if (recording) {
            if (recordingFrameCounter % RECORDING_FRAME_INTERVAL == 0) {
                saveFrameCapture()
            }
            recordingFrameCounter++
        }
    }

    /** Draw a [screenProgram] quad textured with an external-OES texture; [mvpMatrix] is set. */
    /**
     * Render a screen's UiScreen as N angled segments arranged on a cylinder around the
     * viewer — the curved-ultrawide visual. The flat screen at world centre (cx, cy, cz)
     * of size (w, h) gets sliced into [CURVED_SEGMENTS] vertical strips; each strip is
     * positioned on the arc subtending [arcDeg] and shows its 1/N slice of the screen's
     * texture (via a slice * surfaceTransform texture matrix).
     */
    private fun drawCurvedScreen(
        ui: UiScreen,
        screen: Screen,
        cx: Float, cy: Float, cz: Float,
    ) {
        val n = CURVED_SEGMENTS
        val radius = -cz  // distance from origin (z is negative in front of the camera)
        val arcRad = Math.toRadians(screen.curveDeg.toDouble()).toFloat()
        val arcLength = radius * arcRad
        // Height keeps the screen's content aspect, applied to the *arc length* — so a
        // wider arc means a proportionally taller display (the ultrawide aspect is
        // preserved end-to-end on the cylinder). Independent of widthFraction.
        val aspect = screen.contentWidthPx.toFloat() / screen.contentHeightPx
        val halfH = arcLength / aspect / 2f
        // Build a single triangle-strip mesh that wraps around the cylinder — N+1 vertex
        // columns sharing edges, so there are zero gaps between segments (flat-quads
        // can't tile a curve without gaps in Z; a strip with shared verts can).
        val buf = curvedMeshBuffer(n + 1)
        var off = 0
        for (i in 0..n) {
            val tNorm = i.toFloat() / n
            val theta = -arcRad / 2f + tNorm * arcRad
            val sin = kotlin.math.sin(theta)
            val cos = kotlin.math.cos(theta)
            val x = cx + radius * sin
            val z = cz * cos  // cz is negative; multiplying by cos preserves sign
            // Top vertex of column (positive Y), then bottom (so the triangle strip is
            // wound consistently — top0, bot0, top1, bot1, ...).
            buf.put(off++, x);              buf.put(off++, cy + halfH); buf.put(off++, z)
            buf.put(off++, tNorm);          buf.put(off++, 1f)
            buf.put(off++, x);              buf.put(off++, cy - halfH); buf.put(off++, z)
            buf.put(off++, tNorm);          buf.put(off++, 0f)
        }

        GLES20.glUseProgram(screenProgram)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(screenUMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(screenUTexMatrix, 1, false, ui.textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ui.textureId)
        GLES20.glUniform1i(screenUTexture, 0)
        bindQuad(buf, screenAPosition, screenATexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, (n + 1) * 2)
    }

    /** Reusable FloatBuffer for the curve mesh — resized lazily; avoids per-frame alloc. */
    private var curvedMeshBuf: FloatBuffer? = null
    private fun curvedMeshBuffer(columns: Int): FloatBuffer {
        val needed = columns * 2 * FLOATS_PER_VERTEX
        var b = curvedMeshBuf
        if (b == null || b.capacity() < needed) {
            b = ByteBuffer.allocateDirect(needed * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            curvedMeshBuf = b
        }
        b.position(0)
        return b
    }

    private fun drawExternalQuad(textureId: Int, textureMatrix: FloatArray) {
        GLES20.glUniformMatrix4fv(screenUMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(screenUTexMatrix, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(screenUTexture, 0)
        bindQuad(screenQuad, screenAPosition, screenATexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
    }

    /** Run window-control actions queued from the main thread. GL thread only. */
    private fun drainGlTasks() {
        var task = glTasks.poll()
        while (task != null) {
            task()
            task = glTasks.poll()
        }
    }

    /** Realise every queued app launch onto its screen's display. GL thread only. */
    private fun drainPendingApps() {
        var request = pendingApps.poll()
        while (request != null) {
            addWindow(request)
            request = pendingApps.poll()
        }
    }

    private fun addWindow(request: AppRequest) {
        // Per-screen model: an app launch lands on the screen's existing *trusted*
        // VirtualDisplay (created by syncScreens). The screen's own Presentation
        // (wallpaper + taskbar) sits underneath the launched activity. The launch
        // target screen is either explicit (layout-restore) or the screen under the cursor.
        val targetIdx = request.screenIdx?.coerceIn(0, layout.screens.size - 1)
            ?: screenIndexUnderCursor()
            ?: 0
        val targetUi = if (targetIdx == 0) desktop else extraScreens.getOrNull(targetIdx - 1)
        if (targetUi == null) {
            Log.w(TAG, "addWindow: screen $targetIdx not ready yet — dropping launch of ${request.packageName}")
            return
        }
        val launch = WorkspaceController.appLauncher
        if (launch == null) {
            Log.w(TAG, "addWindow: no app launcher wired — dropping launch of ${request.packageName}")
            return
        }
        // Record the screen assignment up-front so a layout switch fired before the
        // launch completes still saves the right app.
        currentScreenApps[targetIdx] = request.copy(screenIdx = targetIdx)
        mainHandler.post {
            val screenDisplayId = targetUi.displayId
            if (screenDisplayId == null) {
                // Slot display still spinning up (it's posted async to the main thread
                // from syncScreens). Re-queue with a short delay; addWindow drains
                // pendingApps each frame, so this retries until the display arrives.
                Log.i(TAG, "addWindow: screen $targetIdx display not ready — retrying launch")
                mainHandler.postDelayed(
                    { pendingApps.add(request.copy(screenIdx = targetIdx)) },
                    150L,
                )
                return@post
            }
            WorkspaceController.registerUxSpaceDisplay(screenDisplayId)
            launch(screenDisplayId, request.packageName, request.activityName)
            // Per-screen taskbar filter — fire onAppLaunched now that we know the screen,
            // so only the target screen's DesktopPresentation adds this app to its bar.
            WorkspaceController.notifyAppLaunchedOnScreen(
                request.packageName, request.label, targetIdx,
            )
            Log.i(
                TAG,
                "addWindow: launched ${request.packageName} on screen $targetIdx display=$screenDisplayId",
            )
        }
    }

    /**
     * Move an app currently launched on some screen of the active layout to the next screen
     * (cyclic). Closes the app on the source screen via the wired `closeApp` hook (which
     * fires the closed listener for taskbar cleanup) and re-queues it onto the next screen
     * via `pendingApps` so the normal addWindow path handles the launch + bookkeeping.
     */
    fun moveAppToNextScreen(packageName: String) {
        glTasks.add {
            val n = layout.screens.size
            if (n < 2) {
                Log.i(TAG, "moveAppToNextScreen: layout has only $n screen(s) — nothing to do")
                return@add
            }
            val entry = currentScreenApps.entries.firstOrNull { it.value.packageName == packageName }
            if (entry == null) {
                Log.w(TAG, "moveAppToNextScreen: $packageName not on any screen")
                return@add
            }
            val srcSlot = entry.key
            val req = entry.value
            val dstSlot = (srcSlot + 1) % n
            Log.i(TAG, "moveAppToNextScreen: $packageName screen$srcSlot -> $dstSlot")
            currentScreenApps.remove(srcSlot)
            closeAndNotify(packageName)
            // Small delay to let the force-stop settle before the relaunch.
            mainHandler.postDelayed(
                { pendingApps.add(req.copy(screenIdx = dstSlot)) },
                250L,
            )
        }
    }

    /**
     * Slot index whose world rectangle (after the current view's zoom/anchor + per-screen
     * yaw) contains the cursor, or null if the cursor misses every screen. Uses the same
     * ray-plane intersection as [cursorToRectPx] so tilted side screens in SBS / V-H-V
     * are hit correctly.
     */
    private fun screenIndexUnderCursor(): Int? {
        layout.screens.forEachIndexed { i, screen ->
            if (cursorToRectPx(screen) != null) return i
        }
        return null
    }

    /**
     * Show-desktop toggle. In the per-screen model "show desktop" means closing any apps
     * stacked on the screens so the per-screen DesktopPresentation (wallpaper) is
     * visible again. Any thread.
     */
    fun toggleShowDesktop() {
        glTasks.add {
            if (currentScreenApps.isEmpty()) return@add
            val pkgs = currentScreenApps.values.map { it.packageName }
            currentScreenApps.clear()
            pkgs.forEach { closeAndNotify(it) }
        }
    }

    /**
     * Bring the named app to the foreground of whichever screen it's currently on by
     * re-launching it onto the same screen's display — Android brings an existing activity
     * forward. No-op if the app isn't tracked as on any screen. Any thread.
     */
    fun focusApp(packageName: String) {
        glTasks.add {
            val entry = currentScreenApps.entries.firstOrNull { it.value.packageName == packageName }
                ?: return@add
            pendingApps.add(entry.value.copy(screenIdx = entry.key))
        }
    }

    /** Force-stop the named app and drop it from its screen's tracking. Any thread. */
    fun closeAppByPackage(packageName: String) {
        glTasks.add {
            val entry = currentScreenApps.entries.firstOrNull { it.value.packageName == packageName }
                ?: return@add
            currentScreenApps.remove(entry.key)
            closeAndNotify(packageName)
        }
    }

    /**
     * Draw the touchpad cursor — a flat arrow overlay on top of everything. The hand /
     * grab sprite that used to appear during a window-drag is gone with the per-app
     * window model; the new per-screen world has no draggable window frames.
     */
    private fun drawCursor() {
        // Hide the cursor after CURSOR_IDLE_TIMEOUT_MS of no touchpad input — any move /
        // scroll / click / drag bumps lastInputAtMs and brings it back.
        if (android.os.SystemClock.uptimeMillis() - lastInputAtMs > CURSOR_IDLE_TIMEOUT_MS) {
            return
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        val flashing = cursorFlashFrames > 0
        if (flashing) cursorFlashFrames--
        GLES20.glUseProgram(cursorProgram)
        cursorArrow.position(0)
        GLES20.glVertexAttribPointer(
            cursorAPosition, 2, GLES20.GL_FLOAT, false, ARROW_STRIDE_BYTES, cursorArrow,
        )
        GLES20.glEnableVertexAttribArray(cursorAPosition)
        val outline = CURSOR_SCALE * 0.07f
        GLES20.glUniform2f(cursorUCenter, cursorX - outline, cursorY + outline)
        GLES20.glUniform2f(
            cursorUHalfSize, CURSOR_SCALE * 1.18f, CURSOR_SCALE * 1.18f * surfaceAspect,
        )
        GLES20.glUniform4f(cursorUColor, 0.04f, 0.04f, 0.07f, 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, CURSOR_ARROW_VERTEX_COUNT)

        GLES20.glUniform2f(cursorUCenter, cursorX, cursorY)
        GLES20.glUniform2f(cursorUHalfSize, CURSOR_SCALE, CURSOR_SCALE * surfaceAspect)
        if (flashing) {
            GLES20.glUniform4f(cursorUColor, 0.31f, 0.76f, 0.97f, 1f)
        } else {
            GLES20.glUniform4f(cursorUColor, 1f, 1f, 1f, 1f)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, CURSOR_ARROW_VERTEX_COUNT)
    }

    /**
     * Resolve a cursor click. App windows sit in front of the desktop, so try them first:
     * a hit on the title bar dispatches a tap into the chrome's own view tree (its buttons),
     * a hit on the app content injects a tap into that app's display via Shizuku; otherwise
     * the click goes to the desktop's own One UI view tree.
     */
    private fun handleClick() {
        // In-view toolbar consumes the click first when expanded and a button is under
        // the cursor — its actions run on the main thread.
        if (toolbarExpanded) {
            val hit = toolbarButtonAtCursor()
            if (hit != null) {
                Log.i(TAG, "toolbar button '${hit.label}' clicked")
                mainHandler.post { hit.action() }
                toolbarLastShownMs = android.os.SystemClock.uptimeMillis()
                return
            }
        }
        // While the drawer is open it is modal: a tap on the panel goes to the drawer, a tap
        // on the scrim outside it closes the drawer.
        if (drawerOpen) {
            val px = drawer?.let {
                val r = drawerWorld()
                cursorToRectPx(r[0], r[1], r[2], r[3], r[4], DRAWER_WIDTH_PX, DRAWER_HEIGHT_PX)
            }
            if (px != null) {
                val d = drawer
                if (d != null) mainHandler.post { d.dispatchTap(px[0], px[1]) }
            } else {
                WorkspaceController.setDrawerOpen(false)
            }
            return
        }
        // Per-screen routing: find which screen the cursor is over, compute screen-local pixel
        // coords, inject the tap via the privileged helper. Same path reaches both the
        // per-screen Presentation (when no app is launched there) and any activity stacked
        // on top — the input dispatcher routes to the topmost window on that display.
        val screenIdx = screenIndexUnderCursor() ?: return
        val screen = layout.screens[screenIdx]
        val displayId = screenDisplayId(screenIdx) ?: return
        val px = cursorToRectPx(screen) ?: return
        WorkspaceController.appTap?.invoke(displayId, px[0].toInt(), px[1].toInt())
        Log.d(
            TAG,
            "handleClick screen=$screenIdx display=$displayId px=(${px[0].toInt()},${px[1].toInt()})",
        )
    }

    /**
     * Dispatch an accumulated scroll delta. The drawer overlay is modal — when it's open,
     * scroll goes to its panel (or nowhere on the scrim), never to the windows behind it.
     * Otherwise: topmost non-minimised app window's content first (injected through the
     * privileged helper as a touch-swipe, since shell `input` doesn't expose a wheel
     * scroll), then the desktop.
     */
    private fun handleScroll(dyFraction: Float) {
        val vScroll = dyFraction * SCROLL_SENSITIVITY
        if (drawerOpen) {
            val d = drawer ?: return
            val r = drawerWorld()
            val px = cursorToRectPx(r[0], r[1], r[2], r[3], r[4], DRAWER_WIDTH_PX, DRAWER_HEIGHT_PX)
                ?: return
            mainHandler.post { d.dispatchScroll(px[0], px[1], vScroll) }
            return
        }
        // Same per-screen routing as handleClick: find screen under cursor, inject the scroll
        // into its display via the privileged helper.
        val screenIdx = screenIndexUnderCursor() ?: return
        val screen = layout.screens[screenIdx]
        val displayId = screenDisplayId(screenIdx) ?: return
        val px = cursorToRectPx(screen) ?: return
        WorkspaceController.appScroll?.invoke(displayId, px[0].toInt(), px[1].toInt(), vScroll)
    }

    /**
     * Dispatch an accumulated pinch into the topmost app window under the cursor. Each
     * call sends one discrete two-finger pinch through the privileged helper; held
     * pinches show up as a sequence of small zooms, which Photos / Maps / browsers
     * handle gracefully.
     */
    private fun handlePinch(scale: Float) {
        if (scale == 1f) return
        // Drawer is modal — pinch over it doesn't reach the windows behind.
        if (drawerOpen) {
            Log.i(TAG, "pinch ignored — drawer open")
            return
        }
        when (viewMode) {
            ViewMode.FREE, ViewMode.PINNED -> applyCursorAnchoredZoom(scale)
        }
    }

    /**
     * Apply a cursor-anchored projection zoom for the active view mode. Updates the
     * mode's saved (zoom, tx, ty) so the world point currently under the cursor stays
     * under the cursor as the zoom changes — same model as a desktop browser's
     * Ctrl-scroll zoom. The two modes keep independent zoom state.
     */
    private fun applyCursorAnchoredZoom(scale: Float) {
        val oldZoom = activeZoom()
        val newZoom = (oldZoom * scale).coerceIn(WORKSPACE_ZOOM_MIN, WORKSPACE_ZOOM_MAX)
        // Effective scale after clamp — keeps the anchor math consistent at the limits.
        val effScale = if (oldZoom > 0f) newZoom / oldZoom else 1f
        val oldTx = activeTx()
        val oldTy = activeTy()
        val newTx = cursorX * (1f - effScale) + oldTx * effScale
        val newTy = cursorY * (1f - effScale) + oldTy * effScale
        if (viewMode == ViewMode.FREE) {
            freeZoom = newZoom; freeTx = newTx; freeTy = newTy
        } else {
            pinnedZoom = newZoom; pinnedTx = newTx; pinnedTy = newTy
        }
        bandDirty = true
        Log.i(
            TAG,
            "pinch $viewMode scale=${"%.3f".format(scale)} zoom " +
                "${"%.3f".format(oldZoom)} -> ${"%.3f".format(newZoom)} " +
                "anchor=(${"%.2f".format(cursorX)},${"%.2f".format(cursorY)}) " +
                "tx=${"%.3f".format(newTx)} ty=${"%.3f".format(newTy)}",
        )
        mainHandler.post { WorkspaceController.notifyZoomChanged(newZoom) }
    }

    /**
     * Set the active view-mode's projection zoom (and reset its anchor to scene centre)
     * — the toolbar zoom button calls this with discrete values, so bigger %
     * really does mean bigger / closer scene (matches the pinch direction).
     */
    fun setWorkspaceZoom(zoom: Float) {
        val z = zoom.coerceIn(WORKSPACE_ZOOM_MIN, WORKSPACE_ZOOM_MAX)
        if (viewMode == ViewMode.FREE) {
            freeZoom = z; freeTx = 0f; freeTy = 0f
        } else {
            pinnedZoom = z; pinnedTx = 0f; pinnedTy = 0f
        }
        bandDirty = true
        Log.i(TAG, "setWorkspaceZoom $viewMode -> ${"%.3f".format(z)}")
        mainHandler.post { WorkspaceController.notifyZoomChanged(z) }
    }

    /** Read the active view-mode's projection zoom. */
    fun workspaceZoom(): Float = activeZoom()

    private fun activeZoom(): Float = if (viewMode == ViewMode.FREE) freeZoom else pinnedZoom
    private fun activeTx(): Float = if (viewMode == ViewMode.FREE) freeTx else pinnedTx
    private fun activeTy(): Float = if (viewMode == ViewMode.FREE) freeTy else pinnedTy

    /**
     * On FREE→PINNED swap, find the layout screen the user is currently looking at (closest
     * to the head's forward ray hitting the desktop plane) and shift the PINNED view's
     * NDC translation so that screen lands centred. So locking from a sideways head pose
     * doesn't snap the workspace away from what the user was just focused on.
     */
    private fun anchorPinnedOnProminentScreen() {
        val screens = layout.screens
        if (screens.isEmpty()) return
        // Head-forward vector in world space — rotate (0,0,-1) by the head quaternion.
        val fwdX = -2f * (headX * headZ + headW * headY)
        val fwdY = 2f * (headW * headX - headY * headZ)
        val fwdZ = -1f + 2f * (headX * headX + headY * headY)
        if (fwdZ >= -1e-3f) {
            Log.i(TAG, "anchorPinnedOnProminentScreen: head not facing scene (fwdZ=$fwdZ) — skipping")
            return
        }
        // Ray from origin along forward, intersected with the desktop plane at z=-SCREEN_DISTANCE.
        val t = -Screen.SCREEN_DISTANCE / fwdZ
        val hitX = t * fwdX
        val hitY = t * fwdY
        // Closest screen by world-space centre.
        var bestIdx = 0
        var bestScreenX = 0f
        var bestScreenY = 0f
        var bestDist = Float.POSITIVE_INFINITY
        screens.forEachIndexed { i, screen ->
            val r = Screen.worldRect(screen, desktopHalfWidth, desktopHalfHeight)
            val dx = r[0] - hitX
            val dy = r[1] - hitY
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d; bestIdx = i; bestScreenX = r[0]; bestScreenY = r[1]
            }
        }
        // Project screen centre to NDC under the current PINNED zoom (no translation yet) —
        // then set pinnedTx/Ty so x_ndc' = pinnedZoom * x_ndc_orig + tx = 0.
        // Using the FOV math: x_ndc_orig = (cot(fov/2)/aspect) * x_world / |z_world|.
        val halfFov = Math.toRadians(FOV_Y_DEGREES / 2.0)
        val cot = (1.0 / Math.tan(halfFov)).toFloat()
        val ndcX = cot / surfaceAspect * bestScreenX / Screen.SCREEN_DISTANCE
        val ndcY = cot * bestScreenY / Screen.SCREEN_DISTANCE
        // Anchor in raw NDC units, which is what applyBand puts into projection[8,9].
        val rawZoom = pinnedZoom * layout.zoomBaseScale
        pinnedTx = -rawZoom * ndcX
        pinnedTy = -rawZoom * ndcY
        Log.i(
            TAG,
            "anchorPinnedOnProminentScreen: hit=(${"%.2f".format(hitX)},${"%.2f".format(hitY)}) " +
                "-> screen[$bestIdx] centre=(${"%.2f".format(bestScreenX)},${"%.2f".format(bestScreenY)}) " +
                "ndc=(${"%.2f".format(ndcX)},${"%.2f".format(ndcY)}) " +
                "tx=${"%.3f".format(pinnedTx)} ty=${"%.3f".format(pinnedTy)}",
        )
    }

    /** World rect (centre x, y, z and size w, h) of the app-drawer panel — centred. */
    private fun drawerWorld(): FloatArray {
        val w = 2f * desktopHalfWidth * DRAWER_WIDTH_FRACTION
        val h = w * DRAWER_HEIGHT_PX / DRAWER_WIDTH_PX
        return floatArrayOf(0f, 0f, -Screen.SCREEN_DISTANCE, w, h)
    }

    /** Draw the dimming scrim behind the drawer — a flat, blended, full-screen quad. */
    /**
     * Render the in-view toolbar. Idle = a 5-px peek line at bottom centre; when the
     * cursor is over the peek (or over the expanded toolbar, or within
     * [TOOLBAR_AUTOHIDE_MS] of the last button click) the toolbar expands into 4
     * coloured buttons. State machine is updated here too, so this is the single point
     * of truth for hover-driven expand/collapse.
     */
    private fun drawToolbar() {
        val now = android.os.SystemClock.uptimeMillis()
        val onPeek = cursorY in (PEEK_Y - PEEK_HALF_H)..(PEEK_Y + PEEK_HALF_H) &&
            cursorX in -PEEK_HALF_W..PEEK_HALF_W
        val onExpanded = toolbarExpanded &&
            cursorY in (TOOLBAR_Y - TOOLBAR_HALF_H)..(TOOLBAR_Y + TOOLBAR_HALF_H) &&
            cursorX in -TOOLBAR_HALF_W..TOOLBAR_HALF_W
        if (onPeek || onExpanded) {
            toolbarExpanded = true
            toolbarLastShownMs = now
        } else if (toolbarExpanded && now - toolbarLastShownMs > TOOLBAR_AUTOHIDE_MS) {
            toolbarExpanded = false
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(cursorProgram)
        scrimQuad.position(0)
        GLES20.glVertexAttribPointer(
            cursorAPosition, 2, GLES20.GL_FLOAT, false, ARROW_STRIDE_BYTES, scrimQuad,
        )
        GLES20.glEnableVertexAttribArray(cursorAPosition)
        if (toolbarExpanded) {
            // Translucent background bar behind the buttons.
            GLES20.glUniform2f(cursorUCenter, 0f, TOOLBAR_Y)
            GLES20.glUniform2f(cursorUHalfSize, TOOLBAR_HALF_W, TOOLBAR_HALF_H)
            GLES20.glUniform4f(cursorUColor, 0f, 0f, 0f, 0.45f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
            // Each button as a coloured quad on top of the background.
            for (b in toolbarButtons) {
                GLES20.glUniform2f(cursorUCenter, b.cx, TOOLBAR_Y)
                GLES20.glUniform2f(cursorUHalfSize, b.halfW, TOOLBAR_HALF_H * 0.78f)
                GLES20.glUniform4f(cursorUColor, b.color[0], b.color[1], b.color[2], b.color[3])
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
            }
        } else {
            // Peek handle — a short bright line at the bottom centre.
            GLES20.glUniform2f(cursorUCenter, 0f, PEEK_Y)
            GLES20.glUniform2f(cursorUHalfSize, PEEK_HALF_W, PEEK_HALF_H)
            GLES20.glUniform4f(cursorUColor, 1f, 1f, 1f, 0.55f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Toolbar button whose NDC rect contains the cursor, or null. */
    private fun toolbarButtonAtCursor(): HudButton? {
        if (cursorY !in (TOOLBAR_Y - TOOLBAR_HALF_H)..(TOOLBAR_Y + TOOLBAR_HALF_H)) return null
        return toolbarButtons.firstOrNull {
            cursorX in (it.cx - it.halfW)..(it.cx + it.halfW)
        }
    }

    private fun drawScrim() {
        GLES20.glUseProgram(cursorProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        scrimQuad.position(0)
        GLES20.glVertexAttribPointer(
            cursorAPosition, 2, GLES20.GL_FLOAT, false, ARROW_STRIDE_BYTES, scrimQuad,
        )
        GLES20.glEnableVertexAttribArray(cursorAPosition)
        GLES20.glUniform2f(cursorUCenter, 0f, 0f)
        GLES20.glUniform2f(cursorUHalfSize, 1f, 1f)
        GLES20.glUniform4f(cursorUColor, 0f, 0f, 0f, SCRIM_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Draw the app-drawer panel quad, in front of the windows. */
    private fun drawDrawerPanel() {
        val d = drawer ?: return
        d.updateTexture()
        val r = drawerWorld()
        GLES20.glUseProgram(screenProgram)
        buildModelRect(modelMatrix, r[0], r[1], r[2], r[3], r[4])
        Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
        drawExternalQuad(d.textureId, d.textureMatrix)
    }

    /** World (x, y) where the cursor ray meets the plane z = [planeZ]; null if it misses. */
    private fun cursorRayHit(planeZ: Float): FloatArray? {
        if (!Matrix.invertM(invViewProjection, 0, viewProjection, 0)) return null
        val near = unproject(cursorX, cursorY, -1f) ?: return null
        val far = unproject(cursorX, cursorY, 1f) ?: return null
        val dirZ = far[2] - near[2]
        if (abs(dirZ) < 1e-5f) return null
        val t = (planeZ - near[2]) / dirZ
        if (t < 0f) return null
        return floatArrayOf(
            near[0] + t * (far[0] - near[0]),
            near[1] + t * (far[1] - near[1]),
        )
    }

    /**
     * Cursor → screen-local pixel `[px, py]`, or null if the cursor misses the screen rect.
     * Accounts for [Screen.yawDeg] by intersecting the cursor ray with the screen's
     * tilted plane (normal rotated by yaw around Y), then unrotating the hit into
     * screen-local space. Flat screens (yaw == 0) use the fast flat-plane path.
     */
    private fun cursorToRectPx(screen: Screen): FloatArray? {
        val r = Screen.worldRect(screen, desktopHalfWidth, desktopHalfHeight)
        val cx = r[0]; val cy = r[1]; val cz = r[2]; val w = r[3]; val h = r[4]
        if (screen.yawDeg == 0f) {
            return cursorToRectPx(cx, cy, cz, w, h, screen.contentWidthPx, screen.contentHeightPx)
        }
        if (!Matrix.invertM(invViewProjection, 0, viewProjection, 0)) return null
        val near = unproject(cursorX, cursorY, -1f) ?: return null
        val far = unproject(cursorX, cursorY, 1f) ?: return null
        val dx = far[0] - near[0]
        val dy = far[1] - near[1]
        val dz = far[2] - near[2]
        val yawRad = Math.toRadians(screen.yawDeg.toDouble())
        val nx = Math.sin(yawRad).toFloat()
        val nz = Math.cos(yawRad).toFloat()
        // n · dir == 0 → ray parallel to plane.
        val denom = nx * dx + nz * dz
        if (abs(denom) < 1e-6f) return null
        val t = (nx * (cx - near[0]) + nz * (cz - near[2])) / denom
        if (t < 0f) return null
        val hx = near[0] + t * dx
        val hy = near[1] + t * dy
        val hz = near[2] + t * dz
        // Inverse-rotate the hit into screen-local space (yaw = 0 there, quad in XY plane).
        val cosNeg = Math.cos(-yawRad).toFloat()
        val sinNeg = Math.sin(-yawRad).toFloat()
        val localX = (hx - cx) * cosNeg + (hz - cz) * sinNeg
        val localY = hy - cy
        val hw = w / 2f
        val hh = h / 2f
        if (localX < -hw || localX > hw || localY < -hh || localY > hh) return null
        return floatArrayOf(
            (localX + hw) / (2f * hw) * screen.contentWidthPx,
            (hh - localY) / (2f * hh) * screen.contentHeightPx,
        )
    }

    /** Slot index → the VirtualDisplay that hosts that screen's UiScreen, or null if not ready. */
    private fun screenDisplayId(idx: Int): Int? {
        val ui = if (idx == 0) desktop else extraScreens.getOrNull(idx - 1)
        return ui?.displayId
    }

    /**
     * Cursor → pixel `[px, py]` within a world-space quad centred at ([x], [y], [z]), of size
     * [w] × [h] metres and [pxW] × [pxH] pixels; null if the cursor ray misses the quad.
     */
    private fun cursorToRectPx(
        x: Float, y: Float, z: Float, w: Float, h: Float, pxW: Int, pxH: Int,
    ): FloatArray? {
        val hit = cursorRayHit(z) ?: return null
        val hw = w / 2f
        val hh = h / 2f
        val lx = hit[0] - x
        val ly = hit[1] - y
        if (lx < -hw || lx > hw || ly < -hh || ly > hh) return null
        return floatArrayOf(
            (lx + hw) / (2f * hw) * pxW,
            (hh - ly) / (2f * hh) * pxH,
        )
    }

    /** Unproject an NDC point to world space via the inverse view-projection. */
    private fun unproject(ndcX: Float, ndcY: Float, ndcZ: Float): FloatArray? {
        clipPoint[0] = ndcX
        clipPoint[1] = ndcY
        clipPoint[2] = ndcZ
        clipPoint[3] = 1f
        Matrix.multiplyMV(worldPoint, 0, invViewProjection, 0, clipPoint, 0)
        val w = worldPoint[3]
        if (abs(w) < 1e-6f) return null
        return floatArrayOf(worldPoint[0] / w, worldPoint[1] / w, worldPoint[2] / w)
    }

    /** Read back the just-rendered frame and save it as a PNG, for off-device inspection. */
    private fun saveFrameCapture() {
        val w = surfaceWidth
        val h = surfaceHeight
        if (w == 0 || h == 0) return
        val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        val rgba = ByteArray(w * h * 4)
        buffer.rewind()
        buffer.get(rgba)
        // glReadPixels rows run bottom-to-top; flip into top-down opaque ARGB pixels.
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val src = (h - 1 - y) * w * 4
            val dst = y * w
            for (x in 0 until w) {
                val i = src + x * 4
                val r = rgba[i].toInt() and 0xFF
                val g = rgba[i + 1].toInt() and 0xFF
                val b = rgba[i + 2].toInt() and 0xFF
                pixels[dst + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        // Mutable bitmap — we draw a debug overlay on it below. `createBitmap(pixels…)`
        // produces an immutable bitmap that Canvas refuses, so allocate then setPixels.
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        // Annotate the capture with what the renderer *thinks* the cursor is doing —
        // a red circle at the cursor's internal NDC position, plus the coords + the
        // current press target. Lets us compare the rendered cursor sprite against the
        // logical state when the two seem to disagree.
        val canvas = android.graphics.Canvas(bitmap)
        val cursorPxX = (cursorX + 1f) / 2f * w
        val cursorPxY = (1f - cursorY) / 2f * h
        val ringPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.RED
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(cursorPxX, cursorPxY, 28f, ringPaint)
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.YELLOW
            textSize = 22f
            setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
        }
        val info = "cursor=(${"%.3f".format(cursorX)},${"%.3f".format(cursorY)})  layout=${layout.displayName}  screens=${layout.screens.size}"
        canvas.drawText(info, 12f, h - 16f, textPaint)
        Thread {
            val dir = File(appContext.getExternalFilesDir(null), "captures").apply { mkdirs() }
            val file = File(dir, "uxspace-${System.currentTimeMillis()}.png")
            val ok = runCatching {
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.isSuccess
            Log.i(TAG, if (ok) "capture saved: ${file.absolutePath}" else "capture save failed")
            mainHandler.post {
                Toast.makeText(
                    appContext,
                    if (ok) "Workspace captured" else "Capture failed",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }.start()
    }

    private fun bindQuad(quad: FloatBuffer, positionHandle: Int, texCoordHandle: Int) {
        quad.position(0)
        GLES20.glVertexAttribPointer(
            positionHandle, POSITION_FLOATS, GLES20.GL_FLOAT, false, STRIDE_BYTES, quad,
        )
        GLES20.glEnableVertexAttribArray(positionHandle)

        quad.position(POSITION_FLOATS)
        GLES20.glVertexAttribPointer(
            texCoordHandle, TEXCOORD_FLOATS, GLES20.GL_FLOAT, false, STRIDE_BYTES, quad,
        )
        GLES20.glEnableVertexAttribArray(texCoordHandle)
    }

    /**
     * Camera view matrix — the inverse of the head rotation, so the scene stays world-fixed
     * as the head turns. Built from the conjugate of the head-orientation quaternion.
     */
    private fun buildView(out: FloatArray) {
        if (viewMode == ViewMode.PINNED) {
            // Pinned: the scene is locked to the viewport — it follows the head.
            Matrix.setIdentityM(out, 0)
            return
        }
        // Compose head pose with the recenter anchor: effective = head * anchor. The view
        // matrix is the inverse of that rotation (conjugate of the unit quaternion).
        val ew = headW * anchorW - headX * anchorX - headY * anchorY - headZ * anchorZ
        val ex = headW * anchorX + headX * anchorW + headY * anchorZ - headZ * anchorY
        val ey = headW * anchorY - headX * anchorZ + headY * anchorW + headZ * anchorX
        val ez = headW * anchorZ + headX * anchorY - headY * anchorX + headZ * anchorW
        val w = ew
        val x = -ex
        val y = -ey
        val z = -ez
        val norm = w * w + x * x + y * y + z * z
        val s = if (norm > 1e-6f) 2f / norm else 0f
        val xs = x * s; val ys = y * s; val zs = z * s
        val wx = w * xs; val wy = w * ys; val wz = w * zs
        val xx = x * xs; val xy = x * ys; val xz = x * zs
        val yy = y * ys; val yz = y * zs; val zz = z * zs
        out[0] = 1f - (yy + zz); out[1] = xy + wz; out[2] = xz - wy; out[3] = 0f
        out[4] = xy - wz; out[5] = 1f - (xx + zz); out[6] = yz + wx; out[7] = 0f
        out[8] = xz + wy; out[9] = yz - wx; out[10] = 1f - (xx + yy); out[11] = 0f
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
    }

    /** Model matrix placing the unit quad at a world-space rect — centre ([x],[y],[z]), size. */
    private fun buildModelRect(
        out: FloatArray, x: Float, y: Float, z: Float, w: Float, h: Float,
    ) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, x, y, z)
        Matrix.scaleM(out, 0, w / 2f, h / 2f, 1f)
    }

    /**
     * Model matrix for a quad with an extra yaw rotation about the Y axis — used by
     * multi-screen presets where side screens are tilted to face the user. Rotation is
     * applied around the screen's own centre (translate, rotate, scale).
     */
    private fun buildModelRectYawed(
        out: FloatArray, x: Float, y: Float, z: Float, w: Float, h: Float, yawDeg: Float,
    ) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, x, y, z)
        if (yawDeg != 0f) Matrix.rotateM(out, 0, yawDeg, 0f, 1f, 0f)
        Matrix.scaleM(out, 0, w / 2f, h / 2f, 1f)
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private companion object {
        const val TAG = "UxSpace/Renderer"

        /** Pixel resolution of the desktop UI surface (16:9). */
        const val DESKTOP_WIDTH_PX = 1920
        const val DESKTOP_HEIGHT_PX = 1080

        /** The desktop sits just behind the launched-app screens, filling the view. */
        const val DESKTOP_DISTANCE = 4.2f

        /** Pixel resolution of the app-drawer panel surface. */
        const val DRAWER_WIDTH_PX = 1400
        const val DRAWER_HEIGHT_PX = 920

        /** The drawer panel's width, as a fraction of the desktop width. */
        const val DRAWER_WIDTH_FRACTION = 0.62f

        /** Opacity of the dimming scrim drawn behind the open drawer. */
        const val SCRIM_ALPHA = 0.55f

        /** Default render band — the glasses' top/bottom edges are uncomfortable to view. */
        const val DEFAULT_SCREEN_BAND = 0.83f

        /** The render band cannot shrink below this fraction of the display. */
        const val MIN_SCREEN_BAND = 0.5f

        /** Hard upper bound on the render band — beyond 1.0 the scene crops top/bottom. */
        const val MAX_SCREEN_BAND = 2.5f

        /** FREE-mode pinch zoom clamps the render band into this range — 80% to 250%. */
        const val PINCH_SCREEN_BAND_MIN = 0.8f
        const val PINCH_SCREEN_BAND_MAX = 2.5f

        /** Pinch zoom clamps the projection scale into this range — 80% to 200%. */
        const val WORKSPACE_ZOOM_MIN = 0.8f
        const val WORKSPACE_ZOOM_MAX = 2.0f

        /** Initial workspace zoom for both PINNED and FREE modes — 120% on startup. */
        const val DEFAULT_WORKSPACE_ZOOM = 1.2f

        // In-view toolbar HUD — NDC layout. Peek = a small bright line at bottom centre;
        // when the cursor is over it (or the expanded toolbar), the toolbar expands.
        const val PEEK_Y = -0.97f
        const val PEEK_HALF_W = 0.06f
        const val PEEK_HALF_H = 0.005f
        const val TOOLBAR_Y = -0.90f
        const val TOOLBAR_HALF_W = 0.42f
        const val TOOLBAR_HALF_H = 0.06f

        /** How long the expanded toolbar lingers after the last hover or click. */
        const val TOOLBAR_AUTOHIDE_MS = 3000L

        /**
         * How many vertical strips approximate a curved screen. Each strip is a flat
         * yawed quad — fewer strips ⇒ visible folds at boundaries (each step is a
         * larger yaw delta); more ⇒ smoother curve, more draws.
         */
        const val CURVED_SEGMENTS = 24

        /**
         * Per-segment world width = chord × overlap. 1.0 = exact butt-join; >1 causes
         * the strips to overlap geometrically and draw on top of each other at the
         * seam (without depth test the last one wins, doubling the seam edge).
         */
        const val CURVED_SEG_OVERLAP = 1.0

        /** Cursor scale (NDC), motion per touchpad-width, and click-flash duration. */
        const val CURSOR_SCALE = 0.01405f
        const val CURSOR_SENSITIVITY = 1.0f

        /** Capture every Nth render frame while recording — 12 ≈ 5 fps at a 60 Hz GL loop. */
        const val RECORDING_FRAME_INTERVAL = 12
        const val CURSOR_FLASH_FRAMES = 12

        /** How long with no touchpad input before the cursor is hidden. */
        const val CURSOR_IDLE_TIMEOUT_MS = 5_000L

        /** Starting pinch span (pixels between two fingers) per dispatched pinch. */
        const val PINCH_BASE_SPAN_PX = 400

        /** Clamps for the per-pinch ending span so a frame can't generate a degenerate gesture. */
        const val PINCH_MIN_SPAN_PX = 40
        const val PINCH_MAX_SPAN_PX = 1600

        /** Duration of one dispatched pinch — short so a held pinch chains smoothly. */
        const val PINCH_DURATION_MS = 80

        /** Scroll units (AXIS_VSCROLL) per full touchpad-height of two-finger drag. */
        const val SCROLL_SENSITIVITY = 12f

        const val ARROW_STRIDE_BYTES = 2 * 4
        const val CURSOR_ARROW_VERTEX_COUNT = 3

        /** A pointer arrowhead as one triangle (x, y) — tip at the origin, pointing up-left. */
        val CURSOR_ARROW_VERTICES = floatArrayOf(
            0f, 0f,
            0f, -1f,
            0.7f, -0.7f,
        )

        /** A full-screen quad (x, y), drawn with the cursor program as the drawer scrim. */
        val SCRIM_QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )

        const val FOV_Y_DEGREES = 55f
        const val NEAR_PLANE = 0.1f
        const val FAR_PLANE = 100f

        const val POSITION_FLOATS = 3
        const val TEXCOORD_FLOATS = 2
        const val FLOATS_PER_VERTEX = POSITION_FLOATS + TEXCOORD_FLOATS
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
        const val QUAD_VERTEX_COUNT = 4

        /**
         * Screen quad: x, y, z, u, v. Texture v rises from 0 at the bottom to 1 at the top —
         * the convention a SurfaceTexture's transform matrix is built for.
         */
        val SCREEN_QUAD_VERTICES = floatArrayOf(
            -1f, -1f, 0f, 0f, 0f,
            1f, -1f, 0f, 1f, 0f,
            -1f, 1f, 0f, 0f, 1f,
            1f, 1f, 0f, 1f, 1f,
        )

        const val SCREEN_VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        // The #extension directive must be the first line of the source.
        const val SCREEN_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}\n"

        const val CURSOR_VERTEX_SHADER = """
            uniform vec2 uCenter;
            uniform vec2 uHalfSize;
            attribute vec4 aPosition;
            void main() {
                gl_Position = vec4(aPosition.xy * uHalfSize + uCenter, 0.0, 1.0);
            }
        """

        const val CURSOR_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """

        fun directBufferOf(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(data)
                    position(0)
                }

        fun buildProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)

            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                error("Program link failed: $log")
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return program
        }

        fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)

            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("Shader compile failed: $log")
            }
            return shader
        }
    }
}
