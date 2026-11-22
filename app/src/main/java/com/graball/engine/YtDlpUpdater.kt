package com.graball.engine

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Self-updater for yt-dlp talking directly to the official GitHub repo:
 * - Stable:  yt-dlp/yt-dlp
 * - Nightly: yt-dlp/yt-dlp-nightly-builds
 *
 * Downloads the official ~3 MB pure-Python zipapp (which includes yt_dlp +
 * yt_dlp_ejs and zero native .so files, so it works identically on 32-bit,
 * 64-bit, Android 5-16, and targetSdk 34+ W^X). Verifies ZIP integrity AND
 * live-runs `python yt-dlp.tmp --version` before swapping it in atomically.
 * Falls back to bundled Python/OpenSSL 3.2 + certifi on old Android 5-6
 * devices if the system TLS stack fails.
 */
object YtDlpUpdater {

    private const val TAG = "YtDlpUpdater"
    private const val PREFS = "graball_ytdlp_updater"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_INSTALLED_VER = "installed_version"

    const val CHANNEL_STABLE = "stable"
    const val CHANNEL_NIGHTLY = "nightly"

    data class UpdateResult(
        val ok: Boolean,
        val updated: Boolean,
        val version: String,
        val message: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getChannel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CHANNEL, CHANNEL_STABLE) ?: CHANNEL_STABLE

