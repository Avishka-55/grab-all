package com.graball

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight, pro-grade right-edge draggable Fast Scroller for RecyclerView.
 *
 * - Wide invisible 34dp touch grab zone on the right edge so thumbs catch it
 *   effortlessly without accidentally clicking song rows.
 * - Sleek rounded pill handle stays subtly visible on long lists (>= 12 items),
 *   lights up in GrabAll orange when scrolling or grabbed, and hides on short lists.
 * - Floating preview pill bubble to the left of the handle while dragging
 *   (shows song initial / position like "M · 142" or "A").
 * - Zero external dependencies, zero per-frame allocations, API 21-34 compatible.
 */
class FastScrollerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val spScale = resources.displayMetrics.scaledDensity

    private val trackWidthPx = 2.5f * density
    private val thumbIdleWidthPx = 5.5f * density
    private val thumbDragWidthPx = 8f * density
    private val thumbIdleHeightPx = 46f * density
    private val thumbDragHeightPx = 54f * density
    private val rightMarginPx = 3f * density
    private val verticalPaddingPx = 8f * density
    private val grabZoneWidthPx = 34f * density

    private val bubbleHeightPx = 36f * density
    private val bubblePadHPx = 14f * density
    private val bubbleGapPx = 14f * density
    private val bubbleCornerPx = 18f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6B2C")
        style = Paint.Style.FILL
    }
    private val thumbIdlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 107, 44)
        style = Paint.Style.FILL
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(242, 255, 107, 44)
        style = Paint.Style.FILL
    }
    private val bubbleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * spScale
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val trackRect = RectF()
    private val thumbRect = RectF()
    private val bubbleRect = RectF()
    private val bubbleShadowRect = RectF()

    private var recyclerView: RecyclerView? = null
    private var labelProvider: ((position: Int, total: Int) -> String)? = null

    private var scrollFraction = 0f
    private var isDragging = false
    private var isActive = false
    private var isScrollable = false
    private var currentBubbleLabel = ""

    private val fadeIdleRunnable = Runnable {
        if (!isDragging) {
            isActive = false
            invalidate()
        }
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (!isDragging) {
                syncFromRecycler(rv, activate = dy != 0)
            }
        }
    }

    fun attachTo(
        rv: RecyclerView,
        labelProvider: (position: Int, total: Int) -> String
    ) {
        recyclerView?.removeOnScrollListener(scrollListener)
        recyclerView = rv
        this.labelProvider = labelProvider
        rv.addOnScrollListener(scrollListener)
        refresh()
    }

    /** Call after adapter data changes (tab switch, search filter, library reload). */
    fun refresh() {
        val rv = recyclerView ?: return
        rv.post {
            syncFromRecycler(rv, activate = false)
        }
    }

    private fun syncFromRecycler(rv: RecyclerView, activate: Boolean) {
        val count = rv.adapter?.itemCount ?: 0
        val range = rv.computeVerticalScrollRange()
        val extent = rv.computeVerticalScrollExtent()
        val maxScroll = (range - extent).coerceAtLeast(0)
        val shouldShow = count >= 12 && maxScroll > extent / 4
        isScrollable = shouldShow
        if (!shouldShow) {
            isDragging = false
            isActive = false
            visibility = GONE
            return
        }
        if (visibility != VISIBLE) {
            visibility = VISIBLE
        }
        val offset = rv.computeVerticalScrollOffset()
        scrollFraction = if (maxScroll > 0) {
            (offset.toFloat() / maxScroll.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        if (activate) {
            isActive = true
            removeCallbacks(fadeIdleRunnable)
            postDelayed(fadeIdleRunnable, 1400L)
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isScrollable) return false
        val rv = recyclerView ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x < width - grabZoneWidthPx) {
                    return false
                }
                isDragging = true
                isActive = true
                removeCallbacks(fadeIdleRunnable)
                parent?.requestDisallowInterceptTouchEvent(true)
                rv.stopScroll()
                scrubToY(event.y, rv)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                scrubToY(event.y, rv)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return false
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                removeCallbacks(fadeIdleRunnable)
                postDelayed(fadeIdleRunnable, 1200L)
                invalidate()
                return true
            }
        }
        return false
    }

    private fun scrubToY(touchY: Float, rv: RecyclerView) {
        val thumbH = thumbDragHeightPx
        val topBound = verticalPaddingPx + thumbH / 2f
        val bottomBound = (height - verticalPaddingPx - thumbH / 2f).coerceAtLeast(topBound + 1f)
        val frac = ((touchY - topBound) / (bottomBound - topBound)).coerceIn(0f, 1f)
        scrollFraction = frac

        val total = rv.adapter?.itemCount ?: 0
        if (total <= 0) return
        val targetPos = ((total - 1) * frac).toInt().coerceIn(0, total - 1)
        val lm = rv.layoutManager as? LinearLayoutManager
        if (lm != null) {
            lm.scrollToPositionWithOffset(targetPos, 0)
        } else {
            rv.scrollToPosition(targetPos)
        }
        currentBubbleLabel = labelProvider?.invoke(targetPos, total).orEmpty()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isScrollable || height <= 0 || width <= 0) return

        val thumbW = if (isDragging) thumbDragWidthPx else thumbIdleWidthPx
        val thumbH = if (isDragging) thumbDragHeightPx else thumbIdleHeightPx
        val usableTop = verticalPaddingPx
        val usableBottom = (height - verticalPaddingPx).coerceAtLeast(usableTop + thumbH)
        val travel = (usableBottom - usableTop - thumbH).coerceAtLeast(0f)

        val thumbTop = usableTop + travel * scrollFraction
        val thumbRight = width - rightMarginPx
        val thumbLeft = thumbRight - thumbW
        val thumbCenterX = (thumbLeft + thumbRight) / 2f
        val thumbCenterY = thumbTop + thumbH / 2f

        // Subtle track guide while scrolling or dragging
        if (isActive || isDragging) {
            trackRect.set(
                thumbCenterX - trackWidthPx / 2f,
                usableTop,
                thumbCenterX + trackWidthPx / 2f,
                usableBottom
            )
            canvas.drawRoundRect(trackRect, trackWidthPx / 2f, trackWidthPx / 2f, trackPaint)
        }

        // Sleek rounded thumb pill
        thumbRect.set(thumbLeft, thumbTop, thumbRight, thumbTop + thumbH)
        val radius = thumbW / 2f
        val paint = if (isActive || isDragging) thumbPaint else thumbIdlePaint
        canvas.drawRoundRect(thumbRect, radius, radius, paint)

        // Floating section preview bubble while dragging
        if (isDragging && currentBubbleLabel.isNotBlank()) {
            val textWidth = bubbleTextPaint.measureText(currentBubbleLabel)
            val bubbleW = max(bubbleHeightPx, textWidth + bubblePadHPx * 2f)
            val bubbleRight = thumbLeft - bubbleGapPx
            val bubbleLeft = (bubbleRight - bubbleW).coerceAtLeast(8f * density)
            val bubbleTop = (thumbCenterY - bubbleHeightPx / 2f)
                .coerceIn(usableTop, (usableBottom - bubbleHeightPx).coerceAtLeast(usableTop))
            val bubbleBottom = bubbleTop + bubbleHeightPx

            bubbleShadowRect.set(
                bubbleLeft,
                bubbleTop + 2f * density,
                bubbleRight,
                bubbleBottom + 2f * density
            )
            canvas.drawRoundRect(bubbleShadowRect, bubbleCornerPx, bubbleCornerPx, bubbleShadowPaint)

            bubbleRect.set(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom)
            canvas.drawRoundRect(bubbleRect, bubbleCornerPx, bubbleCornerPx, bubblePaint)

            val fm = bubbleTextPaint.fontMetrics
            val textY = (bubbleTop + bubbleBottom) / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(currentBubbleLabel, (bubbleLeft + bubbleRight) / 2f, textY, bubbleTextPaint)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(fadeIdleRunnable)
        recyclerView?.removeOnScrollListener(scrollListener)
        super.onDetachedFromWindow()
    }
}
