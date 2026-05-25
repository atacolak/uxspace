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

    /**
     * Wallpaper layer — kept as a field so we can hide it while any app is running on
     * this slot. With the translucent Presentation theme, hiding the wallpaper view
     * lets the launched activity (which renders *behind* the Presentation's window in
     * SurfaceFlinger z-order — TYPE_PRIVATE_PRESENTATION sits above TYPE_BASE_APPLICATION)
     * show through. The taskbar stays opaque on top.
     */
    private lateinit var wallpaper: View

    /** Taskbar icons for the open app windows, keyed by package, in launch order. */
    private val runningIcons = LinkedHashMap<String, View>()

    /** Display label per running package — for the window-chrome title text. */
    private val runningLabels = HashMap<String, String>()

    private val chromeListener = object : WindowChromeView.Listener {
        override fun onMinimize(packageName: String) {
            // No "minimised" state in the per-slot model yet; for now close — same
            // behavior as the close button. TODO: actual minimise (hide the activity
            // but keep its task alive, restore from the taskbar icon).
            WorkspaceController.closeAppByPackage(packageName)
        }
        override fun onMaximize(packageName: String) {
            // No "windowed" state to toggle yet — activities currently fill the slot.
            // No-op until freeform launch bounds are wired.
        }
        override fun onClose(packageName: String) {
            WorkspaceController.closeAppByPackage(packageName)
        }
    }

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
                android.util.Log.i(
                    "UxSpace/Launch",
                    "14) DesktopPresentation slot=$slotIdx adding taskbar icon for $packageName",
                )
                mainHandler.post { addRunningApp(packageName, label) }
            }
        }

    private val appClosedListener: (String) -> Unit = { packageName ->
        android.util.Log.i(
            "UxSpace/Launch",
            "X) DesktopPresentation slot=$slotIdx removing taskbar icon for $packageName",
        )
        mainHandler.post { removeRunningApp(packageName) }
    }

    /** Drawer view embedded as a child of the root; visibility driven by the controller. */
    private lateinit var drawer: DrawerView

    /** Settings panel embedded alongside the drawer — same modal model. */
    private lateinit var settings: SettingsView

    /**
     * Window chrome — a thin title bar pinned to the top of the slot, visible only while
     * an app is running on this slot. Carries the foreground app's icon + name plus
     * minimize / maximize / close buttons. The chrome is part of the slot Presentation's
     * view tree, so its button clicks are routed by WorkspaceRenderer via
     * `UiScreen.dispatchTap` (the Presentation is FLAG_NOT_TOUCHABLE for the system
     * dispatcher).
     */
    private lateinit var chrome: WindowChromeView

    /**
     * Transparent click-catcher sized to the whole screen. Shown on *every* slot
     * whenever any modal panel (drawer or settings) is open anywhere, so a click on any
     * monitor's wallpaper — including a different monitor from the one hosting the
     * panel — closes it. Taskbar + drawer + settings sit above the scrim in z-order so
     * their own clicks still register.
     */
    private lateinit var scrim: View

    private val drawerStateListener: (Boolean, Int) -> Unit = { open, screenIdx ->
        mainHandler.post {
            val showDrawerHere = open && screenIdx == slotIdx
            if (::drawer.isInitialized) {
                drawer.visibility = if (showDrawerHere) View.VISIBLE else View.GONE
            }
            refreshScrim()
            android.util.Log.i(
                "UxSpace/Drawer",
                "screen=$slotIdx listener fired open=$open targetScreen=$screenIdx " +
                    "showDrawerHere=$showDrawerHere",
            )
        }
    }

    private val settingsStateListener: (Boolean, Int) -> Unit = { open, screenIdx ->
        mainHandler.post {
            val showHere = open && screenIdx == slotIdx
            if (::settings.isInitialized) {
                settings.visibility = if (showHere) View.VISIBLE else View.GONE
            }
            refreshScrim()
        }
    }

    /** Scrim visible whenever any modal panel is open anywhere. */
    private fun refreshScrim() {
        if (!::scrim.isInitialized) return
        val anyOpen = WorkspaceController.isDrawerOpen || WorkspaceController.isSettingsOpen
        scrim.visibility = if (anyOpen) View.VISIBLE else View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reshape the Presentation's window so it doesn't eat input or paint over the
        // launched app:
        //   - Set the window background drawable to transparent (theme has an opaque
        //     DeviceDefault background which would render a solid panel over the
        //     activity stacked beneath us on the same trusted display).
        //   - FLAG_NOT_TOUCHABLE so the system input dispatcher skips this window
        //     when it picks a target on the display. UxSpace's WorkspaceRenderer
        //     calls dispatchTap() directly on this Presentation's view tree for
        //     taskbar / drawer / settings hits, which doesn't go through the system
        //     dispatcher and therefore isn't blocked by the flag.
        //   - FLAG_DISMISS_KEYGUARD because Samsung One UI parks a transient
        //     KEYGUARD_DIALOG window (type 2009) on every Presentation-capable
        //     secondary display; that window also doesn't carry NOT_TOUCHABLE, so
        //     touches injected at the display are eaten before they ever reach our
        //     activity. The dismiss flag chases it off our display.
        window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        val root = FrameLayout(context)
        wallpaper = buildWallpaper()
        root.addView(wallpaper)
        // Scrim sits between wallpaper and taskbar/drawer so a click on bare
        // wallpaper closes the drawer, but taskbar buttons + the drawer itself
        // still receive their own clicks (they're above the scrim in z-order).
        scrim = View(context).apply {
            visibility = View.GONE
            setOnClickListener {
                // Close whichever modal is open. Mutually exclusive so usually one of
                // these is a no-op; the order doesn't matter.
                if (WorkspaceController.isDrawerOpen) {
                    WorkspaceController.setDrawerOpen(false, slotIdx)
                }
                if (WorkspaceController.isSettingsOpen) {
                    WorkspaceController.setSettingsOpen(false, slotIdx)
                }
            }
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        // Window chrome is a thin strip pinned to the top of the slot. Hidden until an
        // app is running on this slot — refreshChrome() flips its visibility from
        // addRunningApp / removeRunningApp.
        chrome = WindowChromeView(context).apply {
            visibility = View.GONE
            setListener(chromeListener)
        }
        root.addView(
            chrome,
            FrameLayout.LayoutParams(MATCH, dp(CHROME_HEIGHT_DP), Gravity.TOP),
        )
        if (showTaskbar) root.addView(buildTaskbar())
        root.addView(buildVersionLabel())
        // Drawer goes last so it sits on top of wallpaper + taskbar in the view tree.
        // Insets from screen edges so it doesn't cover the whole surface; tap outside
        // would land on the wallpaper (no close-on-outside yet — drawer closes when an
        // app is launched or on lock/unlock).
        // Drawer size: aspect-preserved fit inside 60% of the host screen's pixel
        // dims, with both the baseline cap and the aspect transposed to portrait on
        // a V slot. Without the transpose, a V slot (1080×1920) clamps effectiveH
        // to the landscape baseline H (1080) and keeps the landscape 1400×920
        // aspect, producing a stubby horizontal drawer in the middle of a tall
        // screen. Cap rule unchanged: ultrawide / panoramic screens still get the
        // *same* drawer as a single 1920×1080 screen.
        val metrics = android.util.DisplayMetrics()
        display.getRealMetrics(metrics)
        val portraitHost = metrics.heightPixels > metrics.widthPixels
        val baselineW = if (portraitHost) DRAWER_BASELINE_SCREEN_H else DRAWER_BASELINE_SCREEN_W
        val baselineH = if (portraitHost) DRAWER_BASELINE_SCREEN_W else DRAWER_BASELINE_SCREEN_H
        val effectiveW = minOf(metrics.widthPixels, baselineW)
        val effectiveH = minOf(metrics.heightPixels, baselineH)
        val maxW = (effectiveW * DRAWER_SCREEN_FRACTION).toInt()
        val maxH = (effectiveH * DRAWER_SCREEN_FRACTION).toInt()
        val aspect = if (portraitHost) DRAWER_ASPECT_H / DRAWER_ASPECT_W
        else DRAWER_ASPECT_W / DRAWER_ASPECT_H
        var dw = maxW
        var dh = (maxW / aspect).toInt()
        if (dh > maxH) { dh = maxH; dw = (maxH * aspect).toInt() }
        drawer = DrawerView(context).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dw, dh, Gravity.CENTER)
        }
        root.addView(drawer)
        // Settings panel shares the drawer's size + centring rule — it's a sibling
        // modal that opens via the taskbar's Settings button (or workspace toolbar).
        settings = SettingsView(context).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dw, dh, Gravity.CENTER)
        }
        root.addView(settings)
        setContentView(root)
        // Per-screen taskbar — only react to launches on this screen.
        WorkspaceController.addAppLaunchedListener(appLaunchedListener)
        WorkspaceController.addAppClosedListener(appClosedListener)
        WorkspaceController.addDrawerStateListener(drawerStateListener)
        WorkspaceController.addSettingsStateListener(settingsStateListener)
    }

    override fun onDetachedFromWindow() {
        WorkspaceController.removeAppLaunchedListener(appLaunchedListener)
        WorkspaceController.removeAppClosedListener(appClosedListener)
        WorkspaceController.removeDrawerStateListener(drawerStateListener)
        WorkspaceController.removeSettingsStateListener(settingsStateListener)
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
                // Toggle is local to *this* slot — if the drawer is open on another
                // monitor, one click moves it here instead of taking two (close-then-
                // open). Only close when the drawer is already on this slot.
                val openHere = WorkspaceController.isDrawerOpen &&
                    WorkspaceController.drawerOnScreen == slotIdx
                android.util.Log.i(
                    "UxSpace/Drawer",
                    "screen=$slotIdx drawer button clicked, openOn=" +
                        "${WorkspaceController.drawerOnScreen} openHere=$openHere",
                )
                WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.ALL)
                WorkspaceController.setDrawerOpen(!openHere, slotIdx)
            },
        )
        addView(buildDivider())
        addView(
            taskbarButton(R.drawable.ic_recent, "Recent apps") {
                WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.RECENT)
                WorkspaceController.setDrawerOpen(true, slotIdx)
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
                WorkspaceController.setDrawerOpen(true, slotIdx)
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
                taskbarButton(R.drawable.ic_settings, "Settings") {
                    // Same one-click move semantics as the App drawer: clicking on a
                    // different slot's Settings button moves the panel here in one tap.
                    val openHere = WorkspaceController.isSettingsOpen &&
                        WorkspaceController.settingsOnScreen == slotIdx
                    WorkspaceController.setSettingsOpen(!openHere, slotIdx)
                },
            )
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
        runningLabels[packageName] = label
        runningApps.addView(
            icon,
            LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(12) },
        )
        refreshWallpaperVisibility()
        refreshChrome()
    }

    private fun appIcon(packageName: String): Drawable? = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()

    /** Remove an app's taskbar icon when its window is closed. */
    private fun removeRunningApp(packageName: String) {
        if (!::runningApps.isInitialized) return
        runningIcons.remove(packageName)?.let { runningApps.removeView(it) }
        runningLabels.remove(packageName)
        refreshWallpaperVisibility()
        refreshChrome()
    }

    /**
     * Sync the window-chrome bar to the slot's foreground app — the most recently
     * launched (last entry in the [runningIcons] LinkedHashMap). Hidden when no app is
     * running on this slot.
     */
    private fun refreshChrome() {
        if (!::chrome.isInitialized) return
        val top = runningIcons.keys.lastOrNull()
        if (top == null) {
            chrome.visibility = View.GONE
            chrome.bind(null, null, null)
            return
        }
        chrome.bind(top, runningLabels[top], appIcon(top))
        chrome.visibility = View.VISIBLE
    }

    /**
     * Wallpaper visible only when no app is running on this slot. With the translucent
     * Presentation theme, the wallpaper is the one piece of the desktop's UI that
     * would still occlude a launched activity (the activity renders behind the
     * Presentation window in SurfaceFlinger z-order); hiding it lets the activity
     * appear in the slot. Taskbar stays opaque on top so the user still has it.
     */
    private fun refreshWallpaperVisibility() {
        if (!::wallpaper.isInitialized) return
        wallpaper.visibility = if (runningIcons.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun clockText(): String =
        SimpleDateFormat("h:mm a\nEEE, MMM d", Locale.getDefault()).format(Date())

    private companion object {
        const val WALLPAPER_ASSET = "workspace_background.jpg"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val VOID_COLOR = 0xFF0E1018.toInt()
        const val CLOCK_INTERVAL_MS = 20_000L

        /**
         * Height of the window-chrome bar in dp. Mirrored on the renderer side as
         * `CHROME_HEIGHT_PX` (computed with the slot Presentation's density 200) so the
         * cursor-region check picks the same band on the slot's surface texture.
         */
        const val CHROME_HEIGHT_DP = 36

        /** Translucent white for the left-cluster divider — a quarter-strength rule line. */
        const val DIVIDER_COLOR = 0x40FFFFFF

        // Drawer sizing. The effective screen is min(host px, baseline), then 60 % cap
        // per dim, aspect-preserved. So ultrawide / wider-than-baseline screens get
        // the same drawer as a single 1920×1080 screen — UI doesn't scale with extra
        // real estate, per the design rule. Aspect 1400×920 ≈ 1.52:1.
        const val DRAWER_BASELINE_SCREEN_W = 1920
        const val DRAWER_BASELINE_SCREEN_H = 1080
        const val DRAWER_SCREEN_FRACTION = 0.6f
        const val DRAWER_ASPECT_W = 1400f
        const val DRAWER_ASPECT_H = 920f
    }
}
