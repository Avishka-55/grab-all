package com.graball.youtube

import android.app.AlertDialog
import android.app.Dialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.graball.BrowseActivity
import com.graball.DarkBottomSheet
import com.graball.GrabAllApp
import com.graball.LoginActivity
import com.graball.R
import com.graball.SleepTimerManager
import com.graball.engine.DownloadProgressBus
import com.graball.engine.WebViewCookieSync
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
    private lateinit var subtitleOverlay: TextView
    private lateinit var holdSpeedPill: TextView
    private lateinit var btnScreenUnlock: TextView
    private lateinit var gestureHud: TextView
    private lateinit var playerLoadingBox: LinearLayout
    private lateinit var playerLoadingText: TextView
    private lateinit var playerTopOverlay: LinearLayout
    private lateinit var btnTopCaptions: TextView
    private lateinit var btnTopAudio: TextView
    private lateinit var btnTopQuality: TextView
    private lateinit var btnTopAspect: TextView
    private lateinit var btnTopLock: TextView
    private lateinit var detailsContainer: LinearLayout

    private lateinit var videoTitle: TextView
    private lateinit var videoMeta: TextView
    private lateinit var descChevron: TextView
    private lateinit var videoDescription: TextView
    private lateinit var chaptersRow: HorizontalScrollView
    private lateinit var chaptersContainer: LinearLayout
    private lateinit var channelAvatar: ImageView
    private lateinit var channelNameView: TextView
    private lateinit var channelSubsView: TextView
    private lateinit var commentsCard: LinearLayout
    private lateinit var commentsPreview: TextView
    private lateinit var playerErrorRow: LinearLayout
    private lateinit var videoError: TextView
    private lateinit var actionLike: TextView
    private lateinit var actionSubscribe: TextView
    private lateinit var actionComments: TextView
    private lateinit var actionOpenWeb: TextView
    private lateinit var actionBgAudio: TextView
    private lateinit var actionSpeed: TextView
    private lateinit var actionSleepTimer: TextView
    private lateinit var actionShare: TextView
    private lateinit var actionBookmark: TextView
    private lateinit var actionSponsorBlock: TextView
    private lateinit var relatedList: RecyclerView
    private lateinit var relatedAdapter: YtItemAdapter
    private lateinit var floatingAudio: FloatingAudioController

    private data class VideoChapter(val timeMs: Long, val timeLabel: String, val title: String)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private var lastSeenNetGen = GrabAllApp.networkGeneration
    private var lastRelatedVideoId: String? = null
    private var lastChaptersDescKey: String? = null
    private var currentEngagement: YouTubeInnerTube.VideoEngagement? = null
    private var lastSponsorSkipShown: String? = null
    private var lastChannelAvatarUrl: String? = null
    private var descriptionExpanded = false
    private var isFullscreen = false
    private var isScreenLocked = false
    private var isHolding2x = false
    private var inPipMode = false
    private var exitingPipForAudioOnly = false
    private var isControllerVisible = false
    private var explicitBgAudioRequested = false
    private var normalPlayerHeightPx = 0
    private var fullscreenResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    private val hideHudRunnable = Runnable {
        gestureHud.visibility = View.GONE
    }

    private val hideUnlockRunnable = Runnable {
        if (::btnScreenUnlock.isInitialized) {
            btnScreenUnlock.visibility = View.GONE
        }
    }

    private val holdSpeedRunnable = Runnable {
        if (isScreenLocked || inPipMode) return@Runnable
        val svc = YouTubePlaybackService.instance ?: return@Runnable
        val st = YouTubePlaybackService.currentState
        if (!svc.isReadyForSeek() || st.isAudioOnly) return@Runnable
        isHolding2x = true
        svc.setTemporarySpeed(2.0f)
        holdSpeedPill.visibility = View.VISIBLE
        playerView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private val sleepTimerListener: (SleepTimerManager.State) -> Unit = { st ->
        if (::actionSleepTimer.isInitialized) {
            actionSleepTimer.text = st.shortChipLabel()
            actionSleepTimer.setBackgroundResource(
                if (st.isActive) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg
            )
            actionSleepTimer.setTextColor(
                ContextCompat.getColor(this, R.color.graball_text)
            )
        }
    }

    private var subtitleTickerRunning = false
    private val subtitleTicker = object : Runnable {
        override fun run() {
            if (!subtitleTickerRunning) return
            renderSubtitleOverlay()
            val state = YouTubePlaybackService.currentState
            if (!state.captionsEnabled || !state.isPlaying || inPipMode) {
                subtitleTickerRunning = false
                return
            }
            mainHandler.postDelayed(this, 150L)
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_AUDIO_ONLY -> {
                    exitingPipForAudioOnly = true
                    explicitBgAudioRequested = true
                    YouTubePlaybackService.instance?.setAudioOnlyMode(true)
                    Toast.makeText(
                        this@YouTubePlayerActivity,
                        "🎧 Continuing in Audio Only mode",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        finishAndRemoveTask()
                    } else {
                        finish()
                    }
                }
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
        subtitleOverlay = findViewById(R.id.ytSubtitleOverlay)
        holdSpeedPill = findViewById(R.id.holdSpeedPill)
        btnScreenUnlock = findViewById(R.id.btnScreenUnlock)
        gestureHud = findViewById(R.id.gestureHud)
        playerLoadingBox = findViewById(R.id.playerLoadingBox)
        playerLoadingText = findViewById(R.id.playerLoadingText)
        playerTopOverlay = findViewById(R.id.playerTopOverlay)
        btnTopCaptions = findViewById(R.id.btnTopCaptions)
        btnTopAudio = findViewById(R.id.btnTopAudio)
        btnTopQuality = findViewById(R.id.btnTopQuality)
        btnTopAspect = findViewById(R.id.btnTopAspect)
        btnTopLock = findViewById(R.id.btnTopLock)
        detailsContainer = findViewById(R.id.detailsContainer)

        videoTitle = findViewById(R.id.playerVideoTitle)
        videoMeta = findViewById(R.id.playerVideoMeta)
        descChevron = findViewById(R.id.playerDescChevron)
        videoDescription = findViewById(R.id.playerDescription)
        videoDescription.movementMethod = LinkMovementMethod.getInstance()
        videoDescription.highlightColor = Color.TRANSPARENT
        chaptersRow = findViewById(R.id.playerChaptersRow)
        chaptersContainer = findViewById(R.id.playerChaptersContainer)
        channelAvatar = findViewById(R.id.playerChannelAvatar)
        channelNameView = findViewById(R.id.playerChannelName)
        channelSubsView = findViewById(R.id.playerChannelSubs)
        commentsCard = findViewById(R.id.playerCommentsCard)
        commentsPreview = findViewById(R.id.playerCommentsPreview)
        playerErrorRow = findViewById(R.id.playerErrorRow)
        videoError = findViewById(R.id.playerError)

        channelAvatar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        channelAvatar.clipToOutline = true

        findViewById<View>(R.id.playerTitleRow).setOnClickListener {
            val desc = YouTubePlaybackService.currentState.streamInfo?.description.orEmpty().trim()
            if (desc.isBlank()) return@setOnClickListener
            descriptionExpanded = !descriptionExpanded
            videoDescription.text = if (descriptionExpanded) buildClickableDescription(desc) else ""
            videoDescription.visibility = if (descriptionExpanded) View.VISIBLE else View.GONE
            descChevron.text = if (descriptionExpanded) "⌃" else "⌄"
        }
        findViewById<View>(R.id.playerChannelInfo).setOnClickListener {
            openChannelPage()
        }
        findViewById<TextView>(R.id.btnPlayerRetry).setOnClickListener {
            YouTubePlaybackService.instance?.retryCurrent()
        }
        actionLike = findViewById(R.id.actionLike)
        actionSubscribe = findViewById(R.id.actionSubscribe)
        actionComments = findViewById(R.id.actionComments)
        actionOpenWeb = findViewById(R.id.actionOpenWeb)
        actionBgAudio = findViewById(R.id.actionBgAudio)
        actionSpeed = findViewById(R.id.actionSpeed)
        actionSleepTimer = findViewById(R.id.actionSleepTimer)
        actionShare = findViewById(R.id.actionShare)
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
        findViewById<TextView>(R.id.btnFullscreen).setOnClickListener { toggleFullscreen() }
        btnTopQuality.setOnClickListener { showQualityPicker() }
        btnTopCaptions.setOnClickListener { showCaptionsPicker() }
        btnTopAudio.setOnClickListener { showAudioTrackPicker() }
        btnTopAspect.setOnClickListener { cycleAspectRatio() }
        btnTopLock.setOnClickListener { lockFullscreen() }
        btnScreenUnlock.setOnClickListener { unlockFullscreen(showFeedback = true) }
        // Disable PlayerView's own tap-to-toggle so our GestureDetector is the
        // single source of truth (prevents the double-toggle bug where tapping
        // to hide immediately re-shows controls!).
        playerView.controllerHideOnTouch = false
        playerView.controllerAutoShow = false
        playerView.controllerShowTimeoutMs = 3500
        // Keep the top overlay (Back / CC / Quality / PiP / Fullscreen) synced with
        // ExoPlayer's controls in BOTH portrait & fullscreen so it auto-hides cleanly
        // during playback and reappears on tap.
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                isControllerVisible = (visibility == View.VISIBLE)
                playerTopOverlay.visibility = if (inPipMode) View.GONE else visibility
                updateAudioOnlyBannerVisibility(YouTubePlaybackService.currentState)
                updateSubtitleOverlayPosition()
            }
        )

        findViewById<TextView>(R.id.actionDownload).setOnClickListener {
            val item = YouTubePlaybackService.currentState.currentItem
            if (item != null) {
                DownloadSheetHelper.show(this, item)
            }
        }

        actionLike.setOnClickListener { toggleLike() }
        actionSubscribe.setOnClickListener { toggleSubscribe() }
        commentsCard.setOnClickListener { showCommentsDialog() }
        actionComments.setOnClickListener { showCommentsDialog() }
        actionOpenWeb.setOnClickListener { openCurrentVideoInWeb() }

        actionBgAudio.setOnClickListener {
            val svc = YouTubePlaybackService.instance ?: return@setOnClickListener
            val next = !YouTubePlaybackService.currentState.isAudioOnly
            explicitBgAudioRequested = next
            svc.setAudioOnlyMode(next)
            if (next) {
                if (!inPipMode && !isFullscreen) {
                    floatingAudio.animateLaunchFrom(actionBgAudio)
                }
                showHud("🎧 Audio Mode ON")
            } else {
                showHud("Video resumed")
            }
        }

        actionSpeed.setOnClickListener { showSpeedPicker() }
        actionSleepTimer.setOnClickListener {
            SleepTimerManager.showPickerDialog(this) {
                if (isFullscreen && !inPipMode) applyChromeVisibility()
            }
        }
        actionShare.setOnClickListener { shareCurrentVideo() }

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
            },
            onChannelClick = { item ->
                if (item.channelId.isNotBlank()) {
                    startActivity(
                        YouTubeChannelActivity.createIntent(
                            context = this,
                            channelId = item.channelId,
                            channelName = item.channel,
                            channelAvatar = item.channelAvatarUrl
                        )
                    )
                }
            }
        )
        relatedList.layoutManager = LinearLayoutManager(this)
        relatedList.adapter = relatedAdapter

        floatingAudio = FloatingAudioController(this, onlyWhenExplicitAudioOnly = true)
        setupGestures()
        registerPipReceiver()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeInstance = this
        val svc = YouTubePlaybackService.instance
        if (svc != null) {
            if (intent.getBooleanExtra("restore_video", false)) {
                intent.removeExtra("restore_video")
                svc.setAudioOnlyMode(false)
            }
            playerView.player = svc.getPlayer()
            svc.setVideoSurfaceVisible(true)
        }
        applyChromeVisibility()
        if (!inPipMode) {
            playerView.showController()
        }
    }

    override fun onStart() {
        super.onStart()
        activeInstance = this
        explicitBgAudioRequested = false
        val svc = YouTubePlaybackService.instance
        if (svc != null) {
            playerView.player = svc.getPlayer()
            val switchToVideo = intent?.getBooleanExtra("restore_video", false) == true
            if (switchToVideo) {
                intent?.removeExtra("restore_video")
                svc.setAudioOnlyMode(false)
            }
            // Mark video surface visible (automatically restores video unless the
            // user explicitly enabled Audio-Only mode).
            svc.setVideoSurfaceVisible(true)
        }
        if (!inPipMode && !isScreenLocked) {
            playerView.showController()
        }
        YouTubePlaybackService.addListener(stateListener)
        SleepTimerManager.addListener(sleepTimerListener)
    }

    override fun onResume() {
        super.onResume()
        val netGenNow = GrabAllApp.networkGeneration
        if (netGenNow != lastSeenNetGen) {
            lastSeenNetGen = netGenNow
            ThumbnailLoader.retryMissingImagesIn(window.decorView)
            val vid = lastRelatedVideoId
            if (currentEngagement == null && !vid.isNullOrBlank()) {
                loadRelated(vid)
            }
        }
        if (!inPipMode && !isFullscreen) {
            floatingAudio.attach()
        }
        syncSubtitleTicker(YouTubePlaybackService.currentState)
    }

    override fun onPause() {
        releaseHoldSpeedIfActive()
        stopSubtitleTicker()
        floatingAudio.detach()
        super.onPause()
    }

    override fun onStop() {
        YouTubePlaybackService.removeListener(stateListener)
        SleepTimerManager.removeListener(sleepTimerListener)
        // Guard with activeInstance so a stale/closing instance can never detach
        // ExoPlayer or disable video for a newer active player screen.
        if (activeInstance === this) {
            playerView.player = null
            // Anytime the player UI is no longer visible (display turned off, home
            // pressed, or navigated back to mini-player) — except during screen
            // rotation or PiP dismissal while screen is on — drop the video track
            // so playback runs audio-only without flickering the floating bubble.
            val screenInteractive = (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isInteractive ?: true
            if (!isChangingConfigurations && !(inPipMode && screenInteractive)) {
                YouTubePlaybackService.instance?.setVideoSurfaceVisible(false)
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.removeCallbacks(hideUnlockRunnable)
        mainHandler.removeCallbacks(holdSpeedRunnable)
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
        val wasInPip = inPipMode
        inPipMode = isInPictureInPictureMode
        // When the user closes/dismisses the PiP window (X or swipe away),
        // Android stops the activity BEFORE calling this callback, so lifecycle
        // state is CREATED (not STARTED/RESUMED). Fully stop playback (saving
        // watch progress) so no lingering floating audio bubble or notification appears.
        if (wasInPip && !isInPictureInPictureMode &&
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            if (!exitingPipForAudioOnly && activeInstance === this) {
                YouTubePlaybackService.instance?.stopPlayback()
            }
            exitingPipForAudioOnly = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            } else {
                finish()
            }
            return
        }
        applyChromeVisibility()
        val svc = YouTubePlaybackService.instance
        if (svc != null && activeInstance === this) {
            if (isInPictureInPictureMode) {
                svc.setVideoSurfaceVisible(!YouTubePlaybackService.currentState.isAudioOnly)
            } else {
                playerView.player = svc.getPlayer()
                svc.setVideoSurfaceVisible(true)
            }
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
        if (isFullscreen && isScreenLocked) {
            flashUnlockButton()
            return
        }
        if (isFullscreen) {
            toggleFullscreen()
        } else {
            navigateBackCleanly()
        }
    }

    /**
     * Never trap the user or wipe the YouTube Feed back stack when returning
     * from a split PiP task: if another GrabAll Activity is already alive behind
     * us, close just this task so Android reveals it at the exact scroll spot!
     */
    private fun navigateBackCleanly() {
        if (isTaskRoot) {
            if (!com.graball.GrabAllApp.hasOtherAliveActivity()) {
                startActivity(
                    Intent(this, com.graball.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
                return
            }
        }
        finish()
    }

    // ---- State Binding ----

    private fun bindState(state: YouTubePlaybackState) {
        // If playback was completely stopped while in PiP (e.g., user started a
        // local song or tapped Stop in the notification), close the empty PiP box.
        if (inPipMode && state.currentItem == null && !state.isLoading) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            } else {
                finish()
            }
            return
        }
        val svc = YouTubePlaybackService.instance
        if (svc != null && playerView.player == null) {
            playerView.player = svc.getPlayer()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                svc.setVideoSurfaceVisible(true)
            }
        }

        playerLoadingBox.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        if (state.isLoading) {
            playerLoadingText.text = state.loadingStatus ?: "Loading…"
        }
        // Keep the ExoPlayer time bar disabled while the stream is resolving or
        // before initial prepare completes so dragging early never triggers a
        // premature HTTP range request.
        playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_progress)?.let { timeBar ->
            timeBar.isEnabled = state.isReadyForSeek
            timeBar.alpha = if (state.isReadyForSeek) 1.0f else 0.45f
        }
        updateAudioOnlyBannerVisibility(state)
        // Keep the screen awake while actively watching video, and let it sleep
        // normally when paused, ended, or in Audio-Only mode (saves battery).
        val keepScreenAwake = (state.isPlaying || state.isBuffering || state.isLoading) && !state.isAudioOnly
        playerView.keepScreenOn = keepScreenAwake
        if (keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val item = state.currentItem
        val info = state.streamInfo
        videoTitle.text = info?.title?.takeIf { !DownloadProgressBus.isPlaceholderTitle(it) }
            ?: item?.title?.takeIf { !DownloadProgressBus.isPlaceholderTitle(it) }
            ?: info?.title?.takeIf { it.isNotBlank() }
            ?: item?.title?.takeIf { it.isNotBlank() }
            ?: "Nothing playing"
        val channelLabel = refreshVideoMeta(state)
        if (channelLabel.isNotBlank()) {
            channelNameView.text = channelLabel
        }
        val descText = info?.description.orEmpty().trim()
        descChevron.visibility = if (descText.isNotBlank()) View.VISIBLE else View.INVISIBLE
        if (descriptionExpanded) {
            if (descText.isNotBlank()) {
                videoDescription.text = buildClickableDescription(descText)
            } else {
                descriptionExpanded = false
                videoDescription.visibility = View.GONE
                descChevron.text = "⌄"
            }
        }
        updateChaptersStrip(item?.id.orEmpty(), descText)

        if (!state.error.isNullOrBlank()) {
            videoError.text = state.error
            playerErrorRow.visibility = View.VISIBLE
        } else {
            playerErrorRow.visibility = View.GONE
        }

        val actualHeight = state.activeVideoHeight
        val topQualityText = when {
            state.isAudioOnly -> "⚙ Audio"
            actualHeight != null -> "⚙ ${actualHeight}p"
            else -> "⚙ Auto"
        }
        btnTopQuality.text = topQualityText

        actionSpeed.text = "${trimSpeed(state.playbackSpeed)}×"

        actionBgAudio.setBackgroundResource(
            if (state.isAudioOnly) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg
        )
        btnTopCaptions.visibility = if (state.hasCaptions) View.VISIBLE else View.GONE
        val ccColor = ContextCompat.getColor(
            this,
            if (state.captionsEnabled) R.color.graball_text else R.color.graball_subtext
        )
        btnTopCaptions.setBackgroundResource(
            if (state.captionsEnabled) R.drawable.yt_chip_selected else R.drawable.yt_badge_bg
        )
        btnTopCaptions.setTextColor(ccColor)
        val audioTracks = svc?.availableAudioTracks().orEmpty()
        if (audioTracks.size > 1) {
            val activeAudioLabel = svc?.currentAudioTrackLabel().orEmpty()
            val shortCode = activeAudioLabel
                .substringBefore('(')
                .trim()
                .take(8)
                .ifBlank { "Audio" }
            btnTopAudio.text = "🔊 $shortCode"
            btnTopAudio.visibility = View.VISIBLE
        } else {
            btnTopAudio.visibility = View.GONE
        }
        syncSubtitleTicker(state)

        if (item != null) {
            updateBookmarkChip(YouTubeStore.isBookmarked(this, item.id))
            if (item.id != lastRelatedVideoId) {
                lastRelatedVideoId = item.id
                descriptionExpanded = false
                videoDescription.visibility = View.GONE
                descChevron.text = "⌄"
                lastChannelAvatarUrl = null
                channelAvatar.setImageResource(R.drawable.yt_chip_bg)
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

    private fun updateAudioOnlyBannerVisibility(state: YouTubePlaybackState) {
        // Hide the banner while ExoPlayer's controller is open so transport
        // controls (Play/Pause/Next/Prev/Seek) are always unobstructed.
        val show = state.isAudioOnly && !inPipMode && !state.isLoading && !isControllerVisible
        audioOnlyBanner.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadRelated(videoId: String, attempt: Int = 0) {
        if (attempt == 0) {
            currentEngagement = null
            updateEngagementChips(null)
        }
        io.execute {
            val (items, engagement) = YouTubeInnerTube.fetchWatchData(this, videoId)
            runOnUiThread {
                if (!isFinishing && lastRelatedVideoId == videoId) {
                    if (items.isNotEmpty() || attempt > 0) {
                        relatedAdapter.submitList(items, videoId)
                    }
                    if (engagement != null) {
                        currentEngagement = engagement
                        updateEngagementChips(engagement)
                    } else if (attempt == 0) {
                        // Retry once cleanly if `/next` had a transient hiccup (e.g. VPN switch)
                        playerView.postDelayed({
                            if (!isFinishing && lastRelatedVideoId == videoId && currentEngagement == null) {
                                loadRelated(videoId, attempt = 1)
                            }
                        }, 2_000L)
                    }
                }
            }
        }
    }

    private fun updateEngagementChips(eng: YouTubeInnerTube.VideoEngagement?) {
        val liked = eng?.isLiked == true
        val likeLabel = eng?.likeCount?.takeIf { it.isNotBlank() }
        actionLike.text = when {
            liked && likeLabel != null -> "👍 $likeLabel"
            liked -> "👍 Liked"
            likeLabel != null -> "👍 $likeLabel"
            else -> "👍 Like"
        }
        actionLike.setBackgroundResource(
            if (liked) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg
        )

        val subscribed = eng?.isSubscribed == true
        actionSubscribe.text = if (subscribed) "✓ Subscribed" else "🔔 Subscribe"
        actionSubscribe.setBackgroundResource(
            if (subscribed) R.drawable.yt_chip_bg else R.drawable.yt_chip_selected
        )

        if (!eng?.channelName.isNullOrBlank()) {
            channelNameView.text = eng?.channelName
        }
        val subText = eng?.subscriberCount?.takeIf { it.isNotBlank() }
        channelSubsView.text = if (subText != null) "$subText · View channel ›" else "View channel ›"
        val avatarUrl = eng?.channelAvatarUrl.orEmpty()
        if (avatarUrl.isNotBlank() && avatarUrl != lastChannelAvatarUrl) {
            lastChannelAvatarUrl = avatarUrl
            ThumbnailLoader.load(avatarUrl, channelAvatar, targetWidthPx = 120)
        }

        val commentCount = eng?.commentCount?.takeIf { it.isNotBlank() }
        actionComments.text = if (commentCount != null) "💬 Comments · $commentCount" else "💬 Comments"
        val teaser = eng?.topCommentPreview?.takeIf { it.isNotBlank() }
        commentsPreview.text = teaser ?: "Tap to view comments"
        refreshVideoMeta(YouTubePlaybackService.currentState)
    }

    private fun refreshVideoMeta(state: YouTubePlaybackState): String {
        val item = state.currentItem
        val info = state.streamInfo
        val channelLabel = currentEngagement?.channelName?.takeIf { it.isNotBlank() }
            ?: info?.channel?.takeIf { it.isNotBlank() }
            ?: item?.channel.orEmpty()
        val viewsLabel = info?.viewCount?.takeIf { it.isNotBlank() }
            ?: currentEngagement?.viewCount?.takeIf { it.isNotBlank() }
            ?: item?.views.orEmpty()
        val dateLabel = info?.publishDate?.takeIf { it.isNotBlank() }
            ?: currentEngagement?.publishDate?.takeIf { it.isNotBlank() }
            ?: item?.publishedTime?.takeIf { it.isNotBlank() }
            .orEmpty()
        videoMeta.text = listOf(viewsLabel, dateLabel, channelLabel)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        return channelLabel
    }

    private fun resolvedChannelId(): String? {
        val state = YouTubePlaybackService.currentState
        return currentEngagement?.channelId?.takeIf { it.startsWith("UC") }
            ?: state.streamInfo?.channelId?.takeIf { it.startsWith("UC") }
            ?: state.currentItem?.channelId?.takeIf { it.startsWith("UC") }
    }

    private fun openChannelPage() {
        val cid = resolvedChannelId()
        if (cid.isNullOrBlank()) {
            val vid = YouTubePlaybackService.currentState.currentItem?.id
            if (!vid.isNullOrBlank()) {
                loadRelated(vid, attempt = 1)
            }
            Toast.makeText(this, "Loading channel details… tap again in a moment", Toast.LENGTH_SHORT).show()
            return
        }
        val state = YouTubePlaybackService.currentState
        val name = currentEngagement?.channelName?.ifBlank { null }
            ?: state.streamInfo?.channel?.ifBlank { null }
            ?: state.currentItem?.channel.orEmpty()
        val avatar = currentEngagement?.channelAvatarUrl?.ifBlank { null }
            ?: state.currentItem?.channelAvatarUrl.orEmpty()
        startActivity(
            YouTubeChannelActivity.createIntent(
                context = this,
                channelId = cid,
                channelName = name,
                channelAvatar = avatar,
                isSubscribed = currentEngagement?.isSubscribed == true
            )
        )
    }

    private fun requireYouTubeLogin(): Boolean {
        if (WebViewCookieSync.buildYouTubeAuthHeaders(this) != null) return true
        AlertDialog.Builder(this)
            .setTitle("Sign in to YouTube")
            .setMessage("Sign in with your Google account inside GrabAll to like videos, subscribe to channels, and comment.")
            .setPositiveButton("Log in") { _, _ ->
                startActivity(Intent(this, LoginActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
        return false
    }

    private fun toggleLike() {
        val videoId = YouTubePlaybackService.currentState.currentItem?.id ?: return
        if (!requireYouTubeLogin()) return
        val prev = currentEngagement
        val nextLiked = !(prev?.isLiked ?: false)
        val optimistic = (prev ?: YouTubeInnerTube.VideoEngagement(
            channelId = null,
            channelName = "",
            isSubscribed = false,
            isLiked = false,
            likeCount = "",
            commentCount = "",
            commentsToken = null
        )).copy(isLiked = nextLiked)
        currentEngagement = optimistic
        updateEngagementChips(optimistic)

        io.execute {
            val ok = YouTubeInnerTube.setLike(this, videoId, nextLiked)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ok) {
                    Toast.makeText(
                        this,
                        if (nextLiked) "Liked video" else "Removed like",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    currentEngagement = prev
                    updateEngagementChips(prev)
                    Toast.makeText(
                        this,
                        "Couldn't update like — try signing in again in Settings",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun toggleSubscribe() {
        if (YouTubePlaybackService.currentState.currentItem == null) return
        if (!requireYouTubeLogin()) return
        val prev = currentEngagement
        val channelId = resolvedChannelId()
        if (channelId.isNullOrBlank()) {
            Toast.makeText(this, "Loading channel info… tap Subscribe again in a moment", Toast.LENGTH_SHORT).show()
            YouTubePlaybackService.currentState.currentItem?.id?.let { loadRelated(it, attempt = 1) }
            return
        }
        val nextSub = !(prev?.isSubscribed ?: false)
        val optimistic = (prev ?: YouTubeInnerTube.VideoEngagement(
            channelId = channelId,
            channelName = YouTubePlaybackService.currentState.streamInfo?.channel.orEmpty(),
            isSubscribed = false,
            isLiked = false,
            likeCount = "",
            commentCount = "",
            commentsToken = null
        )).copy(channelId = channelId, isSubscribed = nextSub)
        currentEngagement = optimistic
        updateEngagementChips(optimistic)

        io.execute {
            val ok = YouTubeInnerTube.setSubscribe(this, channelId, nextSub)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ok) {
                    Toast.makeText(
                        this,
                        if (nextSub) "Subscribed" else "Unsubscribed",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    currentEngagement = prev
                    updateEngagementChips(prev)
                    Toast.makeText(
                        this,
                        "Couldn't update subscription — try signing in again in Settings",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun buildTimestampSpannable(
        rawText: String,
        accentColor: Int,
        onSeekSeconds: (Long) -> Unit
    ): CharSequence {
        val tsRegex = Regex("""\b(?:(\d{1,2}):)?([0-5]?\d):([0-5]\d)\b""")
        val matches = tsRegex.findAll(rawText).toList()
        if (matches.isEmpty()) return rawText
        val sb = android.text.SpannableStringBuilder(rawText)
        for (m in matches) {
            val h = m.groupValues[1].toLongOrNull() ?: 0L
            val min = m.groupValues[2].toLongOrNull() ?: 0L
            val sec = m.groupValues[3].toLongOrNull() ?: 0L
            val totalSec = h * 3600L + min * 60L + sec
            val span = object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) {
                    onSeekSeconds(totalSec)
                }

                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = accentColor
                    ds.isUnderlineText = false
                    ds.isFakeBoldText = true
                }
            }
            sb.setSpan(
                span,
                m.range.first,
                m.range.last + 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sb
    }

    private fun showCommentsDialog() {
        if (YouTubePlaybackService.currentState.currentItem == null) return
        val initialToken = currentEngagement?.commentsToken
        if (initialToken.isNullOrBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Comments")
                .setMessage("Comments aren't available for this video yet. Open it in Ad-Free YouTube Web to read and post comments?")
                .setPositiveButton("Open in YouTube Web") { _, _ -> openCurrentVideoInWeb() }
                .setNegativeButton("Close", null)
                .show()
            return
        }

        val dp = resources.displayMetrics.density
        val colorText = ContextCompat.getColor(this, R.color.graball_text)
        val colorSub = ContextCompat.getColor(this, R.color.graball_subtext)
        val colorAccent = ContextCompat.getColor(this, R.color.graball_orange)
        val colorPink = ContextCompat.getColor(this, R.color.graball_pink)
        val colorCard = ContextCompat.getColor(this, R.color.graball_card)
        val colorSmallBtn = ContextCompat.getColor(this, R.color.graball_small_btn)

        // Sub-view state: null = main comments list, non-null = replies view for that parent comment.
        var activeReplyParent: YouTubeInnerTube.YtComment? = null
        var activeReplyParentIndex: Int = -1
        var leaveRepliesView: (() -> Unit)? = null

        val dialog = object : Dialog(this) {
            @Deprecated("Deprecated in Java")
            override fun onBackPressed() {
                if (activeReplyParent != null) {
                    leaveRepliesView?.invoke()
                } else {
                    super.onBackPressed()
                }
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(18 * dp, 18 * dp, 18 * dp, 18 * dp, 0f, 0f, 0f, 0f)
                setColor(ContextCompat.getColor(this@YouTubePlayerActivity, R.color.graball_nav))
            }
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        }

        // Top drag handle pill
        sheet.addView(
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = (10 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    cornerRadius = 2 * dp
                    setColor(0x55FFFFFF)
                }
            }
        )

        // Sheet header (Back arrow in Replies mode + Title + Close)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
        }
        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 19f
            setTextColor(colorText)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt()).apply {
                marginEnd = (6 * dp).toInt()
            }
            visibility = View.GONE
            setOnClickListener { leaveRepliesView?.invoke() }
        }
        val mainTitleText = currentEngagement?.commentCount?.takeIf { it.isNotBlank() }
            ?.let { "Comments · $it" } ?: "Comments"
        val titleView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = mainTitleText
            setTextColor(colorText)
            textSize = 15.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(colorSub)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt())
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(backBtn)
        headerRow.addView(titleView)
        headerRow.addView(closeBtn)
        sheet.addView(headerRow)

        // Sort chips bar (Top / Newest) shown in main comments view
        val sortRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
            visibility = View.GONE
        }
        sheet.addView(sortRow)

        val statusText = TextView(this).apply {
            text = "Loading comments…"
            setTextColor(colorSub)
            textSize = 13f
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
        }
        sheet.addView(statusText)

        val loadedComments = mutableListOf<YouTubeInnerTube.YtComment>()
        val loadedReplies = mutableListOf<YouTubeInnerTube.YtComment>()
        val expandedCommentIds = mutableSetOf<String>()
        val sortOptions = mutableListOf<YouTubeInnerTube.CommentSortOption>()
        currentEngagement?.commentSortOptions?.let { sortOptions.addAll(it) }
        var activeSortIndex = sortOptions.indexOfFirst { it.selected }.coerceAtLeast(0)

        var nextToken: String? = null
        var nextRepliesToken: String? = null
        var loadingMore = false
        var loadingReplies = false
        var createParams: String? = null

        val composeInput = android.widget.EditText(this).apply {
            hint = "Add a comment…"
            textSize = 13f
            maxLines = 4
            setHintTextColor(colorSub)
            setTextColor(colorText)
            setBackgroundResource(R.drawable.yt_chip_bg)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sendBtn = TextView(this).apply {
            text = "Post"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(colorText)
            setBackgroundResource(R.drawable.yt_chip_selected)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((64 * dp).toInt(), (38 * dp).toInt()).apply {
                marginStart = (8 * dp).toInt()
            }
        }

        // Reusable rich comment ViewHolder for both top-level comments and replies.
        class CommentRowHolder(
            val root: LinearLayout,
            val avatar: ImageView,
            val pinnedTv: TextView,
            val authorTv: TextView,
            val timeTv: TextView,
            val bodyTv: TextView,
            val readMoreTv: TextView,
            val likeBtn: TextView,
            val dislikeBtn: TextView,
            val replyBtn: TextView,
            val heartBadge: TextView,
            val viewRepliesBtn: TextView
        ) : RecyclerView.ViewHolder(root)

        fun createCommentHolder(): CommentRowHolder {
            val outer = LinearLayout(this@YouTubePlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dp).toInt() }
                setBackgroundResource(R.drawable.card_bg)
                setPadding((10 * dp).toInt(), (9 * dp).toInt(), (10 * dp).toInt(), (9 * dp).toInt())
            }
            val avatarSize = (32 * dp).toInt()
            val avatar = ImageView(this@YouTubePlayerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                    marginEnd = (10 * dp).toInt()
                    topMargin = (2 * dp).toInt()
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorSmallBtn)
                }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
            }
            val col = LinearLayout(this@YouTubePlayerActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val pinnedTv = TextView(this@YouTubePlayerActivity).apply {
                text = "📌 Pinned"
                textSize = 10.5f
                setTextColor(colorAccent)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, (2 * dp).toInt())
                visibility = View.GONE
            }
            val metaRow = LinearLayout(this@YouTubePlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val authorTv = TextView(this@YouTubePlayerActivity).apply {
                textSize = 12f
                setTextColor(colorText)
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val timeTv = TextView(this@YouTubePlayerActivity).apply {
                textSize = 11f
                setTextColor(colorSub)
                setPadding((6 * dp).toInt(), 0, 0, 0)
            }
            metaRow.addView(authorTv)
            metaRow.addView(timeTv)

            val bodyTv = TextView(this@YouTubePlayerActivity).apply {
                textSize = 13.5f
                setTextColor(colorText)
                setLineSpacing(2 * dp, 1f)
                setPadding(0, (4 * dp).toInt(), 0, 0)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                highlightColor = Color.TRANSPARENT
            }
            val readMoreTv = TextView(this@YouTubePlayerActivity).apply {
                text = "Read more"
                textSize = 11.5f
                setTextColor(colorSub)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, (2 * dp).toInt(), (8 * dp).toInt(), (2 * dp).toInt())
                visibility = View.GONE
            }

            val actionRow = LinearLayout(this@YouTubePlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * dp).toInt() }
            }
            fun makeActionChip(): TextView = TextView(this@YouTubePlayerActivity).apply {
                textSize = 12f
                setTextColor(colorSub)
                setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * dp).toInt() }
            }
            val likeBtn = makeActionChip()
            val dislikeBtn = makeActionChip()
            val replyBtn = makeActionChip().apply { text = "💬 Reply" }
            val heartBadge = TextView(this@YouTubePlayerActivity).apply {
                text = "❤️"
                textSize = 12f
                setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
                visibility = View.GONE
            }
            actionRow.addView(likeBtn)
            actionRow.addView(dislikeBtn)
            actionRow.addView(replyBtn)
            actionRow.addView(heartBadge)

            val viewRepliesBtn = TextView(this@YouTubePlayerActivity).apply {
                textSize = 12.5f
                setTextColor(colorAccent)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding((4 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (2 * dp).toInt())
                visibility = View.GONE
            }

            col.addView(pinnedTv)
            col.addView(metaRow)
            col.addView(bodyTv)
            col.addView(readMoreTv)
            col.addView(actionRow)
            col.addView(viewRepliesBtn)
            outer.addView(avatar)
            outer.addView(col)
            return CommentRowHolder(
                root = outer,
                avatar = avatar,
                pinnedTv = pinnedTv,
                authorTv = authorTv,
                timeTv = timeTv,
                bodyTv = bodyTv,
                readMoreTv = readMoreTv,
                likeBtn = likeBtn,
                dislikeBtn = dislikeBtn,
                replyBtn = replyBtn,
                heartBadge = heartBadge,
                viewRepliesBtn = viewRepliesBtn
            )
        }

        lateinit var openRepliesFor: (YouTubeInnerTube.YtComment, Int, Boolean) -> Unit
        lateinit var commentAdapter: RecyclerView.Adapter<CommentRowHolder>
        lateinit var repliesAdapter: RecyclerView.Adapter<CommentRowHolder>

        fun handleVoteToggle(
            list: MutableList<YouTubeInnerTube.YtComment>,
            adapter: RecyclerView.Adapter<CommentRowHolder>,
            index: Int,
            isLikeTap: Boolean,
            isParentInRepliesView: Boolean = false
        ) {
            if (!requireYouTubeLogin()) return
            val orig = list.getOrNull(index) ?: return
            val actionParam = if (isLikeTap) {
                if (orig.isLiked) orig.unlikeActionParams else orig.likeActionParams
            } else {
                if (orig.isDisliked) orig.undislikeActionParams else orig.dislikeActionParams
            }
            if (actionParam.isNullOrBlank()) {
                Toast.makeText(this, "Sign in again to vote on comments", Toast.LENGTH_SHORT).show()
                return
            }

            val updated = if (isLikeTap) {
                val nowLiked = !orig.isLiked
                val nextCount = when {
                    nowLiked && orig.likeCountLiked.isNotBlank() -> orig.likeCountLiked
                    nowLiked && orig.likes.toIntOrNull() != null -> (orig.likes.toInt() + 1).toString()
                    nowLiked && orig.likes.isBlank() -> "1"
                    !nowLiked && orig.likes.toIntOrNull() != null -> (orig.likes.toInt() - 1).coerceAtLeast(0).let { if (it == 0) "" else it.toString() }
                    else -> orig.likes
                }
                orig.copy(isLiked = nowLiked, isDisliked = if (nowLiked) false else orig.isDisliked, likes = nextCount)
            } else {
                val nowDisliked = !orig.isDisliked
                val nextCount = if (nowDisliked && orig.isLiked && orig.likes.toIntOrNull() != null) {
                    (orig.likes.toInt() - 1).coerceAtLeast(0).let { if (it == 0) "" else it.toString() }
                } else {
                    orig.likes
                }
                orig.copy(isDisliked = nowDisliked, isLiked = if (nowDisliked) false else orig.isLiked, likes = nextCount)
            }

            list[index] = updated
            adapter.notifyItemChanged(index)
            if (isParentInRepliesView && activeReplyParentIndex in loadedComments.indices) {
                activeReplyParent = updated
                loadedComments[activeReplyParentIndex] = updated
                commentAdapter.notifyItemChanged(activeReplyParentIndex)
            }

            io.execute {
                val (ok, msg) = YouTubeInnerTube.performCommentAction(this, actionParam)
                runOnUiThread {
                    if (isFinishing || !dialog.isShowing) return@runOnUiThread
                    if (!ok) {
                        if (index in list.indices) {
                            list[index] = orig
                            adapter.notifyItemChanged(index)
                        }
                        if (isParentInRepliesView && activeReplyParentIndex in loadedComments.indices) {
                            activeReplyParent = orig
                            loadedComments[activeReplyParentIndex] = orig
                            commentAdapter.notifyItemChanged(activeReplyParentIndex)
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        fun bindCommentHolder(
            holder: CommentRowHolder,
            c: YouTubeInnerTube.YtComment,
            position: Int,
            isReplyItem: Boolean,
            isParentHeader: Boolean,
            list: MutableList<YouTubeInnerTube.YtComment>,
            adapter: RecyclerView.Adapter<CommentRowHolder>
        ) {
            val lp = holder.root.layoutParams as RecyclerView.LayoutParams
            lp.marginStart = if (isReplyItem) (18 * dp).toInt() else 0
            holder.root.layoutParams = lp
            if (isParentHeader) {
                holder.root.background = GradientDrawable().apply {
                    cornerRadius = 12 * dp
                    setColor(colorCard)
                    setStroke((1 * dp).toInt(), colorAccent)
                }
            } else {
                holder.root.setBackgroundResource(R.drawable.card_bg)
            }

            holder.avatar.setImageDrawable(null)
            if (c.authorAvatarUrl.isNotBlank()) {
                ThumbnailLoader.load(c.authorAvatarUrl, holder.avatar, targetWidthPx = 96)
            }

            holder.pinnedTv.visibility = if (c.isPinned) View.VISIBLE else View.GONE
            val authorDisplay = buildString {
                append(c.author)
                if (c.isVerified) append(" ✓")
            }
            holder.authorTv.text = authorDisplay
            if (c.isChannelOwner) {
                holder.authorTv.background = GradientDrawable().apply {
                    cornerRadius = 10 * dp
                    setColor(colorSmallBtn)
                }
                holder.authorTv.setPadding((7 * dp).toInt(), (1 * dp).toInt(), (7 * dp).toInt(), (1 * dp).toInt())
            } else {
                holder.authorTv.background = null
                holder.authorTv.setPadding(0, 0, 0, 0)
            }
            holder.timeTv.text = c.timeText

            val key = c.commentId.ifBlank { "${c.author}|${c.text.take(24)}" }
            val isExpanded = key in expandedCommentIds
            val spannable = buildTimestampSpannable(c.text, colorAccent) { sec ->
                YouTubePlaybackService.instance?.seekTo(sec * 1000L)
                Toast.makeText(
                    this@YouTubePlayerActivity,
                    "Jumped to ${sec / 60}:${String.format("%02d", sec % 60)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            holder.bodyTv.text = spannable
            val isLong = c.text.length > 220 || c.text.count { it == '\n' } >= 4
            holder.bodyTv.maxLines = if (isLong && !isExpanded) 5 else Int.MAX_VALUE
            holder.readMoreTv.visibility = if (isLong) View.VISIBLE else View.GONE
            holder.readMoreTv.text = if (isExpanded) "Show less" else "Read more"
            holder.readMoreTv.setOnClickListener {
                if (key in expandedCommentIds) expandedCommentIds.remove(key) else expandedCommentIds.add(key)
                adapter.notifyItemChanged(position)
            }

            holder.likeBtn.text = if (c.likes.isNotBlank()) "👍 ${c.likes}" else "👍"
            holder.likeBtn.setTextColor(if (c.isLiked) colorAccent else colorSub)
            holder.likeBtn.setTypeface(null, if (c.isLiked) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            holder.likeBtn.setOnClickListener {
                handleVoteToggle(list, adapter, position, isLikeTap = true, isParentInRepliesView = isParentHeader)
            }

            holder.dislikeBtn.text = "👎"
            holder.dislikeBtn.setTextColor(if (c.isDisliked) colorPink else colorSub)
            holder.dislikeBtn.setOnClickListener {
                handleVoteToggle(list, adapter, position, isLikeTap = false, isParentInRepliesView = isParentHeader)
            }

            val showReplyAction = !isReplyItem && !isParentHeader &&
                (!c.createReplyParams.isNullOrBlank() || !c.repliesContinuationToken.isNullOrBlank())
            holder.replyBtn.visibility = if (showReplyAction) View.VISIBLE else View.GONE
            holder.replyBtn.setOnClickListener {
                openRepliesFor(c, position, true)
            }

            holder.heartBadge.visibility = if (c.isCreatorHearted) View.VISIBLE else View.GONE
            if (c.isCreatorHearted && c.heartTooltip.isNotBlank()) {
                holder.heartBadge.setOnClickListener {
                    Toast.makeText(this@YouTubePlayerActivity, c.heartTooltip, Toast.LENGTH_SHORT).show()
                }
            } else {
                holder.heartBadge.setOnClickListener(null)
            }

            val showRepliesPill = !isReplyItem && !isParentHeader && !c.repliesContinuationToken.isNullOrBlank()
            holder.viewRepliesBtn.visibility = if (showRepliesPill) View.VISIBLE else View.GONE
            if (showRepliesPill) {
                holder.viewRepliesBtn.text = "▾ ${c.replyCountText.ifBlank { "View replies" }}"
                holder.viewRepliesBtn.setOnClickListener {
                    openRepliesFor(c, position, false)
                }
            }
        }

        commentAdapter = object : RecyclerView.Adapter<CommentRowHolder>() {
            override fun getItemCount(): Int = loadedComments.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentRowHolder = createCommentHolder()
            override fun onBindViewHolder(holder: CommentRowHolder, position: Int) {
                bindCommentHolder(
                    holder = holder,
                    c = loadedComments[position],
                    position = position,
                    isReplyItem = false,
                    isParentHeader = false,
                    list = loadedComments,
                    adapter = this
                )
            }
        }

        repliesAdapter = object : RecyclerView.Adapter<CommentRowHolder>() {
            override fun getItemCount(): Int = loadedReplies.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentRowHolder = createCommentHolder()
            override fun onBindViewHolder(holder: CommentRowHolder, position: Int) {
                bindCommentHolder(
                    holder = holder,
                    c = loadedReplies[position],
                    position = position,
                    isReplyItem = position > 0,
                    isParentHeader = position == 0,
                    list = loadedReplies,
                    adapter = this
                )
            }
        }

        val listRv = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@YouTubePlayerActivity)
            adapter = commentAdapter
            visibility = View.GONE
        }
        val repliesRv = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@YouTubePlayerActivity)
            adapter = repliesAdapter
            visibility = View.GONE
        }
        sheet.addView(listRv)
        sheet.addView(repliesRv)

        val loadMoreBtn = TextView(this).apply {
            text = "Load more comments"
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(colorText)
            setBackgroundResource(R.drawable.yt_chip_bg)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (38 * dp).toInt()
            ).apply { topMargin = (8 * dp).toInt() }
            visibility = View.GONE
        }
        sheet.addView(loadMoreBtn)

        val composeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * dp).toInt() }
            addView(composeInput)
            addView(sendBtn)
        }
        sheet.addView(composeRow)

        fun updateLoadMoreButton() {
            if (activeReplyParent != null) {
                if (loadingReplies) {
                    loadMoreBtn.text = "Loading more replies…"
                    loadMoreBtn.visibility = View.VISIBLE
                } else {
                    loadMoreBtn.visibility = View.GONE
                }
            } else {
                if (loadingMore && loadedComments.isNotEmpty()) {
                    loadMoreBtn.text = "Loading more comments…"
                    loadMoreBtn.visibility = View.VISIBLE
                } else {
                    loadMoreBtn.visibility = View.GONE
                }
            }
        }

        lateinit var fetchBatch: (String, Boolean) -> Unit

        fun renderSortChips() {
            sortRow.removeAllViews()
            if (sortOptions.size < 2 || activeReplyParent != null) {
                sortRow.visibility = View.GONE
                return
            }
            sortRow.visibility = View.VISIBLE
            sortOptions.forEachIndexed { idx, opt ->
                val chip = TextView(this@YouTubePlayerActivity).apply {
                    text = opt.title
                    textSize = 12f
                    setTypeface(null, if (idx == activeSortIndex) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setTextColor(colorText)
                    setBackgroundResource(if (idx == activeSortIndex) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
                    setPadding((12 * dp).toInt(), (5 * dp).toInt(), (12 * dp).toInt(), (5 * dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (8 * dp).toInt() }
                    setOnClickListener {
                        if (idx == activeSortIndex || loadingMore) return@setOnClickListener
                        activeSortIndex = idx
                        renderSortChips()
                        statusText.text = "Loading ${opt.title.lowercase()} comments…"
                        statusText.visibility = View.VISIBLE
                        listRv.visibility = View.GONE
                        loadMoreBtn.visibility = View.GONE
                        fetchBatch(opt.continuationToken, false)
                    }
                }
                sortRow.addView(chip)
            }
        }

        fun fetchRepliesBatch(token: String, append: Boolean) {
            if (loadingReplies) return
            loadingReplies = true
            updateLoadMoreButton()
            if (!append && loadedReplies.size <= 1) {
                statusText.text = "Loading replies…"
                statusText.visibility = View.VISIBLE
            }
            io.execute {
                val page = YouTubeInnerTube.fetchCommentReplies(this, token)
                runOnUiThread {
                    loadingReplies = false
                    if (isFinishing || !dialog.isShowing || activeReplyParent == null) return@runOnUiThread
                    nextRepliesToken = page.continuationToken
                    val start = loadedReplies.size
                    loadedReplies.addAll(page.comments)
                    statusText.visibility = View.GONE
                    repliesRv.visibility = View.VISIBLE
                    if (append && page.comments.isNotEmpty()) {
                        repliesAdapter.notifyItemRangeInserted(start, page.comments.size)
                    } else {
                        repliesAdapter.notifyDataSetChanged()
                    }
                    updateLoadMoreButton()
                }
            }
        }

        openRepliesFor = { parent, parentIndex, focusComposer ->
            activeReplyParent = parent
            activeReplyParentIndex = parentIndex
            nextRepliesToken = null
            loadingReplies = false
            backBtn.visibility = View.VISIBLE
            titleView.text = if (parent.replyCountText.isNotBlank()) "Replies · ${parent.replyCountText}" else "Replies"
            sortRow.visibility = View.GONE
            listRv.visibility = View.GONE
            loadedReplies.clear()
            loadedReplies.add(parent)
            repliesAdapter.notifyDataSetChanged()
            repliesRv.visibility = View.VISIBLE
            composeInput.hint = "Reply to ${parent.author}…"
            updateLoadMoreButton()

            val rTok = parent.repliesContinuationToken
            if (!rTok.isNullOrBlank()) {
                fetchRepliesBatch(rTok, append = false)
            } else {
                statusText.visibility = View.GONE
            }
            if (focusComposer) {
                composeInput.requestFocus()
            }
        }

        leaveRepliesView = {
            activeReplyParent = null
            activeReplyParentIndex = -1
            nextRepliesToken = null
            loadingReplies = false
            backBtn.visibility = View.GONE
            titleView.text = mainTitleText
            repliesRv.visibility = View.GONE
            listRv.visibility = if (loadedComments.isEmpty()) View.GONE else View.VISIBLE
            statusText.visibility = if (loadedComments.isEmpty()) View.VISIBLE else View.GONE
            composeInput.hint = "Add a comment…"
            composeInput.setText("")
            renderSortChips()
            updateLoadMoreButton()
        }

        sendBtn.setOnClickListener {
            val text = composeInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            if (!requireYouTubeLogin()) return@setOnClickListener

            val replyTarget = activeReplyParent
            if (replyTarget != null) {
                val rParams = replyTarget.createReplyParams
                if (rParams.isNullOrBlank()) {
                    Toast.makeText(
                        this,
                        "Replies aren't enabled for this comment — sign in again or retry",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                sendBtn.isEnabled = false
                sendBtn.text = "…"
                io.execute {
                    val (ok, msg) = YouTubeInnerTube.postCommentReply(this, rParams, text)
                    runOnUiThread {
                        sendBtn.isEnabled = true
                        sendBtn.text = "Post"
                        if (isFinishing || !dialog.isShowing) return@runOnUiThread
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        if (ok) {
                            composeInput.setText("")
                            val insertIdx = if (loadedReplies.isNotEmpty()) 1 else 0
                            loadedReplies.add(
                                insertIdx,
                                YouTubeInnerTube.YtComment(
                                    author = "You",
                                    text = text,
                                    timeText = "just now",
                                    likes = ""
                                )
                            )
                            repliesAdapter.notifyItemInserted(insertIdx)
                            repliesRv.scrollToPosition(insertIdx)
                        }
                    }
                }
                return@setOnClickListener
            }

            val params = createParams
            if (params.isNullOrBlank()) {
                Toast.makeText(
                    this,
                    "Sign in to YouTube (👤 on the home screen) to comment",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            sendBtn.isEnabled = false
            sendBtn.text = "…"
            io.execute {
                val (ok, msg) = YouTubeInnerTube.postComment(this, params, text)
                runOnUiThread {
                    sendBtn.isEnabled = true
                    sendBtn.text = "Post"
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    if (ok) {
                        composeInput.setText("")
                        loadedComments.add(0, YouTubeInnerTube.YtComment("You", text, "just now", ""))
                        statusText.visibility = View.GONE
                        listRv.visibility = View.VISIBLE
                        commentAdapter.notifyItemInserted(0)
                        listRv.scrollToPosition(0)
                    }
                }
            }
        }

        fetchBatch = { token, append ->
            if (!loadingMore) {
                loadingMore = true
                updateLoadMoreButton()
                io.execute {
                    val page = YouTubeInnerTube.fetchCommentsPaged(this, token)
                    runOnUiThread {
                        loadingMore = false
                        if (isFinishing || !dialog.isShowing) return@runOnUiThread
                        nextToken = page.continuationToken
                        if (!page.createCommentParams.isNullOrBlank()) createParams = page.createCommentParams
                        if (sortOptions.isEmpty() && page.sortOptions.isNotEmpty()) {
                            sortOptions.addAll(page.sortOptions)
                            activeSortIndex = sortOptions.indexOfFirst { it.selected }.coerceAtLeast(0)
                        }
                        if (!append) {
                            loadedComments.clear()
                        }
                        val start = loadedComments.size
                        loadedComments.addAll(page.comments)
                        if (activeReplyParent == null) {
                            renderSortChips()
                            if (loadedComments.isEmpty()) {
                                statusText.text = "No comments yet — be the first to comment below."
                                statusText.visibility = View.VISIBLE
                                listRv.visibility = View.GONE
                            } else {
                                statusText.visibility = View.GONE
                                listRv.visibility = View.VISIBLE
                                if (append) {
                                    commentAdapter.notifyItemRangeInserted(start, page.comments.size)
                                } else {
                                    commentAdapter.notifyDataSetChanged()
                                }
                            }
                            updateLoadMoreButton()
                        }
                    }
                }
            }
        }

        loadMoreBtn.setOnClickListener {
            if (activeReplyParent != null) {
                val tok = nextRepliesToken ?: return@setOnClickListener
                fetchRepliesBatch(tok, append = true)
            } else {
                val tok = nextToken ?: return@setOnClickListener
                fetchBatch(tok, true)
            }
        }

        // Seamless infinite scroll (official YouTube style): auto-loads the next
        // page as soon as the user nears the bottom of loaded comments or replies.
        listRv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loadingMore || activeReplyParent != null) return
                val tok = nextToken ?: return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val total = commentAdapter.itemCount
                if (total > 0 && lm.findLastVisibleItemPosition() >= total - 4) {
                    fetchBatch(tok, true)
                }
            }
        })
        repliesRv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loadingReplies || activeReplyParent == null) return
                val tok = nextRepliesToken ?: return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val total = repliesAdapter.itemCount
                if (total > 0 && lm.findLastVisibleItemPosition() >= total - 3) {
                    fetchRepliesBatch(tok, append = true)
                }
            }
        })

        renderSortChips()
        dialog.setContentView(sheet)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setGravity(Gravity.BOTTOM)
            val sheetHeight = (resources.displayMetrics.heightPixels * 0.78f).toInt()
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, sheetHeight)
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        fetchBatch(initialToken, false)
    }

    private fun openCurrentVideoInWeb() {
        val videoId = YouTubePlaybackService.currentState.currentItem?.id
        val url = if (!videoId.isNullOrBlank()) {
            "https://m.youtube.com/watch?v=$videoId"
        } else {
            "https://m.youtube.com"
        }
        startActivity(
            Intent(this, BrowseActivity::class.java).putExtra("start_url", url)
        )
    }

    // ---- PiP & Fullscreen ----

    private fun enterPipOrMinimize() {
        // Never re-enable video if the user explicitly chose Audio-Only mode!
        if (YouTubePlaybackService.currentState.isAudioOnly) {
            Toast.makeText(
                this,
                "Audio Only — continuing in background",
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }
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
        if (YouTubePlaybackService.currentState.isAudioOnly) {
            finish()
            return
        }
        try {
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
        val audioOnlyIntent = PendingIntent.getBroadcast(
            this, 3, Intent(ACTION_PIP_AUDIO_ONLY).setPackage(packageName), flags
        )
        val playPauseIntent = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName), flags
        )
        val nextIntent = PendingIntent.getBroadcast(
            this, 2, Intent(ACTION_PIP_NEXT).setPackage(packageName), flags
        )
        val audioOnlyIcon = Icon.createWithResource(this, R.drawable.ic_pip_audio)
        val playPauseIcon = Icon.createWithResource(
            this,
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        val nextIcon = Icon.createWithResource(this, android.R.drawable.ic_media_next)
        val actions = listOf(
            RemoteAction(
                audioOnlyIcon,
                "Audio Only",
                "Switch to background audio only",
                audioOnlyIntent
            ),
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
        if ((!isFullscreen || inPipMode) && isScreenLocked) {
            isScreenLocked = false
            mainHandler.removeCallbacks(hideUnlockRunnable)
            if (::btnScreenUnlock.isInitialized) btnScreenUnlock.visibility = View.GONE
        }
        val hideDetails = inPipMode || isFullscreen
        detailsContainer.visibility = if (hideDetails) View.GONE else View.VISIBLE
        playerTopOverlay.visibility = if (inPipMode || isScreenLocked) {
            View.GONE
        } else if (playerView.isControllerFullyVisible) {
            View.VISIBLE
        } else {
            View.GONE
        }
        playerView.useController = !inPipMode && !isScreenLocked

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
        // Extend edge-to-edge into the camera cutout / notch in fullscreen so
        // Zoom/Fill truly covers 100% of tall screens with no black side pillar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            val targetCutout = if (isFullscreen && !inPipMode) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            if (attrs.layoutInDisplayCutoutMode != targetCutout) {
                attrs.layoutInDisplayCutoutMode = targetCutout
                window.attributes = attrs
            }
        }
        btnTopAspect.visibility = if (isFullscreen && !inPipMode) View.VISIBLE else View.GONE
        btnTopLock.visibility = if (isFullscreen && !inPipMode) View.VISIBLE else View.GONE
        playerView.resizeMode = if (isFullscreen && !inPipMode) {
            fullscreenResizeMode
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        updateAspectButtonUi()
        if (hideDetails) {
            floatingAudio.detach()
        } else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            floatingAudio.attach()
        }
        updateAudioOnlyBannerVisibility(YouTubePlaybackService.currentState)
        updateSubtitleOverlayPosition()
        syncSubtitleTicker(YouTubePlaybackService.currentState)
    }

    private fun updateSubtitleOverlayPosition() {
        if (!::subtitleOverlay.isInitialized) return
        val dp = resources.displayMetrics.density
        val lp = subtitleOverlay.layoutParams as? FrameLayout.LayoutParams ?: return
        val bottomDp = when {
            inPipMode -> 6
            isControllerVisible -> if (isFullscreen) 78 else 56
            else -> if (isFullscreen) 22 else 12
        }
        val targetBottom = (bottomDp * dp).toInt()
        if (lp.bottomMargin != targetBottom) {
            lp.bottomMargin = targetBottom
            subtitleOverlay.layoutParams = lp
        }
        val sizePref = YouTubeStore.getCaptionTextSize(this)
        subtitleOverlay.textSize = when (sizePref) {
            0 -> if (isFullscreen) 14.5f else 11.5f
            2 -> if (isFullscreen) 20.5f else 16.0f
            else -> if (isFullscreen) 17.0f else 13.5f
        }
    }

    private fun lockFullscreen() {
        if (!isFullscreen || inPipMode) return
        releaseHoldSpeedIfActive()
        isScreenLocked = true
        playerView.hideController()
        playerTopOverlay.visibility = View.GONE
        playerView.useController = false
        flashUnlockButton()
    }

    private fun unlockFullscreen(showFeedback: Boolean) {
        if (!isScreenLocked) return
        isScreenLocked = false
        mainHandler.removeCallbacks(hideUnlockRunnable)
        btnScreenUnlock.visibility = View.GONE
        playerView.useController = !inPipMode
        if (!inPipMode) {
            playerView.showController()
            playerTopOverlay.visibility = View.VISIBLE
        }
        if (showFeedback) showHud("🔓 Screen unlocked")
    }

    private fun flashUnlockButton() {
        btnScreenUnlock.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideUnlockRunnable)
        mainHandler.postDelayed(hideUnlockRunnable, 2800L)
    }

    private fun releaseHoldSpeedIfActive() {
        mainHandler.removeCallbacks(holdSpeedRunnable)
        if (isHolding2x) {
            isHolding2x = false
            YouTubePlaybackService.instance?.setTemporarySpeed(null)
            if (::holdSpeedPill.isInitialized) {
                holdSpeedPill.visibility = View.GONE
            }
        }
    }

    private fun shareCurrentVideo() {
        val st = YouTubePlaybackService.currentState
        val item = st.currentItem ?: return
        val title = st.streamInfo?.title?.takeIf { it.isNotBlank() } ?: item.title
        val url = "https://youtu.be/${item.id}"
        val text = if (title.isNotBlank()) "$title\n$url" else url
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(sendIntent, "Share video"))
        } catch (_: Exception) {
            Toast.makeText(this, "No share app available", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Clickable Timestamps & Video Chapters ----

    private val timestampRegex = Regex("(?<![\\d:])(\\d{1,2}:)?(\\d{1,2}):(\\d{2})(?![\\d:])")

    private fun parseTimestampMs(raw: String): Long? {
        val parts = raw.split(':').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> if (parts[1] < 60L) (parts[0] * 60L + parts[1]) * 1000L else null
            3 -> if (parts[1] < 60L && parts[2] < 60L) (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L else null
            else -> null
        }
    }

    private fun seekToTimestamp(ms: Long, label: String) {
        val svc = YouTubePlaybackService.instance ?: return
        if (!svc.isReadyForSeek()) {
            Toast.makeText(this, "Video is still loading…", Toast.LENGTH_SHORT).show()
            return
        }
        svc.seekTo(ms)
        showHud("⏩ $label")
    }

    private fun buildClickableDescription(desc: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder(desc)
        val linkColor = ContextCompat.getColor(this, R.color.graball_orange)
        timestampRegex.findAll(desc).forEach { match ->
            val raw = match.value
            val ms = parseTimestampMs(raw) ?: return@forEach
            val span = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    seekToTimestamp(ms, raw)
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = linkColor
                    ds.isFakeBoldText = true
                    ds.isUnderlineText = false
                }
            }
            sb.setSpan(span, match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun extractChapters(desc: String): List<VideoChapter> {
        if (desc.isBlank()) return emptyList()
        val seenTimes = HashSet<Long>()
        val chapters = mutableListOf<VideoChapter>()
        desc.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.length < 4 || line.length > 140) return@forEach
            val match = timestampRegex.find(line) ?: return@forEach
            val tsText = match.value
            val ms = parseTimestampMs(tsText) ?: return@forEach
            val before = line.substring(0, match.range.first).trim()
            val after = line.substring(match.range.last + 1).trim()
            // Accept standard "0:00 Title" (with optional bullet/bracket) or "Title - 0:00"
            val titleCandidate = when {
                before.length <= 4 && after.isNotBlank() -> after
                after.length <= 2 && before.isNotBlank() -> before
                else -> return@forEach
            }
            val cleaned = titleCandidate
                .trimStart('-', '–', '—', ':', '|', '•', ')', ']', ' ')
                .trimEnd('-', '–', '—', ':', '|', '(', '[', ' ')
                .trim()
            if (cleaned.isNotBlank() && seenTimes.add(ms)) {
                chapters.add(VideoChapter(timeMs = ms, timeLabel = tsText, title = cleaned))
            }
        }
        if (chapters.size < 2) return emptyList()
        return chapters.sortedBy { it.timeMs }.take(40)
    }

    private fun updateChaptersStrip(videoId: String, desc: String) {
        val key = "$videoId:${desc.hashCode()}"
        if (key == lastChaptersDescKey) return
        lastChaptersDescKey = key

        val chapters = extractChapters(desc)
        chaptersContainer.removeAllViews()
        if (chapters.isEmpty()) {
            chaptersRow.visibility = View.GONE
            return
        }
        val dp = resources.displayMetrics.density
        val textColor = ContextCompat.getColor(this, R.color.graball_text)
        val orangeColor = ContextCompat.getColor(this, R.color.graball_orange)
        chapters.forEachIndexed { idx, ch ->
            val chip = TextView(this).apply {
                val label = SpannableStringBuilder().apply {
                    append(ch.timeLabel)
                    setSpan(
                        android.text.style.ForegroundColorSpan(orangeColor),
                        0,
                        ch.timeLabel.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append("  ")
                    append(ch.title)
                }
                text = label
                textSize = 11.5f
                setTextColor(textColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (190 * dp).toInt()
                setBackgroundResource(R.drawable.yt_chip_bg)
                setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (idx < chapters.lastIndex) marginEnd = (6 * dp).toInt()
                }
                setOnClickListener {
                    seekToTimestamp(ch.timeMs, "${ch.timeLabel} · ${ch.title}")
                }
            }
            chaptersContainer.addView(chip)
        }
        chaptersRow.visibility = View.VISIBLE
    }

    private fun renderSubtitleOverlay() {
        if (!::subtitleOverlay.isInitialized) return
        val state = YouTubePlaybackService.currentState
        if (inPipMode || !state.captionsEnabled || state.isAudioOnly) {
            if (subtitleOverlay.visibility != View.GONE) {
                subtitleOverlay.visibility = View.GONE
            }
            return
        }
        val line = YouTubePlaybackService.instance?.currentSubtitleText()
        if (line.isNullOrBlank()) {
            if (subtitleOverlay.visibility != View.GONE) {
                subtitleOverlay.visibility = View.GONE
            }
        } else {
            if (subtitleOverlay.text.toString() != line) {
                subtitleOverlay.text = line
            }
            if (subtitleOverlay.visibility != View.VISIBLE) {
                subtitleOverlay.visibility = View.VISIBLE
            }
        }
    }

    private fun syncSubtitleTicker(state: YouTubePlaybackState) {
        renderSubtitleOverlay()
        val shouldTick = state.captionsEnabled &&
            state.isPlaying &&
            !state.isAudioOnly &&
            !inPipMode &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            (YouTubePlaybackService.instance?.hasLoadedSubtitles() == true)
        if (shouldTick) {
            if (!subtitleTickerRunning) {
                subtitleTickerRunning = true
                mainHandler.removeCallbacks(subtitleTicker)
                mainHandler.postDelayed(subtitleTicker, 150L)
            }
        } else {
            stopSubtitleTicker()
        }
    }

    private fun stopSubtitleTicker() {
        subtitleTickerRunning = false
        mainHandler.removeCallbacks(subtitleTicker)
    }

    private fun cycleAspectRatio() {
        val next = when (fullscreenResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        applyFullscreenResizeMode(next, showFeedback = true)
    }

    private fun applyFullscreenResizeMode(mode: Int, showFeedback: Boolean) {
        fullscreenResizeMode = mode
        if (isFullscreen && !inPipMode) {
            playerView.resizeMode = mode
        }
        updateAspectButtonUi()
        if (showFeedback) {
            val label = when (mode) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoomed to fill"
                AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretched to fill"
                else -> "Original (Fit)"
            }
            showHud("Aspect: $label")
        }
    }

    private fun updateAspectButtonUi() {
        btnTopAspect.text = when (fullscreenResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
            else -> "Fit"
        }
        val active = fullscreenResizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT
        btnTopAspect.setTextColor(
            ContextCompat.getColor(
                this,
                if (active) R.color.graball_orange else R.color.graball_text
            )
        )
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
                    if (inPipMode || isScreenLocked) return false
                    releaseHoldSpeedIfActive()
                    val svc = YouTubePlaybackService.instance ?: return false
                    if (!svc.isReadyForSeek()) return false
                    val isRight = e.x > playerContainer.width / 2f
                    val delta = if (isRight) 10_000L else -10_000L
                    svc.seekBy(delta)
                    showHud(if (isRight) "10s »" else "« 10s")
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (inPipMode || isScreenLocked || isHolding2x) return false
                    if (playerView.isControllerFullyVisible) {
                        playerView.hideController()
                        playerTopOverlay.visibility = View.GONE
                    } else {
                        playerView.showController()
                        playerTopOverlay.visibility = View.VISIBLE
                    }
                    return true
                }
            }
        )

        var downX = 0f
        var downY = 0f
        var lastDy = 0f
        var draggingVertical = false
        var pinchHandled = false
        var pinchScaleAccum = 1f
        var isLeftSide = true
        var startBrightness = 0.5f
        var startVolume = 0
        var maxVolume = 15

        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    if (!isFullscreen || inPipMode) return false
                    pinchScaleAccum = 1f
                    pinchHandled = false
                    draggingVertical = false
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!isFullscreen || inPipMode || pinchHandled) return false
                    pinchScaleAccum *= detector.scaleFactor
                    if (pinchScaleAccum > 1.12f) {
                        pinchHandled = true
                        if (fullscreenResizeMode != AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                            applyFullscreenResizeMode(
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                                showFeedback = true
                            )
                        } else {
                            showHud("Aspect: Zoomed to fill")
                        }
                    } else if (pinchScaleAccum < 0.88f) {
                        pinchHandled = true
                        if (fullscreenResizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                            applyFullscreenResizeMode(
                                AspectRatioFrameLayout.RESIZE_MODE_FIT,
                                showFeedback = true
                            )
                        } else {
                            showHud("Aspect: Original (Fit)")
                        }
                    }
                    return true
                }
            }
        )

        playerView.setOnTouchListener { _, event ->
            if (inPipMode) return@setOnTouchListener false
            if (isScreenLocked) {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (btnScreenUnlock.visibility == View.VISIBLE) {
                        mainHandler.removeCallbacks(hideUnlockRunnable)
                        btnScreenUnlock.visibility = View.GONE
                    } else {
                        flashUnlockButton()
                    }
                }
                return@setOnTouchListener true
            }
            if (isFullscreen) {
                scaleDetector.onTouchEvent(event)
            }
            val multiTouch = event.pointerCount > 1 || scaleDetector.isInProgress
            if (multiTouch) {
                releaseHoldSpeedIfActive()
                draggingVertical = false
                lastDy = 0f
                return@setOnTouchListener true
            }
            if (pinchHandled) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    pinchHandled = false
                }
                return@setOnTouchListener true
            }
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
                    mainHandler.removeCallbacks(holdSpeedRunnable)
                    mainHandler.postDelayed(holdSpeedRunnable, 320L)
                    // Consume touch on the video surface so PlayerView.onTouchEvent
                    // doesn't double-toggle controls behind our GestureDetector!
                    // (Child Play/Pause/SeekBar buttons still handle their own taps.)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = downY - event.y // upward swipe = positive, downward = negative
                    if (isHolding2x) {
                        return@setOnTouchListener true
                    }
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        mainHandler.removeCallbacks(holdSpeedRunnable)
                    }
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
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasHolding = isHolding2x
                    releaseHoldSpeedIfActive()
                    if (!wasHolding && draggingVertical && !isFullscreen && event.actionMasked == MotionEvent.ACTION_UP) {
                        if (lastDy < -portraitSwipeThresholdPx) {
                            enterPipOrMinimize()
                        } else if (lastDy > portraitSwipeThresholdPx) {
                            toggleFullscreen()
                        }
                    }
                    draggingVertical = false
                    lastDy = 0f
                    true
                }
                else -> true
            }
        }
    }

    private fun showHud(text: String) {
        gestureHud.text = text
        gestureHud.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.postDelayed(hideHudRunnable, 900L)
    }

    private fun showCaptionsPicker() {
        val svc = YouTubePlaybackService.instance ?: return
        val tracks = svc.availableCaptions()
        if (tracks.isEmpty()) {
            showHud("No subtitles available for this video")
            return
        }
        val state = YouTubePlaybackService.currentState
        val activeIdx = if (state.captionsEnabled) state.selectedCaptionIndex else -1
        val dp = resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()
        val orange = ContextCompat.getColor(this, R.color.graball_orange)

        val dialog = DarkBottomSheet.createDialog(this)
        val root = DarkBottomSheet.createContainer(this)
        root.addView(
            DarkBottomSheet.createHeader(
                ctx = this,
                title = "Subtitles & Captions",
                badge = "${tracks.size} languages",
                onClose = { dialog.dismiss() }
            )
        )

        // Inline Text Size Selector Row (no extra dialog hop!)
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#202236"))
                cornerRadius = 12 * dp
            }
            setPadding(d(12), d(8), d(10), d(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = d(10) }
        }
        sizeRow.addView(TextView(this).apply {
            text = "Aa Text Size"
            textSize = 12.5f
            setTextColor(Color.parseColor("#C7CBE0"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sizeNames = listOf("Small", "Medium", "Large")
        val sizeChips = mutableListOf<TextView>()
        fun refreshSizeChips(selectedIdx: Int) {
            sizeChips.forEachIndexed { idx, chip ->
                val active = idx == selectedIdx
                chip.setTextColor(if (active) Color.WHITE else Color.parseColor("#9DA2BD"))
                chip.background = GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(if (active) orange else Color.parseColor("#2A2D46"))
                }
            }
        }
        sizeNames.forEachIndexed { idx, label ->
            val chip = TextView(this).apply {
                text = label
                textSize = 11.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(d(10), d(5), d(10), d(5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (idx > 0) marginStart = d(5) }
                setOnClickListener {
                    YouTubeStore.setCaptionTextSize(this@YouTubePlayerActivity, idx)
                    updateSubtitleOverlayPosition()
                    refreshSizeChips(idx)
                    showHud("Subtitle size: $label")
                }
            }
            sizeChips.add(chip)
            sizeRow.addView(chip)
        }
        refreshSizeChips(YouTubeStore.getCaptionTextSize(this))
        root.addView(sizeRow)

        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        listBox.addView(
            DarkBottomSheet.createOptionRow(
                ctx = this,
                title = "Off",
                subtitle = "Hide subtitles",
                selected = activeIdx < 0
            ) {
                svc.selectCaption(null)
                subtitleOverlay.visibility = View.GONE
                stopSubtitleTicker()
                showHud("Subtitles: OFF")
                dialog.dismiss()
            }
        )
        tracks.forEachIndexed { idx, cap ->
            listBox.addView(
                DarkBottomSheet.createOptionRow(
                    ctx = this,
                    title = cap.label,
                    selected = idx == activeIdx
                ) {
                    dialog.dismiss()
                    showHud("Loading CC: ${cap.label}…")
                    svc.selectCaption(idx) { loaded ->
                        if (isFinishing) return@selectCaption
                        if (loaded) {
                            showHud("CC: ${cap.label}")
                            syncSubtitleTicker(YouTubePlaybackService.currentState)
                        } else {
                            showHud("Couldn't load captions for this track")
                        }
                    }
                }
            )
        }

        root.addView(DarkBottomSheet.wrapScrollable(this, listBox, maxHeightFraction = 0.48f))
        DarkBottomSheet.show(dialog, root) {
            if (isFullscreen && !inPipMode) applyChromeVisibility()
        }
    }

    private fun showAudioTrackPicker() {
        val svc = YouTubePlaybackService.instance ?: return
        val tracks = svc.availableAudioTracks()
        if (tracks.size <= 1) return
        val currentLabel = svc.currentAudioTrackLabel()

        val dialog = DarkBottomSheet.createDialog(this)
        val root = DarkBottomSheet.createContainer(this)
        root.addView(
            DarkBottomSheet.createHeader(
                ctx = this,
                title = "Audio Language",
                subtitle = "Switch dub or original audio track",
                badge = "${tracks.size} tracks",
                onClose = { dialog.dismiss() }
            )
        )

        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        tracks.forEach { t ->
            val selected = t.label == currentLabel
            val cleanTitle = t.label.replace(Regex("\\s*\\(original\\)", RegexOption.IGNORE_CASE), "").trim()
            listBox.addView(
                DarkBottomSheet.createOptionRow(
                    ctx = this,
                    title = cleanTitle.ifBlank { t.label },
                    badge = if (t.isOriginal) "ORIGINAL" else null,
                    selected = selected
                ) {
                    svc.selectAudioTrack(t.id)
                    showHud("Audio: ${t.label}")
                    dialog.dismiss()
                }
            )
        }

        root.addView(DarkBottomSheet.wrapScrollable(this, listBox, maxHeightFraction = 0.52f))
        DarkBottomSheet.show(dialog, root) {
            if (isFullscreen && !inPipMode) applyChromeVisibility()
        }
    }

    // ---- Quality & Speed Pickers ----

    private fun showQualityPicker() {
        val svc = YouTubePlaybackService.instance ?: return
        val heights = svc.availableVideoHeights()
            .ifEmpty { listOf(1080, 720, 480, 360) }
        val state = YouTubePlaybackService.currentState
        val activeH = state.activeVideoHeight
        val effectiveCheckedH = when {
            state.isAudioOnly || state.selectedHeight == null -> null
            state.selectedHeight in heights -> state.selectedHeight
            activeH != null && activeH in heights -> activeH
            else -> null
        }
        val audioTracks = svc.availableAudioTracks()
        val hasMultiAudio = audioTracks.size > 1
        val stats = svc.currentStreamStatsLabel()

        val dialog = DarkBottomSheet.createDialog(this)
        val root = DarkBottomSheet.createContainer(this)
        root.addView(
            DarkBottomSheet.createHeader(
                ctx = this,
                title = "Video Quality",
                subtitle = stats.ifBlank { "Choose playback resolution" },
                badge = activeH?.let { "${it}p" },
                onClose = { dialog.dismiss() }
            )
        )

        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (hasMultiAudio) {
            listBox.addView(
                DarkBottomSheet.createOptionRow(
                    ctx = this,
                    title = "🔊 Audio Language",
                    subtitle = svc.currentAudioTrackLabel(),
                    trailingText = "›"
                ) {
                    dialog.dismiss()
                    showAudioTrackPicker()
                }
            )
        }

        val autoSelected = !state.isAudioOnly && state.selectedHeight == null
        listBox.addView(
            DarkBottomSheet.createOptionRow(
                ctx = this,
                title = "Auto",
                subtitle = if (activeH != null) "Adapts to network · Currently ${activeH}p" else "Adapts to your connection",
                badge = "AUTO",
                selected = autoSelected
            ) {
                svc.setAudioOnlyMode(false)
                svc.setQuality(null)
                showHud("Quality: Auto")
                dialog.dismiss()
            }
        )

        listBox.addView(
            DarkBottomSheet.createOptionRow(
                ctx = this,
                title = "🎧 Audio Only",
                subtitle = "Saves battery & mobile data",
                selected = state.isAudioOnly
            ) {
                svc.setAudioOnlyMode(true)
                showHud("Audio Only")
                dialog.dismiss()
            }
        )

        heights.forEach { h ->
            val qualityBadge = when {
                h >= 2160 -> "4K"
                h >= 1440 -> "2K"
                h >= 720 -> "HD"
                else -> null
            }
            val selected = !state.isAudioOnly && effectiveCheckedH == h
            listBox.addView(
                DarkBottomSheet.createOptionRow(
                    ctx = this,
                    title = "${h}p",
                    badge = qualityBadge,
                    selected = selected
                ) {
                    svc.setAudioOnlyMode(false)
                    svc.setQuality(h)
                    showHud("Quality: ${h}p")
                    dialog.dismiss()
                }
            )
        }

        root.addView(DarkBottomSheet.wrapScrollable(this, listBox, maxHeightFraction = 0.54f))
        DarkBottomSheet.show(dialog, root) {
            if (isFullscreen && !inPipMode) applyChromeVisibility()
        }
    }

    private fun showSpeedPicker() {
        val svc = YouTubePlaybackService.instance ?: return
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val current = YouTubePlaybackService.currentState.playbackSpeed
        val dp = resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()
        val orange = ContextCompat.getColor(this, R.color.graball_orange)

        val dialog = DarkBottomSheet.createDialog(this)
        val root = DarkBottomSheet.createContainer(this)
        root.addView(
            DarkBottomSheet.createHeader(
                ctx = this,
                title = "Playback Speed",
                badge = "${trimSpeed(current)}×",
                onClose = { dialog.dismiss() }
            )
        )

        // Clean 2-row pill grid for instant 1-tap selection
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = d(8) }
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        speeds.forEachIndexed { idx, sp ->
            val selected = abs(sp - current) < 0.05f
            val chip = TextView(this).apply {
                text = if (sp == 1.0f) "1× Normal" else "${trimSpeed(sp)}×"
                textSize = 13.5f
                setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#D0D4E6"))
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = 12 * dp
                    setColor(if (selected) orange else Color.parseColor("#202236"))
                }
                layoutParams = LinearLayout.LayoutParams(0, d(42), 1f).apply {
                    val isLastInRow = (idx == 3 || idx == speeds.lastIndex)
                    if (!isLastInRow) marginEnd = d(7)
                }
                setOnClickListener {
                    svc.setSpeed(sp)
                    showHud("Speed: ${trimSpeed(sp)}×")
                    dialog.dismiss()
                }
            }
            if (idx < 4) row1.addView(chip) else row2.addView(chip)
        }
        root.addView(row1)
        root.addView(row2)

        DarkBottomSheet.show(dialog, root) {
            if (isFullscreen && !inPipMode) applyChromeVisibility()
        }
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
            addAction(ACTION_PIP_AUDIO_ONLY)
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
        private const val ACTION_PIP_AUDIO_ONLY = "com.graball.youtube.PIP_AUDIO_ONLY"
        private const val ACTION_PIP_PLAY_PAUSE = "com.graball.youtube.PIP_PLAY_PAUSE"
        private const val ACTION_PIP_NEXT = "com.graball.youtube.PIP_NEXT"

        @Volatile
        private var activeInstance: YouTubePlayerActivity? = null
    }
}
