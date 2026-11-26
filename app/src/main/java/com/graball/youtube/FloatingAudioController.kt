package com.graball.youtube

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.graball.PlayerActivity
import com.graball.PlayerService
import com.graball.R
import kotlin.math.abs

/**
 * Unified, performance-friendly Draggable Floating Audio Mini-Control
 * (in-app overlay — works for BOTH YouTube Audio-Only and Local Music Player
 * with zero special Android overlay permissions).
 *
 * Performance guarantees:
 * - Lazy view creation: zero views are inflated/added when no audio is active.
 * - Zero idle CPU: progress ring ticker (1s) and GPU-composited disc rotation
 *   run ONLY while audio is actively playing and the bubble is visible; both
 *   pause/stop immediately on pause, stop, expand, or activity pause.
 */
class FloatingAudioController(
    private val activity: Activity,
    private val onlyWhenExplicitAudioOnly: Boolean = false
) {

    private enum class Source { NONE, YOUTUBE, LOCAL }

    private data class ActiveAudio(
        val source: Source,
        val title: String,
        val isPlaying: Boolean,
        val progress: Float,
        val thumbUrl: String? = null,
        val artBitmap: Bitmap? = null
    )

    private val dp = activity.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())

    private var rootOverlay: FrameLayout? = null
    private var backdrop: View? = null
    private var bubbleFrame: FrameLayout? = null
    private var bubbleDisc: FrameLayout? = null
    private var bubbleFallbackIcon: TextView? = null
    private var bubbleThumb: ImageView? = null
    private var bubbleRing: CircularProgressRingView? = null
    private var pillRow: LinearLayout? = null
    private var pillFallbackIcon: TextView? = null
    private var pillThumb: ImageView? = null
    private var pillTitle: TextView? = null
    private var pillPlayPause: TextView? = null

    private var spinAnimator: ObjectAnimator? = null
    private var activeSource: Source = Source.NONE
    private var expanded = false
    private var attached = false
    private var tickerRunning = false
    private var lastThumbUrl: String? = null
    private var lastArtBitmap: Bitmap? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            if (!attached || !tickerRunning) return
            val audio = resolveActiveAudio()
            if (audio == null || !audio.isPlaying || expanded) {
                tickerRunning = false
                return
            }
            bubbleRing?.setProgress(audio.progress)
            handler.postDelayed(this, 1000L)
        }
    }

    private val ytListener: (YouTubePlaybackState) -> Unit = {
        refreshFromServices()
    }

    private val localListener: () -> Unit = {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshFromServices()
        } else {
            handler.post { refreshFromServices() }
        }
    }

    fun attach() {
        if (attached) return
        attached = true
        YouTubePlaybackService.addListener(ytListener)
        if (!onlyWhenExplicitAudioOnly) {
            PlayerService.addStateListener(localListener)
        }
        refreshFromServices()
    }

    /**
     * Smoothly launches the circular floating bubble directly out of [anchorView]
     * (e.g. the "🎧 Audio" button) and glides it to its snapped screen edge.
     */
    fun animateLaunchFrom(anchorView: View) {
        if (!attached) attach()
        refreshFromServices()
        val overlay = rootOverlay ?: return
        val bubble = bubbleFrame ?: return
        expanded = false
        pillRow?.visibility = View.GONE
        backdrop?.visibility = View.GONE
        bubble.visibility = View.VISIBLE

        overlay.post {
            if (!attached || rootOverlay !== overlay || bubbleFrame !== bubble) return@post
            if (overlay.width <= 0 || overlay.height <= 0) return@post
            val (targetX, targetY) = computeSavedBubbleTarget(overlay, bubble.layoutParams.width)
            val overlayLoc = IntArray(2)
            val anchorLoc = IntArray(2)
            overlay.getLocationInWindow(overlayLoc)
            anchorView.getLocationInWindow(anchorLoc)
            val bSize = bubble.layoutParams.width
            val startX = (anchorLoc[0] - overlayLoc[0]) + (anchorView.width - bSize) / 2f
            val startY = (anchorLoc[1] - overlayLoc[1]) + (anchorView.height - bSize) / 2f

            bubble.animate().cancel()
            bubble.x = startX
            bubble.y = startY
            bubble.scaleX = 0.25f
            bubble.scaleY = 0.25f
            bubble.alpha = 0.2f
            bubble.animate()
                .x(targetX)
                .y(targetY)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(340L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        }
    }

    fun detach() {
        if (!attached) return
        attached = false
        stopTicker()
        stopSpin(resetRotation = true)
        YouTubePlaybackService.removeListener(ytListener)
        PlayerService.removeStateListener(localListener)
        val overlay = rootOverlay
        if (overlay != null) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        rootOverlay = null
        backdrop = null
        bubbleFrame = null
        bubbleDisc = null
        bubbleFallbackIcon = null
        bubbleThumb = null
        bubbleRing = null
        pillRow = null
        pillFallbackIcon = null
        pillThumb = null
        pillTitle = null
        pillPlayPause = null
        lastThumbUrl = null
        lastArtBitmap = null
        activeSource = Source.NONE
        expanded = false
    }

    private fun ensureViewsCreated(): Boolean {
        if (rootOverlay != null) return true
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false

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

        val circleOutline = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }

        val disc = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(thumbSize, thumbSize, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF23233A.toInt())
            }
            outlineProvider = circleOutline
            clipToOutline = true
        }

        val fallbackNote = TextView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            text = "♫"
            textSize = 18f
            setTextColor(0xFFFF6B35.toInt())
        }

        val thumb = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        disc.addView(fallbackNote)
        disc.addView(thumb)

        val ring = CircularProgressRingView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize, Gravity.CENTER)
        }
        bubble.addView(disc)
        bubble.addView(ring)
        setupBubbleDrag(bubble, overlay)
        overlay.addView(bubble)
        bubbleFrame = bubble
        bubbleDisc = disc
        bubbleFallbackIcon = fallbackNote
        bubbleThumb = thumb
        bubbleRing = ring

        val pill = buildExpandedPill(circleOutline)
        overlay.addView(pill)
        pillRow = pill

        content.addView(overlay)
        rootOverlay = overlay

        overlay.post {
            positionBubbleFromSaved(overlay)
        }
        return true
    }

    private fun buildExpandedPill(circleOutline: ViewOutlineProvider): LinearLayout {
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

        val sz = (36 * dp).toInt()
        val artHolder = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (8 * dp).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF23233A.toInt())
            }
            outlineProvider = circleOutline
            clipToOutline = true
            setOnClickListener { openFullPlayer() }
        }
        val pillFallback = TextView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            text = "♫"
            textSize = 15f
            setTextColor(0xFFFF6B35.toInt())
        }
        val thumb = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        artHolder.addView(pillFallback)
        artHolder.addView(thumb)
        pill.addView(artHolder)
        pillFallbackIcon = pillFallback
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
            val btnSz = (34 * dp).toInt()
            return TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(btnSz, btnSz).apply {
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
            when (activeSource) {
                Source.YOUTUBE -> YouTubePlaybackService.instance?.togglePlayPause()
                Source.LOCAL -> PlayerService.instance?.togglePlayPause()
                Source.NONE -> Unit
            }
        }
        pillPlayPause = playPause
        pill.addView(playPause)

        pill.addView(
            iconBtn("⏭") {
                when (activeSource) {
                    Source.YOUTUBE -> YouTubePlaybackService.instance?.playNext()
                    Source.LOCAL -> PlayerService.instance?.playNext()
                    Source.NONE -> Unit
                }
            }
        )
        pill.addView(
            iconBtn("✕") {
                setExpanded(false)
                when (activeSource) {
                    Source.YOUTUBE -> YouTubePlaybackService.instance?.stopPlayback()
                    Source.LOCAL -> PlayerService.instance?.stopPlayback()
                    Source.NONE -> Unit
                }
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

    private fun computeSavedBubbleTarget(parent: FrameLayout, bSize: Int): Pair<Float, Float> {
        val margin = (8 * dp)
        val targetX = if (savedSnapRight) {
            (parent.width - bSize - margin).coerceAtLeast(margin)
        } else {
            margin
        }
        val minY = (56 * dp)
        val maxY = (parent.height - bSize - (86 * dp)).coerceAtLeast(minY)
        val targetY = (parent.height * savedYFraction).coerceIn(minY, maxY)
        return targetX to targetY
    }

    private fun positionBubbleFromSaved(parent: FrameLayout) {
        val bubble = bubbleFrame ?: return
        if (parent.width <= 0 || parent.height <= 0) return
        val (targetX, targetY) = computeSavedBubbleTarget(parent, bubble.layoutParams.width)
        bubble.x = targetX
        bubble.y = targetY
    }

    private fun setExpanded(showPill: Boolean) {
        expanded = showPill
        val parent = rootOverlay ?: return
        val bubble = bubbleFrame ?: return
        val pill = pillRow ?: return
        val dim = backdrop ?: return
        val audio = resolveActiveAudio()

        if (!showPill) {
            pill.visibility = View.GONE
            dim.visibility = View.GONE
            if (audio != null) {
                bubble.visibility = View.VISIBLE
                syncAnimations(audio.isPlaying)
            } else {
                stopTicker()
                stopSpin(resetRotation = false)
            }
            return
        }

        stopTicker()
        stopSpin(resetRotation = false)
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
        val targetY = bubble.y.coerceIn(
            56 * dp,
            (parent.height - pill.measuredHeight - 86 * dp).coerceAtLeast(56 * dp)
        )
        pill.x = targetX
        pill.y = targetY
        pillTitle?.isSelected = true
    }

    private fun resolveActiveAudio(): ActiveAudio? {
        val ytState = YouTubePlaybackService.currentState
        val ytItem = ytState.currentItem
        val ytQualifies = if (onlyWhenExplicitAudioOnly) {
            ytItem != null && ytState.isAudioOnly
        } else {
            ytItem != null && (ytState.isAudioOnly || ytState.isVideoDisabled)
        }
        if (ytQualifies && ytItem != null) {
            return ActiveAudio(
                source = Source.YOUTUBE,
                title = ytItem.title,
                isPlaying = ytState.isPlaying,
                progress = YouTubePlaybackService.instance?.progressFraction() ?: 0f,
                thumbUrl = ytItem.thumbnailUrl.takeIf { it.isNotBlank() }
            )
        }
        if (onlyWhenExplicitAudioOnly) return null
        val localSvc = PlayerService.instance
        if (localSvc != null && localSvc.hasLoadedTrack()) {
            return ActiveAudio(
                source = Source.LOCAL,
                title = localSvc.displayTitle(),
                isPlaying = localSvc.isPlaying(),
                progress = localSvc.progressFraction(),
                artBitmap = localSvc.currentArtBitmap()?.takeIf { !it.isRecycled }
            )
        }
        return null
    }

    private fun refreshFromServices() {
        if (!attached) return
        val audio = resolveActiveAudio()
        if (audio == null) {
            activeSource = Source.NONE
            expanded = false
            stopTicker()
            stopSpin(resetRotation = true)
            bubbleFrame?.visibility = View.GONE
            pillRow?.visibility = View.GONE
            backdrop?.visibility = View.GONE
            return
        }

        if (!ensureViewsCreated()) return
        activeSource = audio.source

        pillTitle?.text = audio.title
        pillPlayPause?.text = if (audio.isPlaying) "⏸" else "▶"
        bubbleRing?.setProgress(audio.progress)
        bindArtwork(audio)

        if (!expanded) {
            bubbleFrame?.visibility = View.VISIBLE
            pillRow?.visibility = View.GONE
            backdrop?.visibility = View.GONE
            syncAnimations(audio.isPlaying)
        } else {
            stopTicker()
            stopSpin(resetRotation = false)
        }
    }

    private fun bindArtwork(audio: ActiveAudio) {
        val bThumb = bubbleThumb ?: return
        val pThumb = pillThumb ?: return
        when (audio.source) {
            Source.YOUTUBE -> {
                lastArtBitmap = null
                val url = audio.thumbUrl
                if (!url.isNullOrBlank()) {
                    bThumb.visibility = View.VISIBLE
                    pThumb.visibility = View.VISIBLE
                    bubbleFallbackIcon?.visibility = View.GONE
                    pillFallbackIcon?.visibility = View.GONE
                    if (url != lastThumbUrl) {
                        lastThumbUrl = url
                        ThumbnailLoader.load(url, bThumb, targetWidthPx = 144)
                        ThumbnailLoader.load(url, pThumb, targetWidthPx = 144)
                    }
                } else {
                    lastThumbUrl = null
                    showFallbackDisc(bThumb, pThumb)
                }
            }
            Source.LOCAL -> {
                lastThumbUrl = null
                val bmp = audio.artBitmap
                if (bmp != null && !bmp.isRecycled) {
                    bThumb.visibility = View.VISIBLE
                    pThumb.visibility = View.VISIBLE
                    bubbleFallbackIcon?.visibility = View.GONE
                    pillFallbackIcon?.visibility = View.GONE
                    if (bmp !== lastArtBitmap) {
                        lastArtBitmap = bmp
                        bThumb.tag = null
                        pThumb.tag = null
                        bThumb.setImageBitmap(bmp)
                        pThumb.setImageBitmap(bmp)
                    }
                } else {
                    lastArtBitmap = null
                    showFallbackDisc(bThumb, pThumb)
                }
            }
            Source.NONE -> Unit
        }
    }

    private fun showFallbackDisc(bThumb: ImageView, pThumb: ImageView) {
        bThumb.setImageDrawable(null)
        pThumb.setImageDrawable(null)
        bThumb.visibility = View.GONE
        pThumb.visibility = View.GONE
        bubbleFallbackIcon?.visibility = View.VISIBLE
        pillFallbackIcon?.visibility = View.VISIBLE
    }

    private fun syncAnimations(isPlaying: Boolean) {
        if (isPlaying && !expanded) {
            startTicker()
            startSpin()
        } else {
            stopTicker()
            pauseSpin()
        }
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        handler.removeCallbacks(progressTicker)
        handler.postDelayed(progressTicker, 1000L)
    }

    private fun stopTicker() {
        tickerRunning = false
        handler.removeCallbacks(progressTicker)
    }

    private fun startSpin() {
        val disc = bubbleDisc ?: return
        disc.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val existing = spinAnimator
        if (existing != null && existing.target === disc) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && existing.isPaused) {
                existing.resume()
            } else if (!existing.isRunning) {
                existing.start()
            }
            return
        }
        spinAnimator?.cancel()
        spinAnimator = ObjectAnimator.ofFloat(disc, View.ROTATION, disc.rotation, disc.rotation + 360f).apply {
            duration = 16_000L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
    }

    private fun pauseSpin() {
        val anim = spinAnimator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && anim.isRunning && !anim.isPaused) {
            anim.pause()
        } else {
            anim.cancel()
            spinAnimator = null
        }
        bubbleDisc?.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private fun stopSpin(resetRotation: Boolean) {
        spinAnimator?.cancel()
        spinAnimator = null
        bubbleDisc?.let { disc ->
            disc.setLayerType(View.LAYER_TYPE_NONE, null)
            if (resetRotation) disc.rotation = 0f
        }
    }

    private fun openFullPlayer() {
        val src = activeSource
        setExpanded(false)
        when (src) {
            Source.YOUTUBE -> {
                if (activity is YouTubePlayerActivity) {
                    YouTubePlaybackService.instance?.setAudioOnlyMode(false)
                } else {
                    activity.startActivity(
                        Intent(activity, YouTubePlayerActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                    )
                }
            }
            Source.LOCAL -> {
                activity.startActivity(
                    Intent(activity, PlayerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("open_now_playing", true)
                    }
                )
            }
            Source.NONE -> Unit
        }
    }

    companion object {
        private var savedSnapRight: Boolean = true
        private var savedYFraction: Float = 0.62f
    }
}
