package com.uxspace

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
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.uxspace.databinding.ActivityMainBinding
import com.uxspace.glasses.GlassesDisplay
import com.uxspace.privileged.PairingNotifier
import com.uxspace.privileged.PrivilegedService
import com.uxspace.privileged.PrivilegedService.State
import com.uxspace.spatial.WorkspaceController
import com.uxspace.spatial.WorkspacePresentation
import com.uxspace.spatial.WorkspaceRenderer

/**
 * The phone-side control panel — UxSpace's input device.
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

    /** Virtual display id whose text field is currently focused, or -1. Set by the
     *  accessibility service's focus events; drives the auto-open of the phone keyboard
     *  and the routing of typed text back via [PrivilegedService.text]. */
    private var focusedAppDisplayId: Int = -1

    /** Last value of [keyboardField] so [forwardKeyboardDelta] can compute insertions
     *  and deletions per text-change event. */
    private var lastKeyboardText: String = ""

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

    /**
     * Result-launcher for RECORD_AUDIO. Granted once at first launch so the OS doesn't
     * re-prompt every time an app launched into the workspace tries to use the mic.
     */
    private val requestMicrophonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* ignored — launched apps will see the system prompt themselves if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.setupButton.setOnClickListener { onSetupAction() }
        binding.viewModeButton.setOnClickListener { toggleViewMode() }
        binding.captureButton.setOnClickListener { onCapture() }
        binding.captureButton.setOnLongClickListener {
            if (WorkspaceController.isRunning) {
                WorkspaceController.capture()
                Toast.makeText(this, "Single snapshot", Toast.LENGTH_SHORT).show()
                true
            } else false
        }
        binding.layoutButton.setOnClickListener {
            if (WorkspaceController.currentViewMode == WorkspaceRenderer.ViewMode.PINNED) {
                Toast.makeText(this, "Unlock view to change layout", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val next = WorkspaceController.cycleLayout()
            Toast.makeText(this, "Layout: ${next.displayName}", Toast.LENGTH_SHORT).show()
        }
        binding.screenHeightButton.setOnClickListener {
            val percent = (WorkspaceController.cycleScreenBand() * 100).toInt()
            Toast.makeText(this, "Zoom $percent%", Toast.LENGTH_SHORT).show()
        }
        binding.alignHorizonButton.setOnClickListener {
            Log.i("UxSpace/Main", "alignHorizon click: running=${WorkspaceController.isRunning}")
            if (WorkspaceController.isRunning) {
                WorkspaceController.alignVerticalToHead()
            } else {
                Toast.makeText(this, "Connect glasses first", Toast.LENGTH_SHORT).show()
            }
        }
        WorkspaceController.addZoomListener(zoomHudListener)
        binding.keyboardButton.setOnClickListener { toggleKeyboard() }
        // Long-press to jump to Accessibility settings — the auto-keyboard feature
        // needs the UxSpaceAccessibilityService toggled on there.
        binding.keyboardButton.setOnLongClickListener {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this, "Enable UxSpace under Accessibility", Toast.LENGTH_LONG,
            ).show()
            true
        }
        renderViewModeButton()

        binding.trackpad.onMove = { dx, dy -> WorkspaceController.moveCursor(dx, dy) }
        binding.trackpad.onTap = { WorkspaceController.click() }
        binding.trackpad.onScroll = { dy -> WorkspaceController.scroll(dy) }
        binding.trackpad.onZoom = { scale -> WorkspaceController.pinch(scale) }
        binding.trackpad.onDragStart = { WorkspaceController.beginDrag() }
        binding.trackpad.onDragEnd = { WorkspaceController.endDrag() }
        binding.trackpad.onDragCancel = { WorkspaceController.cancelDrag() }

        // Forward IME keystrokes into the workspace drawer's search field while the drawer
        // is open, or into a focused text field on a launched app's virtual display
        // (detected by UxSpaceAccessibilityService). The drawer / virtual displays can't
        // host an IME, so the phone-side keyboard field is the only practical input path.
        binding.keyboardField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val current = s?.toString().orEmpty()
                when {
                    WorkspaceController.isDrawerOpen ->
                        WorkspaceController.setDrawerSearchQuery(current)
                    focusedAppDisplayId >= 0 ->
                        forwardKeyboardDelta(focusedAppDisplayId, lastKeyboardText, current)
                }
                lastKeyboardText = current
            }
        })

        // Accessibility service callbacks — opens / closes the phone keyboard when a
        // text field gains / loses focus on one of our virtual displays.
        WorkspaceController.onAppTextFieldFocused = { displayId ->
            mainHandler.post {
                focusedAppDisplayId = displayId
                showKeyboard()
            }
        }
        WorkspaceController.onAppTextFieldUnfocused = { displayId ->
            mainHandler.post {
                if (focusedAppDisplayId == displayId) {
                    focusedAppDisplayId = -1
                    hideKeyboard()
                }
            }
        }

        // Keep the panel resumed during a session, so re-showing the workspace after a
        // glasses blip happens from a live window.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PrivilegedService.addListener(privilegeListener)
        // Watch for the glasses the whole time the panel exists — not just while resumed.
        displayManager().registerDisplayListener(displayListener, mainHandler)
        requestNotificationPermissionIfNeeded()
        requestMicrophonePermissionIfNeeded()
        renderStatus()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission.launch(perm)
        }
    }

    private fun requestMicrophonePermissionIfNeeded() {
        val perm = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermission.launch(perm)
        }
    }

    /**
     * Delivered when the glasses are connected while UxSpace is already running. Receiving the
     * attach intent also grants USB access; [syncGlasses] then shows the workspace if it is
     * not already up. It must NOT tear down a running workspace first — doing so spun up a
     * second [WorkspacePresentation], and its [HeadTracking][com.uxspace.glasses.HeadTracking]
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
        if (!WorkspaceController.isRunning) {
            Toast.makeText(this, R.string.waiting_for_glasses, Toast.LENGTH_SHORT).show()
            return
        }
        // Tap toggles recording — frame sequence into the captures directory at
        // RECORDING_FRAME_INTERVAL (5 fps). A long-press still does a single snapshot
        // when one-shot debugging is enough.
        val recording = WorkspaceController.toggleRecording()
        Toast.makeText(
            this,
            if (recording) "Recording started" else "Recording stopped",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** Show or hide the system keyboard. Keystroke routing into the focused app is M4. */
    private fun toggleKeyboard() {
        if (binding.keyboardField.visibility == View.VISIBLE) hideKeyboard()
        else showKeyboard()
    }

    /** Bring up the phone IME and focus the hidden keyboardField. Idempotent. */
    private fun showKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val field = binding.keyboardField
        if (field.visibility != View.VISIBLE) field.visibility = View.VISIBLE
        // Clear before showing so the delta tracking starts from empty; if the user
        // had been typing a draft we'd otherwise re-send the whole thing as new keys.
        if (field.text.isNotEmpty()) {
            field.text.clear()
            lastKeyboardText = ""
        }
        field.requestFocus()
        imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Hide the phone IME and clear keyboardField state. Idempotent. */
    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val field = binding.keyboardField
        imm.hideSoftInputFromWindow(field.windowToken, 0)
        field.visibility = View.GONE
        field.text.clear()
        lastKeyboardText = ""
    }

    /**
     * Forward the delta between [old] and [new] keyboardField text into the focused
     * app on [displayId] — backspaces for any characters removed, then the new tail
     * via `input text`. Lets a phone-side IME type into an out-of-process app on a
     * virtual display.
     */
    private fun forwardKeyboardDelta(displayId: Int, old: String, new: String) {
        val common = old.commonPrefixWith(new).length
        val toDelete = old.length - common
        val toAdd = new.substring(common)
        repeat(toDelete) {
            PrivilegedService.key(displayId, android.view.KeyEvent.KEYCODE_DEL)
        }
        if (toAdd.isNotEmpty()) {
            PrivilegedService.text(displayId, toAdd)
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
        WorkspaceController.removeZoomListener(zoomHudListener)
        super.onDestroy()
    }

    /** Hide the zoom HUD a short while after the last zoom change. */
    private val hudHideRunnable = Runnable { binding.zoomHud.visibility = View.GONE }

    /** Pops up a "Zoom NN%" HUD on the trackpad whenever the workspace zoom changes. */
    private val zoomHudListener: (Float) -> Unit = { zoom ->
        binding.zoomHud.text = "Zoom ${(zoom * 100).toInt()}%"
        binding.zoomHud.visibility = View.VISIBLE
        binding.zoomHud.removeCallbacks(hudHideRunnable)
        binding.zoomHud.postDelayed(hudHideRunnable, ZOOM_HUD_HIDE_MS)
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
                } catch (e: android.view.WindowManager.BadTokenException) {
                    // Common during a glasses connect/disconnect race — the display
                    // briefly exists but isn't trusted yet; the next syncGlasses retry
                    // succeeds. Don't log a full stack trace as if it were a crash.
                    Log.w("UxSpace/Main", "presentation rejected (display not trusted yet): ${e.message}")
                    null
                } catch (e: Exception) {
                    Log.e("UxSpace/Main", "could not show workspace on the glasses", e)
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
                actionLabel = R.string.privilege_action_open_wireless_debugging,
            )
            State.NEEDS_PAIRING -> populateWizard(
                R.string.privilege_title_pair,
                R.string.privilege_msg_pair,
                actionLabel = R.string.privilege_action_open_wireless_debugging,
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
            State.NEEDS_WIRELESS_DEBUGGING, State.NEEDS_PAIRING -> openWirelessDebugging()
            else -> Unit
        }
    }

    private fun openAboutPhone() {
        startFirstAvailable(
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
    }

    /**
     * Open Developer Options and scroll to / highlight the Wireless Debugging row. The
     * `:settings:fragment_args_key` extra is the documented way to deep-link to a specific
     * preference inside a Settings page; on Samsung One UI it both scrolls there and
     * briefly highlights the row. Falls back to plain Developer Options, then to the top
     * of Settings.
     */
    private fun openWirelessDebugging() {
        val targeted = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(SETTINGS_FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_PREF_KEY)
            putExtra(
                SETTINGS_SHOW_FRAGMENT_ARGS,
                Bundle().apply { putString(SETTINGS_FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_PREF_KEY) },
            )
        }
        startFirstAvailable(
            targeted,
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
    }

    /** Try each intent in order; stop at the first one that launches. */
    private fun startFirstAvailable(vararg intents: Intent) {
        for (intent in intents) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    private companion object {
        /** Settings preference key for the Wireless Debugging row in Developer Options. */
        const val WIRELESS_DEBUGGING_PREF_KEY = "toggle_adb_wireless"

        /** Settings deep-link extras — preserved across most OEM Settings forks. */
        const val SETTINGS_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        const val SETTINGS_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

        /** How long the trackpad's zoom HUD lingers after the last zoom event. */
        const val ZOOM_HUD_HIDE_MS = 1200L
    }
}
