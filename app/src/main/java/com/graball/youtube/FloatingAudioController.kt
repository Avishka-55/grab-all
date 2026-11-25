package com.graball.youtube

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.graball.R
import kotlin.math.abs

/**
 * Draggable edge-snapped Floating Audio Mini-Control (in-app overlay, zero
 * special Android permissions needed).
 *
 * - Appears automatically whenever background / audio-only playback is active
 *   outside [YouTubePlayerActivity].
 * - Collapsed: 52dp circular track artwork with a live progress ring snapped
 *   magnetically to the left or right edge (draggable vertically & horizontally).
 * - Expanded (on tap): compact dark floating pill with scrolling marquee title,
 *   Play/Pause, Next, and Close controls. Tapping outside collapses it back.
 */
class FloatingAudioController(private val activity: Activity) {

    private val dp = activity.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())

    private var rootOverlay: FrameLayout? = null
    private var backdrop: View? = null
    private var bubbleFrame: FrameLayout? = null
    private var bubbleThumb: ImageView? = null
    private var bubbleRing: CircularProgressRingView? = null
    private var pillRow: LinearLayout? = null
    private var pillThumb: ImageView? = null
    private var pillTitle: TextView? = null
    private var pillPlayPause: TextView? = null

    private var expanded = false
    private var attached = false
    private var lastThumbUrl: String? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            if (!attached) return
            val svc = YouTubePlaybackService.instance
            if (svc != null && YouTubePlaybackService.currentState.currentItem != null) {
                bubbleRing?.setProgress(svc.progressFraction())
            }
            handler.postDelayed(this, 600L)
        }
    }

    private val listener: (YouTubePlaybackState) -> Unit = { state ->
        updateState(state)
    }

    fun attach() {
        if (attached) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val overlay = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            clipChildren = false
            clipToPadding = false
        }

        val dim = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener { setExpanded(false) }
        }
        overlay.addView(dim)
        backdrop = dim

        val bubbleSize = (54 * dp).toInt()
        val thumbSize = (46 * dp).toInt()
        val bubble = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
            elevation = 10 * dp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE181828.toInt())
            }
            visibility = View.GONE
        }

        val thumb = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(thumbSize, thumbSize, Gravity.CENTER)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            setImageResource(R.drawable.yt_chip_bg)
        }
        val ring = CircularProgressRingView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize, Gravity.CENTER)
        }
        bubble.addView(thumb)
        bubble.addView(ring)
        setupBubbleDrag(bubble, overlay)
        overlay.addView(bubble)
        bubbleFrame = bubble
        bubbleThumb = thumb
        bubbleRing = ring

        val pill = buildExpandedPill()
        overlay.addView(pill)
        pillRow = pill

        content.addView(overlay)
        rootOverlay = overlay
        attached = true

        overlay.post {
            positionBubbleFromSaved(overlay)
        }
        YouTubePlaybackService.addListener(listener)
        handler.post(progressTicker)
    }

    fun detach() {
        if (!attached) return
        attached = false
        handler.removeCallbacks(progressTicker)
        YouTubePlaybackService.removeListener(listener)
        val overlay = rootOverlay
        if (overlay != null) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        rootOverlay = null
        backdrop = null
        bubbleFrame = null
        bubbleThumb = null
        bubbleRing = null
        pillRow = null
        pillThumb = null
        pillTitle = null
        pillPlayPause = null
        expanded = false
    }

    private fun buildExpandedPill(): LinearLayout {
        val pillHeight = (48 * dp).toInt()
        val pill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                pillHeight
            )
            elevation = 12 * dp
            setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * dp
                setColor(0xF21A1A2E.toInt())
                setStroke((1 * dp).toInt(), 0x44FFFFFF)
            }
            visibility = View.GONE
        }

        val thumb = ImageView(activity).apply {
            val sz = (36 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (8 * dp).toInt()
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            setOnClickListener { openFullPlayer() }
        }
        pill.addView(thumb)
        pillThumb = thumb

        val title = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams((128 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            setOnClickListener { openFullPlayer() }
        }
        pill.addView(title)
        pillTitle = title

        fun iconBtn(symbol: String, onClick: () -> Unit): TextView {
            val sz = (34 * dp).toInt()
            return TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginStart = (2 * dp).toInt()
                }
                gravity = Gravity.CENTER
                text = symbol
                textSize = 15f
                setTextColor(Color.WHITE)
                setOnClickListener { onClick() }
            }
        }

        val playPause = iconBtn("⏸") {
            YouTubePlaybackService.instance?.togglePlayPause()
        }
        pillPlayPause = playPause
        pill.addView(playPause)

        pill.addView(
            iconBtn("⏭") {
                YouTubePlaybackService.instance?.playNext()
            }
        )
        pill.addView(
            iconBtn("✕") {
                setExpanded(false)
                YouTubePlaybackService.instance?.stopPlayback()
            }.apply { setTextColor(0xFFB8B8CC.toInt()) }
        )

        return pill
    }

    private fun setupBubbleDrag(bubble: FrameLayout, parent: FrameLayout) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false

        bubble.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = v.x
                    startY = v.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val maxX = (parent.width - v.width).coerceAtLeast(0).toFloat()
                        val minY = (56 * dp)
                        val maxY = (parent.height - v.height - (86 * dp)).coerceAtLeast(minY)
                        v.x = (startX + dx).coerceIn(0f, maxX)
                        v.y = (startY + dy).coerceIn(minY, maxY)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val margin = (8 * dp)
                        val maxX = (parent.width - v.width - margin).coerceAtLeast(margin)
                        val snapRight = (v.x + v.width / 2f) >= (parent.width / 2f)
                        savedSnapRight = snapRight
                        if (parent.height > 0) {
                            savedYFraction = (v.y / parent.height.toFloat()).coerceIn(0.12f, 0.78f)
                        }
                        val targetX = if (snapRight) maxX else margin
                        v.animate().x(targetX).setDuration(160L).start()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        setExpanded(!expanded)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun positionBubbleFromSaved(parent: FrameLayout) {
        val bubble = bubbleFrame ?: return
        if (parent.width <= 0 || parent.height <= 0) return
        val margin = (8 * dp)
        val bSize = bubble.layoutParams.width
        val targetX = if (savedSnapRight) {
            (parent.width - bSize - margin).coerceAtLeast(margin)
        } else {
            margin
        }
        val minY = (56 * dp)
        val maxY = (parent.height - bSize - (86 * dp)).coerceAtLeast(minY)
        val targetY = (parent.height * savedYFraction).coerceIn(minY, maxY)
        bubble.x = targetX
        bubble.y = targetY
    }

    private fun setExpanded(showPill: Boolean) {
        expanded = showPill
        val parent = rootOverlay ?: return
        val bubble = bubbleFrame ?: return
        val pill = pillRow ?: return
        val dim = backdrop ?: return

        if (!showPill) {
            pill.visibility = View.GONE
            dim.visibility = View.GONE
            if (shouldShowBubble(YouTubePlaybackService.currentState)) {
                bubble.visibility = View.VISIBLE
            }
            return
        }

        bubble.visibility = View.GONE
        dim.visibility = View.VISIBLE
        pill.visibility = View.VISIBLE
        pill.measure(
            View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.AT_MOST)
        )
        val margin = (10 * dp)
        val pillW = pill.measuredWidth
        val targetX = if (savedSnapRight) {
            (parent.width - pillW - margin).coerceAtLeast(margin)
        } else {
            margin
        }
        val targetY = bubble.y.coerceIn(56 * dp, (parent.height - pill.measuredHeight - 86 * dp).coerceAtLeast(56 * dp))
        pill.x = targetX
        pill.y = targetY
        pillTitle?.isSelected = true
    }

    private fun shouldShowBubble(state: YouTubePlaybackState): Boolean {
        return state.currentItem != null && (state.isAudioOnly || state.isVideoDisabled)
    }

    private fun updateState(state: YouTubePlaybackState) {
        if (!attached) return
        val item = state.currentItem
        if (!shouldShowBubble(state) || item == null) {
            expanded = false
            bubbleFrame?.visibility = View.GONE
            pillRow?.visibility = View.GONE
            backdrop?.visibility = View.GONE
            return
        }

        pillTitle?.text = item.title
        pillPlayPause?.text = if (state.isPlaying) "⏸" else "▶"
        if (item.thumbnailUrl.isNotBlank() && item.thumbnailUrl != lastThumbUrl) {
            lastThumbUrl = item.thumbnailUrl
            bubbleThumb?.let { ThumbnailLoader.load(item.thumbnailUrl, it, targetWidthPx = 160) }
            pillThumb?.let { ThumbnailLoader.load(item.thumbnailUrl, it, targetWidthPx = 160) }
        }
        YouTubePlaybackService.instance?.let { svc ->
            bubbleRing?.setProgress(svc.progressFraction())
        }

        if (!expanded) {
            bubbleFrame?.visibility = View.VISIBLE
            pillRow?.visibility = View.GONE
            backdrop?.visibility = View.GONE
        }
    }

    private fun openFullPlayer() {
        setExpanded(false)
        activity.startActivity(
            Intent(activity, YouTubePlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    companion object {
        private var savedSnapRight: Boolean = true
        private var savedYFraction: Float = 0.62f
    }
}
