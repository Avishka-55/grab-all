package com.graball

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * GrabAll's own music player (v0.7.0). Plays audio files already in
 * Downloads/GrabAll — local files only, no YouTube streaming.
 *
 * Started AND bound: startService keeps it alive in the background
 * (music keeps playing while he chats or browses), binding lets
 * PlayerActivity read position/duration and drive seek/next/prev.
 * Notification actions (prev / play-pause / next) work even when the
 * activity is gone. Audio focus pauses on calls and resumes after.
 */
class PlayerService : Service(), MediaPlayer.OnCompletionListener,
    AudioManager.OnAudioFocusChangeListener {

    companion object {
        const val ACTION_PLAY = "com.graball.player.PLAY"
        const val ACTION_PAUSE = "com.graball.player.PAUSE"
        const val ACTION_RESUME = "com.graball.player.RESUME"
        const val ACTION_NEXT = "com.graball.player.NEXT"
        const val ACTION_PREV = "com.graball.player.PREV"
        const val ACTION_STOP = "com.graball.player.STOP"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_INDEX = "index"
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "graball_player"
    }

    private val binder = PlayerBinder()
    private var player: MediaPlayer? = null
    private var playlist: List<File> = emptyList()
    private var index = -1
    private var wasPlayingBeforeFocusLoss = false
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    inner class PlayerBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onUnbind(intent: Intent): Boolean = true

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Player", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: arrayListOf()
                val idx = intent.getIntExtra(EXTRA_INDEX, 0)
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
        super.onDestroy()
    }

    // ---- Activity-facing controls ----

    fun playList(files: List<File>, startIndex: Int) {
        if (files.isEmpty()) return
        playlist = files
        index = startIndex.coerceIn(0, files.size - 1)
        startPlayback()
    }

    fun currentFile(): File? = playlist.getOrNull(index)
    fun isPlaying(): Boolean = try { player?.isPlaying == true } catch (_: Exception) { false }
    fun position(): Int = try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }
    fun duration(): Int = try { player?.duration ?: 0 } catch (_: Exception) { 0 }

    fun seekTo(ms: Int) {
        try { player?.seekTo(ms) } catch (_: Exception) { }
    }

    fun pause() {
        try { player?.pause() } catch (_: Exception) { }
        // Paused, not dead: drop out of the foreground so the
        // notification becomes swipe-dismissable instead of stuck.
        stopForeground(false)
        updateNotification()
    }

    fun resume() {
        if (requestFocus()) {
            try { player?.start() } catch (_: Exception) { }
            // Only re-enter the foreground when there is actually a
            // player to resume — after a Stop there is none, and a
            // stray "Paused" notification must not come back.
            if (player != null) {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        updateNotification()
    }

    fun playNext() {
        if (index + 1 < playlist.size) {
            index++
            startPlayback()
        }
    }

    fun playPrev() {
        if (index > 0) {
            index--
            startPlayback()
        } else {
            seekTo(0)
        }
    }

    // ---- Internals ----

    private fun startPlayback() {
        val file = currentFile() ?: return
        releasePlayer()
        requestFocus()
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
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    private fun stopPlayback() {
        releasePlayer()
        // No current track any more, so the player screen shows its
        // idle "tap a song" line instead of the last song's name.
        index = -1
        stopForeground(true)
        stopSelf()
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Exception) { }
        player = null
        abandonFocus()
    }

    override fun onCompletion(mp: MediaPlayer) {
        if (index + 1 < playlist.size) {
            index++
            startPlayback()
        } else {
            stopPlayback()
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

    // ---- Notification ----

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

    private fun buildNotification(): Notification {
        val file = currentFile()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            pendingIntentFlags()
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(file?.name ?: "GrabAll Music")
            .setContentText(if (isPlaying()) "Playing" else "Paused")
            .setContentIntent(openApp)
            // Ongoing only while playing: a paused notification can
            // be swiped away, and the Stop action kills the service.
            .setOngoing(isPlaying())
            .addAction(android.R.drawable.ic_media_previous, "Prev", actionIntent(ACTION_PREV))
            .addAction(
                if (isPlaying()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying()) "Pause" else "Play",
                actionIntent(if (isPlaying()) ACTION_PAUSE else ACTION_RESUME)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", actionIntent(ACTION_NEXT))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop",
                actionIntent(ACTION_STOP)
            )
            .build()
    }

    private fun updateNotification() {
        if (currentFile() == null) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }
}
