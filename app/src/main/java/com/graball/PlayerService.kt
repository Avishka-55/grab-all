package com.graball

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.graball.youtube.YouTubePlaybackService
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * GrabAll's local music player service.
 * Plays audio strictly from the active folder/playlist passed by PlayerActivity,
 * displays native Android MediaStyle lockscreen/shade notifications with real
 * embedded album cover art & ID3 metadata, and coordinates cleanly with
 * YouTubePlaybackService so local music and YouTube audio never overlap.
 */
class PlayerService : Service(), MediaPlayer.OnCompletionListener,
    AudioManager.OnAudioFocusChangeListener {

    enum class RepeatMode { OFF, ALL, ONE }

    data class LastPlayed(
        val file: File,
        val folderLabel: String,
        val positionMs: Int,
        val durationMs: Int
    )

    companion object {
        const val ACTION_PLAY = "com.graball.player.PLAY"
        const val ACTION_PLAY_URI = "com.graball.player.PLAY_URI"
        const val ACTION_PAUSE = "com.graball.player.PAUSE"
        const val ACTION_RESUME = "com.graball.player.RESUME"
        const val ACTION_NEXT = "com.graball.player.NEXT"
        const val ACTION_PREV = "com.graball.player.PREV"
        const val ACTION_STOP = "com.graball.player.STOP"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_INDEX = "index"
        const val EXTRA_FOLDER_LABEL = "folder_label"
        const val EXTRA_SEEK_MS = "seek_ms"
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "graball_player"
        private const val LAST_PREFS = "graball_player_last"
        private const val KEY_LAST_PATH = "last_path"
        private const val KEY_LAST_FOLDER = "last_folder"
        private const val KEY_LAST_POS_MS = "last_pos_ms"
        private const val KEY_LAST_DUR_MS = "last_dur_ms"

        fun getLastPlayed(context: Context): LastPlayed? {
            val prefs = context.getSharedPreferences(LAST_PREFS, Context.MODE_PRIVATE)
            val path = prefs.getString(KEY_LAST_PATH, null) ?: return null
            val f = File(path)
            if (!f.isFile) return null
            return LastPlayed(
                file = f,
                folderLabel = prefs.getString(KEY_LAST_FOLDER, "") ?: "",
                positionMs = prefs.getInt(KEY_LAST_POS_MS, 0).coerceAtLeast(0),
                durationMs = prefs.getInt(KEY_LAST_DUR_MS, 0).coerceAtLeast(0)
            )
        }

        @Volatile
        var instance: PlayerService? = null
            private set

        /**
         * Stop local playback, persist the last-played track & timestamp,
         * cancel notification ID 2 (even if detached/paused), and stop the service.
         */
        fun stopAndClear(context: Context) {
            val svc = instance
            if (svc != null) {
                svc.stopPlayback()
            } else {
                try {
                    (context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                        ?.cancel(NOTIFICATION_ID)
                } catch (_: Exception) {
                }
            }
        }
    }

    private val binder = PlayerBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val artExecutor = Executors.newSingleThreadExecutor()

    private var player: MediaPlayer? = null
    private var isPrepared = false
    private var pendingSeekMs = 0
    private var playbackGen = 0
    private var hasAudioFocus = false
    private var playlist: List<File> = emptyList()
    private var index = -1
    private var folderLabel: String = ""
    private var shuffleEnabled = false
    private var repeatMode = RepeatMode.ALL
    private var wasPlayingBeforeFocusLoss = false
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private var currentArtPath: String? = null
    private var currentArt: Bitmap? = null
    private var currentMeta: AlbumArtLoader.TrackMeta? = null
    private var externalUri: Uri? = null
    private var externalTitle: String = ""
    private var mediaSession: MediaSession? = null
    private var noisyReceiverRegistered = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pauseIfPlaying()
            }
        }
    }

    inner class PlayerBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onUnbind(intent: Intent): Boolean = true

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Music Player", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        initMediaSession()
        try {
            ContextCompat.registerReceiver(
                this,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyReceiverRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun initMediaSession() {
        try {
            val openApp = PendingIntent.getActivity(
                this, 0,
                Intent(this, PlayerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("open_now_playing", true)
                },
                pendingIntentFlags()
            )
            mediaSession = MediaSession(this, "GrabAllMusic").apply {
                @Suppress("DEPRECATION")
                setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                setSessionActivity(openApp)
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        resume()
                    }

                    override fun onPause() {
                        pause()
                    }

                    override fun onSkipToNext() {
                        playNext()
                    }

                    override fun onSkipToPrevious() {
                        playPrev()
                    }

                    override fun onStop() {
                        stopPlayback()
                    }

                    override fun onSeekTo(pos: Long) {
                        seekTo(pos.toInt())
                    }

                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val ke = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            mediaButtonIntent.getParcelableExtra(
                                Intent.EXTRA_KEY_EVENT,
                                KeyEvent::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                        }
                        if (ke != null && ke.action == KeyEvent.ACTION_DOWN) {
                            when (ke.keyCode) {
                                KeyEvent.KEYCODE_HEADSETHOOK,
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                    if (isPlaying()) pause() else resume()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                    resume()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                    pause()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                    playNext()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                    playPrev()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_STOP -> {
                                    stopPlayback()
                                    return true
                                }
                            }
                        }
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                }, mainHandler)
            }
        } catch (_: Exception) {
        }
    }

    private fun syncMediaSession() {
        val session = mediaSession ?: return
        if (!hasLoadedTrack()) {
            try {
                session.setPlaybackState(
                    PlaybackState.Builder()
                        .setState(PlaybackState.STATE_STOPPED, 0L, 0f)
                        .build()
                )
                session.isActive = false
            } catch (_: Exception) {
            }
            return
        }
        val file = currentFile()
        val meta = currentMeta
        val displayTitle = meta?.title?.takeIf { it.isNotBlank() }
            ?: file?.nameWithoutExtension
            ?: externalTitle.takeIf { it.isNotBlank() }
            ?: "GrabAll Music"
        val displayArtist = meta?.artist?.takeIf { it.isNotBlank() }
            ?: folderLabel.takeIf { it.isNotBlank() }
            ?: "GrabAll Music"
        val displayAlbum = meta?.album?.takeIf { it.isNotBlank() } ?: folderLabel
        val durMs = duration().toLong().coerceAtLeast(0L)
        val posMs = position().toLong().coerceAtLeast(0L)
        val playing = isPlaying()
        val preparing = !isPrepared && player != null

        try {
            val mdBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, displayTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, displayArtist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, displayAlbum)
            if (durMs > 0L) {
                mdBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, durMs)
            }
            currentArt?.let { mdBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            session.setMetadata(mdBuilder.build())

            val pbState = when {
                playing -> PlaybackState.STATE_PLAYING
                preparing -> PlaybackState.STATE_BUFFERING
                else -> PlaybackState.STATE_PAUSED
            }
            val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SEEK_TO
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(pbState, posMs, if (playing) 1.0f else 0f)
                    .build()
            )
            if (!session.isActive) {
                session.isActive = true
            }
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: arrayListOf()
                val idx = intent.getIntExtra(EXTRA_INDEX, 0)
                folderLabel = intent.getStringExtra(EXTRA_FOLDER_LABEL).orEmpty()
                val seekMs = intent.getIntExtra(EXTRA_SEEK_MS, 0).coerceAtLeast(0)
                if (paths.isNotEmpty()) {
                    externalUri = null
                    externalTitle = ""
                    playlist = paths.map { File(it) }
                    index = idx.coerceIn(0, playlist.size - 1)
                    startPlayback(initialSeekMs = seekMs)
                }
            }
            ACTION_PLAY_URI -> {
                val uriStr = intent.getStringExtra(EXTRA_URI)
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                if (!uriStr.isNullOrBlank()) {
                    val uri = Uri.parse(uriStr)
                    playlist = emptyList()
                    index = -1
                    folderLabel = "External Audio"
                    externalUri = uri
                    externalTitle = title
                    startUriPlayback(uri)
                }
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrev()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        persistLastPlayed()
        releasePlayer(abandonAudioFocus = true)
        if (noisyReceiverRegistered) {
            noisyReceiverRegistered = false
            try { unregisterReceiver(noisyReceiver) } catch (_: Exception) { }
        }
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Exception) {
        }
        mediaSession = null
        artExecutor.shutdownNow()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- Activity-facing controls ----

    fun currentFile(): File? = playlist.getOrNull(index)
    fun currentExternalUri(): Uri? = externalUri
    fun currentExternalTitle(): String = externalTitle
    fun hasLoadedTrack(): Boolean = currentFile() != null || externalUri != null
    fun currentPlaylist(): List<File> = playlist
    fun currentIndex(): Int = index
    fun currentFolderLabel(): String = folderLabel
    fun currentTrackMeta(): AlbumArtLoader.TrackMeta? = currentMeta
    fun currentArtBitmap(): Bitmap? = currentArt

    fun isPlaying(): Boolean =
        if (!isPrepared) false else try { player?.isPlaying == true } catch (_: Exception) { false }

    fun isActiveOrPreparing(): Boolean = isPlaying() || (!isPrepared && player != null)

    // Guarding with isPrepared prevents calling getCurrentPosition()/getDuration()
    // during MediaPlayer.Preparing state, which otherwise triggers native error (-38, 0)!
    fun position(): Int =
        if (!isPrepared) 0 else try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }

    fun duration(): Int =
        if (!isPrepared) 0 else try { player?.duration ?: 0 } catch (_: Exception) { 0 }

    fun audioSessionId(): Int =
        try { player?.audioSessionId ?: 0 } catch (_: Exception) { 0 }

    fun isShuffleEnabled(): Boolean = shuffleEnabled
    fun getRepeatMode(): RepeatMode = repeatMode

    fun pauseIfPlaying() {
        if (isPlaying()) {
            pause()
        }
    }

    fun playAtIndex(targetIndex: Int) {
        if (targetIndex in playlist.indices) {
            index = targetIndex
            startPlayback()
        }
    }

    fun toggleShuffle(): Boolean {
        shuffleEnabled = !shuffleEnabled
        return shuffleEnabled
    }

    fun cycleRepeatMode(): RepeatMode {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        return repeatMode
    }

    fun seekTo(ms: Int) {
        if (!isPrepared) return
        try { player?.seekTo(ms.coerceAtLeast(0)) } catch (_: Exception) { }
        syncMediaSession()
    }

    fun pause() {
        persistLastPlayed()
        if (isPrepared) {
            try { player?.pause() } catch (_: Exception) { }
        }
        @Suppress("DEPRECATION")
        stopForeground(false)
        updateNotification()
    }

    fun resume() {
        YouTubePlaybackService.stopAndClear(applicationContext)
        if (player == null) {
            val ext = externalUri
            if (currentFile() != null) {
                startPlayback()
            } else if (ext != null) {
                startUriPlayback(ext)
            }
            return
        }
        if (!isPrepared) return
        if (ensureAudioFocus()) {
            try { player?.start() } catch (_: Exception) { }
            startForegroundCompat(buildNotification())
        }
        updateNotification()
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        if (shuffleEnabled && playlist.size > 1) {
            var next = Random.nextInt(playlist.size)
            if (next == index) next = (index + 1) % playlist.size
            index = next
            startPlayback()
            return
        }
        if (index + 1 < playlist.size) {
            index++
            startPlayback()
        } else if (repeatMode == RepeatMode.ALL) {
            index = 0
            startPlayback()
        }
    }

    fun playPrev() {
        if (playlist.isEmpty()) return
        if (position() > 4000) {
            seekTo(0)
            return
        }
        if (index > 0) {
            index--
            startPlayback()
        } else if (repeatMode == RepeatMode.ALL) {
            index = playlist.lastIndex
            startPlayback()
        } else {
            seekTo(0)
        }
    }

    // ---- Internals ----

    private fun persistLastPlayed(overridePosMs: Int? = null) {
        val file = currentFile() ?: return
        if (overridePosMs == null && !isPrepared) return
        val pos = overridePosMs ?: position()
        val dur = duration()
        getSharedPreferences(LAST_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_PATH, file.absolutePath)
            .putString(KEY_LAST_FOLDER, folderLabel)
            .putInt(KEY_LAST_POS_MS, pos)
            .putInt(KEY_LAST_DUR_MS, dur)
            .apply()
    }

    private fun startPlayback(initialSeekMs: Int = 0) {
        val file = currentFile() ?: return
        val gen = ++playbackGen
        pendingSeekMs = initialSeekMs
        persistLastPlayed(overridePosMs = initialSeekMs)
        // Enforce single active player: stop YouTube & clear its notification cleanly
        YouTubePlaybackService.stopAndClear(applicationContext)
        // Keep AudioFocus held across track transitions so abandoning + immediately
        // re-requesting focus never triggers a stale AUDIOFOCUS_LOSS pause race!
        releasePlayer(abandonAudioFocus = false)
        ensureAudioFocus()
        loadTrackArtAndMetaAsync(file)
        try {
            val mp = MediaPlayer().apply {
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener(this@PlayerService)
                setOnErrorListener { failedMp, what, _ ->
                    // Ignore stale players or benign -38 state-query errors
                    if (gen != playbackGen || failedMp !== player || what == -38) {
                        true
                    } else {
                        isPrepared = false
                        releasePlayer(abandonAudioFocus = false)
                        true
                    }
                }
                setOnPreparedListener { readyMp ->
                    if (gen != playbackGen || readyMp !== player) {
                        try { readyMp.release() } catch (_: Exception) { }
                        return@setOnPreparedListener
                    }
                    isPrepared = true
                    try {
                        val seek = pendingSeekMs
                        pendingSeekMs = 0
                        val dur = readyMp.duration
                        if (seek > 1000 && (dur <= 0 || seek < dur - 2000)) {
                            readyMp.seekTo(seek)
                        }
                        readyMp.start()
                        persistLastPlayed()
                        startForegroundCompat(buildNotification())
                    } catch (_: Exception) {
                    }
                    updateNotification()
                }
                prepareAsync()
            }
            player = mp
            startForegroundCompat(buildNotification())
        } catch (_: Exception) {
            releasePlayer(abandonAudioFocus = false)
        }
    }

    private fun startUriPlayback(uri: Uri) {
        val gen = ++playbackGen
        pendingSeekMs = 0
        YouTubePlaybackService.stopAndClear(applicationContext)
        releasePlayer(abandonAudioFocus = false)
        ensureAudioFocus()
        loadUriArtAndMetaAsync(uri)
        try {
            val mp = MediaPlayer().apply {
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(applicationContext, uri)
                setOnCompletionListener(this@PlayerService)
                setOnErrorListener { failedMp, what, _ ->
                    if (gen != playbackGen || failedMp !== player || what == -38) {
                        true
                    } else {
                        isPrepared = false
                        releasePlayer(abandonAudioFocus = false)
                        true
                    }
                }
                setOnPreparedListener { readyMp ->
                    if (gen != playbackGen || readyMp !== player) {
                        try { readyMp.release() } catch (_: Exception) { }
                        return@setOnPreparedListener
                    }
                    isPrepared = true
                    try {
                        readyMp.start()
                        startForegroundCompat(buildNotification())
                    } catch (_: Exception) {
                    }
                    updateNotification()
                }
                prepareAsync()
            }
            player = mp
            startForegroundCompat(buildNotification())
        } catch (_: Exception) {
            releasePlayer(abandonAudioFocus = false)
        }
    }

    private fun loadUriArtAndMetaAsync(uri: Uri) {
        val key = uri.toString()
        if (key == currentArtPath) return
        currentArtPath = key
        currentArt = null
        currentMeta = null
        artExecutor.execute {
            var meta: AlbumArtLoader.TrackMeta? = null
            var art: Bitmap? = null
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(applicationContext, uri)
                val t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
                val a = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
                val alb = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim().orEmpty()
                if (t.isNotBlank() || a.isNotBlank() || alb.isNotBlank()) {
                    meta = AlbumArtLoader.TrackMeta(t, a, alb)
                }
                val pic = mmr.embeddedPicture
                if (pic != null && pic.isNotEmpty()) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(pic, 0, pic.size, opts)
                    var sample = 1
                    while (opts.outWidth / sample > 512 || opts.outHeight / sample > 512) {
                        sample *= 2
                    }
                    val dec = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    art = BitmapFactory.decodeByteArray(pic, 0, pic.size, dec)
                }
            } catch (_: Throwable) {
            } finally {
                try { mmr.release() } catch (_: Throwable) { }
            }
            mainHandler.post {
                if (currentArtPath == key) {
                    currentMeta = meta
                    currentArt = art
                    updateNotification()
                }
            }
        }
    }

    private fun loadTrackArtAndMetaAsync(file: File) {
        val path = file.absolutePath
        if (path == currentArtPath) return
        currentArtPath = path
        currentArt = null
        currentMeta = AlbumArtLoader.getCachedMeta(file)
        artExecutor.execute {
            val meta = AlbumArtLoader.loadMetaSync(file)
            val art = AlbumArtLoader.loadArtSync(file, targetPx = 256)
            mainHandler.post {
                if (currentArtPath == path) {
                    currentMeta = meta
                    currentArt = art
                    updateNotification()
                }
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun stopPlayback() {
        persistLastPlayed()
        playbackGen++
        releasePlayer(abandonAudioFocus = true)
        index = -1
        playlist = emptyList()
        externalUri = null
        externalTitle = ""
        currentArtPath = null
        currentArt = null
        currentMeta = null
        syncMediaSession()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun releasePlayer(abandonAudioFocus: Boolean) {
        isPrepared = false
        val old = player
        player = null
        if (old != null) {
            try { old.setOnPreparedListener(null) } catch (_: Exception) { }
            try { old.setOnCompletionListener(null) } catch (_: Exception) { }
            try { old.setOnErrorListener(null) } catch (_: Exception) { }
            try { old.stop() } catch (_: Exception) { }
            try { old.reset() } catch (_: Exception) { }
            try { old.release() } catch (_: Exception) { }
        }
        if (abandonAudioFocus) {
            abandonFocus()
        }
    }

    override fun onCompletion(mp: MediaPlayer) {
        if (mp !== player) return
        val ext = externalUri
        if (ext != null && playlist.isEmpty()) {
            if (repeatMode == RepeatMode.ONE) {
                startUriPlayback(ext)
            } else {
                seekTo(0)
                pause()
            }
            return
        }
        when (repeatMode) {
            RepeatMode.ONE -> startPlayback()
            RepeatMode.ALL -> playNext()
            RepeatMode.OFF -> {
                if (shuffleEnabled && playlist.size > 1) {
                    playNext()
                } else if (index + 1 < playlist.size) {
                    index++
                    startPlayback()
                } else {
                    stopPlayback()
                }
            }
        }
    }

    private fun ensureAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val am = audioManager ?: return false
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(this)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                ).build()
            focusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        hasAudioFocus = granted
        return granted
    }

    private fun abandonFocus() {
        hasAudioFocus = false
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(this)
        }
        focusRequest = null
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                wasPlayingBeforeFocusLoss = isPlaying()
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false
                    resume()
                }
            }
        }
    }

    // ---- Native MediaStyle Notification with Embedded Cover Art ----

    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, PlayerService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent, pendingIntentFlags()
        )
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val file = currentFile()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("open_now_playing", true)
            },
            pendingIntentFlags()
        )
        val meta = currentMeta
        val displayTitle = meta?.title?.takeIf { it.isNotBlank() }
            ?: file?.nameWithoutExtension
            ?: externalTitle.takeIf { it.isNotBlank() }
            ?: "GrabAll Music"
        val playing = isPlaying() || (!isPrepared && player != null)
        val sub = buildString {
            if (!meta?.artist.isNullOrBlank()) {
                append(meta!!.artist)
                if (folderLabel.isNotBlank()) append(" · ").append(folderLabel)
            } else {
                append(if (playing) "Playing" else "Paused")
                if (folderLabel.isNotBlank()) append(" · ").append(folderLabel)
            }
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(displayTitle)
            .setContentText(sub)
            .setContentIntent(openApp)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setColor(ContextCompat.getColor(this, R.color.graball_orange))
            .addAction(android.R.drawable.ic_media_previous, "Prev", actionIntent(ACTION_PREV))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                actionIntent(if (playing) ACTION_PAUSE else ACTION_RESUME)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", actionIntent(ACTION_NEXT))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop",
                actionIntent(ACTION_STOP)
            )
            .setStyle(
                Notification.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .also { style ->
                        mediaSession?.sessionToken?.let { style.setMediaSession(it) }
                    }
            )
        currentArt?.let { builder.setLargeIcon(it) }
        syncMediaSession()
        return builder.build()
    }

    private fun updateNotification() {
        if (!hasLoadedTrack()) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }
}
