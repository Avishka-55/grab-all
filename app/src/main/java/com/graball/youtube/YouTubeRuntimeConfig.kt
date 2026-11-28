package com.graball.youtube

import android.content.Context
import android.util.Log
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Silent GitHub-hosted hot-updater for YouTube client versions, fallback order,
 * BotGuard keys, and `base.js` regexes (`config/yt-runtime.json`).
 *
 * - Ships with built-in tested defaults (works 100% offline).
 * - Loads last-known-good config synchronously from SharedPreferences (< 1 ms).
 * - Refreshes silently on a background daemon thread every 4 hours (5s timeout,
 *   zero UI impact, strict regex/schema validation before applying).
 */
object YouTubeRuntimeConfig {

    private const val TAG = "YouTubeRuntimeConfig"
    private const val PREFS_NAME = "yt_runtime_cfg_v1"
    private const val KEY_JSON = "json"
    private const val KEY_FETCHED_AT = "fetched_at_ms"
    private const val REFRESH_INTERVAL_MS = 4L * 60L * 60L * 1000L
    private const val FORCE_REFRESH_MIN_GAP_MS = 5L * 60L * 1000L

    private const val REMOTE_CONFIG_URL =
        "https://raw.githubusercontent.com/Avishka-55/grab-all/main/config/yt-runtime.json"

    data class Snapshot(
        val visionosVersion: String = "1.03",
        val visionosUa: String =
            "com.google.visionosyoutube/1.03 (RealityDevice17,1; U; CPU visionOS 26_6_1 like Mac OS X; en_US) gzip",
        val androidVrVersion: String = "1.61.48",
        val androidVrUa: String =
            "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        val webCreatorVersion: String = "1.20260708.06.00",
        val mwebVersion: String = "2.20260708.05.00",
        val mwebUa: String =
            "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1",
        val tvSamsungVersion: String = "5.20260707",
        val tvSamsungUa: String =
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
        val tvApiVersion: String = "7.20260707.07.00",
        val tvApiUa: String =
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
        val webRemixVersion: String = "1.20260707.12.00",
        val webSafariUa: String =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15",
        val signedInV8Order: List<String> =
            listOf("WEB_CREATOR", "MWEB", "TV_SAMSUNG", "TV_API", "WEB_REMIX"),
        val guestV8Order: List<String> =
            listOf("TV_SAMSUNG", "TV_API"),
        val botguardRequestKey: String = "O43z0dpjhgX20SCx4KAo",
        val botguardJnnApiKey: String = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw",
        val embedPotRegex: String = "^(?=.*pot_cms)(?=.*pot_csms).*$",
        val urlHelperSigRegex: String =
            """([a-zA-Z0-9_${'$'}]{2,})=function\s*\([a-zA-Z0-9_${'$'}]+,[a-zA-Z0-9_${'$'}]+,[a-zA-Z0-9_${'$'}]+\)\{(?:[^}{]{0,600}\bnew\s+(g\.[a-zA-Z0-9_${'$'}]+)\s*\([a-zA-Z0-9_${'$'}]+,(?:!0|true)\)[^}{]{0,400}\.set\(["']alr["'],["']yes["']\)[^}{]{0,200}\breturn\b)"""
    )

    @Volatile
    var current: Snapshot = Snapshot()
        private set

    @Volatile
    private var lastFetchedAtMs: Long = 0L

    private val refreshing = AtomicBoolean(false)

