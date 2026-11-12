package com.graball

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.graball.engine.DownloadHistory
import com.graball.engine.DownloadProgressBus
import com.graball.engine.DownloadService
import com.graball.engine.MediaStorePublisher
import java.io.File

/**
 * Downloads screen (v0.6.0): the queue moved here from the main
 * screen, so the main screen is just for grabbing links. Rows,
 * history and live progress all live here now — the same machinery
 * MainActivity used to carry, moved verbatim.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var queueContainer: LinearLayout
    private val rowStatus = mutableMapOf<String, TextView>()
    private val rowDetail = mutableMapOf<String, TextView>()
    private val rowCancel = mutableMapOf<String, Button>()
    private val rowPause = mutableMapOf<String, Button>()
    private val rowCopyLog = mutableMapOf<String, Button>()
    private val rowRetry = mutableMapOf<String, Button>()
    private val rowClear = mutableMapOf<String, Button>()
    private val rowOpen = mutableMapOf<String, Button>()
    private val rowViews = mutableMapOf<String, LinearLayout>()
    /** Latest published file per URL, relative to Downloads/GrabAll. */
    private val rowOutput = mutableMapOf<String, String>()
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
        setContentView(R.layout.activity_downloads)
        queueContainer = findViewById(R.id.queueContainer)
        findViewById<LinearLayout>(R.id.navGrab).setOnClickListener {
            navigateHome()
        }
                findViewById<LinearLayout>(R.id.navPlayer).setOnClickListener {
            startActivity(
                Intent(this, PlayerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }
findViewById<LinearLayout>(R.id.navSettings).setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }
        // Past downloads survive restarts: reload them as rows
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
            // Finished history rows get their dismiss/retry buttons
            // up front, not only after a live update arrives.
            if (entry.status == "Failed") {
                rowRetry[entry.url]?.visibility = android.view.View.VISIBLE
                rowClear[entry.url]?.visibility = android.view.View.VISIBLE
            } else {
                rowClear[entry.url]?.visibility = android.view.View.VISIBLE
                // Done rows remember their file (v0.7.5 history field),
                // so Open works after a restart too.
                if (entry.file.isNotBlank()) {
                    rowOutput[entry.url] = entry.file
                    rowOpen[entry.url]?.visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DownloadProgressBus.subscribe(progressListener)
        // Rows for downloads that started while this screen was
        // closed: replay the bus's latest state per URL, so a
        // "Preparing…" download shows a row at once.
        DownloadProgressBus.snapshot().forEach { updateRow(it) }
    }

    override fun onPause() {
        DownloadProgressBus.unsubscribe(progressListener)
        super.onPause()
    }

    private fun navigateHome() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }

    /** Add a queue row for [url], or refresh it if one already exists. */
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
                    this@DownloadsActivity, R.color.graball_subtext
                )
            )
            visibility = android.view.View.GONE
        }
        if (!detailText.isNullOrBlank()) {
            detail.text = detailText
            detail.visibility = android.view.View.VISIBLE
        }
        // One horizontal button row: Cancel/Pause used to stack
        // full-width with no gap between them, which looked broken.
        // Buttons share the row equally, with a gap on each side.
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun buttonParams() = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        }
        val cancel = Button(this).apply {
            text = getString(R.string.queue_cancel)
            textSize = 12f
            layoutParams = buttonParams()
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
                DownloadService.cancel(this@DownloadsActivity, url)
            }
        }
        val pause = Button(this).apply {
            text = getString(R.string.queue_pause)
            textSize = 12f
            layoutParams = buttonParams()
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
                    DownloadService.resume(this@DownloadsActivity, url)
                } else {
                    DownloadService.pause(this@DownloadsActivity, url)
                }
            }
        }
        val copyLog = Button(this).apply {
            text = getString(R.string.queue_copy_log)
            textSize = 12f
            layoutParams = buttonParams()
            setBackgroundResource(R.drawable.btn_small)
            setTextColor(Color.WHITE)
            visibility = android.view.View.GONE
            setOnClickListener {
                val log = DownloadService.getLog(url)
                if (log.isNullOrBlank()) {
                    Toast.makeText(
                        this@DownloadsActivity, "No log to copy",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("GrabAll log", log)
                    )
                    Toast.makeText(
                        this@DownloadsActivity, "Log copied",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        // Retry restarts a failed download with the exact settings
        // it started with (same quality, audio mode, playlist size,
        // staging dir) — the service kept them per URL, so a retry
        // also resumes from the .part file instead of starting over.
        // If the app was restarted since, the settings are gone and
        // the service says to start again from the link.
        val retry = Button(this).apply {
            text = getString(R.string.queue_retry)
            textSize = 12f
            layoutParams = buttonParams()
            setBackgroundResource(R.drawable.btn_small)
            setTextColor(Color.WHITE)
            visibility = android.view.View.GONE
            setOnClickListener {
                DownloadService.resume(this@DownloadsActivity, url)
            }
        }
        // Clear dismisses a finished row for good: the row, its
        // history entry, and its mode label all go, so it never
        // comes back on the next launch.
        val clear = Button(this).apply {
            text = getString(R.string.queue_clear)
            textSize = 12f
            layoutParams = buttonParams()
            setBackgroundResource(R.drawable.btn_small)
            setTextColor(Color.WHITE)
            visibility = android.view.View.GONE
            setOnClickListener {
                queueContainer.removeView(rowViews[url])
                rowStatus.remove(url)
                rowDetail.remove(url)
                rowCancel.remove(url)
                rowPause.remove(url)
                rowCopyLog.remove(url)
                rowRetry.remove(url)
                rowClear.remove(url)
                rowOpen.remove(url)
                rowViews.remove(url)
                rowMode.remove(url)
                rowOutput.remove(url)
                cancelledUrls.remove(url)
                pausedRows.remove(url)
                DownloadProgressBus.modes.remove(url)
                DownloadHistory.remove(this@DownloadsActivity, url)
            }
        }
        buttonsRow.addView(cancel)
        buttonsRow.addView(pause)
        buttonsRow.addView(copyLog)
        buttonsRow.addView(retry)
        buttonsRow.addView(clear)
        // Open sits on its own full-width line under the buttons: it
        // only ever appears on a finished row, and the five-button
        // row is already tight enough without a sixth squeezed in.
        val open = Button(this).apply {
            text = getString(R.string.queue_open)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
            setBackgroundResource(R.drawable.btn_small)
            setTextColor(Color.WHITE)
            visibility = android.view.View.GONE
            setOnClickListener {
                rowOutput[url]?.let { openOutput(it) }
            }
        }
        row.addView(title)
        row.addView(status)
        row.addView(detail)
        row.addView(buttonsRow)
        row.addView(open)
        queueContainer.addView(row)
        rowStatus[url] = status
        rowDetail[url] = detail
        rowCancel[url] = cancel
        rowPause[url] = pause
        rowCopyLog[url] = copyLog
        rowRetry[url] = retry
        rowClear[url] = clear
        rowOpen[url] = open
        rowViews[url] = row
    }

    /** Open a finished download in the device's default app for its
     * type (video player, music player, …). [relPath] is relative to
     * Downloads/GrabAll, as recorded on the row. */
    private fun openOutput(relPath: String) {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "GrabAll/$relPath"
        )
        if (!file.isFile) {
            Toast.makeText(
                this, getString(R.string.queue_file_missing), Toast.LENGTH_SHORT
            ).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                this, "com.graball.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MediaStorePublisher.mimeFor(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this, getString(R.string.queue_open_failed), Toast.LENGTH_SHORT
            ).show()
        }
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
        val retryView = rowRetry[state.id]
        val clearView = rowClear[state.id]
        val openView = rowOpen[state.id]
        // A DOWNLOADING or PREPARING tick means a NEW download for
        // this URL: clear any cancelled flag left by an earlier run
        // of the same URL, or the row would stay stuck on
        // "Cancelled" forever.
        if (state.status == DownloadProgressBus.Status.DOWNLOADING ||
            state.status == DownloadProgressBus.Status.PREPARING
        ) {
            cancelledUrls.remove(state.id)
        }
        // Mode label: history rows carry their own; live rows read
        // the label whoever started the download recorded on the bus.
        val mode = rowMode[state.id] ?: DownloadProgressBus.modes[state.id]
        if (cancelledUrls.contains(state.id)) {
            statusView.text = getString(R.string.queue_cancelled)
            detailView?.visibility = android.view.View.GONE
            cancelView?.visibility = android.view.View.GONE
            pauseView?.visibility = android.view.View.GONE
            copyView?.visibility = android.view.View.GONE
            retryView?.visibility = android.view.View.GONE
            openView?.visibility = android.view.View.GONE
            clearView?.visibility = android.view.View.VISIBLE
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
            if (state.output != null) {
                rowOutput[state.id] = state.output
            }
            DownloadHistory.record(
                this, state.id, mode ?: "", "Done", null, state.output
            )
        } else if (state.status == DownloadProgressBus.Status.FAILED) {
            DownloadHistory.record(
                this, state.id, mode ?: "", "Failed",
                state.detail
            )
        }
        statusView.text = when (state.status) {
            DownloadProgressBus.Status.PREPARING -> {
                val modeSuffix = mode?.let { " · $it" } ?: ""
                getString(R.string.queue_preparing) + modeSuffix
            }
            DownloadProgressBus.Status.DOWNLOADING -> {
                val pct = state.percent?.toInt()
                val base = if (pct != null) getString(R.string.queue_downloading, pct)
                else getString(R.string.queue_downloading_unknown)
                val itemSuffix = state.item?.let { " · $it" } ?: ""
                val modeSuffix = mode?.let { " · $it" } ?: ""
                base + itemSuffix + modeSuffix
            }
            DownloadProgressBus.Status.COMPLETE -> {
                val modeSuffix = mode?.let { " · $it" } ?: ""
                getString(R.string.queue_done) + modeSuffix
            }
            DownloadProgressBus.Status.FAILED -> getString(R.string.queue_failed)
            DownloadProgressBus.Status.PAUSED -> getString(R.string.queue_paused)
        }
        cancelView?.visibility = if (
            state.status == DownloadProgressBus.Status.DOWNLOADING ||
            state.status == DownloadProgressBus.Status.PAUSED ||
            state.status == DownloadProgressBus.Status.PREPARING
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
        retryView?.visibility = if (
            state.status == DownloadProgressBus.Status.FAILED
        ) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        clearView?.visibility = if (
            state.status == DownloadProgressBus.Status.COMPLETE ||
            state.status == DownloadProgressBus.Status.FAILED
        ) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        openView?.visibility = if (
            state.status == DownloadProgressBus.Status.COMPLETE &&
            rowOutput.containsKey(state.id)
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
