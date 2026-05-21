package com.viturekit.sample.stereo

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.viturekit.VitureSession
import com.viturekit.model.ConnectionState
import com.viturekit.model.DisplayMode
import com.viturekit.sample.stereo.databinding.ActivityStereoBinding
import com.viturekit.stub.StubVitureGlasses
import kotlinx.coroutines.launch

/**
 * VitureKit stereo sample: a head-tracked, side-by-side 3D scene.
 *
 * It creates a [VitureSession], requests [DisplayMode.MODE_3D] once connected, and pipes the
 * IMU `Flow` into a [StereoRenderer]. As with the cursor demo it runs on [StubVitureGlasses],
 * so it works on any device — connect real glasses by swapping in a real-SDK backend.
 */
class StereoActivity : ComponentActivity() {

    private lateinit var binding: ActivityStereoBinding
    private lateinit var session: VitureSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStereoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        session = VitureSession.create(this, StubVitureGlasses()).bindToLifecycle(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    session.imu.collect { reading ->
                        binding.stereoView.stereoRenderer.onHeadOrientation(reading.euler)
                    }
                }
                launch {
                    session.connectionState.collect(::renderStatus)
                }
            }
        }

        session.connect()
    }

    private fun renderStatus(state: ConnectionState) {
        if (state == ConnectionState.CONNECTED) {
            // A per-eye renderer needs the glasses in side-by-side 3D mode.
            session.setDisplayMode(DisplayMode.MODE_3D)
        }
        val connection = when (state) {
            ConnectionState.CONNECTED -> "Connected"
            ConnectionState.CONNECTING -> "Connecting"
            ConnectionState.PERMISSION_REQUIRED -> "USB permission"
            ConnectionState.DISCONNECTED -> "Disconnected"
            ConnectionState.ERROR -> "Error"
        }
        binding.statusText.text = getString(R.string.status_format, connection, "SBS 3D")
    }

    override fun onResume() {
        super.onResume()
        binding.stereoView.onResume()
    }

    override fun onPause() {
        binding.stereoView.onPause()
        super.onPause()
    }
}
