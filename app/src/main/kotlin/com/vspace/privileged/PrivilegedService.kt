package com.vspace.privileged

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Orchestrates VSpace's own shell-uid helper: pairs with the device's wireless-debugging
 * dialog, discovers and connects to the ADB connect service, starts a [PrivilegedServer]
 * over `app_process`, and exposes the privileged-call surface VSpace uses to launch apps,
 * inject input, and create trusted virtual displays.
 *
 * Built in stage 4 of [docs/PRIVILEGE.md]; not yet wired by VSpaceApp — the app still goes
 * through [ShizukuManager]. Stage 5 swaps the wiring and removes the Shizuku path.
 *
 * Lifecycle:
 *
 *  - First run, after [activate] pairs with a 6-digit code, the ADB identity (key + cert)
 *    is persisted; the device trusts it permanently.
 *  - Every launch, [ensureRunning] discovers the connect port via mDNS, connects, and
 *    starts the server. The returned [AdbStream] is held open so the server stays alive;
 *    closing it (or the app dying) tears the server down.
 *  - The server sends its Binder back through [BinderReceiverProvider]; that arrives at
 *    [onPrivilegedBinder] and moves the state machine to [State.READY].
 */
object PrivilegedService {

    private const val TAG = "VSpace/Privileged"

    /** Steps the user (or VSpace) must clear before privileged calls work. */
    enum class State {
        /** Android < 11 — the wireless-debugging path does not exist. */
        UNSUPPORTED,

        /**
         * Developer options is locked. Wireless Debugging can't be turned on until the user
         * unlocks Developer options (Settings → About → tap Build number 7×), so the wizard
         * walks them through that first.
         */
        NEEDS_DEVELOPER_OPTIONS,

        /** Paired before, but the connect service is not on mDNS (wireless debugging off). */
        NEEDS_WIRELESS_DEBUGGING,

        /**
         * Never paired (or the device forgot our key — connect failed with
         * AdbPairingRequiredException, which clears the paired marker). The wizard asks for
         * a pairing code.
         */
        NEEDS_PAIRING,

        /** mDNS / TCP work in progress. */
        DISCOVERING,
        CONNECTING,
        STARTING,

        /** Helper bound — privileged calls go through. */
        READY,
    }

    @Volatile
    var state: State = State.NEEDS_PAIRING
        private set

    @Volatile
    private var service: IPrivilegedService? = null

    /** Held to keep the helper alive — closing the stream brings the server down with it. */
    @Volatile
    private var adbStream: AdbStream? = null

