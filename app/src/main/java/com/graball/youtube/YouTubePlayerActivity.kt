package com.graball.youtube

import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.graball.R
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Full NewPipe-style ad-free YouTube video & background audio player.
 *
 * Features:
 * - Hardware-accelerated Media3 ExoPlayer (zero ads)
 * - Gesture controls: double-tap left/right (±10s), vertical swipe left
 *   (brightness) / right (volume), single tap to toggle controls
 * - Native Android Picture-in-Picture (API 26+) with Play/Pause & Next controls
 *   in the PiP window; minimizes cleanly to in-app mini-player on API 21-25
 * - True battery-saver Screen-Off / BG Audio mode: disables video track so
 *   ExoPlayer stops downloading & decoding video frames when screen is off
 * - Quality picker, playback speed (0.5x-2.0x), SponsorBlock toggle, local
 *   bookmarks, 1-tap Download Video/MP3, and Up Next related videos
 */
@OptIn(UnstableApi::class)
class YouTubePlayerActivity : AppCompatActivity() {

    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var audioOnlyBanner: TextView
    private lateinit var gestureHud: TextView
    private lateinit var playerLoadingBox: LinearLayout
    private lateinit var playerLoadingText: TextView
    private lateinit var playerTopOverlay: LinearLayout
    private lateinit var detailsContainer: LinearLayout

    private lateinit var videoTitle: TextView
    private lateinit var videoMeta: TextView
    private lateinit var playerErrorRow: LinearLayout
    private lateinit var videoError: TextView
    private lateinit var actionBgAudio: TextView
    private lateinit var actionQuality: TextView
    private lateinit var actionSpeed: TextView
    private lateinit var actionCaptions: TextView
    private lateinit var actionBookmark: TextView
    private lateinit var actionSponsorBlock: TextView
    private lateinit var relatedList: RecyclerView
    private lateinit var relatedAdapter: YtItemAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var lastRelatedVideoId: String? = null
    private var lastSponsorSkipShown: String? = null
    private var isFullscreen = false
    private var inPipMode = false
    private var explicitBgAudioRequested = false
    private var autoDisabledVideoForScreenOff = false
    private var normalPlayerHeightPx = 0

