package com.vspace.privileged

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
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
     * Two-finger pinch on [displayId], centred at (centerX, centerY), pointer spread
     * going from [fromSpan] to [toSpan] over [durationMs]. Builds a MotionEvent sequence
     * (DOWN, POINTER_DOWN, MOVEs, POINTER_UP, UP) and submits it through `InputManager`
     * directly — `input` only does a single pointer.
     *
     * Reflective access to `InputManager.getInstance()` and `injectInputEvent(...)` —
     * both are hidden but accessible from the shell uid this process runs as
     * (shell has `INJECT_EVENTS`).
     */
    override fun pinchOnDisplay(
        displayId: Int,
        centerX: Int,
        centerY: Int,
        fromSpan: Int,
        toSpan: Int,
        durationMs: Int,
    ) {
        try {
            val injector = obtainInjector() ?: run {
                Log.e(TAG, "pinch: InputManager unavailable")
                return
            }
            val steps = (durationMs / PINCH_STEP_MS).coerceAtLeast(3)
            val downAt = SystemClock.uptimeMillis()
            // The two pointers move horizontally apart from / together to the centre.
            fun pointAtStep(step: Int): Pair<FloatArray, FloatArray> {
                val t = step.toFloat() / steps
                val span = fromSpan + (toSpan - fromSpan) * t
                val half = span / 2f
                return floatArrayOf(centerX - half, centerY.toFloat()) to
                    floatArrayOf(centerX + half, centerY.toFloat())
            }
            val (start0, start1) = pointAtStep(0)
            injectMotionEvent(injector, displayId, downAt, downAt, MotionEvent.ACTION_DOWN,
                start0, null)
            injectMotionEvent(
                injector, displayId, downAt, downAt,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                start0, start1,
            )
            for (step in 1..steps) {
                val (p0, p1) = pointAtStep(step)
                val t = downAt + step.toLong() * PINCH_STEP_MS
                injectMotionEvent(injector, displayId, downAt, t, MotionEvent.ACTION_MOVE, p0, p1)
            }
            val (end0, end1) = pointAtStep(steps)
            val finalT = downAt + steps.toLong() * PINCH_STEP_MS
            injectMotionEvent(
                injector, displayId, downAt, finalT,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                end0, end1,
            )
            injectMotionEvent(injector, displayId, downAt, finalT, MotionEvent.ACTION_UP, end0, null)
        } catch (t: Throwable) {
            Log.e(TAG, "pinch failed", t)
        }
    }

    /** Build and submit one frame of the pinch — one or two pointers. */
    private fun injectMotionEvent(
        injector: Any,
        displayId: Int,
        downAt: Long,
        eventAt: Long,
        action: Int,
        p0: FloatArray,
        p1: FloatArray?,
    ) {
        val count = if (p1 == null) 1 else 2
        val props = Array(count) { idx ->
            MotionEvent.PointerProperties().apply {
                id = idx
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { idx ->
            val src = if (idx == 0) p0 else p1!!
            MotionEvent.PointerCoords().apply {
                x = src[0]
                y = src[1]
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downAt, eventAt, action, count, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        // MotionEvent has setDisplayId since API 30 (hidden in some versions).
        runCatching {
            event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        }
        val injectMethod = injector.javaClass.getMethod(
            "injectInputEvent",
            android.view.InputEvent::class.java,
            Int::class.javaPrimitiveType,
        )
        // 0 = INJECT_INPUT_EVENT_MODE_ASYNC.
        injectMethod.invoke(injector, event, 0)
        event.recycle()
    }

    /** `InputManager.getInstance()` or, on newer Android, an equivalent service-hosted singleton. */
    private fun obtainInjector(): Any? {
        return runCatching {
            val cls = Class.forName("android.hardware.input.InputManager")
            cls.getMethod("getInstance").invoke(null)
        }.onFailure { Log.w(TAG, "InputManager.getInstance() failed: ${it.message}") }.getOrNull()
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

        /** Frame spacing for the pinch interpolation in [pinchOnDisplay]. */
        private const val PINCH_STEP_MS = 16

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
                sendBinderToApp(server)
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
         * [BinderReceiverProvider].
         *
         * Cannot use [Context.getContentResolver] because this process wasn't started by
         * AMS — `acquireProvider` calls `IActivityManager.getContentProvider`, which
         * requires a registered application record for the calling pid and rejects us with
         * "Unable to find app for caller". Instead we go straight through
         * `IActivityManager.getContentProviderExternal` — the same hidden API the `cmd
         * content` shell command uses to call into providers from shell-uid — and invoke
         * `IContentProvider.call` directly. Both are accessed reflectively because they
         * are not in the public SDK.
         */
        private fun sendBinderToApp(binder: IBinder) {
            val authority = BinderReceiverProvider.AUTHORITY
            val token = Binder()
            val extras = Bundle().apply { putBinder(BinderReceiverProvider.EXTRA_BINDER, binder) }
            val activityManager = activityManagerService()
                ?: throw IllegalStateException("no IActivityManager binder")
            val iAmClass = Class.forName("android.app.IActivityManager")
            val holder = iAmClass
                .getMethod(
                    "getContentProviderExternal",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    IBinder::class.java,
                    String::class.java,
                )
                .invoke(activityManager, authority, 0, token, SHELL_PACKAGE)
                ?: throw IllegalStateException("getContentProviderExternal returned null")
            val provider = holder.javaClass.getField("provider").get(holder)
                ?: throw IllegalStateException("ContentProviderHolder.provider was null")
            try {
                invokeProviderCall(provider, authority, extras)
                Log.i(TAG, "sent binder via getContentProviderExternal")
            } finally {
                runCatching {
                    iAmClass.getMethod(
                        "removeContentProviderExternal",
                        String::class.java,
                        IBinder::class.java,
                    ).invoke(activityManager, authority, token)
                }
            }
        }

        /** `ActivityManager.getService()` — the singleton `IActivityManager` binder proxy. */
        private fun activityManagerService(): Any? = runCatching {
            Class.forName("android.app.ActivityManager")
                .getMethod("getService")
                .invoke(null)
        }.onFailure { Log.e(TAG, "ActivityManager.getService() failed", it) }.getOrNull()

        /**
         * Invoke `IContentProvider.call(...)` reflectively, building whichever calling
         * identity the platform expects: API 31+ uses an `AttributionSource`; older
         * versions take a plain `(callingPkg, callingFeatureId)` pair.
         */
        private fun invokeProviderCall(provider: Any, authority: String, extras: Bundle) {
            val iCpClass = Class.forName("android.content.IContentProvider")
            val callMethods = iCpClass.declaredMethods.filter {
                it.name == "call" && it.returnType == Bundle::class.java
            }
            val attribClass = runCatching { Class.forName("android.content.AttributionSource") }
                .getOrNull()
            val attribCall = if (attribClass != null) {
                callMethods.firstOrNull { it.parameterTypes.firstOrNull() == attribClass }
            } else {
                null
            }
            if (attribCall != null) {
                val ctor = attribClass!!.getConstructor(
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                )
                val src = ctor.newInstance(Process.SHELL_UID, SHELL_PACKAGE, null)
                attribCall.invoke(
                    provider, src, authority,
                    BinderReceiverProvider.METHOD_SET_BINDER, null, extras,
                )
                return
            }
            // Pre-API 31 fallback: (callingPkg, callingFeatureId?, authority, method, arg, extras).
            val stringCall = callMethods.firstOrNull {
                it.parameterTypes.firstOrNull() == String::class.java
            } ?: throw IllegalStateException("no usable IContentProvider.call signature")
            val params = stringCall.parameterTypes
            val args: Array<Any?> = when (params.size) {
                5 -> arrayOf(
                    SHELL_PACKAGE, authority,
                    BinderReceiverProvider.METHOD_SET_BINDER, null, extras,
                )
                6 -> arrayOf(
                    SHELL_PACKAGE, null, authority,
                    BinderReceiverProvider.METHOD_SET_BINDER, null, extras,
                )
                else -> throw IllegalStateException(
                    "unexpected IContentProvider.call arity ${params.size}",
                )
            }
            stringCall.invoke(provider, *args)
        }
    }
}
