package com.graball.engine

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Central layout for GrabAll's embedded native runtime.
 *
 * Everything lives under the app's private internal storage, because
 * Android 10+ mounts external storage and cache partitions noexec — binaries
 * must run from the private files dir. Downloads are staged here first and
 * then published to the public Downloads folder (MediaStore step, TODO).
 *
 * Manifest requirements (from the proven youtubedl-android setup):
 * - android:extractNativeLibs="true" — required for the bundled native libs.
 * - android:requestLegacyExternalStorage="true" — still applies on API 29
 *   (his Android 10 device); ignored on API 30+.
 * - ABI filter must include 'armeabi-v7a'.
 *
 * The native assets themselves (CPython bundle, yt-dlp script + ejs data,
 * qjs binary, ffmpeg binary) are NOT in the repo yet — they are built /
 * fetched during the runtime-packaging step and extracted here on first
 * launch by the RuntimeExtractor (TODO). Nothing in this object references
 * Android resources, so it compiles before those assets exist.
 */
object RuntimePaths {

    /** Root of the extracted runtime: filesDir/runtime/ */
    fun runtimeDir(context: Context): File = File(context.filesDir, "runtime")

    /** Extracted CPython executable. */
    fun pythonBinary(context: Context): File =
        File(runtimeDir(context), "python/bin/python")

    /** Directory holding libpython3.13.so (CPython is built --enable-shared). */
    fun pythonLibDir(context: Context): File =
        File(runtimeDir(context), "python/lib")

    /** Extracted yt-dlp script (plus its yt-dlp-ejs data files alongside). */
    fun ytDlpScript(context: Context): File =
        File(runtimeDir(context), "ytdlp/yt-dlp")

    /** qjs executable, cross-compiled for the device ABI with the NDK. */
    fun quickJsBinary(context: Context): File =
        File(runtimeDir(context), "qjs/bin/qjs")

    /** Directory holding the ffmpeg executable (for --ffmpeg-location). */
    fun ffmpegDir(context: Context): File =
        File(runtimeDir(context), "ffmpeg/bin")

    /** On-device YouTube session cookies, Netscape format. Never exported. */
    fun cookiesFile(context: Context): File =
        File(context.filesDir, "cookies/youtube-cookies.txt")

    /** Staging dir for finished downloads before the MediaStore publish. */
    fun stagingDir(context: Context): File =
        File(context.filesDir, "staging")

    /**
     * Stable staging dir for one URL (first 16 hex chars of its SHA-1).
     * Resume depends on it: a re-tap after a network failure must land
     * in the SAME dir as the failed run, so yt-dlp finds the .part file
     * and continues instead of starting over. (The old per-download
     * timestamp dir made every retry start from zero.)
     */
    fun stagingDirFor(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)
        return File(stagingDir(context), digest)
    }
}
