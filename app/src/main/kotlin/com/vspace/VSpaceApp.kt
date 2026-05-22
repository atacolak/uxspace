package com.vspace

import android.app.Application
import com.vspace.shizuku.ShizukuManager
import com.vspace.workspace.DesktopPresentation
import com.vspace.workspace.WorkspaceController

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
        WorkspaceController.appLauncher = { displayId, packageName, activityName ->
            ShizukuManager.launchApp(displayId, packageName, activityName)
        }
    }
}
