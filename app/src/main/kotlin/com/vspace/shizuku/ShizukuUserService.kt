package com.vspace.shizuku

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import kotlin.system.exitProcess

/**
 * Runs inside a process Shizuku spawns with ADB-shell privileges (uid 2000).
 *
 * From shell context, `am` may launch apps onto any display and `input` may inject events
 * into any display — neither of which a normal app is allowed to do. This helper shells out
 * to those commands on the workspace's behalf, and creates the workspace's virtual displays:
 * a display created here is *trusted*, which a normal app's display is not.
 */
class ShizukuUserService() : IShizukuService.Stub() {

    /** Context Shizuku hands the user service — used to reach the DisplayManager. */
    private var context: Context? = null

    /** Trusted virtual displays created for the workspace, keyed by display id. */
    private val virtualDisplays = HashMap<Int, VirtualDisplay>()

    /** Shizuku instantiates the user service with this constructor when a Context is available. */
    @Suppress("unused")
    constructor(context: Context) : this() {
        this.context = context
    }

    override fun destroy() {
        releaseAllDisplays()
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
    ): Int {
        val displayManager = displayManager()
        if (displayManager == null) {
            Log.e(TAG, "createVirtualDisplay: no DisplayManager (context unavailable)")
            return -1
        }
        return try {
            val display = displayManager.createVirtualDisplay(
                name, width, height, densityDpi, surface, TRUSTED_DISPLAY_FLAGS,
            )
            if (display == null) {
                Log.e(TAG, "createVirtualDisplay returned null")
                return -1
            }
            val id = display.display.displayId
            synchronized(virtualDisplays) { virtualDisplays[id] = display }
            Log.i(TAG, "trusted virtual display created id=$id ${width}x$height '$name'")
            id
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            -1
        }
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        val display = synchronized(virtualDisplays) { virtualDisplays.remove(displayId) }
        if (display != null) {
            runCatching { display.release() }
            Log.i(TAG, "trusted virtual display released id=$displayId")
        }
    }

    private fun releaseAllDisplays() {
        val displays = synchronized(virtualDisplays) {
            virtualDisplays.values.toList().also { virtualDisplays.clear() }
        }
        displays.forEach { runCatching { it.release() } }
    }

    /**
     * A DisplayManager for creating the virtual display.
     *
     * The display must be created under the package that owns this process's uid (shell) —
     * `DisplayManagerService` rejects a mismatch with "packageName must match the calling
     * uid". The Shizuku-provided context carries the *app's* package (`com.vspace`), so the
     * display is created from a `com.android.shell` package context instead.
     */
    private fun displayManager(): DisplayManager? {
        val base = baseContext() ?: return null
        val shellContext = runCatching {
            base.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.onFailure { Log.e(TAG, "could not create a $SHELL_PACKAGE context", it) }.getOrNull()
            ?: return null
        return shellContext.getSystemService(DisplayManager::class.java)
    }

    /** The Shizuku-provided context, or this process's system context fetched reflectively. */
    private fun baseContext(): Context? {
        context?.let { return it }
        val ctx = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentActivityThread").invoke(null)
            activityThread.getMethod("getSystemContext").invoke(current) as Context
        }.onFailure { Log.e(TAG, "could not obtain a system context", it) }.getOrNull()
        context = ctx
        return ctx
    }

    override fun launchOnDisplay(
        displayId: Int,
        packageName: String,
        activityName: String,
    ): Boolean = run(
        "am", "start",
        "--display", displayId.toString(),
        "-n", "$packageName/$activityName",
        "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER",
        "-f", FLAG_NEW_TASK_MULTIPLE,
    )

    override fun tap(displayId: Int, x: Int, y: Int) {
        run("input", "-d", displayId.toString(), "tap", x.toString(), y.toString())
    }

    override fun swipe(
        displayId: Int,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Int,
    ) {
        run(
            "input", "-d", displayId.toString(), "swipe",
            fromX.toString(), fromY.toString(), toX.toString(), toY.toString(),
            durationMs.toString(),
        )
    }

    override fun key(displayId: Int, keyCode: Int) {
        run("input", "-d", displayId.toString(), "keyevent", keyCode.toString())
    }

    override fun text(displayId: Int, value: String) {
        run("input", "-d", displayId.toString(), "text", value)
    }

    override fun forceStop(packageName: String) {
        // Closes the app's windows and kills its process — so releasing the virtual display
        // it ran on has no live activity left to relocate onto the phone's screen.
        run("am", "force-stop", packageName)
    }

    /**
     * Whether [displayId] currently has an activity on it. Used after a Back press to tell
     * whether Back closed the app (so VSpace can close the now-empty window). Errs on the
     * side of `true` if the dump cannot be read or parsed, so a live app is never closed.
     */
    override fun displayHasActivity(displayId: Int): Boolean {
        val dump = runCapture("dumpsys", "activity", "activities") ?: return true
        if (!dump.contains("ActivityRecord{") || !dump.contains("Display #")) return true
        var inDisplay = false
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("Display #")) {
                val num = line.removePrefix("Display #").takeWhile(Char::isDigit).toIntOrNull()
                inDisplay = num == displayId
            } else if (inDisplay && line.contains("ActivityRecord{")) {
                return true
            }
        }
        return false
    }

    /** Run a shell command and return its standard output, or `null` if it could not run. */
    private fun runCapture(vararg command: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val output = process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            Log.e(TAG, "command failed: ${command.joinToString(" ")}", e)
            null
        }
    }

    /** Run a shell command, log anything it prints, and report a clean exit. */
    private fun run(vararg command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val output = (
                process.inputStream.bufferedReader().readText() +
                    process.errorStream.bufferedReader().readText()
                ).trim()
            val exit = process.waitFor()
            if (exit != 0 || output.isNotEmpty()) {
                Log.i(TAG, "[$exit] ${command.joinToString(" ")}${if (output.isEmpty()) "" else " :: $output"}")
            }
            exit == 0
        } catch (e: Exception) {
            Log.e(TAG, "command failed: ${command.joinToString(" ")}", e)
            false
        }
    }

    private companion object {
        const val TAG = "VSpace/Shizuku"
        // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK
        const val FLAG_NEW_TASK_MULTIPLE = "0x18000000"

        /** Package owning the shell uid — the virtual display is created under it. */
        const val SHELL_PACKAGE = "com.android.shell"

        /**
         * Flags for the workspace's virtual displays. `PUBLIC` so the system places activities
         * on it; `OWN_CONTENT_ONLY` so it never mirrors the phone; `PRESENTATION` marks it as
         * secondary content; `TRUSTED` (1 << 10 — a hidden constant) so an app launched onto
         * it may follow its own activity launches there rather than escaping to the phone.
         */
        const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        const val TRUSTED_DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                VIRTUAL_DISPLAY_FLAG_TRUSTED
    }
}
