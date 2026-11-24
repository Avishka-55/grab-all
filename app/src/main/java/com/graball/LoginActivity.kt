package com.graball

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.graball.engine.CookieImporter
import com.graball.engine.WebViewCookieSync

/**
 * Dedicated on-device YouTube / Google sign-in launched from Settings.
 *
 * Opens Google's YouTube ServiceLogin page inside an in-app WebView with a
 * clean Chrome Mobile User-Agent (WebView `; wv` and `Version/4.0` markers
 * stripped) and multi-window popup support for 2FA/OAuth challenges.
 *
 * As soon as Google redirects back to YouTube with an authenticated session
 * (`SAPISID` / `LOGIN_INFO`), cookies are auto-saved in Netscape format to
 * `youtube-cookies.txt` for yt-dlp / FastYouTubeDownloader and this screen
 * closes — while BrowseActivity is also logged in automatically via Android's
 * shared WebView CookieManager.
 *
 * Also keeps the cookies.txt file import button as a fallback for devices with
 * outdated system WebViews.
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val GOOGLE_YOUTUBE_LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com"
    }

    private lateinit var webView: WebView
    private var popupDialog: Dialog? = null
    private var popupWebView: WebView? = null
    private var autoSaved = false

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
        // Needed so the accounts.google.com -> youtube.com handoff keeps cookies.
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val cleanUserAgent = WebViewCookieSync.cleanBrowserUserAgent(
            webView.settings.userAgentString
        )
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = cleanUserAgent
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.javaScriptCanOpenWindowsAutomatically = true

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                dismissPopup()
                val child = WebView(this@LoginActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = cleanUserAgent
                    settings.setSupportMultipleWindows(true)
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val url = request.url.toString()
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                return false
                            }
                            return try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                true
                            } catch (_: Exception) {
                                true
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView) {
                            dismissPopup()
                        }
                    }
                }
                val dialog = Dialog(
                    this@LoginActivity,
                    android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen
                ).apply {
                    setContentView(child)
                    setCancelable(true)
                    setOnDismissListener {
                        if (popupWebView === child) {
                            popupWebView = null
                            popupDialog = null
                        }
                        child.destroy()
                        maybeAutoSave(webView.url)
                    }
                }
                popupWebView = child
                popupDialog = dialog
                dialog.show()
                transport.webView = child
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                maybeAutoSave(url)
            }

            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String,
                isReload: Boolean
            ) {
                maybeAutoSave(url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (_: Exception) {
                    true
                }
            }
        }

        findViewById<Button>(R.id.doneButton).setOnClickListener { saveLogin() }
        findViewById<Button>(R.id.importButton).setOnClickListener {
            importLauncher.launch("*/*")
        }
        if (savedInstanceState == null) {
            webView.loadUrl(GOOGLE_YOUTUBE_LOGIN_URL)
        }
    }

    override fun onDestroy() {
        dismissPopup()
        super.onDestroy()
    }

    private fun dismissPopup() {
        val dialog = popupDialog
        popupDialog = null
        val child = popupWebView
        popupWebView = null
        if (dialog != null && dialog.isShowing) {
            dialog.dismiss()
        } else {
            child?.destroy()
        }
    }

    private fun isYouTubeUrl(url: String?): Boolean {
        val host = url?.let { Uri.parse(it).host }?.lowercase() ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
    }

    /**
     * Auto-save and close as soon as Google finishes authenticating and
     * redirects back to YouTube with a live session.
     */
    private fun maybeAutoSave(url: String?) {
        if (autoSaved || !isYouTubeUrl(url)) return
        if (WebViewCookieSync.exportYouTubeCookiesIfLoggedIn(this, forceWrite = true)) {
            autoSaved = true
            Toast.makeText(this, "YouTube login saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun saveLogin() {
        if (WebViewCookieSync.exportYouTubeCookiesIfLoggedIn(this, forceWrite = true)) {
            autoSaved = true
            Toast.makeText(this, "YouTube login saved", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "No login found — sign in first", Toast.LENGTH_LONG).show()
        }
    }

    private fun importCookies(uri: Uri) {
        val result = CookieImporter.importCookies(this, uri)
        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
        if (result.ok) {
            finish()
        }
    }
}
