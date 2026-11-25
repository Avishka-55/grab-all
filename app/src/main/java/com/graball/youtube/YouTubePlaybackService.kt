package com.graball.youtube

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Snapshot of live YouTube playback state shared between the full player
 * screen and the in-app mini-player bar.
 */
data class YouTubePlaybackState(
    val currentItem: YtItem? = null,
    val streamInfo: VideoStreamInfo? = null,
    val queue: List<YtItem> = emptyList(),
    val queueIndex: Int = -1,
    val isLoading: Boolean = false,
    val loadingStatus: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isReadyForSeek: Boolean = false,
    val isAudioOnly: Boolean = false,
    val isVideoDisabled: Boolean = false,
    val captionsEnabled: Boolean = false,
    val selectedHeight: Int? = null,
    val activeVideoHeight: Int? = null,
    val playbackSpeed: Float = 1.0f,
    val error: String? = null,
    val lastSponsorSkip: String? = null
)

/**
 * Lightweight Media3 MediaSessionService powering ad-free YouTube video,
 * PiP, and true screen-off background audio playback.
 *
 * Battery & RAM optimizations:
 * - Modest ExoPlayer buffer (15s min / 30s max) for low RAM on old 32-bit phones.
 * - WAKE_MODE_NETWORK keeps audio streaming cleanly with the screen locked and
 *   releases automatically when paused.
 * - Audio-only mode disables C.TRACK_TYPE_VIDEO on adaptive streams so ExoPlayer
 *   completely stops downloading and decoding video frames while the screen is
 *   off or user is in Background Audio mode.
 * - SponsorBlock auto-skip timer runs ONLY while playing and segments exist.
 */
@OptIn(UnstableApi::class)
class YouTubePlaybackService : MediaSessionService() {

    private enum class StreamMode { ADAPTIVE_MERGE, HLS_VARIANT, HLS_MASTER, MUXED }

    private var player: ExoPlayer? = null
    private var uiPlayer: Player? = null
    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    // Cached pool so tapping a new video never blocks behind a slow fallback
    // or SponsorBlock fetch for a previous video.
    private val ioExecutor = Executors.newCachedThreadPool()

    private val queue = mutableListOf<YtItem>()
    private val playedVideoIds = mutableSetOf<String>()
    private var queueIndex = -1
    private var fetchingAutoNext = false
    private var currentStreamInfo: VideoStreamInfo? = null
    private var currentStreamMode: StreamMode? = null
    private val attemptedModes = mutableSetOf<StreamMode>()
    private var sponsorSegments: List<SponsorSegment> = emptyList()
    private val skippedSegmentKeys = mutableSetOf<String>()
    private var isLoading = false
    private var loadingStatus: String? = null
    private var isAudioOnly = false
    private var isVideoSurfaceVisible = false
    private var captionsEnabled = false
    private var selectedHeight: Int? = null
    private var activeVideoHeight: Int? = null
    private var preparedVideoHeight: Int? = null
    private var playbackSpeed = 1.0f
    private var lastError: String? = null
    private var lastSponsorSkip: String? = null
    private var currentArtwork: Bitmap? = null
    private var currentArtworkUrl: String? = null
    private var isForegroundActive = false
    private var loadGeneration = 0
    private var currentTrackStartedPlaying = false
    private var streamReachedReady = false
    private var pendingSeekPositionMs: Long? = null
    private var autoRecoveryAttempts = 0

    // Background pre-resolved stream for the next video in the queue (15m TTL)
    // so Next (⏭) and Autoplay start nearly instantly even over VPN.
    @Volatile
    private var preloadedStreamInfo: VideoStreamInfo? = null
    @Volatile
    private var preloadedAtMs: Long = 0L
    @Volatile
    private var preloadingVideoId: String? = null

