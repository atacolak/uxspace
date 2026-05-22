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
 * An app window's title bar — a dark strip carrying the app's name and the minimise,
 * maximise, and close buttons.
 *
 * It is hosted on its own [UiScreen] and drawn by [WorkspaceRenderer] as a quad directly
 * above the app's content quad. The cursor's taps reach the buttons through
 * [UiScreen.dispatchTap], so ordinary click listeners are all that is needed — the button
 * actions are handed back to the renderer through the callbacks.
 */
class WindowChrome(
    outerContext: Context,
    display: Display,
    private val title: String,
    private val onMinimize: () -> Unit,
    private val onMaximize: () -> Unit,
    private val onClose: () -> Unit,
) : Presentation(outerContext, display, android.R.style.Theme_DeviceDefault) {

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR_COLOR)
        }

        val label = TextView(context).apply {
            text = title
            setTextColor(TITLE_COLOR)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(20), 0, dp(12), 0)
        }
        bar.addView(label, LinearLayout.LayoutParams(0, MATCH, 1f))

        bar.addView(button("–", onMinimize))   // en dash — minimise
        bar.addView(button("□", onMaximize))   // hollow square — maximise
        bar.addView(button("×", onClose))      // multiplication sign — close

        setContentView(bar, ViewGroup.LayoutParams(MATCH, MATCH))
    }

    /** One title-bar button: a centred glyph with a borderless ripple. */
    private fun button(glyph: String, action: () -> Unit): TextView = TextView(context).apply {
        text = glyph
        gravity = Gravity.CENTER
        setTextColor(TITLE_COLOR)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        isClickable = true
        val ripple = TypedValue()
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, ripple, true,
        )
        setBackgroundResource(ripple.resourceId)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(56), MATCH)
    }

    private companion object {
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val BAR_COLOR = 0xFF1B1F2A.toInt()
        const val TITLE_COLOR = 0xFFE6E8EE.toInt()
    }
}
