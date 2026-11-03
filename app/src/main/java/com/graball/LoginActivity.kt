package com.graball

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
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
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView

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
