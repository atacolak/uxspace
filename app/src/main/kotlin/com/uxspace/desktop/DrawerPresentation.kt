package com.uxspace.desktop

import android.app.Presentation
import android.content.Context
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
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.uxspace.R
import com.uxspace.apps.AppCache
import com.uxspace.apps.InstalledApp
import com.uxspace.spatial.WorkspaceController

/**
 * UxSpace's app drawer — the Samsung DeX drawer panel: Personal / Work tabs, an 8-column app
 * grid, and a search bar.
 *
 * It is its own [Presentation] on a dedicated `UiScreen`, so [com.uxspace.spatial.WorkspaceRenderer]
 * can draw it as an overlay *in front of* the app windows — behind a dimming scrim — instead
 * of behind them. The panel fills the whole surface; the renderer gives it its place in the
 * scene and the scrim. Tapping an app launches it and closes the drawer.
 */
class DrawerPresentation(
    outerContext: Context,
    display: Display,
) : Presentation(outerContext, display, android.R.style.Theme_DeviceDefault_NoActionBar) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter = AppGridAdapter()
    private lateinit var search: EditText
    private lateinit var allAppsTab: TextView
    private lateinit var recentTab: TextView

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildPanel())
        loadApps()
        // The phone control panel forwards keyboard text in here — typing on the phone's
        // IME-backed field filters the app grid, since the drawer's own EditText can't
        // receive a system IME on a secondary display.
        WorkspaceController.onDrawerSearchQuery = { query ->
            mainHandler.post { setSearchText(query) }
        }
        // Two ways the drawer changes mode: this hook (from the taskbar's All apps /
        // Recent buttons) and the in-drawer tab labels themselves.
        WorkspaceController.onDrawerModeChanged = { mode ->
            mainHandler.post { applyMode(mode) }
        }
        applyMode(WorkspaceController.drawerMode)
    }

    override fun onStop() {
        if (WorkspaceController.onDrawerSearchQuery != null) {
            WorkspaceController.onDrawerSearchQuery = null
        }
        if (WorkspaceController.onDrawerModeChanged != null) {
            WorkspaceController.onDrawerModeChanged = null
        }
        super.onStop()
    }

    /** Reflect the controller's drawer mode in the tab styling and the adapter's filter. */
    private fun applyMode(mode: WorkspaceController.DrawerMode) {
        if (::allAppsTab.isInitialized) styleTab(allAppsTab, mode == WorkspaceController.DrawerMode.ALL)
        if (::recentTab.isInitialized) styleTab(recentTab, mode == WorkspaceController.DrawerMode.RECENT)
        adapter.setMode(mode, WorkspaceController.recentApps)
    }

    private fun styleTab(tab: TextView, active: Boolean) {
        if (active) {
            tab.setTextColor(TAB_ACTIVE)
            tab.typeface = Typeface.DEFAULT_BOLD
        } else {
            tab.setTextColor(TAB_INACTIVE)
            tab.typeface = Typeface.DEFAULT
        }
    }

    /** Mirror externally-typed text into the search box without re-triggering its watcher. */
    private fun setSearchText(query: String) {
        if (!::search.isInitialized) return
        if (search.text.toString() == query) return
        search.setText(query)
        search.setSelection(query.length)
    }

    /** The panel fills the surface — the renderer positions and scrims it in the scene. */
    private fun buildPanel(): View {
        allAppsTab = tabLabel("All apps") {
            WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.ALL)
        }
        recentTab = tabLabel("Recent") {
            WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.RECENT)
        }
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(allAppsTab)
            addView(
                recentTab,
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(36) },
            )
        }

        val grid = GridView(context).apply {
            numColumns = DRAWER_COLUMNS
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(8)
            isVerticalScrollBarEnabled = false
            adapter = this@DrawerPresentation.adapter
            setOnItemClickListener { _, _, position, _ ->
                (this@DrawerPresentation.adapter.getItem(position) as? InstalledApp)?.let(::launch)
            }
        }

        search = EditText(context).apply {
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

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL_COLOR)
            setPadding(dp(22), dp(22), dp(22), dp(20))
            addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
            addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(search, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
        }
    }

    private fun tabLabel(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        textSize = 15f
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setTextColor(TAB_INACTIVE)
        setOnClickListener { onClick() }
    }

    private fun launch(app: InstalledApp) {
        WorkspaceController.launchApp(app.packageName, app.activityName, app.label)
        WorkspaceController.setDrawerOpen(false)
    }

    private fun loadApps() {
        // Synchronous if [AppCache] has already finished its app-start preload (typical
        // case); otherwise the callback fires on the loader thread when it's ready.
        AppCache.whenReady { list ->
            mainHandler.post { adapter.submit(list) }
        }
    }

    /** Grid adapter — one icon-over-label cell per installed app, with mode + search filtering. */
    private inner class AppGridAdapter : BaseAdapter() {
        private val full = ArrayList<InstalledApp>()
        private val items = ArrayList<InstalledApp>()
        private var query = ""
        private var mode: WorkspaceController.DrawerMode = WorkspaceController.DrawerMode.ALL
        private var recentOrder: List<String> = emptyList()

        fun submit(apps: List<InstalledApp>) {
            full.clear()
            full.addAll(apps)
            recompute()
        }

        fun setQuery(text: String) {
            query = text.trim()
            recompute()
        }

        fun setMode(mode: WorkspaceController.DrawerMode, recentPackages: List<String>) {
            this.mode = mode
            this.recentOrder = recentPackages
            recompute()
        }

        private fun recompute() {
            val base = when (mode) {
                WorkspaceController.DrawerMode.ALL -> full
                WorkspaceController.DrawerMode.RECENT -> {
                    val byPkg = full.associateBy { it.packageName }
                    recentOrder.mapNotNull { byPkg[it] }
                }
            }
            items.clear()
            items.addAll(
                if (query.isEmpty()) base
                else base.filter { it.label.contains(query, ignoreCase = true) },
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
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val DRAWER_COLUMNS = 8

        const val PANEL_COLOR = 0xFFECEDEF.toInt()
        const val SEARCH_COLOR = 0xFFE3E4E8.toInt()
        const val LABEL_COLOR = 0xFF33353B.toInt()
        const val TAB_ACTIVE = 0xFF1A1B1F.toInt()
        const val TAB_INACTIVE = 0xFF9A9CA3.toInt()
        const val HINT_COLOR = 0xFF8A8C93.toInt()
    }
}
