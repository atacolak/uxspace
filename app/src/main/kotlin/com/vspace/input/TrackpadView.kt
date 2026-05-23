package com.vspace.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

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

    /**
     * Called on a two-finger pinch with the ratio of current to previous finger spread —
     * 1.0 means no zoom, > 1 spreads apart (zoom in), < 1 pinches together (zoom out).
     */
    var onZoom: ((scaleFactor: Float) -> Unit)? = null

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

    /**
     * Cursor-movement buffered during the touch's "warmup". A two-finger gesture often
     * lands its second finger a few ms after the first; if we treat that gap as a
     * one-finger drag the cursor jumps before the gesture is recognised, which then sends
     * the auto-scroll to wherever the cursor jumped to. We buffer the first ~30 ms / 8 px
     * of one-finger movement and discard it if `POINTER_DOWN` arrives during the window.
     */
    private var warmupActive = false
    private var warmupDx = 0f
    private var warmupDy = 0f

    /** Total cursor delta emitted by *this* touch — undone on `POINTER_DOWN`. */
    private var cursorEmittedDx = 0f
    private var cursorEmittedDy = 0f

    /** Latched true once a second finger lands; cleared when all fingers lift. */
    private var scrolling = false
    private var lastScrollY = 0f
    private var lastSpread = 0f

    /**
     * Two-finger flick → continuous scroll. A second finger lands, the user moves to
     * indicate direction and speed, lifts, and the scroll runs at that velocity until the
     * next two-finger touch stops it. The first POINTER_DOWN of each two-finger gesture
     * either cancels an active auto-scroll (acting as a stop) or starts a new gesture; the
     * matching UP either kicks the captured velocity into auto-scroll or — if this was the
     * stop gesture — does nothing.
     */
    private var autoScrollActive = false
    private var autoScrollFractionPerMs = 0f
    private var twoFingerDownTime = 0L
    private var twoFingerDownY = 0f
    private var twoFingerLastY = 0f
    private var twoFingerLastTime = 0L
    private var twoFingerStartedDuringAutoScroll = false

    private val autoScrollTick = object : Runnable {
        override fun run() {
            if (!autoScrollActive) return
            onScroll?.invoke(autoScrollFractionPerMs * AUTO_SCROLL_TICK_MS)
            postDelayed(this, AUTO_SCROLL_TICK_MS.toLong())
        }
    }

    private fun cancelAutoScroll() {
        if (!autoScrollActive) return
        autoScrollActive = false
        removeCallbacks(autoScrollTick)
    }

    private fun maybeStartAutoScroll() {
        val dt = twoFingerLastTime - twoFingerDownTime
        val dy = twoFingerLastY - twoFingerDownY
        if (dt <= 0 || abs(dy) < MIN_FLICK_PX || height <= 0) return
        val fractionPerMs = (dy / dt) / height
        autoScrollFractionPerMs = fractionPerMs.coerceIn(-MAX_AUTO_SCROLL_PER_MS, MAX_AUTO_SCROLL_PER_MS)
        autoScrollActive = true
        post(autoScrollTick)
    }

    override fun onDetachedFromWindow() {
        cancelAutoScroll()
        super.onDetachedFromWindow()
    }

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
                warmupActive = true
                warmupDx = 0f
                warmupDy = 0f
                cursorEmittedDx = 0f
                cursorEmittedDy = 0f
                removeCallbacks(holdRunnable)
                postDelayed(holdRunnable, HOLD_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger — switch from cursor-move to two-finger gestures. If
                // auto-scroll is running, this touch *stops* it (and the matching UP will
                // not start a new one).
                scrolling = true
                movedFar = true
                removeCallbacks(holdRunnable)
                endDragIfActive()
                // Discard any one-finger movement buffered while we were waiting to see if
                // a second finger would arrive, and undo any cursor delta that already
                // emitted (warmup may have flushed early). Both keep the cursor where it
                // was when the gesture really began so auto-scroll can target the right
                // window.
                warmupActive = false
                warmupDx = 0f
                warmupDy = 0f
                if (cursorEmittedDx != 0f || cursorEmittedDy != 0f) {
                    onMove?.invoke(-cursorEmittedDx, -cursorEmittedDy)
                    cursorEmittedDx = 0f
                    cursorEmittedDy = 0f
                }
                twoFingerStartedDuringAutoScroll = autoScrollActive
                cancelAutoScroll()
                twoFingerDownTime = event.eventTime
                twoFingerDownY = averageY(event)
                twoFingerLastY = twoFingerDownY
                twoFingerLastTime = twoFingerDownTime
                lastScrollY = twoFingerDownY
                lastSpread = pointerSpread(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrolling) {
                    val y = averageY(event)
                    val spread = pointerSpread(event)
                    val dSpread = spread - lastSpread
                    val dy = y - lastScrollY
                    // Pinch is interactive — emit each frame. Vertical motion is only used
                    // to capture the flick velocity for auto-scroll; the actual scrolling
                    // starts when the user lifts.
                    if (
                        spread > MIN_PINCH_SPREAD_PX && lastSpread > MIN_PINCH_SPREAD_PX &&
                        abs(dSpread) > abs(dy) * PINCH_BIAS
                    ) {
                        onZoom?.invoke(spread / lastSpread)
                    }
                    twoFingerLastY = y
                    twoFingerLastTime = event.eventTime
                    lastScrollY = y
                    lastSpread = spread
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
                    if (warmupActive) {
                        warmupDx += dx
                        warmupDy += dy
                        val warmupOver =
                            event.eventTime - downTime > WARMUP_MS ||
                                abs(warmupDx) > WARMUP_DISTANCE_PX ||
                                abs(warmupDy) > WARMUP_DISTANCE_PX
                        if (warmupOver) {
                            if (width > 0) {
                                val fx = warmupDx / width
                                val fy = warmupDy / width
                                onMove?.invoke(fx, fy)
                                cursorEmittedDx += fx
                                cursorEmittedDy += fy
                            }
                            warmupActive = false
                            warmupDx = 0f
                            warmupDy = 0f
                        }
                    } else if (width > 0) {
                        val fx = dx / width
                        val fy = dy / width
                        onMove?.invoke(fx, fy)
                        cursorEmittedDx += fx
                        cursorEmittedDy += fy
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Going from 2 fingers back down to 1 — finalize the two-finger gesture.
                if (scrolling && !twoFingerStartedDuringAutoScroll) {
                    maybeStartAutoScroll()
                }
                // Re-average over the fingers that remain, so the next move does not jump.
                lastScrollY = averageY(event, lifting = event.actionIndex)
                lastSpread = pointerSpread(event, lifting = event.actionIndex)
                scrolling = false
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
                scrolling = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                endDragIfActive()
                scrolling = false
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

    /** Distance between the first two active pointers; 0 if only one pointer remains. */
    private fun pointerSpread(event: MotionEvent, lifting: Int = -1): Float {
        val indices = (0 until event.pointerCount).filter { it != lifting }
        if (indices.size < 2) return 0f
        val a = indices[0]; val b = indices[1]
        val dx = event.getX(a) - event.getX(b)
        val dy = event.getY(a) - event.getY(b)
        return sqrt(dx * dx + dy * dy)
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

        /** Below this finger spread (pixels) a pinch reading is too jittery — fall back to scroll. */
        const val MIN_PINCH_SPREAD_PX = 50f

        /** Spread-change must outpace centroid-change by this factor to register as a pinch. */
        const val PINCH_BIAS = 1.2f

        /** Auto-scroll tick interval — 60 Hz, matches typical display refresh. */
        const val AUTO_SCROLL_TICK_MS = 16

        /** Hold one-finger cursor moves for this long before flushing — swallows the brief
         *  one-finger window before a two-finger gesture's second finger lands. */
        const val WARMUP_MS = 30L

        /** ...unless the finger has moved this far first, in which case flush early. */
        const val WARMUP_DISTANCE_PX = 8f

        /** Below this total vertical travel during the gesture, no auto-scroll starts. */
        const val MIN_FLICK_PX = 20

        /** Pad-fractions per millisecond, capped so a super-fast flick stays sane. */
        const val MAX_AUTO_SCROLL_PER_MS = 0.01f
    }
}
