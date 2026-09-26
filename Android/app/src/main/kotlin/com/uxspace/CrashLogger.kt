package com.uxspace

import android.app.Application
import android.util.Log
import java.io.File

/**
 * Last-resort Java crash dump. Native SIGABRT from libglasses still will not land here,
 * but a Kotlin/Java launch crash will.
 */
internal fun Application.installCrashLogger() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        val text = buildString {
            appendLine("UxSpace crash ${System.currentTimeMillis()}")
            appendLine("thread=${thread.name}")
            appendLine(error.stackTraceToString())
        }
        Log.e("UxSpace/Crash", text)
        runCatching { File(filesDir, "uxspace-crash.txt").writeText(text) }
        runCatching {
            File("/storage/emulated/0/Download/uxspace-crash.txt").writeText(text)
        }
        previous?.uncaughtException(thread, error)
    }
}
