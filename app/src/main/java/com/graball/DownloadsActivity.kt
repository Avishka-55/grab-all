package com.graball

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.graball.engine.DownloadHistory
import com.graball.engine.DownloadProgressBus
import com.graball.engine.DownloadService
import com.graball.engine.FastYouTubeDownloader
import com.graball.engine.MediaStorePublisher
import com.graball.youtube.ThumbnailLoader
import java.io.File
import java.util.Locale

/**
 * Compact, friendly Downloads screen:
 * - Shows each download's real video/playlist/file title (not raw URL)
 * - Slim progress bar + compact chip action strip so 5-6 items fit on screen
 * - Open, Delete from storage (with confirmation), Clear, Pause/Resume, Retry, Log
 */
class DownloadsActivity : AppCompatActivity() {

    private enum class Filter { ALL, ACTIVE, VIDEO, AUDIO }

    private lateinit var queueContainer: LinearLayout
    private lateinit var countSubView: TextView
    private lateinit var btnClearCompleted: TextView
    private lateinit var emptyBox: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptySub: TextView
    private lateinit var filterChips: Map<Filter, TextView>
    private var currentFilter = Filter.ALL

    private val rowTitle = mutableMapOf<String, TextView>()
    private val rowStatus = mutableMapOf<String, TextView>()
    private val rowDetail = mutableMapOf<String, TextView>()
    private val rowProgress = mutableMapOf<String, ProgressBar>()
    private val rowCancel = mutableMapOf<String, TextView>()
    private val rowPause = mutableMapOf<String, TextView>()
    private val rowCopyLog = mutableMapOf<String, TextView>()
    private val rowRetry = mutableMapOf<String, TextView>()
    private val rowClear = mutableMapOf<String, TextView>()
    private val rowOpen = mutableMapOf<String, TextView>()
    private val rowDelete = mutableMapOf<String, TextView>()
    private val rowViews = mutableMapOf<String, LinearLayout>()
    private val rowIsActive = mutableMapOf<String, Boolean>()
    private val rowIsAudio = mutableMapOf<String, Boolean>()
    private val rowFileSize = mutableMapOf<String, String>()

    /** Latest published file per URL (content:// URI or path relative to Downloads/GrabAll). */
    private val rowOutput = mutableMapOf<String, String>()
    private val cancelledUrls = mutableSetOf<String>()
    private val pausedRows = mutableSetOf<String>()
    private val rowMode = mutableMapOf<String, String>()
    private val rowDisplayTitle = mutableMapOf<String, String>()

