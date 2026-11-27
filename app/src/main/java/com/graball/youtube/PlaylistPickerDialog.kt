package com.graball.youtube

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.graball.R
import com.graball.engine.DownloadPrefs
import com.graball.engine.DownloadProgressBus
import com.graball.engine.DownloadService
import com.graball.engine.FastYouTubeDownloader
import com.graball.engine.RuntimePaths
import java.io.File

/**
 * Unified Playlist Download Bottom Sheet:
 * - Mode switch at top: "Download All (N)" (1-tap full playlist) vs "Select Videos"
 *   (searchable checklist to pick exact songs/videos)
 * - Clean Audio (M4A/MP3) vs Video quality pills
 * - Smart full-width orange CTA showing exact item count & format
 */
object PlaylistPickerDialog {

    fun show(context: Context, details: PlaylistDetails, initialSelectMode: Boolean = false) {
        if (details.items.isEmpty()) {
            Toast.makeText(context, "Playlist is empty", Toast.LENGTH_SHORT).show()
            return
        }
        showSheetUi(context, details, initialSelectMode)
    }

    fun showForUrl(context: Context, playlistUrl: String, initialSelectMode: Boolean = false) {
        val playlistId = FastYouTubeDownloader.extractPlaylistId(playlistUrl)
        if (playlistId == null) {
            Toast.makeText(context, "Couldn't read playlist ID from link", Toast.LENGTH_SHORT).show()
            return
        }
        val dp = context.resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(ProgressBar(context))
            addView(TextView(context).apply {
                text = "Loading playlist items…"
                setPadding((14 * dp).toInt(), 0, 0, 0)
                textSize = 14f
            })
        }
        val loadingDialog = AlertDialog.Builder(context)
            .setTitle("Playlist")
            .setView(box)
            .setCancelable(true)
            .create()
        loadingDialog.show()

