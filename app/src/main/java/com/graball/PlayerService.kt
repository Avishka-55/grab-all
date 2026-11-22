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
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "graball_player"

        @Volatile
        var instance: PlayerService? = null
            private set
    }

    private val binder = PlayerBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val artExecutor = Executors.newSingleThreadExecutor()

    private var player: MediaPlayer? = null
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
                if (paths.isNotEmpty()) {
                    playlist = paths.map { File(it) }
                    index = idx.coerceIn(0, playlist.size - 1)
                    startPlayback()
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
        releasePlayer()
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
    fun isPlaying(): Boolean = try { player?.isPlaying == true } catch (_: Exception) { false }
    fun position(): Int = try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }
    fun duration(): Int = try { player?.duration ?: 0 } catch (_: Exception) { 0 }
    fun audioSessionId(): Int = try { player?.audioSessionId ?: 0 } catch (_: Exception) { 0 }
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
        try { player?.seekTo(ms) } catch (_: Exception) { }
    }

    fun pause() {
        try { player?.pause() } catch (_: Exception) { }
        @Suppress("DEPRECATION")
        stopForeground(false)
        updateNotification()
    }

    fun resume() {
        YouTubePlaybackService.instance?.pauseIfPlaying()
        if (requestFocus()) {
            try { player?.start() } catch (_: Exception) { }
            if (player != null) {
                startForegroundCompat(buildNotification())
            }
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

    private fun startPlayback() {
        val file = currentFile() ?: return
        // Ensure YouTube background audio pauses so both never play together
        YouTubePlaybackService.instance?.pauseIfPlaying()
        releasePlayer()
        requestFocus()
        loadTrackArtAndMetaAsync(file)
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener(this@PlayerService)
                setOnErrorListener { _, _, _ ->
                    releasePlayer()
                    true
                }
                setOnPreparedListener { it.start(); updateNotification() }
                prepareAsync()
            }
            player = mp
            startForegroundCompat(buildNotification())
        } catch (_: Exception) {
            releasePlayer()
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
        releasePlayer()
        index = -1
        currentArtPath = null
        currentArt = null
        currentMeta = null
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Exception) { }
        player = null
        abandonFocus()
    }

    override fun onCompletion(mp: MediaPlayer) {
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

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    private fun abandonFocus() {
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
                wasPlayingBeforeFocusLoss = isPlaying()
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
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
        val playing = isPlaying()
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
