package com.graball.engine

import android.app.ActivityManager
import android.content.Context

/**
 * Last-used download options and user-configurable concurrency setting.
 *
 * The playlist choice is only saved for playlist downloads: a video
 * download must never overwrite it, or the next playlist panel
 * would open on "Single video" — the exact trap the panels exist
 * to prevent. Playlist panels therefore default to First 20.
 */
object DownloadPrefs {
    private const val FILE = "graball_prefs"
    private const val KEY_QUALITY = "quality"
    private const val KEY_AUDIO = "audio"
    private const val KEY_PLAYLIST = "playlist"
    private const val KEY_CONCURRENT = "concurrent_downloads"
    private const val KEY_AUDIO_FORMAT = "audio_format"

    const val AUDIO_FORMAT_MP3 = "mp3"
    const val AUDIO_FORMAT_M4A = "m4a"

    /** Supported explicit values (0 = Auto). */
    val CONCURRENT_OPTIONS = intArrayOf(0, 1, 2, 3, 4, 6)

    data class Choices(
        val qualityPos: Int,
        val audioOnly: Boolean,
        val playlistPos: Int
    )

    fun load(context: Context): Choices {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Choices(
            prefs.getInt(KEY_QUALITY, 0),
            prefs.getBoolean(KEY_AUDIO, false),
            prefs.getInt(KEY_PLAYLIST, 1)
        )
    }

    fun save(context: Context, choices: Choices) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_QUALITY, choices.qualityPos)
            .putBoolean(KEY_AUDIO, choices.audioOnly)
            .putInt(KEY_PLAYLIST, choices.playlistPos)
            .apply()
    }

    /** Raw setting: 0 = Auto, or explicit 1..6. */
    fun getConcurrentDownloads(context: Context): Int {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_CONCURRENT, 0)
    }

    fun setConcurrentDownloads(context: Context, count: Int) {
        val clamped = if (count <= 0) 0 else count.coerceIn(1, 6)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CONCURRENT, clamped)
            .apply()
    }

    /**
     * Effective concurrency used by DownloadService & FastYouTubeDownloader:
     * - Auto (0): 2 on low-RAM / dual-core phones, 3–4 on modern multi-core phones.
     * - Explicit: user's chosen 1..6 parallel tracks / concurrent downloads.
     */
    fun resolvedConcurrency(context: Context): Int {
        val raw = getConcurrentDownloads(context)
        if (raw in 1..6) return raw
        val isLowRam = try {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.isLowRamDevice == true
        } catch (_: Exception) {
            false
        }
        if (isLowRam) return 2
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 8 -> 4
            cores >= 4 -> 3
            else -> 2
        }
    }

    /** Audio-only download format: "mp3" (default) or "m4a" (fast zero re-encode). */
    fun getAudioFormat(context: Context): String {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_AUDIO_FORMAT, AUDIO_FORMAT_MP3)
        return if (raw == AUDIO_FORMAT_M4A) AUDIO_FORMAT_M4A else AUDIO_FORMAT_MP3
    }

    fun setAudioFormat(context: Context, format: String) {
        val clean = if (format == AUDIO_FORMAT_M4A) AUDIO_FORMAT_M4A else AUDIO_FORMAT_MP3
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_AUDIO_FORMAT, clean)
            .apply()
    }

    fun isFastM4a(context: Context): Boolean = getAudioFormat(context) == AUDIO_FORMAT_M4A
}