    private val sponsorTicker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.isPlaying) return
            if (YouTubeStore.isSponsorBlockEnabled(this@YouTubePlaybackService) &&
                sponsorSegments.isNotEmpty()
            ) {
                val pos = p.currentPosition
                val hit = sponsorSegments.firstOrNull { seg ->
                    pos in seg.startMs until (seg.endMs - 400L)
                }
                if (hit != null) {
                    val key = "${currentStreamInfo?.videoId}:${hit.startMs}"
                    if (skippedSegmentKeys.add(key)) {
                        p.seekTo(hit.endMs)
                        lastSponsorSkip = "Skipped ${hit.category}"
                        broadcastState()
                    }
                }
            }
            maybePreloadNextTrack(p)
            mainHandler.postDelayed(this, 750L)
        }
    }

    /**
     * Silently pre-resolves the next video in the queue once the current track
     * has played past 8s (or has <= 20s remaining) so Next (⏭) and Autoplay
     * transitions start immediately without waiting on stream resolution.
     */
    private fun maybePreloadNextTrack(p: ExoPlayer) {
        val nextItem = queue.getOrNull(queueIndex + 1) ?: return
        if (nextItem.isPlaylist) return
        val pos = p.currentPosition
        val dur = p.duration
        val shouldPreload = pos >= 8_000L || (dur > 0L && dur != C.TIME_UNSET && (dur - pos) <= 20_000L)
        if (!shouldPreload) return
        val existing = preloadedStreamInfo
        val now = System.currentTimeMillis()
        if (existing?.videoId == nextItem.id && (now - preloadedAtMs) < PRELOAD_TTL_MS) return
        if (preloadingVideoId == nextItem.id) return
        preloadingVideoId = nextItem.id
        ioExecutor.execute {
            try {
                val info = YouTubeInnerTube.resolveStreams(applicationContext, nextItem.id).info
                if (info != null) {
                    preloadedStreamInfo = info
                    preloadedAtMs = System.currentTimeMillis()
                }
            } catch (_: Throwable) {
            } finally {
                if (preloadingVideoId == nextItem.id) {
                    preloadingVideoId = null
                }
            }
        }
    }

    private fun consumePreloadedStreamInfo(videoId: String): VideoStreamInfo? {
        val info = preloadedStreamInfo ?: return null
        val fresh = (System.currentTimeMillis() - preloadedAtMs) < PRELOAD_TTL_MS
        if (info.videoId == videoId && fresh) {
            preloadedStreamInfo = null
            preloadedAtMs = 0L
            return info
        }
        return null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ThumbnailLoader.init(this)
        createNotificationChannel()
        selectedHeight = YouTubeStore.getDefaultHeight(this).takeIf { it > 0 }
        playbackSpeed = YouTubeStore.getPlaybackSpeed(this)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                25_000, // minBufferMs (25s smooth cushion on VPN)
                60_000, // maxBufferMs (buffer up to 60s ahead via fast 2MB chunks)
                1_000,  // bufferForPlaybackMs (snappy 1s start)
                2_000   // bufferForPlaybackAfterRebufferMs
            )
            .build()

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build().apply {
                setAudioAttributes(audioAttrs, true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                setPlaybackSpeed(playbackSpeed)
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            com.graball.PlayerService.stopAndClear(applicationContext)
                            currentTrackStartedPlaying = true
                            startSponsorTicker()
                        } else {
                            stopSponsorTicker()
                            saveCurrentProgress()
                        }
                        broadcastState()
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        if (currentStreamMode == StreamMode.HLS_MASTER && selectedHeight != null) {
                            applyTrackSelection()
                        }
                        refreshActiveVideoHeight(tracks)
                        broadcastState()
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.height > 0) {
                            val normalized = normalizeQualityHeight(videoSize.height)
                            if (activeVideoHeight != normalized) {
                                activeVideoHeight = normalized
                                broadcastState()
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            streamReachedReady = true
                            lastError = null
                            val deferredSeek = pendingSeekPositionMs
                            if (deferredSeek != null) {
                                pendingSeekPositionMs = null
                                val dur = player?.duration?.takeIf { it > 0L && it != C.TIME_UNSET }
                                val target = if (dur != null) {
                                    deferredSeek.coerceIn(0L, (dur - 500L).coerceAtLeast(0L))
                                } else {
                                    deferredSeek.coerceAtLeast(0L)
                                }
                                player?.seekTo(target)
                            }
                        } else if (playbackState == Player.STATE_ENDED) {
                            saveCurrentProgress(resetToZero = true)
                            playNext()
                        }
                        broadcastState()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer error ($currentStreamMode): ${error.message}", error)
                        // Automatically cascade through alternative stream modes
                        // (HLS -> Adaptive Merge -> Muxed) before surfacing an error.
                        // Preserve any deferred seek or active playback position safely.
                        val info = currentStreamInfo
                        val pos = pendingSeekPositionMs
                            ?: if (currentTrackStartedPlaying || streamReachedReady) {
                                player?.currentPosition?.coerceAtLeast(0L) ?: 0L
                            } else {
                                0L
                            }
                        if (info != null && error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                            evictStreamFromCache(info)
                        }
                        if (info != null && tryNextStreamMode(info, pos, playImmediately = true)) {
                            return
                        }
                        // If a transient HTTP error (e.g. 403/416/503 from an early seek or
                        // expired/rate-limited GoogleVideo range) hits with no fallback mode
                        // left, silently re-resolve a fresh stream once at the target position.
                        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                            autoRecoveryAttempts < 1 &&
                            queueIndex in queue.indices
                        ) {
                            autoRecoveryAttempts++
                            Log.w(TAG, "Auto-recovering from BAD_HTTP_STATUS at ${pos}ms")
                            loadAtIndex(
                                queueIndex,
                                startFromBeginning = (pos == 0L),
                                overrideStartPositionMs = pos.takeIf { it > 0L },
                                resetRecoveryCounter = false
                            )
                            return
                        }
                        lastError = "Playback failed (${error.errorCodeName}). Tap Retry or Download."
                        broadcastState()
                    }
                })
            }
        player = exo

        val openIntent = Intent(this, YouTubePlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val sessionActivity = PendingIntent.getActivity(this, 100, openIntent, flags)
        // Wrap ExoPlayer so lock-screen, notification, and Bluetooth headset
        // Next / Previous buttons navigate our service-managed play queue.
        val sessionPlayer = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun hasNextMediaItem(): Boolean = queueIndex + 1 in queue.indices
            override fun hasPreviousMediaItem(): Boolean = queueIndex > 0

            override fun seekToNext() {
                playNext()
            }

            override fun seekToNextMediaItem() {
                playNext()
            }

            override fun seekToPrevious() {
                playPrevious()
            }

            override fun seekToPreviousMediaItem() {
                playPrevious()
            }

            override fun seekTo(positionMs: Long) {
                safeSeekTo(positionMs)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                safeSeekTo(positionMs)
            }

            override fun seekForward() {
                seekBy(10_000L)
            }

            override fun seekBack() {
                seekBy(-10_000L)
            }
        }
        uiPlayer = sessionPlayer
        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(sessionActivity)
            .build()

        pendingAction?.let { action ->
            pendingAction = null
            action(this)
        }
        broadcastState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                togglePlayPause()
                return START_STICKY
            }
            ACTION_NEXT -> {
                playNext()
                return START_STICKY
            }
            ACTION_PREV -> {
                playPrevious()
                return START_STICKY
            }
            ACTION_STOP -> {
                stopPlayback()
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.playbackState == Player.STATE_IDLE) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopSponsorTicker()
        saveCurrentProgress()
        stopForegroundCompat(removeNotification = true)
        mediaSession?.release()
        mediaSession = null
        uiPlayer = null
        player?.release()
        player = null
        ioExecutor.shutdownNow()
        if (instance === this) {
            instance = null
        }
        currentState = YouTubePlaybackState()
        notifyListeners()
        super.onDestroy()
    }

    // ---- Public Player Controls ----

    /** Returns the seek-safe Player wrapper for binding to `PlayerView`. */
    fun getPlayer(): Player? = uiPlayer ?: player

    fun isReadyForSeek(): Boolean {
        val p = player ?: return false
        if (isLoading || currentStreamInfo == null) return false
        return streamReachedReady || p.playbackState == Player.STATE_READY
    }

    /**
     * Guards ExoPlayer from premature seeks while a stream is still resolving
     * or completing initial prepare/buffering:
     * - If the stream is already ready, seeks immediately (clamped safely inside duration).
     * - If the stream is preparing/buffering with a known duration, defers the
     *   seek in [pendingSeekPositionMs] and executes it the instant `STATE_READY` fires.
     * - If still resolving (`isLoading`) or duration isn't known yet, ignores safely.
     */
    private fun safeSeekTo(positionMs: Long) {
        val p = player ?: return
        if (isLoading || currentStreamInfo == null) return
        val rawDur = p.duration
        val validDur = rawDur.takeIf { it > 0L && it != C.TIME_UNSET }
        val clamped = if (validDur != null) {
            positionMs.coerceIn(0L, (validDur - 500L).coerceAtLeast(0L))
        } else {
            positionMs.coerceAtLeast(0L)
        }
        if (isReadyForSeek()) {
            pendingSeekPositionMs = null
            p.seekTo(clamped)
        } else if (validDur != null) {
            // Stream is still doing initial prepare/buffering; wait until STATE_READY
            // so we don't abort header/manifest loads and trigger BAD_HTTP_STATUS.
            pendingSeekPositionMs = clamped
        }
    }

    fun playItem(item: YtItem, audioOnly: Boolean = false) {
        if (item.isPlaylist) return
        saveCurrentProgress()
        val existingIdx = queue.indexOfFirst { it.id == item.id }
        if (existingIdx >= 0) {
            queueIndex = existingIdx
        } else if (queueIndex < 0 && queue.isNotEmpty()) {
            queue.add(0, item)
            queueIndex = 0
        } else {
            // Fresh video tapped: keep history before it, replace stale auto-next tail
            // so Next (⏭) immediately queues related songs for this new track!
            if (queueIndex in queue.indices && queueIndex < queue.lastIndex) {
                queue.subList(queueIndex + 1, queue.size).clear()
            }
            queue.add(item)
            queueIndex = queue.lastIndex
        }
        isAudioOnly = audioOnly
        loadAtIndex(queueIndex)
    }

    fun playQueue(items: List<YtItem>, startIndex: Int = 0, audioOnly: Boolean = false) {
        val videos = items.filter { !it.isPlaylist }
        if (videos.isEmpty()) return
        saveCurrentProgress()
        queue.clear()
        queue.addAll(videos)
        queueIndex = startIndex.coerceIn(0, queue.lastIndex)
        isAudioOnly = audioOnly
        loadAtIndex(queueIndex)
    }

    fun addToQueue(item: YtItem) {
        if (item.isPlaylist) return
        if (queue.none { it.id == item.id }) {
            queue.add(item)
        }
        if (queueIndex < 0 && queue.isNotEmpty()) {
            queueIndex = 0
            loadAtIndex(0)
        } else {
            broadcastState()
        }
    }

    fun removeFromQueue(index: Int) {
        if (index !in queue.indices) return
        if (index == queueIndex) {
            queue.removeAt(index)
            if (queue.isEmpty()) {
                stopPlayback()
            } else {
                queueIndex = index.coerceAtMost(queue.lastIndex)
                loadAtIndex(queueIndex)
            }
        } else {
            queue.removeAt(index)
            if (index < queueIndex) queueIndex--
            broadcastState()
        }
    }

    fun playNext(): Boolean {
        if (queueIndex + 1 in queue.indices) {
            saveCurrentProgress()
            queueIndex++
            loadAtIndex(queueIndex, startFromBeginning = true)
            return true
        }
        // If user hit Next before background related-videos queue finished loading,
        // fetch related tracks on the fly and immediately advance to the next one!
        val cur = queue.getOrNull(queueIndex) ?: return false
        if (!fetchingAutoNext) {
            fetchingAutoNext = true
            val gen = loadGeneration
            ioExecutor.execute {
                val related = YouTubeInnerTube.fetchRelated(cur.id, applicationContext)
                mainHandler.post {
                    fetchingAutoNext = false
                    if (gen != loadGeneration) return@post
                    val added = appendRelatedToQueue(related)
                    if (added > 0 && queueIndex + 1 in queue.indices) {
                        saveCurrentProgress()
                        queueIndex++
                        loadAtIndex(queueIndex, startFromBeginning = true)
                    }
                }
            }
            return true
        }
        return false
    }

    fun playPrevious(): Boolean {
        val p = player
        if (p != null && p.currentPosition > 4_000L) {
            p.seekTo(0L)
            return true
        }
        if (queueIndex - 1 in queue.indices) {
            saveCurrentProgress()
            queueIndex--
            loadAtIndex(queueIndex, startFromBeginning = true)
            return true
        }
        return false
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying || p.playWhenReady) {
            p.pause()
        } else {
            com.graball.PlayerService.stopAndClear(applicationContext)
            p.play()
        }
    }

    fun pauseIfPlaying() {
        val p = player ?: return
        if (p.isPlaying) p.pause()
    }

    fun seekTo(positionMs: Long) {
        safeSeekTo(positionMs)
    }

    fun positionMs(): Long =
        if (isLoading) 0L else (player?.currentPosition?.coerceAtLeast(0L) ?: 0L)

    fun durationMs(): Long =
        if (isLoading) 0L else (player?.duration?.takeIf { it > 0L } ?: 0L)
    fun audioSessionId(): Int = player?.audioSessionId ?: 0

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        if (!isReadyForSeek()) return
        val dur = p.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: return
        val target = (p.currentPosition + deltaMs).coerceIn(0L, (dur - 500L).coerceAtLeast(0L))
        p.seekTo(target)
    }

    private fun isEffectivelyAudioOnly(): Boolean = isAudioOnly || !isVideoSurfaceVisible

    fun setAudioOnlyMode(enabled: Boolean) {
        if (isAudioOnly == enabled) return
        isAudioOnly = enabled
        applyTrackSelection()
        broadcastState()
    }

    /**
     * Tracks whether a live video surface (main player screen or active PiP
     * window) is currently visible to the user. Anytime video is NOT visible
     * (display off, minimized to mini-player, app backgrounded, or PiP closed),
     * ExoPlayer immediately disables C.TRACK_TYPE_VIDEO so zero video bytes
     * download and playback runs in battery-saving audio-only mode.
     */
    fun setVideoSurfaceVisible(visible: Boolean) {
        if (isVideoSurfaceVisible == visible) return
        isVideoSurfaceVisible = visible
        applyTrackSelection()
        broadcastState()
    }

    fun setCaptionsEnabled(enabled: Boolean) {
        if (captionsEnabled == enabled) return
        captionsEnabled = enabled
        applyTrackSelection()
        broadcastState()
    }

    /** True if the active stream has any subtitle/caption track (either HLS-embedded or WebVTT). */
    fun hasAnyCaptions(): Boolean {
        if (currentStreamInfo?.captionTracks?.isNotEmpty() == true) return true
        val p = player ?: return false
        return p.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
    }

    /** Real playable video heights detected from ExoPlayer tracks + stream metadata. */
    fun availableVideoHeights(): List<Int> {
        val heights = mutableSetOf<Int>()
        currentStreamInfo?.availableHeights()?.let { heights.addAll(it) }
        val p = player
        if (p != null) {
            for (group in p.currentTracks.groups) {
                if (group.type != C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until group.length) {
                    val h = group.getTrackFormat(i).height
                    if (h in 144..2160) heights.add(normalizeQualityHeight(h))
                }
            }
        }
        return heights.sortedDescending()
    }

    fun setQuality(height: Int?) {
        selectedHeight = height
        YouTubeStore.setDefaultHeight(this, height ?: 0)
        val info = currentStreamInfo ?: return
        val p = player ?: return
        val pos = p.currentPosition.coerceAtLeast(0L)
        if (currentStreamMode == StreamMode.HLS_MASTER) {
            // Apply an explicit TrackSelectionOverride AND seek-flush the
            // buffered low-res segments immediately so quality snaps right away.
            applyTrackSelection()
            if (isReadyForSeek()) {
                p.seekTo(pos)
            }
            broadcastState()
            return
        }
        val wasPlaying = p.playWhenReady
        attemptedModes.clear()
        prepareStream(info, resumePosMs = pos, playImmediately = wasPlaying)
        broadcastState()
    }

    fun retryCurrent() {
        if (queueIndex in queue.indices) {
            loadAtIndex(queueIndex)
        }
    }

    fun setSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.5f, 2.5f)
        YouTubeStore.setPlaybackSpeed(this, playbackSpeed)
        player?.setPlaybackSpeed(playbackSpeed)
        broadcastState()
    }

    fun stopPlayback() {
        stopSponsorTicker()
        saveCurrentProgress()
        player?.stop()
        player?.clearMediaItems()
        queue.clear()
        playedVideoIds.clear()
        queueIndex = -1
        fetchingAutoNext = false
        currentStreamInfo = null
        preloadedStreamInfo = null
        preloadedAtMs = 0L
        preloadingVideoId = null
        activeVideoHeight = null
        preparedVideoHeight = null
        currentArtwork = null
        currentArtworkUrl = null
        sponsorSegments = emptyList()
        isLoading = false
        lastError = null
        stopForegroundCompat(removeNotification = true)
        broadcastState()
        stopSelf()
    }

    // ---- Stream Loading Internals ----

    private fun loadAtIndex(
        index: Int,
        startFromBeginning: Boolean = false,
        overrideStartPositionMs: Long? = null,
        resetRecoveryCounter: Boolean = true
    ) {
        val item = queue.getOrNull(index) ?: return
        playedVideoIds.add(item.id)
        com.graball.PlayerService.stopAndClear(applicationContext)
        val gen = ++loadGeneration
        currentTrackStartedPlaying = false
        streamReachedReady = false
        pendingSeekPositionMs = null
        if (resetRecoveryCounter) {
            autoRecoveryAttempts = 0
        }
        // Set loading state BEFORE player?.stop() so ExoPlayer's synchronous
        // onIsPlayingChanged(false) callback keeps the foreground notification pinned.
        isLoading = true
        loadingStatus = "Connecting to YouTube…"
        lastError = null
        lastSponsorSkip = null
        currentStreamInfo = null
        currentStreamMode = null
        activeVideoHeight = null
        preparedVideoHeight = null
        attemptedModes.clear()
        sponsorSegments = emptyList()
        skippedSegmentKeys.clear()
        player?.stop()
        player?.clearMediaItems()
        player?.seekTo(0L)
        broadcastState()

        // Fast-path: if the next video was already pre-resolved in the background,
        // start preparing ExoPlayer immediately with zero network resolution wait!
        val cachedInfo = if (resetRecoveryCounter) consumePreloadedStreamInfo(item.id) else null
        if (cachedInfo != null) {
            applyResolvedStreamOnMain(
                gen = gen,
                index = index,
                item = item,
                info = cachedInfo,
                startFromBeginning = startFromBeginning,
                overrideStartPositionMs = overrideStartPositionMs
            )
            return
        }

        ioExecutor.execute {
            try {
                val result = YouTubeInnerTube.resolveStreams(
                    applicationContext,
                    item.id
                ) { status ->
                    mainHandler.post {
                        if (gen == loadGeneration && isLoading) {
                            loadingStatus = status
                            broadcastState()
                        }
                    }
                }
                mainHandler.post {
                    if (gen != loadGeneration) return@post
                    val info = result.info
                    if (info == null) {
                        isLoading = false
                        loadingStatus = null
                        lastError = result.error
                            ?: "Couldn't load stream for this video. Try Retry or Download."
                        broadcastState()
                        return@post
                    }
                    applyResolvedStreamOnMain(
                        gen = gen,
                        index = index,
                        item = item,
                        info = info,
                        startFromBeginning = startFromBeginning,
                        overrideStartPositionMs = overrideStartPositionMs
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected error loading video ${item.id}", t)
                mainHandler.post {
                    if (gen != loadGeneration) return@post
                    isLoading = false
                    loadingStatus = null
                    lastError = "Load error: ${t.message ?: t.javaClass.simpleName}"
                    broadcastState()
                }
            }
        }
    }

    private fun applyResolvedStreamOnMain(
        gen: Int,
        index: Int,
        item: YtItem,
        info: VideoStreamInfo,
        startFromBeginning: Boolean,
        overrideStartPositionMs: Long?
    ) {
        if (gen != loadGeneration) return
        currentStreamInfo = info
        val enriched = item.copy(
            title = info.title,
            channel = info.channel.ifBlank { item.channel },
            thumbnailUrl = info.thumbnailUrl.ifBlank { item.thumbnailUrl }
        )
        if (index in queue.indices) {
            queue[index] = enriched
        }
        val savedPos = if (startFromBeginning) {
            0L
        } else {
            YouTubeStore.getSavedPosition(applicationContext, item.id)
        }
        val resumePos = when {
            overrideStartPositionMs != null -> {
                val maxMs = (info.durationSec * 1000L - 2_000L).coerceAtLeast(0L)
                if (maxMs > 0L) overrideStartPositionMs.coerceIn(0L, maxMs) else overrideStartPositionMs.coerceAtLeast(0L)
            }
            !startFromBeginning && info.durationSec > 30 &&
                savedPos in 5_000L until (info.durationSec * 1000L - 10_000L) -> savedPos
            else -> 0L
        }
        YouTubeStore.recordHistory(applicationContext, enriched, resumePos)
        prepareStream(info, resumePosMs = resumePos, playImmediately = true)
        isLoading = false
        loadingStatus = null
        broadcastState()

        // Fetch SponsorBlock asynchronously AFTER playback starts so it
        // never delays video startup even if sponsor.ajay.app is slow.
        if (YouTubeStore.isSponsorBlockEnabled(applicationContext)) {
            ioExecutor.execute {
                val segs = SponsorBlockClient.fetchSegments(item.id)
                mainHandler.post {
                    if (gen == loadGeneration) {
                        sponsorSegments = segs
                    }
                }
            }
        }

        // Auto-populate Up Next queue with YouTube's related videos whenever
        // we reach the end of the queue, so Next (⏭) and Autoplay always work
        // from Music Player, Notification, PiP, and Bluetooth controls.
        if (queueIndex >= queue.lastIndex && !fetchingAutoNext) {
            fetchingAutoNext = true
            ioExecutor.execute {
                val related = YouTubeInnerTube.fetchRelated(item.id, applicationContext)
                mainHandler.post {
                    fetchingAutoNext = false
                    if (gen == loadGeneration && appendRelatedToQueue(related) > 0) {
                        broadcastState()
                    }
                }
            }
        }
    }

    private fun appendRelatedToQueue(related: List<YtItem>): Int {
        val existingIds = queue.map { it.id }.toMutableSet()
        var added = 0
        for (rel in related) {
            if (rel.isPlaylist) continue
            if (rel.id in existingIds || rel.id in playedVideoIds) continue
            queue.add(rel)
            existingIds.add(rel.id)
            added++
            if (added >= 15) break
        }
        return added
    }

    private fun evictStreamFromCache(info: VideoStreamInfo) {
        val cache = mediaCache ?: return
        val urls = buildList {
            info.hlsUrl?.let { add(it) }
            info.hlsVariants.forEach { add(it.url) }
            info.videoStreams.forEach { add(it.url) }
            info.audioStreams.forEach { add(it.url) }
            info.muxedStreams.forEach { add(it.url) }
        }
        ioExecutor.execute {
            for (u in urls) {
                try {
                    cache.removeResource(u)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun prepareStream(info: VideoStreamInfo, resumePosMs: Long, playImmediately: Boolean) {
        attemptedModes.clear()
        if (!tryNextStreamMode(info, resumePosMs, playImmediately)) {
            lastError = "No playable format available for this video"
            broadcastState()
        }
    }

    /**
     * Try the next unattempted stream mode in priority order:
     * 1) HLS (chunked, unthrottled, instant startup, supports quality cap & audio-only!)
     * 2) Adaptive Merge (separate H.264 video + AAC audio for battery-saver audio-only)
     * 3) Muxed MP4 (universal single-file fallback)
     */
    private fun tryNextStreamMode(
        info: VideoStreamInfo,
        resumePosMs: Long,
        playImmediately: Boolean
    ): Boolean {
        val p = player ?: return false
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(info.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)

        // Wrap GoogleVideo requests in 2 MB bounded `&range=start-end` chunks to
        // completely bypass YouTube's single-connection throttle on VPN/tv_samsung.
        val chunkedFactory = YouTubeChunkedDataSource.Factory(httpFactory)

        // Wrap upstream HTTP with a bounded 40 MB LRU disk cache so replays,
        // loops, and backward seeks use zero extra network data.
        val dsFactory: DataSource.Factory = getOrCreateMediaCache(applicationContext)?.let { cache ->
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(chunkedFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } ?: chunkedFactory

        val meta = MediaMetadata.Builder()
            .setTitle(info.title)
            .setArtist(info.channel)
            .setArtworkUri(info.thumbnailUrl.takeIf { it.startsWith("http") }?.let { Uri.parse(it) })
            .build()

        fun attachSubtitles(baseSource: MediaSource): MediaSource {
            val tracks = info.captionTracks
            if (tracks.isEmpty()) return baseSource
            val subSources = tracks.take(4).mapIndexed { idx, cap ->
                val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(cap.vttUrl))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(cap.langCode)
                    .setLabel(cap.label)
                    .setSelectionFlags(if (idx == 0) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
                SingleSampleMediaSource.Factory(httpFactory)
                    .createMediaSource(subConfig, C.TIME_UNSET)
            }
            val all = arrayOf(baseSource) + subSources.toTypedArray()
            return MergingMediaSource(*all)
        }

        // 1) Direct Adaptive Merge FIRST (exact chosen 144p-2160p video + best M4A audio):
        // Deterministic quality switching (~0.3s), zero ABR downgrade, true 1440p/4K VP9,
        // and zero video bytes downloaded in Audio-Only mode.
        val videoStream = info.pickVideoStream(selectedHeight)
        val audioStream = info.bestAudio()
        if (StreamMode.ADAPTIVE_MERGE !in attemptedModes && videoStream != null && audioStream != null) {
            attemptedModes.add(StreamMode.ADAPTIVE_MERGE)
            currentStreamMode = StreamMode.ADAPTIVE_MERGE
            preparedVideoHeight = videoStream.height.takeIf { it > 0 }?.let { normalizeQualityHeight(it) }
            activeVideoHeight = preparedVideoHeight
            val vItem = MediaItem.Builder()
                .setUri(videoStream.url)
                .setMediaMetadata(meta)
                .build()
            val aItem = MediaItem.Builder()
                .setUri(audioStream.url)
                .setMediaMetadata(meta)
                .build()
            val vSrc = ProgressiveMediaSource.Factory(dsFactory).createMediaSource(vItem)
            val aSrc = ProgressiveMediaSource.Factory(dsFactory).createMediaSource(aItem)
            val merged = attachSubtitles(MergingMediaSource(vSrc, aSrc))
            applyTrackSelection()
            p.setMediaSource(merged, resumePosMs)
            p.setPlaybackSpeed(playbackSpeed)
            p.playWhenReady = playImmediately
            p.prepare()
            return true
        }

        // 2) Exact Per-Resolution HLS Variant SECOND (e.g. web_safari 144p-1080p muxed HLS on VPN):
        // Hands ExoPlayer ONLY the chosen resolution's .m3u8 playlist so it starts in HD
        // (720p Auto) and switches immediately on tap without getting stuck on 360p ABR!
        val hlsVariant = info.pickHlsVariant(selectedHeight)
        if (StreamMode.HLS_VARIANT !in attemptedModes && hlsVariant != null) {
            attemptedModes.add(StreamMode.HLS_VARIANT)
            currentStreamMode = StreamMode.HLS_VARIANT
            preparedVideoHeight = hlsVariant.height.takeIf { it > 0 }?.let { normalizeQualityHeight(it) }
            activeVideoHeight = preparedVideoHeight
            val item = MediaItem.Builder()
                .setUri(hlsVariant.url)
                .setMediaMetadata(meta)
                .build()
            val src = attachSubtitles(HlsMediaSource.Factory(dsFactory).createMediaSource(item))
            applyTrackSelection()
            p.setMediaSource(src, resumePosMs)
            p.setPlaybackSpeed(playbackSpeed)
            p.playWhenReady = playImmediately
            p.prepare()
            return true
        }

        // 3) HLS Master Manifest THIRD (live streams or separate-audio HLS manifests)
        if (StreamMode.HLS_MASTER !in attemptedModes && !info.hlsUrl.isNullOrBlank()) {
            attemptedModes.add(StreamMode.HLS_MASTER)
            currentStreamMode = StreamMode.HLS_MASTER
            preparedVideoHeight = null
            activeVideoHeight = null
            val item = MediaItem.Builder()
                .setUri(info.hlsUrl)
                .setMediaMetadata(meta)
                .build()
            val src = attachSubtitles(HlsMediaSource.Factory(dsFactory).createMediaSource(item))
            applyTrackSelection()
            p.setMediaSource(src, resumePosMs)
            p.setPlaybackSpeed(playbackSpeed)
            p.playWhenReady = playImmediately
            p.prepare()
            return true
        }

        // 4) Muxed single-file MP4 last-resort fallback
        val muxed = info.pickMuxedStream(selectedHeight)
        if (StreamMode.MUXED !in attemptedModes && muxed != null) {
            attemptedModes.add(StreamMode.MUXED)
            currentStreamMode = StreamMode.MUXED
            preparedVideoHeight = muxed.height.takeIf { it > 0 }?.let { normalizeQualityHeight(it) }
            activeVideoHeight = preparedVideoHeight
            val item = MediaItem.Builder()
                .setUri(muxed.url)
                .setMediaMetadata(meta)
                .build()
            val src = attachSubtitles(ProgressiveMediaSource.Factory(dsFactory).createMediaSource(item))
            applyTrackSelection()
            p.setMediaSource(src, resumePosMs)
            p.setPlaybackSpeed(playbackSpeed)
            p.playWhenReady = playImmediately
            p.prepare()
            return true
        }

        return false
    }

    private fun refreshActiveVideoHeight(tracks: Tracks) {
        val fromFormat = player?.videoFormat?.height?.takeIf { it > 0 }
        if (fromFormat != null) {
            activeVideoHeight = normalizeQualityHeight(fromFormat)
            return
        }
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO || !group.isSelected) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    val h = group.getTrackFormat(i).height
                    if (h > 0) {
                        activeVideoHeight = normalizeQualityHeight(h)
                        return
                    }
                }
            }
        }
    }

    private fun normalizeQualityHeight(rawHeight: Int): Int {
        val standards = intArrayOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
        for (std in standards) {
            if (kotlin.math.abs(rawHeight - std) <= 12) return std
        }
        return rawHeight
    }

    private fun applyTrackSelection() {
        val p = player ?: return
        val chosen = selectedHeight
        val builder = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isEffectivelyAudioOnly())
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsEnabled)
            .setPreferredVideoMimeType(null)

        if (currentStreamMode == StreamMode.HLS_MASTER) {
            if (chosen != null) {
                var bestGroup: Tracks.Group? = null
                var bestTrackIdx = -1
                var bestScore = Long.MIN_VALUE
                for (group in p.currentTracks.groups) {
                    if (group.type != C.TRACK_TYPE_VIDEO) continue
                    for (i in 0 until group.length) {
                        val fmt = group.getTrackFormat(i)
                        val h = normalizeQualityHeight(fmt.height)
                        if (h <= 0) continue
                        val score = when {
                            h == chosen -> 1_000_000_000L + fmt.bitrate.coerceAtLeast(0)
                            h < chosen -> (h * 100_000L) + fmt.bitrate.coerceAtLeast(0)
                            else -> -kotlin.math.abs(h - chosen) * 100_000L
                        }
                        if (score > bestScore) {
                            bestScore = score
                            bestGroup = group
                            bestTrackIdx = i
                        }
                    }
                }
                if (bestGroup != null && bestTrackIdx >= 0) {
                    builder
                        .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                        .setMinVideoSize(0, 0)
                        .setForceHighestSupportedBitrate(false)
                        .setOverrideForType(
                            TrackSelectionOverride(bestGroup.mediaTrackGroup, listOf(bestTrackIdx))
                        )
                } else {
                    builder
                        .setMaxVideoSize(Int.MAX_VALUE, chosen + 16)
                        .setMinVideoSize(0, 0)
                        .setForceHighestSupportedBitrate(true)
                }
            } else {
                builder
                    .setMaxVideoSize(Int.MAX_VALUE, 1080)
                    .setMinVideoSize(0, 0)
                    .setForceHighestSupportedBitrate(false)
            }
        } else {
            // Single-resolution modes (ADAPTIVE_MERGE, HLS_VARIANT, MUXED):
            // remove size caps so 1440p / 2160p streams are never rejected by ExoPlayer.
            builder
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .setMinVideoSize(0, 0)
                .setForceHighestSupportedBitrate(false)
        }
        p.trackSelectionParameters = builder.build()
    }

    private fun applyVideoTrackEnabled(enabled: Boolean) {
        isAudioOnly = !enabled
        applyTrackSelection()
    }

    private fun startSponsorTicker() {
        mainHandler.removeCallbacks(sponsorTicker)
        mainHandler.postDelayed(sponsorTicker, 750L)
    }

    private fun stopSponsorTicker() {
        mainHandler.removeCallbacks(sponsorTicker)
    }

    private fun saveCurrentProgress(resetToZero: Boolean = false) {
        val item = queue.getOrNull(queueIndex) ?: return
        val pos = if (resetToZero || !currentTrackStartedPlaying) {
            0L
        } else {
            player?.currentPosition ?: 0L
        }
        YouTubeStore.recordHistory(applicationContext, item, pos)
    }

    private fun broadcastState() {
        val p = player
        val bufferingToPlay = p != null && p.playWhenReady && p.playbackState == Player.STATE_BUFFERING
        currentState = YouTubePlaybackState(
            currentItem = queue.getOrNull(queueIndex),
            streamInfo = currentStreamInfo,
            queue = queue.toList(),
            queueIndex = queueIndex,
            isLoading = isLoading,
            loadingStatus = loadingStatus,
            isPlaying = p?.isPlaying == true,
            isBuffering = bufferingToPlay,
            isReadyForSeek = isReadyForSeek(),
            isAudioOnly = isAudioOnly,
            isVideoDisabled = isEffectivelyAudioOnly(),
            captionsEnabled = captionsEnabled,
            selectedHeight = selectedHeight,
            activeVideoHeight = activeVideoHeight ?: preparedVideoHeight,
            playbackSpeed = playbackSpeed,
            error = lastError,
            lastSponsorSkip = lastSponsorSkip
        )
        syncPlaybackNotification()
        notifyListeners()
    }

    // ---- Foreground Media Notification (always visible in BG / Audio-Only / Lock Screen) ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "YouTube Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for YouTube video & background audio playback"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(channel)
        }
    }

    private fun syncPlaybackNotification() {
        val item = currentState.currentItem
        if (item == null) {
            stopForegroundCompat(removeNotification = true)
            return
        }
        // Load thumbnail bitmap asynchronously for the notification artwork
        val thumbUrl = currentStreamInfo?.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: item.thumbnailUrl
        if (thumbUrl.isNotBlank() && thumbUrl != currentArtworkUrl) {
            currentArtworkUrl = thumbUrl
            currentArtwork = null
            ioExecutor.execute {
                val bmp = ThumbnailLoader.loadSync(thumbUrl, targetWidthPx = 256)
                if (bmp != null) {
                    mainHandler.post {
                        if (currentArtworkUrl == thumbUrl) {
                            currentArtwork = bmp
                            syncPlaybackNotification()
                        }
                    }
                }
            }
        }

        val notif = buildPlaybackNotification(item)
        val shouldForeground = currentState.isPlaying || currentState.isLoading || currentState.isBuffering
        if (shouldForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        YT_NOTIFICATION_ID,
                        notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(YT_NOTIFICATION_ID, notif)
                }
                isForegroundActive = true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground failed: ${e.message}")
            }
        } else {
            if (isForegroundActive) {
                stopForegroundCompat(removeNotification = false)
            }
            try {
                (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.notify(YT_NOTIFICATION_ID, notif)
            } catch (_: Exception) {
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (!isForegroundActive && !removeNotification) return
        isForegroundActive = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(
                    if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH
                )
            } else {
                stopForeground(removeNotification)
            }
            if (removeNotification) {
                (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.cancel(YT_NOTIFICATION_ID)
            }
        } catch (_: Exception) {
        }
    }

    private fun contentTapPendingIntent(): PendingIntent {
        // In Audio-Only mode, tapping the notification / media bar opens GrabAll's
        // full-screen Music Player directly (with lyrics, download & Watch Video button).
        // In normal Video mode, it opens YouTubePlayerActivity.
        val openIntent = if (currentState.isAudioOnly) {
            Intent(this, com.graball.PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("open_now_playing", true)
            }
        } else {
            Intent(this, YouTubePlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val reqCode = if (currentState.isAudioOnly) 205 else 200
        return PendingIntent.getActivity(this, reqCode, openIntent, piFlags)
    }

    private fun buildPlaybackNotification(item: YtItem): Notification {
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPi = contentTapPendingIntent()
        mediaSession?.setSessionActivity(contentPi)

        fun svcAction(action: String, reqCode: Int): PendingIntent {
            val i = Intent(this, YouTubePlaybackService::class.java).setAction(action)
            return PendingIntent.getService(this, reqCode, i, piFlags)
        }

        val playing = currentState.isPlaying
        val activeOrPreparing = playing || currentState.isLoading || currentState.isBuffering
        val sub = buildString {
            if (currentState.isVideoDisabled) append("🎧 Background Audio")
            else if (currentState.isLoading) append(currentState.loadingStatus ?: "Loading…")
            else if (currentState.isBuffering) append("Buffering…")
            else if (playing) append("Playing")
            else append("Paused")
            if (item.channel.isNotBlank()) {
                append(" · ").append(item.channel)
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(item.title)
            .setContentText(sub)
            .setContentIntent(contentPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(activeOrPreparing)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Prev",
                svcAction(ACTION_PREV, 201)
            )
            .addAction(
                if (activeOrPreparing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (activeOrPreparing) "Pause" else "Play",
                svcAction(ACTION_PLAY_PAUSE, 202)
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                svcAction(ACTION_NEXT, 203)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                svcAction(ACTION_STOP, 204)
            )

        currentArtwork?.let { builder.setLargeIcon(it) }
        mediaSession?.let { session ->
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "YouTubePlaybackService"
        private const val CHANNEL_ID = "graball_yt_player"
        private const val YT_NOTIFICATION_ID = 3
        private const val MAX_MEDIA_CACHE_BYTES = 40L * 1024L * 1024L
        private const val PRELOAD_TTL_MS = 15L * 60L * 1000L

        const val ACTION_PLAY_PAUSE = "com.graball.youtube.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.graball.youtube.ACTION_NEXT"
        const val ACTION_PREV = "com.graball.youtube.ACTION_PREV"
        const val ACTION_STOP = "com.graball.youtube.ACTION_STOP"

        @Volatile
        private var mediaCache: SimpleCache? = null

        @Synchronized
        fun getOrCreateMediaCache(context: Context): SimpleCache? {
            if (mediaCache != null) return mediaCache
            return try {
                val dir = File(context.applicationContext.cacheDir, "yt_media_cache")
                if (!dir.exists()) dir.mkdirs()
                val evictor = LeastRecentlyUsedCacheEvictor(MAX_MEDIA_CACHE_BYTES)
                val db = StandaloneDatabaseProvider(context.applicationContext)
                SimpleCache(dir, evictor, db).also { mediaCache = it }
            } catch (e: Exception) {
                Log.w(TAG, "Media cache init failed: ${e.message}")
                null
            }
        }

        @Synchronized
        fun clearMediaCache(context: Context) {
            try {
                mediaCache?.release()
            } catch (_: Exception) {
            }
            mediaCache = null
            try {
                File(context.applicationContext.cacheDir, "yt_media_cache").deleteRecursively()
            } catch (_: Exception) {
            }
        }

        @Volatile
        var instance: YouTubePlaybackService? = null
            private set

        /**
         * Stop YouTube playback, save current video timestamp to history,
         * cancel notification ID 3 (even if detached/paused), and stop the service.
         */
        fun stopAndClear(context: Context) {
            val svc = instance
            if (svc != null) {
                svc.stopPlayback()
            } else {
                try {
                    (context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                        ?.cancel(YT_NOTIFICATION_ID)
                } catch (_: Exception) {
                }
            }
        }

        @Volatile
        var currentState: YouTubePlaybackState = YouTubePlaybackState()
            private set

        private val listeners = CopyOnWriteArrayList<(YouTubePlaybackState) -> Unit>()
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var pendingAction: ((YouTubePlaybackService) -> Unit)? = null

        fun addListener(listener: (YouTubePlaybackState) -> Unit) {
            listeners.add(listener)
            listener(currentState)
        }

        fun removeListener(listener: (YouTubePlaybackState) -> Unit) {
            listeners.remove(listener)
        }

        private fun notifyListeners() {
            val snap = currentState
            mainHandler.post {
                listeners.forEach { it(snap) }
            }
        }

        /** Ensure the service is started and run [action] on it immediately once ready. */
        fun withService(context: android.content.Context, action: (YouTubePlaybackService) -> Unit) {
            val svc = instance
            if (svc != null) {
                action(svc)
            } else {
                pendingAction = action
                val intent = Intent(context, YouTubePlaybackService::class.java)
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                }
            }
        }
    }
}
