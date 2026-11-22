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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
    val isAudioOnly: Boolean = false,
    val captionsEnabled: Boolean = false,
    val selectedHeight: Int? = null,
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

    private enum class StreamMode { HLS, ADAPTIVE_MERGE, MUXED }

    private var player: ExoPlayer? = null
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
    private var captionsEnabled = false
    private var selectedHeight: Int? = null
    private var playbackSpeed = 1.0f
    private var lastError: String? = null
    private var lastSponsorSkip: String? = null
    private var currentArtwork: Bitmap? = null
    private var currentArtworkUrl: String? = null
    private var isForegroundActive = false
    private var loadGeneration = 0

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
            mainHandler.postDelayed(this, 750L)
        }
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
                15_000, // minBufferMs
                30_000, // maxBufferMs
                1_500,  // bufferForPlaybackMs
                3_000   // bufferForPlaybackAfterRebufferMs
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
                            startSponsorTicker()
                        } else {
                            stopSponsorTicker()
                            saveCurrentProgress()
                        }
                        broadcastState()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            saveCurrentProgress(resetToZero = true)
                            playNext()
                        }
                        broadcastState()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer error ($currentStreamMode): ${error.message}", error)
                        // Automatically cascade through alternative stream modes
                        // (HLS -> Adaptive Merge -> Muxed) before surfacing an error.
                        val info = currentStreamInfo
                        val pos = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
                        if (info != null && tryNextStreamMode(info, pos, playImmediately = true)) {
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
        }
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

    fun getPlayer(): ExoPlayer? = player

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
            loadAtIndex(queueIndex)
            return true
        }
        // If user hit Next before background related-videos queue finished loading,
        // fetch related tracks on the fly and immediately advance to the next one!
        val cur = queue.getOrNull(queueIndex) ?: return false
        if (!fetchingAutoNext) {
            fetchingAutoNext = true
            val gen = loadGeneration
            ioExecutor.execute {
                val related = YouTubeInnerTube.fetchRelated(cur.id)
                mainHandler.post {
                    fetchingAutoNext = false
                    if (gen != loadGeneration) return@post
                    val added = appendRelatedToQueue(related)
                    if (added > 0 && queueIndex + 1 in queue.indices) {
                        saveCurrentProgress()
                        queueIndex++
                        loadAtIndex(queueIndex)
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
            loadAtIndex(queueIndex)
            return true
        }
        return false
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            com.graball.PlayerService.instance?.pauseIfPlaying()
            p.play()
        }
    }

    fun pauseIfPlaying() {
        val p = player ?: return
        if (p.isPlaying) p.pause()
    }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo(positionMs.coerceIn(0L, dur))
    }

    fun positionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
    fun durationMs(): Long = player?.duration?.takeIf { it > 0L } ?: 0L
    fun audioSessionId(): Int = player?.audioSessionId ?: 0

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (p.currentPosition + deltaMs).coerceIn(0L, dur)
        p.seekTo(target)
    }

    fun setAudioOnlyMode(enabled: Boolean) {
        if (isAudioOnly == enabled) return
        isAudioOnly = enabled
        applyVideoTrackEnabled(!enabled)
        broadcastState()
    }

    fun setCaptionsEnabled(enabled: Boolean) {
        if (captionsEnabled == enabled) return
        captionsEnabled = enabled
        applyTrackSelection()
        broadcastState()
    }

    fun setQuality(height: Int?) {
        selectedHeight = height
        YouTubeStore.setDefaultHeight(this, height ?: 0)
        val info = currentStreamInfo ?: return
        val p = player ?: return
        if (currentStreamMode == StreamMode.HLS) {
            // Instant seamless quality switch on HLS without re-preparing!
            applyTrackSelection()
            broadcastState()
            return
        }
        val pos = p.currentPosition
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

    private fun loadAtIndex(index: Int) {
        val item = queue.getOrNull(index) ?: return
        playedVideoIds.add(item.id)
        com.graball.PlayerService.instance?.pauseIfPlaying()
        val gen = ++loadGeneration
        player?.stop()
        isLoading = true
        loadingStatus = "Connecting to YouTube…"
        lastError = null
        lastSponsorSkip = null
        currentStreamInfo = null
        currentStreamMode = null
        attemptedModes.clear()
        sponsorSegments = emptyList()
        skippedSegmentKeys.clear()
        broadcastState()

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
                    isLoading = false
                    loadingStatus = null
                    val info = result.info
                    if (info == null) {
                        lastError = result.error
                            ?: "Couldn't load stream for this video. Try Retry or Download."
                        broadcastState()
                        return@post
                    }
                    currentStreamInfo = info
                    val enriched = item.copy(
                        title = info.title,
                        channel = info.channel.ifBlank { item.channel },
                        thumbnailUrl = info.thumbnailUrl.ifBlank { item.thumbnailUrl }
                    )
                    if (index in queue.indices) {
                        queue[index] = enriched
                    }
                    val savedPos = YouTubeStore.getSavedPosition(applicationContext, item.id)
                    val resumePos = if (info.durationSec > 30 &&
                        savedPos in 5_000L until (info.durationSec * 1000L - 10_000L)
                    ) {
                        savedPos
                    } else {
                        0L
                    }
                    YouTubeStore.recordHistory(applicationContext, enriched, resumePos)
                    prepareStream(info, resumePosMs = resumePos, playImmediately = true)
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
                            val related = YouTubeInnerTube.fetchRelated(item.id)
                            mainHandler.post {
                                fetchingAutoNext = false
                                if (gen == loadGeneration && appendRelatedToQueue(related) > 0) {
                                    broadcastState()
                                }
                            }
                        }
                    }
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

        // Wrap upstream HTTP with a bounded 40 MB LRU disk cache so replays,
        // loops, and backward seeks use zero extra network data.
        val dsFactory: DataSource.Factory = getOrCreateMediaCache(applicationContext)?.let { cache ->
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(httpFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } ?: httpFactory

        val meta = MediaMetadata.Builder()
            .setTitle(info.title)
            .setArtist(info.channel)
            .setArtworkUri(info.thumbnailUrl.takeIf { it.startsWith("http") }?.let { Uri.parse(it) })
            .build()

        // 1) HLS first when available
        if (StreamMode.HLS !in attemptedModes && !info.hlsUrl.isNullOrBlank()) {
            attemptedModes.add(StreamMode.HLS)
            currentStreamMode = StreamMode.HLS
            val item = MediaItem.Builder()
                .setUri(info.hlsUrl)
                .setMediaMetadata(meta)
                .build()
            val src = HlsMediaSource.Factory(dsFactory).createMediaSource(item)
            applyTrackSelection()
            p.setMediaSource(src, resumePosMs)
            p.setPlaybackSpeed(playbackSpeed)
            p.prepare()
            p.playWhenReady = playImmediately
            return true
        }

        // 2) Adaptive separate video + audio
        val videoStream = info.pickVideoStream(selectedHeight)
        val audioStream = info.bestAudio()
        if (StreamMode.ADAPTIVE_MERGE !in attemptedModes && videoStream != null && audioStream != null) {
            attemptedModes.add(StreamMode.ADAPTIVE_MERGE)
            currentStreamMode = StreamMode.ADAPTIVE_MERGE
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
            applyTrackSelection()
            p.setMediaSource(MergingMediaSource(vSrc, aSrc), resumePosMs)
            p.setPlaybackSpeed(playbackSpeed)
            p.prepare()
            p.playWhenReady = playImmediately
            return true
        }

        // 3) Muxed single-file MP4 fallback
        val muxed = info.pickMuxedStream(selectedHeight)
        if (StreamMode.MUXED !in attemptedModes && muxed != null) {
            attemptedModes.add(StreamMode.MUXED)
            currentStreamMode = StreamMode.MUXED
            val item = MediaItem.Builder()
                .setUri(muxed.url)
                .setMediaMetadata(meta)
                .build()
            val src = ProgressiveMediaSource.Factory(dsFactory).createMediaSource(item)
            applyTrackSelection()
            p.setMediaSource(src, resumePosMs)
            p.setPlaybackSpeed(playbackSpeed)
            p.prepare()
            p.playWhenReady = playImmediately
            return true
        }

        return false
    }

    private fun applyTrackSelection() {
        val p = player ?: return
        val maxH = selectedHeight ?: 720
        val builder = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isAudioOnly)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsEnabled)
            .setPreferredVideoMimeType("video/avc")
            .setMaxVideoSize(Int.MAX_VALUE, maxH)
        if (captionsEnabled) {
            builder.setPreferredTextLanguage("en")
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
        val pos = if (resetToZero) 0L else (player?.currentPosition ?: 0L)
        YouTubeStore.recordHistory(applicationContext, item, pos)
    }

    private fun broadcastState() {
        currentState = YouTubePlaybackState(
            currentItem = queue.getOrNull(queueIndex),
            streamInfo = currentStreamInfo,
            queue = queue.toList(),
            queueIndex = queueIndex,
            isLoading = isLoading,
            loadingStatus = loadingStatus,
            isPlaying = player?.isPlaying == true,
            isAudioOnly = isAudioOnly,
            captionsEnabled = captionsEnabled,
            selectedHeight = selectedHeight,
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
        val shouldForeground = currentState.isPlaying || currentState.isLoading
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

    private fun buildPlaybackNotification(item: YtItem): Notification {
        val openIntent = Intent(this, YouTubePlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPi = PendingIntent.getActivity(this, 200, openIntent, piFlags)

        fun svcAction(action: String, reqCode: Int): PendingIntent {
            val i = Intent(this, YouTubePlaybackService::class.java).setAction(action)
            return PendingIntent.getService(this, reqCode, i, piFlags)
        }

        val playing = currentState.isPlaying
        val sub = buildString {
            if (currentState.isAudioOnly) append("🎧 Background Audio")
            else if (currentState.isLoading) append(currentState.loadingStatus ?: "Loading…")
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
            .setOngoing(playing || currentState.isLoading)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Prev",
                svcAction(ACTION_PREV, 201)
            )
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
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