    private val hideHudRunnable = Runnable {
        gestureHud.visibility = View.GONE
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_PLAY_PAUSE -> YouTubePlaybackService.instance?.togglePlayPause()
                ACTION_PIP_NEXT -> YouTubePlaybackService.instance?.playNext()
            }
        }
    }

    private val stateListener: (YouTubePlaybackState) -> Unit = { state ->
        bindState(state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_player)

        normalPlayerHeightPx = (225 * resources.displayMetrics.density).toInt()
        playerContainer = findViewById(R.id.playerContainer)
        playerView = findViewById(R.id.ytPlayerView)
        audioOnlyBanner = findViewById(R.id.audioOnlyBanner)
        gestureHud = findViewById(R.id.gestureHud)
        playerLoadingBox = findViewById(R.id.playerLoadingBox)
        playerLoadingText = findViewById(R.id.playerLoadingText)
        playerTopOverlay = findViewById(R.id.playerTopOverlay)
        detailsContainer = findViewById(R.id.detailsContainer)

        videoTitle = findViewById(R.id.playerVideoTitle)
        videoMeta = findViewById(R.id.playerVideoMeta)
        playerErrorRow = findViewById(R.id.playerErrorRow)
        videoError = findViewById(R.id.playerError)
        findViewById<TextView>(R.id.btnPlayerRetry).setOnClickListener {
            YouTubePlaybackService.instance?.retryCurrent()
        }
        actionBgAudio = findViewById(R.id.actionBgAudio)
        actionQuality = findViewById(R.id.actionQuality)
        actionSpeed = findViewById(R.id.actionSpeed)
        actionCaptions = findViewById(R.id.actionCaptions)
        actionBookmark = findViewById(R.id.actionBookmark)
        actionSponsorBlock = findViewById(R.id.actionSponsorBlock)
        relatedList = findViewById(R.id.relatedList)

        findViewById<TextView>(R.id.btnPlayerBack).setOnClickListener {
            if (isFullscreen) {
                toggleFullscreen()
            } else {
                navigateBackCleanly()
            }
        }
        findViewById<TextView>(R.id.btnTopPip).setOnClickListener { enterPipOrMinimize() }
        findViewById<TextView>(R.id.actionPip).setOnClickListener { enterPipOrMinimize() }
        findViewById<TextView>(R.id.btnFullscreen).setOnClickListener { toggleFullscreen() }

        findViewById<TextView>(R.id.actionDownload).setOnClickListener {
            val item = YouTubePlaybackService.currentState.currentItem
            if (item != null) {
                DownloadSheetHelper.show(this, item)
            }
        }

        actionBgAudio.setOnClickListener {
            val svc = YouTubePlaybackService.instance ?: return@setOnClickListener
            val next = !YouTubePlaybackService.currentState.isAudioOnly
            svc.setAudioOnlyMode(next)
            if (next) {
                explicitBgAudioRequested = true
                Toast.makeText(
                    this,
                    "Background Audio ON — keeps playing even with screen off",
                    Toast.LENGTH_SHORT
                ).show()
                moveTaskToBack(false)
            }
        }

        actionQuality.setOnClickListener { showQualityPicker() }
        actionSpeed.setOnClickListener { showSpeedPicker() }
        actionCaptions.setOnClickListener {
            val svc = YouTubePlaybackService.instance ?: return@setOnClickListener
            val next = !YouTubePlaybackService.currentState.captionsEnabled
            svc.setCaptionsEnabled(next)
            showHud(if (next) "CC: ON" else "CC: OFF")
        }

        actionBookmark.setOnClickListener {
            val item = YouTubePlaybackService.currentState.currentItem ?: return@setOnClickListener
            val now = YouTubeStore.toggleBookmark(this, item)
            updateBookmarkChip(now)
            Toast.makeText(
                this,
                if (now) "Saved to bookmarks" else "Removed from bookmarks",
                Toast.LENGTH_SHORT
            ).show()
        }

        actionSponsorBlock.setOnClickListener {
            val next = !YouTubeStore.isSponsorBlockEnabled(this)
            YouTubeStore.setSponsorBlockEnabled(this, next)
            updateSponsorBlockChip(next)
            Toast.makeText(
                this,
                if (next) "SponsorBlock enabled" else "SponsorBlock disabled",
                Toast.LENGTH_SHORT
            ).show()
        }
        updateSponsorBlockChip(YouTubeStore.isSponsorBlockEnabled(this))

        relatedAdapter = YtItemAdapter(
            context = this,
            onClick = { item, _ ->
                YouTubePlaybackService.withService(this) { svc ->
                    svc.playItem(item, audioOnly = YouTubePlaybackService.currentState.isAudioOnly)
                }
            },
            onDownload = { item -> DownloadSheetHelper.show(this, item) },
            onQueue = { item, _ ->
                YouTubePlaybackService.withService(this) { svc ->
                    svc.addToQueue(item)
                }
                Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show()
            }
        )
        relatedList.layoutManager = LinearLayoutManager(this)
        relatedList.adapter = relatedAdapter

        setupGestures()
        registerPipReceiver()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("restore_video", false)) {
            intent.removeExtra("restore_video")
            YouTubePlaybackService.instance?.setAudioOnlyMode(false)
        }
    }

    override fun onStart() {
        super.onStart()
        explicitBgAudioRequested = false
        val svc = YouTubePlaybackService.instance
        if (svc != null) {
            playerView.player = svc.getPlayer()
            val switchToVideo = intent?.getBooleanExtra("restore_video", false) == true
            if (switchToVideo) {
                intent?.removeExtra("restore_video")
            }
            if (autoDisabledVideoForScreenOff || switchToVideo) {
                autoDisabledVideoForScreenOff = false
                svc.setAudioOnlyMode(false)
            }
        }
        YouTubePlaybackService.addListener(stateListener)
    }

    override fun onStop() {
        YouTubePlaybackService.removeListener(stateListener)
        playerView.player = null
        // Battery saver: when app is backgrounded or screen locks (and NOT in PiP
        // or rotating), disable video track so ExoPlayer streams audio-only!
        if (!inPipMode && !isChangingConfigurations &&
            YouTubeStore.isScreenOffAudioOnly(this)
        ) {
            val svc = YouTubePlaybackService.instance
            if (svc != null && !YouTubePlaybackService.currentState.isAudioOnly) {
                autoDisabledVideoForScreenOff = true
                svc.setAudioOnlyMode(true)
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(hideHudRunnable)
        try {
            unregisterReceiver(pipReceiver)
        } catch (_: Exception) {
        }
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val state = YouTubePlaybackService.currentState
        if (!explicitBgAudioRequested &&
            state.isPlaying &&
            !state.isAudioOnly &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipMode = isInPictureInPictureMode
        applyChromeVisibility()
        if (!isInPictureInPictureMode && YouTubePlaybackService.instance != null) {
            playerView.player = YouTubePlaybackService.instance?.getPlayer()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!inPipMode) {
            val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (landscape != isFullscreen) {
                isFullscreen = landscape
                applyChromeVisibility()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isFullscreen) {
            toggleFullscreen()
        } else {
            navigateBackCleanly()
        }
    }

    /** Never trap the user or exit to launcher if this player became task root. */
    private fun navigateBackCleanly() {
        if (isTaskRoot) {
            startActivity(
                Intent(this, com.graball.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }
        finish()
    }

    // ---- State Binding ----

    private fun bindState(state: YouTubePlaybackState) {
        val svc = YouTubePlaybackService.instance
        if (svc != null && playerView.player == null) {
            playerView.player = svc.getPlayer()
        }

        playerLoadingBox.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        if (state.isLoading) {
            playerLoadingText.text = state.loadingStatus ?: "Loading…"
        }
        audioOnlyBanner.visibility =
            if (state.isAudioOnly && !inPipMode && !state.isLoading) View.VISIBLE else View.GONE

        val item = state.currentItem
        val info = state.streamInfo
        videoTitle.text = info?.title ?: item?.title ?: "Nothing playing"
        val metaParts = listOf(
            info?.channel?.takeIf { it.isNotBlank() } ?: item?.channel.orEmpty(),
            info?.viewCount?.takeIf { it.isNotBlank() } ?: item?.views.orEmpty()
        ).filter { it.isNotBlank() }
        videoMeta.text = metaParts.joinToString(" · ")

        if (!state.error.isNullOrBlank()) {
            videoError.text = state.error
            playerErrorRow.visibility = View.VISIBLE
        } else {
            playerErrorRow.visibility = View.GONE
        }

        actionQuality.text = if (state.isAudioOnly) {
            "⚙ Audio"
        } else if (state.selectedHeight != null) {
            "⚙ ${state.selectedHeight}p"
        } else {
            "⚙ Auto"
        }

        actionSpeed.text = "${trimSpeed(state.playbackSpeed)}×"

        actionBgAudio.setBackgroundResource(
            if (state.isAudioOnly) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg
        )
        actionCaptions.setBackgroundResource(
            if (state.captionsEnabled) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg
        )
        actionCaptions.setTextColor(
            ContextCompat.getColor(
                this,
                if (state.captionsEnabled) R.color.graball_text else R.color.graball_subtext
            )
        )

        if (item != null) {
            updateBookmarkChip(YouTubeStore.isBookmarked(this, item.id))
            if (item.id != lastRelatedVideoId) {
                lastRelatedVideoId = item.id
                loadRelated(item.id)
            }
        }

        val skipMsg = state.lastSponsorSkip
        if (skipMsg != null && skipMsg != lastSponsorSkipShown) {
            lastSponsorSkipShown = skipMsg
            showHud("⏭ $skipMsg")
        }

        if (inPipMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                setPictureInPictureParams(buildPipParams(state.isPlaying))
            } catch (_: Exception) {
            }
        }
    }

    private fun loadRelated(videoId: String) {
        io.execute {
            val items = YouTubeInnerTube.fetchRelated(videoId)
            runOnUiThread {
                if (!isFinishing) {
                    relatedAdapter.submitList(items, videoId)
                }
            }
        }
    }

    // ---- PiP & Fullscreen ----

    private fun enterPipOrMinimize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipMode()
        } else {
            Toast.makeText(
                this,
                "Continuing in mini-player (PiP requires Android 8+)",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            // Ensure video track is active when entering PiP
            val svc = YouTubePlaybackService.instance
            if (svc != null && YouTubePlaybackService.currentState.isAudioOnly) {
                svc.setAudioOnlyMode(false)
            }
            enterPictureInPictureMode(
                buildPipParams(YouTubePlaybackService.currentState.isPlaying)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "PiP unavailable on this device", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(isPlaying: Boolean): PictureInPictureParams {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val playPauseIntent = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName), flags
        )
        val nextIntent = PendingIntent.getBroadcast(
            this, 2, Intent(ACTION_PIP_NEXT).setPackage(packageName), flags
        )
        val playPauseIcon = Icon.createWithResource(
            this,
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        val nextIcon = Icon.createWithResource(this, android.R.drawable.ic_media_next)
        val actions = listOf(
            RemoteAction(
                playPauseIcon,
                if (isPlaying) "Pause" else "Play",
                if (isPlaying) "Pause" else "Play",
                playPauseIntent
            ),
            RemoteAction(nextIcon, "Next", "Next video", nextIntent)
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)
            .build()
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        requestedOrientation = if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        applyChromeVisibility()
    }

    @Suppress("DEPRECATION")
    private fun applyChromeVisibility() {
        val hideDetails = inPipMode || isFullscreen
        detailsContainer.visibility = if (hideDetails) View.GONE else View.VISIBLE
        playerTopOverlay.visibility = if (inPipMode) View.GONE else View.VISIBLE
        playerView.useController = !inPipMode

        val params = playerContainer.layoutParams
        params.height = if (hideDetails) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            normalPlayerHeightPx
        }
        playerContainer.layoutParams = params

        if (isFullscreen && !inPipMode) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // ---- Gesture Controls ----
    // Portrait mode (like YouTube):
    //   - Drag DOWN -> Picture-in-Picture (or mini-player on Android 5-7)
    //   - Drag UP   -> Fullscreen landscape
    // Fullscreen mode ONLY:
    //   - Left vertical drag  -> Screen brightness
    //   - Right vertical drag -> Media volume
    // Both modes:
    //   - Double-tap left/right -> Seek -10s / +10s
    //   - Single tap            -> Show/hide controls

    private fun setupGestures() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val portraitSwipeThresholdPx = 72f * resources.displayMetrics.density

        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (inPipMode) return false
                    val isRight = e.x > playerContainer.width / 2f
                    val delta = if (isRight) 10_000L else -10_000L
                    YouTubePlaybackService.instance?.seekBy(delta)
                    showHud(if (isRight) "10s »" else "« 10s")
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (inPipMode) return false
                    if (playerView.isControllerFullyVisible) {
                        playerView.hideController()
                    } else {
                        playerView.showController()
                    }
                    return true
                }
            }
        )

        var downX = 0f
        var downY = 0f
        var lastDy = 0f
        var draggingVertical = false
        var isLeftSide = true
        var startBrightness = 0.5f
        var startVolume = 0
        var maxVolume = 15

        playerView.setOnTouchListener { _, event ->
            if (inPipMode) return@setOnTouchListener false
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastDy = 0f
                    draggingVertical = false
                    isLeftSide = event.x < playerContainer.width / 2f
                    val curB = window.attributes.screenBrightness
                    startBrightness = if (curB in 0f..1f) curB else 0.5f
                    startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = downY - event.y // upward swipe = positive, downward = negative
                    if (!draggingVertical && abs(dy) > touchSlop * 2 && abs(dy) > abs(dx) * 1.4f) {
                        draggingVertical = true
                    }
                    if (draggingVertical) {
                        lastDy = dy
                        if (isFullscreen) {
                            // Fullscreen ONLY: left = brightness, right = volume
                            val range = (playerContainer.height * 0.75f).coerceAtLeast(120f)
                            val fraction = dy / range
                            if (isLeftSide) {
                                val nextB = (startBrightness + fraction).coerceIn(0.05f, 1.0f)
                                val attrs = window.attributes
                                attrs.screenBrightness = nextB
                                window.attributes = attrs
                                showHud("☀ ${(nextB * 100).toInt()}%")
                            } else {
                                val nextVol = (startVolume + (fraction * maxVolume).toInt())
                                    .coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                                val pct = (nextVol * 100) / maxVolume
                                showHud("🔊 $pct%")
                            }
                        } else {
                            // Portrait mode: drag down for PiP, drag up for Fullscreen
                            if (dy < -portraitSwipeThresholdPx * 0.5f) {
                                showHud(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "↓ Release for PiP"
                                    else "↓ Release to minimize"
                                )
                            } else if (dy > portraitSwipeThresholdPx * 0.5f) {
                                showHud("↑ Release for Fullscreen")
                            }
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val consumed = draggingVertical
                    if (draggingVertical && !isFullscreen && event.actionMasked == MotionEvent.ACTION_UP) {
                        if (lastDy < -portraitSwipeThresholdPx) {
                            enterPipOrMinimize()
                        } else if (lastDy > portraitSwipeThresholdPx) {
                            toggleFullscreen()
                        }
                    }
                    draggingVertical = false
                    lastDy = 0f
                    consumed
                }
                else -> false
            }
        }
    }

    private fun showHud(text: String) {
        gestureHud.text = text
        gestureHud.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.postDelayed(hideHudRunnable, 900L)
    }

    // ---- Quality & Speed Pickers ----

    private fun showQualityPicker() {
        val svc = YouTubePlaybackService.instance ?: return
        val heights = YouTubePlaybackService.currentState.streamInfo?.availableHeights()
            ?.ifEmpty { listOf(1080, 720, 480, 360) }
            ?: listOf(1080, 720, 480, 360)
        val labels = mutableListOf("Auto", "Audio only (battery saver)")
        heights.forEach { labels.add("${it}p") }

        AlertDialog.Builder(this)
            .setTitle("Video Quality")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        svc.setAudioOnlyMode(false)
                        svc.setQuality(null)
                    }
                    1 -> svc.setAudioOnlyMode(true)
                    else -> {
                        svc.setAudioOnlyMode(false)
                        svc.setQuality(heights[which - 2])
                    }
                }
            }
            .show()
    }

    private fun showSpeedPicker() {
        val svc = YouTubePlaybackService.instance ?: return
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val labels = speeds.map { "${trimSpeed(it)}×" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setItems(labels) { _, which ->
                svc.setSpeed(speeds[which])
            }
            .show()
    }

    private fun updateBookmarkChip(bookmarked: Boolean) {
        actionBookmark.text = if (bookmarked) "★ Saved" else "☆ Save"
        actionBookmark.setTextColor(
            ContextCompat.getColor(
                this,
                if (bookmarked) R.color.graball_orange else R.color.graball_text
            )
        )
    }

    private fun updateSponsorBlockChip(enabled: Boolean) {
        actionSponsorBlock.text = if (enabled) "SB: ON" else "SB: OFF"
        actionSponsorBlock.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.graball_active else R.color.graball_subtext
            )
        )
    }

    private fun trimSpeed(speed: Float): String {
        return if (speed == speed.toInt().toFloat()) {
            speed.toInt().toString()
        } else {
            speed.toString()
        }
    }

    private fun registerPipReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_PLAY_PAUSE)
            addAction(ACTION_PIP_NEXT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(pipReceiver, filter)
        }
    }

    companion object {
        private const val ACTION_PIP_PLAY_PAUSE = "com.graball.youtube.PIP_PLAY_PAUSE"
        private const val ACTION_PIP_NEXT = "com.graball.youtube.PIP_NEXT"
    }
}
