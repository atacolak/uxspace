package com.vspace.desktop

import android.app.Presentation
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vspace.R
import com.vspace.apps.InstalledApp
import com.vspace.apps.InstalledApps
import com.vspace.spatial.VSpaceTheme
import com.vspace.spatial.WorkspaceController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The desktop shown on the workspace's [UiScreen] — VSpace's DeX-style home: a wallpaper,
 * a bottom taskbar with an app launcher, and an app drawer.
 *
 * It is a real Android view hierarchy on `Theme.DeviceDefault`, so on a Samsung device the
 * widgets are styled as One UI — the way Samsung DeX itself is themed. The cursor's clicks
 * arrive as ordinary touch events ([UiScreen.dispatchTap]), so standard click listeners and
 * animations just work.
 *
 * The drawer reproduces the Samsung DeX app drawer: a contained, rounded, light panel with
 * Personal/Work tabs, an 8-column icon grid, and a search bar at the bottom.
 */
class DesktopPresentation(
    outerContext: Context,
    display: Display,
) : Presentation(outerContext, display, android.R.style.Theme_DeviceDefault) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter = AppGridAdapter()
    private var drawer: View? = null
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
        root.addView(buildDrawer().also { drawer = it })
        root.addView(buildTaskbar())
        setContentView(root)
        loadApps()
        // Clear the taskbar entry if its window is closed from the window's own title bar.
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

    private fun buildDrawer(): View {
        // Personal / Work tabs across the top.
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(tabLabel("Personal", active = true))
            addView(
                tabLabel("Work", active = false),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(36) },
            )
        }

        // The 8-column app grid.
        val grid = GridView(context).apply {
            numColumns = DRAWER_COLUMNS
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(8)
            isVerticalScrollBarEnabled = false
            adapter = this@DesktopPresentation.adapter
            setOnItemClickListener { _, _, position, _ ->
                (this@DesktopPresentation.adapter.getItem(position) as? InstalledApp)
                    ?.let { launch(it) }
            }
        }

        // The search bar along the bottom — a rounded pill.
        val search = EditText(context).apply {
            hint = "Search"
            setHintTextColor(HINT_COLOR)
            setTextColor(TAB_ACTIVE)
            textSize = 14f
            setSingleLine()
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(SEARCH_COLOR)
            }
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    adapter.setQuery(s?.toString().orEmpty())
                }
            })
        }

        // The light, rounded, contained drawer panel.
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(PANEL_COLOR)
            }
            setPadding(dp(18), dp(22), dp(18), dp(18))
            addView(
                tabs,
                LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) },
            )
            addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(
                search,
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) },
            )
        }

        // A dimmed, full-screen scrim; the panel floats in the centre.
        return FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(DRAWER_SCRIM)
            visibility = View.GONE
            // A tap on the dimmed area outside the panel closes the drawer.
            setOnClickListener { setDrawerVisible(false) }
            addView(
                panel,
                FrameLayout.LayoutParams(MATCH, MATCH).apply {
                    gravity = Gravity.CENTER
                    marginStart = dp(400)
                    marginEnd = dp(400)
                    topMargin = dp(112)
                    bottomMargin = dp(104)
                },
            )
        }
    }

    private fun tabLabel(text: String, active: Boolean): TextView = TextView(context).apply {
        this.text = text
        textSize = 15f
        setPadding(dp(8), dp(6), dp(8), dp(6))
        if (active) {
            setTextColor(TAB_ACTIVE)
            typeface = Typeface.DEFAULT_BOLD
        } else {
            setTextColor(TAB_INACTIVE)
        }
    }

    private fun buildTaskbar(): View {
        val launcher = ImageButton(context).apply {
            setImageResource(R.drawable.ic_apps)
            background = null
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { toggleDrawer() }
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

    private fun toggleDrawer() {
        setDrawerVisible(drawer?.visibility != View.VISIBLE)
    }

    /**
     * Show or hide the app drawer. While it is open the app windows are lifted out of the way
     * — the drawer is drawn on the desktop plane, behind them, so it would otherwise be
     * occluded.
     */
    private fun setDrawerVisible(visible: Boolean) {
        drawer?.visibility = if (visible) View.VISIBLE else View.GONE
        WorkspaceController.setDrawerOpen(visible)
    }

    private fun launch(app: InstalledApp) {
        WorkspaceController.launchApp(app.packageName, app.activityName, app.label)
        showRunningApp(app)
        setDrawerVisible(false)
    }

    /**
     * Show the launched app in the taskbar — its icon toggles minimise / restore. Closing is
     * done from the window's own title bar, so the taskbar carries no close button.
     */
    private fun showRunningApp(app: InstalledApp) {
        if (!::runningApps.isInitialized) return
        // One window at a time today, so the strip shows the current app.
        runningApps.removeAllViews()
        runningPackage = app.packageName
        val icon = ImageView(context).apply {
            setImageDrawable(app.icon)
            setOnClickListener { toggleMinimized(this) }
        }
        runningApps.addView(
            icon,
            LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(12) },
        )
    }

    /** Tap the running-app icon: minimise the window, or restore it. */
    private fun toggleMinimized(icon: ImageView) {
        val hidden = !WorkspaceController.appsHidden
        WorkspaceController.setAppsHidden(hidden)
        icon.alpha = if (hidden) 0.4f else 1f
    }

    /** Clear the taskbar entry when its window is closed from the window's title bar. */
    private fun onAppClosed(packageName: String) {
        if (packageName == runningPackage && ::runningApps.isInitialized) {
            runningApps.removeAllViews()
            runningPackage = null
        }
    }

    private fun clockText(): String =
        SimpleDateFormat("h:mm a\nEEE, MMM d", Locale.getDefault()).format(Date())

    private fun loadApps() {
        Thread {
            val list = runCatching { InstalledApps.query(context) }.getOrDefault(emptyList())
            mainHandler.post { adapter.submit(list) }
        }.start()
    }

    /** Grid adapter — one icon-over-label cell per installed app, with search filtering. */
    private inner class AppGridAdapter : BaseAdapter() {
        private val full = ArrayList<InstalledApp>()
        private val items = ArrayList<InstalledApp>()
        private var query = ""

        fun submit(apps: List<InstalledApp>) {
            full.clear()
            full.addAll(apps)
            recompute()
        }

        fun setQuery(text: String) {
            query = text.trim()
            recompute()
        }

        private fun recompute() {
            items.clear()
            items.addAll(
                if (query.isEmpty()) full
                else full.filter { it.label.contains(query, ignoreCase = true) },
            )
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView as? LinearLayout ?: newCell()
            val app = items[position]
            (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }

        private fun newCell(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(10), dp(6), dp(10))
            addView(ImageView(context), LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(
                TextView(context).apply {
                    setTextColor(LABEL_COLOR)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) },
            )
        }
    }

    private companion object {
        const val WALLPAPER_ASSET = "workspace_background.jpg"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val DRAWER_COLUMNS = 8

        const val VOID_COLOR = 0xFF0E1018.toInt()
        const val DRAWER_SCRIM = 0xB3000000.toInt()
        const val PANEL_COLOR = 0xFFECEDEF.toInt()
        const val SEARCH_COLOR = 0xFFE3E4E8.toInt()
        const val LABEL_COLOR = 0xFF33353B.toInt()
        const val TAB_ACTIVE = 0xFF1A1B1F.toInt()
        const val TAB_INACTIVE = 0xFF9A9CA3.toInt()
        const val HINT_COLOR = 0xFF8A8C93.toInt()
        const val CLOCK_INTERVAL_MS = 20_000L
    }
}
