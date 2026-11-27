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
import android.widget.FrameLayout
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
import com.graball.engine.FastYouTubeDownloader
import com.graball.engine.LinkDetector
import com.graball.engine.LinkKind
import com.graball.engine.RuntimeExtractor
import com.graball.engine.RuntimePaths
import com.graball.engine.WebViewCookieSync
import com.graball.engine.YtDlpRunner
import org.json.JSONArray
import org.json.JSONObject
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

    /**
     * Universal entry point for ANY pasted, clipboard-detected, or shared URL
     * (YouTube, Facebook, TikTok, Instagram, X, Reddit, Vimeo, etc.).
     * Opens the modern Download Bottom Sheet with async resolution + real file sizes.
     */
    fun showForUrl(
        context: Context,
        url: String,
        kind: LinkKind,
        onActionTaken: (() -> Unit)? = null
    ) {
        if (FastYouTubeDownloader.isYouTubeUrl(url)) {
            val plId = FastYouTubeDownloader.extractPlaylistId(url)
            val vidId = FastYouTubeDownloader.extractVideoId(url)
            val ytItem = when {
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
                else -> null
            }
            if (ytItem != null) {
                show(
                    context = context,
                    item = ytItem,
                    showQuickActions = true,
                    onActionTaken = onActionTaken
                )
                return
            }
        }

        val platform = detectPlatform(url)
        val placeholderItem = YtItem(
            id = "",
            title = if (kind == LinkKind.PLAYLIST) "${platform.name} Playlist" else "${platform.name} Video",
            channel = LinkDetector.preview(url),
            isPlaylist = (kind == LinkKind.PLAYLIST)
        )
        showInternal(
            context = context,
            item = placeholderItem,
            showQuickActions = false,
            externalUrl = url,
            platformIcon = platform.icon,
            platformName = platform.name,
            onActionTaken = onActionTaken
        )
    }

    fun show(
        context: Context,
        item: YtItem,
        showQuickActions: Boolean = false,
        onActionTaken: (() -> Unit)? = null
    ) {
        showInternal(
            context = context,
            item = item,
            showQuickActions = showQuickActions,
            externalUrl = null,
            platformIcon = "▶",
            platformName = "YouTube",
            onActionTaken = onActionTaken
        )
    }

    private data class PlatformMeta(val name: String, val icon: String)

    private fun detectPlatform(url: String): PlatformMeta {
        val lower = url.lowercase(Locale.US)
        return when {
            lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com") ->
                PlatformMeta("Facebook", "📘")
            lower.contains("tiktok.com") ->
                PlatformMeta("TikTok", "🎵")
            lower.contains("instagram.com") || lower.contains("instagr.am") ->
                PlatformMeta("Instagram", "📸")
            lower.contains("twitter.com") || lower.contains("x.com") ->
                PlatformMeta("X", "𝕏")
            lower.contains("reddit.com") || lower.contains("redd.it") ->
                PlatformMeta("Reddit", "🟠")
            lower.contains("vimeo.com") ->
                PlatformMeta("Vimeo", "🎬")
            else -> PlatformMeta("Video", "🌐")
        }
    }

    private fun showInternal(
        context: Context,
        item: YtItem,
        showQuickActions: Boolean,
        externalUrl: String?,
        platformIcon: String,
        platformName: String,
        onActionTaken: (() -> Unit)?
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var currentItem = item
        val targetUrl = externalUrl ?: item.watchUrl
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

        val thumbBox = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(52)).apply {
                marginEnd = dp(12)
            }
            setBackgroundColor(colorCard)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                }
            }
            clipToOutline = true
        }
        thumbBox.addView(
            TextView(context).apply {
                text = platformIcon
                textSize = 22f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        )
        val thumbView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        thumbBox.addView(thumbView)
        val thumbUrl = item.thumbnailUrl.ifBlank {
            if (externalUrl == null && !item.isPlaylist && item.id.isNotBlank()) {
                "https://i.ytimg.com/vi/${item.id}/hqdefault.jpg"
            } else {
                ""
            }
        }
        if (thumbUrl.isNotBlank()) {
            ThumbnailLoader.load(thumbUrl, thumbView, targetWidthPx = 320)
        }
        headerRow.addView(thumbBox)

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
        var selectedAudioTrackId: String? = null
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
                val resolvedTitle = info.title.takeIf { !DownloadProgressBus.isPlaceholderTitle(it) }
                    ?: currentItem.title.takeIf { !DownloadProgressBus.isPlaceholderTitle(it) }
                    ?: info.title.ifBlank { currentItem.title }
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

            // Audio Language Selector (shown automatically for multi-audio / AI-dubbed YouTube videos)
            val audioTracks = info?.availableAudioTracks().orEmpty()
            if (audioTracks.size > 1) {
                val activeTrack = audioTracks.firstOrNull { it.id == selectedAudioTrackId }
                    ?: audioTracks.firstOrNull { it.isOriginal }
                    ?: audioTracks.first()
                selectedAudioTrackId = activeTrack.id
                val origSuffix = if (activeTrack.isOriginal && !activeTrack.label.contains("original", ignoreCase = true)) {
                    " (Original)"
                } else {
                    ""
                }
                val audioRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(colorCard)
                        setStroke(dp(1), 0x33FFFFFF)
                    }
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }
                audioRow.addView(
                    TextView(context).apply {
                        text = "🔊 Audio Language"
                        textSize = 12f
                        setTextColor(colorSub)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                )
                audioRow.addView(
                    TextView(context).apply {
                        text = "${activeTrack.label}$origSuffix ▾"
                        textSize = 12.5f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(colorOrange)
                    }
                )
                audioRow.setOnClickListener {
                    val labels = audioTracks.map { t ->
                        val mark = if (t.id == selectedAudioTrackId) "✓ " else ""
                        val tag = if (t.isOriginal && !t.label.contains("original", ignoreCase = true)) " (Original)" else ""
                        "$mark${t.label}$tag"
                    }.toTypedArray()
                    android.app.AlertDialog.Builder(context)
                        .setTitle("Audio Language")
                        .setItems(labels) { _, which ->
                            selectedAudioTrackId = audioTracks[which].id
                            renderOptions(info, fallbackNote)
                        }
                        .show()
                }
                body.addView(audioRow)
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
            val url = targetUrl
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
                "%(title).120B.%(ext)s"
            } else {
                "%(playlist_title).80B/%(playlist_index)s - %(title).100B.%(ext)s"
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

            if (!DownloadProgressBus.isPlaceholderTitle(currentItem.title)) {
                DownloadProgressBus.titles[url] = currentItem.title
            }
            if (currentItem.thumbnailUrl.startsWith("http")) {
                DownloadProgressBus.thumbnails[url] = currentItem.thumbnailUrl
            }
            DownloadService.startDownload(
                context, url, template, chosen.audioOnly, chosen.maxHeight, playlistMode,
                audioTrackId = selectedAudioTrackId
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

        // Non-YouTube platforms (Facebook, TikTok, Instagram, X, Reddit, Vimeo, etc.)
        if (externalUrl != null) {
            if (item.isPlaylist) {
                renderOptions(info = null)
                return
            }
            val cachedExt = getCachedInfo(externalUrl)
            if (cachedExt != null) {
                renderOptions(info = cachedExt)
                return
            }
            // Instant 0ms UX: show selectable quality cards right away so the user can tap
            // Download immediately without waiting, while real thumbnail + sizes load quietly above!
            renderOptions(info = null)
            loadingText.text = "Checking $platformName sizes…"
            loadingRow.visibility = View.VISIBLE

            val appCtx = context.applicationContext
            // ~250ms instant thumbnail + title fast-path for TikTok via public oEmbed
            if (externalUrl.contains("tiktok.com", ignoreCase = true)) {
                resolverExecutor.execute {
                    val oembed = fetchTikTokOEmbed(externalUrl) ?: return@execute
                    mainHandler.post {
                        if (dismissed) return@post
                        if (DownloadProgressBus.isPlaceholderTitle(currentItem.title) && oembed.title.isNotBlank()) {
                            currentItem = currentItem.copy(title = oembed.title)
                            titleTv.text = oembed.title
                        }
                        if (oembed.author.isNotBlank()) {
                            metaTv.text = oembed.author
                        }
                        if (oembed.thumbUrl.startsWith("http") && currentItem.thumbnailUrl.isBlank()) {
                            currentItem = currentItem.copy(thumbnailUrl = oembed.thumbUrl)
                            DownloadProgressBus.thumbnails[externalUrl] = oembed.thumbUrl
                            ThumbnailLoader.load(oembed.thumbUrl, thumbView, targetWidthPx = 320)
                        }
                    }
                }
            }

            resolverExecutor.execute {
                val resolved = resolveExternalUrlInfo(appCtx, externalUrl, platformName)
                if (resolved != null) {
                    cacheStreamInfo(externalUrl, resolved)
                }
                mainHandler.post {
                    if (dismissed) return@post
                    loadingRow.visibility = View.GONE
                    if (resolved != null) {
                        renderOptions(info = resolved)
                    }
                }
            }
            return
        }

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
            var resolved = try {
                YouTubeInnerTube.resolveForDownload(
                    context = appCtx,
                    videoId = item.id
                ).info
            } catch (_: Exception) {
                null
            }
            // Guarantee a real title even if a fallback InnerTube client omitted videoDetails.title
            if (resolved != null && DownloadProgressBus.isPlaceholderTitle(resolved.title)) {
                val oembedTitle = FastYouTubeDownloader.fetchYouTubeOEmbedTitle(item.id)
                if (!oembedTitle.isNullOrBlank()) {
                    resolved = resolved.copy(title = oembedTitle)
                }
            } else if (resolved == null && DownloadProgressBus.isPlaceholderTitle(currentItem.title)) {
                val oembedTitle = FastYouTubeDownloader.fetchYouTubeOEmbedTitle(item.id)
                if (!oembedTitle.isNullOrBlank()) {
                    mainHandler.post {
                        if (!dismissed) {
                            currentItem = currentItem.copy(title = oembedTitle)
                            titleTv.text = oembedTitle
                        }
                    }
                }
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

    private fun getCachedInfo(cacheKey: String): VideoStreamInfo? {
        if (cacheKey.isBlank()) return null
        synchronized(cacheLock) {
            val entry = infoCache[cacheKey] ?: return null
            if (System.currentTimeMillis() - entry.cachedAtMs <= CACHE_TTL_MS) {
                return entry.info
            }
            infoCache.remove(cacheKey)
        }
        return null
    }

    private fun getInstantStreamInfo(videoId: String): VideoStreamInfo? {
        if (videoId.isBlank()) return null
        YouTubePlaybackService.currentState.streamInfo
            ?.takeIf { it.videoId == videoId && it.availableHeights().isNotEmpty() }
            ?.let { return it }
        return getCachedInfo(videoId)
    }

    /**
     * Resolves metadata, thumbnail, real resolutions, and file sizes for Facebook,
     * TikTok, Instagram, X, Reddit, Vimeo, and other supported sites via GrabAll's
     * bundled yt-dlp extractor (--dump-single-json).
     */
    private fun resolveExternalUrlInfo(
        context: Context,
        url: String,
        platformName: String
    ): VideoStreamInfo? {
        return try {
            if (!RuntimeExtractor.extractIfNeeded(context, DownloadService.RUNTIME_VERSION)) {
                return null
            }
            WebViewCookieSync.exportYouTubeCookiesIfLoggedIn(context)
            val cookies = RuntimePaths.cookiesFile(context).takeIf { it.exists() }
            val runner = YtDlpRunner(
                pythonBinary = RuntimePaths.pythonBinary(context),
                ytDlpScript = RuntimePaths.ytDlpScript(context),
                ffmpegDir = RuntimePaths.ffmpegDir(context),
                quickJsBinary = RuntimePaths.quickJsBinary(context),
                cookiesFile = cookies,
                cacheDir = context.cacheDir,
                homeDir = context.filesDir,
                certFile = RuntimePaths.certFile(context),
                pythonHomeDir = RuntimePaths.pythonHomeDir(context),
                pythonLibDir = RuntimePaths.pythonLibDir(context),
                pythonPathDir = RuntimePaths.ytDlpBundleDir(context)
            )
            val (raw, _) = runner.extractInfoJson(url, timeoutSec = 12L)
            if (raw.isNullOrBlank()) return null

            val root = JSONObject(raw)
            val rawTitle = root.optString("title").ifBlank { root.optString("fulltitle") }
            val cleanTitle = cleanSocialTitle(rawTitle).ifBlank { "$platformName Video" }
            val channel = root.optString("uploader")
                .ifBlank { root.optString("channel") }
                .ifBlank { platformName }
            val durationSec = root.optLong("duration", 0L)
            val thumb = root.optString("thumbnail").takeIf { it.startsWith("http") }.orEmpty()
            val formats = root.optJSONArray("formats") ?: JSONArray()

            val videoStreams = mutableListOf<StreamFormat>()
            val audioStreams = mutableListOf<StreamFormat>()
            val muxedStreams = mutableListOf<StreamFormat>()

            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val fUrl = f.optString("url")
                if (!fUrl.startsWith("http")) continue
                val vcodec = f.optString("vcodec", "")
                val acodec = f.optString("acodec", "")
                val formatId = f.optString("format_id", "").lowercase(Locale.US)
                val ext = f.optString("ext", "mp4")
                var height = f.optInt("height", 0)
                val width = f.optInt("width", 0)
                if (height > 0 && width > 0) {
                    // Portrait Reels/TikToks: use short side so 1080x1920 shows as 1080p, 720x1280 as 720p!
                    height = minOf(height, width)
                }
                if (height <= 0) {
                    height = when {
                        formatId == "hd" || formatId.contains("hd") -> 720
                        formatId == "sd" || formatId.contains("sd") -> 360
                        else -> 0
                    }
                }
                val tbr = (f.optDouble("tbr", 0.0) * 1000.0).toInt()
                    .takeIf { it > 0 }
                    ?: (f.optDouble("vbr", 0.0) * 1000.0).toInt()
                    .takeIf { it > 0 }
                    ?: (f.optDouble("abr", 0.0) * 1000.0).toInt()
                val fileSize = f.optLong("filesize", 0L).takeIf { it > 0L }
                    ?: f.optLong("filesize_approx", 0L).coerceAtLeast(0L)

                val hasVideo = vcodec != "none" && (vcodec.isNotBlank() || height > 0)
                val hasAudio = acodec != "none" && (acodec.isNotBlank() || !hasVideo)
                val mime = when {
                    hasVideo && !hasAudio -> "video/$ext; codecs=\"$vcodec\""
                    !hasVideo && hasAudio -> "audio/$ext; codecs=\"$acodec\""
                    else -> "video/$ext; codecs=\"$vcodec, $acodec\""
                }
                val normH = if (height > 0) VideoStreamInfo.normalizeHeight(height) else 0
                val fmt = StreamFormat(
                    itag = i,
                    url = fUrl,
                    mimeType = mime,
                    qualityLabel = if (normH > 0) "${normH}p" else ext.uppercase(Locale.US),
                    height = normH,
                    bitrate = tbr,
                    isAudioOnly = !hasVideo && hasAudio,
                    isVideoOnly = hasVideo && !hasAudio,
                    isMuxed = hasVideo && hasAudio,
                    contentLengthBytes = fileSize
                )
                when {
                    fmt.isAudioOnly -> audioStreams.add(fmt)
                    fmt.isVideoOnly -> videoStreams.add(fmt)
                    fmt.isMuxed -> muxedStreams.add(fmt)
                }
            }

            if (thumb.isNotBlank()) {
                DownloadProgressBus.thumbnails[url] = thumb
            }

            VideoStreamInfo(
                videoId = root.optString("id", ""),
                title = cleanTitle,
                channel = channel,
                durationSec = durationSec,
                viewCount = "",
                description = "",
                thumbnailUrl = thumb,
                hlsUrl = null,
                videoStreams = videoStreams,
                audioStreams = audioStreams,
                muxedStreams = muxedStreams,
                userAgent = "Mozilla/5.0"
            )
        } catch (_: Exception) {
            null
        }
    }

    private data class QuickOEmbed(val title: String, val author: String, val thumbUrl: String)

    private fun fetchTikTokOEmbed(url: String): QuickOEmbed? {
        return try {
            val encoded = java.net.URLEncoder.encode(url, "UTF-8")
            val req = okhttp3.Request.Builder()
                .url("https://www.tiktok.com/oembed?url=$encoded")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            YouTubeSessionConfig.anonymousOkHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val obj = JSONObject(resp.body?.string().orEmpty())
                val title = cleanSocialTitle(obj.optString("title"))
                val author = obj.optString("author_name").trim()
                val thumb = obj.optString("thumbnail_url").trim()
                if (title.isBlank() && thumb.isBlank()) null
                else QuickOEmbed(title, author, thumb)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Strips noisy Facebook/social view+reaction counters from extracted captions. */
    private fun cleanSocialTitle(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val stripped = trimmed.replace(
            Regex("""^\s*[\d.,]+[KMBkmb]?\s+views?\b[^|｜]*[|｜]\s*""", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        return (if (stripped.isNotBlank()) stripped else trimmed).take(140)
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
