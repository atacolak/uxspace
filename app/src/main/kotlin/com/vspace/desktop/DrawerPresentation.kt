package com.vspace.desktop

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
import com.vspace.R
import com.vspace.apps.InstalledApp
import com.vspace.apps.InstalledApps
import com.vspace.spatial.WorkspaceController

/**
 * VSpace's app drawer — the Samsung DeX drawer panel: Personal / Work tabs, an 8-column app
 * grid, and a search bar.
 *
 * It is its own [Presentation] on a dedicated `UiScreen`, so [com.vspace.spatial.WorkspaceRenderer]
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

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildPanel())
        loadApps()
    }

    /** The panel fills the surface — the renderer positions and scrims it in the scene. */
    private fun buildPanel(): View {
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(tabLabel("Personal", active = true))
            addView(
                tabLabel("Work", active = false),
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

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL_COLOR)
            setPadding(dp(22), dp(22), dp(22), dp(20))
            addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
            addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(search, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
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

    private fun launch(app: InstalledApp) {
        WorkspaceController.launchApp(app.packageName, app.activityName, app.label)
        WorkspaceController.setDrawerOpen(false)
    }

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
