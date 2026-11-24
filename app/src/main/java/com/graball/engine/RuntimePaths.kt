package com.graball.engine

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Central layout for GrabAll's embedded native runtime.
 *
 * For Android 10-16 (targetSdk 34+) W^X SELinux compliance, all native ELF
 * binaries and Python C extensions (.so) ship in jniLibs/<abi>/lib*.so and
 * are extracted by Android's PackageManager into nativeLibraryDir with the
 * executable apk_data_file context. Pure-Python stdlib, yt-dlp, and symlinks
 * to nativeLibraryDir live under filesDir/runtime/.
 */
object RuntimePaths {

    /** Root of the extracted runtime: filesDir/runtime/ */
    fun runtimeDir(context: Context): File = File(context.filesDir, "runtime")

    /** Directory where Android extracted jniLibs/<abi>/lib*.so. */
    fun nativeLibDir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    /** CPython executable (prefers jniLibs nativeLibraryDir for targetSdk 34+ W^X). */
    fun pythonBinary(context: Context): File {
        val jni = File(nativeLibDir(context), "libpython_exec.so")
        return if (jni.exists()) jni else File(runtimeDir(context), "python/bin/python")
    }

    /** Python home dir holding lib/python3.13/ stdlib. */
    fun pythonHomeDir(context: Context): File = File(runtimeDir(context), "python")

    /** Directory holding libpython3.13.so (prefers nativeLibraryDir). */
    fun pythonLibDir(context: Context): File {
        val jni = nativeLibDir(context)
        return if (File(jni, "libpython3.13.so").exists()) {
            jni
        } else {
            File(runtimeDir(context), "python/lib")
        }
    }

    /** Directory holding bundled yt-dlp and its pure-Python deps (certifi, mutagen, requests). */
    fun ytDlpBundleDir(context: Context): File = File(runtimeDir(context), "ytdlp")

    /** Bundled yt-dlp entrypoint script inside assets-extracted runtime. */
    fun bundledYtDlpScript(context: Context): File =
        File(ytDlpBundleDir(context), "yt-dlp")

    /** User-updated yt-dlp pure-Python zipapp (survives runtime re-extraction). */
    fun updatedYtDlpScript(context: Context): File =
        File(context.filesDir, "ytdlp-override/yt-dlp")

    /** Active yt-dlp script: uses self-updated zipapp when present, else bundled. */
    fun ytDlpScript(context: Context): File {
        val override = updatedYtDlpScript(context)
        return if (override.isFile && override.length() > 100_000L) {
            override
        } else {
            bundledYtDlpScript(context)
        }
    }

    /** Bundled CA certificate bundle (certifi) for Python/OpenSSL TLS. */
    fun certFile(context: Context): File =
        File(ytDlpBundleDir(context), "certifi/cacert.pem")

    /** QuickJS executable (prefers jniLibs nativeLibraryDir). */
    fun quickJsBinary(context: Context): File {
        val jni = File(nativeLibDir(context), "libqjs.so")
        return if (jni.exists()) jni else File(runtimeDir(context), "qjs/bin/qjs")
    }

    /** Directory holding ffmpeg (and ffprobe) symlinks/binaries for --ffmpeg-location. */
    fun ffmpegDir(context: Context): File =
        File(runtimeDir(context), "ffmpeg/bin")

    /** On-device YouTube session cookies, Netscape format. Never exported. */
    fun cookiesFile(context: Context): File =
        File(context.filesDir, "cookies/youtube-cookies.txt")

    /** Persistent yt-dlp cache (stores preprocessed YouTube JS player solver). */
    fun ytDlpCacheDir(context: Context): File =
        File(context.filesDir, "yt-dlp-cache")

    /** Staging dir for finished downloads before the MediaStore publish. */
    fun stagingDir(context: Context): File =
        File(context.filesDir, "staging")

    /** Stable staging dir for one URL (first 16 hex chars of its SHA-1) for resume. */
    fun stagingDirFor(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)
        return File(stagingDir(context), digest)
    }
}
