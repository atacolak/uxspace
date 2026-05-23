package com.vspace.spatial

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.Matrix
import android.util.Log
import android.view.Surface

/**
 * One virtual screen in the workspace.
 *
 * A screen is an Android virtual display whose output goes into a [SurfaceTexture]; the
 * texture is then sampled onto a quad by [WorkspaceRenderer]. The display itself is created
 * through Shizuku ([WorkspaceController.createVirtualDisplay]) so it is *trusted* — a normal
 * app's virtual display is not, and a launched app would escape an untrusted display back to
 * the phone. An app is launched onto the display separately, also through Shizuku.
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
    /** Package of the app launched onto this screen — force-stopped when it is closed. */
    val packageName: String,
) {
    val surfaceTexture: SurfaceTexture =
        SurfaceTexture(textureId).apply { setDefaultBufferSize(widthPx, heightPx) }

    private val surface = Surface(surfaceTexture)

    /** SurfaceTexture → texture-coordinate transform, refreshed every GL frame. */
    val textureMatrix: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** World placement; [WorkspaceRenderer] turns these into the quad's model matrix. */
    var worldX: Float = 0f
    var worldY: Float = 0f
    var worldZ: Float = -DEFAULT_DISTANCE
    /** Rotation about the Y axis, in degrees — angles a side screen to face the viewer. */
    var worldYawDeg: Float = 0f
    var worldWidth: Float = DEFAULT_WIDTH
    val worldHeight: Float get() = worldWidth * heightPx / widthPx

    /** The backing virtual display's id (for input injection), or -1 before [createDisplay]. */
    @Volatile
    var displayId: Int = -1
        private set

    @Volatile
    private var released = false

    /**
     * Create the trusted virtual display backing this screen, through Shizuku. Call on the
     * main thread.
     *
     * @return the new display's id (to launch an app onto), or `null` on failure.
     */
    fun createDisplay(context: Context): Int? {
        if (released) return null
        val create = WorkspaceController.createVirtualDisplay
        if (create == null) {
            Log.e(TAG, "screen $id: no virtual-display creator wired (Shizuku not ready)")
            return null
        }
        val createdId = create(
            "vspace-screen-$id",
            widthPx,
            heightPx,
            APP_DISPLAY_DPI,
            surface,
        )
        if (createdId == null) {
            Log.e(TAG, "screen $id: trusted virtual display creation failed")
            return null
        }
        displayId = createdId
        Log.i(TAG, "screen $id: trusted virtual display id=$createdId ${widthPx}x$heightPx")
        return createdId
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

    /** Release the virtual display and all Surface/GL resources. Call on the GL thread. */
    fun release() {
        if (released) return
        released = true
        val id = displayId
        if (id >= 0) WorkspaceController.releaseVirtualDisplay?.invoke(id)
        displayId = -1
        surface.release()
        surfaceTexture.release()
    }

    private companion object {
        const val TAG = "VSpace/Screen"

        /** Default distance from the viewer, and width, of a screen in metres. */
        const val DEFAULT_DISTANCE = 4.0f
        const val DEFAULT_WIDTH = 1.4f

        /**
         * Density Android tells the launched app its display is at. The phone's native
         * density (~420 dpi on a Fold) makes apps think they're on a tiny phone screen and
         * scale their UI up — text and buttons become huge inside a 1600x900 window. A
         * desktop-class 160 dpi (mdpi, 1x) leaves apps thinking they have plenty of dp room
         * and they render at their compact "tablet / Chromebook" layouts.
         */
        const val APP_DISPLAY_DPI = 160
    }
}
