package com.graball

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.appcompat.app.AppCompatActivity
import com.graball.engine.WebViewCookieSync
import com.graball.youtube.YouTubeInnerTube
import com.graball.youtube.YouTubeSessionConfig

/**
 * Dedicated on-device YouTube account screen supporting three flows:
 * - [MODE_SIGN_IN]: loads `https://m.youtube.com/signin`
 * - [MODE_SWITCH_ACCOUNT]: loads Google's AccountChooser continuing into
 *   `m.youtube.com/signin`, waiting until `LOGIN_INFO` actually changes before
 *   auto-closing so the previous account's cookie never triggers a false exit
 * - [MODE_LOGOUT]: loads `https://m.youtube.com/logout` for a clean server-side
 *   sign-out, then expires YouTube session cookies locally
 *
 * Uses a clean Chrome Mobile User-Agent (WebView `; wv` / `Version/4.0` tokens
 * removed) and multi-window popup support for Google 2FA / OAuth prompts.
 * Android's [CookieManager] is the persistent session cache shared directly
 * with GrabAll's OkHttp client and exported to `youtube-cookies.txt` for yt-dlp.
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "youtube_auth_mode"
        const val MODE_SIGN_IN = "sign_in"
        const val MODE_SWITCH_ACCOUNT = "switch_account"
        const val MODE_LOGOUT = "logout"

        private const val YOUTUBE_SIGN_IN_URL = "https://m.youtube.com/signin"
        private const val YOUTUBE_SWITCH_ACCOUNT_URL =
            "https://accounts.google.com/AccountChooser?uilel=0&service=youtube&passive=false" +
                "&continue=https%3A%2F%2Fm.youtube.com%2Fsignin%3Fhl%3Den%26app%3Dm%26action_handle_signin%3Dtrue"
        private const val YOUTUBE_LOGOUT_URL = "https://m.youtube.com/logout"

        fun createSignInIntent(context: Context): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_MODE, MODE_SIGN_IN)

        fun createSwitchAccountIntent(context: Context): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_MODE, MODE_SWITCH_ACCOUNT)

        fun createLogoutIntent(context: Context): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_MODE, MODE_LOGOUT)
    }

    private lateinit var webView: WebView
    private lateinit var actionButton: Button
    private var popupDialog: Dialog? = null
    private var popupWebView: WebView? = null
    private var authMode: String = MODE_SIGN_IN
    private var initialLoginInfo: String? = null
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        webView = findViewById(R.id.loginWebView)
        actionButton = findViewById(R.id.doneButton)

        authMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SIGN_IN
        initialLoginInfo = WebViewCookieSync.getLoginInfoToken()

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
                        handleNavigationState(webView.url, pageFinished = false)
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
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                handleNavigationState(url, pageFinished = false)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                handleNavigationState(url, pageFinished = true)
            }

            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String,
                isReload: Boolean
            ) {
                super.doUpdateVisitedHistory(view, url, isReload)
                handleNavigationState(url, pageFinished = false)
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

        if (authMode == MODE_LOGOUT) {
            actionButton.text = "Finish sign out"
            actionButton.setOnClickListener { completeLogout() }
        } else {
            actionButton.text = getString(R.string.done_save_login)
            actionButton.setOnClickListener { manualSaveLogin() }
        }

        if (savedInstanceState == null) {
            val targetUrl = when (authMode) {
                MODE_SWITCH_ACCOUNT -> YOUTUBE_SWITCH_ACCOUNT_URL
                MODE_LOGOUT -> YOUTUBE_LOGOUT_URL
                else -> YOUTUBE_SIGN_IN_URL
            }
            webView.loadUrl(targetUrl)
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

    private fun isAuthBouncePath(url: String?): Boolean {
        val path = url?.let {
            try {
                Uri.parse(it).path
            } catch (_: Exception) {
                null
            }
        }?.lowercase().orEmpty()
        return path.startsWith("/signin") || path.startsWith("/logout")
    }

    private fun handleNavigationState(url: String?, pageFinished: Boolean) {
        if (completed) return

        if (authMode == MODE_LOGOUT) {
            val loggedOut = !WebViewCookieSync.hasLoggedInYouTubeSession()
            val returnedHome = WebViewCookieSync.isYouTubeUrl(url) && !isAuthBouncePath(url)
            if (loggedOut || (pageFinished && returnedHome)) {
                completeLogout()
            }
            return
        }

        if (!WebViewCookieSync.isYouTubeUrl(url) || isAuthBouncePath(url)) return
        if (!WebViewCookieSync.hasLoggedInYouTubeSession()) return

        val currentLoginInfo = WebViewCookieSync.getLoginInfoToken()
        // In switch-account mode, wait until LOGIN_INFO actually changes so we
        // don't auto-close on the previous account's cookie before switching.
        if (authMode == MODE_SWITCH_ACCOUNT &&
            !initialLoginInfo.isNullOrBlank() &&
            currentLoginInfo == initialLoginInfo
        ) {
            return
        }

        completeSignIn()
    }

    private fun completeSignIn() {
        if (completed) return
        completed = true
        WebViewCookieSync.flushCookies()
        WebViewCookieSync.exportYouTubeCookiesIfLoggedIn(this, forceWrite = true)
        YouTubeSessionConfig.invalidate()
        YouTubeInnerTube.resetAnonBotBlockHint()
        webView.stopLoading()
        val msg = if (authMode == MODE_SWITCH_ACCOUNT) {
            "YouTube account switched"
        } else {
            "YouTube login saved"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun completeLogout() {
        if (completed) return
        completed = true
        WebViewCookieSync.clearYouTubeSession(this)
        webView.stopLoading()
        Toast.makeText(this, "Signed out of YouTube", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun manualSaveLogin() {
        if (WebViewCookieSync.hasLoggedInYouTubeSession()) {
            completeSignIn()
        } else {
            Toast.makeText(
                this,
                "Sign-in not complete yet — wait for YouTube home page to finish loading",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
