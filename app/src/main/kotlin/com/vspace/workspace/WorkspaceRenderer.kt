package com.vspace.workspace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import com.vspace.apps.InstalledApp
import com.vspace.shizuku.ShizukuManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the workspace: a backdrop image, then a 3D scene of textured quads — one per
 * [VirtualScreen].
 *
 * Each screen quad samples an external-OES texture fed by a `VirtualDisplay`. The camera
 * orientation comes from [setHeadOrientation] — feeding it head pose (M2) makes the screens
 * world-fixed; until then the camera is static and the screens sit straight ahead.
 *
 * App launches arrive on any thread via [requestApp] and are realised on the GL thread inside
 * [onDrawFrame], where a GL context is guaranteed to exist.
 */
class WorkspaceRenderer(
    private val appContext: Context,
    private val mainHandler: Handler,
) : GLSurfaceView.Renderer {

    private data class AppRequest(val packageName: String, val activityName: String)

    private val pendingApps = ConcurrentLinkedQueue<AppRequest>()
    private val screens = ArrayList<VirtualScreen>()
    private var nextScreenId = 1

    // Head-orientation quaternion (w, x, y, z); identity means looking straight ahead.
    @Volatile private var headW = 1f
    @Volatile private var headX = 0f
    @Volatile private var headY = 0f
    @Volatile private var headZ = 0f

    /** How the screen tracks the head. */
    @Volatile private var viewMode = ViewMode.PINNED

    /** Whether the screen follows the head ([PINNED]) or stays put in the world ([FREE]). */
    enum class ViewMode { PINNED, FREE }

    // Screen-quad program (samples a SurfaceTexture as an external-OES texture).
    private var screenProgram = 0
    private var screenAPosition = 0
    private var screenATexCoord = 0
    private var screenUMvp = 0
    private var screenUTexMatrix = 0
    private var screenUTexture = 0

    // Backdrop program (samples a plain 2D image, drawn full-screen behind the scene).
    private var backgroundProgram = 0
    private var backgroundAPosition = 0
    private var backgroundATexCoord = 0
    private var backgroundUMvp = 0
    private var backgroundUTexture = 0
    private var backgroundTextureId = 0
    private var backgroundImageAspect = DEFAULT_BACKGROUND_ASPECT

    // Cursor program — a flat-colour quad drawn as a screen-space overlay.
    private var cursorProgram = 0
    private var cursorAPosition = 0
    private var cursorUCenter = 0
    private var cursorUHalfSize = 0
    private var cursorUColor = 0
    @Volatile private var cursorX = 0f
    @Volatile private var cursorY = 0f
    @Volatile private var cursorClickPending = false
    private var cursorFlashFrames = 0
    private var surfaceAspect = 1.78f

    // UI program — a textured world-space quad (the app button and drawer panel).
    private var uiProgram = 0
    private var uiAPosition = 0
    private var uiATexCoord = 0
    private var uiUMvp = 0
    private var uiUTexture = 0
    private var appButtonTexture = 0
    private var panelTexture = 0
    private var taskbarTexture = 0
    @Volatile private var drawerOpen = false
    @Volatile private var captureRequested = false

    // Scratch for projecting world-space UI to NDC for cursor hit-testing.
    private val projectScratch = FloatArray(4)
    private val worldPoint = FloatArray(4)

    // Installed apps shown as the drawer's icon grid; textures upload lazily on the GL thread.
    private class DrawerIcon(val app: InstalledApp) {
        var textureId = 0
    }

    @Volatile private var drawerIcons: List<DrawerIcon> = emptyList()

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private lateinit var backgroundQuad: FloatBuffer
    private lateinit var screenQuad: FloatBuffer
    private lateinit var cursorArrow: FloatBuffer

    private val projection = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    /** Queue an app to be placed on a virtual screen. Safe to call from any thread. */
    fun requestApp(packageName: String, activityName: String) {
        pendingApps.add(AppRequest(packageName, activityName))
    }

    /** Feed a head-orientation quaternion for the camera. Safe to call from any thread. */
    fun setHeadPose(w: Float, x: Float, y: Float, z: Float) {
        headW = w
        headX = x
        headY = y
        headZ = z
    }

    /** Switch how the screen tracks the head. Safe to call from any thread. */
    fun setViewMode(mode: ViewMode) {
        viewMode = mode
    }

    /** Provide the installed apps shown as the drawer's icon grid. Safe from any thread. */
    fun setApps(apps: List<InstalledApp>) {
        drawerIcons = apps.map { DrawerIcon(it) }
    }

    /** Request a PNG snapshot of the next rendered frame. Safe to call from any thread. */
    fun requestCapture() {
        captureRequested = true
    }

    /** Move the cursor by a fraction of the touchpad's width. Safe to call from any thread. */
    fun moveCursor(dxFraction: Float, dyFraction: Float) {
        cursorX = (cursorX + dxFraction * CURSOR_SENSITIVITY).coerceIn(-1f, 1f)
        cursorY = (cursorY - dyFraction * CURSOR_SENSITIVITY).coerceIn(-1f, 1f)
    }

    /** Register a cursor click. Safe to call from any thread. */
    fun cursorClick() {
        cursorClickPending = true
    }

    /** Release every screen's VirtualDisplay and GL resources. Call on the GL thread. */
    fun releaseAll() {
        screens.forEach { it.release() }
        screens.clear()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.04f, 0.05f, 0.08f, 1f)

        screenProgram = buildProgram(SCREEN_VERTEX_SHADER, SCREEN_FRAGMENT_SHADER)
        screenAPosition = GLES20.glGetAttribLocation(screenProgram, "aPosition")
        screenATexCoord = GLES20.glGetAttribLocation(screenProgram, "aTexCoord")
        screenUMvp = GLES20.glGetUniformLocation(screenProgram, "uMvp")
        screenUTexMatrix = GLES20.glGetUniformLocation(screenProgram, "uTexMatrix")
        screenUTexture = GLES20.glGetUniformLocation(screenProgram, "uTexture")

        backgroundProgram = buildProgram(BACKGROUND_VERTEX_SHADER, BACKGROUND_FRAGMENT_SHADER)
        backgroundAPosition = GLES20.glGetAttribLocation(backgroundProgram, "aPosition")
        backgroundATexCoord = GLES20.glGetAttribLocation(backgroundProgram, "aTexCoord")
        backgroundUMvp = GLES20.glGetUniformLocation(backgroundProgram, "uMvp")
        backgroundUTexture = GLES20.glGetUniformLocation(backgroundProgram, "uTexture")

        cursorProgram = buildProgram(CURSOR_VERTEX_SHADER, CURSOR_FRAGMENT_SHADER)
        cursorAPosition = GLES20.glGetAttribLocation(cursorProgram, "aPosition")
        cursorUCenter = GLES20.glGetUniformLocation(cursorProgram, "uCenter")
        cursorUHalfSize = GLES20.glGetUniformLocation(cursorProgram, "uHalfSize")
        cursorUColor = GLES20.glGetUniformLocation(cursorProgram, "uColor")

        uiProgram = buildProgram(UI_VERTEX_SHADER, UI_FRAGMENT_SHADER)
        uiAPosition = GLES20.glGetAttribLocation(uiProgram, "aPosition")
        uiATexCoord = GLES20.glGetAttribLocation(uiProgram, "aTexCoord")
        uiUMvp = GLES20.glGetUniformLocation(uiProgram, "uMvp")
        uiUTexture = GLES20.glGetUniformLocation(uiProgram, "uTexture")

        backgroundQuad = directBufferOf(BACKGROUND_QUAD_VERTICES)
        screenQuad = directBufferOf(SCREEN_QUAD_VERTICES)
        cursorArrow = directBufferOf(CURSOR_ARROW_VERTICES)
        loadBackgroundTexture()
        appButtonTexture = uploadTexture(buildAppsGlyph())
        panelTexture = uploadTexture(buildRoundedPanel(512, 299, 46f, 232))
        taskbarTexture = uploadTexture(buildRoundedPanel(1024, 75, 37f, 225))
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)

        val aspect = width.toFloat() / height.toFloat()
        surfaceAspect = aspect
        Matrix.perspectiveM(projection, 0, FOV_Y_DEGREES, aspect, NEAR_PLANE, FAR_PLANE)
    }

    override fun onDrawFrame(gl: GL10?) {
        drainPendingApps()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (surfaceWidth == 0 || surfaceHeight == 0) return

        buildView(viewMatrix)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, viewMatrix, 0)

        drawBackground()

        if (screens.isNotEmpty()) {
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(screenProgram)
            for (screen in screens) {
                screen.updateTexture()
                buildModel(modelMatrix, screen)
                Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
                drawScreen(screen)
            }
        }

        if (cursorClickPending) {
            cursorClickPending = false
            cursorFlashFrames = CURSOR_FLASH_FRAMES
            handleClick()
        }
        drawDrawerUi()
        drawCursor()

        if (captureRequested) {
            captureRequested = false
            saveFrameCapture()
        }
    }

    /** Draw the backdrop image full-screen behind the 3D scene. */
    /** Draw the backdrop as a large world-fixed wall behind the screens — head-tracked too. */
    private fun drawBackground() {
        if (backgroundTextureId == 0) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(backgroundProgram)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, 0f, -BACKGROUND_DISTANCE)
        Matrix.scaleM(
            modelMatrix, 0,
            BACKGROUND_HALF_WIDTH, BACKGROUND_HALF_WIDTH / backgroundImageAspect, 1f,
        )
        Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(backgroundUMvp, 1, false, mvpMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backgroundTextureId)
        GLES20.glUniform1i(backgroundUTexture, 0)

        bindQuad(backgroundQuad, backgroundAPosition, backgroundATexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
    }

    /** Realise every queued app launch as a new [VirtualScreen]. GL thread only. */
    private fun drainPendingApps() {
        var request = pendingApps.poll()
        while (request != null) {
            addScreen(request)
            request = pendingApps.poll()
        }
    }

    private fun addScreen(request: AppRequest) {
        // Up to three screens; a fourth evicts the oldest.
        if (screens.size >= MAX_SCREENS) {
            screens.removeAt(0).release()
        }

        val screen = VirtualScreen(
            id = nextScreenId++,
            textureId = createExternalTexture(),
            widthPx = SCREEN_WIDTH_PX,
            heightPx = SCREEN_HEIGHT_PX,
        )
        screens.add(screen)
        relayout()

        // Create the display on the main thread, then launch the app onto it via Shizuku —
        // a normal app is not allowed to place another app on a virtual display.
        mainHandler.post {
            val displayId = screen.createDisplay(appContext)
            if (displayId != null) {
                ShizukuManager.launchApp(displayId, request.packageName, request.activityName)
            }
        }
    }

    /** Arrange the screens on a gentle arc in front of the viewer, each facing inward. */
    private fun relayout() {
        val count = screens.size
        screens.forEachIndexed { index, screen ->
            val angleDeg = (index - (count - 1) / 2f) * SCREEN_SPREAD_DEGREES
            val angleRad = Math.toRadians(angleDeg.toDouble())
            screen.worldX = (SCREEN_DISTANCE * sin(angleRad)).toFloat()
            screen.worldZ = (-SCREEN_DISTANCE * cos(angleRad)).toFloat()
            screen.worldYawDeg = -angleDeg
            screen.worldWidth = SCREEN_FILL_WIDTH
        }
    }

    private fun drawScreen(screen: VirtualScreen) {
        GLES20.glUniformMatrix4fv(screenUMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(screenUTexMatrix, 1, false, screen.textureMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, screen.textureId)
        GLES20.glUniform1i(screenUTexture, 0)

        bindQuad(screenQuad, screenAPosition, screenATexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
    }

    /** Draw the touchpad cursor — an arrow pointer — as a flat overlay on top of everything. */
    private fun drawCursor() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(cursorProgram)

        cursorArrow.position(0)
        GLES20.glVertexAttribPointer(
            cursorAPosition, 2, GLES20.GL_FLOAT, false, ARROW_STRIDE_BYTES, cursorArrow,
        )
        GLES20.glEnableVertexAttribArray(cursorAPosition)

        val flashing = cursorFlashFrames > 0
        if (flashing) cursorFlashFrames--

        // A larger dark arrow, nudged up-left, frames the bright arrow drawn on top.
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

    /** Resolve a cursor click against the workspace UI. */
    private fun handleClick() {
        if (drawerOpen) {
            // A click on an icon launches that app; any click closes the drawer.
            val visible = drawerIcons.take(GRID_COLS * GRID_ROWS)
            val hit = visible.indices.firstOrNull { index ->
                cursorOverWorldQuad(iconX(index), iconY(index), ICON_Z, ICON_HALF_W, ICON_HALF_H)
            }
            if (hit != null) {
                val app = visible[hit].app
                requestApp(app.packageName, app.activityName)
            }
            drawerOpen = false
        } else if (cursorOverWorldQuad(
                APP_BUTTON_X, APP_BUTTON_Y, APP_BUTTON_Z, APP_BUTTON_HALF, APP_BUTTON_HALF,
            )
        ) {
            drawerOpen = true
        }
    }

    /** Whether the cursor (NDC) lies over a world-space quad, found by projecting the quad. */
    private fun cursorOverWorldQuad(
        cx: Float,
        cy: Float,
        cz: Float,
        halfW: Float,
        halfH: Float,
    ): Boolean {
        val center = projectToNdc(cx, cy, cz) ?: return false
        val edgeX = projectToNdc(cx + halfW, cy, cz) ?: return false
        val edgeY = projectToNdc(cx, cy + halfH, cz) ?: return false
        val ndcHalfX = abs(edgeX[0] - center[0])
        val ndcHalfY = abs(edgeY[1] - center[1])
        return cursorX >= center[0] - ndcHalfX && cursorX <= center[0] + ndcHalfX &&
            cursorY >= center[1] - ndcHalfY && cursorY <= center[1] + ndcHalfY
    }

    /** Project a world point to NDC via the current view-projection; null if behind the eye. */
    private fun projectToNdc(x: Float, y: Float, z: Float): FloatArray? {
        worldPoint[0] = x
        worldPoint[1] = y
        worldPoint[2] = z
        worldPoint[3] = 1f
        Matrix.multiplyMV(projectScratch, 0, viewProjection, 0, worldPoint, 0)
        val w = projectScratch[3]
        if (w <= 0.0001f) return null
        return floatArrayOf(projectScratch[0] / w, projectScratch[1] / w)
    }

    /** World X of the drawer icon at [index] — its column, centred across the panel. */
    private fun iconX(index: Int): Float =
        (index % GRID_COLS - (GRID_COLS - 1) / 2f) * GRID_CELL_W

    /** World Y of the drawer icon at [index] — its row, laid out down from the panel's top. */
    private fun iconY(index: Int): Float =
        GRID_TOP_Y - (index / GRID_COLS) * GRID_CELL_H

    /** Draw the workspace UI — the open app drawer, or the taskbar with its launcher button. */
    private fun drawDrawerUi() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        if (drawerOpen) {
            drawUiWorldQuad(panelTexture, 0f, 0f, DRAWER_Z, DRAWER_HALF_WIDTH, DRAWER_HALF_HEIGHT)
            val visible = drawerIcons.take(GRID_COLS * GRID_ROWS)
            visible.forEachIndexed { index, icon ->
                if (icon.textureId == 0) {
                    icon.textureId = uploadTexture(iconBitmap(icon.app))
                }
                drawUiWorldQuad(
                    icon.textureId, iconX(index), iconY(index), ICON_Z, ICON_HALF_W, ICON_HALF_H,
                )
            }
        } else {
            drawUiWorldQuad(
                taskbarTexture, 0f, TASKBAR_Y, TASKBAR_Z,
                TASKBAR_HALF_WIDTH, TASKBAR_HALF_HEIGHT,
            )
            drawUiWorldQuad(
                appButtonTexture, APP_BUTTON_X, APP_BUTTON_Y, APP_BUTTON_Z,
                APP_BUTTON_HALF, APP_BUTTON_HALF,
            )
        }
    }

    /** Draw a textured quad in world space — a UI element anchored near the screen. */
    private fun drawUiWorldQuad(
        texture: Int,
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        halfWidth: Float,
        halfHeight: Float,
    ) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(uiProgram)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, centerX, centerY, centerZ)
        Matrix.scaleM(modelMatrix, 0, halfWidth, halfHeight, 1f)
        Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(uiUMvp, 1, false, mvpMatrix, 0)

        backgroundQuad.position(0)
        GLES20.glVertexAttribPointer(
            uiAPosition, POSITION_FLOATS, GLES20.GL_FLOAT, false, STRIDE_BYTES, backgroundQuad,
        )
        GLES20.glEnableVertexAttribArray(uiAPosition)
        backgroundQuad.position(POSITION_FLOATS)
        GLES20.glVertexAttribPointer(
            uiATexCoord, TEXCOORD_FLOATS, GLES20.GL_FLOAT, false, STRIDE_BYTES, backgroundQuad,
        )
        GLES20.glEnableVertexAttribArray(uiATexCoord)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(uiUTexture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
        GLES20.glDisable(GLES20.GL_BLEND)
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
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        Thread {
            val dir = File(appContext.getExternalFilesDir(null), "captures").apply { mkdirs() }
            val file = File(dir, "vspace-${System.currentTimeMillis()}.png")
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

    private fun uploadTexture(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return ids[0]
    }

    /** Build the launcher glyph — a translucent disc with a 3×3 grid of soft dots. */
    private fun buildAppsGlyph(): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Translucent dark disc — the round button surface; outside it stays transparent.
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 26, 28, 36) }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.48f, disc)
        // 3x3 grid of soft round dots — the app-grid mark.
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 232, 235, 242) }
        val margin = size * 0.32f
        val gap = size * 0.10f
        val cell = (size - 2f * margin - 2f * gap) / 3f
        for (row in 0..2) {
            for (col in 0..2) {
                val cx = margin + col * (cell + gap) + cell / 2f
                val cy = margin + row * (cell + gap) + cell / 2f
                canvas.drawCircle(cx, cy, cell * 0.42f, dot)
            }
        }
        return bitmap
    }

    /** A rounded, translucent dark surface — One UI 8 styling for the drawer and taskbar. */
    private fun buildRoundedPanel(
        widthPx: Int,
        heightPx: Int,
        radius: Float,
        fillAlpha: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val inset = 2.5f
        val right = widthPx - inset
        val bottom = heightPx - inset
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(fillAlpha, 20, 22, 30) }
        canvas.drawRoundRect(inset, inset, right, bottom, radius, radius, fill)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.argb(40, 255, 255, 255)
        }
        canvas.drawRoundRect(inset, inset, right, bottom, radius, radius, edge)
        return bitmap
    }

    /** Compose an app's icon and label into one drawer-cell bitmap — One UI 8 styling. */
    private fun iconBitmap(app: InstalledApp): Bitmap {
        val w = ICON_CELL_PX_W
        val h = ICON_CELL_PX_H
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val iconSize = (w * 0.72f).toInt()
        val iconLeft = (w - iconSize) / 2
        val iconTop = (w * 0.07f).toInt()
        app.icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
        app.icon.draw(canvas)
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 232, 235, 242)
            textAlign = Paint.Align.CENTER
            textSize = w * 0.125f
        }
        val text = TextUtils.ellipsize(app.label, label, w * 0.95f, TextUtils.TruncateAt.END)
        canvas.drawText(
            text.toString(), w / 2f, iconTop + iconSize + label.textSize * 1.4f, label,
        )
        return bitmap
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
            // Pinned: the screen is locked to the viewport — it follows the head.
            Matrix.setIdentityM(out, 0)
            return
        }
        val w = headW
        val x = -headX
        val y = -headY
        val z = -headZ
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

    /** Model matrix placing the unit quad at the screen's world position, facing, and size. */
    private fun buildModel(out: FloatArray, screen: VirtualScreen) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, screen.worldX, 0f, screen.worldZ)
        Matrix.rotateM(out, 0, screen.worldYawDeg, 0f, 1f, 0f)
        Matrix.scaleM(out, 0, screen.worldWidth / 2f, screen.worldHeight / 2f, 1f)
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

    /** Decode the backdrop image from assets and upload it as a 2D texture. */
    private fun loadBackgroundTexture() {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.assets.open(BACKGROUND_ASSET).use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val decode = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            val bitmap = appContext.assets.open(BACKGROUND_ASSET).use {
                BitmapFactory.decodeStream(it, null, decode)
            } ?: return

            backgroundImageAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            backgroundTextureId = ids[0]
            Log.i(TAG, "backdrop loaded: ${bitmap.width}x${bitmap.height}")
            bitmap.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "backdrop image unavailable: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "VSpace/Renderer"

        const val BACKGROUND_ASSET = "workspace_background.jpg"
        const val DEFAULT_BACKGROUND_ASPECT = 16f / 10f
        const val MAX_TEXTURE_DIM = 2048

        /** The desktop wallpaper — a flat plane just behind the screen, filling the view. */
        const val BACKGROUND_DISTANCE = 4.05f
        const val BACKGROUND_HALF_WIDTH = 4.6f

        /** Pixel resolution of each virtual screen (16:9). */
        const val SCREEN_WIDTH_PX = 1600
        const val SCREEN_HEIGHT_PX = 900

        /** A single screen for now, sized to fill the view. */
        const val MAX_SCREENS = 1
        const val SCREEN_DISTANCE = 4.0f
        const val SCREEN_SPREAD_DEGREES = 28f
        const val SCREEN_FILL_WIDTH = 7.2f

        /** Cursor scale (NDC), motion per touchpad-width, and click-flash duration. */
        const val CURSOR_SCALE = 0.0281f
        const val CURSOR_SENSITIVITY = 2.6f
        const val CURSOR_FLASH_FRAMES = 12

        const val ARROW_STRIDE_BYTES = 2 * 4
        const val CURSOR_ARROW_VERTEX_COUNT = 3

        /** A pointer arrowhead as one triangle (x, y) — tip at the origin, pointing up-left. */
        val CURSOR_ARROW_VERTICES = floatArrayOf(
            0f, 0f,
            0f, -1f,
            0.7f, -0.7f,
        )

        /** The launcher button, centred on the taskbar — world coordinates near the screen. */
        const val APP_BUTTON_X = 0f
        const val APP_BUTTON_Y = -1.73f
        const val APP_BUTTON_Z = -3.80f
        const val APP_BUTTON_HALF = 0.20f

        /** DeX-style taskbar — a full-width translucent bar along the bottom edge. */
        const val TASKBAR_Y = -1.73f
        const val TASKBAR_Z = -3.82f
        const val TASKBAR_HALF_WIDTH = 3.55f
        const val TASKBAR_HALF_HEIGHT = 0.26f

        /** The app drawer — a rounded translucent panel over the screen. */
        const val DRAWER_Z = -3.8f
        const val DRAWER_HALF_WIDTH = 3.6f
        const val DRAWER_HALF_HEIGHT = 2.1f

        /** Drawer app-icon grid: 6 × 3 cells of icon + label — world metres and bitmap pixels. */
        const val GRID_COLS = 6
        const val GRID_ROWS = 3
        const val GRID_CELL_W = 1.06f
        const val GRID_CELL_H = 1.16f
        const val GRID_TOP_Y = 1.14f
        const val ICON_Z = -3.78f
        const val ICON_HALF_W = 0.45f
        const val ICON_HALF_H = 0.548f
        const val ICON_CELL_PX_W = 184
        const val ICON_CELL_PX_H = 224

        const val FOV_Y_DEGREES = 55f
        const val NEAR_PLANE = 0.1f
        const val FAR_PLANE = 100f

        const val POSITION_FLOATS = 3
        const val TEXCOORD_FLOATS = 2
        const val FLOATS_PER_VERTEX = POSITION_FLOATS + TEXCOORD_FLOATS
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
        const val QUAD_VERTEX_COUNT = 4

        /**
         * Backdrop quad: x, y, z, u, v. Texture v rises from 0 at the top to 1 at the
         * bottom, matching `BitmapFactory` row order for a plain 2D image.
         */
        val BACKGROUND_QUAD_VERTICES = floatArrayOf(
            -1f, -1f, 0f, 0f, 1f,
            1f, -1f, 0f, 1f, 1f,
            -1f, 1f, 0f, 0f, 0f,
            1f, 1f, 0f, 1f, 0f,
        )

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

        /** Smallest power-of-two subsample that keeps both dimensions within the GL limit. */
        fun sampleSizeFor(width: Int, height: Int): Int {
            var sample = 1
            while (width / sample > MAX_TEXTURE_DIM || height / sample > MAX_TEXTURE_DIM) {
                sample *= 2
            }
            return sample
        }

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

        const val BACKGROUND_VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        const val BACKGROUND_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

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

        const val UI_VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        const val UI_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
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
