package com.graball

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Lightweight, zero-permission mirrored sound-wave view (Option A).
 *
 * Renders two layered translucent Gaussian-modulated sinusoidal ribbons across
 * the album-art/backdrop transition seam:
 * - Zero object allocations inside onDraw()
 * - Throttled to ~30fps ONLY while actively playing & visible
 * - Gently flattens and completely stops ticking when paused/hidden (0% idle CPU)
 */
class AudioWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val primaryPath = Path()
    private val secondaryPath = Path()
    private val upperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val lowerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val secondaryUpperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val secondaryLowerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var baseColor: Int = Color.parseColor("#FFE8C8")
    private var playing: Boolean = false
    private var amplitude: Float = 0.06f
    private var phase: Float = 0f
    private var tickScheduled: Boolean = false

    private val ticker = Runnable {
        tickScheduled = false
        if (!isAttachedToWindow || visibility != VISIBLE) return@Runnable
        val targetAmp = if (playing) 1.0f else 0.06f
        amplitude += (targetAmp - amplitude) * 0.14f
        if (playing) {
            phase += 0.15f
            if (phase > 1000f) phase -= 1000f
        } else if (kotlin.math.abs(amplitude - targetAmp) < 0.01f) {
            amplitude = targetAmp
            invalidate()
            return@Runnable // Fully settled -> stop scheduling frames (0% idle CPU)
        }
        invalidate()
        scheduleNextTick()
    }

    init {
        applyTint(baseColor)
    }

    fun setWaveColor(color: Int) {
        if (baseColor == color) return
        baseColor = color
        applyTint(color)
        invalidate()
    }

    fun setPlaying(isPlaying: Boolean) {
        if (playing == isPlaying && (isPlaying == tickScheduled)) return
        playing = isPlaying
        if (isAttachedToWindow && visibility == VISIBLE) {
            scheduleNextTick()
        }
    }

    private fun applyTint(color: Int) {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        upperPaint.color = Color.argb(135, r, g, b)
        lowerPaint.color = Color.argb(65, r, g, b)
        secondaryUpperPaint.color = Color.argb(80, r, g, b)
        secondaryLowerPaint.color = Color.argb(40, r, g, b)
    }

    private fun scheduleNextTick() {
        if (tickScheduled) return
        tickScheduled = true
        postDelayed(ticker, 33L) // ~30 fps cap
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playing) scheduleNextTick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        tickScheduled = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && playing) {
            scheduleNextTick()
        } else if (visibility != VISIBLE) {
            removeCallbacks(ticker)
            tickScheduled = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val midY = h * 0.54f
        val maxPeak = h * 0.46f * amplitude

        // Secondary softer wave (slightly phase-shifted for depth)
        drawMirroredWave(canvas, w, midY, maxPeak * 0.72f, phase * 0.78f + 1.4f, secondaryPath, secondaryUpperPaint, secondaryLowerPaint)
        // Primary brighter wave (3 organic hills: left, tall center, right)
        drawMirroredWave(canvas, w, midY, maxPeak, phase, primaryPath, upperPaint, lowerPaint)
    }

    private fun drawMirroredWave(
        canvas: Canvas,
        w: Float,
        midY: Float,
        maxPeak: Float,
        ph: Float,
        path: Path,
        topPaint: Paint,
        bottomPaint: Paint
    ) {
        val steps = 40
        // Upper ribbon
        path.reset()
        path.moveTo(0f, midY)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = t * w
            val yOff = sampleHeight(t, ph) * maxPeak
            path.lineTo(x, midY - yOff)
        }
        path.lineTo(w, midY)
        path.close()
        canvas.drawPath(path, topPaint)

        // Mirrored lower reflection (slightly gentler)
        path.reset()
        path.moveTo(0f, midY)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = t * w
            val yOff = sampleHeight(t, ph) * maxPeak * 0.65f
            path.lineTo(x, midY + yOff)
        }
        path.lineTo(w, midY)
        path.close()
        canvas.drawPath(path, bottomPaint)
    }

    /** 3-lobe smooth organic envelope matching the screenshot's horizon wave. */
    private fun sampleHeight(t: Float, ph: Float): Float {
        // Taper smoothly to 0 at left/right edges
        val edge = sin(t * PI).toFloat().coerceAtLeast(0f)
        // Three Gaussian hills at x = 0.17, 0.48, 0.79 modulated gently in time
        val h1 = gaussian(t, 0.17f, 0.085f) * (0.42f + 0.28f * sin(ph * 1.15f + 0.5f))
        val h2 = gaussian(t, 0.48f, 0.105f) * (0.68f + 0.32f * sin(ph * 0.92f))
        val h3 = gaussian(t, 0.79f, 0.085f) * (0.45f + 0.28f * sin(ph * 1.32f + 2.1f))
        return ((h1 + h2 + h3) * edge).coerceIn(0f, 1f)
    }

    private fun gaussian(x: Float, mean: Float, sigma: Float): Float {
        val d = (x - mean) / sigma
        return exp(-0.5f * d * d)
    }
}
