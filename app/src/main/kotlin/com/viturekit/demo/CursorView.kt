package com.viturekit.demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.viturekit.model.EulerAngles
import kotlin.math.hypot

/**
 * A full-screen view that turns head orientation into an on-screen cursor.
 *
 * Yaw steers the cursor left/right, pitch steers it up/down. Holding the cursor over one of
 * the ring targets for [DWELL_MILLIS] selects it — a hands-free "click". This is the visual
 * proof that VitureKit's IMU `Flow` is delivering usable, low-latency orientation.
 *
 * All mutating methods must be called on the main thread.
 */
class CursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Called on the main thread each time a target is selected, with the running tally. */
    var onTargetActivated: ((activated: Int, total: Int) -> Unit)? = null

    private class Target(val fractionX: Float, val fractionY: Float, var activated: Boolean = false)

    private val targets = listOf(
        Target(0.20f, 0.32f), Target(0.50f, 0.24f), Target(0.80f, 0.32f),
        Target(0.28f, 0.74f), Target(0.50f, 0.82f), Target(0.72f, 0.72f),
    )

    /** Total number of targets in the scene. */
    val targetCount: Int get() = targets.size

    /** How many targets have been selected so far. */
    val activatedCount: Int get() = targets.count { it.activated }

    private val density = resources.displayMetrics.density
    private val targetRadius = 36f * density
    private val cursorRadius = 16f * density
    private val gridStep = 64f * density

    private var cursorX = 0f
    private var cursorY = 0f
    private var tracking = false

    private var hoveredIndex = -1
    private var dwellStartNanos = 0L
    private var dwellProgress = 0f

    private val arcRect = RectF()

    private val gridPaint = strokePaint("#16213F", 1f)
    private val targetIdlePaint = strokePaint("#3A4569", 3f)
    private val targetHoverPaint = strokePaint("#00E5FF", 3f)
    private val dwellPaint = strokePaint("#00E5FF", 5f).apply { strokeCap = Paint.Cap.ROUND }
    private val cursorStrokePaint = strokePaint("#00E5FF", 3f).apply { strokeCap = Paint.Cap.ROUND }
    private val targetDonePaint = fillPaint("#2BD66A")
    private val targetDoneCorePaint = fillPaint("#0B1021")
    private val cursorCorePaint = fillPaint("#00E5FF")
    private val cursorGlowPaint = fillPaint("#3300E5FF")

    /** Feed a fresh head orientation; updates the cursor and any dwell-selection in progress. */
    fun updateOrientation(euler: EulerAngles) {
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val normalizedYaw = (euler.yawDeg / YAW_RANGE_DEG).coerceIn(-1f, 1f)
        val normalizedPitch = (euler.pitchDeg / PITCH_RANGE_DEG).coerceIn(-1f, 1f)
        cursorX = halfWidth + normalizedYaw * halfWidth * EDGE_MARGIN
        // Pitch up (positive) should move the cursor up the screen (smaller y).
        cursorY = halfHeight - normalizedPitch * halfHeight * EDGE_MARGIN
        tracking = true
        updateDwell()
        invalidate()
    }

    /** Hide the cursor — call when tracking stops (disconnected / IMU off). */
    fun clearCursor() {
        tracking = false
        hoveredIndex = -1
        dwellProgress = 0f
        invalidate()
    }

    /** Mark every target unselected again. */
    fun resetTargets() {
        targets.forEach { it.activated = false }
        hoveredIndex = -1
        dwellProgress = 0f
        invalidate()
    }

    private fun updateDwell() {
        val now = System.nanoTime()
        val index = targetIndexUnderCursor()
        if (index < 0 || targets[index].activated) {
            hoveredIndex = -1
            dwellProgress = 0f
            return
        }
        if (index != hoveredIndex) {
            hoveredIndex = index
            dwellStartNanos = now
            dwellProgress = 0f
            return
        }
        val elapsedMillis = (now - dwellStartNanos) / 1_000_000.0
        dwellProgress = (elapsedMillis / DWELL_MILLIS).toFloat().coerceIn(0f, 1f)
        if (dwellProgress >= 1f) {
            targets[index].activated = true
            hoveredIndex = -1
            dwellProgress = 0f
            onTargetActivated?.invoke(activatedCount, targets.size)
        }
    }

    private fun targetIndexUnderCursor(): Int {
        targets.forEachIndexed { index, target ->
            val centerX = target.fractionX * width
            val centerY = target.fractionY * height
            if (hypot(cursorX - centerX, cursorY - centerY) <= targetRadius) {
                return index
            }
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        var x = gridStep
        while (x < viewWidth) {
            canvas.drawLine(x, 0f, x, viewHeight, gridPaint)
            x += gridStep
        }
        var y = gridStep
        while (y < viewHeight) {
            canvas.drawLine(0f, y, viewWidth, y, gridPaint)
            y += gridStep
        }

        targets.forEachIndexed { index, target ->
            val centerX = target.fractionX * viewWidth
            val centerY = target.fractionY * viewHeight
            when {
                target.activated -> {
                    canvas.drawCircle(centerX, centerY, targetRadius, targetDonePaint)
                    canvas.drawCircle(centerX, centerY, targetRadius * 0.34f, targetDoneCorePaint)
                }
                index == hoveredIndex -> {
                    canvas.drawCircle(centerX, centerY, targetRadius, targetHoverPaint)
                    arcRect.set(
                        centerX - targetRadius, centerY - targetRadius,
                        centerX + targetRadius, centerY + targetRadius,
                    )
                    canvas.drawArc(arcRect, START_ANGLE, SWEEP_FULL * dwellProgress, false, dwellPaint)
                }
                else -> canvas.drawCircle(centerX, centerY, targetRadius, targetIdlePaint)
            }
        }

        if (tracking) {
            canvas.drawCircle(cursorX, cursorY, cursorRadius * 1.9f, cursorGlowPaint)
            canvas.drawCircle(cursorX, cursorY, cursorRadius, cursorStrokePaint)
            val outer = cursorRadius * 1.8f
            val inner = cursorRadius * 0.55f
            canvas.drawLine(cursorX - outer, cursorY, cursorX - inner, cursorY, cursorStrokePaint)
            canvas.drawLine(cursorX + inner, cursorY, cursorX + outer, cursorY, cursorStrokePaint)
            canvas.drawLine(cursorX, cursorY - outer, cursorX, cursorY - inner, cursorStrokePaint)
            canvas.drawLine(cursorX, cursorY + inner, cursorX, cursorY + outer, cursorStrokePaint)
            canvas.drawCircle(cursorX, cursorY, cursorRadius * 0.22f, cursorCorePaint)
        }
    }

    private fun strokePaint(color: String, widthDp: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = Color.parseColor(color)
            strokeWidth = widthDp * density
        }

    private fun fillPaint(color: String): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = Color.parseColor(color)
        }

    private companion object {
        /** Yaw at which the cursor reaches the screen edge, in degrees. */
        const val YAW_RANGE_DEG = 35f

        /** Pitch at which the cursor reaches the screen edge, in degrees. */
        const val PITCH_RANGE_DEG = 22f

        /** Keeps the cursor a little inside the physical screen edge. */
        const val EDGE_MARGIN = 0.92f

        /** Dwell time needed to select a target, in milliseconds. */
        const val DWELL_MILLIS = 750.0

        const val START_ANGLE = -90f
        const val SWEEP_FULL = 360f
    }
}
