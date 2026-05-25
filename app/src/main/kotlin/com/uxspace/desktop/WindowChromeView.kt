package com.uxspace.desktop

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.uxspace.R
import com.uxspace.spatial.UxSpaceTheme

/**
 * Title bar drawn above a launched app within a slot. Built fresh on top of the per-slot
 * `DesktopPresentation` model — the old `WindowChrome` (a separate Presentation on its
 * own VirtualDisplay) is gone; we now stack the bar as a sibling view of the wallpaper /
 * taskbar / drawer, visible only while an app is running on this slot. The visual idea
 * is borrowed from the old chrome: a dark thin strip with the app's icon + name on the
 * left and three ripple buttons (minimize, maximize, close) on the right.
 *
 * Cursor clicks are routed into this view tree by [com.uxspace.spatial.WorkspaceRenderer]
 * through `UiScreen.dispatchTap` — the slot Presentation holds FLAG_NOT_TOUCHABLE so the
 * system input dispatcher never delivers display-injected events here, but in-process
 * dispatch (calling `decorView.dispatchTouchEvent` directly) isn't sieved by that flag.
 *
 * Callers wire button actions through [setListener]; [bind] updates the icon/name when
 * the foreground app on the slot changes.
 */
class WindowChromeView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onMinimize(packageName: String)
        fun onMaximize(packageName: String)
        fun onClose(packageName: String)
    }

    private val icon: ImageView
    private val label: TextView
    private val minBtn: ImageButton
    private val maxBtn: ImageButton
    private val closeBtn: ImageButton

    private var packageName: String? = null
    private var listener: Listener? = null

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(UxSpaceTheme.windowTitleBar)
        setPadding(dp(10), 0, 0, 0)

        icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        addView(
            icon,
            LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) },
        )

        label = TextView(context).apply {
            setTextColor(UxSpaceTheme.windowFrameText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(label, LayoutParams(0, MATCH, 1f))

        minBtn = chromeButton(R.drawable.ic_minimize, "Minimize") {
            packageName?.let { listener?.onMinimize(it) }
        }
        maxBtn = chromeButton(R.drawable.ic_maximize, "Maximize") {
            packageName?.let { listener?.onMaximize(it) }
        }
        closeBtn = chromeButton(R.drawable.ic_close, "Close") {
            packageName?.let { listener?.onClose(it) }
        }
        addView(minBtn)
        addView(maxBtn)
        addView(closeBtn)
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Bind the chrome to the foreground app on this slot, or clear it. */
    fun bind(packageName: String?, label: String?, iconDrawable: Drawable?) {
        this.packageName = packageName
        this.label.text = label.orEmpty()
        this.icon.setImageDrawable(iconDrawable)
    }

    private fun chromeButton(
        iconRes: Int,
        description: String,
        onClick: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        setImageResource(iconRes)
        // Vector drawables in the project default to a light tint for the dark taskbar;
        // override here so the chrome's dark glyphs sit on the light frame.
        imageTintList = ColorStateList.valueOf(UxSpaceTheme.windowFrameText)
        contentDescription = description
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        val ripple = TypedValue()
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, ripple, true,
        )
        setBackgroundResource(ripple.resourceId)
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(dp(48), MATCH)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
