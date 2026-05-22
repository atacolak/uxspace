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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.vspace.apps.AppListAdapter
import com.vspace.apps.InstalledApp
import com.vspace.apps.InstalledApps
import com.vspace.databinding.ActivityMainBinding
import com.vspace.glasses.GlassesDisplay
import com.vspace.shizuku.ShizukuManager
import com.vspace.shizuku.ShizukuManager.State
import com.vspace.spatial.WorkspaceController
import com.vspace.spatial.WorkspacePresentation
import com.vspace.spatial.WorkspaceRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone-side control panel. It walks the user through Shizuku setup, shows the workspace
 * on the glasses (as a `Presentation`) whenever they are connected, and lists installed apps
 * — tapping one places it on a virtual screen inside the workspace.
 *
 * The phone keeps showing this panel; the glasses show the workspace. Two screens, no DeX.
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
        renderViewModeButton()
        binding.captureButton.setOnClickListener {
            if (WorkspaceController.isRunning) {
                WorkspaceController.capture()
            } else {
                Toast.makeText(this, R.string.status_no_glasses, Toast.LENGTH_SHORT).show()
            }
        }
        binding.trackpad.onMove = { dx, dy -> WorkspaceController.moveCursor(dx, dy) }
        binding.trackpad.onTap = { WorkspaceController.click() }
        binding.trackpad.onScroll = { dy -> WorkspaceController.scroll(dy) }
        // Keep the panel resumed during a session, so re-showing the workspace after a
        // glasses blip happens from a live window.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadApps()
        ShizukuManager.addListener(shizukuListener)
        // Watch for the glasses the whole time the panel exists — not just while it is
        // resumed. The display can blip (USB-C), and the workspace must return on its own.
        displayManager().registerDisplayListener(displayListener, mainHandler)
    }

    /**
     * Delivered when the glasses are connected while VSpace is already running. Receiving the
     * attach intent also grants USB access to the glasses, so restart the workspace — letting
     * head tracking open the IMU connection it was previously denied.
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
        binding.viewModeButton.text = when (WorkspaceController.currentViewMode) {
            WorkspaceRenderer.ViewMode.PINNED -> "View: Pinned — tap for Free"
            WorkspaceRenderer.ViewMode.FREE -> "View: Free — tap for Pinned"
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

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.Default) {
                InstalledApps.query(this@MainActivity)
            }
            binding.appList.adapter = AppListAdapter(this@MainActivity, apps)
            binding.appList.setOnItemClickListener { parent, _, position, _ ->
                openInWorkspace(parent.getItemAtPosition(position) as InstalledApp)
            }
        }
    }

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

    /** Place [app] on a virtual screen in the workspace running on the glasses. */
    private fun openInWorkspace(app: InstalledApp) {
        if (ShizukuManager.state != State.READY) {
            Toast.makeText(this, R.string.shizuku_needed, Toast.LENGTH_SHORT).show()
            return
        }
        if (!WorkspaceController.launchApp(app.packageName, app.activityName)) {
            Toast.makeText(this, R.string.status_no_glasses, Toast.LENGTH_SHORT).show()
        }
    }
}
