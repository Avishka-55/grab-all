package com.graball

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.graball.engine.DownloadService
import com.graball.engine.RuntimePaths
import java.io.File

/**
 * Browse YouTube inside the app, with a Download button for whatever
 * video or playlist page is open — the SnapTube-style flow, no
 * copy-pasting links.
 *
 * The button cannot live in Chrome or the YouTube app (one app cannot
 * touch another app's screen), so it lives here, in GrabAll's own
 * browser view. Watching works fine in a WebView; only Google SIGN-IN
 * is refused inside one, which is why the session still comes from
 * the cookies import on the main screen, not from logging in here.
 */
class BrowseActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var qualitySpinner: Spinner
    private lateinit var audioOnlyCheck: CheckBox
    private lateinit var playlistSpinner: Spinner
    private var currentUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        webView = findViewById(R.id.browseWebView)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                currentUrl = url
            }

            // YouTube is a single-page app: tapping a video changes the
            // address via pushState WITHOUT a full page load, so
            // onPageFinished never fires for it — and the old code kept
            // thinking the user was still on the home page ("Open a
            // YouTube video first" even while a video was open). This
            // callback fires for pushState navigations too, so it is
            // the reliable place to track the address.
            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String,
                isReload: Boolean
            ) {
                currentUrl = url
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
        qualitySpinner = findViewById(R.id.browseQuality)
        qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Best", "1080p", "720p", "480p", "360p")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        audioOnlyCheck = findViewById(R.id.browseAudioOnly)
        playlistSpinner = findViewById(R.id.browsePlaylist)
        playlistSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Single video", "First 20", "First 30", "First 50", "All videos")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        findViewById<Button>(R.id.browseDownload).setOnClickListener {
            downloadCurrentPage()
        }
        findViewById<Button>(R.id.browseReload).setOnClickListener {
            webView.reload()
        }
        if (savedInstanceState == null) {
            webView.loadUrl("https://www.youtube.com")
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun downloadCurrentPage() {
        // Prefer the WebView's live address: the cached one can lag a
        // pushState navigation even with doUpdateVisitedHistory.
        val url = webView.url ?: currentUrl
        if (url == null || !isDownloadablePage(url)) {
            Toast.makeText(
                this,
                "Open a YouTube video or playlist first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val mode = when (playlistSpinner.selectedItemPosition) {
            1 -> "20"
            2 -> "30"
            3 -> "50"
            4 -> "all"
            else -> "single"
        }
        if (mode == "single" && url.contains("playlist?list=")) {
            Toast.makeText(
                this,
                "That's a playlist — pick how many videos above",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val staging = RuntimePaths.stagingDirFor(this, url)
        if (!staging.exists()) {
            staging.mkdirs()
        }
        val fileName = if (mode == "single") {
            "%(title)s.%(ext)s"
        } else {
            "%(playlist_index)s - %(title)s.%(ext)s"
        }
        val template = File(staging, fileName).absolutePath
        val maxHeight = when (qualitySpinner.selectedItemPosition) {
            1 -> 1080
            2 -> 720
            3 -> 480
            4 -> 360
            else -> null
        }
        DownloadService.startDownload(
            this, url, template,
            audioOnlyCheck.isChecked, maxHeight, mode
        )
        Toast.makeText(this, "Download started — back on the main screen", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun isDownloadablePage(url: String): Boolean {
        return url.contains("youtube.com/watch") ||
            url.contains("youtube.com/shorts/") ||
            url.contains("youtube.com/playlist") ||
            url.contains("youtu.be/")
    }
}
