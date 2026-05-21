package com.viturekit.sample.stereo

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/**
 * A [GLSurfaceView] preconfigured for the [StereoRenderer]: an OpenGL ES 2.0 context and
 * continuous rendering, so the scene keeps animating and tracking head motion every frame.
 */
class StereoGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    /** The renderer driving this surface — feed it head orientation each IMU sample. */
    val stereoRenderer = StereoRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(stereoRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
