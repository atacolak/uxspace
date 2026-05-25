package com.uxspace.privileged

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
 * UxSpace's shell-uid privileged helper — runs `am` / `input`, creates the workspace's
 * trusted virtual displays, and hands itself back to the app over a Binder. It exists in
 * two activation modes, both passing through this same class:
 *
 *  - **Shizuku** (transitional). Shizuku binds it as a user service; the AIDL Stub is
 *    delivered through Shizuku's `bindUserService` callback.
 *  - **UxSpace's own bootstrap** (`docs/PRIVILEGE.md`). `app_process` invokes [main], the
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
     * uid". A Shizuku-provided or app context carries the *app's* package (`com.uxspace`),
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
    ): Boolean {
        Log.i(
            "UxSpace/Launch",
            "11) helper.launchOnDisplay pkg=$packageName/$activityName display=$displayId",
        )
        val ok = runVerbose(
            "am", "start",
            "--display", displayId.toString(),
            // 5 = WINDOWING_MODE_FREEFORM — launches the activity into a freeform,
            // movable window rather than fullscreen. Falls through gracefully to
            // fullscreen if the device doesn't expose FEATURE_FREEFORM_WINDOW_MANAGEMENT.
            "--windowingMode", "5",
            "-n", "$packageName/$activityName",
            "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER",
            // NEW_TASK | MULTIPLE_TASK as raw flags. EXCLUDE_FROM_RECENTS is split out
            // into am's high-level `--activity-exclude-from-recents` argument because
            // bundling 0x00800000 into the raw -f blob broke launches on a secondary
            // trusted display — the activity record was created on the right display
            // but never produced a visible frame, leaving the taskbar with an icon
            // but the workspace showing only wallpaper.
            "-f", FLAG_NEW_TASK_MULTIPLE,
            "--activity-exclude-from-recents",
        )
        Log.i("UxSpace/Launch", "12) am-start returned ok=$ok display=$displayId")
        // ~600 ms after the launch, dump the activity stack for this display so we can
        // see whether the activity actually landed where we asked it to. The dumpsys
        // call is cheap; this only fires once per launch.
        scheduleDisplayDump(displayId, packageName)
        return ok
    }

    /**
     * Fire a one-shot delayed dump of `dumpsys activity activities` filtered to a
     * specific display id, so we can see post-launch what's actually on that display.
     * Helps diagnose the "icon shown in taskbar but no window" symptom — if the
     * dump shows the activity on a different display (or not at all), we know the
     * launch routing went wrong.
     */
    private fun scheduleDisplayDump(displayId: Int, packageName: String) {
        Thread {
            try {
                Thread.sleep(600)
                val dump = runCapture("dumpsys", "activity", "activities") ?: return@Thread
                val lines = dump.lineSequence().toList()
                var inDisplay = false
                var matched = 0
                val out = StringBuilder()
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.startsWith("Display #")) {
                        val num = line.removePrefix("Display #").takeWhile(Char::isDigit).toIntOrNull()
                        inDisplay = num == displayId
                        if (inDisplay) out.appendLine(line)
                    } else if (inDisplay && (
                            line.contains("ActivityRecord{") ||
                                line.contains("Stack ") ||
                                line.contains("RootTask ") ||
                                line.startsWith("* Task")
                            )
                    ) {
                        out.appendLine("    $line")
                        matched++
                    }
                }
                Log.i(
                    "UxSpace/Launch",
                    "13) dumpsys display=$displayId activities=$matched for pkg=$packageName\n" +
                        if (out.isEmpty()) "    (no display section found)" else out.toString().trimEnd(),
                )
            } catch (_: InterruptedException) {
                // Helper shutting down — fine.
            }
        }.start()
    }

    /**
     * Like [run] but always logs stdout and stderr separately on completion — used for
     * the launch path so we can see exactly what `am start` printed. The other shell-out
     * call-sites (input tap, swipe, key, force-stop) stay on [run] which logs only when
     * something printed or the exit code was non-zero, to keep input logs quiet.
     */
    private fun runVerbose(vararg command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            Log.i(TAG, "[$exit] ${command.joinToString(" ")}")
            if (stdout.isNotEmpty()) Log.i(TAG, "  stdout: $stdout")
            if (stderr.isNotEmpty()) Log.i(TAG, "  stderr: $stderr")
            exit == 0
        } catch (e: Exception) {
            Log.e(TAG, "command failed: ${command.joinToString(" ")}", e)
            false
        }
    }

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

    /**
     * Streamed touch injection: one [ACTION_DOWN] / [ACTION_MOVE] / [ACTION_UP] /
     * [ACTION_CANCEL] motion event at a time on the target display. The down timestamp
     * is latched on `DOWN` and reused for the rest of the sequence, so the app sees a
     * coherent gesture (which is required by Android's input dispatching).
     */
    override fun injectTouch(displayId: Int, x: Int, y: Int, action: Int) {
        try {
            val injector = obtainInjector() ?: run {
                Log.e(TAG, "touch: InputManager unavailable")
                return
            }
            val androidAction = when (action) {
                0 -> MotionEvent.ACTION_DOWN
                1 -> MotionEvent.ACTION_MOVE
                2 -> MotionEvent.ACTION_UP
                3 -> MotionEvent.ACTION_CANCEL
                else -> {
                    Log.e(TAG, "touch: unknown action $action")
                    return
                }
            }
            val now = SystemClock.uptimeMillis()
            val downAt = if (androidAction == MotionEvent.ACTION_DOWN) {
                touchDownAt[displayId] = now
                now
            } else {
                touchDownAt[displayId] ?: now
            }
            val pt = floatArrayOf(x.toFloat(), y.toFloat())
            val ok = injectMotionEvent(injector, displayId, downAt, now, androidAction, pt, null)
            if (!ok) {
                Log.w(TAG, "touch inject FAILED action=$androidAction display=$displayId at ($x,$y)")
            }
            if (androidAction == MotionEvent.ACTION_UP ||
                androidAction == MotionEvent.ACTION_CANCEL) {
                touchDownAt.remove(displayId)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "touch injection failed", t)
        }
    }

    /** Per-display down timestamp for a streamed touch sequence. */
    private val touchDownAt = mutableMapOf<Int, Long>()

    /**
     * Inject a one-shot ACTION_SCROLL motion event on the target display — mouse-wheel
     * equivalent, fast, no synthesized touch swipe. Same shape as `UiScreen.dispatchScroll`,
     * routed through `InputManager.injectInputEvent` so the event lands on an
     * out-of-process VirtualDisplay.
     */
    override fun injectScroll(displayId: Int, x: Int, y: Int, vScroll: Float) {
        try {
            val injector = obtainInjector() ?: run {
                Log.e(TAG, "scroll: InputManager unavailable")
                return
            }
            val now = SystemClock.uptimeMillis()
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_MOUSE
                },
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x.toFloat()
                    this.y = y.toFloat()
                    setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                },
            )
            val event = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0,
            )
            event.source = InputDevice.SOURCE_MOUSE
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
        } catch (t: Throwable) {
            Log.e(TAG, "scroll failed", t)
        }
    }

    /** Build and submit one frame of the pinch — one or two pointers. Returns the
     *  injectInputEvent boolean — true means the event was accepted by the dispatcher. */
    private fun injectMotionEvent(
        injector: Any,
        displayId: Int,
        downAt: Long,
        eventAt: Long,
        action: Int,
        p0: FloatArray,
        p1: FloatArray?,
    ): Boolean {
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
        // MotionEvent has setDisplayId since API 30 (hidden in some versions). Read it
        // back so we can tell if the reflection silently failed — that would route the
        // event to display 0 (the phone screen) instead of the virtual display.
        val setOk = runCatching {
            event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        }.isSuccess
        val actualDisplayId = runCatching {
            event.javaClass.getMethod("getDisplayId").invoke(event) as? Int
        }.getOrNull() ?: -1
        if (!setOk || actualDisplayId != displayId) {
            Log.w(TAG, "displayId mismatch: setOk=$setOk wanted=$displayId got=$actualDisplayId")
        }
        val injectMethod = injector.javaClass.getMethod(
            "injectInputEvent",
            android.view.InputEvent::class.java,
            Int::class.javaPrimitiveType,
        )
        // 0 = INJECT_INPUT_EVENT_MODE_ASYNC. Returns Boolean — false means the
        // dispatcher rejected the event (permission, no window, wrong display, …).
        val result = injectMethod.invoke(injector, event, 0)
        event.recycle()
        return (result as? Boolean) ?: false
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
     * whether Back closed the app (so UxSpace can close the now-empty window). Errs on the
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
        private const val TAG = "UxSpace/Privileged"

        // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK. The third bit we want
        // (FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS, 0x00800000) is no longer ORed in here —
        // we pass it via am's `--activity-exclude-from-recents` argument instead so it
        // doesn't ride in the same raw -f blob. See launchOnDisplay() for the reason.
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
         *
         * `OWN_DISPLAY_GROUP` (1<<11) + `ALWAYS_UNLOCKED` (1<<12) were tried as a way to keep
         * the Samsung-injected KEYGUARD_DIALOG window off this display — but together they
         * leave the display in `state OFF` (DisplayPowerManager in the new group never
         * receives a power-on), which kills rendering entirely. Don't combine them again.
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
         * Binder to the UxSpace app through [BinderReceiverProvider], then loops forever.
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
         * Pass [binder] back to the UxSpace app process by calling its
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
