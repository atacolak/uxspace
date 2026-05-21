package com.viturekit.sample.stereo

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.viturekit.model.EulerAngles
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A minimal side-by-side (SBS) stereo renderer driven by head orientation.
 *
 * Each frame is drawn twice — once into the left half of the surface, once into the right —
 * with the camera offset by half the interpupillary distance for each eye. The camera's
 * orientation is taken from [onHeadOrientation], so feeding it the VITURE IMU `Flow` makes
 * the 3D scene head-tracked. Sent to glasses in [com.viturekit.model.DisplayMode.MODE_3D],
 * the two halves land one per eye.
 *
 * Geometry is intentionally simple — a ground grid and a handful of spinning cubes — enough
 * to make head tracking and stereo depth obvious without a full engine.
 */
class StereoRenderer : GLSurfaceView.Renderer {

    @Volatile
    private var headOrientation: EulerAngles = EulerAngles.ZERO

    private var program = 0
    private var aPositionHandle = 0
    private var aColorHandle = 0
    private var uMvpHandle = 0

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val startNanos = System.nanoTime()

    private lateinit var cubeBuffer: FloatBuffer
    private lateinit var gridBuffer: FloatBuffer
    private var gridVertexCount = 0

    // Scratch matrices, reused every frame to avoid per-frame allocation.
    private val projection = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private val cubePositions = arrayOf(
        floatArrayOf(0f, 0f, -5f),
        floatArrayOf(-3f, 0.6f, -7f),
        floatArrayOf(3f, -0.4f, -6f),
        floatArrayOf(-2f, -0.9f, -4.2f),
        floatArrayOf(2.6f, 1.3f, -9f),
    )

    /** Feed a fresh head orientation. Safe to call from any thread. */
    fun onHeadOrientation(euler: EulerAngles) {
        headOrientation = euler
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.02f, 0.03f, 0.06f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aColorHandle = GLES20.glGetAttribLocation(program, "aColor")
        uMvpHandle = GLES20.glGetUniformLocation(program, "uMvp")

        cubeBuffer = directBufferOf(buildCube())
        val grid = buildGrid()
        gridBuffer = directBufferOf(grid)
        gridVertexCount = grid.size / FLOATS_PER_VERTEX
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (surfaceWidth == 0 || surfaceHeight == 0) return

        GLES20.glUseProgram(program)
        val head = headOrientation
        val eyeWidth = surfaceWidth / 2
        val aspect = eyeWidth.toFloat() / surfaceHeight.toFloat()
        Matrix.perspectiveM(projection, 0, FOV_Y_DEGREES, aspect, NEAR_PLANE, FAR_PLANE)
        val seconds = (System.nanoTime() - startNanos) / 1_000_000_000f

        for (eye in 0..1) {
            val eyeSign = if (eye == LEFT_EYE) -1f else 1f
            GLES20.glViewport(eye * eyeWidth, 0, eyeWidth, surfaceHeight)

            buildEyeView(viewMatrix, head, eyeSign)
            Matrix.multiplyMM(viewProjection, 0, projection, 0, viewMatrix, 0)

            // Ground grid — model is identity, so its MVP is just the view-projection.
            drawMesh(gridBuffer, gridVertexCount, GLES20.GL_LINES, viewProjection)

            for (index in cubePositions.indices) {
                buildCubeModel(modelMatrix, cubePositions[index], seconds, index)
                Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
                drawMesh(cubeBuffer, CUBE_VERTEX_COUNT, GLES20.GL_TRIANGLES, mvpMatrix)
            }
        }
    }

