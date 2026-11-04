package com.graball

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.graball.engine.CookieImporter
import com.graball.engine.DownloadProgressBus
import com.graball.engine.DownloadService
import com.graball.engine.RuntimePaths
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var queueContainer: LinearLayout
    private lateinit var cookiesStatus: TextView
    private lateinit var qualitySpinner: Spinner
    private lateinit var audioOnlyCheck: CheckBox
    private lateinit var playlistSpinner: Spinner
    private val rowStatus = mutableMapOf<String, TextView>()
    private val rowDetail = mutableMapOf<String, TextView>()
    private val rowCancel = mutableMapOf<String, Button>()
    private val cancelledUrls = mutableSetOf<String>()
    // What each row is downloading ("Audio only · First 20", "720p"),
    // so a finished row still answers "was that audio or video?".
    private val rowMode = mutableMapOf<String, String>()

    private val progressListener: (DownloadProgressBus.State) -> Unit = { state ->
        updateRow(state)
    }

    // Cookies import lives on the main screen now: the old login
    // screen's WebView sign-in is refused by Google (401), so the
    // import is the session's front door, not a buried fallback.
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                val result = CookieImporter.importCookies(this, uri)
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                if (result.ok) {
                    cookiesStatus.text = getString(R.string.cookies_saved)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        queueContainer = findViewById(R.id.queueContainer)
        cookiesStatus = findViewById(R.id.cookiesStatus)
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
        findViewById<Button>(R.id.downloadButton).setOnClickListener {
            val url = linkInput.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Paste a video link first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val playlistMode = when (playlistSpinner.selectedItemPosition) {
                1 -> "20"
                2 -> "30"
                3 -> "50"
                4 -> "all"
                else -> "single"
            }
            if (playlistMode == "single" && url.contains("playlist?list=")) {
                Toast.makeText(
                    this,
                    "That's a playlist — pick how many videos above",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            // A fresh staging subdir per download: a shared staging
            // dir lets yt-dlp "skip" a re-download off a leftover file
            // and lets the service mistake a stale path for a success.
            val staging = File(
                RuntimePaths.stagingDir(this),
                System.currentTimeMillis().toString()
            )
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
            DownloadService.startDownload(
                this, url, template,
                audioOnlyCheck.isChecked, maxHeight, playlistMode
            )
            addRow(url, getString(R.string.queue_queued))
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.browseButton).setOnClickListener {
            startActivity(Intent(this, BrowseActivity::class.java))
        }
        findViewById<Button>(R.id.importButton).setOnClickListener {
            importLauncher.launch("*/*")
        }
    }

    override fun onResume() {
        super.onResume()
        // Visible proof of whether the cookies import worked, so a failed
        // download never leaves the user guessing about the session.
        cookiesStatus.text = if (RuntimePaths.cookiesFile(this).exists()) {
            getString(R.string.cookies_saved)
        } else {
            getString(R.string.cookies_missing)
        }
        DownloadProgressBus.subscribe(progressListener)
    }

    override fun onPause() {
        DownloadProgressBus.unsubscribe(progressListener)
        super.onPause()
    }

    /** Add a queue row for [url], or refresh it if one already exists. */
    private fun addRow(url: String, statusText: String) {
        if (rowStatus.containsKey(url)) {
            rowStatus[url]?.text = statusText
            rowDetail[url]?.visibility = android.view.View.GONE
            rowCancel[url]?.visibility = android.view.View.VISIBLE
            return
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }
        val title = TextView(this).apply {
            text = url
            textSize = 14f
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
        }
        val status = TextView(this).apply {
            text = statusText
            textSize = 13f
        }
        val detail = TextView(this).apply {
            textSize = 12f
            visibility = android.view.View.GONE
        }
        val cancel = Button(this).apply {
            text = getString(R.string.queue_cancel)
            textSize = 12f
            setOnClickListener {
                // The service kills the running yt-dlp; the bus will
                // still report FAILED for it, but this row stays on
                // "Cancelled" because the URL is in cancelledUrls.
                cancelledUrls.add(url)
                status.text = getString(R.string.queue_cancelled)
                detail.visibility = android.view.View.GONE
                visibility = android.view.View.GONE
                DownloadService.cancel(this@MainActivity)
            }
        }
        row.addView(title)
        row.addView(status)
        row.addView(detail)
        row.addView(cancel)
        queueContainer.addView(row)
        rowStatus[url] = status
        rowDetail[url] = detail
        rowCancel[url] = cancel
    }

    private fun updateRow(state: DownloadProgressBus.State) {
        val statusView = rowStatus[state.id] ?: run {
            addRow(state.id, "")
            rowStatus[state.id]!!
        }
        val detailView = rowDetail[state.id]
        val cancelView = rowCancel[state.id]
        if (cancelledUrls.contains(state.id)) {
            statusView.text = getString(R.string.queue_cancelled)
            detailView?.visibility = android.view.View.GONE
            cancelView?.visibility = android.view.View.GONE
            return
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
        }
        cancelView?.visibility = if (
            state.status == DownloadProgressBus.Status.DOWNLOADING
        ) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        if (state.status == DownloadProgressBus.Status.FAILED &&
            !state.detail.isNullOrBlank() && detailView != null
        ) {
            detailView.text = state.detail
            detailView.visibility = android.view.View.VISIBLE
        } else {
            detailView?.visibility = android.view.View.GONE
        }
    }
}
