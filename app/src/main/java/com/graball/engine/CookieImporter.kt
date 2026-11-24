package com.graball.engine

import android.content.Context
import android.net.Uri

/**
 * Shared cookies.txt import, used by both the main screen's
 * "Import cookies" button and the old login screen.
 *
 * The WebView sign-in is dead — Google refuses sign-in inside an
 * embedded browser (401) — so importing an exported cookies.txt is the
 * only way a session gets into the app. Accepts Netscape-format files
 * and space-separated exports (converted to tabs, like the Telegram
 * bot). Only youtube.com / google.com lines are kept. The
 * "# Netscape HTTP Cookie File" header is always written first:
 * yt-dlp rejects a headerless file, which is exactly what the old
 * import produced by dropping every "#" line. "#HttpOnly_" lines are
 * kept: they are real session cookies, not comments.
 */
object CookieImporter {

    data class Result(val ok: Boolean, val message: String)

    fun importCookies(context: Context, uri: Uri): Result {
        val text = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
        } catch (_: Exception) {
            null
        }
        if (text.isNullOrBlank()) {
            return Result(false, "Couldn't read that file")
        }
        val kept = text.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            if (trimmed.startsWith("#") && !trimmed.startsWith("#HttpOnly_")) {
                return@mapNotNull null
            }
            val fields = trimmed.split("\t", " ").filter { it.isNotEmpty() }
            if (fields.size < 7) return@mapNotNull null
            val domain = fields[0]
            if (!domain.contains("youtube.com") && !domain.contains("google.com")) {
                return@mapNotNull null
            }
            fields.joinToString("\t")
        }
        if (kept.isEmpty()) {
            return Result(
                false,
                "That file doesn't look like a YouTube cookies file"
            )
        }
        val file = RuntimePaths.cookiesFile(context)
        file.parentFile?.mkdirs()
        file.writeText(
            "# Netscape HTTP Cookie File\n" + kept.joinToString("\n") + "\n"
        )
        // Mirror imported cookies into WebView's CookieManager so BrowseActivity
        // opens YouTube already logged in without needing another sign-in.
        WebViewCookieSync.injectCookiesFromFile(file)
        return Result(true, "Cookies imported in app")
    }
}
