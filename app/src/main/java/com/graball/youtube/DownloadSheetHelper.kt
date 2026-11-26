package com.graball.youtube

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.graball.PlayerActivity
import com.graball.R
import com.graball.engine.DownloadPrefs
import com.graball.engine.DownloadProgressBus
import com.graball.engine.DownloadService
import com.graball.engine.RuntimePaths
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Modern bottom-sheet Download Format Picker for any YouTube video or playlist.
 *
 * - Opens immediately with video thumbnail, title, and metadata.
 * - For single videos, resolves real available resolutions & audio/video file
 *   sizes asynchronously (or 0ms instantaneously if already playing in
 *   YouTubePlaybackService / recent in-memory cache).
 * - Displays clean selectable Music (M4A Fast / MP3 192k) and Video (144p-4K)
 *   cards with real sizes and a dynamic primary Download button.
 */
object DownloadSheetHelper {

    private data class CachedInfo(val info: VideoStreamInfo, val cachedAtMs: Long)

    private data class FormatOption(
        val id: String,
        val title: String,
        val badge: String,
        val sizeText: String,
        val audioOnly: Boolean,
        val audioFormat: String?, // DownloadPrefs.AUDIO_FORMAT_M4A or AUDIO_FORMAT_MP3
        val maxHeight: Int?,
        val modeLabel: String
    )

