package com.graball.engine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.graball.MainActivity
import com.graball.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks GrabAll's official open-source GitHub repository (Avishka-55/grab-all)
 * directly for newer signed Android APK releases.
 *
 * Non-annoying design:
 * - Auto-checks at most once every 24 hours.
 * - Remembers the last notified version so each release posts at most ONE silent
 *   (`IMPORTANCE_LOW`) notification — never a blocking startup popup.
 * - Caches the latest available release locally so the Settings tab dot badge and
 *   Settings screen can display it immediately with zero extra network calls.
 */
object AppUpdateChecker {

    private const val PREFS = "graball_app_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_NOTIFY_ENABLED = "notify_enabled"
    private const val KEY_LAST_NOTIFIED_VER = "last_notified_version"
    private const val KEY_CACHED_VER = "cached_version"
    private const val KEY_CACHED_URL = "cached_url"
    private const val KEY_CACHED_SIZE = "cached_size"
    private const val KEY_CACHED_NOTES = "cached_notes"

    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L // 24 hours
    private const val CHANNEL_ID = "graball_app_updates"
    private const val NOTIFICATION_ID = 9401

    private const val REPO = "Avishka-55/grab-all"
    private const val LATEST_API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$REPO/releases?per_page=10"
    private const val LATEST_WEB_URL = "https://github.com/$REPO/releases/latest"

    data class AppRelease(
        val version: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val isNewer: Boolean,
        val currentVersion: String,
        val releaseNotes: String = ""
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

    fun isNotifyEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFY_ENABLED, true)

    fun setNotifyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_NOTIFY_ENABLED, enabled)
            .apply()
    }

    fun shouldAutoCheck(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS
    }

    /** Returns the cached newer release if one is available and still newer than installed. */
    fun getCachedAvailableRelease(context: Context): AppRelease? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ver = prefs.getString(KEY_CACHED_VER, null)?.trim().orEmpty()
        val url = prefs.getString(KEY_CACHED_URL, null)?.trim().orEmpty()
        if (ver.isBlank() || url.isBlank()) return null
        val current = currentVersionName(context)
        if (!isVersionNewer(ver, current)) {
            clearCachedRelease(context)
            return null
        }
        return AppRelease(
            version = ver,
            apkUrl = url,
            sizeBytes = prefs.getLong(KEY_CACHED_SIZE, 0L),
            isNewer = true,
            currentVersion = current,
            releaseNotes = prefs.getString(KEY_CACHED_NOTES, "") ?: ""
        )
    }

    fun hasCachedUpdate(context: Context): Boolean = getCachedAvailableRelease(context) != null

    private fun cacheRelease(context: Context, release: AppRelease) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (release.isNewer) {
            editor.putString(KEY_CACHED_VER, release.version)
                .putString(KEY_CACHED_URL, release.apkUrl)
                .putLong(KEY_CACHED_SIZE, release.sizeBytes)
                .putString(KEY_CACHED_NOTES, release.releaseNotes)
        } else {
            editor.remove(KEY_CACHED_VER)
                .remove(KEY_CACHED_URL)
                .remove(KEY_CACHED_SIZE)
                .remove(KEY_CACHED_NOTES)
        }
        editor.apply()
    }

    private fun clearCachedRelease(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_CACHED_VER)
            .remove(KEY_CACHED_URL)
            .remove(KEY_CACHED_SIZE)
            .remove(KEY_CACHED_NOTES)
            .apply()
    }

    /**
     * Posts a quiet, low-priority notification at most ONCE per version.
     * Returns true if a notification was posted.
     */
    fun notifyOnceIfEligible(context: Context, release: AppRelease): Boolean {
        if (!release.isNewer || !isNotifyEnabled(context)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastNotified = prefs.getString(KEY_LAST_NOTIFIED_VER, null)
        if (lastNotified == release.version) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Quiet notifications when a new version of GrabAll is available"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = MainActivity.createUpdateIntent(context)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(context, NOTIFICATION_ID, openIntent, pendingFlags)

        val sizeText = if (release.sizeBytes > 0) {
            String.format(" · %.1f MB", release.sizeBytes / (1024.0 * 1024.0))
        } else {
            ""
        }
        val bodyText = "v${release.currentVersion} → v${release.version}$sizeText · Tap to view & update"

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_downloads)
            .setContentTitle("GrabAll v${release.version} available")
            .setContentText(bodyText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(NOTIFICATION_ID, notif)
        prefs.edit().putString(KEY_LAST_NOTIFIED_VER, release.version).apply()
        return true
    }

    /** Fetch latest Android release info. Always call off main thread. */
    fun check(context: Context, markChecked: Boolean = true): AppRelease? {
        if (markChecked) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply()
        }
        val current = currentVersionName(context)
        val result = checkFromLatestApi(current)
            ?: checkFromReleasesApi(current)
            ?: checkFromLatestRedirect(current)
        if (result != null) {
            cacheRelease(context, result)
        }
        return result
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

        val notes = cleanReleaseNotes(rel.optString("body", ""))
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
                        currentVersion = current,
                        releaseNotes = notes
                    )
                }
            }
        }
        return null
    }

    /** Trim GitHub Markdown release body into a concise 4-line summary. */
    private fun cleanReleaseNotes(raw: String): String {
        if (raw.isBlank()) return ""
        val lines = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("---") }
            .map { line ->
                line.replace("**", "")
                    .replace("`", "")
                    .replace(Regex("^[-*]\\s+"), "• ")
            }
            .take(5)
            .toList()
        return lines.joinToString("\n").take(360)
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
