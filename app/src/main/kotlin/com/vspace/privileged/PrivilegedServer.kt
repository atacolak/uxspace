package com.vspace.privileged

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import kotlin.system.exitProcess

/**
 * VSpace's shell-uid privileged helper — runs `am` / `input`, creates the workspace's
 * trusted virtual displays, and hands itself back to the app over a Binder. It exists in
 * two activation modes, both passing through this same class:
 *
 *  - **Shizuku** (transitional). Shizuku binds it as a user service; the AIDL Stub is
 *    delivered through Shizuku's `bindUserService` callback.
 *  - **VSpace's own bootstrap** (`docs/PRIVILEGE.md`). `app_process` invokes [main], the
 *    server is constructed in the shell-uid process, and its Binder is handed to the app
 *    through [BinderReceiverProvider].
 *
 * From either entry point the privileged work is identical — only the start-up path
 * differs. `am`/`input` are shell-outs because shell uid may not call `ActivityTaskManager`
 * directly; `createVirtualDisplay` uses the `DisplayManager` from a `com.android.shell`
 * package context so the call passes `DisplayManagerService`'s package/uid check.
 */
class PrivilegedServer() : IPrivilegedService.Stub() {

    /** A Context — Shizuku-provided in the user-service path, system-context in [main]. */
    private var context: Context? = null

    /** Trusted virtual displays created for the workspace, keyed by display id. */
    private val virtualDisplays = HashMap<Int, VirtualDisplay>()

    /** Shizuku instantiates the user service with this constructor when a Context is available. */
    @Suppress("unused")
    constructor(context: Context) : this() {
        this.context = context
    }

    /** Used by [main] to set the system context once the ActivityThread is up. */
    internal fun setContext(context: Context) {
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
     * uid". A Shizuku-provided or app context carries the *app's* package (`com.vspace`),
     * so the display is created from a `com.android.shell` package context instead.
     */
    private fun displayManager(): DisplayManager? {
        val base = baseContext() ?: return null
        val shellContext = runCatching {
            base.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.onFailure { Log.e(TAG, "could not create a $SHELL_PACKAGE context", it) }.getOrNull()
            ?: return null
        return shellContext.getSystemService(DisplayManager::class.java)
    }

    /** The provided context, or this process's system context fetched reflectively. */
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

    companion object {
        private const val TAG = "VSpace/Privileged"

        // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK
        private const val FLAG_NEW_TASK_MULTIPLE = "0x18000000"

        /** Package owning the shell uid — the virtual display is created under it. */
        private const val SHELL_PACKAGE = "com.android.shell"

        /**
         * Flags for the workspace's virtual displays. `PUBLIC` so the system places activities
         * on it; `OWN_CONTENT_ONLY` so it never mirrors the phone; `PRESENTATION` marks it as
         * secondary content; `TRUSTED` (1 << 10 — a hidden constant) so an app launched onto
         * it may follow its own activity launches there rather than escaping to the phone.
         */
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        private const val TRUSTED_DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                VIRTUAL_DISPLAY_FLAG_TRUSTED

        /**
         * Entry point when this class is loaded by `app_process` from the ADB shell bootstrap
         * (see `ServerBootstrap`). Sets up a system context, builds the Stub, hands its
         * Binder to the VSpace app through [BinderReceiverProvider], then loops forever.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                Looper.prepareMainLooper()
                val systemContext = obtainSystemContext()
                    ?: throw IllegalStateException("could not obtain a system context")
                val server = PrivilegedServer().also { it.setContext(systemContext) }
                sendBinderToApp(systemContext, server)
                Log.i(TAG, "PrivilegedServer ready; entering main loop")
                Looper.loop()
            } catch (t: Throwable) {
                Log.e(TAG, "PrivilegedServer crashed during start-up", t)
                exitProcess(1)
            }
        }

        /** `ActivityThread.systemMain().getSystemContext()` — the standard app_process bootstrap. */
        private fun obtainSystemContext(): Context? = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val systemMain = activityThread.getMethod("systemMain").invoke(null)
            activityThread.getMethod("getSystemContext").invoke(systemMain) as Context
        }.onFailure { Log.e(TAG, "obtainSystemContext failed", it) }.getOrNull()

        /**
         * Pass [binder] back to the VSpace app process by calling its
         * [BinderReceiverProvider] — Binders survive in a Bundle across the process
         * boundary. This is the same trick Shizuku uses to publish its own server.
         *
         * The call needs the calling package to belong to this process's uid (shell, 2000),
         * or AMS rejects with "Given calling package android does not match caller's uid
         * 2000". The system context boots with package "android" (uid 1000), and
         * `createPackageContext("com.android.shell")` only changes `mBasePackageName` —
         * `mOpPackageName` (what `ContentResolver` actually uses) is *inherited* from the
         * parent. So we force both fields reflectively.
         */
        private fun sendBinderToApp(context: Context, binder: IBinder) {
            val shellContext = makeShellContext(context)
            val authority = Uri.parse("content://${BinderReceiverProvider.AUTHORITY}")
            val extras = Bundle().apply { putBinder(BinderReceiverProvider.EXTRA_BINDER, binder) }
            shellContext.contentResolver.call(
                authority, BinderReceiverProvider.METHOD_SET_BINDER, null, extras,
            )
        }

        /**
         * Wrap [base] in a context whose `getOpPackageName()` returns `com.android.shell`.
         * Reflection is needed because `createPackageContext` doesn't override
         * `mOpPackageName` — see the comment on [sendBinderToApp].
         */
        private fun makeShellContext(base: Context): Context {
            val shellContext = runCatching {
                base.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            }.onFailure {
                Log.e(TAG, "createPackageContext($SHELL_PACKAGE) failed", it)
            }.getOrNull() ?: base
            runCatching {
                val cls = Class.forName("android.app.ContextImpl")
                for (name in arrayOf("mOpPackageName", "mBasePackageName")) {
                    val f = cls.getDeclaredField(name)
                    f.isAccessible = true
                    f.set(shellContext, SHELL_PACKAGE)
                }
            }.onFailure {
                Log.e(TAG, "could not force op package to $SHELL_PACKAGE", it)
            }
            return shellContext
        }
    }
}