    /** View matrix for one eye: inverse head rotation, then a half-IPD lateral offset. */
    private fun buildEyeView(out: FloatArray, head: EulerAngles, eyeSign: Float) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, -eyeSign * HALF_IPD, 0f, 0f)
        Matrix.rotateM(out, 0, -head.rollDeg, 0f, 0f, 1f)
        Matrix.rotateM(out, 0, -head.pitchDeg, 1f, 0f, 0f)
        Matrix.rotateM(out, 0, -head.yawDeg, 0f, 1f, 0f)
    }

    private fun buildCubeModel(out: FloatArray, position: FloatArray, seconds: Float, index: Int) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, position[0], position[1], position[2])
        Matrix.rotateM(out, 0, seconds * (12f + index * 7f), 0.3f, 1f, 0.15f)
        Matrix.scaleM(out, 0, 0.7f, 0.7f, 0.7f)
    }

    private fun drawMesh(buffer: FloatBuffer, vertexCount: Int, mode: Int, mvp: FloatArray) {
        GLES20.glUniformMatrix4fv(uMvpHandle, 1, false, mvp, 0)

        buffer.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, buffer)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        buffer.position(3)
        GLES20.glVertexAttribPointer(aColorHandle, 4, GLES20.GL_FLOAT, false, STRIDE_BYTES, buffer)
        GLES20.glEnableVertexAttribArray(aColorHandle)

        GLES20.glDrawArrays(mode, 0, vertexCount)
    }

    private companion object {
        const val LEFT_EYE = 0

        const val FLOATS_PER_VERTEX = 7          // x, y, z, r, g, b, a
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
        const val CUBE_VERTEX_COUNT = 36         // 6 faces * 2 triangles * 3 vertices

        const val FOV_Y_DEGREES = 60f
        const val NEAR_PLANE = 0.1f
        const val FAR_PLANE = 100f

        /** Half the interpupillary distance, in world units — the per-eye camera offset. */
        const val HALF_IPD = 0.032f

        const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec4 aPosition;
            attribute vec4 aColor;
            varying vec4 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uMvp * aPosition;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 vColor;
            void main() {
                gl_FragColor = vColor;
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
            // Shaders are retained by the linked program; the handles can be released.
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

        /** 36 interleaved vertices for a unit cube, one solid colour per face. */
        fun buildCube(): FloatArray {
            val h = 0.5f
            val corners = arrayOf(
                floatArrayOf(-h, -h, -h), floatArrayOf(h, -h, -h),
                floatArrayOf(h, h, -h), floatArrayOf(-h, h, -h),
                floatArrayOf(-h, -h, h), floatArrayOf(h, -h, h),
                floatArrayOf(h, h, h), floatArrayOf(-h, h, h),
            )
            // Each face: four corner indices (as two triangles) plus an RGBA colour.
            val faces = arrayOf(
                intArrayOf(4, 5, 6, 7) to floatArrayOf(0.20f, 0.85f, 1.00f, 1f),
                intArrayOf(1, 0, 3, 2) to floatArrayOf(1.00f, 0.45f, 0.55f, 1f),
                intArrayOf(0, 4, 7, 3) to floatArrayOf(0.55f, 0.80f, 0.35f, 1f),
                intArrayOf(5, 1, 2, 6) to floatArrayOf(1.00f, 0.78f, 0.30f, 1f),
                intArrayOf(3, 7, 6, 2) to floatArrayOf(0.65f, 0.55f, 1.00f, 1f),
                intArrayOf(0, 1, 5, 4) to floatArrayOf(0.30f, 0.45f, 0.75f, 1f),
            )
            val out = ArrayList<Float>(CUBE_VERTEX_COUNT * FLOATS_PER_VERTEX)
            for ((quad, color) in faces) {
                for (cornerIndex in intArrayOf(quad[0], quad[1], quad[2], quad[0], quad[2], quad[3])) {
                    val corner = corners[cornerIndex]
                    out.add(corner[0]); out.add(corner[1]); out.add(corner[2])
                    out.add(color[0]); out.add(color[1]); out.add(color[2]); out.add(color[3])
                }
            }
            return out.toFloatArray()
        }

        /** A flat reference grid on the ground plane, drawn with GL_LINES. */
        fun buildGrid(): FloatArray {
            val groundY = -1.6f
            val extent = 8
            val color = floatArrayOf(0.16f, 0.22f, 0.40f, 1f)
            val out = ArrayList<Float>()
            fun vertex(x: Float, z: Float) {
                out.add(x); out.add(groundY); out.add(z)
                out.add(color[0]); out.add(color[1]); out.add(color[2]); out.add(color[3])
            }
            val span = extent.toFloat()
            for (line in -extent..extent) {
                val coordinate = line.toFloat()
                vertex(coordinate, -span); vertex(coordinate, span)
                vertex(-span, coordinate); vertex(span, coordinate)
            }
            return out.toFloatArray()
        }
    }
}
