package com.graball

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.graball.engine.AppUpdateChecker
import com.graball.engine.DownloadPrefs
import com.graball.engine.DownloadService
import com.graball.engine.DownloadProgressBus
import com.graball.engine.FastYouTubeDownloader
import com.graball.engine.LinkDetector
import com.graball.engine.LinkKind
import com.graball.engine.RuntimePaths
import com.graball.youtube.DownloadSheetHelper
import com.graball.youtube.PlaylistPickerDialog
import com.graball.youtube.ThumbnailLoader
import com.graball.youtube.WatchHistoryEntry
import com.graball.youtube.YouTubeActivity
import com.graball.youtube.YouTubePlaybackService
import com.graball.youtube.YouTubePlayerActivity
import com.graball.youtube.YouTubeStore
import com.graball.youtube.YtItem
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var qualitySpinner: Spinner
    private lateinit var audioOnlyCheck: CheckBox
    private lateinit var playlistSpinner: Spinner
    private lateinit var linkInput: EditText
    private lateinit var clearLinkButton: TextView
    private lateinit var grabPanel: LinearLayout
    private lateinit var grabType: TextView
    private lateinit var grabCloseButton: TextView
    private lateinit var playlistRow: LinearLayout
    private lateinit var grabQuickRow: LinearLayout
    private lateinit var grabWatchButton: TextView
    private lateinit var grabSecondButton: TextView
    private lateinit var clipboardBanner: LinearLayout
    private lateinit var clipboardLabel: TextView
    private lateinit var clipboardPreview: TextView
    private lateinit var clipboardActionBtn: TextView
    private lateinit var clipboardDismissBtn: TextView
    private lateinit var continueWatchingSection: LinearLayout
    private lateinit var continueWatchingContainer: LinearLayout
    private lateinit var continueWatchingSeeAll: TextView
    /** Top tabs (Search is this screen); kept to re-select Search on return. */
    private lateinit var mainTabs: List<TextView>
    /** What the open Grab panel holds; video panels force single. */
    private var grabbedKind: LinkKind = LinkKind.VIDEO
    private var detectedClipboardUrl: String? = null
    private var dismissedClipboardUrl: String? = null
    private var lastContinueWatchingKey: String? = null
    private val homeIo = Executors.newSingleThreadExecutor()

    private val hintHandler = Handler(Looper.getMainLooper())
    private val rotatingHints = listOf(
        "Search YouTube…",
        "Paste any video URL…",
        "Search songs or playlists…"
    )
    private var hintIdx = 0
    private val hintCycler = object : Runnable {
        override fun run() {
            if (::linkInput.isInitialized && linkInput.text.isNullOrEmpty() && !linkInput.hasFocus()) {
                hintIdx = (hintIdx + 1) % rotatingHints.size
                linkInput.animate().alpha(0.35f).setDuration(140L).withEndAction {
                    linkInput.hint = rotatingHints[hintIdx]
                    linkInput.animate().alpha(1f).setDuration(180L).start()
                }.start()
            }
            hintHandler.postDelayed(this, 2800L)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Android 13+ (API 33+) hides foreground download/music
        // notifications unless POST_NOTIFICATIONS is granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        qualitySpinner = findViewById(R.id.qualitySpinner)
        qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Best", "1080p", "720p", "480p", "360p")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        audioOnlyCheck = findViewById(R.id.audioOnlyCheck)
        playlistSpinner = findViewById(R.id.playlistSpinner)
        playlistSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Single video", "First 20", "First 30", "First 50", "All videos")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val linkInput = findViewById<EditText>(R.id.linkInput)
        this.linkInput = linkInput
        clearLinkButton = findViewById(R.id.clearLinkButton)
        grabPanel = findViewById(R.id.grabPanel)
        grabType = findViewById(R.id.grabType)
        grabCloseButton = findViewById(R.id.grabCloseButton)
        playlistRow = findViewById(R.id.playlistRow)
        grabQuickRow = findViewById(R.id.grabQuickRow)
        grabWatchButton = findViewById(R.id.grabWatchButton)
        grabSecondButton = findViewById(R.id.grabSecondButton)
        clipboardBanner = findViewById(R.id.clipboardBanner)
        clipboardLabel = findViewById(R.id.clipboardLabel)
        clipboardPreview = findViewById(R.id.clipboardPreview)
        clipboardActionBtn = findViewById(R.id.clipboardActionBtn)
        clipboardDismissBtn = findViewById(R.id.clipboardDismissBtn)
        continueWatchingSection = findViewById(R.id.continueWatchingSection)
        continueWatchingContainer = findViewById(R.id.continueWatchingContainer)
        continueWatchingSeeAll = findViewById(R.id.continueWatchingSeeAll)

        // Subtle orange-accented border on the Clipboard Auto-Detect pill
        val density = resources.displayMetrics.density
        clipboardBanner.background = GradientDrawable().apply {
            cornerRadius = 12f * density
            setColor(0xFF1F1B18.toInt())
            setStroke((1f * density).toInt().coerceAtLeast(1), 0x55FF6B35)
        }

        fun triggerDetectedClipboardLink() {
            val url = detectedClipboardUrl ?: return
            dismissedClipboardUrl = url
            clipboardBanner.visibility = View.GONE
            val kind = LinkDetector.detect(url)
            if (kind == LinkKind.UNKNOWN) return
            if (!openYouTubeLinkSheet(url, kind, clearInputOnAction = false)) {
                linkInput.setText(url)
                showGrabPanel(url, kind)
            }
        }
        clipboardBanner.setOnClickListener { triggerDetectedClipboardLink() }
        clipboardActionBtn.setOnClickListener { triggerDetectedClipboardLink() }
        clipboardDismissBtn.setOnClickListener {
            dismissedClipboardUrl = detectedClipboardUrl
            clipboardBanner.visibility = View.GONE
        }

        continueWatchingSeeAll.setOnClickListener {
            startActivity(
                Intent(this, YouTubeActivity::class.java)
                    .putExtra("start_mode", "history")
            )
        }

        fun dismissGrabPanel(clearText: Boolean) {
            grabPanel.visibility = View.GONE
            if (clearText && !linkInput.text.isNullOrEmpty()) {
                linkInput.setText("")
            }
        }
        clearLinkButton.setOnClickListener { dismissGrabPanel(clearText = true) }
        grabCloseButton.setOnClickListener { dismissGrabPanel(clearText = true) }
        linkInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrEmpty()
                clearLinkButton.visibility = if (hasText) View.VISIBLE else View.GONE
                if (!hasText && grabPanel.visibility == View.VISIBLE) {
                    grabPanel.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        grabWatchButton.setOnClickListener {
            val url = linkInput.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            val plId = FastYouTubeDownloader.extractPlaylistId(url)
            val vidId = FastYouTubeDownloader.extractVideoId(url)
            if (grabbedKind == LinkKind.PLAYLIST && !plId.isNullOrBlank()) {
                startActivity(
                    Intent(this, YouTubeActivity::class.java)
                        .putExtra("start_playlist_id", plId)
                )
            } else if (!vidId.isNullOrBlank()) {
                val item = YtItem(
                    id = vidId,
                    title = "YouTube Video",
                    channel = "",
                    thumbnailUrl = "https://i.ytimg.com/vi/$vidId/hqdefault.jpg"
                )
                YouTubePlaybackService.withService(this) { svc ->
                    svc.playItem(item, audioOnly = false)
                }
                startActivity(
                    Intent(this, YouTubePlayerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("restore_video", true)
                    }
                )
            }
        }

        grabSecondButton.setOnClickListener {
            val url = linkInput.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            if (grabbedKind == LinkKind.PLAYLIST && FastYouTubeDownloader.extractPlaylistId(url) != null) {
                PlaylistPickerDialog.showForUrl(this, url)
            } else {
                val vidId = FastYouTubeDownloader.extractVideoId(url) ?: return@setOnClickListener
                val item = YtItem(
                    id = vidId,
                    title = "YouTube Audio",
                    channel = "",
                    thumbnailUrl = "https://i.ytimg.com/vi/$vidId/hqdefault.jpg"
                )
                YouTubePlaybackService.withService(this) { svc ->
                    svc.playItem(item, audioOnly = true)
                }
                startActivity(
                    Intent(this, PlayerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("open_now_playing", true)
                    }
                )
            }
        }
        // Option 4 chrome: tabs, site cards, bottom nav. Search is
        // this screen; YouTube opens the in-app browser; More opens
        // the supported-sites list.
        mainTabs = listOf(
            findViewById(R.id.tabSearch),
            findViewById(R.id.tabYouTube),
            findViewById(R.id.tabMusic),
            findViewById(R.id.tabMore)
        )
        selectTab(mainTabs[0])
        mainTabs[1].setOnClickListener {
            selectTab(mainTabs[1])
            startActivity(Intent(this, YouTubeActivity::class.java))
        }
        mainTabs[2].setOnClickListener {
            selectTab(mainTabs[2])
            startActivity(
                Intent(this, YouTubeActivity::class.java).putExtra("start_mode", "music")
            )
        }
        mainTabs[3].setOnClickListener {
            selectTab(mainTabs[3])
            startActivity(Intent(this, SitesActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.siteYoutube).setOnClickListener {
            startActivity(Intent(this, YouTubeActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.siteTiktok).setOnClickListener {
            startActivity(
                Intent(this, BrowseActivity::class.java).putExtra(
                    "start_url", "https://www.tiktok.com"
                )
            )
        }
        findViewById<LinearLayout>(R.id.siteInstagram).setOnClickListener {
            startActivity(
                Intent(this, BrowseActivity::class.java).putExtra(
                    "start_url", "https://www.instagram.com"
                )
            )
        }
        findViewById<LinearLayout>(R.id.siteFacebook).setOnClickListener {
            startActivity(
                Intent(this, BrowseActivity::class.java).putExtra(
                    "start_url", "https://www.facebook.com"
                )
            )
        }
        findViewById<LinearLayout>(R.id.siteWhatsapp).setOnClickListener {
            startActivity(Intent(this, StatusActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.siteMore).setOnClickListener {
            startActivity(Intent(this, SitesActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.navDownloads).setOnClickListener {
            NavHelper.switchTab(this, DownloadsActivity::class.java)
        }
        findViewById<LinearLayout>(R.id.navPlayer).setOnClickListener {
            NavHelper.switchTab(this, PlayerActivity::class.java)
        }
        findViewById<LinearLayout>(R.id.navSettings).setOnClickListener {
            NavHelper.switchTab(this, SettingsActivity::class.java)
        }
        fun handleSmartGrabOrSearch() {
            var input = linkInput.text.toString().trim()
            if (input.isEmpty()) {
                // Empty box + a link on the clipboard: Grab pulls it
                // in first, so the button is one tap, not two.
                val clip = (getSystemService(Context.CLIPBOARD_SERVICE)
                    as? ClipboardManager)?.primaryClip
                    ?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
                val found = Regex("https?://\\S+").find(clip)?.value
                    ?.trimEnd('.', ',', ';', '!', '?', ')', ']')
                if (found != null) {
                    input = found
                    linkInput.setText(input)
                }
            }
            if (input.isEmpty()) {
                Toast.makeText(this, "Type a search or paste a video link", Toast.LENGTH_SHORT).show()
                return
            }
            val kind = LinkDetector.detect(input)
            if (kind != LinkKind.UNKNOWN) {
                dismissedClipboardUrl = input
                clipboardBanner.visibility = View.GONE
                if (!openYouTubeLinkSheet(input, kind, clearInputOnAction = true)) {
                    showGrabPanel(input, kind)
                }
            } else {
                // Keywords detected -> jump directly into YouTube search!
                grabPanel.visibility = android.view.View.GONE
                startActivity(
                    Intent(this, YouTubeActivity::class.java)
                        .putExtra("start_query", input)
                )
            }
        }
        findViewById<Button>(R.id.grabButton).setOnClickListener {
            handleSmartGrabOrSearch()
        }
        linkInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                handleSmartGrabOrSearch()
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.downloadButton).setOnClickListener {
            val url = linkInput.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Paste a video link first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // The panel decides the mode: a video panel is always
            // single, whatever the (hidden) playlist spinner holds.
            val playlistMode = if (grabbedKind == LinkKind.PLAYLIST) {
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
            if (playlistMode == "single" && url.contains("playlist?list=")) {
                Toast.makeText(
                    this,
                    "That's a playlist — pick how many videos above",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            // A STABLE staging subdir per URL (not per tap): a re-tap
            // after a network failure must land in the same dir, so
            // yt-dlp finds the kept .part file and resumes.
            val staging = RuntimePaths.stagingDirFor(this, url)
            if (!staging.exists()) {
                staging.mkdirs()
            }
            // Playlist downloads share one folder, so index the file
            // names — same-title videos would otherwise overwrite.
            // v0.7.5: a playlist lands in its own folder named after
            // it (Downloads/GrabAll/<Playlist Title>), via the
            // %(playlist_title)s template dir; singles stay flat.
            val fileName = if (playlistMode == "single") {
                "%(title)s.%(ext)s"
            } else {
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
                when (playlistMode) {
                    "20" -> append(" · First 20")
                    "30" -> append(" · First 30")
                    "50" -> append(" · First 50")
                    "all" -> append(" · All videos")
                }
            }
            // Remember these choices for the next panel — but a
            // video download must not overwrite the playlist choice
            // (see DownloadPrefs).
            val saved = DownloadPrefs.load(this)
            DownloadPrefs.save(
                this,
                DownloadPrefs.Choices(
                    qualitySpinner.selectedItemPosition,
                    audioOnlyCheck.isChecked,
                    if (grabbedKind == LinkKind.PLAYLIST) {
                        playlistSpinner.selectedItemPosition
                    } else {
                        saved.playlistPos
                    }
                )
            )
            DownloadService.startDownload(
                this, url, template,
                audioOnlyCheck.isChecked, maxHeight, playlistMode
            )
            // No row here anymore: the row is born on the Downloads
            // screen, from the first progress update for this URL.
            grabPanel.visibility = View.GONE
            linkInput.setText("")
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        }
        handleShareIntent(intent)
        maybeCheckAppUpdate()
    }

    /** Silent periodic (every 6h) check against GrabAll's public Android release mirror. */
    private fun maybeCheckAppUpdate() {
        if (!AppUpdateChecker.shouldAutoCheck(this)) return
        Thread {
            val release = AppUpdateChecker.check(applicationContext, markChecked = true)
            if (release != null && release.isNewer) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val sizeStr = if (release.sizeBytes > 0) {
                        String.format(" (%.1f MB)", release.sizeBytes / (1024.0 * 1024.0))
                    } else {
                        ""
                    }
                    AlertDialog.Builder(this)
                        .setTitle("GrabAll v${release.version} Available")
                        .setMessage(
                            "A newer Android version of GrabAll is ready$sizeStr.\n" +
                                "Current: v${release.currentVersion} → New: v${release.version}"
                        )
                        .setPositiveButton("Download APK") { _, _ ->
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)))
                            } catch (_: Exception) {
                            }
                        }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Show the Grab panel for a link: the header says what was
     * detected (video or playlist, plus a short preview), the
     * playlist count only appears for playlists, and the spinners
     * open on the user's last-used choices.
     */
    private fun showGrabPanel(url: String, kind: LinkKind) {
        grabbedKind = kind
        grabType.text = (if (kind == LinkKind.PLAYLIST) "Playlist" else "Video") +
            " · " + LinkDetector.preview(url)
        playlistRow.visibility = if (kind == LinkKind.PLAYLIST) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        val isYt = FastYouTubeDownloader.isYouTubeUrl(url)
        val hasYtPlaylist = isYt && FastYouTubeDownloader.extractPlaylistId(url) != null
        val hasYtVideo = isYt && FastYouTubeDownloader.extractVideoId(url) != null
        if (kind == LinkKind.PLAYLIST && hasYtPlaylist) {
            grabQuickRow.visibility = android.view.View.VISIBLE
            grabWatchButton.text = "▶ Watch Playlist"
            grabSecondButton.text = "☑ Select Items"
        } else if (hasYtVideo) {
            grabQuickRow.visibility = android.view.View.VISIBLE
            grabWatchButton.text = "▶ Watch Video"
            grabSecondButton.text = "🎧 Listen Audio"
        } else {
            grabQuickRow.visibility = android.view.View.GONE
        }
        val prefs = DownloadPrefs.load(this)
        qualitySpinner.setSelection(prefs.qualityPos)
        audioOnlyCheck.isChecked = prefs.audioOnly
        if (kind == LinkKind.PLAYLIST) {
            playlistSpinner.setSelection(prefs.playlistPos)
        }
        grabPanel.visibility = android.view.View.VISIBLE
    }

    /**
     * Route any YouTube video or playlist URL straight into GrabAll's modern
     * Download Bottom Sheet (with real resolutions, file sizes, and Watch Ad-Free
     * / Listen Audio quick actions). Returns false for non-YouTube URLs so they
     * continue using the generic Grab panel.
     */
    private fun openYouTubeLinkSheet(
        url: String,
        kind: LinkKind,
        clearInputOnAction: Boolean
    ): Boolean {
        if (!FastYouTubeDownloader.isYouTubeUrl(url)) return false
        val plId = FastYouTubeDownloader.extractPlaylistId(url)
        val vidId = FastYouTubeDownloader.extractVideoId(url)
        val item = when {
            kind == LinkKind.PLAYLIST && !plId.isNullOrBlank() -> YtItem(
                id = plId,
                title = "YouTube Playlist",
                channel = "",
                isPlaylist = true
            )
            !vidId.isNullOrBlank() -> YtItem(
                id = vidId,
                title = "YouTube Video",
                channel = "",
                thumbnailUrl = "https://i.ytimg.com/vi/$vidId/hqdefault.jpg"
            )
            else -> return false
        }
        grabPanel.visibility = View.GONE
        DownloadSheetHelper.show(
            context = this,
            item = item,
            showQuickActions = true
        ) {
            if (clearInputOnAction && !linkInput.text.isNullOrEmpty()) {
                linkInput.setText("")
            }
        }
        return true
    }

    /**
     * Shared links land here from the YouTube app, Chrome, etc.
     * (ACTION_SEND, text/plain). YouTube links open directly in the modern
     * Download & Watch bottom sheet; other links open the Grab panel.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) {
            return
        }
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(shared)?.value
            ?.trimEnd('.', ',', ';', '!', '?', ')', ']') ?: return
        dismissedClipboardUrl = url
        clipboardBanner.visibility = View.GONE
        val kind = LinkDetector.detect(url)
        if (kind == LinkKind.UNKNOWN) {
            linkInput.setText(url)
            return
        }
        if (FastYouTubeDownloader.isYouTubeUrl(url)) {
            linkInput.post {
                if (!isFinishing && !isDestroyed) {
                    if (!openYouTubeLinkSheet(url, kind, clearInputOnAction = false)) {
                        linkInput.setText(url)
                        showGrabPanel(url, kind)
                    }
                }
            }
        } else {
            linkInput.setText(url)
            showGrabPanel(url, kind)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            checkClipboardForLink()
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the browser: this screen is Search again, so the
        // tab highlight returns with it.
        if (::mainTabs.isInitialized) {
            selectTab(mainTabs[0])
        }
        hintHandler.removeCallbacks(hintCycler)
        hintHandler.postDelayed(hintCycler, 2800L)
        refreshContinueWatching()
        if (hasWindowFocus()) {
            checkClipboardForLink()
        }
    }

    /**
     * Inspect clipboard for a supported video/playlist URL and display a
     * dismissible one-tap pill below the search bar (requires window focus on Android 10+).
     */
    private fun checkClipboardForLink() {
        if (isFinishing || isDestroyed || !::clipboardBanner.isInitialized) return
        if (grabPanel.visibility == View.VISIBLE) {
            clipboardBanner.visibility = View.GONE
            return
        }
        val clipText = try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
        val url = Regex("https?://\\S+").find(clipText)?.value
            ?.trimEnd('.', ',', ';', '!', '?', ')', ']')
        if (url.isNullOrBlank()) {
            detectedClipboardUrl = null
            clipboardBanner.visibility = View.GONE
            return
        }
        val kind = LinkDetector.detect(url)
        val currentInput = linkInput.text?.toString()?.trim().orEmpty()
        if (kind == LinkKind.UNKNOWN || url == dismissedClipboardUrl || url == currentInput) {
            clipboardBanner.visibility = View.GONE
            return
        }
        detectedClipboardUrl = url
        val isYt = FastYouTubeDownloader.isYouTubeUrl(url)
        clipboardLabel.text = when {
            isYt && kind == LinkKind.PLAYLIST -> "📋 YouTube playlist on clipboard"
            isYt -> "📋 YouTube link on clipboard"
            kind == LinkKind.PLAYLIST -> "📋 Playlist link on clipboard"
            else -> "📋 Video link on clipboard"
        }
        clipboardPreview.text = LinkDetector.preview(url)
        clipboardActionBtn.text = if (isYt) "Grab / Watch" else "Grab"
        clipboardBanner.visibility = View.VISIBLE
    }

    /** Load the 5 most recently watched YouTube videos into the horizontal Home strip. */
    private fun refreshContinueWatching() {
        if (!::continueWatchingSection.isInitialized) return
        val appCtx = applicationContext
        homeIo.execute {
            val entries = YouTubeStore.getHistory(appCtx)
                .filter { !it.item.isPlaylist && it.item.id.isNotBlank() }
                .take(6)
            val key = entries.joinToString("|") { "${it.item.id}:${it.positionMs / 5000L}" }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (entries.isEmpty()) {
                    lastContinueWatchingKey = null
                    continueWatchingSection.visibility = View.GONE
                    return@runOnUiThread
                }
                continueWatchingSection.visibility = View.VISIBLE
                if (key == lastContinueWatchingKey && continueWatchingContainer.childCount > 0) {
                    return@runOnUiThread
                }
                lastContinueWatchingKey = key
                renderContinueWatchingCards(entries)
            }
        }
    }

    private fun renderContinueWatchingCards(entries: List<WatchHistoryEntry>) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val colorCard = ContextCompat.getColor(this, R.color.graball_card)
        val colorText = ContextCompat.getColor(this, R.color.graball_text)
        val colorSub = ContextCompat.getColor(this, R.color.graball_subtext)
        val colorOrange = ContextCompat.getColor(this, R.color.graball_orange)

        continueWatchingContainer.removeAllViews()
        entries.forEachIndexed { idx, entry ->
            val item = entry.item
            val cardWidth = dp(154)
            val thumbHeight = dp(86)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    cardWidth,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (idx < entries.lastIndex) marginEnd = dp(10)
                }
            }

            // Rounded 16:9 thumbnail box with duration badge + orange resume bar
            val thumbBox = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(cardWidth, thumbHeight)
                setBackgroundColor(colorCard)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                    }
                }
                clipToOutline = true
            }

            val thumbIv = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val thumbUrl = item.thumbnailUrl.ifBlank {
                "https://i.ytimg.com/vi/${item.id}/hqdefault.jpg"
            }
            ThumbnailLoader.load(thumbUrl, thumbIv, targetWidthPx = 320)
            thumbBox.addView(thumbIv)

            if (item.duration.isNotBlank()) {
                val durBadge = TextView(this).apply {
                    text = item.duration
                    textSize = 10f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(0xCC000000.toInt())
                    }
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.END
                    ).apply {
                        marginEnd = dp(6)
                        bottomMargin = dp(6)
                    }
                }
                thumbBox.addView(durBadge)
            }

            // Resume progress bar along bottom edge when we have saved progress
            val progressFraction = computeResumeFraction(entry.positionMs, item.duration)
            if (progressFraction > 0f) {
                val track = FrameLayout(this).apply {
                    setBackgroundColor(0x55FFFFFF)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(3),
                        Gravity.BOTTOM
                    )
                }
                val fill = View(this).apply {
                    setBackgroundColor(colorOrange)
                    layoutParams = FrameLayout.LayoutParams(
                        (cardWidth * progressFraction).toInt().coerceAtLeast(dp(6)),
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                track.addView(fill)
                thumbBox.addView(track)
            }
            card.addView(thumbBox)

            val titleTv = TextView(this).apply {
                text = item.title.ifBlank { "YouTube Video" }
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(colorText)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(2), dp(6), dp(2), 0)
            }
            card.addView(titleTv)

            val resumeLabel = formatResumePosition(entry.positionMs)
            val subText = buildList {
                if (item.channel.isNotBlank()) add(item.channel)
                if (resumeLabel.isNotBlank()) add("▶ $resumeLabel")
            }.joinToString(" · ")
            if (subText.isNotBlank()) {
                val subTv = TextView(this).apply {
                    text = subText
                    textSize = 10.5f
                    setTextColor(colorSub)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(2), dp(2), 0)
                }
                card.addView(subTv)
            }

            card.setOnClickListener {
                YouTubePlaybackService.withService(this) { svc ->
                    svc.playItem(item, audioOnly = false)
                }
                startActivity(
                    Intent(this, YouTubePlayerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("restore_video", true)
                    }
                )
            }
            card.setOnLongClickListener {
                DownloadSheetHelper.show(this, item, showQuickActions = true)
                true
            }

            continueWatchingContainer.addView(card)
        }
    }

    private fun computeResumeFraction(positionMs: Long, durationStr: String): Float {
        if (positionMs < 3000L) return 0f
        val durationSec = parseDurationToSeconds(durationStr)
        if (durationSec <= 0L) return 0.25f
        val fraction = positionMs.toFloat() / (durationSec * 1000f)
        return fraction.coerceIn(0.05f, 0.98f)
    }

    private fun parseDurationToSeconds(text: String): Long {
        val parts = text.trim().split(':').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            else -> 0L
        }
    }

    private fun formatResumePosition(positionMs: Long): String {
        val totalSec = positionMs / 1000L
        if (totalSec < 5L) return ""
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    private val tabSwipe by lazy { NavHelper.TabSwipeHelper(this) }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (tabSwipe.onDispatchTouchEvent(ev)) {
            val cancel = android.view.MotionEvent.obtain(ev).apply {
                action = android.view.MotionEvent.ACTION_CANCEL
            }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::grabPanel.isInitialized && grabPanel.visibility == View.VISIBLE) {
            grabPanel.visibility = View.GONE
            linkInput.setText("")
            return
        }
        super.onBackPressed()
    }

    override fun onPause() {
        hintHandler.removeCallbacks(hintCycler)
        super.onPause()
    }

    override fun onDestroy() {
        homeIo.shutdownNow()
        super.onDestroy()
    }

    /** Add a queue row for [url], or refresh it if one already exists. */
    /** Highlight one top tab; the rest go dim. */
    private fun selectTab(selected: TextView) {
        mainTabs.forEach {
            it.setBackgroundResource(android.R.color.transparent)
            it.setTextColor(ContextCompat.getColor(this, R.color.graball_tab_text))
            it.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        selected.setBackgroundResource(R.drawable.tab_selected)
        selected.setTextColor(Color.BLACK)
        selected.setTypeface(null, android.graphics.Typeface.BOLD)
    }

}
