package com.graball

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.graball.engine.DownloadPrefs
import com.graball.engine.DownloadProgressBus
import com.graball.engine.DownloadService
import com.graball.engine.LinkDetector
import com.graball.engine.LinkKind
import com.graball.engine.RuntimePaths
import com.graball.engine.WebViewCookieSync
import java.io.File
import kotlin.math.abs

/**
 * Browse YouTube inside the app, with a Download button for whatever
 * video or playlist page is open — the SnapTube-style flow, no
 * copy-pasting links.
 *
 * The button cannot live in Chrome or the YouTube app (one app cannot
 * touch another app's screen), so it lives here, in GrabAll's own
 * browser view. Watching works fine in a WebView; only Google SIGN-IN
 * is refused inside one, which is why the session still comes from
 * the cookies import in Settings, not from logging in here.
 */
class BrowseActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var qualitySpinner: Spinner
    private lateinit var audioOnlyCheck: CheckBox
    private lateinit var playlistSpinner: Spinner
    private lateinit var panel: LinearLayout
    private lateinit var fab: Button
    private lateinit var linkPreview: TextView
    private lateinit var optionsRow: LinearLayout
    private lateinit var playlistRow: LinearLayout
    private lateinit var downloadButton: Button
    private lateinit var loading: ProgressBar
    private var currentUrl: String? = null
    /** What the open panel holds; UNKNOWN means "not detected". */
    private var panelKind: LinkKind = LinkKind.UNKNOWN
    private var popupDialog: Dialog? = null
    private var popupWebView: WebView? = null
    private var sessionSyncToastShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        webView = findViewById(R.id.browseWebView)
        loading = findViewById(R.id.browseLoading)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
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
                val child = WebView(this@BrowseActivity).apply {
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
                    this@BrowseActivity,
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
                        syncYouTubeSessionIfNeeded(webView.url ?: currentUrl)
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
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (isYouTubeAdRequest(request.url)) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                loading.visibility = android.view.View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                loading.visibility = android.view.View.GONE
                currentUrl = url
                updateFabState()
                if (isYouTubeUrl(url)) {
                    injectYouTubeAdBlocker(view)
                }
                syncYouTubeSessionIfNeeded(url)
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
                updateFabState()
                if (isYouTubeUrl(url)) {
                    injectYouTubeAdBlocker(view)
                }
                syncYouTubeSessionIfNeeded(url)
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
        panel = findViewById(R.id.browsePanel)
        fab = findViewById(R.id.browseFab)
        linkPreview = findViewById(R.id.browseLinkPreview)
        optionsRow = findViewById(R.id.browseOptionsRow)
        playlistRow = findViewById(R.id.browsePlaylistRow)
        downloadButton = findViewById(R.id.browseDownload)
        fab.setOnClickListener {
            if (panel.visibility == android.view.View.VISIBLE) {
                hidePanel()
            } else {
                showPanel()
            }
        }
        // The Grab button floats over the page, so it has to move
        // out of the way: drag it anywhere, tap it to open the
        // panel. A short tap still counts as a click; anything past
        // the touch slop is a drag, and the button stays inside the
        // screen. Position is kept as view translation, so it
        // survives the panel hiding and showing the button.
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var downFabX = 0f
        var downFabY = 0f
        var dragging = false
        fab.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downFabX = fab.x
                    downFabY = fab.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val parent = fab.parent as android.view.View
                        fab.x = (downFabX + dx).coerceIn(
                            0f, (parent.width - fab.width).toFloat()
                        )
                        fab.y = (downFabY + dy).coerceIn(
                            0f, (parent.height - fab.height).toFloat()
                        )
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        fab.performClick()
                    }
                    true
                }
                else -> true
            }
        }
        findViewById<Button>(R.id.browseClose).setOnClickListener {
            hidePanel()
        }
        findViewById<Button>(R.id.browseRefreshLink).setOnClickListener {
            refreshPanelLink()
        }
        if (savedInstanceState == null) {
            val startUrl = intent.getStringExtra("start_url")
                ?: "https://m.youtube.com"
            webView.loadUrl(startUrl)
        }
    }

    override fun onPause() {
        super.onPause()
        syncYouTubeSessionIfNeeded(webView.url ?: currentUrl, showToast = false)
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
     * Layer 1 YouTube Ad-Blocker: blocks known ad & tracking endpoints at the
     * network layer while leaving `accounts.google.com` and `googlevideo.com`
     * completely untouched so login and playback stay seamless.
     */
    private fun isYouTubeAdRequest(uri: Uri?): Boolean {
        if (uri == null) return false
        val host = uri.host?.lowercase() ?: return false
        if (host == "accounts.google.com" || host.endsWith(".googlevideo.com")) {
            return false
        }
        if (host.contains("doubleclick.net") ||
            host.contains("googlesyndication.com") ||
            host.contains("googleadservices.com") ||
            host.startsWith("adservice.google.") ||
            host == "ads.youtube.com" ||
            host == "s0.2mdn.net"
        ) {
            return true
        }
        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            val path = uri.path?.lowercase().orEmpty()
            if (path.contains("/pagead/") ||
                path.contains("/api/stats/ads") ||
                path.contains("/ptracking") ||
                path.contains("/get_midroll_info")
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Layer 2 YouTube Ad-Blocker: injects CSS to hide sponsored feed/banner
     * slots and a lightweight observer that immediately skips/fast-forwards any
     * in-stream video ads on m.youtube.com.
     */
    private fun injectYouTubeAdBlocker(view: WebView) {
        val js = """
            (function() {
              if (!document.getElementById('graball-adblock-css')) {
                var style = document.createElement('style');
                style.id = 'graball-adblock-css';
                style.textContent = [
                  'ytm-promoted-sparkles-web-renderer',
                  'ytm-promoted-video-renderer',
                  'ytm-companion-ad-renderer',
                  'ytm-in-feed-ad-layout-renderer',
                  'ytm-statement-banner-renderer',
                  'ad-slot-renderer',
                  '.ad-showing .ytp-ad-module',
                  '.ytp-ad-overlay-container',
                  '.ytp-ad-player-overlay',
                  '#player-ads'
                ].join(',') + ' { display: none !important; }';
                (document.head || document.documentElement).appendChild(style);
              }
              if (window.__graballAdSkipInstalled) return;
              window.__graballAdSkipInstalled = true;
              function skipAds() {
                try {
                  var skipBtns = document.querySelectorAll(
                    '.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern, .ytp-ad-overlay-close-button'
                  );
                  for (var i = 0; i < skipBtns.length; i++) {
                    skipBtns[i].click();
                  }
                  var player = document.querySelector('.html5-video-player');
                  if (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) {
                    var v = player.querySelector('video');
                    if (v) {
                      v.muted = true;
                      if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.1) {
                        v.currentTime = v.duration;
                      }
                    }
                  }
                } catch (e) {}
              }
              setInterval(skipAds, 250);
              var obs = new MutationObserver(skipAds);
              obs.observe(document.documentElement, { childList: true, subtree: true });
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun syncYouTubeSessionIfNeeded(url: String?, showToast: Boolean = true) {
        if (!isYouTubeUrl(url)) return
        val synced = WebViewCookieSync.exportYouTubeCookiesIfLoggedIn(this)
        if (synced && showToast && !sessionSyncToastShown) {
            sessionSyncToastShown = true
            Toast.makeText(this, "YouTube login synced for downloads", Toast.LENGTH_SHORT).show()
        }
    }

    /** Bright when the open page is downloadable, dim when it is not. */
    private fun updateFabState() {
        val url = webView.url ?: currentUrl ?: return
        fab.alpha =
            if (LinkDetector.detect(url, strictYouTube = true) != LinkKind.UNKNOWN) {
                1.0f
            } else {
                0.5f
            }
    }

    private fun showPanel() {
        refreshPanelLink()
        panel.visibility = android.view.View.VISIBLE
        fab.visibility = android.view.View.GONE
    }

    private fun hidePanel() {
        panel.visibility = android.view.View.GONE
        fab.visibility = android.view.View.VISIBLE
    }

    /**
     * Re-read the live address and reshape the panel around it:
     * video → quality + audio only; playlist → those plus the
     * count; anything else → "link not detected" with just Refresh
     * and Reload, so the user navigates and refreshes instead of
     * downloading the wrong page.
     */
    private fun refreshPanelLink() {
        // Prefer the WebView's live address: the cached one can lag a
        // pushState navigation even with doUpdateVisitedHistory.
        val url = webView.url ?: currentUrl
        val kind = if (url == null) {
            LinkKind.UNKNOWN
        } else {
            LinkDetector.detect(url, strictYouTube = true)
        }
        panelKind = kind
        when (kind) {
            LinkKind.UNKNOWN -> {
                linkPreview.text = getString(R.string.link_not_detected)
                optionsRow.visibility = android.view.View.GONE
                playlistRow.visibility = android.view.View.GONE
                downloadButton.visibility = android.view.View.GONE
            }
            LinkKind.VIDEO -> {
                linkPreview.text = "Video · " + LinkDetector.preview(url!!)
                optionsRow.visibility = android.view.View.VISIBLE
                playlistRow.visibility = android.view.View.GONE
                downloadButton.visibility = android.view.View.VISIBLE
            }
            LinkKind.PLAYLIST -> {
                linkPreview.text = "Playlist · " + LinkDetector.preview(url!!)
                optionsRow.visibility = android.view.View.VISIBLE
                playlistRow.visibility = android.view.View.VISIBLE
                downloadButton.visibility = android.view.View.VISIBLE
            }
        }
        if (kind != LinkKind.UNKNOWN) {
            val prefs = DownloadPrefs.load(this)
            qualitySpinner.setSelection(prefs.qualityPos)
            audioOnlyCheck.isChecked = prefs.audioOnly
            if (kind == LinkKind.PLAYLIST) {
                playlistSpinner.setSelection(prefs.playlistPos)
            }
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
        // Re-detect from the LIVE address at tap time (not the
        // panel's kind): the user may have navigated after opening
        // the panel without tapping Refresh link. Spinner values are
        // left exactly as the user set them — no re-restore here.
        // The explicit null check also smart-casts url to non-null
        // for everything below (the kind check alone cannot).
        val url = webView.url ?: currentUrl
        if (url == null) {
            Toast.makeText(
                this,
                "Open a YouTube video or playlist first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val kind = LinkDetector.detect(url, strictYouTube = true)
        if (kind == LinkKind.UNKNOWN) {
            Toast.makeText(
                this,
                "Open a YouTube video or playlist first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val mode = if (kind == LinkKind.PLAYLIST) {
            when (playlistSpinner.selectedItemPosition) {
                1 -> "20"
                2 -> "30"
                3 -> "50"
                4 -> "all"
                else -> "single"
            }
        } else {
            "single"
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
            // v0.7.5: a playlist lands in its own folder named after
            // it (Downloads/GrabAll/<Playlist Title>).
            "%(playlist_title)s/%(playlist_index)s - %(title)s.%(ext)s"
        }
        val template = File(staging, fileName).absolutePath
        val maxHeight = when (qualitySpinner.selectedItemPosition) {
            1 -> 1080
            2 -> 720
            3 -> 480
            4 -> 360
            else -> null
        }
        // Remember these choices for the next panel — but a video
        // download must not overwrite the playlist choice.
        val saved = DownloadPrefs.load(this)
        DownloadPrefs.save(
            this,
            DownloadPrefs.Choices(
                qualitySpinner.selectedItemPosition,
                audioOnlyCheck.isChecked,
                if (kind == LinkKind.PLAYLIST) {
                    playlistSpinner.selectedItemPosition
                } else {
                    saved.playlistPos
                }
            )
        )
        DownloadProgressBus.modes[url] = buildString {
            if (audioOnlyCheck.isChecked) {
                append("Audio only")
            } else {
                append(
                    when (qualitySpinner.selectedItemPosition) {
                        1 -> "1080p"
                        2 -> "720p"
                        3 -> "480p"
                        4 -> "360p"
                        else -> "Best"
                    }
                )
            }
            when (mode) {
                "20" -> append(" · First 20")
                "30" -> append(" · First 30")
                "50" -> append(" · First 50")
                "all" -> append(" · All videos")
            }
        }
        DownloadService.startDownload(
            this, url, template,
            audioOnlyCheck.isChecked, maxHeight, mode
        )
        Toast.makeText(this, "Download started — back on the main screen", Toast.LENGTH_SHORT).show()
        finish()
    }
}