    private val progressListener: (DownloadProgressBus.State) -> Unit = { state ->
        updateRow(state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        ThumbnailLoader.init(this)

        queueContainer = findViewById(R.id.queueContainer)
        countSubView = findViewById(R.id.downloadsCountSub)
        btnClearCompleted = findViewById(R.id.btnClearCompleted)
        emptyBox = findViewById(R.id.downloadsEmptyBox)
        emptyTitle = findViewById(R.id.downloadsEmptyTitle)
        emptySub = findViewById(R.id.downloadsEmptySub)

        filterChips = mapOf(
            Filter.ALL to findViewById(R.id.filterAll),
            Filter.ACTIVE to findViewById(R.id.filterActive),
            Filter.VIDEO to findViewById(R.id.filterVideo),
            Filter.AUDIO to findViewById(R.id.filterAudio)
        )
        filterChips.forEach { (filter, chip) ->
            chip.setOnClickListener {
                if (currentFilter != filter) {
                    currentFilter = filter
                    updateFilterChipStyles()
                    refreshFilterAndChrome()
                }
            }
        }
        updateFilterChipStyles()

        btnClearCompleted.setOnClickListener { confirmClearCompleted() }

        findViewById<LinearLayout>(R.id.navGrab).setOnClickListener {
            NavHelper.switchTab(this, MainActivity::class.java)
        }
        findViewById<LinearLayout>(R.id.navPlayer).setOnClickListener {
            NavHelper.switchTab(this, PlayerActivity::class.java)
        }
        findViewById<LinearLayout>(R.id.navSettings).setOnClickListener {
            NavHelper.switchTab(this, SettingsActivity::class.java)
        }

        // Load history newest-first so recent downloads appear at the top.
        DownloadHistory.load(this).asReversed().forEach { entry ->
            rowMode[entry.url] = entry.mode
            val friendlyTitle = resolveFriendlyTitle(entry.url, entry.title, entry.file)
            rowDisplayTitle[entry.url] = friendlyTitle
            val isAudio = entry.mode.contains("Audio", ignoreCase = true) ||
                (entry.file.isNotBlank() && isAudioOutput(entry.file))
            rowIsAudio[entry.url] = isAudio
            rowIsActive[entry.url] = false
            val sizeStr = if (entry.status == "Done" && entry.file.isNotBlank()) {
                resolveOutputSizeLabel(entry.file).also { if (it.isNotBlank()) rowFileSize[entry.url] = it }
            } else {
                ""
            }
            val modeSuffix = if (entry.mode.isNotBlank()) " · " + entry.mode else ""
            val sizeSuffix = if (sizeStr.isNotBlank()) " · $sizeStr" else ""
            val statusText = (if (entry.status == "Done") {
                getString(R.string.queue_done)
            } else {
                getString(R.string.queue_failed)
            }) + modeSuffix + sizeSuffix
            addRow(
                url = entry.url,
                displayTitle = friendlyTitle,
                statusText = statusText,
                detailText = entry.detail,
                showCancel = false,
                addToTop = false
            )
            rowStatus[entry.url]?.setTextColor(
                if (entry.status == "Done") Color.parseColor("#6EE7B7")
                else Color.parseColor("#FF7A7A")
            )
            if (entry.status == "Failed") {
                rowRetry[entry.url]?.visibility = View.VISIBLE
                rowClear[entry.url]?.visibility = View.VISIBLE
            } else {
                rowClear[entry.url]?.visibility = View.VISIBLE
                if (entry.file.isNotBlank()) {
                    rowOutput[entry.url] = entry.file
                    rowOpen[entry.url]?.text = if (isAudio) "▶ Play" else "▶ Open"
                    rowOpen[entry.url]?.visibility = View.VISIBLE
                    rowDelete[entry.url]?.visibility = View.VISIBLE
                }
            }
        }
        refreshFilterAndChrome()
    }

    override fun onResume() {
        super.onResume()
        DownloadProgressBus.subscribe(progressListener)
        DownloadProgressBus.snapshot().forEach { updateRow(it) }
        refreshFilterAndChrome()
    }

    override fun onPause() {
        DownloadProgressBus.unsubscribe(progressListener)
        super.onPause()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Resolve a clean human-friendly title instead of showing raw URLs:
     * 1. Explicit video/playlist title from YouTube / FastYouTubeDownloader
     * 2. Filename or playlist folder name from the published output file / content:// URI
     * 3. Compact cleaned host + path fallback while still preparing
     */
    private fun resolveFriendlyTitle(url: String, explicitTitle: String?, output: String?): String {
        val explicit = explicitTitle?.trim()?.takeIf { !DownloadProgressBus.isPlaceholderTitle(it) }
        if (explicit != null) return explicit

        val busTitle = DownloadProgressBus.titles[url]?.trim()
            ?.takeIf { !DownloadProgressBus.isPlaceholderTitle(it) }
        if (busTitle != null) return busTitle

        if (!output.isNullOrBlank()) {
            if (output.startsWith("content://")) {
                queryContentDisplayName(Uri.parse(output))?.let { name ->
                    val stripped = name.substringBeforeLast('.').ifBlank { name }
                    if (!DownloadProgressBus.isPlaceholderTitle(stripped)) return stripped
                }
            } else {
                val parts = output.trim('/').split('/')
                if (parts.size >= 2 && !DownloadProgressBus.isPlaceholderTitle(parts[0])) {
                    // Playlist subfolder name
                    return parts[0]
                }
                val base = parts.lastOrNull().orEmpty().substringBeforeLast('.')
                if (!DownloadProgressBus.isPlaceholderTitle(base)) return base
            }
        }

        // Compact fallback while initial metadata is still loading
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.removePrefix("www.")?.removePrefix("m.") ?: ""
            val listId = uri.getQueryParameter("list")
            val vidId = uri.getQueryParameter("v")
            when {
                host.contains("youtube") && !listId.isNullOrBlank() -> "YouTube Playlist"
                host.contains("youtube") && !vidId.isNullOrBlank() -> "YouTube Video ($vidId)"
                host.contains("youtu.be") -> "YouTube Video"
                host.isNotBlank() -> "$host download"
                else -> url.take(48)
            }
        } catch (_: Exception) {
            url.take(48)
        }
    }

    private fun queryContentDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Create a compact, sleek chip button (30dp tall, crisp text, zero bloat). */
    private fun makeChipButton(
        label: String,
        primary: Boolean = false,
        danger: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTypeface(null, if (primary) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(
                when {
                    danger -> Color.parseColor("#FF7A7A")
                    primary -> Color.WHITE
                    else -> ContextCompat.getColor(this@DownloadsActivity, R.color.graball_text)
                }
            )
            setBackgroundResource(if (primary) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
            setPadding(dp(11), 0, dp(11), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(30)
            ).apply {
                marginEnd = dp(6)
            }
        }
    }

    /** Add a compact download card for [url], or refresh it if one already exists. */
    private fun addRow(
        url: String,
        displayTitle: String,
        statusText: String,
        detailText: String? = null,
        showCancel: Boolean = true,
        addToTop: Boolean = true
    ) {
        if (rowStatus.containsKey(url)) {
            rowTitle[url]?.text = displayTitle
            rowStatus[url]?.text = statusText
            rowDetail[url]?.visibility = View.GONE
            rowCancel[url]?.visibility = if (showCancel) View.VISIBLE else View.GONE
            return
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.card_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(9)
            }
        }

        // Top row: Rounded thumbnail / type badge + Title & Status column
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val thumbBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(48)).apply {
                marginEnd = dp(11)
            }
            setBackgroundColor(ContextCompat.getColor(this@DownloadsActivity, R.color.graball_nav))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                }
            }
            clipToOutline = true
        }
        val modeHint = rowMode[url] ?: DownloadProgressBus.modes[url].orEmpty()
        val isAudioHint = rowIsAudio[url] ?: modeHint.contains("Audio", ignoreCase = true)
        val isPlaylistHint = FastYouTubeDownloader.extractPlaylistId(url) != null ||
            modeHint.contains("First ", ignoreCase = true) ||
            modeHint.contains("All videos", ignoreCase = true)
        val placeholderIcon = TextView(this).apply {
            text = when {
                isAudioHint -> "🎵"
                isPlaylistHint -> "📋"
                else -> "🎬"
            }
            textSize = 20f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        thumbBox.addView(placeholderIcon)

        val ytVidId = FastYouTubeDownloader.extractVideoId(url)
        val thumbUrl = if (!ytVidId.isNullOrBlank()) {
            "https://i.ytimg.com/vi/$ytVidId/mqdefault.jpg"
        } else {
            DownloadProgressBus.thumbnails[url]
        }
        if (!thumbUrl.isNullOrBlank()) {
            val thumbIv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            thumbBox.addView(thumbIv)
            ThumbnailLoader.load(thumbUrl, thumbIv, targetWidthPx = 240)
        }
        headerRow.addView(thumbBox)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = displayTitle
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 2
        }

        val status = TextView(this).apply {
            text = statusText
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(this@DownloadsActivity, R.color.graball_orange))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
            }
        }
        textCol.addView(title)
        textCol.addView(status)
        headerRow.addView(textCol)

        val progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            isIndeterminate = showCancel
            visibility = if (showCancel) View.VISIBLE else View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                progressTintList = ContextCompat.getColorStateList(
                    this@DownloadsActivity, R.color.graball_orange
                )
                indeterminateTintList = ContextCompat.getColorStateList(
                    this@DownloadsActivity, R.color.graball_orange
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(4)
            ).apply {
                topMargin = dp(6)
            }
        }

        val detail = TextView(this).apply {
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(this@DownloadsActivity, R.color.graball_subtext))
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 2
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
            }
        }
        if (!detailText.isNullOrBlank()) {
            detail.text = detailText
            detail.visibility = View.VISIBLE
        }

        // Single sleek compact horizontal action chip row
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        val open = makeChipButton("▶ Open", primary = true).apply {
            visibility = View.GONE
            setOnClickListener {
                rowOutput[url]?.let { openOutput(url, it) }
            }
        }

        val pause = makeChipButton("⏸ Pause").apply {
            visibility = if (showCancel) View.VISIBLE else View.GONE
            setOnClickListener {
                if (pausedRows.contains(url)) {
                    DownloadService.resume(this@DownloadsActivity, url)
                } else {
                    DownloadService.pause(this@DownloadsActivity, url)
                }
            }
        }

        val cancel = makeChipButton("✕ Cancel", danger = true).apply {
            visibility = if (showCancel) View.VISIBLE else View.GONE
            setOnClickListener {
                cancelledUrls.add(url)
                status.text = getString(R.string.queue_cancelled)
                detail.visibility = View.GONE
                progressBar.visibility = View.GONE
                visibility = View.GONE
                rowPause[url]?.visibility = View.GONE
                rowCopyLog[url]?.visibility = View.GONE
                rowClear[url]?.visibility = View.VISIBLE
                rowIsActive[url] = false
                DownloadService.cancel(this@DownloadsActivity, url)
                refreshFilterAndChrome()
            }
        }

        val retry = makeChipButton("↻ Retry", primary = true).apply {
            visibility = View.GONE
            setOnClickListener {
                DownloadService.resume(this@DownloadsActivity, url)
            }
        }

        val copyLog = makeChipButton("📋 Log").apply {
            visibility = View.GONE
            setOnClickListener {
                val log = DownloadService.getLog(url)
                if (log.isNullOrBlank()) {
                    Toast.makeText(
                        this@DownloadsActivity, "No log to copy", Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("GrabAll log", log))
                    Toast.makeText(
                        this@DownloadsActivity, "Log copied", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        val deleteFile = makeChipButton("🗑 Delete", danger = true).apply {
            visibility = View.GONE
            setOnClickListener {
                confirmDeleteFile(url)
            }
        }

        val clear = makeChipButton("✕ Clear").apply {
            visibility = View.GONE
            setOnClickListener {
                removeRowAndHistory(url)
            }
        }

        actionsRow.addView(open)
        actionsRow.addView(pause)
        actionsRow.addView(cancel)
        actionsRow.addView(retry)
        actionsRow.addView(copyLog)
        actionsRow.addView(deleteFile)
        actionsRow.addView(clear)

        row.addView(headerRow)
        row.addView(progressBar)
        row.addView(detail)
        row.addView(actionsRow)

        if (addToTop) {
            queueContainer.addView(row, 0)
        } else {
            queueContainer.addView(row)
        }

        rowTitle[url] = title
        rowStatus[url] = status
        rowDetail[url] = detail
        rowProgress[url] = progressBar
        rowCancel[url] = cancel
        rowPause[url] = pause
        rowCopyLog[url] = copyLog
        rowRetry[url] = retry
        rowClear[url] = clear
        rowOpen[url] = open
        rowDelete[url] = deleteFile
        rowViews[url] = row
    }

    private fun removeRowAndHistory(url: String) {
        rowViews[url]?.let { queueContainer.removeView(it) }
        rowTitle.remove(url)
        rowStatus.remove(url)
        rowDetail.remove(url)
        rowProgress.remove(url)
        rowCancel.remove(url)
        rowPause.remove(url)
        rowCopyLog.remove(url)
        rowRetry.remove(url)
        rowClear.remove(url)
        rowOpen.remove(url)
        rowDelete.remove(url)
        rowViews.remove(url)
        rowMode.remove(url)
        rowOutput.remove(url)
        rowDisplayTitle.remove(url)
        rowIsActive.remove(url)
        rowIsAudio.remove(url)
        rowFileSize.remove(url)
        cancelledUrls.remove(url)
        pausedRows.remove(url)
        DownloadProgressBus.remove(url)
        DownloadHistory.remove(this, url)
        refreshFilterAndChrome()
    }

    private fun updateFilterChipStyles() {
        val colorText = ContextCompat.getColor(this, R.color.graball_text)
        filterChips.forEach { (filter, chip) ->
            val selected = filter == currentFilter
            chip.setBackgroundResource(if (selected) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
            chip.setTextColor(if (selected) Color.WHITE else colorText)
            chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun refreshFilterAndChrome() {
        if (!::queueContainer.isInitialized) return
        val totalCount = rowViews.size
        val activeCount = rowViews.keys.count { rowIsActive[it] == true }
        val finishedCount = (totalCount - activeCount).coerceAtLeast(0)

        var visibleCount = 0
        rowViews.forEach { (url, view) ->
            val isActive = rowIsActive[url] == true
            val isAudio = rowIsAudio[url] == true
            val show = when (currentFilter) {
                Filter.ALL -> true
                Filter.ACTIVE -> isActive
                Filter.AUDIO -> isAudio
                Filter.VIDEO -> !isAudio
            }
            view.visibility = if (show) View.VISIBLE else View.GONE
            if (show) visibleCount++
        }

        countSubView.text = when {
            activeCount > 0 && finishedCount > 0 -> "$activeCount active · $finishedCount saved"
            activeCount > 0 -> "$activeCount active download${if (activeCount == 1) "" else "s"}"
            totalCount > 0 -> "$totalCount item${if (totalCount == 1) "" else "s"} · Downloads/GrabAll"
            else -> "Saved in Downloads/GrabAll"
        }

        btnClearCompleted.visibility = if (finishedCount > 0) View.VISIBLE else View.GONE

        if (visibleCount == 0) {
            emptyBox.visibility = View.VISIBLE
            when (currentFilter) {
                Filter.ALL -> {
                    emptyTitle.text = "No downloads yet"
                    emptySub.text = "Paste any video link on Grab or tap Download on a YouTube video to save it here."
                }
                Filter.ACTIVE -> {
                    emptyTitle.text = "No active downloads"
                    emptySub.text = "In-progress and paused downloads will appear here."
                }
                Filter.VIDEO -> {
                    emptyTitle.text = "No video downloads"
                    emptySub.text = "Saved MP4 videos will appear here."
                }
                Filter.AUDIO -> {
                    emptyTitle.text = "No audio downloads"
                    emptySub.text = "Saved M4A and MP3 tracks will appear here."
                }
            }
        } else {
            emptyBox.visibility = View.GONE
        }
    }

    private fun confirmClearCompleted() {
        val finishedUrls = rowViews.keys.filter { rowIsActive[it] != true }
        if (finishedUrls.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Clear finished items?")
            .setMessage(
                "Remove ${finishedUrls.size} completed/failed item${if (finishedUrls.size == 1) "" else "s"} from this list?\n" +
                    "Your downloaded files stay safely in storage."
            )
            .setPositiveButton("Clear") { _, _ ->
                finishedUrls.forEach { removeRowAndHistory(it) }
                Toast.makeText(this, "Cleared ${finishedUrls.size} items", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resolveOutputSizeLabel(output: String?): String {
        if (output.isNullOrBlank()) return ""
        val bytes = try {
            if (output.startsWith("content://")) {
                var sz = -1L
                contentResolver.query(
                    Uri.parse(output),
                    arrayOf(OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) {
                        sz = cursor.getLong(0)
                    }
                }
                sz
            } else {
                val f = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "GrabAll/$output"
                )
                if (f.isFile) f.length() else -1L
            }
        } catch (_: Exception) {
            -1L
        }
        if (bytes <= 0L) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024.0 -> String.format(Locale.US, "%.2f GB", mb / 1024.0)
            mb >= 100.0 -> String.format(Locale.US, "%.0f MB", mb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        }
    }

    private fun confirmDeleteFile(url: String) {
        val output = rowOutput[url] ?: return
        val label = rowDisplayTitle[url] ?: "this file"
        AlertDialog.Builder(this)
            .setTitle("Delete from storage?")
            .setMessage("Permanently delete \"$label\" from your device?")
            .setPositiveButton("Delete") { _, _ ->
                val deleted = deleteOutputFromStorage(output)
                removeRowAndHistory(url)
                Toast.makeText(
                    this,
                    if (deleted) "Deleted from storage" else "Removed from list (file already gone)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteOutputFromStorage(output: String): Boolean {
        if (output.startsWith("content://")) {
            return try {
                val rows = contentResolver.delete(Uri.parse(output), null, null)
                rows > 0
            } catch (_: Exception) {
                false
            }
        }
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "GrabAll/$output"
        )
        return try {
            file.exists() && file.delete()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Open a finished download smartly:
     * - Audio playlist -> opens straight into that folder in GrabAll Music Player
     * - Non-audio playlist -> opens the folder in the device's file manager
     * - Single audio file -> opens directly in GrabAll Music Player
     * - Single video/other file -> opens via system ACTION_VIEW chooser
     */
    private fun openOutput(url: String, output: String) {
        val mode = rowMode[url] ?: DownloadProgressBus.modes[url].orEmpty()
        val isAudio = mode.contains("Audio", ignoreCase = true) || isAudioOutput(output)
        val isPlaylist = mode.contains("First ", ignoreCase = true) ||
            mode.contains("All videos", ignoreCase = true) ||
            mode.contains("selected", ignoreCase = true) ||
            FastYouTubeDownloader.extractPlaylistId(url) != null ||
            (!output.startsWith("content://") && output.contains('/'))

        if (isPlaylist) {
            val relSubfolder = resolvePlaylistRelativeFolder(url, isAudio, output)
            val grabAllRoot = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "GrabAll"
            )
            val folderDir = if (!relSubfolder.isNullOrBlank()) {
                File(grabAllRoot, relSubfolder)
            } else {
                grabAllRoot
            }
            val leafFolderName = relSubfolder?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            if (isAudio) {
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(PlayerActivity.EXTRA_OPEN_FOLDER_PATH, folderDir.absolutePath)
                    if (!leafFolderName.isNullOrBlank()) {
                        putExtra(PlayerActivity.EXTRA_OPEN_FOLDER_NAME, leafFolderName)
                    }
                }
                startActivity(intent)
                @Suppress("DEPRECATION")
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                return
            } else {
                if (openFolderInFileManager(relSubfolder, folderDir)) {
                    return
                }
                // Fall back to opening the latest video file if no file manager handled folder URI
                Toast.makeText(
                    this,
                    "Saved in Downloads/GrabAll/${relSubfolder ?: ""}".trimEnd('/'),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Single audio file -> open straight inside GrabAll Music Player
        if (isAudio) {
            if (openSingleAudioInGrabAll(output)) return
        }

        openFileWithSystem(output)
    }

    private fun isAudioOutput(output: String): Boolean {
        if (output.startsWith("content://")) {
            val mime = try { contentResolver.getType(Uri.parse(output)) } catch (_: Exception) { null }
            if (mime != null && (mime.startsWith("audio/", ignoreCase = true) ||
                    mime.equals("application/ogg", ignoreCase = true) ||
                    mime.equals("application/flac", ignoreCase = true))
            ) return true
            val name = queryContentDisplayName(Uri.parse(output)) ?: return false
            return AudioFormats.isSupportedOpenAudioFile(name)
        }
        return AudioFormats.isSupportedOpenAudioFile(output)
    }

    /** Returns relative path under Downloads/GrabAll, e.g. "YouTube/My Playlist". */
    private fun resolvePlaylistRelativeFolder(url: String, isAudio: Boolean, output: String): String? {
        if (output.startsWith("content://") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                contentResolver.query(
                    Uri.parse(output),
                    arrayOf(MediaStore.Downloads.RELATIVE_PATH),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val rel = cursor.getString(0).orEmpty().trim('/')
                        // e.g. "Download/GrabAll/YouTube/My Playlist"
                        val idx = rel.indexOf("GrabAll/", ignoreCase = true)
                        if (idx >= 0) {
                            val sub = rel.substring(idx + "GrabAll/".length).trim('/')
                            if (sub.isNotBlank()) return sub
                        }
                    }
                }
            } catch (_: Exception) {
            }
        } else if (!output.startsWith("content://") && output.contains('/')) {
            val sub = output.substringBeforeLast('/').trim('/')
            if (sub.isNotBlank()) return sub
        }
        val title = rowDisplayTitle[url]?.takeIf {
            it.isNotBlank() && !it.startsWith("YouTube Playlist")
        }
        return if (title != null) {
            MediaStorePublisher.targetSubfolder(url, isAudio, title)
        } else {
            MediaStorePublisher.platformFolderForUrl(url)
        }
    }

    private fun openFolderInFileManager(folderName: String?, folderDir: File): Boolean {
        val relSub = if (!folderName.isNullOrBlank()) "Download/GrabAll/$folderName" else "Download/GrabAll"
        // 1) Official Android DocumentsUI / Files directory URI (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val docUri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:$relSub"
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
                return true
            } catch (_: Exception) {
            }
        }
        // 2) Common third-party / OEM file managers (resource/folder)
        try {
            val uri = Uri.fromFile(folderDir)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
            }
            startActivity(intent)
            return true
        } catch (_: Exception) {
        }
        // 3) System Downloads app
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            Toast.makeText(
                this,
                "Folder: Downloads/GrabAll/${folderName ?: ""}".trimEnd('/'),
                Toast.LENGTH_LONG
            ).show()
            return true
        } catch (_: Exception) {
        }
        return false
    }

    private fun openSingleAudioInGrabAll(output: String): Boolean {
        return try {
            val uri: Uri
            val mime: String
            if (output.startsWith("content://")) {
                uri = Uri.parse(output)
                mime = contentResolver.getType(uri) ?: "audio/*"
            } else {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "GrabAll/$output"
                )
                if (!file.isFile) return false
                uri = FileProvider.getUriForFile(this, "com.graball.fileprovider", file)
                mime = MediaStorePublisher.mimeFor(file.name)
            }
            val intent = Intent(this, PlayerActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun openFileWithSystem(output: String) {
        if (output.startsWith("content://")) {
            try {
                val uri = Uri.parse(output)
                val mime = contentResolver.getType(uri) ?: "*/*"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(
                    this, getString(R.string.queue_open_failed), Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "GrabAll/$output"
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
        val resolvedTitle = resolveFriendlyTitle(state.id, state.title, state.output ?: rowOutput[state.id])
        rowDisplayTitle[state.id] = resolvedTitle

        val statusView = rowStatus[state.id] ?: run {
            addRow(state.id, resolvedTitle, "", addToTop = true)
            rowStatus[state.id]!!
        }
        rowTitle[state.id]?.text = resolvedTitle

        val detailView = rowDetail[state.id]
        val progressView = rowProgress[state.id]
        val cancelView = rowCancel[state.id]
        val pauseView = rowPause[state.id]
        val copyView = rowCopyLog[state.id]
        val retryView = rowRetry[state.id]
        val clearView = rowClear[state.id]
        val openView = rowOpen[state.id]
        val deleteView = rowDelete[state.id]

        if (state.status == DownloadProgressBus.Status.DOWNLOADING ||
            state.status == DownloadProgressBus.Status.PREPARING
        ) {
            cancelledUrls.remove(state.id)
        }

        val mode = rowMode[state.id] ?: DownloadProgressBus.modes[state.id]
        if (!mode.isNullOrBlank()) {
            rowMode[state.id] = mode
        }
        val isAudioRow = (mode?.contains("Audio", ignoreCase = true) == true) ||
            (state.output?.let { isAudioOutput(it) } == true) ||
            (rowIsAudio[state.id] == true)
        rowIsAudio[state.id] = isAudioRow

        if (cancelledUrls.contains(state.id)) {
            rowIsActive[state.id] = false
            statusView.text = getString(R.string.queue_cancelled)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.graball_subtext))
            detailView?.visibility = View.GONE
            progressView?.visibility = View.GONE
            cancelView?.visibility = View.GONE
            pauseView?.visibility = View.GONE
            copyView?.visibility = View.GONE
            retryView?.visibility = View.GONE
            openView?.visibility = View.GONE
            deleteView?.visibility = View.GONE
            clearView?.visibility = View.VISIBLE
            refreshFilterAndChrome()
            return
        }

        if (state.status == DownloadProgressBus.Status.PAUSED) {
            pausedRows.add(state.id)
        } else {
            pausedRows.remove(state.id)
        }

        if (state.status == DownloadProgressBus.Status.COMPLETE) {
            if (state.output != null) {
                rowOutput[state.id] = state.output
                val sz = resolveOutputSizeLabel(state.output)
                if (sz.isNotBlank()) rowFileSize[state.id] = sz
            }
            val finalTitle = resolveFriendlyTitle(state.id, state.title, rowOutput[state.id])
            rowDisplayTitle[state.id] = finalTitle
            rowTitle[state.id]?.text = finalTitle
            DownloadHistory.record(
                this, state.id, mode ?: "", "Done", null, state.output, finalTitle
            )
        } else if (state.status == DownloadProgressBus.Status.FAILED) {
            DownloadHistory.record(
                this, state.id, mode ?: "", "Failed", state.detail, null, resolvedTitle
            )
        }

        val modeSuffix = mode?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        val sizeSuffix = rowFileSize[state.id]?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        statusView.text = when (state.status) {
            DownloadProgressBus.Status.PREPARING ->
                getString(R.string.queue_preparing) + modeSuffix
            DownloadProgressBus.Status.DOWNLOADING -> {
                val pct = state.percent?.toInt()
                val base = if (pct != null) getString(R.string.queue_downloading, pct)
                else getString(R.string.queue_downloading_unknown)
                val itemSuffix = state.item?.let { " · $it" } ?: ""
                base + itemSuffix + modeSuffix
            }
            DownloadProgressBus.Status.COMPLETE ->
                getString(R.string.queue_done) + modeSuffix + sizeSuffix
            DownloadProgressBus.Status.FAILED ->
                getString(R.string.queue_failed) + modeSuffix
            DownloadProgressBus.Status.PAUSED ->
                getString(R.string.queue_paused) + modeSuffix
        }
        statusView.setTextColor(
            when (state.status) {
                DownloadProgressBus.Status.COMPLETE -> Color.parseColor("#6EE7B7")
                DownloadProgressBus.Status.FAILED -> Color.parseColor("#FF7A7A")
                DownloadProgressBus.Status.PAUSED -> ContextCompat.getColor(this, R.color.graball_subtext)
                else -> ContextCompat.getColor(this, R.color.graball_orange)
            }
        )

        // Slim progress bar visibility & level
        when (state.status) {
            DownloadProgressBus.Status.PREPARING -> {
                progressView?.visibility = View.VISIBLE
                progressView?.isIndeterminate = true
            }
            DownloadProgressBus.Status.DOWNLOADING -> {
                progressView?.visibility = View.VISIBLE
                val pct = state.percent?.toInt()
                if (pct != null) {
                    progressView?.isIndeterminate = false
                    progressView?.progress = pct.coerceIn(0, 100)
                } else {
                    progressView?.isIndeterminate = true
                }
            }
            DownloadProgressBus.Status.PAUSED -> {
                progressView?.visibility = View.VISIBLE
                progressView?.isIndeterminate = false
            }
            else -> {
                progressView?.visibility = View.GONE
            }
        }

        val isActive = state.status == DownloadProgressBus.Status.DOWNLOADING ||
            state.status == DownloadProgressBus.Status.PAUSED ||
            state.status == DownloadProgressBus.Status.PREPARING
        rowIsActive[state.id] = isActive
        cancelView?.visibility = if (isActive) View.VISIBLE else View.GONE
        pauseView?.visibility = if (
            state.status == DownloadProgressBus.Status.DOWNLOADING ||
            state.status == DownloadProgressBus.Status.PAUSED
        ) View.VISIBLE else View.GONE
        pauseView?.text = if (state.status == DownloadProgressBus.Status.PAUSED) {
            "▶ Resume"
        } else {
            "⏸ Pause"
        }

        copyView?.visibility = if (
            state.status == DownloadProgressBus.Status.FAILED ||
            state.status == DownloadProgressBus.Status.PAUSED
        ) View.VISIBLE else View.GONE
        retryView?.visibility = if (state.status == DownloadProgressBus.Status.FAILED) {
            View.VISIBLE
        } else {
            View.GONE
        }
        clearView?.visibility = if (
            state.status == DownloadProgressBus.Status.COMPLETE ||
            state.status == DownloadProgressBus.Status.FAILED
        ) View.VISIBLE else View.GONE

        val hasOutput = state.status == DownloadProgressBus.Status.COMPLETE &&
            rowOutput.containsKey(state.id)
        openView?.text = if (isAudioRow) "▶ Play" else "▶ Open"
        openView?.visibility = if (hasOutput) View.VISIBLE else View.GONE
        deleteView?.visibility = if (hasOutput) View.VISIBLE else View.GONE

        if ((state.status == DownloadProgressBus.Status.FAILED ||
            state.status == DownloadProgressBus.Status.PAUSED ||
            state.status == DownloadProgressBus.Status.PREPARING ||
            state.status == DownloadProgressBus.Status.DOWNLOADING) &&
            !state.detail.isNullOrBlank() && detailView != null
        ) {
            detailView.text = state.detail
            detailView.visibility = View.VISIBLE
        } else {
            detailView?.visibility = View.GONE
        }
        refreshFilterAndChrome()
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
}
