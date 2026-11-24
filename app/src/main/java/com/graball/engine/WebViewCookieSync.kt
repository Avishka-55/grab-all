package com.graball.engine

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.security.MessageDigest

/**
 * On-device YouTube session cookie manager bridging Android WebView's
 * [CookieManager] with OkHttp ([okHttpCookieJar]), GrabAll's native
 * `YouTubeInnerTube` API (`Cookie` + `Authorization: SAPISIDHASH`), and
 * `yt-dlp` (`youtube-cookies.txt`).
 *
 * The single source of truth is Android's persistent WebView [CookieManager]
 * (`flush()` writes changes to disk), so signing in, switching accounts, or
 * logging out in any WebView immediately applies to native API calls.
 */
object WebViewCookieSync {

    private const val NETSCAPE_HEADER = "# Netscape HTTP Cookie File"
    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"
    private const val MIN_LOGIN_INFO_LEN = 10

    /** SID cookies used by YouTube & yt-dlp for SAPISIDHASH authentication. */
    private val SID_COOKIE_NAMES = setOf(
        "SAPISID",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID"
    )

    private val YOUTUBE_HOSTS = listOf(
        "https://youtube.com",
        "https://www.youtube.com",
        "https://m.youtube.com"
    )

    /** Targeted YouTube session cookie keys cleared on sign-out without wiping unrelated sites. */
    private val YOUTUBE_SESSION_COOKIE_KEYS = listOf(
        "YTC",
        "GPS",
        "VISITOR_INFO1_LIVE",
        "PREF",
        "LOGIN_INFO",
        "SAPISID",
        "APISID",
        "SID",
        "HSID",
        "SSID",
        "SIDCC",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID",
        "__Secure-1PSID",
        "__Secure-3PSID",
        "__Secure-1PSIDCC",
        "__Secure-3PSIDCC",
        "__Secure-1PSIDTS",
        "__Secure-3PSIDTS"
    )

    private val EXPORT_SOURCES = listOf(
        "https://youtube.com" to ".youtube.com",
        "https://www.youtube.com" to ".youtube.com",
        "https://m.youtube.com" to ".youtube.com",
        "https://accounts.google.com" to ".google.com",
        "https://www.google.com" to ".google.com"
    )

    data class YouTubeAuthHeaders(
        val cookieHeader: String,
        val authorizationHeader: String
    )

    @Volatile
    private var lastExportedHash: Int = 0

