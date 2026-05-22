package com.vspace.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Detects Shizuku, walks the user through activating it, and — once ready — runs privileged
 * actions (launch an app onto a virtual display, inject input) through a shell-uid helper.
 *
 * VSpace cannot start Shizuku itself; activating it is the Shizuku app's job. What VSpace can
 * do is detect exactly which step is missing and send the user straight to it.
 */
object ShizukuManager {

    private const val TAG = "VSpace/Shizuku"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    // GitHub releases, not Play Store: the Play Store build is not offered on Android 16.
    private const val DOWNLOAD_URL = "https://github.com/RikkaApps/Shizuku/releases/latest"
    private const val PERMISSION_REQUEST_CODE = 4001

    /** Each value is one step the user (or VSpace) must clear before launches work. */
    enum class State {
        /** The Shizuku app is not installed. */
        NOT_INSTALLED,

        /** Installed, but its service is not running — not activated since the last boot. */
        NOT_RUNNING,

        /** Running, but the user has not granted VSpace access through Shizuku. */
        NEEDS_PERMISSION,

        /** Permission granted; the privileged helper is being bound. */
        CONNECTING,

        /** Helper bound — launches and input injection are available. */
        READY,
    }

    @Volatile
    var state: State = State.NOT_INSTALLED
        private set

    @Volatile
    private var service: IShizukuService? = null

    @Volatile
    private var binding = false

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vspace-shizuku") }
    private var appContext: Context? = null

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val onBinderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val onPermissionResult = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == PERMISSION_REQUEST_CODE) refresh()
    }

    /** Wire up Shizuku listeners. Call once, from the Application. */
    fun init(context: Context) {
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermissionResult)
        refresh()
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** Re-evaluate which step the user is on, binding the helper once it is reachable. */
    fun refresh() {
        val context = appContext ?: return
        val next = when {
            !isInstalled(context) -> State.NOT_INSTALLED
            !pingBinder() -> State.NOT_RUNNING
            !hasPermission() -> State.NEEDS_PERMISSION
            service != null -> State.READY
            else -> State.CONNECTING
        }
        if (next == State.CONNECTING) bindService()
        setState(next)
    }

    private fun setState(next: State) {
        if (next == state) return
        Log.i(TAG, "state: $state -> $next")
        state = next
        listeners.forEach { runCatching { it() } }
    }

    // region User-facing setup steps

    /** Where to send the user when [state] is [State.NOT_INSTALLED]. */
    fun downloadIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))

    /** Where to send the user when [state] is [State.NOT_RUNNING] — the Shizuku app itself. */
    fun openShizukuIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)

    /** Ask Shizuku to grant VSpace access; resolves [State.NEEDS_PERMISSION]. */
    fun requestPermission() {
        if (!pingBinder()) return
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { Log.e(TAG, "requestPermission failed", it) }
    }

    // endregion

    // region Privileged actions

    /** Launch an app onto [displayId]. No-op (logged) unless [state] is [State.READY]. */
    fun launchApp(displayId: Int, packageName: String, activityName: String) {
        val helper = service
        if (helper == null) {
            Log.w(TAG, "launchApp ignored — Shizuku not ready (state=$state)")
            return
        }
        worker.execute {
            runCatching {
                val ok = helper.launchOnDisplay(displayId, packageName, activityName)
                Log.i(TAG, "launch $packageName/$activityName display=$displayId ok=$ok")
            }.onFailure { Log.e(TAG, "launchApp failed", it) }
        }
    }

    fun tap(displayId: Int, x: Int, y: Int) =
        onWorker { service?.tap(displayId, x, y) }

    fun swipe(displayId: Int, fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int) =
        onWorker { service?.swipe(displayId, fromX, fromY, toX, toY, durationMs) }

    fun key(displayId: Int, keyCode: Int) =
        onWorker { service?.key(displayId, keyCode) }

    fun text(displayId: Int, value: String) =
        onWorker { service?.text(displayId, value) }

    /** Force-stop [packageName] — used to close an app launched into the workspace. */
    fun forceStop(packageName: String) =
        onWorker { service?.forceStop(packageName) }

    // endregion

    private fun onWorker(block: () -> Unit) {
        worker.execute { runCatching { block() } }
    }

    private fun bindService() {
        if (binding || service != null) return
        val context = appContext ?: return
        binding = true
        runCatching {
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(context, ShizukuUserService::class.java))
                    .daemon(false)
                    .processNameSuffix("shizuku")
                    .debuggable(false)
                    .version(1),
                connection,
            )
        }.onFailure {
            Log.e(TAG, "bindUserService failed", it)
            binding = false
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            binding = false
            service = if (binder != null && binder.pingBinder()) {
                IShizukuService.Stub.asInterface(binder)
            } else {
                null
            }
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binding = false
            service = null
            refresh()
        }
    }

    private fun isInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private fun pingBinder(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun hasPermission(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
}
