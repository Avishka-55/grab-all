package com.graball.youtube

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
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
 * Interactive Playlist Item Picker dialog:
 * Lets the user browse, filter, and check exact songs/videos inside any
 * YouTube playlist (even 100+ items), pick Audio (MP3) or Video quality,
 * and download ONLY the selected items at full parallel speed into
 * Downloads/GrabAll/YouTube/<Playlist Title>/.
 */
object PlaylistPickerDialog {

    /** Open directly when [PlaylistDetails] is already loaded (e.g. YouTubeActivity). */
    fun show(context: Context, details: PlaylistDetails) {
        if (details.items.isEmpty()) {
            Toast.makeText(context, "Playlist is empty", Toast.LENGTH_SHORT).show()
            return
        }
        showPickerUi(context, details)
    }

    /** Load playlist from a YouTube URL asynchronously, then open the picker. */
    fun showForUrl(context: Context, playlistUrl: String) {
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
                showPickerUi(context, details)
            }
        }.start()
    }

    private fun showPickerUi(context: Context, details: PlaylistDetails) {
        val dp = context.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()

        val prefs = DownloadPrefs.load(context)
        val allIndexed = details.items.mapIndexed { idx, item -> idx to item }
        val selectedIds = LinkedHashSet<String>()
        // Pre-select nothing? Or pre-select all? Starting unchecked with 1-tap
        // "Select All" makes picking 3-5 exact songs instant; let's start unchecked
        // with clear "Select All" & "Clear" chips right at the top!
        var visibleList = allIndexed

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161622"))
            setPadding(d(16), d(14), d(16), d(12))
        }

        val titleView = TextView(context).apply {
            text = details.title
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val countView = TextView(context).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.graball_orange))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(2) }
        }

        // Quick filter input for 100+ song playlists
        val filterInput = EditText(context).apply {
            hint = "Filter ${details.items.size} items…"
            textSize = 13f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(ContextCompat.getColor(context, R.color.graball_tab_text))
            setBackgroundResource(R.drawable.input_bg)
            setPadding(d(12), d(8), d(12), d(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                d(38)
            ).apply { topMargin = d(10) }
        }

        // Controls row: Select All | Clear | Audio MP3 | Quality
        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(8) }
        }

        fun makeChip(label: String): TextView = TextView(context).apply {
            text = label
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.yt_chip_bg)
            setPadding(d(10), 0, d(10), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                d(30)
            ).apply { marginEnd = d(6) }
        }

        val btnSelectAll = makeChip("Select All")
        val btnClearAll = makeChip("Clear")
        val audioCheck = CheckBox(context).apply {
            text = "MP3"
            textSize = 12f
            setTextColor(Color.WHITE)
            isChecked = prefs.audioOnly
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = d(4) }
        }

        val qualitySpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Best", "1080p", "720p", "480p", "360p")
            )
            setSelection(prefs.qualityPos)
            isEnabled = !prefs.audioOnly
            alpha = if (prefs.audioOnly) 0.4f else 1.0f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        audioCheck.setOnCheckedChangeListener { _, checked ->
            qualitySpinner.isEnabled = !checked
            qualitySpinner.alpha = if (checked) 0.4f else 1.0f
        }

        controlsRow.addView(btnSelectAll)
        controlsRow.addView(btnClearAll)
        controlsRow.addView(audioCheck)
        controlsRow.addView(qualitySpinner)

        // Checklist RecyclerView (bounded height so dialog stays crisp on any screen)
        val maxListHeight = (context.resources.displayMetrics.heightPixels * 0.46f).toInt()
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxListHeight
            ).apply { topMargin = d(8) }
        }

        // Bottom Action Row
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(10) }
        }
        val btnCancel = TextView(context).apply {
            text = "Cancel"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.graball_subtext))
            setBackgroundResource(R.drawable.yt_chip_bg)
            layoutParams = LinearLayout.LayoutParams(0, d(40), 0.7f).apply { marginEnd = d(8) }
        }
        val btnDownload = TextView(context).apply {
            text = "⬇ Download Selected (0)"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.yt_chip_selected)
            layoutParams = LinearLayout.LayoutParams(0, d(40), 1.5f)
        }
        bottomRow.addView(btnCancel)
        bottomRow.addView(btnDownload)

        fun refreshCounts() {
            val n = selectedIds.size
            countView.text = "$n of ${details.items.size} selected · Tap songs to toggle"
            btnDownload.text = if (n > 0) "⬇ Download Selected ($n)" else "Select items to download"
            btnDownload.alpha = if (n > 0) 1.0f else 0.45f
            btnDownload.isEnabled = n > 0
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
                    setTextColor(Color.WHITE)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                val metaTv = TextView(parent.context).apply {
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(context, R.color.graball_subtext))
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
                    refreshCounts()
                }
            }
        }
        recycler.adapter = listAdapter

        btnSelectAll.setOnClickListener {
            visibleList.forEach { (_, item) -> selectedIds.add(item.id) }
            listAdapter.notifyDataSetChanged()
            refreshCounts()
        }
        btnClearAll.setOnClickListener {
            if (filterInput.text.isNullOrBlank()) {
                selectedIds.clear()
            } else {
                visibleList.forEach { (_, item) -> selectedIds.remove(item.id) }
            }
            listAdapter.notifyDataSetChanged()
            refreshCounts()
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

        root.addView(titleView)
        root.addView(countView)
        root.addView(filterInput)
        root.addView(controlsRow)
        root.addView(recycler)
        root.addView(bottomRow)
        refreshCounts()

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnDownload.setOnClickListener {
            if (selectedIds.isEmpty()) return@setOnClickListener
            val audioOnly = audioCheck.isChecked
            val qPos = qualitySpinner.selectedItemPosition
            val maxHeight = when (qPos) {
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

            val modeLabel = buildString {
                if (audioOnly) {
                    append("Audio only")
                } else {
                    append(
                        when (qPos) {
                            1 -> "1080p"
                            2 -> "720p"
                            3 -> "480p"
                            4 -> "360p"
                            else -> "Best"
                        }
                    )
                }
                append(" · ${selectedIds.size} selected")
            }
            DownloadProgressBus.modes[playlistUrl] = modeLabel
            DownloadProgressBus.titles[playlistUrl] = details.title

            DownloadPrefs.save(
                context,
                DownloadPrefs.Choices(
                    qualityPos = qPos,
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
                playlistMode = "selected",
                selectedVideoIds = ArrayList(selectedIds)
            )
            Toast.makeText(
                context,
                "Downloading ${selectedIds.size} selected item${if (selectedIds.size == 1) "" else "s"}…",
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
        }

        dialog.show()
    }
}
