package com.vspace

import android.app.Application
import com.vspace.desktop.DesktopPresentation
import com.vspace.desktop.DrawerPresentation
import com.vspace.privileged.ShizukuManager
import com.vspace.spatial.WorkspaceController

/**
 * Application entry point — initialises Shizuku detection and wires the workspace's
 * injectable hooks once at process start, before any activity or the renderer runs.
 */
class VSpaceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuManager.init(this)
        // Keeps the rendering layer free of the desktop UI and Shizuku.
        WorkspaceController.desktopContent = { context, display ->
            DesktopPresentation(context, display)
        }
        WorkspaceController.drawerContent = { context, display ->
            DrawerPresentation(context, display)
        }
        WorkspaceController.createVirtualDisplay = { name, width, height, dpi, surface ->
            ShizukuManager.createVirtualDisplay(name, width, height, dpi, surface)
        }
        WorkspaceController.releaseVirtualDisplay = { displayId ->
            ShizukuManager.releaseVirtualDisplay(displayId)
        }
        WorkspaceController.appLauncher = { displayId, packageName, activityName ->
            ShizukuManager.launchApp(displayId, packageName, activityName)
        }
        WorkspaceController.appTap = { displayId, x, y ->
            ShizukuManager.tap(displayId, x, y)
        }
        WorkspaceController.appBack = { displayId, onEmptied ->
            ShizukuManager.sendBack(displayId, onEmptied)
        }
        WorkspaceController.closeApp = { packageName ->
            ShizukuManager.forceStop(packageName)
        }
    }
}
