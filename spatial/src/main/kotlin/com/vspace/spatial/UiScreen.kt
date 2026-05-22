package com.vspace.spatial

import android.app.Presentation
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.opengl.Matrix
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface

/**
 * A workspace screen whose content is an Android UI — VSpace's own [Presentation] — rather
 * than a launched third-party app.
 *
 * Mechanically it is identical to [VirtualScreen]: a [VirtualDisplay] renders into a
 * [SurfaceTexture] that [WorkspaceRenderer] samples onto a quad. What differs is the
 * content (our own view hierarchy) and that the cursor's clicks are dispatched straight
 * into that view tree — no Shizuku needed, since it is our own window.
 *
 * Threading: the constructor and [updateTexture]/[release] run on the GL thread; [start]
 * and [dispatchTap] run on the main thread.
 */
class UiScreen(
    /** GL external-OES texture name the UI's frames are decoded into. */
    val textureId: Int,
    /** Surface pixel size — exposed so callers can map cursor hits to its pixels. */
    val width: Int,
    val height: Int,
    private val mainHandler: Handler,
    /** Virtual-display name — kept distinct per screen (desktop, each window's chrome). */
    private val displayName: String,
) {
    val surfaceTexture: SurfaceTexture =
        SurfaceTexture(textureId).apply { setDefaultBufferSize(width, height) }

    private val surface = Surface(surfaceTexture)

    /** SurfaceTexture → texture-coordinate transform, refreshed every GL frame. */
    val textureMatrix: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null

    @Volatile
    private var released = false

    /**
     * Create the backing [VirtualDisplay] and show the [Presentation] built by
     * [presentationFor] on it. Call on the main thread.
     */
    fun start(context: Context, presentationFor: (Context, Display) -> Presentation) {
        if (released) return
        val displayManager = context.getSystemService(DisplayManager::class.java)
        if (displayManager == null) {
            Log.e(TAG, "no DisplayManager")
            return
        }
        val virtual = displayManager.createVirtualDisplay(
            displayName, width, height, DENSITY_DPI, surface, FLAGS,
        )
        if (virtual == null) {
            Log.e(TAG, "createVirtualDisplay returned null")
            return
        }
        virtualDisplay = virtual
        presentation = try {
            presentationFor(context, virtual.display).also { it.show() }
        } catch (e: Exception) {
            Log.e(TAG, "could not show the desktop presentation", e)
            null
        }
        Log.i(TAG, "ui screen ready ${width}x$height display=${virtual.display.displayId}")
    }

    /** Pull the latest UI frame into the GL texture. Call on the GL thread. */
    fun updateTexture() {
        if (released) return
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(textureMatrix)
        } catch (_: Exception) {
            // No frame produced yet — keep the previous texture contents.
        }
    }

    /** Dispatch a tap at pixel [px], [py] into the hosted UI. Call on the main thread. */
    fun dispatchTap(px: Float, py: Float) {
        val root = presentation?.window?.decorView ?: return
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, px, py, 0)
        val up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, px, py, 0)
        root.dispatchTouchEvent(down)
        root.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /**
     * Dispatch a vertical scroll at pixel [px], [py] into the hosted UI — the same
     * `ACTION_SCROLL` event a mouse wheel produces. Call on the main thread.
     */
    fun dispatchScroll(px: Float, py: Float, vScroll: Float) {
        val root = presentation?.window?.decorView ?: return
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = px
                y = py
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            },
        )
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        root.dispatchGenericMotionEvent(event)
        event.recycle()
    }

    /** Release the Presentation, VirtualDisplay, and Surface/GL resources. GL thread. */
    fun release() {
        if (released) return
        released = true
        // A Presentation is a Dialog — it must be dismissed on the main thread.
        presentation?.let { p -> mainHandler.post { p.dismiss() } }
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
        surface.release()
        surfaceTexture.release()
    }

    private companion object {
        const val TAG = "VSpace/UiScreen"

        /** Logical density of the desktop display — tunes how large its widgets render. */
        const val DENSITY_DPI = 200

        /** `OWN_CONTENT_ONLY` so it never mirrors; `PRESENTATION` marks it secondary content. */
        const val FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    }
}
