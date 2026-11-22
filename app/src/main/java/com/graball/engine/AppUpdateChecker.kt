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
 * Checks GrabAll's public mirror repo (Avishka-55/graball-website) for newer
 * Android APK releases while completely ignoring PC releases (pc-v*).
 *
 * Primary source: raw latest.json (zero GitHub API rate-limit).
 * Fallback: GitHub Releases API filtered strictly to `android-v*` tags.
 */
object AppUpdateChecker {

    private const val PREFS = "graball_app_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L // 6 hours

    private const val LATEST_JSON_URL =
        "https://raw.githubusercontent.com/Avishka-55/graball-website/main/latest.json"
    private const val RELEASES_API_URL =
        "https://api.github.com/repos/Avishka-55/graball-website/releases?per_page=10"

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
        val fromJson = checkFromLatestJson(current)
        if (fromJson != null) return fromJson
        return checkFromReleasesApi(current)
    }

    private fun checkFromLatestJson(current: String): AppRelease? {
        return try {
            val req = Request.Builder()
                .url(LATEST_JSON_URL)
                .header("User-Agent", "GrabAll-Android")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val androidObj = JSONObject(body).optJSONObject("android") ?: return null
                val version = androidObj.optString("version").removePrefix("v").trim()
                val url = androidObj.optString("url").trim()
                val size = androidObj.optLong("size_bytes", 0L)
                if (version.isBlank() || !url.startsWith("http")) return null
                AppRelease(
                    version = version,
                    apkUrl = url,
                    sizeBytes = size,
                    isNewer = isVersionNewer(version, current),
                    currentVersion = current
                )
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
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val rel = arr.optJSONObject(i) ?: continue
                    val tag = rel.optString("tag_name")
                    // Strictly ignore PC releases (pc-v*)
                    if (!tag.startsWith("android-v")) continue
                    val version = tag.removePrefix("android-v").trim()
                    val assets = rel.optJSONArray("assets") ?: continue
                    for (j in 0 until assets.length()) {
                        val a = assets.optJSONObject(j) ?: continue
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            val url = a.optString("browser_download_url")
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
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isVersionNewer(remote: String, local: String): Boolean {
        val r = remote.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val len = maxOf(r.size, l.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
