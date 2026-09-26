package com.uxspace

import android.app.Application
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Java crash dump that Termux can read without ADB: world-readable files at
 * /storage/emulated/0/uxspace-crash.txt (Download is 0660 media_rw and unreadable).
 */
internal fun dumpCrashText(text: String) {
    Log.e("UxSpace/Crash", text)
    val files = listOf(
        File("/storage/emulated/0/uxspace-crash.txt"),
        File("/storage/emulated/0/Download/uxspace-crash.txt"),
        File("/sdcard/uxspace-crash.txt"),
    )
    for (f in files) {
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(text)
            runCatching { f.setReadable(true, false) }
            runCatching { f.setWritable(true, false) }
            runCatching { Os.chmod(f.absolutePath, 0b110_110_110) } // 0666
        }
    }
}

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
                appendLine("caused by: $c")
                appendLine(c.stackTraceToString())
                c = c.cause
                n++
            }
        }
        dumpCrashText(text)
        previous?.uncaughtException(thread, error)
    }
}

internal fun writeAliveMarker(stage: String) {
    val text = "alive stage=$stage t=${System.currentTimeMillis()}\n"
    val f = File("/storage/emulated/0/uxspace-alive.txt")
    runCatching {
        f.writeText(text)
        runCatching { f.setReadable(true, false) }
        runCatching { Os.chmod(f.absolutePath, 0b110_110_110) }
    }
}
