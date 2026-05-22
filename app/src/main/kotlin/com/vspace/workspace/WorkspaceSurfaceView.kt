package com.vspace.workspace

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper

/**
 * The [GLSurfaceView] the workspace is drawn into — an OpenGL ES 2.0 context rendering
 * continuously, so screen frames and (from M2) head motion update every frame.
 *
 * It hosts the same [WorkspaceRenderer] whether it lives in a `Presentation` on the glasses
 * or in the on-phone preview activity.
 */
class WorkspaceSurfaceView(context: Context) : GLSurfaceView(context) {

    val workspaceRenderer = WorkspaceRenderer(
        context = context,
        mainHandler = Handler(Looper.getMainLooper()),
    )

    init {
        setEGLContextClientVersion(2)
        // Keep the GL context across pause/resume, so the desktop and screens survive a blip.
        preserveEGLContextOnPause = true
        setRenderer(workspaceRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
