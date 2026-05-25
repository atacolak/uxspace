package com.uxspace

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.uxspace.apps.AppCache
import com.uxspace.desktop.DesktopPresentation
import com.uxspace.privileged.PrivilegedService
import com.uxspace.spatial.WorkspaceController
import com.uxspace.spatial.WorkspaceController.ToolbarIcons
import com.uxspace.system.SystemStatus

private const val TAP_DOWN_UP_GAP_MS = 40L
private val tapDelayHandler = Handler(Looper.getMainLooper())

/**
 * Application entry point — initialises the privileged-helper orchestrator and wires the
 * workspace's injectable hooks once at process start, before any activity or the renderer
 * runs.
 */
class UxSpaceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrivilegedService.init(this)
        // The helper bootstraps over wireless-debugging ADB. Try to bring it up on every
        // launch — if pairing is needed or wireless debugging is off, the state machine
        // reflects it and MainActivity shows the setup card.
        PrivilegedService.ensureRunning()
        SystemStatus.init(this)
        // Pre-load the app list and rasterise icons on a background thread now, so the
        // drawer's first open is instant and scrolling doesn't hitch on icon draws.
        AppCache.preload(this)

        // Keeps the rendering layer free of the desktop UI and the privileged path.
        WorkspaceController.desktopContent = { context, display, slotIdx, showTaskbar ->
            DesktopPresentation(context, display, slotIdx, showTaskbar)
        }
        WorkspaceController.createVirtualDisplay = { name, width, height, dpi, surface ->
            PrivilegedService.createVirtualDisplay(name, width, height, dpi, surface)
        }
        WorkspaceController.releaseVirtualDisplay = { displayId ->
            PrivilegedService.releaseVirtualDisplay(displayId)
        }
        WorkspaceController.appLauncher = { displayId, packageName, activityName ->
            PrivilegedService.launchApp(displayId, packageName, activityName)
        }
        WorkspaceController.appTap = { displayId, x, y ->
            // Use the same InputManager.injectInputEvent path as the drag — `input tap`
            // shell-outs aren't reliable on out-of-process virtual displays. Some apps
            // reject 0-duration taps (DOWN and UP at the same eventTime), so space
            // them ~40 ms apart with a delayed UP.
            PrivilegedService.injectTouch(displayId, x, y, 0) // TOUCH_DOWN
            tapDelayHandler.postDelayed({
                PrivilegedService.injectTouch(displayId, x, y, 2) // TOUCH_UP
            }, TAP_DOWN_UP_GAP_MS)
        }
        WorkspaceController.appScroll = { displayId, x, y, vScroll ->
            PrivilegedService.scrollOnDisplay(displayId, x, y, vScroll)
        }
        WorkspaceController.closeApp = { packageName ->
            PrivilegedService.forceStop(packageName)
        }
        WorkspaceController.displayHasActivity = { displayId ->
            PrivilegedService.displayHasActivity(displayId)
        }
        // In-view toolbar icon resources — the renderer turns these into GL textures
        // when its surface comes up. Same Material-Symbols set the taskbar uses, so
        // both toolbars read identically.
        WorkspaceController.toolbarIcons = ToolbarIcons(
            lock = R.drawable.ic_pin,
            unlock = R.drawable.ic_pin_off,
            zoom = R.drawable.ic_zoom_in,
            recenter = R.drawable.ic_recenter,
            layout = R.drawable.ic_layout,
            settings = R.drawable.ic_settings,
        )
    }
}
