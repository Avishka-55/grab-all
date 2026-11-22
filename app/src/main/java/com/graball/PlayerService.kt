package com.graball

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
        const val ACTION_PAUSE = "com.graball.player.PAUSE"
        const val ACTION_RESUME = "com.graball.player.RESUME"
        const val ACTION_NEXT = "com.graball.player.NEXT"
        const val ACTION_PREV = "com.graball.player.PREV"
        const val ACTION_STOP = "com.graball.player.STOP"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_INDEX = "index"
        const val EXTRA_FOLDER_LABEL = "folder_label"
        const val EXTRA_SEEK_MS = "seek_ms"
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: arrayListOf()
                val idx = intent.getIntExtra(EXTRA_INDEX, 0)
                folderLabel = intent.getStringExtra(EXTRA_FOLDER_LABEL).orEmpty()
                val seekMs = intent.getIntExtra(EXTRA_SEEK_MS, 0).coerceAtLeast(0)
                if (paths.isNotEmpty()) {
                    playlist = paths.map { File(it) }
                    index = idx.coerceIn(0, playlist.size - 1)
                    startPlayback(initialSeekMs = seekMs)
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
        artExecutor.shutdownNow()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- Activity-facing controls ----

    fun currentFile(): File? = playlist.getOrNull(index)
    fun currentPlaylist(): List<File> = playlist
    fun currentIndex(): Int = index
    fun currentFolderLabel(): String = folderLabel
    fun currentTrackMeta(): AlbumArtLoader.TrackMeta? = currentMeta

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
        YouTubePlaybackService.instance?.pauseIfPlaying()
        if (player == null) {
            if (currentFile() != null) {
                startPlayback()
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
        // Ensure YouTube background audio pauses so both never play together
        YouTubePlaybackService.instance?.pauseIfPlaying()
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

    private fun stopPlayback() {
        persistLastPlayed()
        playbackGen++
        releasePlayer(abandonAudioFocus = true)
        index = -1
        currentArtPath = null
        currentArt = null
        currentMeta = null
        @Suppress("DEPRECATION")
        stopForeground(true)
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
            )
        currentArt?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private fun updateNotification() {
        if (currentFile() == null) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }
}
