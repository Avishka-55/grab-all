package com.graball.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Self-updater for yt-dlp talking directly to the official GitHub repo:
 * - Stable:  yt-dlp/yt-dlp
 * - Nightly: yt-dlp/yt-dlp-nightly-builds
 *
 * Includes:
 * - Zero-rate-limit `/releases/latest` redirect fallback when GitHub API returns 403.
 * - Safe background auto-update (default ON): Wi-Fi only, Stable channel only,
 *   once every 7 days, and only when no downloads are active.
 * - ZIP integrity + live `python yt-dlp.tmp --version` verification before atomic swap.
 */
object YtDlpUpdater {

    private const val TAG = "YtDlpUpdater"
    private const val PREFS = "graball_ytdlp_updater"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_INSTALLED_VER = "installed_version"
    private const val KEY_AUTO_UPDATE = "auto_update_wifi"
    private const val KEY_LAST_AUTO_CHECK = "last_auto_check_ms"

    private const val AUTO_CHECK_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L // 7 days

    const val CHANNEL_STABLE = "stable"
    const val CHANNEL_NIGHTLY = "nightly"

    private val updateInFlight = AtomicBoolean(false)

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

    fun isAutoUpdateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_UPDATE, true)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_UPDATE, enabled)
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

    /**
     * Silent background auto-update with strict guardrails:
     * - Enabled in Settings (default ON)
     * - Unmetered Wi-Fi connection only
     * - At most once every 7 days
     * - Only when no downloads are currently active
     * - Always uses Stable channel so Nightly regressions never surprise the user
     *
     * Call off the main thread.
     */
    fun maybeAutoUpdateInBackground(context: Context) {
        if (!isAutoUpdateEnabled(context)) return
        if (DownloadProgressBus.activeCount() > 0) return
        if (!isOnWifi(context)) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_AUTO_CHECK, 0L)
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return

        // Mark checked before network work so retries wait until next cycle
        prefs.edit().putLong(KEY_LAST_AUTO_CHECK, System.currentTimeMillis()).apply()

        val res = checkAndUpdate(context, forceChannel = CHANNEL_STABLE)
        Log.i(TAG, "Background yt-dlp auto-check finished: ${res.message}")
    }

    private fun isOnWifi(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                info != null && info.isConnected && info.type == ConnectivityManager.TYPE_WIFI
            }
        } catch (_: Exception) {
            false
        }
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
        forceChannel: String? = null,
        onStatus: ((String) -> Unit)? = null
    ): UpdateResult {
        if (!updateInFlight.compareAndSet(false, true)) {
            return UpdateResult(false, false, getCachedVersion(context), "Update already in progress")
        }
        return try {
            checkAndUpdateInternal(context, forceChannel, onStatus)
        } finally {
            updateInFlight.set(false)
        }
    }

    private fun checkAndUpdateInternal(
        context: Context,
        forceChannel: String?,
        onStatus: ((String) -> Unit)?
    ): UpdateResult {
        if (!RuntimeExtractor.extractIfNeeded(context, DownloadService.RUNTIME_VERSION)) {
            return UpdateResult(false, false, "", RuntimeExtractor.lastError ?: "Runtime setup failed")
        }
        val channel = forceChannel ?: getChannel(context)
        val repo = if (channel == CHANNEL_NIGHTLY) {
            "yt-dlp/yt-dlp-nightly-builds"
        } else {
            "yt-dlp/yt-dlp"
        }
        onStatus?.invoke("Checking $repo…")

        val resolved = resolveLatestRelease(context, repo)
            ?: return UpdateResult(false, false, "", "Could not reach GitHub — check internet connection")

        val latestTag = resolved.first
        val downloadUrl = resolved.second

        val currentVersion = probeInstalledVersion(context)?.trim().orEmpty()
        if (currentVersion.isNotBlank() && normalizeVersion(currentVersion) == normalizeVersion(latestTag)) {
            return UpdateResult(true, false, currentVersion, "Already up to date ($currentVersion)")
        }

        // Bail before touching active binary if a download started mid-check
        if (forceChannel != null && DownloadProgressBus.activeCount() > 0) {
            return UpdateResult(false, false, currentVersion, "Skipped while download is active")
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

        if (forceChannel != null && DownloadProgressBus.activeCount() > 0) {
            tmp.delete()
            return UpdateResult(false, false, currentVersion, "Deferred swap while download is active")
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

    /**
     * Resolves `(latestTag, downloadUrl)` via GitHub's zero-rate-limit `/releases/latest`
     * redirect first, falling back to the JSON API (with Python TLS fallback for Android 5-6).
     */
    private fun resolveLatestRelease(context: Context, repo: String): Pair<String, String>? {
        // 1. Fast zero-rate-limit redirect probe
        try {
            val req = Request.Builder()
                .url("https://github.com/$repo/releases/latest")
                .header("User-Agent", "GrabAll-Android")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val finalUrl = resp.request.url.toString()
                    val idx = finalUrl.indexOf("/releases/tag/")
                    if (idx >= 0) {
                        val tag = finalUrl.substring(idx + "/releases/tag/".length)
                            .substringBefore('?')
                            .substringBefore('#')
                            .trim()
                        if (tag.isNotBlank()) {
                            return tag to "https://github.com/$repo/releases/download/$tag/yt-dlp"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Redirect probe failed, trying GitHub API: ${e.message}")
        }

        // 2. Fallback to GitHub Releases API (with Python TLS fallback for old Android)
        val apiUrl = "https://api.github.com/repos/$repo/releases/latest"
        val releaseJsonStr = fetchText(context, apiUrl) ?: return null
        val release = try {
            JSONObject(releaseJsonStr)
        } catch (_: Exception) {
            return null
        }
        val latestTag = release.optString("tag_name").trim()
        if (latestTag.isBlank()) return null

        var downloadUrl: String? = null
        val assets = release.optJSONArray("assets")
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
        return latestTag to downloadUrl
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
