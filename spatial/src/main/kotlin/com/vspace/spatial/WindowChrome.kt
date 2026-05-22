package com.vspace.spatial

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * An app window's frame — the light-grey border around the app, with a title bar carrying
 * the app's name and the minimise, maximise, and close buttons.
 *
 * The frame *is* the window: it is hosted on a full-window-sized [UiScreen], and the app's
 * own content quad is drawn on top of it — inset below the title bar and within the border —
 * by [WorkspaceRenderer], so the app is rendered inside the window. Colours come from
 * [VSpaceTheme]; the cursor's taps reach the buttons through [UiScreen.dispatchTap].
 */
class WindowChrome(
    outerContext: Context,
    display: Display,
    private val title: String,
    private val onMinimize: () -> Unit,
    private val onMaximize: () -> Unit,
    private val onClose: () -> Unit,
) : Presentation(outerContext, display, android.R.style.Theme_DeviceDefault) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The whole surface is the light-grey window colour. The title bar fills the top
        // strip; the app's content quad covers the rest, leaving the grey showing as the
        // border around it.
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(VSpaceTheme.windowFrame)
        }

        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(context).apply {
            text = title
            setTextColor(VSpaceTheme.windowFrameText)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(AppWindow.BORDER_PX + 14, 0, 14, 0)
        }
        titleBar.addView(label, LinearLayout.LayoutParams(0, MATCH, 1f))
        titleBar.addView(button("–", onMinimize))   // en dash — minimise
        titleBar.addView(button("□", onMaximize))   // hollow square — maximise
        titleBar.addView(button("×", onClose))      // multiplication sign — close
        root.addView(titleBar, LinearLayout.LayoutParams(MATCH, AppWindow.TITLE_BAR_PX))
        setContentView(root, ViewGroup.LayoutParams(MATCH, MATCH))
    }

    /** One title-bar button — a centred glyph, as wide as the title bar is tall. */
    private fun button(glyph: String, action: () -> Unit): TextView = TextView(context).apply {
        text = glyph
        gravity = Gravity.CENTER
        setTextColor(VSpaceTheme.windowFrameText)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 32f)
        isClickable = true
        val ripple = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(AppWindow.TITLE_BAR_PX, MATCH)
    }

    private companion object {
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
