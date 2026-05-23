package com.vspace

import android.app.Application
import com.vspace.desktop.DesktopPresentation
import com.vspace.desktop.DrawerPresentation
import com.vspace.privileged.PrivilegedService
import com.vspace.spatial.WorkspaceController
import com.vspace.system.SystemStatus

/**
 * Application entry point — initialises the privileged-helper orchestrator and wires the
 * workspace's injectable hooks once at process start, before any activity or the renderer
 * runs.
 */
class VSpaceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrivilegedService.init(this)
        // The helper bootstraps over wireless-debugging ADB. Try to bring it up on every
        // launch — if pairing is needed or wireless debugging is off, the state machine
        // reflects it and MainActivity shows the setup card.
        PrivilegedService.ensureRunning()
        SystemStatus.init(this)

        // Keeps the rendering layer free of the desktop UI and the privileged path.
        WorkspaceController.desktopContent = { context, display ->
            DesktopPresentation(context, display)
        }
        WorkspaceController.drawerContent = { context, display ->
            DrawerPresentation(context, display)
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
            PrivilegedService.tap(displayId, x, y)
        }
        WorkspaceController.appScroll = { displayId, x, y, vScroll ->
            PrivilegedService.scrollOnDisplay(displayId, x, y, vScroll)
        }
        WorkspaceController.appPinch = { displayId, x, y, fromSpan, toSpan, durationMs ->
            PrivilegedService.pinchOnDisplay(displayId, x, y, fromSpan, toSpan, durationMs)
        }
        WorkspaceController.appBack = { displayId, onEmptied ->
            PrivilegedService.sendBack(displayId, onEmptied)
        }
        WorkspaceController.closeApp = { packageName ->
            PrivilegedService.forceStop(packageName)
        }
    }
}