        Thread {
            val details = try {
                YouTubeInnerTube.fetchPlaylist(playlistId, maxItems = 500)
            } catch (_: Exception) {
                null
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!loadingDialog.isShowing) return@post
                runCatching { loadingDialog.dismiss() }
                if (details == null || details.items.isEmpty()) {
                    Toast.makeText(
                        context,
                        "Couldn't load playlist items (private or unsupported)",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@post
                }
                showSheetUi(context, details, initialSelectMode)
            }
        }.start()
    }

    private fun showSheetUi(
        context: Context,
        details: PlaylistDetails,
        initialSelectMode: Boolean
    ) {
        val dp = context.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()

        val prefs = DownloadPrefs.load(context)
        val allIndexed = details.items.mapIndexed { idx, item -> idx to item }
        val selectedIds = LinkedHashSet<String>()
        var visibleList = allIndexed

        var selectMode = initialSelectMode
        var audioOnly = prefs.audioOnly
        var selectedQualityPos = prefs.qualityPos.coerceIn(0, 4) // 0=Best,1=1080p,2=720p,3=480p,4=360p
        var allCountMode = "all" // "all", "20", "30", "50"

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#161624"))
                cornerRadii = floatArrayOf(
                    20 * dp, 20 * dp, 20 * dp, 20 * dp,
                    0f, 0f, 0f, 0f
                )
            }
            setPadding(d(16), d(10), d(16), d(16))
        }

        // Top drag handle
        val handle = View(context).apply {
            background = GradientDrawable().apply {
                setColor(0x44FFFFFF)
                cornerRadius = 2 * dp
            }
            layoutParams = LinearLayout.LayoutParams(d(38), d(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = d(12)
            }
        }
        root.addView(handle)

        // Header row: Playlist Title + Close button
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = d(8) }
        }
        val titleView = TextView(context).apply {
            text = details.title
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitleView = TextView(context).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.graball_subtext))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(2) }
        }
        titleCol.addView(titleView)
        titleCol.addView(subtitleView)

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.graball_subtext))
            setBackgroundResource(R.drawable.yt_chip_bg)
            layoutParams = LinearLayout.LayoutParams(d(32), d(32))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(titleCol)
        headerRow.addView(closeBtn)
        root.addView(headerRow)

        // Mode Switch: [ Download All (N) ]  |  [ Select Videos ]
        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10101C"))
                cornerRadius = 12 * dp
            }
            setPadding(d(4), d(4), d(4), d(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(12) }
        }
        val tabDownloadAll = TextView(context).apply {
            text = "⬇ Download All (${details.items.size})"
            textSize = 12.5f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, d(36), 1f).apply { marginEnd = d(4) }
        }
        val tabSelectVideos = TextView(context).apply {
            text = "☑ Select Videos"
            textSize = 12.5f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, d(36), 1f)
        }
        modeRow.addView(tabDownloadAll)
        modeRow.addView(tabSelectVideos)
        root.addView(modeRow)

        // Format Toggle Row: [ 🎵 Audio (M4A/MP3) ]  |  [ 🎬 Video ]
        val formatRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(10) }
        }
        val chipAudio = TextView(context).apply {
            text = "🎵 Audio (${DownloadPrefs.audioFormatLabel(context)})"
            textSize = 12.5f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, d(36), 1f).apply { marginEnd = d(8) }
        }
        val chipVideo = TextView(context).apply {
            text = "🎬 Video (MP4)"
            textSize = 12.5f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, d(36), 1f)
        }
        formatRow.addView(chipAudio)
        formatRow.addView(chipVideo)
        root.addView(formatRow)

        // Video Quality Chips (visible when Video mode selected)
        val qualityLabels = listOf("Best", "1080p", "720p", "480p", "360p")
        val qualityRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(8) }
        }
        val qualityChips = qualityLabels.mapIndexed { idx, lbl ->
            TextView(context).apply {
                text = lbl
                textSize = 11.5f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, d(32), 1f).apply {
                    if (idx < qualityLabels.lastIndex) marginEnd = d(5)
                }
            }.also { qualityRow.addView(it) }
        }
        root.addView(qualityRow)

        // Optional Count Limit Row (shown in Download All mode when playlist has > 20 items)
        val countOptions = buildList {
            add("all" to "All (${details.items.size})")
            if (details.items.size > 20) add("20" to "First 20")
            if (details.items.size > 30) add("30" to "First 30")
            if (details.items.size > 50) add("50" to "First 50")
        }
        val countLimitRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(8) }
        }
        val countLimitChips = countOptions.mapIndexed { idx, (key, lbl) ->
            key to TextView(context).apply {
                text = lbl
                textSize = 11.5f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, d(32), 1f).apply {
                    if (idx < countOptions.lastIndex) marginEnd = d(5)
                }
            }.also { countLimitRow.addView(it) }
        }
        root.addView(countLimitRow)

        // Selective Picker Section (visible only when Select Videos mode is active)
        val selectSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(10) }
        }
        val filterInput = EditText(context).apply {
            hint = "Search ${details.items.size} items…"
            textSize = 13f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(ContextCompat.getColor(context, R.color.graball_tab_text))
            setBackgroundResource(R.drawable.input_bg)
            setPadding(d(12), d(8), d(12), d(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                d(38)
            )
        }
        val selectToolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(8) }
        }
        fun makeSmallChip(label: String): TextView = TextView(context).apply {
            text = label
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.yt_chip_bg)
            setPadding(d(11), 0, d(11), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                d(30)
            ).apply { marginEnd = d(6) }
        }
        val btnSelectAll = makeSmallChip("Select All")
        val btnClearAll = makeSmallChip("Clear")
        val selectedCounter = TextView(context).apply {
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(context, R.color.graball_orange))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        selectToolbar.addView(btnSelectAll)
        selectToolbar.addView(btnClearAll)
        selectToolbar.addView(selectedCounter)

        val maxListHeight = (context.resources.displayMetrics.heightPixels * 0.36f).toInt()
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxListHeight
            ).apply { topMargin = d(6) }
        }
        selectSection.addView(filterInput)
        selectSection.addView(selectToolbar)
        selectSection.addView(recycler)
        root.addView(selectSection)

        // Primary Download CTA Button
        val btnDownload = TextView(context).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.yt_chip_selected)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                d(44)
            ).apply { topMargin = d(14) }
        }
        root.addView(btnDownload)

        val textNormalColor = ContextCompat.getColor(context, R.color.graball_text)
        val textSubColor = ContextCompat.getColor(context, R.color.graball_subtext)

        fun styleChip(view: TextView, selected: Boolean) {
            view.setBackgroundResource(if (selected) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
            view.setTextColor(if (selected) Color.WHITE else textSubColor)
            view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }

        fun refreshUi() {
            // Mode tabs
            tabDownloadAll.setBackgroundResource(
                if (!selectMode) R.drawable.yt_chip_selected else android.R.color.transparent
            )
            tabDownloadAll.setTextColor(if (!selectMode) Color.WHITE else textSubColor)
            tabDownloadAll.setTypeface(null, if (!selectMode) Typeface.BOLD else Typeface.NORMAL)

            tabSelectVideos.setBackgroundResource(
                if (selectMode) R.drawable.yt_chip_selected else android.R.color.transparent
            )
            tabSelectVideos.setTextColor(if (selectMode) Color.WHITE else textSubColor)
            tabSelectVideos.setTypeface(null, if (selectMode) Typeface.BOLD else Typeface.NORMAL)

            // Format & quality chips
            styleChip(chipAudio, audioOnly)
            styleChip(chipVideo, !audioOnly)
            qualityRow.visibility = if (audioOnly) View.GONE else View.VISIBLE
            qualityChips.forEachIndexed { idx, chip ->
                styleChip(chip, idx == selectedQualityPos)
            }

            // All-mode vs Select-mode sections
            countLimitRow.visibility = if (!selectMode && countOptions.size > 1) View.VISIBLE else View.GONE
            countLimitChips.forEach { (key, chip) ->
                styleChip(chip, key == allCountMode)
            }
            selectSection.visibility = if (selectMode) View.VISIBLE else View.GONE

            val fmtLabel = if (audioOnly) {
                DownloadPrefs.audioFormatLabel(context)
            } else {
                qualityLabels[selectedQualityPos]
            }
            val metaParts = listOf(details.channel, "${details.items.size} items")
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            subtitleView.text = if (selectMode) {
                "$metaParts · Tap items to choose"
            } else {
                "$metaParts · Saved in Downloads/GrabAll/YouTube"
            }

            if (selectMode) {
                val n = selectedIds.size
                selectedCounter.text = "$n selected"
                btnDownload.text = if (n > 0) {
                    "⬇ Download $n Selected ($fmtLabel)"
                } else {
                    "Select videos above to download"
                }
                btnDownload.alpha = if (n > 0) 1.0f else 0.45f
                btnDownload.isEnabled = n > 0
            } else {
                val targetCount = when (allCountMode) {
                    "20" -> minOf(20, details.items.size)
                    "30" -> minOf(30, details.items.size)
                    "50" -> minOf(50, details.items.size)
                    else -> details.items.size
                }
                val noun = if (audioOnly) "Tracks" else "Videos"
                val prefix = if (allCountMode == "all") "All $targetCount" else "First $targetCount"
                btnDownload.text = "⬇ Download $prefix $noun ($fmtLabel)"
                btnDownload.alpha = 1.0f
                btnDownload.isEnabled = true
            }
        }

        class PickerHolder(
            val row: LinearLayout,
            val check: CheckBox,
            val num: TextView,
            val title: TextView,
            val meta: TextView
        ) : RecyclerView.ViewHolder(row)

        val listAdapter = object : RecyclerView.Adapter<PickerHolder>() {
            override fun getItemCount(): Int = visibleList.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PickerHolder {
                val row = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(d(4), d(7), d(6), d(7))
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                }
                val cb = CheckBox(parent.context).apply {
                    isClickable = false
                    isFocusable = false
                }
                val num = TextView(parent.context).apply {
                    textSize = 11.5f
                    setTextColor(ContextCompat.getColor(context, R.color.graball_orange))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(d(28), LinearLayout.LayoutParams.WRAP_CONTENT)
                    gravity = Gravity.CENTER
                }
                val textCol = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = d(4) }
                }
                val titleTv = TextView(parent.context).apply {
                    textSize = 13f
                    setTextColor(textNormalColor)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                val metaTv = TextView(parent.context).apply {
                    textSize = 11f
                    setTextColor(textSubColor)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                textCol.addView(titleTv)
                textCol.addView(metaTv)
                row.addView(cb)
                row.addView(num)
                row.addView(textCol)
                return PickerHolder(row, cb, num, titleTv, metaTv)
            }

            override fun onBindViewHolder(holder: PickerHolder, position: Int) {
                val (origIdx, item) = visibleList[position]
                val checked = selectedIds.contains(item.id)
                holder.check.isChecked = checked
                holder.num.text = String.format("%02d", origIdx + 1)
                holder.title.text = item.title
                holder.meta.text = listOf(item.channel, item.duration)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                holder.row.setBackgroundColor(
                    if (checked) Color.argb(38, 255, 107, 44) else Color.TRANSPARENT
                )
                holder.row.setOnClickListener {
                    if (selectedIds.contains(item.id)) {
                        selectedIds.remove(item.id)
                    } else {
                        selectedIds.add(item.id)
                    }
                    notifyItemChanged(holder.bindingAdapterPosition)
                    refreshUi()
                }
            }
        }
        recycler.adapter = listAdapter

        tabDownloadAll.setOnClickListener {
            if (selectMode) {
                selectMode = false
                refreshUi()
            }
        }
        tabSelectVideos.setOnClickListener {
            if (!selectMode) {
                selectMode = true
                refreshUi()
            }
        }
        chipAudio.setOnClickListener {
            audioOnly = true
            refreshUi()
        }
        chipVideo.setOnClickListener {
            audioOnly = false
            refreshUi()
        }
        qualityChips.forEachIndexed { idx, chip ->
            chip.setOnClickListener {
                selectedQualityPos = idx
                refreshUi()
            }
        }
        countLimitChips.forEach { (key, chip) ->
            chip.setOnClickListener {
                allCountMode = key
                refreshUi()
            }
        }
        btnSelectAll.setOnClickListener {
            visibleList.forEach { (_, item) -> selectedIds.add(item.id) }
            listAdapter.notifyDataSetChanged()
            refreshUi()
        }
        btnClearAll.setOnClickListener {
            if (filterInput.text.isNullOrBlank()) {
                selectedIds.clear()
            } else {
                visibleList.forEach { (_, item) -> selectedIds.remove(item.id) }
            }
            listAdapter.notifyDataSetChanged()
            refreshUi()
        }
        filterInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                visibleList = if (q.isEmpty()) {
                    allIndexed
                } else {
                    allIndexed.filter { (_, item) ->
                        item.title.lowercase().contains(q) ||
                            item.channel.lowercase().contains(q)
                    }
                }
                listAdapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnDownload.setOnClickListener {
            if (selectMode && selectedIds.isEmpty()) return@setOnClickListener
            val maxHeight = when (selectedQualityPos) {
                1 -> 1080
                2 -> 720
                3 -> 480
                4 -> 360
                else -> null
            }
            val playlistUrl = "https://www.youtube.com/playlist?list=${details.playlistId}"
            val staging = RuntimePaths.stagingDirFor(context, playlistUrl)
            if (!staging.exists()) staging.mkdirs()
            val template = File(
                staging,
                "%(playlist_title)s/%(playlist_index)s - %(title)s.%(ext)s"
            ).absolutePath

            val baseLabel = if (audioOnly) "Audio only" else qualityLabels[selectedQualityPos]
            val playlistMode = if (selectMode) "selected" else allCountMode
            val countSuffix = when {
                selectMode -> " · ${selectedIds.size} selected"
                allCountMode == "20" -> " · First 20"
                allCountMode == "30" -> " · First 30"
                allCountMode == "50" -> " · First 50"
                else -> " · All videos"
            }
            DownloadProgressBus.modes[playlistUrl] = baseLabel + countSuffix
            DownloadProgressBus.titles[playlistUrl] = details.title

            DownloadPrefs.save(
                context,
                DownloadPrefs.Choices(
                    qualityPos = selectedQualityPos,
                    audioOnly = audioOnly,
                    playlistPos = prefs.playlistPos
                )
            )

            DownloadService.startDownload(
                context = context,
                url = playlistUrl,
                outputTemplate = template,
                audioOnly = audioOnly,
                maxHeight = maxHeight,
                playlistMode = playlistMode,
                selectedVideoIds = if (selectMode) ArrayList(selectedIds) else null
            )
            val toastMsg = if (selectMode) {
                "Downloading ${selectedIds.size} selected item${if (selectedIds.size == 1) "" else "s"}…"
            } else {
                "Downloading playlist (${details.title})…"
            }
            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        refreshUi()
        dialog.setContentView(root)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setGravity(Gravity.BOTTOM)
            win.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
    }
}
