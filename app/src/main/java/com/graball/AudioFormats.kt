package com.graball

import java.io.File

/**
 * Single source of truth for GrabAll's expanded audio format support across
 * Music Library scanning, Media3 ExoPlayer + MediaPlayer playback, Downloads
 * smart-opening, and MediaStore MIME publishing.
 */
object AudioFormats {

    /**
     * Full set of audio formats indexed in GrabAll Music Player:
     * - Lossy mainstream: MP3, M4A, M4B, M4P, AAC, ADTS, OGG, OGA, OPUS, WEBA, WMA, MP2, MPGA
     * - Lossless / Hi-Res: FLAC, WAV, AIFF, AIF, ALAC, MKA
     * - Mobile / Voice / Surround / Synth: AMR, AWB, 3GA, AC3, EAC3, MID, MIDI
     */
    val MUSIC_LIBRARY_EXTENSIONS: Set<String> = setOf(
        "mp3", "m4a", "m4b", "m4p", "aac", "adts",
        "flac", "wav", "aiff", "aif", "alac",
        "ogg", "oga", "opus", "weba", "mka",
        "wma", "mp2", "mpga",
        "amr", "awb", "3ga",
        "ac3", "eac3",
        "mid", "midi"
    )

    /**
     * Additional container extensions accepted when the user explicitly taps
     * "Open with GrabAll Music" from a file manager or another app.
     */
    val OPEN_INTENT_EXTENSIONS: Set<String> = MUSIC_LIBRARY_EXTENSIONS + setOf(
        "3gp", "webm"
    )

    /** Formats handled by Android's built-in MIDI synthesizer via MediaPlayer. */
    private val MIDI_EXTENSIONS: Set<String> = setOf("mid", "midi", "xmf", "mxmf")

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun isMusicFile(name: String): Boolean =
        extensionOf(name) in MUSIC_LIBRARY_EXTENSIONS

    fun isSupportedOpenAudioFile(name: String): Boolean =
        extensionOf(name) in OPEN_INTENT_EXTENSIONS

    fun isMidiFile(name: String): Boolean =
        extensionOf(name) in MIDI_EXTENSIONS

    /** Short uppercase format tag for library rows, e.g. "FLAC", "M4A", "OPUS", "MP3". */
    fun formatBadge(file: File): String =
        extensionOf(file.name).uppercase().take(5)

    fun mimeForExtension(ext: String): String? = when (ext.lowercase()) {
        "mp3", "mpga", "mp2" -> "audio/mpeg"
        "m4a", "m4b", "m4p", "alac" -> "audio/mp4"
        "aac", "adts" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "aiff", "aif" -> "audio/aiff"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "weba" -> "audio/webm"
        "mka" -> "audio/x-matroska"
        "wma" -> "audio/x-ms-wma"
        "amr" -> "audio/amr"
        "awb" -> "audio/amr-wb"
        "3ga", "3gp" -> "audio/3gpp"
        "ac3" -> "audio/ac3"
        "eac3" -> "audio/eac3"
        "mid", "midi" -> "audio/midi"
        else -> null
    }
}