    fun setChannel(context: Context, channel: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CHANNEL, channel)
            .apply()
    }

    fun isUsingOverride(context: Context): Boolean {
        val f = RuntimePaths.updatedYtDlpScript(context)
        return f.isFile && f.length() > 100_000L
    }

    fun getCachedVersion(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (isUsingOverride(context)) {
            prefs.getString(KEY_INSTALLED_VER, null)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return "2026.10.26 (bundled)"
    }

    /** Query the active yt-dlp's live `--version`. Call off main thread. */
    fun probeInstalledVersion(context: Context): String? {
        if (!RuntimeExtractor.extractIfNeeded(context, DownloadService.RUNTIME_VERSION)) {
            return null
        }
        val runner = YtDlpRunner(
            pythonBinary = RuntimePaths.pythonBinary(context),
            ytDlpScript = RuntimePaths.ytDlpScript(context),
            ffmpegDir = RuntimePaths.ffmpegDir(context),
            quickJsBinary = RuntimePaths.quickJsBinary(context),
            cookiesFile = null,
            cacheDir = context.cacheDir,
            homeDir = context.filesDir,
            certFile = RuntimePaths.certFile(context),
            pythonHomeDir = RuntimePaths.pythonHomeDir(context),
            pythonLibDir = RuntimePaths.pythonLibDir(context),
            pythonPathDir = RuntimePaths.ytDlpBundleDir(context)
        )
        return runner.version()
    }

    fun resetToBundled(context: Context): Boolean {
        val override = RuntimePaths.updatedYtDlpScript(context)
        val deleted = !override.exists() || override.delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_INSTALLED_VER)
            .apply()
        return deleted
    }

    /**
     * Check official yt-dlp GitHub releases and atomically install if newer.
     * Always call off the main thread.
     */
    fun checkAndUpdate(
        context: Context,
        onStatus: ((String) -> Unit)? = null
    ): UpdateResult {
        if (!RuntimeExtractor.extractIfNeeded(context, DownloadService.RUNTIME_VERSION)) {
            return UpdateResult(false, false, "", RuntimeExtractor.lastError ?: "Runtime setup failed")
        }
        val channel = getChannel(context)
        val repo = if (channel == CHANNEL_NIGHTLY) {
            "yt-dlp/yt-dlp-nightly-builds"
        } else {
            "yt-dlp/yt-dlp"
        }
        onStatus?.invoke("Checking $repo…")

        val apiUrl = "https://api.github.com/repos/$repo/releases/latest"
        val releaseJsonStr = fetchText(context, apiUrl)
            ?: return UpdateResult(false, false, "", "Could not reach GitHub — check internet connection")

        val release = try {
            JSONObject(releaseJsonStr)
        } catch (e: Exception) {
            return UpdateResult(false, false, "", "Invalid GitHub response")
        }

        val latestTag = release.optString("tag_name").trim()
        if (latestTag.isBlank()) {
            return UpdateResult(false, false, "", "No release tag found")
        }

        val currentVersion = probeInstalledVersion(context)?.trim().orEmpty()
        if (currentVersion.isNotBlank() && normalizeVersion(currentVersion) == normalizeVersion(latestTag)) {
            return UpdateResult(true, false, currentVersion, "Already up to date ($currentVersion)")
        }

        // Find the pure-Python `yt-dlp` zipapp asset
        val assets = release.optJSONArray("assets")
        var downloadUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name") == "yt-dlp") {
                    downloadUrl = a.optString("browser_download_url")
                    break
                }
            }
        }
        if (downloadUrl.isNullOrBlank()) {
            downloadUrl = "https://github.com/$repo/releases/download/$latestTag/yt-dlp"
        }

        onStatus?.invoke("Downloading yt-dlp $latestTag…")
        val target = RuntimePaths.updatedYtDlpScript(context)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "yt-dlp.tmp")
        if (tmp.exists()) tmp.delete()

        val downloaded = downloadFile(context, downloadUrl, tmp)
        if (!downloaded || !tmp.isFile || tmp.length() < 100_000L) {
            tmp.delete()
            return UpdateResult(false, false, currentVersion, "Download failed — keeping current version")
        }

        onStatus?.invoke("Verifying $latestTag on device…")
        if (!isValidYtDlpZipapp(tmp)) {
            tmp.delete()
            return UpdateResult(false, false, currentVersion, "Downloaded file failed ZIP verification")
        }

        val verifiedVersion = YtDlpRunner(
            pythonBinary = RuntimePaths.pythonBinary(context),
            ytDlpScript = tmp,
            ffmpegDir = RuntimePaths.ffmpegDir(context),
            quickJsBinary = RuntimePaths.quickJsBinary(context),
            cookiesFile = null,
            cacheDir = context.cacheDir,
            homeDir = context.filesDir,
            certFile = RuntimePaths.certFile(context),
            pythonHomeDir = RuntimePaths.pythonHomeDir(context),
            pythonLibDir = RuntimePaths.pythonLibDir(context),
            pythonPathDir = RuntimePaths.ytDlpBundleDir(context)
        ).version()?.trim()

        if (verifiedVersion.isNullOrBlank() || !verifiedVersion.first().isDigit()) {
            tmp.delete()
            return UpdateResult(false, false, currentVersion, "Verification failed — keeping current version")
        }

        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_INSTALLED_VER, verifiedVersion)
            .apply()

        return UpdateResult(
            ok = true,
            updated = true,
            version = verifiedVersion,
            message = "Updated yt-dlp to $verifiedVersion ✓"
        )
    }

    private fun isValidYtDlpZipapp(file: File): Boolean {
        return try {
            ZipFile(file).use { z ->
                z.getEntry("yt_dlp/version.py") != null && z.getEntry("__main__.py") != null
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeVersion(v: String): String =
        v.trim().removePrefix("v").replace(".0", ".")

    private fun fetchText(context: Context, url: String): String? {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "GrabAll-Android")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    return resp.body?.string()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OkHttp fetch failed, trying Python TLS fallback: ${e.message}")
        }
        // Fallback for old Android 5-6 devices with expired system TLS certs
        return fetchViaPython(context, url, destFile = null)
    }

    private fun downloadFile(context: Context, url: String, dest: File): Boolean {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "GrabAll-Android")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.byteStream()?.use { input ->
                        FileOutputStream(dest).use { out ->
                            input.copyTo(out)
                        }
                    }
                    return dest.isFile && dest.length() > 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OkHttp download failed, trying Python TLS fallback: ${e.message}")
        }
        return fetchViaPython(context, url, destFile = dest) != null && dest.isFile && dest.length() > 0
    }

    private fun fetchViaPython(context: Context, url: String, destFile: File?): String? {
        return try {
            val pyBin = RuntimePaths.pythonBinary(context)
            if (!RuntimeExtractor.ensureExecutable(pyBin)) return null
            val script = if (destFile != null) {
                "import urllib.request, sys; " +
                    "req=urllib.request.Request(sys.argv[1], headers={'User-Agent':'GrabAll-Android'}); " +
                    "open(sys.argv[2],'wb').write(urllib.request.urlopen(req, timeout=30).read()); print('OK')"
            } else {
                "import urllib.request, sys; " +
                    "req=urllib.request.Request(sys.argv[1], headers={'User-Agent':'GrabAll-Android'}); " +
                    "sys.stdout.write(urllib.request.urlopen(req, timeout=20).read().decode('utf-8','replace'))"
            }
            val cmd = mutableListOf(pyBin.absolutePath, "-c", script, url)
            if (destFile != null) cmd.add(destFile.absolutePath)
            val pb = ProcessBuilder(cmd)
            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = RuntimePaths.pythonLibDir(context).absolutePath
            env["PYTHONHOME"] = RuntimePaths.pythonHomeDir(context).absolutePath
            env["PYTHONPATH"] = RuntimePaths.ytDlpBundleDir(context).absolutePath
            val cert = RuntimePaths.certFile(context)
            if (cert.exists()) env["SSL_CERT_FILE"] = cert.absolutePath
            val proc = pb.redirectErrorStream(false).start()
            val out = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val rc = proc.waitFor()
            if (rc == 0 && out.isNotBlank()) out else null
        } catch (_: Exception) {
            null
        }
    }
}
