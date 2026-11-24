package com.graball.engine

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/**
 * Bidirectional sync between Android WebView's [CookieManager] and GrabAll's
 * Netscape-format `youtube-cookies.txt` ([RuntimePaths.cookiesFile]):
 *
 * - WebView → file ([exportYouTubeCookiesIfLoggedIn]): when the user signs into
 *   YouTube inside BrowseActivity, captures YouTube / Google session cookies
 *   and writes a valid `# Netscape HTTP Cookie File` so yt-dlp and
 *   FastYouTubeDownloader immediately use the session.
 * - File → WebView ([injectCookiesIntoWebView]): fallback for users who import
 *   a `cookies.txt` file in Settings — injects those cookies into
 *   [CookieManager] so BrowseActivity opens YouTube already logged in.
 */
object WebViewCookieSync {

    private const val NETSCAPE_HEADER = "# Netscape HTTP Cookie File"

    /** Cookie names that only exist for an authenticated YouTube/Google session. */
    private val AUTH_COOKIE_NAMES = setOf(
        "SAPISID",
        "__Secure-3PAPISID",
        "LOGIN_INFO"
    )

    private val EXPORT_SOURCES = listOf(
        "https://www.youtube.com" to ".youtube.com",
        "https://m.youtube.com" to ".youtube.com",
        "https://accounts.google.com" to ".google.com",
        "https://www.google.com" to ".google.com"
    )

    @Volatile
    private var lastExportedHash: Int = 0

    /**
     * Returns true if [CookieManager] currently holds a logged-in YouTube
     * session (e.g. `SAPISID`, `__Secure-3PAPISID`, or `LOGIN_INFO`).
     */
    fun hasLoggedInYouTubeSession(): Boolean {
        val manager = safeCookieManager() ?: return false
        val urls = listOf("https://www.youtube.com", "https://m.youtube.com")
        for (url in urls) {
            val raw = manager.getCookie(url) ?: continue
            val names = parseCookiePairs(raw).map { it.first }
            if (names.any { it in AUTH_COOKIE_NAMES }) {
                return true
            }
        }
        return false
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

    /**
     * If [CookieManager] holds a logged-in YouTube session, writes the YouTube
     * and Google cookies to [RuntimePaths.cookiesFile] in Netscape format.
     *
     * Returns true if the cookies file was written or updated; false if no
     * logged-in session is present or (when [forceWrite] is false) the exported
     * content is unchanged.
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
     * Reads [RuntimePaths.cookiesFile] (Netscape format) and injects all
     * YouTube / Google cookies into [CookieManager] so BrowseActivity's WebView
     * sees the imported logged-in session.
     *
     * Returns the number of cookies injected.
     */
    fun injectCookiesIntoWebView(context: Context): Int {
        val file = RuntimePaths.cookiesFile(context)
        return injectCookiesFromFile(file)
    }

    fun injectCookiesFromFile(file: File): Int {
        if (!file.isFile) return 0
        val manager = safeCookieManager() ?: return 0
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return 0
        }
        if (text.isBlank()) return 0

        manager.setAcceptCookie(true)
        val nowSec = System.currentTimeMillis() / 1000L
        var count = 0

        for (rawLine in text.lineSequence()) {
            var line = rawLine.trim()
            if (line.isEmpty()) continue

            var httpOnly = false
            if (line.startsWith("#HttpOnly_")) {
                httpOnly = true
                line = line.removePrefix("#HttpOnly_")
            } else if (line.startsWith("#")) {
                continue
            }

            val fields = if (line.contains('\t')) {
                line.split('\t')
            } else {
                line.split(Regex("\\s+"))
            }
            if (fields.size < 7) continue

            val domain = fields[0].trim()
            if (!domain.contains("youtube.com", ignoreCase = true) &&
                !domain.contains("google.com", ignoreCase = true)
            ) {
                continue
            }

            val path = fields[2].trim().ifEmpty { "/" }
            val secure = fields[3].equals("TRUE", ignoreCase = true)
            val expiry = fields[4].trim().toLongOrNull() ?: 0L
            if (expiry in 1 until nowSec) {
                // Skip already-expired cookies.
                continue
            }
            val name = fields[5].trim()
            if (name.isEmpty()) continue
            val value = if (fields.size > 7) {
                fields.subList(6, fields.size).joinToString("\t")
            } else {
                fields[6]
            }

            val host = domain.removePrefix(".")
            if (host.isEmpty()) continue
            val url = "https://$host"

            val header = buildString {
                append(name)
                append('=')
                append(value)
                append("; Path=")
                append(path)
                // RFC 6265: __Host- cookies must NOT specify a Domain attribute.
                if (domain.startsWith(".") && !name.startsWith("__Host-")) {
                    append("; Domain=")
                    append(domain)
                }
                if (secure || name.startsWith("__Secure-") || name.startsWith("__Host-")) {
                    append("; Secure")
                }
                if (httpOnly) {
                    append("; HttpOnly")
                }
                if (expiry > nowSec) {
                    append("; Max-Age=")
                    append(expiry - nowSec)
                }
            }

            try {
                manager.setCookie(url, header)
                count++
            } catch (_: Exception) {
                // Skip malformed cookie entries.
            }
        }

        if (count > 0) {
            try {
                manager.flush()
            } catch (_: Exception) {
                // Ignore flush errors.
            }
        }
        return count
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