    /**
     * OkHttp [CookieJar] backed directly by Android's [CookieManager] so
     * WebView login and OkHttp InnerTube requests share one live cookie store.
     */
    val okHttpCookieJar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val manager = safeCookieManager() ?: return
            val urlStr = url.toString()
            var wroteAny = false
            for (cookie in cookies) {
                try {
                    manager.setCookie(urlStr, cookie.toString())
                    wroteAny = true
                } catch (_: Exception) {
                }
            }
            if (wroteAny) {
                flushCookies()
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val manager = safeCookieManager() ?: return emptyList()
            val raw = try {
                manager.getCookie(url.toString())
            } catch (_: Exception) {
                null
            } ?: return emptyList()
            if (raw.isBlank()) return emptyList()

            val out = mutableListOf<Cookie>()
            for ((name, value) in parseCookiePairs(raw)) {
                try {
                    val builder = Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(url.host)
                        .path("/")
                    if (url.isHttps) {
                        builder.secure()
                    }
                    out.add(builder.build())
                } catch (_: Exception) {
                }
            }
            return out
        }
    }

    /**
     * Strip WebView-identifying tokens (`; wv` and `Version/4.0`) from the
     * system WebView User-Agent so Google's sign-in page sees the device's
     * real Chrome Mobile version instead of rejecting it as an embedded WebView.
     */
    fun cleanBrowserUserAgent(raw: String): String {
        return raw
            .replace(Regex(";\\s*wv\\b"), "")
            .replace(Regex("\\bVersion/\\d+(?:\\.\\d+)*\\s*"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    fun isYouTubeUrl(url: String?): Boolean {
        val host = url?.let {
            try {
                Uri.parse(it).host
            } catch (_: Exception) {
                null
            }
        }?.lowercase() ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
    }

    fun flushCookies() {
        val manager = safeCookieManager() ?: return
        try {
            manager.flush()
        } catch (_: Exception) {
        }
    }

    /** Looks up a specific cookie value across YouTube domains in [CookieManager]. */
    fun findYouTubeCookie(cookieName: String): String? {
        val manager = safeCookieManager() ?: return null
        for (url in YOUTUBE_HOSTS) {
            val raw = try {
                manager.getCookie(url)
            } catch (_: Exception) {
                null
            } ?: continue
            for ((name, value) in parseCookiePairs(raw)) {
                if (name == cookieName && value.isNotEmpty()) {
                    return value
                }
            }
        }
        return null
    }

    /** Returns the current `LOGIN_INFO` cookie value if present, or null. */
    fun getLoginInfoToken(): String? = findYouTubeCookie("LOGIN_INFO")

    /**
     * Returns true ONLY when [CookieManager] holds a complete YouTube login:
     * a non-trivial `LOGIN_INFO` token (length > 10) AND at least one SAPISID
     * cookie on YouTube, preventing false positives on partial redirect cookies.
     */
    fun hasLoggedInYouTubeSession(): Boolean {
        val cookies = readLiveYouTubeCookies()
        val loginInfo = cookies["LOGIN_INFO"] ?: return false
        if (loginInfo.length <= MIN_LOGIN_INFO_LEN) return false
        return SID_COOKIE_NAMES.any { !cookies[it].isNullOrEmpty() }
    }

    /**
     * If [CookieManager] holds a complete logged-in YouTube session, flushes
     * cookies to disk and writes them to [RuntimePaths.cookiesFile] in Netscape
     * format for the `yt-dlp` fallback engine.
     *
     * Returns true if the cookies file was written or updated.
     */
    fun exportYouTubeCookiesIfLoggedIn(
        context: Context,
        forceWrite: Boolean = false
    ): Boolean {
        val manager = safeCookieManager() ?: return false
        if (!hasLoggedInYouTubeSession()) return false

        flushCookies()

        val expiry = System.currentTimeMillis() / 1000L + 365L * 24L * 3600L
        val deduped = LinkedHashMap<Pair<String, String>, String>()
        for ((url, domain) in EXPORT_SOURCES) {
            val raw = try {
                manager.getCookie(url)
            } catch (_: Exception) {
                null
            } ?: continue
            for ((name, value) in parseCookiePairs(raw)) {
                deduped[domain to name] = "$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value"
            }
        }
        if (deduped.isEmpty()) return false

        val content = buildString {
            append(NETSCAPE_HEADER)
            append('\n')
            for (line in deduped.values) {
                append(line)
                append('\n')
            }
        }

        val file = RuntimePaths.cookiesFile(context)
        val fingerprint = deduped.entries.joinToString("\n") { (k, v) ->
            val valuePart = v.substringAfterLast('\t')
            "${k.first}|${k.second}|$valuePart"
        }.hashCode()
        if (!forceWrite && fingerprint == lastExportedHash && file.exists()) {
            return false
        }

        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            lastExportedHash = fingerprint
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Builds YouTube InnerTube `Cookie` + `Authorization: SAPISIDHASH ...`
     * headers from the live WebView [CookieManager] first (falling back to
     * `youtube-cookies.txt` only if WebView cookies are unavailable).
     */
    fun buildYouTubeAuthHeaders(context: Context): YouTubeAuthHeaders? {
        val cookies = LinkedHashMap<String, String>()
        cookies.putAll(readLiveYouTubeCookies())

        val hasLiveLogin = (cookies["LOGIN_INFO"]?.length ?: 0) > MIN_LOGIN_INFO_LEN &&
            SID_COOKIE_NAMES.any { !cookies[it].isNullOrEmpty() }

        if (!hasLiveLogin) {
            val file = RuntimePaths.cookiesFile(context)
            if (file.isFile) {
                try {
                    for (rawLine in file.readText().lineSequence()) {
                        var line = rawLine.trim()
                        if (line.isEmpty()) continue
                        if (line.startsWith("#HttpOnly_")) {
                            line = line.removePrefix("#HttpOnly_")
                        } else if (line.startsWith("#")) {
                            continue
                        }
                        val fields = line.split('\t')
                        if (fields.size < 7) continue
                        val domain = fields[0].lowercase()
                        if (!domain.contains("youtube.com")) continue
                        val name = fields[5].trim()
                        val value = fields[6].trim()
                        if (name.isNotEmpty() && value.isNotEmpty()) {
                            cookies.putIfAbsent(name, value)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        val loginInfo = cookies["LOGIN_INFO"] ?: return null
        if (loginInfo.length <= MIN_LOGIN_INFO_LEN) return null

        val sapisid = cookies["SAPISID"]
        val sapisid1p = cookies["__Secure-1PAPISID"]
        val sapisid3p = cookies["__Secure-3PAPISID"]
        // Match YouTube / yt-dlp behavior: fall back to __Secure-3PAPISID when
        // plain SAPISID is absent (common on m.youtube.com).
        val effectiveSapisid = sapisid ?: sapisid3p
        if (effectiveSapisid == null && sapisid1p == null && sapisid3p == null) return null

        val ts = System.currentTimeMillis() / 1000L
        val authParts = mutableListOf<String>()
        if (effectiveSapisid != null) {
            authParts += "SAPISIDHASH ${ts}_${sha1Hex("$ts $effectiveSapisid $YOUTUBE_ORIGIN")}"
        }
        if (sapisid1p != null) {
            authParts += "SAPISID1PHASH ${ts}_${sha1Hex("$ts $sapisid1p $YOUTUBE_ORIGIN")}"
        }
        if (sapisid3p != null) {
            authParts += "SAPISID3PHASH ${ts}_${sha1Hex("$ts $sapisid3p $YOUTUBE_ORIGIN")}"
        }
        if (authParts.isEmpty()) return null

        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return YouTubeAuthHeaders(
            cookieHeader = cookieHeader,
            authorizationHeader = authParts.joinToString(" ")
        )
    }

    /**
     * Deletes the exported `youtube-cookies.txt` snapshot and invalidates
     * cached session state without touching unrelated site cookies.
     */
    fun clearExportedCookiesOnly(context: Context) {
        lastExportedHash = 0
        com.graball.youtube.YouTubeInnerTube.resetAnonBotBlockHint()
        com.graball.youtube.YouTubeSessionConfig.invalidate()
        try {
            RuntimePaths.cookiesFile(context).delete()
        } catch (_: Exception) {
        }
    }

    /**
     * Signs out cleanly on-device: deletes `youtube-cookies.txt`, invalidates
     * cached client settings, and surgically expires YouTube session cookies in
     * [CookieManager] (leaving non-YouTube site logins intact).
     */
    fun clearYouTubeSession(context: Context) {
        clearExportedCookiesOnly(context)
        val manager = safeCookieManager() ?: return
        try {
            for (host in YOUTUBE_HOSTS) {
                for (key in YOUTUBE_SESSION_COOKIE_KEYS) {
                    manager.setCookie(host, "$key=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                    manager.setCookie(host, "$key=; Domain=.youtube.com; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                }
            }
            manager.flush()
        } catch (_: Exception) {
        }
    }

    private fun readLiveYouTubeCookies(): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val manager = safeCookieManager() ?: return out
        for (url in YOUTUBE_HOSTS) {
            val raw = try {
                manager.getCookie(url)
            } catch (_: Exception) {
                null
            } ?: continue
            for ((name, value) in parseCookiePairs(raw)) {
                if (value.isNotEmpty()) {
                    out[name] = value
                }
            }
        }
        return out
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun parseCookiePairs(raw: String): List<Pair<String, String>> {
        return raw.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) {
                null
            } else {
                trimmed.substring(0, eq).trim() to trimmed.substring(eq + 1).trim()
            }
        }
    }

    private fun safeCookieManager(): CookieManager? {
        return try {
            CookieManager.getInstance()
        } catch (_: Exception) {
            null
        }
    }
}
