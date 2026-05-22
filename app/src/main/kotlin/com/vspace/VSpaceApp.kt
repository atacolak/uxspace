package com.vspace

import android.app.Application
import com.vspace.shizuku.ShizukuManager

/** Application entry point — initialises Shizuku detection once at process start. */
class VSpaceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuManager.init(this)
    }
}
