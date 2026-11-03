package com.graball

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.graball.engine.RuntimePaths

/**
 * On-device YouTube login, kept entirely inside the app.
 *
 * The user signs in to YouTube in this WebView (a Chrome Custom Tab would
 * share Chrome's cookie jar instead, which defeats the point). Tapping
 * "Done" captures the session cookies for youtube.com / google.com and
 * writes them in Netscape cookies.txt format to RuntimePaths.cookiesFile:
 * private app storage, never exported, passed to yt-dlp via --cookies.
 *
 * Use a throwaway Google account, same rule as the Telegram bot. If Google
 * later challenges the session (VPN country hop, password change, bulk
 * downloads), the fix is a ~30s re-login here, not a cookie export.
 *
 * Fallback (added 2026-11-03): Google refused sign-in inside this WebView
 * on the test phone (401 after tapping Sign in), so the screen also offers
 * a cookies.txt import — same flow as the Telegram bot: export the file
 * in a browser and pick it here. The file lands in the same private
 * cookiesFile yt-dlp reads.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importCookies(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        webView = findViewById(R.id.loginWebView)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        // Needed so the accounts.google.com step of the login flow works.
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        findViewById<Button>(R.id.doneButton).setOnClickListener { saveLogin() }
        findViewById<Button>(R.id.importButton).setOnClickListener {
            importLauncher.launch("*/*")
        }
        if (savedInstanceState == null) {
            webView.loadUrl("https://www.youtube.com")
        }
    }

    private fun saveLogin() {
        CookieManager.getInstance().flush()
        val manager = CookieManager.getInstance()
        val entries = mutableListOf<String>()
        entries += cookiesFor(manager, "https://www.youtube.com", ".youtube.com")
        entries += cookiesFor(manager, "https://accounts.google.com", ".google.com")
        if (entries.isEmpty()) {
            Toast.makeText(this, "No login found — sign in first", Toast.LENGTH_LONG).show()
            return
        }
        val file = RuntimePaths.cookiesFile(this)
        file.parentFile?.mkdirs()
        file.writeText(entries.joinToString("\n") + "\n")
        Toast.makeText(this, "Login saved in app", Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * Import a cookies.txt file picked by the user (the fallback when
     * WebView sign-in is refused). Accepts Netscape-format files and
     * space-separated exports (converted to tabs, like the Telegram bot).
     * Only youtube.com / google.com lines are kept; anything else is
     * rejected without writing.
     */
    private fun importCookies(uri: android.net.Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
        } catch (_: Exception) {
            null
        }
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_LONG).show()
            return
        }
        val kept = text.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val fields = trimmed.split("\t", " ").filter { it.isNotEmpty() }
            if (fields.size < 7) return@mapNotNull null
            val domain = fields[0]
            if (!domain.contains("youtube.com") && !domain.contains("google.com")) {
                return@mapNotNull null
            }
            fields.joinToString("\t")
        }
        if (kept.isEmpty()) {
            Toast.makeText(this, "That file doesn't look like a YouTube cookies file", Toast.LENGTH_LONG).show()
            return
        }
        val file = RuntimePaths.cookiesFile(this)
        file.parentFile?.mkdirs()
        file.writeText(kept.joinToString("\n") + "\n")
        Toast.makeText(this, "Cookies imported in app", Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * Convert one host's cookies to Netscape cookies.txt lines. Expiry is
     * set a year out (a simplification — the export tools do the same);
     * Google may still end the session sooner on its own events.
     */
    private fun cookiesFor(
        manager: CookieManager,
        url: String,
        domain: String
    ): List<String> {
        val raw = manager.getCookie(url) ?: return emptyList()
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        return raw.split(";").mapNotNull { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) {
                null
            } else {
                val name = trimmed.substring(0, eq)
                val value = trimmed.substring(eq + 1)
                "$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value"
            }
        }
    }
}
