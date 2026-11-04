package com.graball

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        webView = findViewById(R.id.browseWebView)
        loading = findViewById(R.id.browseLoading)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                loading.visibility = android.view.View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                loading.visibility = android.view.View.GONE
                currentUrl = url
                updateFabState()
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
        findViewById<Button>(R.id.browseClose).setOnClickListener {
            hidePanel()
        }
        findViewById<Button>(R.id.browseRefreshLink).setOnClickListener {
            refreshPanelLink()
        }
        if (savedInstanceState == null) {
            // The Music tab passes m.youtube.com/music; everything
            // else opens plain YouTube.
            webView.loadUrl(
                intent.getStringExtra("start_url")
                    ?: "https://www.youtube.com"
            )
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
