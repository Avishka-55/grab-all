package com.graball.engine

import android.content.Context

/**
 * Last-used download options, so Grab panels open with the user's
 * usual choices instead of resetting to Best/Single every time.
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
}
