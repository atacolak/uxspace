package com.uxspace.desktop

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.uxspace.spatial.Layout
import com.uxspace.spatial.WorkspaceController
import com.uxspace.spatial.WorkspaceRenderer.ViewMode

/**
 * UxSpace's per-slot settings panel — a regular Android view (not a Presentation),
 * embedded as a child of each screen's [DesktopPresentation] alongside the [DrawerView].
 * Same modal model as the drawer: the screen's surface texture contains the panel when
 * visible; sizing always matches the host screen; visibility is driven by the controller
 * via [WorkspaceController.addSettingsStateListener] filtered on the slot index.
 *
 * Contents map 1:1 onto [WorkspaceController] APIs that already exist — view mode,
 * layout choice, workspace zoom, and screen band — plus a small About row with the
 * installed build stamp so a capture can be tied to the binary that produced it. New
 * settings can be appended as sections without touching the modal plumbing.
 */
class SettingsView(context: Context) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var viewModePinned: TextView
    private lateinit var viewModeFree: TextView
    private lateinit var layoutRows: Map<Layout, TextView>
    private lateinit var zoomLabel: TextView
    private lateinit var bandValue: TextView
    private lateinit var bandSeek: SeekBar

    /** Pulled from the controller every time the panel becomes visible. */
    private val zoomListener: (Float) -> Unit = { z -> mainHandler.post { renderZoom(z) } }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(PANEL_COLOR)
        setPadding(dp(24), dp(24), dp(24), dp(20))

        val title = TextView(context).apply {
            text = "Settings"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TITLE_COLOR)
        }
        addView(title, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) })

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = OVER_SCROLL_NEVER
        }
        val body = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(body, LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, 0, 1f))

        body.addView(buildViewModeSection())
        body.addView(spacer(dp(20)))
        body.addView(buildLayoutSection())
        body.addView(spacer(dp(20)))
        body.addView(buildZoomSection())
        body.addView(spacer(dp(20)))
        body.addView(buildScreenBandSection())
        body.addView(spacer(dp(20)))
        body.addView(buildAboutSection())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WorkspaceController.addZoomListener(zoomListener)
        renderAll()
    }

    override fun onDetachedFromWindow() {
        WorkspaceController.removeZoomListener(zoomListener)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Each open re-syncs the panel to whatever the controller currently holds, so a
        // toggle made through the workspace toolbar or another screen's settings panel
        // is reflected the moment this one appears.
        if (changedView === this && visibility == VISIBLE) renderAll()
    }

    private fun renderAll() {
        renderViewMode(WorkspaceController.currentViewMode)
        renderLayout(WorkspaceController.layout)
        renderZoom(WorkspaceController.currentZoom())
        renderScreenBand(WorkspaceController.currentScreenBand())
    }

    // region View mode

    private fun buildViewModeSection(): View {
        viewModePinned = pillButton("Pinned to head") {
            WorkspaceController.setViewMode(ViewMode.PINNED)
            renderViewMode(ViewMode.PINNED)
        }
        viewModeFree = pillButton("Free in world") {
            WorkspaceController.setViewMode(ViewMode.FREE)
            renderViewMode(ViewMode.FREE)
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(viewModePinned, LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
            addView(viewModeFree, LayoutParams(0, WRAP, 1f))
        }
        return sectionBlock("View mode", row)
    }

    private fun renderViewMode(mode: ViewMode) {
        stylePill(viewModePinned, mode == ViewMode.PINNED)
        stylePill(viewModeFree, mode == ViewMode.FREE)
    }

    // endregion

    // region Layout

    private fun buildLayoutSection(): View {
        val rows = mutableMapOf<Layout, TextView>()
        val list = LinearLayout(context).apply { orientation = VERTICAL }
        Layout.values().forEach { layout ->
            val row = pillButton(layout.displayName) {
                WorkspaceController.setLayout(layout)
                renderLayout(layout)
            }
            list.addView(row, LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
            rows[layout] = row
        }
        layoutRows = rows
        return sectionBlock("Layout", list)
    }

    private fun renderLayout(layout: Layout) {
        layoutRows.forEach { (l, row) -> stylePill(row, l == layout) }
    }

    // endregion

    // region Zoom

    private fun buildZoomSection(): View {
        zoomLabel = TextView(context).apply {
            setTextColor(LABEL_COLOR)
            textSize = 13f
        }
        val minusBtn = pillButton("−") { stepZoom(-1) }
        val plusBtn = pillButton("+") { stepZoom(+1) }
        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(zoomLabel, LayoutParams(0, WRAP, 1f))
            addView(minusBtn, LayoutParams(WRAP, WRAP).apply { marginStart = dp(8); minimumWidth = dp(48) })
            addView(plusBtn, LayoutParams(WRAP, WRAP).apply { marginStart = dp(8); minimumWidth = dp(48) })
        }
        return sectionBlock("Workspace zoom", controls)
    }

    private fun stepZoom(direction: Int) {
        // Two steps of cycleScreenBand to walk forward, one to walk back — keeps the
        // controller as the source of truth for the preset list rather than mirroring it.
        if (direction > 0) {
            WorkspaceController.cycleScreenBand()
        } else {
            // No "cycle backwards" API — emulate by cycling forward (N − 1) times until
            // the API grows one. The preset list has 5 entries today.
            repeat(ZOOM_BACKWARD_STEPS) { WorkspaceController.cycleScreenBand() }
        }
    }

    private fun renderZoom(zoom: Float) {
        zoomLabel.text = "${(zoom * 100).toInt()}%"
    }

    // endregion

    // region Screen band

    private fun buildScreenBandSection(): View {
        bandValue = TextView(context).apply {
            setTextColor(LABEL_COLOR)
            textSize = 13f
        }
        bandSeek = SeekBar(context).apply {
            max = BAND_SEEK_MAX
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val fraction = bandFractionFromProgress(value)
                    WorkspaceController.setScreenBand(fraction)
                    renderScreenBand(fraction)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bandSeek, LayoutParams(0, WRAP, 1f))
            addView(bandValue, LayoutParams(WRAP, WRAP).apply { marginStart = dp(12); minimumWidth = dp(56) })
        }
        return sectionBlock("Render band", row)
    }

    private fun renderScreenBand(fraction: Float) {
        bandSeek.progress = bandProgressFromFraction(fraction)
        bandValue.text = "${(fraction * 100).toInt()}%"
    }

    private fun bandFractionFromProgress(progress: Int): Float =
        BAND_MIN + (progress / BAND_SEEK_MAX.toFloat()) * (BAND_MAX - BAND_MIN)

    private fun bandProgressFromFraction(fraction: Float): Int {
        val clamped = fraction.coerceIn(BAND_MIN, BAND_MAX)
        return (((clamped - BAND_MIN) / (BAND_MAX - BAND_MIN)) * BAND_SEEK_MAX).toInt()
    }

    // endregion

    // region About

    private fun buildAboutSection(): View {
        val stamp = TextView(context).apply {
            text = aboutText()
            textSize = 12f
            setTextColor(LABEL_COLOR)
        }
        return sectionBlock("About", stamp)
    }

    private fun aboutText(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val time = java.text.SimpleDateFormat("MMM d  HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(info.lastUpdateTime))
        "UxSpace build $time"
    }.getOrDefault("UxSpace")

    // endregion

    // region UI helpers

    private fun sectionBlock(label: String, content: View): View = LinearLayout(context).apply {
        orientation = VERTICAL
        val header = TextView(context).apply {
            text = label.uppercase()
            textSize = 11f
            setTextColor(SECTION_LABEL)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        addView(header, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        addView(content, LayoutParams(MATCH, WRAP))
    }

    private fun spacer(height: Int): View = View(context).apply {
        layoutParams = LayoutParams(MATCH, height)
    }

    private fun pillButton(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 14f
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
        stylePill(this, active = false)
    }

    private fun stylePill(tv: TextView, active: Boolean) {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (active) PILL_ACTIVE_BG else PILL_INACTIVE_BG)
        }
        tv.background = bg
        tv.setTextColor(if (active) PILL_ACTIVE_FG else PILL_INACTIVE_FG)
        tv.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    // endregion

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val PANEL_COLOR = 0xFFECEDEF.toInt()
        const val TITLE_COLOR = 0xFF1A1B1F.toInt()
        const val SECTION_LABEL = 0xFF6A6C73.toInt()
        const val LABEL_COLOR = 0xFF33353B.toInt()
        const val PILL_INACTIVE_BG = 0xFFDDDFE4.toInt()
        const val PILL_ACTIVE_BG = 0xFF1A1B1F.toInt()
        const val PILL_INACTIVE_FG = 0xFF3B3D43.toInt()
        const val PILL_ACTIVE_FG = 0xFFFFFFFF.toInt()

        // Render band slider runs across [60 %, 100 %]; finer-grained than the on-glasses
        // experience can usefully resolve.
        const val BAND_MIN = 0.60f
        const val BAND_MAX = 1.00f
        const val BAND_SEEK_MAX = 40   // 1-percentage-point steps

        // The preset list in WorkspaceController has 5 entries — four forward cycles equal
        // one step backward. Keep the magic number named so it's obvious if the preset list grows.
        const val ZOOM_BACKWARD_STEPS = 4
    }
}
