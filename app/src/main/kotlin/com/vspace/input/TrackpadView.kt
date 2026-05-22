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
 * A relative-motion touchpad. One finger dragging reports cursor deltas as a fraction of the
 * pad's width; a touch that neither moves far nor lingers is a tap. Two fingers dragging
 * reports a vertical scroll delta as a fraction of the pad's height.
 *
 * The user looks at the glasses, not at this — it is a blind trackpad, like a laptop's.
 */
class TrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called on a one-finger drag with the movement since the last event, fraction of width. */
    var onMove: ((dxFraction: Float, dyFraction: Float) -> Unit)? = null

    /** Called when a one-finger touch ends without having become a drag. */
    var onTap: (() -> Unit)? = null

    /** Called on a two-finger drag with the vertical movement, as a fraction of pad height. */
    var onScroll: ((dyFraction: Float) -> Unit)? = null

    /** Called when a press-and-hold turns the touch into a drag — used to grab a window. */
    var onDragStart: (() -> Unit)? = null

    /** Called when a drag ends. */
    var onDragEnd: (() -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var movedFar = false

    /** Latched true once a second finger lands; cleared when all fingers lift. */
    private var scrolling = false
    private var lastScrollY = 0f

    /** True once a press-and-hold has turned the current touch into a window drag. */
    private var dragging = false

    /** Posted on touch-down; fires if the finger holds still long enough to mean a drag. */
    private val holdRunnable = Runnable {
        if (!scrolling && !movedFar) {
            dragging = true
            onDragStart?.invoke()
        }
    }

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
                scrolling = false
                dragging = false
                removeCallbacks(holdRunnable)
                postDelayed(holdRunnable, HOLD_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger — switch from cursor-move to two-finger scroll.
                scrolling = true
                movedFar = true
                removeCallbacks(holdRunnable)
                endDragIfActive()
                lastScrollY = averageY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrolling) {
                    val y = averageY(event)
                    if (height > 0) onScroll?.invoke((y - lastScrollY) / height)
                    lastScrollY = y
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    if (abs(event.x - downX) > TAP_SLOP_PX ||
                        abs(event.y - downY) > TAP_SLOP_PX
                    ) {
                        movedFar = true
                        // Moving before the hold fires means a cursor move, not a drag.
                        if (!dragging) removeCallbacks(holdRunnable)
                    }
                    if (width > 0) onMove?.invoke(dx / width, dy / width)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-average over the fingers that remain, so the next move does not jump.
                lastScrollY = averageY(event, lifting = event.actionIndex)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                if (dragging) {
                    endDragIfActive()
                } else if (!scrolling && !movedFar &&
                    event.eventTime - downTime < TAP_TIMEOUT_MS
                ) {
                    onTap?.invoke()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                endDragIfActive()
            }
        }
        return true
    }

    private fun endDragIfActive() {
        if (dragging) {
            dragging = false
            onDragEnd?.invoke()
        }
    }

    /** Mean Y of the active pointers, optionally excluding one that is lifting. */
    private fun averageY(event: MotionEvent, lifting: Int = -1): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == lifting) continue
            sum += event.getY(i)
            count++
        }
        return if (count > 0) sum / count else lastScrollY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText(
            "Touchpad — drag to move · tap to click · hold to grab a window · two fingers scroll",
            width / 2f,
            height / 2f,
            labelPaint,
        )
    }

    private companion object {
        const val TAP_SLOP_PX = 24f
        const val TAP_TIMEOUT_MS = 300L

        /** Hold this long without moving and the touch becomes a window drag. */
        const val HOLD_MS = 240L
    }
}
