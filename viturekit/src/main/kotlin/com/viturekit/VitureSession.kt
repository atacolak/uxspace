package com.viturekit

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.viturekit.internal.AndroidDisplayWatcher
import com.viturekit.internal.DisplayWatcher
import com.viturekit.internal.ImuParser
import com.viturekit.model.ConnectionState
import com.viturekit.model.DisplayMode
import com.viturekit.model.ImuFrequency
import com.viturekit.model.ImuReading
import com.viturekit.model.VitureEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The single entry point of VitureKit: an idiomatic, lifecycle-aware Kotlin facade over a
 * [VitureGlasses] backend.
 *
 * A session owns the device lifecycle — USB attach/detach, the permission handshake, IMU
 * streaming and display control — and republishes everything as cold-free `Flow`s:
 *
 *  - [connectionState], [displayMode], [imuEnabled] — `StateFlow`s, always readable.
 *  - [imu] — a hot `SharedFlow` of IMU samples, with configurable backpressure.
 *  - [latestImu] — the most recent sample as a `StateFlow`, convenient for render loops.
 *  - [events] — one-shot [VitureEvent]s.
 *
 * Obtain one with [VitureSession.create], drive it with [connect]/[disconnect], and let it
 * follow your UI by calling [bindToLifecycle]. A bound session tears the device down cleanly
 * on `onDestroy`, so there are no leaked USB handles across configuration changes.
 *
 * All backend calls are confined to a single private thread; the public API is safe to call
 * from any thread.
 *
 * ```
 * val session = VitureSession.create(context, StubVitureGlasses())
 *     .bindToLifecycle(this)
 * session.connect()
 * lifecycleScope.launch {
 *     session.imu.collect { reading -> cursor.moveTo(reading.euler) }
 * }
 * ```
 */
