package com.vspace.spatial

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.opengl.Matrix
import android.util.Log
import android.view.Surface

/**
 * One virtual screen in the workspace.
 *
 * A screen is an Android [VirtualDisplay] whose output goes into a [SurfaceTexture]; the
 * texture is then sampled onto a quad by [WorkspaceRenderer]. The display is created here;
 * an app is launched onto it separately, through Shizuku (a normal app may not place another
 * app on a virtual display).
 *
 * Threading: the constructor and [updateTexture]/[release] run on the GL thread (they touch
 * GL state); [createDisplay] runs on the main thread.
 */
class VirtualScreen(
    val id: Int,
    /** GL external-OES texture name the screen's frames are decoded into. */
    val textureId: Int,
    private val widthPx: Int,
    private val heightPx: Int,
) {
    val surfaceTexture: SurfaceTexture =
        SurfaceTexture(textureId).apply { setDefaultBufferSize(widthPx, heightPx) }

    private val surface = Surface(surfaceTexture)

    /** SurfaceTexture → texture-coordinate transform, refreshed every GL frame. */
    val textureMatrix: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** World placement; [WorkspaceRenderer] turns these into the quad's model matrix. */
    var worldX: Float = 0f
    var worldZ: Float = -DEFAULT_DISTANCE
    /** Rotation about the Y axis, in degrees — angles a side screen to face the viewer. */
    var worldYawDeg: Float = 0f
    var worldWidth: Float = DEFAULT_WIDTH
    val worldHeight: Float get() = worldWidth * heightPx / widthPx

    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var released = false

    /**
     * Create the VirtualDisplay backing this screen. Call on the main thread.
     *
     * @return the new display's id (to launch an app onto), or `null` on failure.
     */
    fun createDisplay(context: Context): Int? {
        if (released) return null
        val displayManager = context.getSystemService(DisplayManager::class.java)
        if (displayManager == null) {
            Log.e(TAG, "screen $id: no DisplayManager")
            return null
        }
        val display = displayManager.createVirtualDisplay(
            "vspace-screen-$id",
            widthPx,
            heightPx,
            context.resources.displayMetrics.densityDpi,
            surface,
            FLAGS,
        )
        if (display == null) {
            Log.e(TAG, "screen $id: createVirtualDisplay returned null")
            return null
        }
        virtualDisplay = display
        val displayId = display.display.displayId
        Log.i(TAG, "screen $id: virtual display created id=$displayId ${widthPx}x$heightPx")
        return displayId
    }

    /** Pull the latest frame into the GL texture. Call on the GL thread. */
    fun updateTexture() {
        if (released) return
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(textureMatrix)
        } catch (_: Exception) {
            // No frame produced yet — keep the previous texture contents.
        }
    }

    /** Release the VirtualDisplay and all Surface/GL resources. Call on the GL thread. */
    fun release() {
        if (released) return
        released = true
        virtualDisplay?.release()
        virtualDisplay = null
        surface.release()
        surfaceTexture.release()
    }

    private companion object {
        const val TAG = "VSpace/Screen"

        /** Default distance from the viewer, and width, of a screen in metres. */
        const val DEFAULT_DISTANCE = 4.0f
        const val DEFAULT_WIDTH = 1.4f

        /**
         * `PUBLIC` so the system will consider launching activities onto it; `OWN_CONTENT_ONLY`
         * so it never mirrors the phone; `PRESENTATION` marks it as secondary-screen content.
         */
        const val FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    }
}
