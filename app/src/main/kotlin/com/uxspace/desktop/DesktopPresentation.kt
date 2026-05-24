package com.uxspace.desktop

import android.app.Presentation
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import com.uxspace.R
import com.uxspace.spatial.UxSpaceTheme
import com.uxspace.spatial.WorkspaceController
import com.uxspace.system.SystemStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The desktop shown on the workspace's back plane — UxSpace's DeX-style home: a wallpaper and
 * a bottom taskbar with an app-drawer launcher, an icon per open app window, and a clock.
 *
 * It is a real Android view hierarchy on `Theme.DeviceDefault`, so on a Samsung device the
 * widgets are styled as One UI. The app drawer is a separate overlay ([DrawerPresentation])
 * so it can float in front of the app windows; the launcher button just toggles it.
 */
class DesktopPresentation(
    outerContext: Context,
    display: Display,
    /**
     * Index of the preset slot this Presentation belongs to (0..N-1). Used to filter
     * [WorkspaceController.onAppLaunched] events so each slot's taskbar shows only the
     * apps actually launched onto that slot.
     */
    private val slotIdx: Int = 0,
    /**
     * Whether to render the taskbar / status row at the bottom of this slot. The V slots
     * of the V/H/V preset turn this off so the H slot owns the taskbar; SBS / Single
     * presets default to true.
     */
    private val showTaskbar: Boolean = true,
) : Presentation(outerContext, display, android.R.style.Theme_DeviceDefault_NoActionBar) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var runningApps: LinearLayout
    private lateinit var batteryText: TextView
    private lateinit var volumeText: TextView

    /** Taskbar icons for the open app windows, keyed by package, in launch order. */
    private val runningIcons = LinkedHashMap<String, View>()

    private val systemStatusListener = object : SystemStatus.Listener {
        override fun onSystemStatusChanged() {
            mainHandler.post { refreshStatusTray() }
        }
    }

    /** Refreshes the taskbar clock; re-posts itself while the desktop is shown. */
    private val clockTick = object : Runnable {
        override fun run() {
            if (::clock.isInitialized) clock.text = clockText()
            mainHandler.postDelayed(this, CLOCK_INTERVAL_MS)
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private val appLaunchedListener: (String, String, Int) -> Unit =
        { packageName, label, launchSlot ->
            if (launchSlot == slotIdx) {
                mainHandler.post { addRunningApp(packageName, label) }
            }
        }

    private val appClosedListener: (String) -> Unit = { packageName ->
        mainHandler.post { removeRunningApp(packageName) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context)
        root.addView(buildWallpaper())
        if (showTaskbar) root.addView(buildTaskbar())
        root.addView(buildVersionLabel())
        setContentView(root)
        // Per-slot taskbar — only react to launches on this slot.
        WorkspaceController.addAppLaunchedListener(appLaunchedListener)
        WorkspaceController.addAppClosedListener(appClosedListener)
    }

    override fun onDetachedFromWindow() {
        WorkspaceController.removeAppLaunchedListener(appLaunchedListener)
        WorkspaceController.removeAppClosedListener(appClosedListener)
        super.onDetachedFromWindow()
    }

    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(clockTick)
        clockTick.run()
        SystemStatus.addListener(systemStatusListener)
        refreshStatusTray()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(clockTick)
        SystemStatus.removeListener(systemStatusListener)
        super.onStop()
    }

    /** Re-render the status tray from the latest [SystemStatus] snapshot. */
    private fun refreshStatusTray() {
        if (::batteryText.isInitialized) {
            val charging = if (SystemStatus.batteryCharging) "⚡ " else ""
            batteryText.text = "$charging${SystemStatus.batteryPercent}%"
        }
        if (::volumeText.isInitialized) {
            volumeText.text = "${(SystemStatus.volumeFraction * 100).toInt()}%"
        }
    }

    private fun buildWallpaper(): View = ImageView(context).apply {
        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        scaleType = ImageView.ScaleType.CENTER_CROP
        val bitmap = runCatching {
            context.assets.open(WALLPAPER_ASSET).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (bitmap != null) setImageBitmap(bitmap) else setBackgroundColor(VOID_COLOR)
    }

    /**
     * A faint build stamp in the workspace's top-left corner. The time is the APK's install
     * time, so a capture can be confirmed to come from the latest build.
     */
    private fun buildVersionLabel(): View = TextView(context).apply {
        text = versionStamp()
        setTextColor(0x73FFFFFF)
        textSize = 11f
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
    }

    private fun versionStamp(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val time = SimpleDateFormat("MMM d  HH:mm:ss", Locale.getDefault())
            .format(Date(info.lastUpdateTime))
        "UxSpace · build $time"
    }.getOrDefault("UxSpace")

    /**
     * The three-cluster DeX-style bar (see docs/TASKBAR.md). Left and right clusters are
     * pinned to their edges; the running-app strip in the middle is centred between them by
     * weighted spacers.
     */
    private fun buildTaskbar(): View {
        runningApps = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(buildLeftCluster(), LinearLayout.LayoutParams(WRAP, MATCH))
            addView(View(context), LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(runningApps, LinearLayout.LayoutParams(WRAP, MATCH))
            addView(View(context), LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(buildRightCluster(), LinearLayout.LayoutParams(WRAP, MATCH))
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setBackgroundColor(UxSpaceTheme.taskbar)
            addView(row, LinearLayout.LayoutParams(MATCH, MATCH))
        }
        return FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            addView(bar, FrameLayout.LayoutParams(MATCH, dp(58)).apply { gravity = Gravity.BOTTOM })
        }
    }

    /**
     * Left cluster — DeX-style launch shelf: drawer, divider, then Recent apps, Show
     * desktop, Optometry, Search. See docs/TASKBAR.md for what each does.
     */
    private fun buildLeftCluster(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            taskbarButton(R.drawable.ic_apps, "App drawer") {
                WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.ALL)
                WorkspaceController.setDrawerOpen(!WorkspaceController.isDrawerOpen)
            },
        )
        addView(buildDivider())
        addView(
            taskbarButton(R.drawable.ic_recent, "Recent apps") {
                WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.RECENT)
                WorkspaceController.setDrawerOpen(true)
            },
        )
        addView(
            taskbarButton(R.drawable.ic_show_desktop, "Show desktop") {
                WorkspaceController.toggleShowDesktop()
            },
        )
        addView(
            taskbarButton(R.drawable.ic_eye, "Optometry chart") {
                Toast.makeText(context, "Optometry chart — coming soon", Toast.LENGTH_SHORT).show()
            },
        )
        addView(
            taskbarButton(R.drawable.ic_search, "Search") {
                // Search reaches all installed apps — not just the recent subset.
                WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.ALL)
                WorkspaceController.setDrawerOpen(true)
            },
        )
    }

    /**
     * Right cluster — DeX-style status / configuration tray: volume, battery, clock for
     * now. Wi-Fi, signal, message indicator and the click-to-open quick-settings panel
     * come in follow-up commits (see docs/TASKBAR.md).
     */
    private fun buildRightCluster(): View {
        volumeText = statusValue()
        batteryText = statusValue()
        clock = TextView(context).apply {
            setTextColor(UxSpaceTheme.taskbarText)
            textSize = 12.5f
            gravity = Gravity.END
            setLineSpacing(0f, 0.95f)
            text = clockText()
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusItem(R.drawable.ic_volume, volumeText, "Volume"))
            addView(statusItem(R.drawable.ic_battery, batteryText, "Battery"))
            addView(
                clock,
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) },
            )
        }
    }

    /** Right-tray cell: a small icon next to a tiny percentage label. */
    private fun statusItem(
        @DrawableRes icon: Int,
        valueLabel: TextView,
        description: String,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(12) }
        contentDescription = description
        addView(
            ImageView(context).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
        )
        addView(
            valueLabel,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(4) },
        )
    }

    private fun statusValue(): TextView = TextView(context).apply {
        setTextColor(UxSpaceTheme.taskbarText)
        textSize = 11f
    }

    private fun taskbarButton(
        @DrawableRes icon: Int,
        description: String,
        onClick: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        setImageResource(icon)
        background = null
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }
        setPadding(dp(8), dp(8), dp(8), dp(8))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setOnClickListener { onClick() }
    }

    /** 1dp vertical line between the drawer button and the rest of the left cluster. */
    private fun buildDivider(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(32)).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        }
        setBackgroundColor(DIVIDER_COLOR)
    }

    /**
     * Add the launched app to the taskbar — its icon raises (and un-minimises) the window on
     * a tap, and restores a maximised window to its frame on a double tap. Closing is done
     * from the window's own title bar. A no-op if the app already has an icon.
     */
    private fun addRunningApp(packageName: String, label: String) {
        if (!::runningApps.isInitialized || runningIcons.containsKey(packageName)) return
        val icon = ImageView(context)
        icon.setImageDrawable(appIcon(packageName))
        icon.contentDescription = label
        val gestures = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    WorkspaceController.focusApp(packageName)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Double-tap = close. (Old single-display model used this to un-maximise
                    // a maximised window; the slot model has no maximised state.)
                    WorkspaceController.closeAppByPackage(packageName)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    // Cycle the running app to the next slot of the active preset —
                    // poor-man's "drag app to another monitor" until a real drag UX
                    // ships. Repeated long-press walks the app around the slots.
                    WorkspaceController.moveAppToNextScreen(packageName)
                }
            },
        )
        // Always consume, so the icon keeps receiving events after the down — otherwise the
        // gesture detector never sees the up and single/double taps are lost.
        icon.setOnTouchListener { v, e ->
            if (gestures.onTouchEvent(e)) v.performClick()
            true
        }
        runningIcons[packageName] = icon
        runningApps.addView(
            icon,
            LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(12) },
        )
    }

    private fun appIcon(packageName: String): Drawable? = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()

    /** Remove an app's taskbar icon when its window is closed. */
    private fun removeRunningApp(packageName: String) {
        if (!::runningApps.isInitialized) return
        runningIcons.remove(packageName)?.let { runningApps.removeView(it) }
    }

    private fun clockText(): String =
        SimpleDateFormat("h:mm a\nEEE, MMM d", Locale.getDefault()).format(Date())

    private companion object {
        const val WALLPAPER_ASSET = "workspace_background.jpg"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val VOID_COLOR = 0xFF0E1018.toInt()
        const val CLOCK_INTERVAL_MS = 20_000L

        /** Translucent white for the left-cluster divider — a quarter-strength rule line. */
        const val DIVIDER_COLOR = 0x40FFFFFF
    }
}
