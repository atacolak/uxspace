package com.vspace.spatial

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import com.vspace.glasses.HeadTracking

/**
 * Hosts the workspace on the VITURE glasses.
 *
 * A [Presentation] is a window bound to a specific [Display]; showing it on the glasses'
 * display takes that display over from mirroring, so the glasses show the 3D workspace while
 * the phone keeps showing the control panel — two independent screens, no DeX required.
 */
class WorkspacePresentation(
    outerContext: Context,
    display: Display,
) : Presentation(outerContext, display) {

    private var surfaceView: WorkspaceSurfaceView? = null
    private var headTracking: HeadTracking? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = WorkspaceSurfaceView(context)
        surfaceView = view
        setContentView(view)
        WorkspaceController.register(view.workspaceRenderer)
        // Drive the camera from the glasses' head pose, so the screens stay world-fixed.
        val renderer = view.workspaceRenderer
        headTracking = HeadTracking(context) { w, x, y, z ->
            renderer.setHeadPose(w, x, y, z)
        }.also { it.start() }
    }

    override fun onStart() {
        super.onStart()
        surfaceView?.onResume()
    }

    override fun onStop() {
        surfaceView?.onPause()
        super.onStop()
    }

    override fun dismiss() {
        headTracking?.stop()
        headTracking = null
        surfaceView?.let { view ->
            WorkspaceController.unregister(view.workspaceRenderer)
            view.queueEvent {
                // Force-stop the launched apps before releasing their displays, so they close
                // rather than being relocated onto the phone's screen.
                view.workspaceRenderer.closeAllWindows()
                view.workspaceRenderer.releaseAll()
            }
            view.onPause()
        }
        surfaceView = null
        super.dismiss()
    }
}
