package com.graball

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.graball.youtube.YouTubePlaybackService
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.ceil

/**
 * Shared lightweight Sleep Timer for both YouTube playback (video & BG audio)
 * and the local Music Player.
 *
 * Uses a single main-looper delayed Runnable (zero background threads, zero idle
 * battery drain when off) and pauses whichever player is active on expiry.
 */
object SleepTimerManager {

    enum class Mode { OFF, TIMED, END_OF_TRACK }

    data class State(
        val mode: Mode = Mode.OFF,
        val remainingMs: Long = 0L
    ) {
        val isActive: Boolean get() = mode != Mode.OFF

        fun shortChipLabel(): String = when (mode) {
            Mode.OFF -> "🌙 Sleep"
            Mode.END_OF_TRACK -> "🌙 End"
            Mode.TIMED -> {
                val mins = ceil(remainingMs / 60_000.0).toInt().coerceAtLeast(1)
                "🌙 ${mins}m"
            }
        }

        fun compactIconLabel(): String = when (mode) {
            Mode.OFF -> "🌙 Sleep"
            Mode.END_OF_TRACK -> "🌙 End"
            Mode.TIMED -> {
                val mins = ceil(remainingMs / 60_000.0).toInt().coerceAtLeast(1)
                "🌙 ${mins}m"
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()

    @Volatile
    private var mode: Mode = Mode.OFF

    @Volatile
    private var endElapsedMs: Long = 0L

    @Volatile
    private var appContext: Context? = null

    private val expireRunnable = Runnable {
        onTimerExpired()
    }

    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            if (mode != Mode.TIMED || listeners.isEmpty()) return
            notifyListeners()
            mainHandler.postDelayed(this, 30_000L)
        }
    }

    fun currentState(): State {
        val m = mode
        if (m != Mode.TIMED) return State(mode = m, remainingMs = 0L)
        val rem = (endElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        return if (rem <= 0L) State(Mode.OFF, 0L) else State(Mode.TIMED, rem)
    }

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(currentState())
        if (mode == Mode.TIMED) {
            mainHandler.removeCallbacks(uiRefreshRunnable)
            mainHandler.postDelayed(uiRefreshRunnable, 30_000L)
        }
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            mainHandler.removeCallbacks(uiRefreshRunnable)
        }
    }

    fun startMinutes(context: Context, minutes: Int) {
        appContext = context.applicationContext
        val durMs = minutes.coerceAtLeast(1) * 60_000L
        mode = Mode.TIMED
        endElapsedMs = SystemClock.elapsedRealtime() + durMs
        mainHandler.removeCallbacks(expireRunnable)
        mainHandler.postDelayed(expireRunnable, durMs)
        if (listeners.isNotEmpty()) {
            mainHandler.removeCallbacks(uiRefreshRunnable)
            mainHandler.postDelayed(uiRefreshRunnable, 30_000L)
        }
        notifyListeners()
    }

    fun armEndOfTrack(context: Context) {
        appContext = context.applicationContext
        mainHandler.removeCallbacks(expireRunnable)
        mainHandler.removeCallbacks(uiRefreshRunnable)
        endElapsedMs = 0L
        mode = Mode.END_OF_TRACK
        notifyListeners()
    }

    fun cancel() {
        mainHandler.removeCallbacks(expireRunnable)
        mainHandler.removeCallbacks(uiRefreshRunnable)
        endElapsedMs = 0L
        mode = Mode.OFF
        notifyListeners()
    }

    /**
     * Called by playback services when the current video/song naturally ends.
     * If End-of-Track sleep timer is armed, disarms it and returns true so the
     * caller pauses cleanly instead of advancing to the next item.
     */
    fun consumeEndOfTrackIfArmed(context: Context): Boolean {
        if (mode != Mode.END_OF_TRACK) return false
        appContext = context.applicationContext
        cancel()
        mainHandler.post {
            Toast.makeText(
                context.applicationContext,
                "🌙 Sleep timer paused playback",
                Toast.LENGTH_SHORT
            ).show()
        }
        return true
    }

    private fun onTimerExpired() {
        val ctx = appContext
        mode = Mode.OFF
        endElapsedMs = 0L
        mainHandler.removeCallbacks(uiRefreshRunnable)

        var pausedAny = false
        YouTubePlaybackService.instance?.let { yt ->
            if (YouTubePlaybackService.currentState.isPlaying) {
                yt.pauseIfPlaying()
                pausedAny = true
            }
        }
        PlayerService.instance?.let { local ->
            if (local.isPlaying()) {
                local.pause()
                pausedAny = true
            }
        }
        notifyListeners()
        if (pausedAny && ctx != null) {
            Toast.makeText(ctx, "🌙 Sleep timer paused playback", Toast.LENGTH_SHORT).show()
        }
    }

    private fun notifyListeners() {
        val snapshot = currentState()
        for (l in listeners) {
            try {
                l(snapshot)
            } catch (_: Exception) {
            }
        }
    }

    fun showPickerDialog(context: Context, onDismiss: (() -> Unit)? = null) {
        val st = currentState()
        val title = when (st.mode) {
            Mode.OFF -> "🌙 Sleep Timer"
            Mode.END_OF_TRACK -> "🌙 Sleep Timer · End of track"
            Mode.TIMED -> {
                val mins = ceil(st.remainingMs / 60_000.0).toInt().coerceAtLeast(1)
                "🌙 Sleep Timer · ${mins}m left"
            }
        }
        val items = arrayOf(
            if (st.mode == Mode.OFF) "✓ Off" else "Off",
            "15 minutes",
            "30 minutes",
            "45 minutes",
            "60 minutes",
            if (st.mode == Mode.END_OF_TRACK) "✓ End of current video / song" else "End of current video / song"
        )
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        cancel()
                        Toast.makeText(context, "Sleep timer off", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        startMinutes(context, 15)
                        Toast.makeText(context, "🌙 Sleep timer set for 15 min", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        startMinutes(context, 30)
                        Toast.makeText(context, "🌙 Sleep timer set for 30 min", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        startMinutes(context, 45)
                        Toast.makeText(context, "🌙 Sleep timer set for 45 min", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        startMinutes(context, 60)
                        Toast.makeText(context, "🌙 Sleep timer set for 60 min", Toast.LENGTH_SHORT).show()
                    }
                    5 -> {
                        armEndOfTrack(context)
                        Toast.makeText(context, "🌙 Playback will pause when this finishes", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setOnDismissListener { onDismiss?.invoke() }
            .show()
    }
}
