package com.vspace.workspace

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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the workspace as a 3D scene of textured quads: the desktop (a [UiScreen] — the
 * One UI taskbar, app drawer, and wallpaper) as the back plane, then one quad per launched
 * app ([VirtualScreen]), then the touchpad cursor as an overlay.
 *
 * Every quad samples an external-OES texture fed by a VirtualDisplay. The camera comes from
 * [setHeadPose]: PINNED locks the scene to the view, FREE world-fixes it as the head turns.
 *
 * App launches arrive on any thread via [requestApp] and are realised on the GL thread in
 * [onDrawFrame], where a GL context is guaranteed.
 */
class WorkspaceRenderer(
    private val context: Context,
    private val mainHandler: Handler,
) : GLSurfaceView.Renderer {

    private val appContext: Context = context.applicationContext

    private data class AppRequest(val packageName: String, val activityName: String)

    private val pendingApps = ConcurrentLinkedQueue<AppRequest>()
    private val screens = ArrayList<VirtualScreen>()
    private var nextScreenId = 1

    /** The desktop — VSpace's own One UI home, hosted on its own virtual display. */
    private var desktop: UiScreen? = null

    // Head-orientation quaternion (w, x, y, z); identity means looking straight ahead.
    @Volatile private var headW = 1f
    @Volatile private var headX = 0f
    @Volatile private var headY = 0f
    @Volatile private var headZ = 0f

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

    @Volatile private var captureRequested = false
    @Volatile private var pendingScroll = 0f

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** Desktop quad half-extents (metres), sized in [onSurfaceChanged] to fill the view. */
    private var desktopHalfWidth = 3.7f
    private var desktopHalfHeight = 2.1f

    private lateinit var screenQuad: FloatBuffer
    private lateinit var cursorArrow: FloatBuffer

    private val projection = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Scratch for unprojecting the cursor onto the desktop plane.
    private val invViewProjection = FloatArray(16)
    private val clipPoint = FloatArray(4)
    private val worldPoint = FloatArray(4)

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

    /** Switch how the scene tracks the head. Safe to call from any thread. */
    fun setViewMode(mode: ViewMode) {
        viewMode = mode
    }

    /** Request a PNG snapshot of the next rendered frame. Safe to call from any thread. */
    fun requestCapture() {
        captureRequested = true
    }

    /** Accumulate a scroll delta (fraction of the touchpad height). Safe from any thread. */
    fun requestScroll(dyFraction: Float) {
        pendingScroll += dyFraction
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

    /** Release the desktop, every screen, and their GL resources. Call on the GL thread. */
    fun releaseAll() {
        desktop?.release()
        desktop = null
        screens.forEach { it.release() }
        screens.clear()
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

        // The desktop: an external texture fed by our own Presentation on a virtual display.
        // The app injects the Presentation factory (see WorkspaceController.desktopContent).
        val factory = WorkspaceController.desktopContent
        if (factory != null) {
            val ui = UiScreen(
                createExternalTexture(), DESKTOP_WIDTH_PX, DESKTOP_HEIGHT_PX, mainHandler,
            )
            desktop = ui
            mainHandler.post { ui.start(context, factory) }
        } else {
            Log.e(TAG, "no desktop content registered — desktop will not render")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)

        surfaceAspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projection, 0, FOV_Y_DEGREES, surfaceAspect, NEAR_PLANE, FAR_PLANE)

        // Size the desktop quad to exactly fill the field of view at its distance.
        val halfFov = Math.toRadians(FOV_Y_DEGREES / 2.0)
        desktopHalfHeight = (DESKTOP_DISTANCE * Math.tan(halfFov)).toFloat()
        desktopHalfWidth = desktopHalfHeight * surfaceAspect
    }

    override fun onDrawFrame(gl: GL10?) {
        drainPendingApps()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (surfaceWidth == 0 || surfaceHeight == 0) return

        buildView(viewMatrix)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, viewMatrix, 0)

        GLES20.glUseProgram(screenProgram)

        // The desktop — back plane, filling the view.
        desktop?.let { d ->
            d.updateTexture()
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, 0f, 0f, -DESKTOP_DISTANCE)
            Matrix.scaleM(modelMatrix, 0, desktopHalfWidth, desktopHalfHeight, 1f)
            Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            drawExternalQuad(d.textureId, d.textureMatrix)
        }

        // Launched apps — quads in front of the desktop.
        if (screens.isNotEmpty()) {
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            for (screen in screens) {
                screen.updateTexture()
                buildModel(modelMatrix, screen)
                Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
                drawExternalQuad(screen.textureId, screen.textureMatrix)
            }
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
        drawCursor()

        if (captureRequested) {
            captureRequested = false
            saveFrameCapture()
        }
    }

    /** Draw a [screenProgram] quad textured with an external-OES texture; [mvpMatrix] is set. */
    private fun drawExternalQuad(textureId: Int, textureMatrix: FloatArray) {
        GLES20.glUniformMatrix4fv(screenUMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(screenUTexMatrix, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(screenUTexture, 0)
        bindQuad(screenQuad, screenAPosition, screenATexCoord)
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
        // A single screen for now; a second launch evicts the first.
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

        // Create the display on the main thread, then launch the app onto it through the
        // injected launcher (Shizuku) — a normal app may not place an app on a display.
        mainHandler.post {
            val displayId = screen.createDisplay(appContext)
            if (displayId != null) {
                WorkspaceController.appLauncher?.invoke(
                    displayId, request.packageName, request.activityName,
                )
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

    /**
     * Resolve a cursor click: ray-cast the screen-space cursor onto the desktop plane and
     * dispatch a tap at the matching desktop pixel into the hosted One UI view tree.
     */
    private fun handleClick() {
        val d = desktop ?: return
        val px = cursorToDesktopPx() ?: return
        mainHandler.post { d.dispatchTap(px[0], px[1]) }
    }

    /** Dispatch an accumulated scroll delta onto the desktop under the cursor. */
    private fun handleScroll(dyFraction: Float) {
        val d = desktop ?: return
        val px = cursorToDesktopPx() ?: return
        val vScroll = dyFraction * SCROLL_SENSITIVITY
        mainHandler.post { d.dispatchScroll(px[0], px[1], vScroll) }
    }

    /**
     * Ray-cast the screen-space cursor onto the desktop plane; returns the matching desktop
     * pixel as `[px, py]`, or null if the cursor misses the desktop.
     */
    private fun cursorToDesktopPx(): FloatArray? {
        if (!Matrix.invertM(invViewProjection, 0, viewProjection, 0)) return null
        val near = unproject(cursorX, cursorY, -1f) ?: return null
        val far = unproject(cursorX, cursorY, 1f) ?: return null
        val dirZ = far[2] - near[2]
        if (abs(dirZ) < 1e-5f) return null
        // Intersect the cursor ray with the desktop plane at z = -DESKTOP_DISTANCE.
        val t = (-DESKTOP_DISTANCE - near[2]) / dirZ
        if (t < 0f) return null
        val hitX = near[0] + t * (far[0] - near[0])
        val hitY = near[1] + t * (far[1] - near[1])
        val hw = desktopHalfWidth
        val hh = desktopHalfHeight
        if (hitX < -hw || hitX > hw || hitY < -hh || hitY > hh) return null
        return floatArrayOf(
            (hitX + hw) / (2f * hw) * DESKTOP_WIDTH_PX,
            (hh - hitY) / (2f * hh) * DESKTOP_HEIGHT_PX,
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

    private companion object {
        const val TAG = "VSpace/Renderer"

        /** Pixel resolution of the desktop UI surface (16:9). */
        const val DESKTOP_WIDTH_PX = 1920
        const val DESKTOP_HEIGHT_PX = 1080

        /** The desktop sits just behind the launched-app screens, filling the view. */
        const val DESKTOP_DISTANCE = 4.2f

        /** Pixel resolution of each launched-app virtual screen (16:9). */
        const val SCREEN_WIDTH_PX = 1600
        const val SCREEN_HEIGHT_PX = 900

        /** A single launched app for now, sized to fill the view. */
        const val MAX_SCREENS = 1
        const val SCREEN_DISTANCE = 4.0f
        const val SCREEN_SPREAD_DEGREES = 28f
        const val SCREEN_FILL_WIDTH = 7.2f

        /** Cursor scale (NDC), motion per touchpad-width, and click-flash duration. */
        const val CURSOR_SCALE = 0.0281f
        const val CURSOR_SENSITIVITY = 2.6f
        const val CURSOR_FLASH_FRAMES = 12

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
