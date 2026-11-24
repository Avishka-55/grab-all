package com.graball.engine

import android.content.Context
import android.webkit.CookieManager
import java.security.MessageDigest

/**
 * On-device YouTube session cookie manager bridging Android WebView's
 * [CookieManager] with `yt-dlp` (`youtube-cookies.txt`) and GrabAll's native
 * `YouTubeInnerTube` API (`Cookie` + `Authorization: SAPISIDHASH`).
 *
 * Strictly matches yt-dlp's `_has_auth_cookies` requirement: a YouTube session
 * is only considered authenticated when BOTH `LOGIN_INFO` AND at least one
 * SAPISID cookie (`SAPISID`, `__Secure-1PAPISID`, or `__Secure-3PAPISID`) are
 * present on `.youtube.com`.
 */
object WebViewCookieSync {

    private const val NETSCAPE_HEADER = "# Netscape HTTP Cookie File"
    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

    /** SID cookies used by YouTube & yt-dlp for SAPISIDHASH authentication. */
    private val SID_COOKIE_NAMES = setOf(
        "SAPISID",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID"
    )

    private val YOUTUBE_CHECK_URLS = listOf(
        "https://www.youtube.com",
        "https://m.youtube.com",
        "https://youtube.com"
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

    /**
     * Returns true ONLY when [CookieManager] holds a complete YouTube login
     * (`LOGIN_INFO` AND at least one SAPISID cookie on YouTube), matching
     * yt-dlp's `_has_auth_cookies` requirement so we never capture a half-baked
     * mid-redirect cookie jar.
     */
    fun hasLoggedInYouTubeSession(): Boolean {
        val manager = safeCookieManager() ?: return false
        val names = HashSet<String>()
        for (url in YOUTUBE_CHECK_URLS) {
            val raw = manager.getCookie(url) ?: continue
            for ((name, value) in parseCookiePairs(raw)) {
                if (value.isNotEmpty()) {
                    names.add(name)
                }
            }
        }
        return "LOGIN_INFO" in names && names.any { it in SID_COOKIE_NAMES }
    }

    /**
     * If [CookieManager] holds a complete logged-in YouTube session, writes the
     * YouTube and Google cookies to [RuntimePaths.cookiesFile] in Netscape format.
     *
     * Returns true if the cookies file was written or updated; false if no
     * complete logged-in session is present or (when [forceWrite] is false) the
     * exported content is unchanged.
     */
    fun exportYouTubeCookiesIfLoggedIn(
        context: Context,
        forceWrite: Boolean = false
    ): Boolean {
        val manager = safeCookieManager() ?: return false
        if (!hasLoggedInYouTubeSession()) return false

        try {
            manager.flush()
        } catch (_: Exception) {
            // Ignore flush errors on older WebView implementations.
        }

        val expiry = System.currentTimeMillis() / 1000L + 365L * 24L * 3600L
        val deduped = LinkedHashMap<Pair<String, String>, String>()
        for ((url, domain) in EXPORT_SOURCES) {
            val raw = manager.getCookie(url) ?: continue
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
     * headers from the saved on-device session (preferring `youtube-cookies.txt`
     * and falling back to live WebView cookies). Returns null when not signed in.
     */
    fun buildYouTubeAuthHeaders(context: Context): YouTubeAuthHeaders? {
        val cookies = LinkedHashMap<String, String>()
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
                        cookies[name] = value
                    }
                }
            } catch (_: Exception) {
            }
        }

        if ("LOGIN_INFO" !in cookies || SID_COOKIE_NAMES.none { it in cookies }) {
            val manager = safeCookieManager()
            if (manager != null) {
                for (url in YOUTUBE_CHECK_URLS) {
                    val raw = manager.getCookie(url) ?: continue
                    for ((name, value) in parseCookiePairs(raw)) {
                        if (value.isNotEmpty()) {
                            cookies[name] = value
                        }
                    }
                }
            }
        }

        if ("LOGIN_INFO" !in cookies) return null
        val sapisid = cookies["SAPISID"]
        val sapisid1p = cookies["__Secure-1PAPISID"]
        val sapisid3p = cookies["__Secure-3PAPISID"]
        // Match yt-dlp's _get_sid_cookies: YouTube falls back to __Secure-3PAPISID
        // for SAPISIDHASH when the plain SAPISID cookie is absent (common on m.youtube.com).
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
     * Signs out cleanly on-device: deletes `youtube-cookies.txt` and clears
     * WebView cookies so both native YouTube/yt-dlp and YouTube Web are logged out.
     */
    fun clearYouTubeSession(context: Context) {
        lastExportedHash = 0
        com.graball.youtube.YouTubeInnerTube.resetAnonBotBlockHint()
        try {
            RuntimePaths.cookiesFile(context).delete()
        } catch (_: Exception) {
        }
        val manager = safeCookieManager() ?: return
        try {
            manager.removeAllCookies(null)
            manager.flush()
        } catch (_: Exception) {
        }
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