class VitureSession internal constructor(
    private val glasses: VitureGlasses,
    /** The configuration this session was created with. */
    val config: VitureConfig,
    private val displayWatcher: DisplayWatcher,
) : DefaultLifecycleObserver, AutoCloseable {

    private val parser = ImuParser(config.imuByteOrder)

    // Every backend call runs on this single thread, so VitureGlasses need not be thread-safe.
    private val sdkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VitureKit-Session").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + sdkExecutor.asCoroutineDispatcher())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    /** The live connection state — the source of truth for whether control calls take effect. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _displayMode = MutableStateFlow(DisplayMode.MODE_2D)
    /** The current glasses display mode. */
    val displayMode: StateFlow<DisplayMode> = _displayMode.asStateFlow()

    private val _imuEnabled = MutableStateFlow(false)
    /** Whether the IMU is currently streaming samples. */
    val imuEnabled: StateFlow<Boolean> = _imuEnabled.asStateFlow()

    private val _latestImu = MutableStateFlow<ImuReading?>(null)
    /** The most recently received IMU sample, or `null` before the first one. */
    val latestImu: StateFlow<ImuReading?> = _latestImu.asStateFlow()

    private val _imu = MutableSharedFlow<ImuReading>(
        replay = 0,
        extraBufferCapacity = config.imuBufferCapacity.coerceAtLeast(1),
        onBufferOverflow = if (config.dropOldestImuSamples) {
            BufferOverflow.DROP_OLDEST
        } else {
            BufferOverflow.DROP_LATEST
        },
    )
    /**
     * The hot stream of IMU samples. Collecting it has no effect on the device — the IMU
     * runs whenever [imuEnabled] is `true`, regardless of collectors.
     */
    val imu: SharedFlow<ImuReading> = _imu.asSharedFlow()

    private val _events = MutableSharedFlow<VitureEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** One-shot device events. See [VitureEvent]. */
    val events: SharedFlow<VitureEvent> = _events.asSharedFlow()

    /** Whether a Samsung DeX session is currently active. */
    val isDexActive: Boolean
        get() = displayWatcher.isDexActive

    @Volatile
    private var desiredImuEnabled: Boolean = config.autoEnableImu

    @Volatile
    private var currentFrequency: ImuFrequency = config.imuFrequency

    @Volatile
    private var lifecyclePaused: Boolean = false

    @Volatile
    private var closed: Boolean = false

    private var boundLifecycle: Lifecycle? = null

    private val backendListener = object : VitureGlassesListener {
        override fun onImuPayload(timestampNanos: Long, payload: ByteArray) {
            val reading = parser.parse(timestampNanos, payload) ?: return
            _latestImu.value = reading
            _imu.tryEmit(reading)
        }

        override fun onDeviceEvent(event: GlassesEvent) = handleDeviceEvent(event)
    }

    // region Public control API

    /**
     * Start connecting to the glasses.
     *
     * Idempotent and non-blocking — observe [connectionState] for progress. Initialises the
     * backend and begins USB observation; the permission dialog (if any) is handled without
     * any code from the caller.
     */
    fun connect() {
        if (closed) return
        scope.launch {
            val state = _connectionState.value
            if (state == ConnectionState.CONNECTING || state == ConnectionState.CONNECTED) return@launch

            desiredImuEnabled = config.autoEnableImu
            displayWatcher.start(::onDisplaysChanged)
            _connectionState.value = ConnectionState.CONNECTING
            glasses.setListener(backendListener)

            val status = glasses.init()
            if (status != VitureGlasses.STATUS_OK) {
                _connectionState.value = ConnectionState.ERROR
                _events.tryEmit(VitureEvent.Error("init() failed", status))
            }
        }
    }

    /** Disconnect and release the device. The session may be re-[connect]ed afterwards. */
    fun disconnect() {
        if (closed) return
        scope.launch {
            runCatching { glasses.setImuEnabled(false) }
            runCatching { glasses.release() }
            glasses.setListener(null)
            _imuEnabled.value = false
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * Request that IMU streaming be enabled or disabled.
     *
     * The request is remembered: if the session is lifecycle-bound and currently stopped,
     * streaming resumes automatically on the next `onStart`. Observe [imuEnabled] for the
     * effective state.
     */
    fun setImuEnabled(enabled: Boolean) {
        desiredImuEnabled = enabled
        applyImuState()
    }

    /** Change the IMU sample rate. Takes effect immediately when connected. */
    fun setImuFrequency(frequency: ImuFrequency) {
        currentFrequency = frequency
        if (closed) return
        scope.launch { glasses.setImuFrequency(frequency) }
    }

    /**
     * Switch the glasses display mode. Use [DisplayMode.MODE_3D] before stereo rendering.
     * Observe [displayMode] for confirmation.
     */
    fun setDisplayMode(mode: DisplayMode) {
        if (closed) return
        scope.launch { glasses.setDisplayMode(mode) }
    }

    // endregion

    // region Lifecycle

    /**
     * Bind this session to a [LifecycleOwner] (an `Activity` or `Fragment`).
     *
     * While bound: IMU streaming pauses on `onStop` and resumes on `onStart` (when
     * [VitureConfig.pauseImuWhenStopped] is set), and the session is [close]d on `onDestroy`.
     *
     * @return this session, for call chaining.
     */
    fun bindToLifecycle(owner: LifecycleOwner): VitureSession {
        boundLifecycle?.removeObserver(this)
        boundLifecycle = owner.lifecycle
        owner.lifecycle.addObserver(this)
        return this
    }

    override fun onStart(owner: LifecycleOwner) {
        lifecyclePaused = false
        applyImuState()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (config.pauseImuWhenStopped) {
            lifecyclePaused = true
            applyImuState()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        close()
    }

    /**
     * Permanently shut the session down: release the device, stop the worker thread, and
     * cancel all internal coroutines. Called automatically on `onDestroy` for a bound
     * session. Must be called on the main thread.
     */
    override fun close() {
        if (closed) return
        closed = true
        boundLifecycle?.removeObserver(this)
        boundLifecycle = null
        displayWatcher.stop()
        scope.cancel()
        // Final teardown is submitted straight to the executor so it survives scope cancel.
        try {
            sdkExecutor.execute {
                runCatching { glasses.setImuEnabled(false) }
                runCatching { glasses.release() }
                runCatching { glasses.setListener(null) }
            }
        } catch (_: RejectedExecutionException) {
            // Already shut down.
        }
        sdkExecutor.shutdown()
        _connectionState.value = ConnectionState.DISCONNECTED
        _imuEnabled.value = false
    }

    // endregion

    // region Internals

    private fun handleDeviceEvent(event: GlassesEvent) {
        when (event) {
            GlassesEvent.Attached ->
                _connectionState.value = ConnectionState.CONNECTING

            GlassesEvent.PermissionRequired ->
                _connectionState.value = ConnectionState.PERMISSION_REQUIRED

            GlassesEvent.PermissionGranted ->
                onConnected()

            GlassesEvent.PermissionDenied -> {
                _connectionState.value = ConnectionState.ERROR
                _events.tryEmit(VitureEvent.Error("USB permission denied", VitureGlasses.STATUS_OK))
            }

            GlassesEvent.Detached -> {
                _connectionState.value = ConnectionState.DISCONNECTED
                _imuEnabled.value = false
                _events.tryEmit(VitureEvent.Disconnected)
            }

            is GlassesEvent.DisplayModeChanged -> {
                _displayMode.value = event.mode
                _events.tryEmit(VitureEvent.DisplayModeChanged(event.mode))
            }

            is GlassesEvent.ImuStateChanged -> {
                _imuEnabled.value = event.enabled
                _events.tryEmit(VitureEvent.ImuStateChanged(event.enabled))
            }

            is GlassesEvent.Error ->
                _events.tryEmit(VitureEvent.Error(event.message, event.code))
        }
    }

    private fun onConnected() {
        _connectionState.value = ConnectionState.CONNECTED
        _events.tryEmit(VitureEvent.Connected)
        applyImuState()
    }

    /** Reconcile the backend IMU state with what the user asked for and the lifecycle. */
    private fun applyImuState() {
        if (closed) return
        scope.launch {
            if (_connectionState.value != ConnectionState.CONNECTED) return@launch
            val shouldStream = desiredImuEnabled && !lifecyclePaused
            glasses.setImuFrequency(currentFrequency)
            glasses.setImuEnabled(shouldStream)
        }
    }

    private fun onDisplaysChanged() {
        _events.tryEmit(VitureEvent.DisplaysChanged(displayWatcher.isDexActive))
    }

    // endregion

    companion object {
        /**
         * Create a session over the given [glasses] backend.
         *
         * @param context any `Context`; only the application context is retained.
         * @param glasses the backend — [com.viturekit.stub.StubVitureGlasses] for development,
         *   or a real-SDK adapter for production.
         * @param config tunables; the defaults are reasonable for most apps.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            context: Context,
            glasses: VitureGlasses,
            config: VitureConfig = VitureConfig(),
        ): VitureSession = VitureSession(
            glasses = glasses,
            config = config,
            displayWatcher = AndroidDisplayWatcher(context.applicationContext),
        )
    }
}