    /** Loads cached config immediately and schedules a non-blocking background refresh if stale. */
    fun init(context: Context) {
        val appCtx = context.applicationContext
        try {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            lastFetchedAtMs = prefs.getLong(KEY_FETCHED_AT, 0L)
            val saved = prefs.getString(KEY_JSON, null)
            if (!saved.isNullOrBlank()) {
                parseValidated(saved)?.let { current = it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading cached runtime config: ${e.message}")
        }
        refreshInBackground(appCtx, force = false)
    }

    /** Silently fetches `config/yt-runtime.json` off the UI thread when stale or forced. */
    fun refreshInBackground(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val minGap = if (force) FORCE_REFRESH_MIN_GAP_MS else REFRESH_INTERVAL_MS
        if ((now - lastFetchedAtMs) < minGap) return
        if (!refreshing.compareAndSet(false, true)) return

        val appCtx = context.applicationContext
        Thread {
            try {
                val client = YouTubeSessionConfig.anonymousOkHttpClient.newBuilder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(6, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(REMOTE_CONFIG_URL)
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string()?.takeIf { it.length in 50..32_768 } ?: return@use
                    val parsed = parseValidated(body) ?: return@use
                    current = parsed
                    val fetchedAt = System.currentTimeMillis()
                    lastFetchedAtMs = fetchedAt
                    appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_JSON, body)
                        .putLong(KEY_FETCHED_AT, fetchedAt)
                        .apply()
                    Log.d(TAG, "Hot-updated YouTube runtime config cleanly")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Runtime config background check skipped: ${e.message}")
            } finally {
                refreshing.set(false)
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun parseValidated(rawJson: String): Snapshot? {
        return try {
            val root = JSONObject(rawJson)
            if (root.optInt("schemaVersion", 0) != 1) return null
            val defaults = Snapshot()
            val c = root.optJSONObject("clients")
            val order = root.optJSONObject("v8ClientOrder")
            val bg = root.optJSONObject("botguard")
            val js = root.optJSONObject("jsSolver")

            fun str(obj: JSONObject?, key: String, fallback: String): String =
                obj?.optString(key)?.trim()?.takeIf { it.isNotBlank() } ?: fallback

            fun strList(obj: JSONObject?, key: String, fallback: List<String>): List<String> {
                val arr: JSONArray = obj?.optJSONArray(key) ?: return fallback
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i).trim().uppercase()
                    if (v.isNotBlank() && v !in out) out.add(v)
                }
                return out.ifEmpty { fallback }
            }

            val embedRegex = str(bg, "embedPotRegex", defaults.embedPotRegex)
            val sigRegex = str(js, "urlHelperSigRegex", defaults.urlHelperSigRegex)
            // Validate regexes before accepting so broken remote JSON can never crash.
            Regex(embedRegex, RegexOption.MULTILINE)
            Regex(sigRegex)

            Snapshot(
                visionosVersion = str(c, "visionosVersion", defaults.visionosVersion),
                visionosUa = str(c, "visionosUa", defaults.visionosUa),
                androidVrVersion = str(c, "androidVrVersion", defaults.androidVrVersion),
                androidVrUa = str(c, "androidVrUa", defaults.androidVrUa),
                webCreatorVersion = str(c, "webCreatorVersion", defaults.webCreatorVersion),
                mwebVersion = str(c, "mwebVersion", defaults.mwebVersion),
                mwebUa = str(c, "mwebUa", defaults.mwebUa),
                tvSamsungVersion = str(c, "tvSamsungVersion", defaults.tvSamsungVersion),
                tvSamsungUa = str(c, "tvSamsungUa", defaults.tvSamsungUa),
                tvApiVersion = str(c, "tvApiVersion", defaults.tvApiVersion),
                tvApiUa = str(c, "tvApiUa", defaults.tvApiUa),
                webRemixVersion = str(c, "webRemixVersion", defaults.webRemixVersion),
                webSafariUa = str(c, "webSafariUa", defaults.webSafariUa),
                signedInV8Order = strList(order, "signedIn", defaults.signedInV8Order),
                guestV8Order = strList(order, "guest", defaults.guestV8Order),
                botguardRequestKey = str(bg, "requestKey", defaults.botguardRequestKey),
                botguardJnnApiKey = str(bg, "jnnApiKey", defaults.botguardJnnApiKey),
                embedPotRegex = embedRegex,
                urlHelperSigRegex = sigRegex
            )
        } catch (e: Exception) {
            Log.w(TAG, "Rejected invalid runtime config JSON: ${e.message}")
            null
        }
    }
}
