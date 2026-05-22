package com.vspace.spatial

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * An app window's frame — a thin light-grey border around the app, with a title bar carrying
 * a back button on the left and the minimise, maximise, and close buttons on the right (the
 * Samsung DeX layout).
 *
 * The frame *is* the window: it is hosted on a full-window-sized [UiScreen], and the app's
 * own content quad is drawn on top of it — inset below the title bar and within the border —
 * by [WorkspaceRenderer], so the app is rendered inside the window. Colours come from
 * [VSpaceTheme]; the cursor's taps reach the buttons through [UiScreen.dispatchTap].
 */
class WindowChrome(
    outerContext: Context,
    display: Display,
    private val onBack: () -> Unit,
    private val onMinimize: () -> Unit,
    private val onMaximize: () -> Unit,
    private val onClose: () -> Unit,
) : Presentation(outerContext, display, android.R.style.Theme_DeviceDefault) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The whole surface is the light window colour. The title bar fills the top strip;
        // the app's content quad covers the rest, leaving the grey showing as a thin border.
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(VSpaceTheme.windowBorder)
        }

        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(VSpaceTheme.windowTitleBar)
        }
        // Back on the left.
        titleBar.addView(button("←", onBack), LinearLayout.LayoutParams(BUTTON_W, MATCH))
        // A draggable gap between the back button and the window controls.
        titleBar.addView(View(context), LinearLayout.LayoutParams(0, MATCH, 1f))
        // Minimise, maximise, close on the right.
        titleBar.addView(button("–", onMinimize), LinearLayout.LayoutParams(BUTTON_W, MATCH))
        titleBar.addView(button("□", onMaximize), LinearLayout.LayoutParams(BUTTON_W, MATCH))
        titleBar.addView(button("×", onClose), LinearLayout.LayoutParams(BUTTON_W, MATCH))

        root.addView(titleBar, LinearLayout.LayoutParams(MATCH, AppWindow.TITLE_BAR_PX))
        setContentView(root)
        Log.i(TAG, "title bar built")
    }

    /** One title-bar button — a centred glyph with a borderless ripple. */
    private fun button(glyph: String, action: () -> Unit): TextView = TextView(context).apply {
        text = glyph
        gravity = Gravity.CENTER
        setTextColor(VSpaceTheme.windowFrameText)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, GLYPH_PX)
        isClickable = true
        val ripple = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)
        setOnClickListener { action() }
    }

    private companion object {
        const val TAG = "VSpace/Chrome"
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /** Title-bar button width, and glyph size, in chrome-surface pixels. */
        const val BUTTON_W = 128
        const val GLYPH_PX = 38f
    }
}
