package com.vspace

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.Build
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.vspace.databinding.ActivityMainBinding
import com.vspace.glasses.GlassesDisplay
import com.vspace.privileged.PairingNotifier
import com.vspace.privileged.PrivilegedService
import com.vspace.privileged.PrivilegedService.State
import com.vspace.spatial.WorkspaceController
import com.vspace.spatial.WorkspacePresentation
import com.vspace.spatial.WorkspaceRenderer

/**
 * The phone-side control panel — VSpace's input device.
 *
 * One activity, three scenes swapped by state:
 *
 *  - **Wizard** (`PrivilegedService.state != READY`) walks the user through wireless-debugging
 *    activation: turn on Wireless Debugging, then enter the 6-digit pairing code. The splash
 *    image sits at the top as a hero banner.
 *  - **Waiting** (paired but no glasses) — centered "Connect your VITURE glasses".
 *  - **Main** (paired + glasses connected) — the existing toolbar (view mode, capture, screen
 *    layout, screen size, keyboard) over the trackpad. The phone shows no app list — the
 *    glasses do, and the phone drives them.
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

    /**
     * Result-launcher for POST_NOTIFICATIONS (API 33+). The notification path is the
     * primary pairing UX; if the user denies, the form below the wizard still works as a
     * fallback so the activity ignores the result.
     */
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* ignored — the form fallback works without notifications */ }

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
        requestNotificationPermissionIfNeeded()
        renderStatus()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission.launch(perm)
        }
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
            Toast.makeText(this, R.string.waiting_for_glasses, Toast.LENGTH_SHORT).show()
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

    /** Show the workspace on the glasses while they are connected; refresh the active scene. */
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
     * Pick which of the three scenes is visible from [PrivilegedService.state] and whether the
     * glasses are connected, and populate the wizard with its current step.
     */
    private fun renderStatus() {
        val glassesShowing = presentation?.isShowing == true
        when {
            PrivilegedService.state != State.READY -> showWizard()
            !glassesShowing -> showScene(showWizard = false, showWaiting = true)
            else -> showScene(showWizard = false, showWaiting = false)
        }
        // The RemoteInput pairing notification is the primary path — post it when the user
        // is at the pair step, take it down otherwise.
        if (PrivilegedService.state == State.NEEDS_PAIRING) {
            PairingNotifier.showPairingPrompt(this)
        } else {
            PairingNotifier.cancel(this)
        }
    }

    private fun showWizard() {
        showScene(showWizard = true, showWaiting = false)
        when (PrivilegedService.state) {
            State.UNSUPPORTED -> populateWizard(
                R.string.privilege_title_unsupported,
                R.string.privilege_msg_unsupported,
                actionLabel = null,
            )
            State.NEEDS_DEVELOPER_OPTIONS -> populateWizard(
                R.string.privilege_title_dev_options,
                R.string.privilege_msg_dev_options,
                actionLabel = R.string.privilege_action_open_about,
            )
            State.NEEDS_WIRELESS_DEBUGGING -> populateWizard(
                R.string.privilege_title_wireless_debugging,
                R.string.privilege_msg_wireless_debugging,
                actionLabel = R.string.privilege_action_open_developer_settings,
            )
            State.NEEDS_PAIRING -> populateWizard(
                R.string.privilege_title_pair,
                R.string.privilege_msg_pair,
                actionLabel = R.string.privilege_action_open_developer_settings,
            )
            State.DISCOVERING -> populateWizard(
                R.string.privilege_title_working,
                R.string.privilege_msg_discovering,
                actionLabel = null,
            )
            State.CONNECTING -> populateWizard(
                R.string.privilege_title_working,
                R.string.privilege_msg_connecting,
                actionLabel = null,
            )
            State.STARTING -> populateWizard(
                R.string.privilege_title_working,
                R.string.privilege_msg_starting,
                actionLabel = null,
            )
            State.READY -> Unit // showWizard would not have been called
        }
    }

    private fun showScene(showWizard: Boolean, showWaiting: Boolean) {
        binding.wizardScene.visibility = if (showWizard) View.VISIBLE else View.GONE
        binding.waitingScene.visibility = if (showWaiting) View.VISIBLE else View.GONE
        binding.mainScene.visibility =
            if (!showWizard && !showWaiting) View.VISIBLE else View.GONE
    }

    private fun populateWizard(title: Int, message: Int, actionLabel: Int?) {
        binding.wizardTitle.setText(title)
        binding.wizardMessage.setText(message)
        if (actionLabel == null) {
            binding.setupButton.visibility = View.GONE
        } else {
            binding.setupButton.visibility = View.VISIBLE
            binding.setupButton.setText(actionLabel)
            binding.setupButton.isEnabled = true
        }
    }

    /**
     * The wizard button: each step sends the user to wherever the next action lives in
     * Android Settings. Actual pairing is done via the notification posted while the
     * NEEDS_PAIRING state is active — see [PairingNotifier].
     */
    private fun onSetupAction() {
        when (PrivilegedService.state) {
            State.NEEDS_DEVELOPER_OPTIONS -> openAboutPhone()
            State.NEEDS_WIRELESS_DEBUGGING, State.NEEDS_PAIRING -> openDeveloperSettings()
            else -> Unit
        }
    }

    private fun openAboutPhone() {
        runCatching { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun openDeveloperSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }
}
