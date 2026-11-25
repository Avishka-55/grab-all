package com.graball.youtube

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Lightweight circular progress ring drawn around the floating Audio bubble.
 */
class CircularProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val strokePx = 2.6f * resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = 0x44FFFFFF
    }
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokePx
        color = 0xFFFF6B35.toInt()
    }
    private val oval = RectF()
    private var progress: Float = 0f

    fun setProgress(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (kotlin.math.abs(clamped - progress) >= 0.004f || clamped == 0f) {
            progress = clamped
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = strokePx / 2f + 1f
        oval.set(half, half, width - half, height - half)
        canvas.drawOval(oval, bgPaint)
        if (progress > 0f) {
            canvas.drawArc(oval, -90f, 360f * progress, false, fgPaint)
        }
    }
}