    private var appContext: Context? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vspace-privileged") }
    private val scheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "vspace-privileged-sched")
    }

    /** Call once, from the Application. Computes the initial state from persisted identity. */
    fun init(context: Context) {
        appContext = context.applicationContext
        state = computeInitialState(context)
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun computeInitialState(context: Context): State = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> State.UNSUPPORTED
        !isDevOptionsEnabled(context) -> State.NEEDS_DEVELOPER_OPTIONS
        hasPaired(context) -> State.NEEDS_WIRELESS_DEBUGGING
        else -> State.NEEDS_PAIRING
    }

    /** Developer options unlocked? Read-only access to Settings.Global; no permission needed. */
    private fun isDevOptionsEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) != 0
    }.getOrDefault(false)

    /**
     * Whether VSpace has previously paired successfully. A separate marker file from the key
     * — the key is generated and persisted on first use of [AdbConnectionManager], whether or
     * not the device ever accepted it, so the key file's existence proves nothing.
     */
    private fun hasPaired(context: Context): Boolean =
        File(context.filesDir, PAIRED_MARKER).exists()

    private fun markPaired(context: Context, paired: Boolean) {
        val marker = File(context.filesDir, PAIRED_MARKER)
        if (paired) {
            marker.parentFile?.mkdirs()
            runCatching { marker.createNewFile() }
        } else {
            runCatching { marker.delete() }
        }
    }

    private fun setState(next: State) {
        if (next == state) return
        Log.i(TAG, "state: $state -> $next")
        state = next
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }

    /**
     * Try to make the helper live — discover the connect port via mDNS, connect, start the
     * server. Idempotent; safe to call from anywhere. The state machine reflects progress.
     */
    fun ensureRunning() {
        worker.execute {
            val ctx = appContext ?: return@execute
            if (state == State.UNSUPPORTED) return@execute
            if (state == State.READY && service != null) return@execute
            // Re-evaluate prerequisites on every attempt — the user may have just unlocked
            // Developer options or toggled Wireless Debugging.
            if (!isDevOptionsEnabled(ctx)) {
                setState(State.NEEDS_DEVELOPER_OPTIONS)
                return@execute
            }
            if (!hasPaired(ctx)) {
                setState(State.NEEDS_PAIRING)
                return@execute
            }

            setState(State.DISCOVERING)
            val endpoint = AdbDiscovery.discoverConnect(ctx, DISCOVERY_TIMEOUT_MS)
            if (endpoint == null) {
                Log.w(TAG, "connect service not advertised — wireless debugging off?")
                setState(State.NEEDS_WIRELESS_DEBUGGING)
                return@execute
            }

            setState(State.CONNECTING)
            val adb = AdbConnectionManager.getInstance(ctx)
            val connected = try {
                adb.connect(endpoint.host, endpoint.port)
            } catch (e: AdbPairingRequiredException) {
                // The device no longer trusts our key — wipe the marker and walk the user
                // back through pairing.
                Log.w(TAG, "device requires (re-)pairing — invalidating the paired marker")
                markPaired(ctx, false)
                setState(State.NEEDS_PAIRING)
                return@execute
            } catch (e: Exception) {
                Log.e(TAG, "ADB connect failed", e)
                setState(State.NEEDS_WIRELESS_DEBUGGING)
                return@execute
            }
            if (!connected) {
                Log.w(TAG, "ADB connect returned false — wireless debugging gone?")
                setState(State.NEEDS_WIRELESS_DEBUGGING)
                return@execute
            }

            setState(State.STARTING)
            val stream = ServerBootstrap.start(ctx, adb)
            if (stream == null) {
                Log.w(TAG, "could not start PrivilegedServer over ADB")
                setState(State.NEEDS_WIRELESS_DEBUGGING)
                return@execute
            }
            adbStream = stream
            // BinderReceiverProvider.onPrivilegedBinder() drives us to READY when the server
            // publishes its Binder back to the app.
        }
    }

    /**
     * Pair using the 6-digit [pairingCode] from Wireless Debugging's "Pair device with a
     * pairing code" dialog. The pairing port is discovered via mDNS — this only works
     * while the dialog is still in the foreground, which is why VSpace reads the code
     * from a notification's `RemoteInput` (the shade overlays the dialog instead of
     * backgrounding it). [done] runs on the worker thread.
     */
    fun activate(pairingCode: String, done: (Boolean) -> Unit) {
        worker.execute {
            val ctx = appContext ?: run { done(false); return@execute }
            try {
                val endpoint = AdbDiscovery.discoverPairing(ctx, DISCOVERY_TIMEOUT_MS)
                if (endpoint == null) {
                    Log.w(TAG, "pair: mDNS did not find the service — is the dialog still up?")
                    markPaired(ctx, false)
                    done(false); return@execute
                }
                val adb = AdbConnectionManager.getInstance(ctx)
                val paired = adb.pair(endpoint.host, endpoint.port, pairingCode)
                Log.i(TAG, "ADB pair ${endpoint.host}:${endpoint.port} ok=$paired")
                markPaired(ctx, paired)
                if (!paired) { done(false); return@execute }
                done(true)
                ensureRunning()
            } catch (e: Exception) {
                Log.e(TAG, "activate failed", e)
                markPaired(ctx, false)
                done(false)
            }
        }
    }

    /**
     * Called by [BinderReceiverProvider] when [PrivilegedServer] hands its Binder back. The
     * provider gates this on the calling uid (shell or self), so this is reachable only
     * from the server we started.
     */
    fun onPrivilegedBinder(binder: IBinder) {
        if (!binder.pingBinder()) {
            Log.w(TAG, "onPrivilegedBinder: ping failed")
            return
        }
        service = IPrivilegedService.Stub.asInterface(binder)
        Log.i(TAG, "privileged binder bound — READY")
        setState(State.READY)
    }

    /** Drop the binder and tear the server down (close its stream). Called on app teardown. */
    fun shutdown() {
        runCatching { adbStream?.close() }
        adbStream = null
        service = null
        if (state == State.READY) setState(State.NEEDS_WIRELESS_DEBUGGING)
    }

    // region Privileged-call surface (mirrors ShizukuManager so VSpaceApp swaps cleanly)

    fun launchApp(displayId: Int, packageName: String, activityName: String) {
        val helper = service
        if (helper == null) {
            Log.w(TAG, "launchApp ignored — not READY (state=$state)")
            return
        }
        worker.execute {
            runCatching {
                val ok = helper.launchOnDisplay(displayId, packageName, activityName)
                Log.i(TAG, "launch $packageName/$activityName display=$displayId ok=$ok")
            }.onFailure { Log.e(TAG, "launchApp failed", it) }
        }
    }

    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
    ): Int? {
        val helper = service
        if (helper == null) {
            Log.w(TAG, "createVirtualDisplay ignored — not READY (state=$state)")
            return null
        }
        return runCatching {
            helper.createVirtualDisplay(name, width, height, densityDpi, surface)
                .takeIf { it >= 0 }
        }.onFailure { Log.e(TAG, "createVirtualDisplay failed", it) }.getOrNull()
    }

    fun releaseVirtualDisplay(displayId: Int) =
        onWorker { service?.releaseVirtualDisplay(displayId) }

    fun tap(displayId: Int, x: Int, y: Int) =
        onWorker { service?.tap(displayId, x, y) }

    fun swipe(displayId: Int, fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int) =
        onWorker { service?.swipe(displayId, fromX, fromY, toX, toY, durationMs) }

    fun key(displayId: Int, keyCode: Int) = onWorker { service?.key(displayId, keyCode) }

    fun text(displayId: Int, value: String) = onWorker { service?.text(displayId, value) }

    fun forceStop(packageName: String) = onWorker { service?.forceStop(packageName) }

    fun sendBack(displayId: Int, onEmptied: () -> Unit) {
        onWorker { service?.key(displayId, KeyEvent.KEYCODE_BACK) }
        scheduler.schedule(
            {
                runCatching {
                    if (service?.displayHasActivity(displayId) == false) onEmptied()
                }.onFailure { Log.e(TAG, "back-empty check failed", it) }
            },
            BACK_SETTLE_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    // endregion

    private fun onWorker(block: () -> Unit) {
        worker.execute { runCatching { block() } }
    }

    /** How long to wait for an mDNS hit before giving up. */
    private const val DISCOVERY_TIMEOUT_MS = 5_000L

    /** How long to wait after a Back press before checking whether it closed the app. */
    private const val BACK_SETTLE_MS = 800L

    /** Path of the marker file created on a successful [activate] (relative to filesDir). */
    private const val PAIRED_MARKER = "adb/paired.flag"
}
