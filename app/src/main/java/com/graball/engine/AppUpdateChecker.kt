package com.graball.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks GrabAll's official open-source GitHub repository (Avishka-55/grab-all)
 * directly for newer signed Android APK releases.
 *
 * Strategy:
 * 1. GitHub Releases `/releases/latest` API (returns latest non-prerelease tag + exact APK size/URL).
 * 2. Fallback: `/releases?per_page=10` API skipping drafts & prereleases (e.g. `runtime-latest`).
 * 3. Zero-rate-limit fallback: follows `github.com/.../releases/latest` redirect to extract the
 *    latest `v*` tag directly when GitHub API's unauthenticated rate limit is hit.
 */
object AppUpdateChecker {

    private const val PREFS = "graball_app_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L // 6 hours

    private const val REPO = "Avishka-55/grab-all"
    private const val LATEST_API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$REPO/releases?per_page=10"
    private const val LATEST_WEB_URL = "https://github.com/$REPO/releases/latest"

    data class AppRelease(
        val version: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val isNewer: Boolean,
        val currentVersion: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun currentVersionName(context: Context): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    fun shouldAutoCheck(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS
    }

    /** Fetch latest Android release info. Always call off main thread. */
    fun check(context: Context, markChecked: Boolean = true): AppRelease? {
        if (markChecked) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply()
        }
        val current = currentVersionName(context)
        checkFromLatestApi(current)?.let { return it }
        checkFromReleasesApi(current)?.let { return it }
        return checkFromLatestRedirect(current)
    }

    private fun checkFromLatestApi(current: String): AppRelease? {
        return try {
            val req = Request.Builder()
                .url(LATEST_API_URL)
                .header("User-Agent", "GrabAll-Android")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                parseReleaseObject(JSONObject(body), current)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun checkFromReleasesApi(current: String): AppRelease? {
        return try {
            val req = Request.Builder()
                .url(RELEASES_API_URL)
                .header("User-Agent", "GrabAll-Android")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val rel = arr.optJSONObject(i) ?: continue
                    val parsed = parseReleaseObject(rel, current)
                    if (parsed != null) return parsed
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseReleaseObject(rel: JSONObject, current: String): AppRelease? {
        if (rel.optBoolean("draft", false) || rel.optBoolean("prerelease", false)) return null
        val tag = rel.optString("tag_name").trim()
        if (!tag.startsWith("v", ignoreCase = true)) return null
        val version = tag.removePrefix("v").removePrefix("V").trim()
        if (version.isBlank()) return null

        val assets = rel.optJSONArray("assets") ?: return null
        for (j in 0 until assets.length()) {
            val a = assets.optJSONObject(j) ?: continue
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                val url = a.optString("browser_download_url").trim()
                if (url.startsWith("http")) {
                    return AppRelease(
                        version = version,
                        apkUrl = url,
                        sizeBytes = a.optLong("size", 0L),
                        isNewer = isVersionNewer(version, current),
                        currentVersion = current
                    )
                }
            }
        }
        return null
    }

    /** Zero-rate-limit fallback via GitHub's /releases/latest HTTP redirect. */
    private fun checkFromLatestRedirect(current: String): AppRelease? {
        return try {
            val req = Request.Builder()
                .url(LATEST_WEB_URL)
                .header("User-Agent", "GrabAll-Android")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val finalUrl = resp.request.url.toString()
                val idx = finalUrl.indexOf("/releases/tag/")
                if (idx < 0) return null
                val tag = finalUrl.substring(idx + "/releases/tag/".length)
                    .substringBefore('?')
                    .substringBefore('#')
                    .trim()
                if (!tag.startsWith("v", ignoreCase = true)) return null
                val version = tag.removePrefix("v").removePrefix("V").trim()
                if (version.isBlank()) return null
                AppRelease(
                    version = version,
                    apkUrl = "https://github.com/$REPO/releases/download/$tag/GrabAll-$tag.apk",
                    sizeBytes = 0L,
                    isNewer = isVersionNewer(version, current),
                    currentVersion = current
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isVersionNewer(remote: String, local: String): Boolean {
        val r = remote.removePrefix("v").removePrefix("V").split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.removePrefix("v").removePrefix("V").split('.').map { it.toIntOrNull() ?: 0 }
        val len = maxOf(r.size, l.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
