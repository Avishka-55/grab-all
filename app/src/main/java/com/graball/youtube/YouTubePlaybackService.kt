package com.graball.youtube

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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
    private var queueIndex = -1
    private var currentStreamInfo: VideoStreamInfo? = null
    private var currentStreamMode: StreamMode? = null
    private val attemptedModes = mutableSetOf<StreamMode>()
    private var sponsorSegments: List<SponsorSegment> = emptyList()
    private val skippedSegmentKeys = mutableSetOf<String>()
    private var isLoading = false
    private var loadingStatus: String? = null
    private var isAudioOnly = false
    private var selectedHeight: Int? = null
    private var playbackSpeed = 1.0f
    private var lastError: String? = null
    private var lastSponsorSkip: String? = null
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
        } else {
            queue.add(0, item)
            queueIndex = 0
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
        if (p.isPlaying) p.pause() else p.play()
    }

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
        queueIndex = -1
        currentStreamInfo = null
        sponsorSegments = emptyList()
        isLoading = false
        lastError = null
        broadcastState()
        stopSelf()
    }

    // ---- Stream Loading Internals ----

    private fun loadAtIndex(index: Int) {
        val item = queue.getOrNull(index) ?: return
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
        val dsFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(info.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)

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
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isAudioOnly)
            .setPreferredVideoMimeType("video/avc")
            .setMaxVideoSize(Int.MAX_VALUE, maxH)
            .build()
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
            selectedHeight = selectedHeight,
            playbackSpeed = playbackSpeed,
            error = lastError,
            lastSponsorSkip = lastSponsorSkip
        )
        notifyListeners()
    }

    companion object {
        private const val TAG = "YouTubePlaybackService"

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
