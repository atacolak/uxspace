package com.vspace.workspace

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
            setOnClickListener { visibility = View.GONE }
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
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { toggleDrawer() }
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(Color.argb(228, 22, 24, 32))
                setStroke(dp(1), Color.argb(38, 255, 255, 255))
            }
            addView(launcher)
        }
        return FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            addView(
                bar,
                FrameLayout.LayoutParams(WRAP, dp(60)).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(14)
                },
            )
        }
    }

    private fun toggleDrawer() {
        val d = drawer ?: return
        d.visibility = if (d.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun launch(app: InstalledApp) {
        WorkspaceController.launchApp(app.packageName, app.activityName)
        drawer?.visibility = View.GONE
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
    }
}
