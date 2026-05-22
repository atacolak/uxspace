package com.vspace

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.vspace.databinding.ActivityMainBinding
import com.vspace.glasses.GlassesDisplay
import com.vspace.privileged.PrivilegedService
import com.vspace.privileged.PrivilegedService.State
import com.vspace.spatial.WorkspaceController
import com.vspace.spatial.WorkspacePresentation
import com.vspace.spatial.WorkspaceRenderer

/**
 * The phone-side control panel — VSpace's input device.
 *
 * It is a toolbar (view mode, capture, screen layout, keyboard) over a touchpad; the system
 * keyboard rises on demand. Apps are launched from the in-glasses app drawer, so the phone
 * shows no app list — the glasses show the workspace, the phone drives it.
 *
 * A setup card sits on top until the [PrivilegedService] is `READY`: it explains where the
 * user is in the wireless-debugging activation flow and offers the next action (open
 * Developer settings, or enter the 6-digit pairing code).
 */
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    /** The workspace shown on the glasses, while they are connected. */
    private var presentation: WorkspacePresentation? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Reacts when the glasses are plugged in or out while the panel is open. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = syncGlasses()
        override fun onDisplayRemoved(displayId: Int) = syncGlasses()
        override fun onDisplayChanged(displayId: Int) = syncGlasses()
    }

    private val privilegeListener: () -> Unit = { runOnUiThread { renderStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.setupButton.setOnClickListener { onSetupAction() }
        binding.viewModeButton.setOnClickListener { toggleViewMode() }
        binding.captureButton.setOnClickListener { onCapture() }
        binding.layoutButton.setOnClickListener {
            Toast.makeText(this, "Screen layouts are coming soon", Toast.LENGTH_SHORT).show()
        }
        binding.screenHeightButton.setOnClickListener {
            val percent = (WorkspaceController.cycleScreenBand() * 100).toInt()
            Toast.makeText(this, "Screen size $percent%", Toast.LENGTH_SHORT).show()
        }
        binding.keyboardButton.setOnClickListener { toggleKeyboard() }
        renderViewModeButton()

        binding.trackpad.onMove = { dx, dy -> WorkspaceController.moveCursor(dx, dy) }
        binding.trackpad.onTap = { WorkspaceController.click() }
        binding.trackpad.onScroll = { dy -> WorkspaceController.scroll(dy) }
        binding.trackpad.onDragStart = { WorkspaceController.beginDrag() }
        binding.trackpad.onDragEnd = { WorkspaceController.endDrag() }

        // Keep the panel resumed during a session, so re-showing the workspace after a
        // glasses blip happens from a live window.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PrivilegedService.addListener(privilegeListener)
        // Watch for the glasses the whole time the panel exists — not just while resumed.
        displayManager().registerDisplayListener(displayListener, mainHandler)
    }

    /**
     * Delivered when the glasses are connected while VSpace is already running. Receiving the
     * attach intent also grants USB access; [syncGlasses] then shows the workspace if it is
     * not already up. It must NOT tear down a running workspace first — doing so spun up a
     * second [WorkspacePresentation], and its [HeadTracking][com.vspace.glasses.HeadTracking]
     * raced the first over the native SDK handle, crashing the process.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            syncGlasses()
        }
    }

    private fun toggleViewMode() {
        val next = when (WorkspaceController.currentViewMode) {
            WorkspaceRenderer.ViewMode.PINNED -> WorkspaceRenderer.ViewMode.FREE
            WorkspaceRenderer.ViewMode.FREE -> WorkspaceRenderer.ViewMode.PINNED
        }
        WorkspaceController.setViewMode(next)
        renderViewModeButton()
    }

    private fun renderViewModeButton() {
        binding.viewModeButton.setImageResource(
            when (WorkspaceController.currentViewMode) {
                WorkspaceRenderer.ViewMode.PINNED -> R.drawable.ic_pin
                WorkspaceRenderer.ViewMode.FREE -> R.drawable.ic_pin_off
            },
        )
    }

    private fun onCapture() {
        if (WorkspaceController.isRunning) {
            WorkspaceController.capture()
        } else {
            Toast.makeText(this, R.string.status_no_glasses, Toast.LENGTH_SHORT).show()
        }
    }

    /** Show or hide the system keyboard. Keystroke routing into the focused app is M4. */
    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val field = binding.keyboardField
        if (field.visibility == View.VISIBLE) {
            imm.hideSoftInputFromWindow(field.windowToken, 0)
            field.visibility = View.GONE
        } else {
            field.visibility = View.VISIBLE
            field.requestFocus()
            imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from Developer settings or the pairing dialog may have changed things —
        // re-attempt bring-up. Idempotent and no-op once READY.
        PrivilegedService.ensureRunning()
        syncGlasses()
    }

    override fun onDestroy() {
        displayManager().unregisterDisplayListener(displayListener)
        presentation?.dismiss()
        presentation = null
        PrivilegedService.removeListener(privilegeListener)
        super.onDestroy()
    }

    private fun displayManager(): DisplayManager =
        getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /** Show the workspace on the glasses while they are connected; refresh the banner. */
    private fun syncGlasses() {
        val display = GlassesDisplay.find(this)
        if (display != null) {
            val current = presentation
            if (current == null || !current.isShowing ||
                current.display.displayId != display.displayId
            ) {
                current?.dismiss()
                presentation = try {
                    WorkspacePresentation(this, display).also { it.show() }
                } catch (e: Exception) {
                    Log.e("VSpace/Main", "could not show workspace on the glasses", e)
                    null
                }
            }
        } else {
            presentation?.dismiss()
            presentation = null
        }
        renderStatus()
    }

    /**
     * Setup card content depending on where we are in the wireless-debugging flow; once
     * `READY`, the card collapses to the plain glasses-connection status.
     */
    private fun renderStatus() {
        when (PrivilegedService.state) {
            State.UNSUPPORTED ->
                setupBanner(R.string.privilege_unsupported, actionLabel = null, codeFieldVisible = false)
            State.NEEDS_WIRELESS_DEBUGGING ->
                setupBanner(
                    R.string.privilege_needs_wireless_debugging,
                    R.string.privilege_action_open_developer_settings,
                    codeFieldVisible = false,
                )
            State.NEEDS_PAIRING ->
                setupBanner(
                    R.string.privilege_needs_pairing,
                    R.string.privilege_action_pair,
                    codeFieldVisible = true,
                )
            State.DISCOVERING ->
                setupBanner(R.string.privilege_discovering, actionLabel = null, codeFieldVisible = false)
            State.CONNECTING ->
                setupBanner(R.string.privilege_connecting, actionLabel = null, codeFieldVisible = false)
            State.STARTING ->
                setupBanner(R.string.privilege_starting, actionLabel = null, codeFieldVisible = false)
            State.READY -> {
                binding.setupButton.visibility = View.GONE
                binding.pairingCode.visibility = View.GONE
                binding.statusText.setText(
                    if (presentation?.isShowing == true) {
                        R.string.status_ready
                    } else {
                        R.string.status_no_glasses
                    },
                )
            }
        }
    }

    private fun setupBanner(status: Int, actionLabel: Int?, codeFieldVisible: Boolean) {
        binding.statusText.setText(status)
        binding.pairingCode.visibility = if (codeFieldVisible) View.VISIBLE else View.GONE
        if (actionLabel == null) {
            binding.setupButton.visibility = View.GONE
        } else {
            binding.setupButton.visibility = View.VISIBLE
            binding.setupButton.setText(actionLabel)
        }
    }

    /** The setup card's button: action depends on the current state. */
    private fun onSetupAction() {
        when (PrivilegedService.state) {
            State.NEEDS_WIRELESS_DEBUGGING -> openDeveloperSettings()
            State.NEEDS_PAIRING -> startPairing()
            else -> Unit
        }
    }

    private fun openDeveloperSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun startPairing() {
        val code = binding.pairingCode.text.toString().trim()
        if (code.length != PAIRING_CODE_LENGTH) {
            Toast.makeText(this, R.string.privilege_pairing_hint, Toast.LENGTH_SHORT).show()
            return
        }
        binding.setupButton.isEnabled = false
        PrivilegedService.activate(code) { ok ->
            runOnUiThread {
                binding.setupButton.isEnabled = true
                if (!ok) {
                    Toast.makeText(
                        this,
                        R.string.privilege_pair_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    binding.pairingCode.text.clear()
                }
            }
        }
    }

    private companion object {
        const val PAIRING_CODE_LENGTH = 6
    }
}
