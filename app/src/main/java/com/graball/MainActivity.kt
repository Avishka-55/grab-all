package com.graball

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.graball.engine.DownloadHistory
import com.graball.engine.DownloadPrefs
import com.graball.engine.DownloadProgressBus
import com.graball.engine.DownloadService
import com.graball.engine.LinkDetector
import com.graball.engine.LinkKind
import com.graball.engine.RuntimePaths
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var queueContainer: LinearLayout
    private lateinit var qualitySpinner: Spinner
    private lateinit var audioOnlyCheck: CheckBox
    private lateinit var playlistSpinner: Spinner
    private lateinit var linkInput: EditText
    private lateinit var grabPanel: LinearLayout
    private lateinit var grabType: TextView
    private lateinit var playlistRow: LinearLayout
    /** Top tabs (Search is this screen); kept to re-select Search on return. */
    private lateinit var mainTabs: List<TextView>
    /** What the open Grab panel holds; video panels force single. */
    private var grabbedKind: LinkKind = LinkKind.VIDEO
    private val rowStatus = mutableMapOf<String, TextView>()
    private val rowDetail = mutableMapOf<String, TextView>()
    private val rowCancel = mutableMapOf<String, Button>()
    private val rowPause = mutableMapOf<String, Button>()
    private val rowCopyLog = mutableMapOf<String, Button>()
    private val cancelledUrls = mutableSetOf<String>()
    /** URLs currently paused (their rows show Resume, not Pause). */
    private val pausedRows = mutableSetOf<String>()
    // What each row is downloading ("Audio only · First 20", "720p"),
    // so a finished row still answers "was that audio or video?".
    private val rowMode = mutableMapOf<String, String>()

    private val progressListener: (DownloadProgressBus.State) -> Unit = { state ->
        updateRow(state)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        queueContainer = findViewById(R.id.queueContainer)
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
        grabPanel = findViewById(R.id.grabPanel)
        grabType = findViewById(R.id.grabType)
        playlistRow = findViewById(R.id.playlistRow)
        // Option 4 chrome: tabs, site cards, bottom nav. Search is
        // this screen; YouTube opens the in-app browser; everything
        // else is structure that toasts "coming soon" until it is real.
        mainTabs = listOf(
            findViewById(R.id.tabSearch),
            findViewById(R.id.tabYouTube),
            findViewById(R.id.tabMusic),
            findViewById(R.id.tabMore)
        )
        selectTab(mainTabs[0])
        mainTabs[1].setOnClickListener {
            selectTab(mainTabs[1])
            startActivity(Intent(this, BrowseActivity::class.java))
        }
        mainTabs[2].setOnClickListener {
            selectTab(mainTabs[2])
            soonToast("Music")
        }
        mainTabs[3].setOnClickListener {
            selectTab(mainTabs[3])
            soonToast("More sites")
        }
        findViewById<LinearLayout>(R.id.siteYoutube).setOnClickListener {
            startActivity(Intent(this, BrowseActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.siteTiktok).setOnClickListener {
            soonToast("TikTok")
        }
        findViewById<LinearLayout>(R.id.siteInstagram).setOnClickListener {
            soonToast("Instagram")
        }
        findViewById<LinearLayout>(R.id.siteFacebook).setOnClickListener {
            soonToast("Facebook")
        }
        findViewById<LinearLayout>(R.id.siteWhatsapp).setOnClickListener {
            soonToast("WhatsApp Status")
        }
        findViewById<LinearLayout>(R.id.siteMore).setOnClickListener {
            soonToast("More sites")
        }
        findViewById<LinearLayout>(R.id.navPlay).setOnClickListener {
            Toast.makeText(this, getString(R.string.player_soon), Toast.LENGTH_SHORT).show()
        }
        findViewById<LinearLayout>(R.id.navSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.grabButton).setOnClickListener {
            var url = linkInput.text.toString().trim()
            if (url.isEmpty()) {
                // Empty box + a link on the clipboard: Grab pulls it
                // in first, so the button is one tap, not two.
                val clip = (getSystemService(Context.CLIPBOARD_SERVICE)
                    as? ClipboardManager)?.primaryClip
                    ?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
                val found = Regex("https?://\\S+").find(clip)?.value
                    ?.trimEnd('.', ',', ';', '!', '?', ')', ']')
                if (found != null) {
                    url = found
                    linkInput.setText(url)
                }
            }
            if (url.isEmpty()) {
                Toast.makeText(this, "Paste a video link first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val kind = LinkDetector.detect(url)
            if (kind == LinkKind.UNKNOWN) {
                Toast.makeText(this, "That doesn't look like a link", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showGrabPanel(url, kind)
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
            val fileName = if (playlistMode == "single") {
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
            cancelledUrls.remove(url)
            rowMode[url] = buildString {
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
            addRow(url, getString(R.string.queue_queued))
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        }
        // Past downloads survive restarts now: reload them as rows
        // (no cancel button — they are finished, not running).
        DownloadHistory.load(this).forEach { entry ->
            rowMode[entry.url] = entry.mode
            val modeSuffix =
                if (entry.mode.isNotBlank()) " · " + entry.mode else ""
            val statusText = (if (entry.status == "Done") {
                getString(R.string.queue_done)
            } else {
                getString(R.string.queue_failed)
            }) + modeSuffix
            addRow(entry.url, statusText, entry.detail, showCancel = false)
        }
        handleShareIntent(intent)
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
        val prefs = DownloadPrefs.load(this)
        qualitySpinner.setSelection(prefs.qualityPos)
        audioOnlyCheck.isChecked = prefs.audioOnly
        if (kind == LinkKind.PLAYLIST) {
            playlistSpinner.setSelection(prefs.playlistPos)
        }
        grabPanel.visibility = android.view.View.VISIBLE
    }

    /**
     * Shared links land here from the YouTube app, Chrome, etc.
     * (ACTION_SEND, text/plain). Prefill the link field and open
     * the Grab panel straight away — the user still picks options
     * and taps Download, so a share can never start a download by
     * surprise.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) {
            return
        }
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(shared)?.value
            ?.trimEnd('.', ',', ';', '!', '?', ')', ']') ?: return
        findViewById<EditText>(R.id.linkInput).setText(url)
        val kind = LinkDetector.detect(url)
        if (kind != LinkKind.UNKNOWN) {
            showGrabPanel(url, kind)
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the browser: this screen is Search again, so the
        // tab highlight returns with it.
        if (::mainTabs.isInitialized) {
            selectTab(mainTabs[0])
        }
        DownloadProgressBus.subscribe(progressListener)
    }

    override fun onPause() {
        DownloadProgressBus.unsubscribe(progressListener)
        super.onPause()
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

    private fun soonToast(name: String) {
        Toast.makeText(this, getString(R.string.soon_toast, name), Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }

    private fun addRow(
        url: String,
        statusText: String,
        detailText: String? = null,
        showCancel: Boolean = true
    ) {
        if (rowStatus.containsKey(url)) {
            rowStatus[url]?.text = statusText
            rowDetail[url]?.visibility = android.view.View.GONE
            rowCancel[url]?.visibility = android.view.View.VISIBLE
            return
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundResource(R.drawable.card_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
        val title = TextView(this).apply {
            text = url
            textSize = 14f
            setTextColor(Color.WHITE)
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
        }
        val status = TextView(this).apply {
            text = statusText
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        val detail = TextView(this).apply {
            textSize = 12f
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity, R.color.graball_subtext
                )
            )
            visibility = android.view.View.GONE
        }
        if (!detailText.isNullOrBlank()) {
            detail.text = detailText
            detail.visibility = android.view.View.VISIBLE
        }
        val cancel = Button(this).apply {
            text = getString(R.string.queue_cancel)
            textSize = 12f
            setBackgroundResource(R.drawable.btn_small)
            setTextColor(Color.WHITE)
            visibility = if (showCancel) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            setOnClickListener {
                // The service kills only this row's yt-dlp; the bus
                // will still report FAILED for it, but this row stays
                // on "Cancelled" because the URL is in cancelledUrls.
                cancelledUrls.add(url)
                status.text = getString(R.string.queue_cancelled)
                detail.visibility = android.view.View.GONE
                visibility = android.view.View.GONE
                // A paused row's Resume button must die with it, or
                // it would offer to resume a cancelled download.
                rowPause[url]?.visibility = android.view.View.GONE
                rowCopyLog[url]?.visibility = android.view.View.GONE
                DownloadService.cancel(this@MainActivity, url)
            }
        }
        val pause = Button(this).apply {
            text = getString(R.string.queue_pause)
            textSize = 12f
            setBackgroundResource(R.drawable.btn_small)
            setTextColor(Color.WHITE)
            visibility = if (showCancel) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            setOnClickListener {
                // The label is the state: Pause stops the running
                // download (kept .part, resumable), Resume restarts
                // it with the same settings via the service.
                if (pausedRows.contains(url)) {
                    DownloadService.resume(this@MainActivity, url)
                } else {
                    DownloadService.pause(this@MainActivity, url)
                }
            }
        }
        val copyLog = Button(this).apply {
            text = getString(R.string.queue_copy_log)
            textSize = 12f
            setBackgroundResource(R.drawable.btn_small)
            setTextColor(Color.WHITE)
            visibility = android.view.View.GONE
            setOnClickListener {
                val log = DownloadService.getLog(url)
                if (log.isNullOrBlank()) {
                    Toast.makeText(
                        this@MainActivity, "No log to copy",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("GrabAll log", log)
                    )
                    Toast.makeText(
                        this@MainActivity, "Log copied",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        row.addView(title)
        row.addView(status)
        row.addView(detail)
        row.addView(cancel)
        row.addView(pause)
        row.addView(copyLog)
        queueContainer.addView(row)
        rowStatus[url] = status
        rowDetail[url] = detail
        rowCancel[url] = cancel
        rowPause[url] = pause
        rowCopyLog[url] = copyLog
    }

    private fun updateRow(state: DownloadProgressBus.State) {
        val statusView = rowStatus[state.id] ?: run {
            addRow(state.id, "")
            rowStatus[state.id]!!
        }
        val detailView = rowDetail[state.id]
        val cancelView = rowCancel[state.id]
        val pauseView = rowPause[state.id]
        val copyView = rowCopyLog[state.id]
        if (cancelledUrls.contains(state.id)) {
            statusView.text = getString(R.string.queue_cancelled)
            detailView?.visibility = android.view.View.GONE
            cancelView?.visibility = android.view.View.GONE
            pauseView?.visibility = android.view.View.GONE
            copyView?.visibility = android.view.View.GONE
            return
        }
        // Pause is not terminal: it is not history, and the row
        // stays live for Resume. Only COMPLETE/FAILED are recorded.
        if (state.status == DownloadProgressBus.Status.PAUSED) {
            pausedRows.add(state.id)
        } else {
            pausedRows.remove(state.id)
        }
        // Terminal states are history: record once, here, where every
        // row update already flows through.
        if (state.status == DownloadProgressBus.Status.COMPLETE) {
            DownloadHistory.record(
                this, state.id, rowMode[state.id] ?: "", "Done", null
            )
        } else if (state.status == DownloadProgressBus.Status.FAILED) {
            DownloadHistory.record(
                this, state.id, rowMode[state.id] ?: "", "Failed",
                state.detail
            )
        }
        statusView.text = when (state.status) {
            DownloadProgressBus.Status.DOWNLOADING -> {
                val pct = state.percent?.toInt()
                val base = if (pct != null) getString(R.string.queue_downloading, pct)
                else getString(R.string.queue_downloading_unknown)
                val itemSuffix = state.item?.let { " · $it" } ?: ""
                val modeSuffix = rowMode[state.id]?.let { " · $it" } ?: ""
                base + itemSuffix + modeSuffix
            }
            DownloadProgressBus.Status.COMPLETE -> {
                val modeSuffix = rowMode[state.id]?.let { " · $it" } ?: ""
                getString(R.string.queue_done) + modeSuffix
            }
            DownloadProgressBus.Status.FAILED -> getString(R.string.queue_failed)
            DownloadProgressBus.Status.PAUSED -> getString(R.string.queue_paused)
        }
        cancelView?.visibility = if (
            state.status == DownloadProgressBus.Status.DOWNLOADING ||
            state.status == DownloadProgressBus.Status.PAUSED
        ) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        pauseView?.visibility = if (
            state.status == DownloadProgressBus.Status.DOWNLOADING ||
            state.status == DownloadProgressBus.Status.PAUSED
        ) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        if (state.status == DownloadProgressBus.Status.PAUSED) {
            pauseView?.text = getString(R.string.queue_resume)
        } else {
            pauseView?.text = getString(R.string.queue_pause)
        }
        copyView?.visibility = if (
            state.status == DownloadProgressBus.Status.FAILED ||
            state.status == DownloadProgressBus.Status.PAUSED
        ) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        if ((state.status == DownloadProgressBus.Status.FAILED ||
            state.status == DownloadProgressBus.Status.PAUSED) &&
            !state.detail.isNullOrBlank() && detailView != null
        ) {
            detailView.text = state.detail
            detailView.visibility = android.view.View.VISIBLE
        } else {
            detailView?.visibility = android.view.View.GONE
        }
    }
}
