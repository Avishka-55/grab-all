package com.graball.youtube

import android.util.Log
import com.graball.engine.WebViewCookieSync
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Dynamically scrapes and caches live YouTube client configuration (`ytcfg`)
 * from `https://www.youtube.com` using the shared [CookieManager]-backed
 * OkHttp client, so InnerTube calls always use fresh `INNERTUBE_API_KEY`,
 * `INNERTUBE_CONTEXT_CLIENT_VERSION`, `ID_TOKEN`, `XSRF_TOKEN`, `visitorData`,
 * and session headers rather than stale hardcoded values.
 *
 * Automatically invalidates and re-scrapes when the user transitions between
 * guest and signed-in states (or switches accounts), or when a signed-in
 * session is missing its `ID_TOKEN`.
 */
object YouTubeSessionConfig {

    private const val TAG = "YouTubeSessionConfig"
    private const val CONFIG_TTL_MS = 30L * 60L * 1000L

    const val DESKTOP_WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

    const val FALLBACK_WEB_CLIENT_VERSION = "2.20260708.00.00"

    data class WebClientConfig(
        val apiKey: String?,
        val clientNameId: String,
        val clientVersion: String,
        val identityToken: String?,
        val xsrfToken: String?,
        val visitorData: String?,
        val gl: String,
        val hl: String,
        val csn: String?,
        val pageCl: String?,
        val pageBuildLabel: String?,
        val variantsChecksum: String?,
        val fetchedAtMs: Long,
        val wasLoggedIn: Boolean,
        val loginInfoSnapshot: String?
    ) {
        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
            return (nowMs - fetchedAtMs) > CONFIG_TTL_MS
        }
    }

    /**
     * OkHttp client sharing the live WebView [android.webkit.CookieManager]
     * cookie store. Use this for authenticated YouTube page & InnerTube calls.
     */
    val sharedOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieSync.okHttpCookieJar)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Cookie-less OkHttp client reserved for anonymous Tier 1 stream bypasses
     * (`VISIONOS`, `ANDROID_VR`, `IOS`) and autocomplete suggestions.
     */
    val anonymousOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val lock = Any()

    @Volatile
    private var cachedWebConfig: WebClientConfig? = null

    fun invalidate() {
        synchronized(lock) {
            cachedWebConfig = null
        }
    }

    /** Updates cached visitorData if InnerTube returns a newer one in `responseContext`. */
    fun updateVisitorData(visitorData: String?) {
        if (visitorData.isNullOrBlank()) return
        synchronized(lock) {
            val current = cachedWebConfig ?: return
            if (current.visitorData != visitorData) {
                cachedWebConfig = current.copy(visitorData = visitorData)
            }
        }
    }

    /**
     * Returns a valid [WebClientConfig], scraping `https://www.youtube.com`
     * whenever the cache is missing, expired, or out of sync with the current
     * WebView login state.
     */
    fun ensureWebConfig(forceRefresh: Boolean = false): WebClientConfig {
        val loggedInNow = WebViewCookieSync.hasLoggedInYouTubeSession()
        val loginTokenNow = if (loggedInNow) WebViewCookieSync.getLoginInfoToken() else null

        if (!forceRefresh) {
            val existing = cachedWebConfig
            if (existing != null && !needsRefresh(existing, loggedInNow, loginTokenNow)) {
                return existing
            }
        }

        synchronized(lock) {
            if (!forceRefresh) {
                val existing = cachedWebConfig
                if (existing != null && !needsRefresh(existing, loggedInNow, loginTokenNow)) {
                    return existing
                }
            }
            val scraped = scrapeWebConfig(loggedInNow, loginTokenNow)
            val result = scraped ?: defaultFallbackConfig(loggedInNow, loginTokenNow)
            if (scraped != null) {
                cachedWebConfig = scraped
            }
            return result
        }
    }

    /** Attaches scraped YouTube client & session headers onto an OkHttp [Request.Builder]. */
    fun injectWebHeaders(builder: Request.Builder, config: WebClientConfig) {
        builder.header("X-YouTube-Client-Name", config.clientNameId)
        builder.header("X-YouTube-Client-Version", config.clientVersion)
        builder.header("Accept-Language", "${config.hl}-${config.gl},${config.hl};q=0.9")
        config.visitorData?.takeIf { it.isNotBlank() }?.let {
            builder.header("X-Goog-Visitor-Id", it)
        }
        config.identityToken?.takeIf { it.isNotBlank() }?.let {
            builder.header("X-YouTube-Identity-Token", it)
        }
        config.pageCl?.takeIf { it.isNotBlank() }?.let {
            builder.header("X-YouTube-Page-CL", it)
        }
        config.pageBuildLabel?.takeIf { it.isNotBlank() }?.let {
            builder.header("X-YouTube-Page-Label", it)
        }
        config.variantsChecksum?.takeIf { it.isNotBlank() }?.let {
            builder.header("X-YouTube-Variants-Checksum", it)
        }
    }

    private fun needsRefresh(
        config: WebClientConfig,
        loggedInNow: Boolean,
        loginTokenNow: String?
    ): Boolean {
        if (config.isExpired()) return true
        if (config.wasLoggedIn != loggedInNow) return true
        if (loggedInNow && config.loginInfoSnapshot != loginTokenNow) return true
        if (loggedInNow && config.identityToken.isNullOrBlank()) return true
        if (config.apiKey.isNullOrBlank()) return true
        return false
    }

    private fun scrapeWebConfig(
        loggedInNow: Boolean,
        loginTokenNow: String?
    ): WebClientConfig? {
        return try {
            val locale = Locale.getDefault()
            val lang = locale.language.takeIf { it.length == 2 } ?: "en"
            val req = Request.Builder()
                .url("https://www.youtube.com/?hl=$lang&persist_hl=1")
                .header("User-Agent", DESKTOP_WEB_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "$lang,en;q=0.9")
                .get()
                .build()

            sharedOkHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null
                parseYtCfgFromHtml(html, loggedInNow, loginTokenNow)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Live ytcfg scrape failed: ${e.message}")
            null
        }
    }

    private fun parseYtCfgFromHtml(
        html: String,
        loggedInNow: Boolean,
        loginTokenNow: String?
    ): WebClientConfig? {
        val merged = JSONObject()
        val marker = "ytcfg.set("
        var searchFrom = 0
        while (searchFrom < html.length) {
            val idx = html.indexOf(marker, searchFrom)
            if (idx < 0) break
            val jsonStart = html.indexOf('{', idx + marker.length)
            if (jsonStart < 0 || jsonStart > idx + marker.length + 12) {
                searchFrom = idx + marker.length
                continue
            }
            val block = extractBalancedJsonObject(html, jsonStart)
            if (block != null) {
                try {
                    val obj = JSONObject(block)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        merged.put(k, obj.opt(k))
                    }
                } catch (_: Exception) {
                }
                searchFrom = jsonStart + block.length
            } else {
                searchFrom = jsonStart + 1
            }
        }

        val apiKey = merged.optString("INNERTUBE_API_KEY").ifBlank {
            extractRegexField(html, """"INNERTUBE_API_KEY"\s*:\s*"([^"]+)"""")
        }.takeIf { it.isNotBlank() }

        val clientVersion = merged.optString("INNERTUBE_CONTEXT_CLIENT_VERSION").ifBlank {
            merged.optString("INNERTUBE_CLIENT_VERSION").ifBlank {
                extractRegexField(html, """"INNERTUBE_CONTEXT_CLIENT_VERSION"\s*:\s*"([^"]+)"""")
            }
        }.ifBlank { FALLBACK_WEB_CLIENT_VERSION }

        val clientNameId = merged.opt("INNERTUBE_CONTEXT_CLIENT_NAME")?.toString()
            ?.takeIf { it.all(Char::isDigit) && it.isNotBlank() }
            ?: "1"

        val idToken = merged.optString("ID_TOKEN").ifBlank {
            extractRegexField(html, """"ID_TOKEN"\s*:\s*"([^"]+)"""")
        }.takeIf { it.isNotBlank() }

        val xsrfToken = merged.optString("XSRF_TOKEN").ifBlank {
            extractRegexField(html, """"XSRF_TOKEN"\s*:\s*"([^"]+)"""")
        }.takeIf { it.isNotBlank() }

        val innerClient = merged.optJSONObject("INNERTUBE_CONTEXT")?.optJSONObject("client")
        val visitorData = merged.optString("VISITOR_DATA").ifBlank {
            innerClient?.optString("visitorData").orEmpty().ifBlank {
                extractRegexField(html, """"visitorData"\s*:\s*"([^"]+)"""")
            }
        }.takeIf { it.isNotBlank() }

        val gl = merged.optString("GL").ifBlank {
            innerClient?.optString("gl").orEmpty()
        }.ifBlank { "US" }

        val hl = merged.optString("HL").ifBlank {
            innerClient?.optString("hl").orEmpty()
        }.ifBlank { "en" }

        val csn = merged.optString("EVENT_ID").ifBlank {
            extractRegexField(html, """"EVENT_ID"\s*:\s*"([^"]+)"""")
        }.takeIf { it.isNotBlank() }

        val pageCl = merged.opt("PAGE_CL")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        val pageBuildLabel = merged.optString("PAGE_BUILD_LABEL").takeIf { it.isNotBlank() }
        val variantsChecksum = merged.optString("VARIANTS_CHECKSUM").takeIf { it.isNotBlank() }

        if (apiKey == null && visitorData == null && clientVersion == FALLBACK_WEB_CLIENT_VERSION) {
            return null
        }

        return WebClientConfig(
            apiKey = apiKey,
            clientNameId = clientNameId,
            clientVersion = clientVersion,
            identityToken = idToken,
            xsrfToken = xsrfToken,
            visitorData = visitorData,
            gl = gl,
            hl = hl,
            csn = csn,
            pageCl = pageCl,
            pageBuildLabel = pageBuildLabel,
            variantsChecksum = variantsChecksum,
            fetchedAtMs = System.currentTimeMillis(),
            wasLoggedIn = loggedInNow,
            loginInfoSnapshot = loginTokenNow
        )
    }

    private fun extractBalancedJsonObject(src: String, startIndex: Int): String? {
        if (startIndex < 0 || startIndex >= src.length || src[startIndex] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        val maxEnd = minOf(src.length, startIndex + 250_000)
        for (i in startIndex until maxEnd) {
            val ch = src[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return src.substring(startIndex, i + 1)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun extractRegexField(html: String, pattern: String): String {
        return Regex(pattern).find(html)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun defaultFallbackConfig(
        loggedInNow: Boolean,
        loginTokenNow: String?
    ): WebClientConfig {
        return WebClientConfig(
            apiKey = null,
            clientNameId = "1",
            clientVersion = FALLBACK_WEB_CLIENT_VERSION,
            identityToken = null,
            xsrfToken = null,
            visitorData = null,
            gl = "US",
            hl = "en",
            csn = null,
            pageCl = null,
            pageBuildLabel = null,
            variantsChecksum = null,
            fetchedAtMs = System.currentTimeMillis(),
            wasLoggedIn = loggedInNow,
            loginInfoSnapshot = loginTokenNow
        )
    }
}
