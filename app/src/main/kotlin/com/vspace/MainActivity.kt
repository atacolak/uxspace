package com.vspace

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.vspace.databinding.ActivityMainBinding
import com.vspace.glasses.GlassesDisplay
import com.vspace.shizuku.ShizukuManager
import com.vspace.shizuku.ShizukuManager.State
import com.vspace.spatial.WorkspaceController
import com.vspace.spatial.WorkspacePresentation
import com.vspace.spatial.WorkspaceRenderer

/**
 * The phone-side control panel — VSpace's input device.
 *
 * It is a toolbar (view mode, capture, screen layout, keyboard) over a touchpad; the system
 * keyboard rises on demand. Apps are launched from the in-glasses app drawer, so the phone
 * shows no app list — the glasses show the workspace, the phone drives it. The Shizuku setup
 * banner sits on top until Shizuku is ready.
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

    private val shizukuListener: () -> Unit = { runOnUiThread { renderStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.shizukuButton.setOnClickListener { onShizukuAction() }
        binding.viewModeButton.setOnClickListener { toggleViewMode() }
        binding.captureButton.setOnClickListener { onCapture() }
        binding.layoutButton.setOnClickListener {
            Toast.makeText(this, "Screen layouts are coming soon", Toast.LENGTH_SHORT).show()
        }
        binding.keyboardButton.setOnClickListener { toggleKeyboard() }
        renderViewModeButton()

        binding.trackpad.onMove = { dx, dy -> WorkspaceController.moveCursor(dx, dy) }
        binding.trackpad.onTap = { WorkspaceController.click() }
        binding.trackpad.onScroll = { dy -> WorkspaceController.scroll(dy) }

        // Keep the panel resumed during a session, so re-showing the workspace after a
        // glasses blip happens from a live window.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ShizukuManager.addListener(shizukuListener)
        // Watch for the glasses the whole time the panel exists — not just while resumed.
        displayManager().registerDisplayListener(displayListener, mainHandler)
    }

    /**
     * Delivered when the glasses are connected while VSpace is already running. Receiving the
     * attach intent also grants USB access, so restart the workspace.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            presentation?.dismiss()
            presentation = null
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
        ShizukuManager.refresh()
        syncGlasses()
    }

    override fun onDestroy() {
        displayManager().unregisterDisplayListener(displayListener)
        presentation?.dismiss()
        presentation = null
        ShizukuManager.removeListener(shizukuListener)
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

    /** Banner: Shizuku setup until that is done, then the glasses-connection state. */
    private fun renderStatus() {
        when (ShizukuManager.state) {
            State.NOT_INSTALLED ->
                shizukuBanner(R.string.shizuku_not_installed, R.string.shizuku_action_install)
            State.NOT_RUNNING ->
                shizukuBanner(R.string.shizuku_not_running, R.string.shizuku_action_open)
            State.NEEDS_PERMISSION ->
                shizukuBanner(R.string.shizuku_needs_permission, R.string.shizuku_action_grant)
            State.CONNECTING ->
                shizukuBanner(R.string.shizuku_connecting, null)
            State.READY -> {
                binding.shizukuButton.visibility = View.GONE
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

    private fun shizukuBanner(status: Int, action: Int?) {
        binding.statusText.setText(status)
        if (action == null) {
            binding.shizukuButton.visibility = View.GONE
        } else {
            binding.shizukuButton.visibility = View.VISIBLE
            binding.shizukuButton.setText(action)
        }
    }

    /** Send the user to whatever Shizuku step is currently outstanding. */
    private fun onShizukuAction() {
        when (ShizukuManager.state) {
            State.NOT_INSTALLED -> startActivity(ShizukuManager.downloadIntent())
            State.NOT_RUNNING ->
                ShizukuManager.openShizukuIntent(this)?.let { startActivity(it) }
            State.NEEDS_PERMISSION -> ShizukuManager.requestPermission()
            else -> Unit
        }
    }
}
