package com.viturekit.demo

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.viturekit.VitureSession
import com.viturekit.demo.databinding.ActivityMainBinding
import com.viturekit.model.ConnectionState
import com.viturekit.model.DisplayMode
import com.viturekit.model.ImuReading
import com.viturekit.model.VitureEvent
import com.viturekit.stub.StubVitureGlasses
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * VitureKit reference app: a head-tracked cursor.
 *
 * It creates a [VitureSession], binds it to this activity's lifecycle, and collects the IMU
 * `Flow` to steer an on-screen cursor — demonstrating the whole library surface in one
 * screen. The session uses [StubVitureGlasses], so the demo runs on any device (and inside
 * a Samsung DeX session) without VITURE hardware or the official SDK.
 */
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var session: VitureSession

    // Rolling estimate of the IMU callback rate, for the on-screen metrics readout.
    private var lastImuNanos = 0L
    private var rateHz = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Swap StubVitureGlasses() for a real-SDK adapter to drive physical glasses.
        session = VitureSession.create(this, StubVitureGlasses()).bindToLifecycle(this)

        binding.hintText.setText(R.string.hint)
        binding.cursorView.onTargetActivated = { activated, total -> renderScore(activated, total) }
        renderScore(0, binding.cursorView.targetCount)

        binding.connectButton.setOnClickListener { toggleConnection() }
        binding.modeButton.setOnClickListener { toggleDisplayMode() }
        binding.resetButton.setOnClickListener {
            binding.cursorView.resetTargets()
            renderScore(0, binding.cursorView.targetCount)
        }

        observeSession()
        session.connect()
    }

    /** Collect every session Flow, scoped so collection only runs while the UI is visible. */
    private fun observeSession() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { session.connectionState.collect(::renderConnection) }
                launch { session.displayMode.collect(::renderDisplayMode) }
                launch { session.imu.collect(::renderImu) }
                launch { session.events.collect(::renderEvent) }
            }
        }
    }

    private fun toggleConnection() {
        when (session.connectionState.value) {
            ConnectionState.CONNECTED, ConnectionState.CONNECTING -> session.disconnect()
            else -> session.connect()
        }
    }

    private fun toggleDisplayMode() {
        val next = if (session.displayMode.value == DisplayMode.MODE_3D) {
            DisplayMode.MODE_2D
        } else {
            DisplayMode.MODE_3D
        }
        session.setDisplayMode(next)
    }

    private fun renderConnection(state: ConnectionState) {
        binding.statusText.text = when (state) {
            ConnectionState.DISCONNECTED -> "● Disconnected"
            ConnectionState.PERMISSION_REQUIRED -> "● Waiting for USB permission"
            ConnectionState.CONNECTING -> "● Connecting…"
            ConnectionState.CONNECTED -> "● Connected"
            ConnectionState.ERROR -> "● Connection error"
        }
        val connecting = state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING
        binding.connectButton.setText(if (connecting) R.string.disconnect else R.string.connect)

        if (state != ConnectionState.CONNECTED) {
            binding.cursorView.clearCursor()
            rateHz = 0.0
            lastImuNanos = 0L
            binding.metricsText.text = ""
        }
    }

    private fun renderDisplayMode(mode: DisplayMode) {
        val name = if (mode == DisplayMode.MODE_3D) "3D" else "2D"
        binding.modeButton.text = getString(R.string.mode_button, name)
    }

    private fun renderImu(reading: ImuReading) {
        if (lastImuNanos != 0L) {
            val deltaNanos = reading.timestampNanos - lastImuNanos
            if (deltaNanos > 0L) {
                val instantHz = 1_000_000_000.0 / deltaNanos
                rateHz = if (rateHz == 0.0) instantHz else rateHz * 0.9 + instantHz * 0.1
            }
        }
        lastImuNanos = reading.timestampNanos

        binding.cursorView.updateOrientation(reading.euler)
        binding.metricsText.text = String.format(
            Locale.US,
            "%4.0f Hz   yaw %+6.1f°   pitch %+6.1f°   roll %+6.1f°",
            rateHz,
            reading.euler.yawDeg,
            reading.euler.pitchDeg,
            reading.euler.rollDeg,
        )
    }

    private fun renderEvent(event: VitureEvent) {
        when (event) {
            is VitureEvent.DisplaysChanged ->
                toast(if (event.dexActive) "Entered Samsung DeX" else "Left Samsung DeX")
            is VitureEvent.Error ->
                toast("VITURE error: ${event.message}")
            else -> Unit
        }
    }

    private fun renderScore(activated: Int, total: Int) {
        binding.scoreText.text = getString(R.string.score, activated, total)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
