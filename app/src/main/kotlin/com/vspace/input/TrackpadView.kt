package com.vspace.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * A relative-motion touchpad. Dragging reports cursor deltas as a fraction of the pad's width
 * (so horizontal and vertical sensitivity match); a touch that neither moves far nor lingers
 * is reported as a tap.
 *
 * The user looks at the glasses, not at this — it is a blind trackpad, like a laptop's.
 */
class TrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called on drag with the movement since the last event, as a fraction of pad width. */
    var onMove: ((dxFraction: Float, dyFraction: Float) -> Unit)? = null

    /** Called when a touch ends without having become a drag. */
    var onTap: (() -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var movedFar = false

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9AA0AC")
        textAlign = Paint.Align.CENTER
        textSize = 38f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                movedFar = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (abs(event.x - downX) > TAP_SLOP_PX || abs(event.y - downY) > TAP_SLOP_PX) {
                    movedFar = true
                }
                if (width > 0) onMove?.invoke(dx / width, dy / width)
            }
            MotionEvent.ACTION_UP -> {
                if (!movedFar && event.eventTime - downTime < TAP_TIMEOUT_MS) onTap?.invoke()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText(
            "Touchpad — drag to move the cursor, tap to click",
            width / 2f,
            height / 2f,
            labelPaint,
        )
    }

    private companion object {
        const val TAP_SLOP_PX = 24f
        const val TAP_TIMEOUT_MS = 300L
    }
}
