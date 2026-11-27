package com.graball

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.graball.engine.DownloadPrefs
import com.graball.engine.DownloadProgressBus
import com.graball.engine.DownloadService
import com.graball.engine.FastYouTubeDownloader
import com.graball.engine.LinkDetector
import com.graball.engine.LinkKind
import com.graball.engine.RuntimePaths
import com.graball.youtube.DownloadSheetHelper
import com.graball.youtube.PlaylistPickerDialog
import com.graball.youtube.YouTubeActivity
import com.graball.youtube.YouTubePlaybackService
import com.graball.youtube.YouTubePlayerActivity
import com.graball.youtube.YtItem
import java.io.File

/**
 * Grab Home tab hosted inside [MainActivity]'s single-activity bottom-nav shell.
 */
class GrabFragment : Fragment(), MainActivity.TabFragment {

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
    private lateinit var mainTabs: List<TextView>

    private var grabbedKind: LinkKind = LinkKind.VIDEO
    private var detectedClipboardUrl: String? = null
    private var dismissedClipboardUrl: String? = null
    private var pendingShareIntent: Intent? = null
    private var tabActive = true

    private val hintHandler = Handler(Looper.getMainLooper())
    private val rotatingHints = listOf(
        "Search YouTube…",
        "Paste any video URL…",
        "Search songs or playlists…"
    )
    private var hintIdx = 0
    private val hintCycler = object : Runnable {
        override fun run() {
            if (tabActive && isResumed && ::linkInput.isInitialized &&
                linkInput.text.isNullOrEmpty() && !linkInput.hasFocus()
            ) {
                hintIdx = (hintIdx + 1) % rotatingHints.size
                linkInput.animate().alpha(0.35f).setDuration(140L).withEndAction {
                    if (!isAdded) return@withEndAction
                    linkInput.hint = rotatingHints[hintIdx]
                    linkInput.animate().alpha(1f).setDuration(180L).start()
                }.start()
            }
            if (tabActive && isResumed) {
                hintHandler.postDelayed(this, 2800L)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_grab, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        qualitySpinner = view.findViewById(R.id.qualitySpinner)
        qualitySpinner.adapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_spinner_item,
            listOf("Best", "1080p", "720p", "480p", "360p")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        audioOnlyCheck = view.findViewById(R.id.audioOnlyCheck)
        playlistSpinner = view.findViewById(R.id.playlistSpinner)
        playlistSpinner.adapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_spinner_item,
            listOf("Single video", "First 20", "First 30", "First 50", "All videos")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        linkInput = view.findViewById(R.id.linkInput)
        clearLinkButton = view.findViewById(R.id.clearLinkButton)
        grabPanel = view.findViewById(R.id.grabPanel)
        grabType = view.findViewById(R.id.grabType)
        grabCloseButton = view.findViewById(R.id.grabCloseButton)
        playlistRow = view.findViewById(R.id.playlistRow)
        grabQuickRow = view.findViewById(R.id.grabQuickRow)
        grabWatchButton = view.findViewById(R.id.grabWatchButton)
        grabSecondButton = view.findViewById(R.id.grabSecondButton)
        clipboardBanner = view.findViewById(R.id.clipboardBanner)
        clipboardLabel = view.findViewById(R.id.clipboardLabel)
        clipboardPreview = view.findViewById(R.id.clipboardPreview)
        clipboardActionBtn = view.findViewById(R.id.clipboardActionBtn)
        clipboardDismissBtn = view.findViewById(R.id.clipboardDismissBtn)

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
            openUnifiedDownloadSheet(url, kind, clearInputOnAction = false)
        }
        clipboardBanner.setOnClickListener { triggerDetectedClipboardLink() }
        clipboardActionBtn.setOnClickListener { triggerDetectedClipboardLink() }
        clipboardDismissBtn.setOnClickListener {
            dismissedClipboardUrl = detectedClipboardUrl
            clipboardBanner.visibility = View.GONE
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
                launchWithSmoothTransition(
                    Intent(ctx, YouTubeActivity::class.java)
                        .putExtra("start_playlist_id", plId)
                )
            } else if (!vidId.isNullOrBlank()) {
                val item = YtItem(
                    id = vidId,
                    title = "YouTube Video",
                    channel = "",
                    thumbnailUrl = "https://i.ytimg.com/vi/$vidId/hqdefault.jpg"
                )
                YouTubePlaybackService.withService(ctx) { svc ->
                    svc.playItem(item, audioOnly = false)
                }
                launchWithSmoothTransition(
                    Intent(ctx, YouTubePlayerActivity::class.java).apply {
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
                PlaylistPickerDialog.showForUrl(ctx, url)
            } else {
                val vidId = FastYouTubeDownloader.extractVideoId(url) ?: return@setOnClickListener
                val item = YtItem(
                    id = vidId,
                    title = "YouTube Audio",
                    channel = "",
                    thumbnailUrl = "https://i.ytimg.com/vi/$vidId/hqdefault.jpg"
                )
                YouTubePlaybackService.withService(ctx) { svc ->
                    svc.playItem(item, audioOnly = true)
                }
                (activity as? MainActivity)?.openMusicWithIntent(
                    Intent().putExtra("open_now_playing", true)
                )
            }
        }

        // Top category bar: Search | YouTube | Music | More with smooth page transitions
        mainTabs = listOf(
            view.findViewById(R.id.tabSearch),
            view.findViewById(R.id.tabYouTube),
            view.findViewById(R.id.tabMusic),
            view.findViewById(R.id.tabMore)
        )
        selectTopTab(mainTabs[0], animate = false)
        mainTabs[0].setOnClickListener {
            selectTopTab(mainTabs[0], animate = true)
        }
        mainTabs[1].setOnClickListener {
            selectTopTab(mainTabs[1], animate = true)
            launchWithSmoothTransition(Intent(ctx, YouTubeActivity::class.java))
        }
        mainTabs[2].setOnClickListener {
            selectTopTab(mainTabs[2], animate = true)
            launchWithSmoothTransition(
                Intent(ctx, YouTubeActivity::class.java).putExtra("start_mode", "music")
            )
        }
        mainTabs[3].setOnClickListener {
            selectTopTab(mainTabs[3], animate = true)
            launchWithSmoothTransition(Intent(ctx, SitesActivity::class.java))
        }

        view.findViewById<LinearLayout>(R.id.siteYoutube).setOnClickListener {
            launchWithSmoothTransition(Intent(ctx, YouTubeActivity::class.java))
        }
        view.findViewById<LinearLayout>(R.id.siteTiktok).setOnClickListener {
            launchWithSmoothTransition(
                Intent(ctx, BrowseActivity::class.java).putExtra("start_url", "https://www.tiktok.com")
            )
        }
        view.findViewById<LinearLayout>(R.id.siteInstagram).setOnClickListener {
            launchWithSmoothTransition(
                Intent(ctx, BrowseActivity::class.java).putExtra("start_url", "https://www.instagram.com")
            )
        }
        view.findViewById<LinearLayout>(R.id.siteFacebook).setOnClickListener {
            launchWithSmoothTransition(
                Intent(ctx, BrowseActivity::class.java).putExtra("start_url", "https://www.facebook.com")
            )
        }
        view.findViewById<LinearLayout>(R.id.siteWhatsapp).setOnClickListener {
            launchWithSmoothTransition(Intent(ctx, StatusActivity::class.java))
        }
        view.findViewById<LinearLayout>(R.id.siteMore).setOnClickListener {
            launchWithSmoothTransition(Intent(ctx, SitesActivity::class.java))
        }

        fun handleSmartGrabOrSearch() {
            var input = linkInput.text.toString().trim()
            if (input.isEmpty()) {
                val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.primaryClip
                    ?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
                val found = Regex("https?://\\S+").find(clip)?.value
                    ?.trimEnd('.', ',', ';', '!', '?', ')', ']')
                if (found != null) {
                    input = found
                    linkInput.setText(input)
                }
            }
            if (input.isEmpty()) {
                Toast.makeText(ctx, "Type a search or paste a video link", Toast.LENGTH_SHORT).show()
                return
            }
            val kind = LinkDetector.detect(input)
            if (kind != LinkKind.UNKNOWN) {
                dismissedClipboardUrl = input
                clipboardBanner.visibility = View.GONE
                openUnifiedDownloadSheet(input, kind, clearInputOnAction = true)
            } else {
                grabPanel.visibility = View.GONE
                launchWithSmoothTransition(
                    Intent(ctx, YouTubeActivity::class.java).putExtra("start_query", input)
                )
            }
        }

        view.findViewById<Button>(R.id.grabButton).setOnClickListener {
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

        view.findViewById<Button>(R.id.downloadButton).setOnClickListener {
            val url = linkInput.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(ctx, "Paste a video link first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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
                    ctx,
                    "That's a playlist — pick how many videos above",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val staging = RuntimePaths.stagingDirFor(ctx, url)
            if (!staging.exists()) {
                staging.mkdirs()
            }
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
            val saved = DownloadPrefs.load(ctx)
            DownloadPrefs.save(
                ctx,
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
                ctx, url, template,
                audioOnlyCheck.isChecked, maxHeight, playlistMode
            )
            grabPanel.visibility = View.GONE
            linkInput.setText("")
            Toast.makeText(ctx, "Download started", Toast.LENGTH_SHORT).show()
        }

        pendingShareIntent?.let {
            pendingShareIntent = null
            handleShareIntent(it)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mainTabs.isInitialized) {
            selectTopTab(mainTabs[0], animate = false)
        }
        if (tabActive) {
            startHintCycler()
            if (activity?.hasWindowFocus() == true) {
                checkClipboardForLink()
            }
        }
    }

    override fun onPause() {
        stopHintCycler()
        super.onPause()
    }

    override fun onTabSelected() {
        tabActive = true
        if (::mainTabs.isInitialized) {
            selectTopTab(mainTabs[0], animate = false)
        }
        startHintCycler()
        checkClipboardForLink()
    }

    override fun onTabUnselected() {
        tabActive = false
        stopHintCycler()
    }

    override fun handleBackPressed(): Boolean {
        if (::grabPanel.isInitialized && grabPanel.visibility == View.VISIBLE) {
            grabPanel.visibility = View.GONE
            linkInput.setText("")
            return true
        }
        return false
    }

    fun onHostWindowFocusGained() {
        if (tabActive && isResumed) {
            checkClipboardForLink()
        }
    }

    fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (!::linkInput.isInitialized) {
            pendingShareIntent = intent
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
        linkInput.post {
            if (isAdded) {
                openUnifiedDownloadSheet(url, kind, clearInputOnAction = false)
            }
        }
    }

    private fun startHintCycler() {
        hintHandler.removeCallbacks(hintCycler)
        hintHandler.postDelayed(hintCycler, 2800L)
    }

    private fun stopHintCycler() {
        hintHandler.removeCallbacks(hintCycler)
    }

    private fun launchWithSmoothTransition(intent: Intent) {
        val act = activity ?: return
        act.startActivity(intent)
        @Suppress("DEPRECATION")
        act.overridePendingTransition(R.anim.page_enter, R.anim.page_exit)
    }

    private fun openUnifiedDownloadSheet(
        url: String,
        kind: LinkKind,
        clearInputOnAction: Boolean
    ) {
        val ctx = context ?: return
        grabPanel.visibility = View.GONE
        DownloadSheetHelper.showForUrl(
            context = ctx,
            url = url,
            kind = kind
        ) {
            if (clearInputOnAction && ::linkInput.isInitialized && !linkInput.text.isNullOrEmpty()) {
                linkInput.setText("")
            }
        }
    }

    private fun checkClipboardForLink() {
        val ctx = context ?: return
        if (!isAdded || !::clipboardBanner.isInitialized) return
        if (grabPanel.visibility == View.VISIBLE) {
            clipboardBanner.visibility = View.GONE
            return
        }
        val clipText = try {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(ctx)
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
        val lowerUrl = url.lowercase()
        val isYt = FastYouTubeDownloader.isYouTubeUrl(url)
        clipboardLabel.text = when {
            isYt && kind == LinkKind.PLAYLIST -> "📋 YouTube playlist on clipboard"
            isYt -> "📋 YouTube link on clipboard"
            lowerUrl.contains("facebook.com") || lowerUrl.contains("fb.watch") -> "📋 Facebook link on clipboard"
            lowerUrl.contains("tiktok.com") -> "📋 TikTok link on clipboard"
            lowerUrl.contains("instagram.com") -> "📋 Instagram link on clipboard"
            lowerUrl.contains("x.com") || lowerUrl.contains("twitter.com") -> "📋 X link on clipboard"
            kind == LinkKind.PLAYLIST -> "📋 Playlist link on clipboard"
            else -> "📋 Video link on clipboard"
        }
        clipboardPreview.text = LinkDetector.preview(url)
        clipboardActionBtn.text = if (isYt) "Grab / Watch" else "Download"
        clipboardBanner.visibility = View.VISIBLE
    }

    private fun selectTopTab(selected: TextView, animate: Boolean) {
        val ctx = context ?: return
        mainTabs.forEach {
            it.setBackgroundResource(android.R.color.transparent)
            it.setTextColor(ContextCompat.getColor(ctx, R.color.graball_tab_text))
            it.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        selected.setBackgroundResource(R.drawable.tab_selected)
        selected.setTextColor(Color.BLACK)
        selected.setTypeface(null, android.graphics.Typeface.BOLD)
        if (animate) {
            selected.animate().cancel()
            selected.scaleX = 0.94f
            selected.scaleY = 0.94f
            selected.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140L)
                .start()
        }
    }
}