    private const val CACHE_TTL_MS = 5L * 60L * 1000L
    private val infoCache = LinkedHashMap<String, CachedInfo>(16, 0.75f, true)
    private val cacheLock = Any()
    private val resolverExecutor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(
        context: Context,
        item: YtItem,
        showQuickActions: Boolean = false,
        onActionTaken: (() -> Unit)? = null
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var currentItem = item
        val prefs = DownloadPrefs.load(context)
        val savedAudioFormat = DownloadPrefs.getAudioFormat(context)
        val colorBg = ContextCompat.getColor(context, R.color.graball_nav)
        val colorCard = ContextCompat.getColor(context, R.color.graball_card)
        val colorText = ContextCompat.getColor(context, R.color.graball_text)
        val colorSub = ContextCompat.getColor(context, R.color.graball_subtext)
        val colorOrange = ContextCompat.getColor(context, R.color.graball_orange)

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        var dismissed = false
        dialog.setOnDismissListener { dismissed = true }

        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(colorBg)
            }
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }

        // Drag handle pill
        sheet.addView(
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(12)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(0x55FFFFFF)
                }
            }
        )

        // Header: Thumbnail + Title + Meta + Close
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val thumbView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(52)).apply {
                marginEnd = dp(12)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(colorCard)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                }
            }
            clipToOutline = true
        }
        val thumbUrl = item.thumbnailUrl.ifBlank {
            if (!item.isPlaylist && item.id.isNotBlank()) "https://i.ytimg.com/vi/${item.id}/hqdefault.jpg" else ""
        }
        if (thumbUrl.isNotBlank()) {
            ThumbnailLoader.load(thumbUrl, thumbView, targetWidthPx = 320)
        }
        headerRow.addView(thumbView)

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleTv = TextView(context).apply {
            text = item.title.ifBlank { if (item.isPlaylist) "YouTube Playlist" else "YouTube Video" }
            setTextColor(colorText)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val metaTv = TextView(context).apply {
            val parts = buildList {
                if (item.isPlaylist) add("Playlist")
                if (item.channel.isNotBlank()) add(item.channel)
                if (item.duration.isNotBlank()) add(item.duration)
                if (item.videoCount.isNotBlank()) add(item.videoCount)
            }
            text = parts.joinToString(" · ")
            setTextColor(colorSub)
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        titleCol.addView(titleTv)
        titleCol.addView(metaTv)
        headerRow.addView(titleCol)

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(colorSub)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginStart = dp(6)
            }
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(closeBtn)
        sheet.addView(headerRow)

        // Optional quick actions row when opened from Home screen / Shared link / Clipboard pill
        if (showQuickActions) {
            val quickRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }

            fun buildQuickBtn(label: String, accent: Boolean, onClick: () -> Unit): TextView {
                return TextView(context).apply {
                    text = label
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(if (accent) colorOrange else colorText)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(if (accent) 0x20FF6B35 else colorCard)
                        setStroke(dp(1), if (accent) 0x66FF6B35 else 0x24FFFFFF)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
                    setOnClickListener { onClick() }
                }
            }

            if (item.isPlaylist) {
                val watchPlBtn = buildQuickBtn("▶ Watch Playlist", accent = true) {
                    dialog.dismiss()
                    onActionTaken?.invoke()
                    context.startActivity(
                        Intent(context, YouTubeActivity::class.java)
                            .putExtra("start_playlist_id", currentItem.id)
                    )
                }
                val selectItemsBtn = buildQuickBtn("☑ Select Items", accent = false) {
                    dialog.dismiss()
                    onActionTaken?.invoke()
                    PlaylistPickerDialog.showForUrl(context, currentItem.watchUrl)
                }
                quickRow.addView(watchPlBtn)
                quickRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
                quickRow.addView(selectItemsBtn)
            } else {
                val watchBtn = buildQuickBtn("▶ Watch Ad-Free", accent = true) {
                    val toPlay = currentItem
                    dialog.dismiss()
                    onActionTaken?.invoke()
                    YouTubePlaybackService.withService(context) { svc ->
                        svc.playItem(toPlay, audioOnly = false)
                    }
                    context.startActivity(
                        Intent(context, YouTubePlayerActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra("restore_video", true)
                        }
                    )
                }
                val listenBtn = buildQuickBtn("🎧 Listen Audio", accent = false) {
                    val toPlay = currentItem
                    dialog.dismiss()
                    onActionTaken?.invoke()
                    YouTubePlaybackService.withService(context) { svc ->
                        svc.playItem(toPlay, audioOnly = true)
                    }
                    context.startActivity(
                        Intent(context, PlayerActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra("open_now_playing", true)
                        }
                    )
                }
                quickRow.addView(watchBtn)
                quickRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
                quickRow.addView(listenBtn)
            }
            sheet.addView(quickRow)
        }

        // Loading indicator row (hidden immediately when cached/playlist)
        val loadingRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(colorCard)
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val loadingSpinner = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                marginEnd = dp(12)
            }
            indeterminateTintList = android.content.res.ColorStateList.valueOf(colorOrange)
        }
        val loadingText = TextView(context).apply {
            text = "Loading resolutions & file sizes…"
            setTextColor(colorText)
            textSize = 13f
        }
        loadingRow.addView(loadingSpinner)
        loadingRow.addView(loadingText)
        sheet.addView(loadingRow)

        // Scrollable body for playlist range + Music + Video grids
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(body)
        sheet.addView(scroll)

        // Primary CTA button
        val downloadBtn = TextView(context).apply {
            text = "⬇ Download"
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(colorOrange)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            )
            alpha = 0.55f
            isEnabled = false
        }
        sheet.addView(downloadBtn)

        var selectedOption: FormatOption? = null
        var selectedPlaylistPos = (prefs.playlistPos - 1).coerceIn(0, 3)
        val optionCardUpdaters = mutableListOf<Pair<FormatOption, (Boolean) -> Unit>>()

        fun updateCta() {
            val opt = selectedOption
            if (opt == null) {
                downloadBtn.text = "Loading…"
                downloadBtn.isEnabled = false
                downloadBtn.alpha = 0.55f
            } else {
                val sizeSuffix = if (opt.sizeText.isNotBlank() && opt.sizeText != "Auto") {
                    " (${opt.sizeText})"
                } else {
                    ""
                }
                downloadBtn.text = "⬇ Download · ${opt.title}$sizeSuffix"
                downloadBtn.isEnabled = true
                downloadBtn.alpha = 1f
            }
        }

        fun selectOption(opt: FormatOption) {
            selectedOption = opt
            optionCardUpdaters.forEach { (candidate, updater) ->
                updater(candidate.id == opt.id)
            }
            updateCta()
        }

        fun buildOptionCard(opt: FormatOption): View {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleView = TextView(context).apply {
                text = opt.title
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(colorText)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            topRow.addView(titleView)

            if (opt.badge.isNotBlank()) {
                val badgeView = TextView(context).apply {
                    text = opt.badge
                    textSize = 9.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(colorOrange)
                    setPadding(dp(5), dp(1), dp(5), dp(1))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(0x28FF6B35)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(4) }
                }
                topRow.addView(badgeView)
            }
            card.addView(topRow)

            val bottomRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            val sizeView = TextView(context).apply {
                text = opt.sizeText.ifBlank { "Auto" }
                textSize = 11.5f
                setTextColor(colorSub)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val checkDot = TextView(context).apply {
                text = "●"
                textSize = 11f
                setTextColor(colorOrange)
                visibility = View.INVISIBLE
            }
            bottomRow.addView(sizeView)
            bottomRow.addView(checkDot)
            card.addView(bottomRow)

            fun applySelected(isSelected: Boolean) {
                card.background = GradientDrawable().apply {
                    cornerRadius = dp(11).toFloat()
                    setColor(if (isSelected) 0x22FF6B35 else colorCard)
                    setStroke(dp(if (isSelected) 2 else 1), if (isSelected) colorOrange else 0x22FFFFFF)
                }
                checkDot.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
                sizeView.setTextColor(if (isSelected) colorText else colorSub)
            }

            applySelected(false)
            card.setOnClickListener { selectOption(opt) }
            optionCardUpdaters.add(opt to ::applySelected)
            return card
        }

        fun addSection(headerTitle: String, options: List<FormatOption>) {
            if (options.isEmpty()) return
            val label = TextView(context).apply {
                text = headerTitle
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(colorSub)
                setPadding(dp(2), dp(6), 0, dp(8))
            }
            body.addView(label)

            var i = 0
            while (i < options.size) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }
                val left = buildOptionCard(options[i])
                row.addView(left)
                row.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
                if (i + 1 < options.size) {
                    val right = buildOptionCard(options[i + 1])
                    row.addView(right)
                } else {
                    row.addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })
                }
                body.addView(row)
                i += 2
            }
        }

        fun renderOptions(info: VideoStreamInfo?, fallbackNote: String? = null) {
            body.removeAllViews()
            optionCardUpdaters.clear()
            loadingRow.visibility = View.GONE

            // Refresh header title/duration if resolved info is richer
            if (info != null) {
                val resolvedTitle = info.title.ifBlank { currentItem.title }
                val resolvedChannel = info.channel.ifBlank { currentItem.channel }
                val resolvedDuration = if (info.durationSec > 0L) formatDuration(info.durationSec) else currentItem.duration
                val resolvedThumb = info.thumbnailUrl.ifBlank { currentItem.thumbnailUrl }
                currentItem = currentItem.copy(
                    title = resolvedTitle,
                    channel = resolvedChannel,
                    duration = resolvedDuration,
                    thumbnailUrl = resolvedThumb
                )
                if (resolvedTitle.isNotBlank()) titleTv.text = resolvedTitle
                val metaParts = buildList {
                    if (resolvedChannel.isNotBlank()) add(resolvedChannel)
                    if (resolvedDuration.isNotBlank()) add(resolvedDuration)
                }
                if (metaParts.isNotEmpty()) metaTv.text = metaParts.joinToString(" · ")
                if (resolvedThumb.isNotBlank() && thumbUrl.isBlank()) {
                    ThumbnailLoader.load(resolvedThumb, thumbView, targetWidthPx = 320)
                }
            }

            // Playlist range chips (playlists only)
            if (item.isPlaylist) {
                val plHeader = TextView(context).apply {
                    text = "Playlist Range"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(colorSub)
                    setPadding(dp(2), 0, 0, dp(8))
                }
                body.addView(plHeader)
                val chipLabels = listOf("First 20", "First 30", "First 50", "All")
                val chipViews = mutableListOf<TextView>()
                val chipRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                }
                fun refreshPlChips() {
                    chipViews.forEachIndexed { idx, tv ->
                        val active = idx == selectedPlaylistPos
                        tv.setBackgroundResource(if (active) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
                        tv.setTextColor(if (active) Color.WHITE else colorText)
                    }
                }
                chipLabels.forEachIndexed { idx, labelText ->
                    val chip = TextView(context).apply {
                        text = labelText
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setPadding(dp(10), dp(7), dp(10), dp(7))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (idx < chipLabels.lastIndex) marginEnd = dp(6)
                        }
                        setOnClickListener {
                            selectedPlaylistPos = idx
                            refreshPlChips()
                        }
                    }
                    chipViews.add(chip)
                    chipRow.addView(chip)
                }
                refreshPlChips()
                body.addView(chipRow)
            }

            if (!fallbackNote.isNullOrBlank()) {
                body.addView(
                    TextView(context).apply {
                        text = fallbackNote
                        textSize = 11.5f
                        setTextColor(colorSub)
                        setPadding(dp(2), 0, dp(2), dp(8))
                    }
                )
            }

            // Music section
            val m4aBytes = info?.estimatedAudioSizeBytes(mp3BitrateKbps = null) ?: 0L
            val mp3Bytes = info?.estimatedAudioSizeBytes(mp3BitrateKbps = 192) ?: 0L
            val audioKbps = info?.bestAudio()?.bitrate?.takeIf { it > 0 }?.div(1000)
            val m4aTitle = if (audioKbps != null && audioKbps in 32..512) "M4A ${audioKbps}k" else "M4A Audio"
            val musicOptions = listOf(
                FormatOption(
                    id = "audio_m4a",
                    title = m4aTitle,
                    badge = "FAST",
                    sizeText = formatSize(m4aBytes, fallback = "Fast M4A"),
                    audioOnly = true,
                    audioFormat = DownloadPrefs.AUDIO_FORMAT_M4A,
                    maxHeight = null,
                    modeLabel = "Audio (M4A)"
                ),
                FormatOption(
                    id = "audio_mp3",
                    title = "MP3 192k",
                    badge = "MP3",
                    sizeText = formatSize(mp3Bytes, fallback = "MP3 Audio"),
                    audioOnly = true,
                    audioFormat = DownloadPrefs.AUDIO_FORMAT_MP3,
                    maxHeight = null,
                    modeLabel = "Audio (MP3)"
                )
            )
            addSection("🎵 Music", musicOptions)

            // Video section
            val videoOptions = mutableListOf<FormatOption>()
            val resolvedHeights = info?.availableHeights().orEmpty()
            if (resolvedHeights.isNotEmpty() && info != null) {
                resolvedHeights.forEach { h ->
                    val bytes = info.estimatedVideoSizeBytes(h)
                    val badge = when {
                        h >= 2160 -> "4K"
                        h >= 1440 -> "2K"
                        h >= 720 -> "HD"
                        else -> "MP4"
                    }
                    videoOptions.add(
                        FormatOption(
                            id = "video_$h",
                            title = "${h}p",
                            badge = badge,
                            sizeText = formatSize(bytes, fallback = "MP4"),
                            audioOnly = false,
                            audioFormat = null,
                            maxHeight = h,
                            modeLabel = "${h}p"
                        )
                    )
                }
            } else {
                listOf(
                    null to ("Best Quality" to "AUTO"),
                    1080 to ("1080p" to "HD"),
                    720 to ("720p" to "HD"),
                    480 to ("480p" to "MP4"),
                    360 to ("360p" to "MP4")
                ).forEach { (h, pair) ->
                    videoOptions.add(
                        FormatOption(
                            id = "video_${h ?: 0}",
                            title = pair.first,
                            badge = pair.second,
                            sizeText = "Auto",
                            audioOnly = false,
                            audioFormat = null,
                            maxHeight = h,
                            modeLabel = h?.let { "${it}p" } ?: "Best"
                        )
                    )
                }
            }
            addSection("🎬 Video", videoOptions)

            // Pick smart default matching user's last preference
            val defaultChoice = if (prefs.audioOnly) {
                musicOptions.firstOrNull { it.audioFormat == savedAudioFormat } ?: musicOptions.first()
            } else {
                val prefHeight = when (prefs.qualityPos) {
                    1 -> 1080
                    2 -> 720
                    3 -> 480
                    4 -> 360
                    else -> null
                }
                if (prefHeight != null) {
                    videoOptions.firstOrNull { it.maxHeight == prefHeight }
                        ?: videoOptions.firstOrNull { (it.maxHeight ?: Int.MAX_VALUE) <= prefHeight }
                        ?: videoOptions.firstOrNull()
                } else {
                    // Default to 720p or 1080p or best available
                    videoOptions.firstOrNull { it.maxHeight == 720 }
                        ?: videoOptions.firstOrNull { it.maxHeight == 1080 }
                        ?: videoOptions.firstOrNull()
                }
            } ?: musicOptions.first()

            selectOption(defaultChoice)
        }

        downloadBtn.setOnClickListener {
            val chosen = selectedOption ?: return@setOnClickListener
            val url = item.watchUrl
            val playlistMode = if (item.isPlaylist) {
                when (selectedPlaylistPos) {
                    0 -> "20"
                    1 -> "30"
                    2 -> "50"
                    else -> "all"
                }
            } else {
                "single"
            }

            if (chosen.audioOnly && chosen.audioFormat != null) {
                DownloadPrefs.setAudioFormat(context, chosen.audioFormat)
            }

            val staging = RuntimePaths.stagingDirFor(context, url)
            if (!staging.exists()) staging.mkdirs()

            val fileName = if (playlistMode == "single") {
                "%(title)s.%(ext)s"
            } else {
                "%(playlist_title)s/%(playlist_index)s - %(title)s.%(ext)s"
            }
            val template = File(staging, fileName).absolutePath

            DownloadProgressBus.modes[url] = buildString {
                append(chosen.modeLabel)
                when (playlistMode) {
                    "20" -> append(" · First 20")
                    "30" -> append(" · First 30")
                    "50" -> append(" · First 50")
                    "all" -> append(" · All videos")
                }
            }

            val mappedQualityPos = when (chosen.maxHeight) {
                1080 -> 1
                720 -> 2
                480 -> 3
                360 -> 4
                else -> 0
            }
            DownloadPrefs.save(
                context,
                DownloadPrefs.Choices(
                    qualityPos = if (chosen.audioOnly) prefs.qualityPos else mappedQualityPos,
                    audioOnly = chosen.audioOnly,
                    playlistPos = if (item.isPlaylist) selectedPlaylistPos + 1 else prefs.playlistPos
                )
            )

            if (currentItem.title.isNotBlank() &&
                currentItem.title != "YouTube Video" &&
                currentItem.title != "YouTube Playlist"
            ) {
                DownloadProgressBus.titles[url] = currentItem.title
            }
            DownloadService.startDownload(
                context, url, template, chosen.audioOnly, chosen.maxHeight, playlistMode
            )
            dialog.dismiss()
            onActionTaken?.invoke()
            Toast.makeText(context, "Downloading ${chosen.title}…", Toast.LENGTH_SHORT).show()
        }

        dialog.setContentView(sheet)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setGravity(Gravity.BOTTOM)
            win.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        // Cap scroll height cleanly so tall 4K lists never push CTA off-screen
        val maxScrollPx = (context.resources.displayMetrics.heightPixels * 0.50f).toInt()
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (scroll.height > maxScrollPx) {
                val lp = scroll.layoutParams
                lp.height = maxScrollPx
                scroll.layoutParams = lp
            }
        }
        dialog.show()

        // Populate formats: instant for playlists, currently-playing video, or cache;
        // otherwise fast background InnerTube resolution (~250-400ms).
        if (item.isPlaylist) {
            renderOptions(info = null)
            // Enrich header asynchronously when opened from a raw pasted/shared playlist URL
            if (item.id.isNotBlank() && (item.title.isBlank() || item.title == "YouTube Playlist")) {
                resolverExecutor.execute {
                    val details = try {
                        YouTubeInnerTube.fetchPlaylist(item.id, maxItems = 10)
                    } catch (_: Exception) {
                        null
                    } ?: return@execute
                    mainHandler.post {
                        if (dismissed) return@post
                        val plTitle = details.title.ifBlank { currentItem.title }
                        val plChannel = details.channel.ifBlank { currentItem.channel }
                        val plCount = when {
                            details.items.size >= 10 -> "10+ videos"
                            details.items.isNotEmpty() -> "${details.items.size} videos"
                            else -> ""
                        }
                        val plThumb = details.items.firstOrNull()?.thumbnailUrl.orEmpty()
                        currentItem = currentItem.copy(
                            title = plTitle,
                            channel = plChannel,
                            videoCount = plCount,
                            thumbnailUrl = plThumb.ifBlank { currentItem.thumbnailUrl }
                        )
                        if (plTitle.isNotBlank()) titleTv.text = plTitle
                        val metaParts = buildList {
                            add("Playlist")
                            if (plChannel.isNotBlank()) add(plChannel)
                            if (plCount.isNotBlank()) add(plCount)
                        }
                        metaTv.text = metaParts.joinToString(" · ")
                        if (plThumb.isNotBlank()) {
                            ThumbnailLoader.load(plThumb, thumbView, targetWidthPx = 320)
                        }
                    }
                }
            }
            return
        }

        val instantInfo = getInstantStreamInfo(item.id)
        if (instantInfo != null) {
            renderOptions(info = instantInfo)
            return
        }

        val appCtx = context.applicationContext
        resolverExecutor.execute {
            val resolved = try {
                YouTubeInnerTube.resolveForDownload(
                    context = appCtx,
                    videoId = item.id
                ).info
            } catch (_: Exception) {
                null
            }
            if (resolved != null) {
                cacheStreamInfo(item.id, resolved)
            }
            mainHandler.post {
                if (dismissed) return@post
                if (resolved != null) {
                    renderOptions(info = resolved)
                } else {
                    renderOptions(
                        info = null,
                        fallbackNote = "Exact sizes unavailable right now — pick a quality to download directly."
                    )
                }
            }
        }
    }

    private fun getInstantStreamInfo(videoId: String): VideoStreamInfo? {
        if (videoId.isBlank()) return null
        YouTubePlaybackService.currentState.streamInfo
            ?.takeIf { it.videoId == videoId && it.availableHeights().isNotEmpty() }
            ?.let { return it }
        synchronized(cacheLock) {
            val entry = infoCache[videoId] ?: return null
            if (System.currentTimeMillis() - entry.cachedAtMs <= CACHE_TTL_MS) {
                return entry.info
            }
            infoCache.remove(videoId)
        }
        return null
    }

    private fun cacheStreamInfo(videoId: String, info: VideoStreamInfo) {
        synchronized(cacheLock) {
            infoCache[videoId] = CachedInfo(info, System.currentTimeMillis())
            while (infoCache.size > 16) {
                val oldestKey = infoCache.entries.firstOrNull()?.key ?: break
                infoCache.remove(oldestKey)
            }
        }
    }

    private fun formatSize(bytes: Long, fallback: String): String {
        if (bytes <= 0L) return fallback
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024.0 -> String.format(Locale.US, "%.2f GB", mb / 1024.0)
            mb >= 100.0 -> String.format(Locale.US, "%.0f MB", mb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        }
    }

    private fun formatDuration(sec: Long): String {
        if (sec <= 0L) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}
