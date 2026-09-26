package com.uxspace

import android.app.Application
import android.os.Environment
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
            appendLine(error.toString())
            appendLine(error.stackTraceToString())
            var c = error.cause
            var n = 0
            while (c != null && n < 4) {
                appendLine("caused by: ${c}")
                appendLine(c.stackTraceToString())
                c = c.cause
                n++
            }
        }
        Log.e("UxSpace/Crash", text)
        val files = listOfNotNull(
            File(filesDir, "uxspace-crash.txt"),
            getExternalFilesDir(null)?.let { File(it, "uxspace-crash.txt") },
            File("/storage/emulated/0/Download/uxspace-crash.txt"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?.let { File(it, "uxspace-crash.txt") },
        )
        files.distinctBy { it.absolutePath }.forEach { f ->
            runCatching {
                f.parentFile?.mkdirs()
                f.writeText(text)
                f.setReadable(true, false)
            }
        }
        previous?.uncaughtException(thread, error)
    }
}
