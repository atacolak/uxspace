package com.vspace.desktop

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
import com.vspace.R
import com.vspace.spatial.VSpaceTheme
import com.vspace.spatial.WorkspaceController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The desktop shown on the workspace's back plane — VSpace's DeX-style home: a wallpaper and
 * a bottom taskbar with an app-drawer launcher, the running-app icon, and a clock.
 *
 * It is a real Android view hierarchy on `Theme.DeviceDefault`, so on a Samsung device the
 * widgets are styled as One UI. The app drawer is a separate overlay ([DrawerPresentation])
 * so it can float in front of the app windows; the launcher button just toggles it.
 */
class DesktopPresentation(
    outerContext: Context,
    display: Display,
) : Presentation(outerContext, display, android.R.style.Theme_DeviceDefault_NoActionBar) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var runningApps: LinearLayout

    /** Package of the app currently shown in the taskbar, or null if none is running. */
    private var runningPackage: String? = null

    /** Refreshes the taskbar clock; re-posts itself while the desktop is shown. */
    private val clockTick = object : Runnable {
        override fun run() {
            if (::clock.isInitialized) clock.text = clockText()
            mainHandler.postDelayed(this, CLOCK_INTERVAL_MS)
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context)
        root.addView(buildWallpaper())
        root.addView(buildTaskbar())
        root.addView(buildVersionLabel())
        setContentView(root)
        // The taskbar reflects the launched app: shown when one launches, cleared when its
        // window closes (from the title bar, or because Back emptied it).
        WorkspaceController.onAppLaunched = { packageName, label ->
            mainHandler.post { showRunningApp(packageName, label) }
        }
        WorkspaceController.onAppClosed = { packageName ->
            mainHandler.post { onAppClosed(packageName) }
        }
    }

    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(clockTick)
        clockTick.run()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(clockTick)
        super.onStop()
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
        "VSpace · build $time"
    }.getOrDefault("VSpace")

    private fun buildTaskbar(): View {
        val launcher = ImageButton(context).apply {
            setImageResource(R.drawable.ic_apps)
            background = null
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener {
                WorkspaceController.setDrawerOpen(!WorkspaceController.isDrawerOpen)
            }
        }
        runningApps = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        clock = TextView(context).apply {
            setTextColor(VSpaceTheme.taskbarText)
            textSize = 12.5f
            gravity = Gravity.END
            setLineSpacing(0f, 0.95f)
            text = clockText()
        }
        // Launcher + running apps form a group centred in the bar; the clock sits right.
        val centerGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(launcher)
            addView(runningApps, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        // A full-width bar flush with the bottom edge — no floating gap.
        val bar = FrameLayout(context).apply {
            setPadding(dp(22), dp(4), dp(24), dp(4))
            setBackgroundColor(VSpaceTheme.taskbar)
            addView(centerGroup, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            addView(
                clock,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL),
            )
        }
        return FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            addView(bar, FrameLayout.LayoutParams(MATCH, dp(58)).apply { gravity = Gravity.BOTTOM })
        }
    }

    /**
     * Show the launched app in the taskbar — its icon toggles minimise / restore, a double
     * tap restores a maximised window. Closing is done from the window's own title bar.
     */
    private fun showRunningApp(packageName: String, label: String) {
        if (!::runningApps.isInitialized) return
        // One window at a time today, so the strip shows the current app.
        runningApps.removeAllViews()
        runningPackage = packageName
        val icon = ImageView(context)
        icon.setImageDrawable(appIcon(packageName))
        icon.contentDescription = label
        val gestures = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    toggleMinimized(icon)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    WorkspaceController.restoreWindow()
                    return true
                }
            },
        )
        // Always consume, so the icon keeps receiving events after the down — otherwise the
        // gesture detector never sees the up and single/double taps are lost.
        icon.setOnTouchListener { _, e ->
            gestures.onTouchEvent(e)
            true
        }
        runningApps.addView(
            icon,
            LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(12) },
        )
    }

    private fun appIcon(packageName: String): Drawable? = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()

    /** Tap the running-app icon: minimise the window, or restore it. */
    private fun toggleMinimized(icon: ImageView) {
        val hidden = !WorkspaceController.appsHidden
        WorkspaceController.setAppsHidden(hidden)
        icon.alpha = if (hidden) 0.4f else 1f
    }

    /** Clear the taskbar entry when its window is closed. */
    private fun onAppClosed(packageName: String) {
        if (packageName == runningPackage && ::runningApps.isInitialized) {
            runningApps.removeAllViews()
            runningPackage = null
        }
    }

    private fun clockText(): String =
        SimpleDateFormat("h:mm a\nEEE, MMM d", Locale.getDefault()).format(Date())

    private companion object {
        const val WALLPAPER_ASSET = "workspace_background.jpg"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val VOID_COLOR = 0xFF0E1018.toInt()
        const val CLOCK_INTERVAL_MS = 20_000L
    }
}
