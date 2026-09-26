package com.uxspace.spatial

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.SurfaceHolder
import android.util.Log
import android.view.WindowManager
import com.uxspace.glasses.HeadTracking

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
        // Presentation HWUI + GLSurfaceView + libcarina_vio all talk to EGL. On the Fold
        // that combination aborted HWUI with "pthread_mutex_lock on a destroyed mutex"
        // during the first glasses frame. Software-composite the Presentation chrome;
        // the workspace still renders on its own GL surface.
        window?.clearFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        val view = WorkspaceSurfaceView(context)
        surfaceView = view
        setContentView(view)
        WorkspaceController.register(view.workspaceRenderer)
        // The screen-fill (render band) comes from the user's persisted setting, pushed into
        // the controller by the app and re-applied here by register() → setScreenBand. Default
        // is 100% (full display); the user can letterbox it down in Settings → View if the
        // glasses' FOV edges are uncomfortable.
        // Drive the camera from the glasses' head pose, so the screens stay world-fixed.
        // The pose-stream watchdog inside HeadTracking flips the controller's
        // headTrackingActive flag on start/stop transitions, which (a) ungrays the FREE
        // toggle in the toolbars and (b) auto-reverts to PINNED when DOF dies.
        val renderer = view.workspaceRenderer
        headTracking = HeadTracking(
            context,
            onPose = { w, x, y, z -> renderer.setHeadPose(w, x, y, z) },
            onPosition = { x, y, z -> renderer.setHeadPosition(x, y, z) },
            use6Dof = { WorkspaceController.carina6Dof },
        ).also { tracking ->
            tracking.onStreamingChanged = { streaming ->
                WorkspaceController.headTrackingActive = streaming
            }
        }
        // Do not dlopen libglasses/libcarina_vio during Presentation/GLSurfaceView
        // first-frame setup — that raced HWUI on the Fold. Start tracking once the
        // glasses surface exists and the first GL context has had a chance to bind.
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Handler(Looper.getMainLooper()).postDelayed({ startHeadTrackingIfNeeded() }, 400)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
        // The DOF-retry button's hook (WorkspaceController.retryHeadTracking) is owned by
        // MainActivity, which routes it through restartHeadTracking() and reports the
        // success / failure outcome — so we don't wire it here.
    }

    private fun startHeadTrackingIfNeeded() {
        val tracking = headTracking ?: return
        if (tracking.isStarted()) return
        runCatching { tracking.start() }
            .onFailure { Log.e("UxSpace/Presentation", "head tracking start failed", it) }
    }

    override fun onStart() {
        super.onStart()
        surfaceView?.onResume()
        Handler(Looper.getMainLooper()).postDelayed({ startHeadTrackingIfNeeded() }, 800)
    }

    /**
     * Re-attempt head-tracking startup. No-op if it's already running. Used to recover
     * from the startup race where the glasses' DisplayPort side enumerates before the USB
     * IMU endpoint — without this hook, the first `HeadTracking.start()` returns with no
     * device found and DOF stays dead for the session. Wired by [com.uxspace.MainActivity]
     * from its `USB_DEVICE_ATTACHED` handler.
     */
    fun retryHeadTracking() {
        headTracking?.start()
    }

    /**
     * Force-restart head tracking with a full SDK teardown first. Used after a USB
     * rescan (from the DOF-stall watchdog): the prior SDK handle is bound to a USB
     * device file the kernel just removed, so we have to release it cleanly before
     * the post-bind attach intent triggers a fresh `start()`.
     */
    fun restartHeadTracking() {
        headTracking?.stop()
        headTracking?.start()
    }

    override fun onStop() {
        surfaceView?.onPause()
        super.onStop()
    }

    override fun dismiss() {
        headTracking?.stop()
        headTracking = null
        WorkspaceController.headTrackingActive = false
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
