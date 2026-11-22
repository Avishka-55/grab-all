package com.graball.youtube

import android.content.Context
import android.util.Log
import com.graball.engine.DownloadService
import com.graball.engine.RuntimeExtractor
import com.graball.engine.RuntimePaths
import com.graball.engine.YtDlpRunner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Lightweight, 100% ad-free YouTube data & stream extractor.
 *
 * - Search, Trending, Playlists, and Related videos hit YouTube's InnerTube
 *   JSON API directly via OkHttp (~200-400ms, zero WebView RAM, skips all ad
 *   renderers).
 * - Stream URLs use a fast 2-tier strategy:
 *   1) Fast path (~300ms): InnerTube /player with VISIONOS client (returns
 *      direct adaptive video + audio + HLS URLs without JS signature solving).
 *   2) Bulletproof fallback: GrabAll's bundled yt-dlp + QuickJS + cookies.txt
 *      (--dump-single-json), handling Made-for-Kids, age-gated videos, or
 *      future YouTube client changes automatically.
 */
object YouTubeInnerTube {

    private const val TAG = "YouTubeInnerTube"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private const val WEB_CLIENT_VERSION = "2.20260708.00.00"
    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val VISIONOS_CLIENT_VERSION = "1.02"
    private const val VISIONOS_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    enum class SearchFilter(val params: String?) {
        ALL(null),
        VIDEOS("EgIQAQ%3D%3D"),
        PLAYLISTS("EgIQAw%3D%3D"),
        MUSIC(null)
    }

    /** Ad renderer keys to ignore completely so feeds remain 100% ad-free. */
    private val AD_KEYS = setOf(
        "adSlotRenderer",
        "promotedSparklesWebRenderer",
        "promotedVideoRenderer",
        "inFeedAdLayoutRenderer",
        "displayAdRenderer",
        "compactPromotedVideoRenderer",
        "searchPyvRenderer",
        "mealbarPromoRenderer"
    )

    // ---- Search / Explore / Playlist / Related ----

    fun search(query: String, filter: SearchFilter = SearchFilter.ALL): List<YtItem> {
        val effectiveQuery = if (filter == SearchFilter.MUSIC && !query.contains("music", true)) {
            "$query music"
        } else {
            query
        }
        val body = JSONObject().apply {
            put("context", webContext())
            put("query", effectiveQuery)
            if (filter.params != null) {
                put("params", filter.params)
            }
        }
        val root = postInnerTube("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", body)
            ?: return emptyList()
        val items = mutableListOf<YtItem>()
        collectItems(root, items, seen = mutableSetOf())
        return if (filter == SearchFilter.PLAYLISTS) {
            items.filter { it.isPlaylist }
        } else if (filter == SearchFilter.VIDEOS) {
            items.filter { !it.isPlaylist }
        } else {
            items
        }
    }

    fun explore(musicMode: Boolean = false): List<YtItem> {
        if (musicMode) {
            val music = search("top music hits 2026 official audio", SearchFilter.ALL)
            if (music.isNotEmpty()) return music
        }
        val body = JSONObject().apply {
            put("context", webContext())
            put("browseId", "FEtrending")
        }
        val root = postInnerTube("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body)
        val items = mutableListOf<YtItem>()
        if (root != null) {
            collectItems(root, items, seen = mutableSetOf())
        }
        if (items.isEmpty()) {
            return search("trending music and videos", SearchFilter.VIDEOS)
        }
        return items.filter { !it.isPlaylist }
    }

    fun fetchPlaylist(playlistId: String): PlaylistDetails? {
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        val body = JSONObject().apply {
            put("context", webContext())
            put("browseId", browseId)
        }
        val root = postInnerTube("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body)
            ?: return null

        val header = root.optJSONObject("header")
        val title = header?.optJSONObject("playlistHeaderRenderer")
            ?.let { extractText(it.optJSONObject("title")) }
            ?: root.optJSONObject("metadata")
                ?.optJSONObject("playlistMetadataRenderer")
                ?.optString("title")
            ?: "Playlist"
        val channel = header?.optJSONObject("playlistHeaderRenderer")
            ?.let { extractText(it.optJSONObject("ownerText")) }
            ?: ""

        val items = mutableListOf<YtItem>()
        collectPlaylistVideos(root, items, seen = mutableSetOf())
        return PlaylistDetails(
            playlistId = playlistId.removePrefix("VL"),
            title = title.ifBlank { "Playlist" },
            channel = channel,
            items = items
        )
    }

    fun fetchRelated(videoId: String): List<YtItem> {
        val body = JSONObject().apply {
            put("context", webContext())
            put("videoId", videoId)
        }
        val root = postInnerTube("https://www.youtube.com/youtubei/v1/next?prettyPrint=false", body)
            ?: return emptyList()
        val items = mutableListOf<YtItem>()
        val seen = mutableSetOf(videoId)
        collectItems(root, items, seen)
        return items.filter { !it.isPlaylist }.take(30)
    }

    // ---- Stream Resolution (Fast InnerTube + Bulletproof yt-dlp Fallback) ----

    fun resolveStreams(context: Context, videoId: String): VideoStreamInfo? {
        // Tier 1: Fast direct InnerTube /player with VISIONOS client (~300ms)
        val fast = resolveViaVisionOs(videoId)
        if (fast != null) return fast

        // Tier 2: Bulletproof fallback via bundled yt-dlp + QuickJS + cookies.txt
        Log.d(TAG, "InnerTube fast path unavailable for $videoId, falling back to yt-dlp")
        return resolveViaYtDlp(context, videoId)
    }

    private fun resolveViaVisionOs(videoId: String): VideoStreamInfo? {
        val body = JSONObject().apply {
            put(
                "context",
                JSONObject().apply {
                    put(
                        "client",
                        JSONObject().apply {
                            put("clientName", "VISIONOS")
                            put("clientVersion", VISIONOS_CLIENT_VERSION)
                            put("deviceMake", "Apple")
                            put("deviceModel", "RealityDevice17,1")
                            put("osName", "visionOS")
                            put("osVersion", "26.5.23O471")
                            put("hl", "en")
                            put("gl", "US")
                        }
                    )
                }
            )
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val root = postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            body = body,
            userAgent = VISIONOS_UA,
            clientNameId = "101",
            clientVersion = VISIONOS_CLIENT_VERSION
        ) ?: return null

        val status = root.optJSONObject("playabilityStatus")?.optString("status") ?: ""
        if (status != "OK") return null

        val streamingData = root.optJSONObject("streamingData") ?: return null
        val hlsUrl = streamingData.optString("hlsManifestUrl").takeIf { it.startsWith("http") }

        val videoStreams = mutableListOf<StreamFormat>()
        val audioStreams = mutableListOf<StreamFormat>()
        val muxedStreams = mutableListOf<StreamFormat>()

        parseInnerTubeFormats(streamingData.optJSONArray("formats"), isMuxedDefault = true, videoStreams, audioStreams, muxedStreams)
        parseInnerTubeFormats(streamingData.optJSONArray("adaptiveFormats"), isMuxedDefault = false, videoStreams, audioStreams, muxedStreams)

        if (hlsUrl == null && muxedStreams.isEmpty() && (videoStreams.isEmpty() || audioStreams.isEmpty())) {
            return null
        }

        val details = root.optJSONObject("videoDetails")
        val title = details?.optString("title").orEmpty().ifBlank { "YouTube Video" }
        val channel = details?.optString("author").orEmpty()
        val durationSec = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
        val views = formatViews(details?.optString("viewCount")?.toLongOrNull())
        val description = details?.optString("shortDescription").orEmpty()
        val thumb = pickThumb(details?.optJSONObject("thumbnail"))
            .ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }

        return VideoStreamInfo(
            videoId = videoId,
            title = title,
            channel = channel,
            durationSec = durationSec,
            viewCount = views,
            description = description,
            thumbnailUrl = thumb,
            hlsUrl = hlsUrl,
            videoStreams = videoStreams,
            audioStreams = audioStreams,
            muxedStreams = muxedStreams,
            userAgent = VISIONOS_UA
        )
    }

    private fun parseInnerTubeFormats(
        arr: JSONArray?,
        isMuxedDefault: Boolean,
        videoOut: MutableList<StreamFormat>,
        audioOut: MutableList<StreamFormat>,
        muxedOut: MutableList<StreamFormat>
    ) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("url")
            if (!url.startsWith("http")) continue
            val mime = obj.optString("mimeType")
            val itag = obj.optInt("itag", 0)
            val height = obj.optInt("height", 0)
            val bitrate = obj.optInt("bitrate", 0)
            val qualityLabel = obj.optString("qualityLabel", if (height > 0) "${height}p" else "")
            val isVideo = mime.startsWith("video/")
            val isAudio = mime.startsWith("audio/")
            val fmt = StreamFormat(
                itag = itag,
                url = url,
                mimeType = mime,
                qualityLabel = qualityLabel,
                height = height,
                bitrate = bitrate,
                isAudioOnly = isAudio,
                isVideoOnly = isVideo && !isMuxedDefault,
                isMuxed = isMuxedDefault
            )
            when {
                isMuxedDefault -> muxedOut.add(fmt)
                isAudio -> audioOut.add(fmt)
                isVideo -> videoOut.add(fmt)
            }
        }
    }

    private fun resolveViaYtDlp(context: Context, videoId: String): VideoStreamInfo? {
        if (!RuntimeExtractor.extractIfNeeded(context, DownloadService.RUNTIME_VERSION)) {
            return null
        }
        val cookies = RuntimePaths.cookiesFile(context).takeIf { it.exists() }
        val runner = YtDlpRunner(
            pythonBinary = RuntimePaths.pythonBinary(context),
            ytDlpScript = RuntimePaths.ytDlpScript(context),
            ffmpegDir = RuntimePaths.ffmpegDir(context),
            quickJsBinary = RuntimePaths.quickJsBinary(context),
            cookiesFile = cookies,
            cacheDir = context.cacheDir,
            homeDir = context.filesDir,
            certFile = RuntimePaths.certFile(context)
        )
        val raw = runner.extractInfoJson("https://www.youtube.com/watch?v=$videoId") ?: return null
        return try {
            val root = JSONObject(raw)
            val formats = root.optJSONArray("formats") ?: JSONArray()
            val videoStreams = mutableListOf<StreamFormat>()
            val audioStreams = mutableListOf<StreamFormat>()
            val muxedStreams = mutableListOf<StreamFormat>()
            var hlsUrl: String? = null
            var detectedUa = WEB_UA

            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val url = f.optString("url")
                if (!url.startsWith("http")) continue
                val proto = f.optString("protocol")
                val vcodec = f.optString("vcodec", "none")
                val acodec = f.optString("acodec", "none")
                val ext = f.optString("ext", "")
                val height = f.optInt("height", 0)
                val tbr = (f.optDouble("tbr", 0.0) * 1000.0).toInt()
                val headers = f.optJSONObject("http_headers")
                val ua = headers?.optString("User-Agent")
                if (!ua.isNullOrBlank()) detectedUa = ua

                if (proto == "m3u8_native" || proto == "m3u8") {
                    if (hlsUrl == null && vcodec != "none" && acodec != "none") {
                        hlsUrl = url
                    }
                    continue
                }
                if (proto != "https" && proto != "http") continue
                val hasVideo = vcodec != "none" && vcodec.isNotBlank()
                val hasAudio = acodec != "none" && acodec.isNotBlank()
                val mime = when {
                    hasVideo && !hasAudio -> "video/$ext; codecs=\"$vcodec\""
                    !hasVideo && hasAudio -> "audio/$ext; codecs=\"$acodec\""
                    else -> "video/$ext; codecs=\"$vcodec, $acodec\""
                }
                val fmt = StreamFormat(
                    itag = f.optString("format_id").toIntOrNull() ?: 0,
                    url = url,
                    mimeType = mime,
                    qualityLabel = if (height > 0) "${height}p" else ext,
                    height = height,
                    bitrate = tbr,
                    isAudioOnly = !hasVideo && hasAudio,
                    isVideoOnly = hasVideo && !hasAudio,
                    isMuxed = hasVideo && hasAudio
                )
                when {
                    fmt.isMuxed -> muxedStreams.add(fmt)
                    fmt.isAudioOnly -> audioStreams.add(fmt)
                    fmt.isVideoOnly -> videoStreams.add(fmt)
                }
            }

            if (hlsUrl == null && muxedStreams.isEmpty() && (videoStreams.isEmpty() || audioStreams.isEmpty())) {
                return null
            }

            VideoStreamInfo(
                videoId = videoId,
                title = root.optString("title", "YouTube Video"),
                channel = root.optString("uploader", root.optString("channel", "")),
                durationSec = root.optLong("duration", 0L),
                viewCount = formatViews(root.optLong("view_count", -1L).takeIf { it >= 0 }),
                description = root.optString("description", ""),
                thumbnailUrl = root.optString("thumbnail")
                    .ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
                hlsUrl = hlsUrl,
                videoStreams = videoStreams,
                audioStreams = audioStreams,
                muxedStreams = muxedStreams,
                userAgent = detectedUa
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing yt-dlp JSON", e)
            null
        }
    }

    // ---- HTTP & JSON Traversal Helpers ----

    private fun webContext(): JSONObject = JSONObject().apply {
        put(
            "client",
            JSONObject().apply {
                put("clientName", "WEB")
                put("clientVersion", WEB_CLIENT_VERSION)
                put("hl", "en")
                put("gl", "US")
            }
        )
    }

    private fun postInnerTube(
        url: String,
        body: JSONObject,
        userAgent: String = WEB_UA,
        clientNameId: String = "1",
        clientVersion: String = WEB_CLIENT_VERSION
    ): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Origin", "https://www.youtube.com")
                .header("X-YouTube-Client-Name", clientNameId)
                .header("X-YouTube-Client-Version", clientVersion)
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val txt = resp.body?.string() ?: return null
                JSONObject(txt)
            }
        } catch (e: Exception) {
            Log.w(TAG, "InnerTube request failed: ${e.message}")
            null
        }
    }

    private fun collectItems(node: Any?, out: MutableList<YtItem>, seen: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key in AD_KEYS) continue
                    val value = node.opt(key)
                    when (key) {
                        "videoRenderer", "compactVideoRenderer", "gridVideoRenderer" -> {
                            parseVideoRenderer(value as? JSONObject)?.let { item ->
                                if (seen.add(item.id)) out.add(item)
                            }
                        }
                        "playlistRenderer", "compactPlaylistRenderer", "gridPlaylistRenderer" -> {
                            parsePlaylistRenderer(value as? JSONObject)?.let { item ->
                                if (seen.add("PL:" + item.id)) out.add(item)
                            }
                        }
                        "lockupViewModel" -> {
                            parseLockupViewModel(value as? JSONObject)?.let { item ->
                                val k = if (item.isPlaylist) "PL:${item.id}" else item.id
                                if (seen.add(k)) out.add(item)
                            }
                        }
                        else -> collectItems(value, out, seen)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectItems(node.opt(i), out, seen)
                }
            }
        }
    }

    private fun collectPlaylistVideos(node: Any?, out: MutableList<YtItem>, seen: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (key == "playlistVideoRenderer" || key == "playlistPanelVideoRenderer") {
                        parseVideoRenderer(value as? JSONObject)?.let { item ->
                            if (seen.add(item.id)) out.add(item)
                        }
                    } else {
                        collectPlaylistVideos(value, out, seen)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectPlaylistVideos(node.opt(i), out, seen)
                }
            }
        }
    }

    private fun parseVideoRenderer(obj: JSONObject?): YtItem? {
        if (obj == null) return null
        val id = obj.optString("videoId")
        if (id.length != 11) return null
        val title = extractText(obj.optJSONObject("title"))
        if (title.isBlank()) return null
        val channel = extractText(obj.optJSONObject("ownerText"))
            .ifBlank { extractText(obj.optJSONObject("shortBylineText")) }
            .ifBlank { extractText(obj.optJSONObject("longBylineText")) }
        val duration = extractText(obj.optJSONObject("lengthText"))
        val views = extractText(obj.optJSONObject("shortViewCountText"))
            .ifBlank { extractText(obj.optJSONObject("viewCountText")) }
        val thumb = pickThumb(obj.optJSONObject("thumbnail"))
            .ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }
        return YtItem(
            id = id,
            title = title,
            channel = channel,
            duration = duration,
            views = views,
            thumbnailUrl = thumb,
            isPlaylist = false
        )
    }

    private fun parsePlaylistRenderer(obj: JSONObject?): YtItem? {
        if (obj == null) return null
        val id = obj.optString("playlistId")
        if (id.isBlank()) return null
        val title = extractText(obj.optJSONObject("title"))
        if (title.isBlank()) return null
        val channel = extractText(obj.optJSONObject("shortBylineText"))
            .ifBlank { extractText(obj.optJSONObject("longBylineText")) }
        val count = obj.optString("videoCount").ifBlank {
            extractText(obj.optJSONObject("videoCountShortText"))
        }
        val thumbs = obj.optJSONArray("thumbnails")
        val thumb = pickThumb(thumbs?.optJSONObject(0))
            .ifBlank { pickThumb(obj.optJSONObject("thumbnail")) }
        return YtItem(
            id = id,
            title = title,
            channel = channel,
            thumbnailUrl = thumb,
            isPlaylist = true,
            videoCount = if (count.isNotBlank()) "$count videos" else "Playlist"
        )
    }

    /** YouTube's newer lockupViewModel renderer used for playlists & some videos. */
    private fun parseLockupViewModel(obj: JSONObject?): YtItem? {
        if (obj == null) return null
        val contentType = obj.optString("contentType")
        val id = obj.optString("contentId")
        if (id.isBlank()) return null
        val isPlaylist = contentType.contains("PLAYLIST", ignoreCase = true)
        if (!isPlaylist && id.length != 11) return null

        val meta = obj.optJSONObject("metadata")
            ?.optJSONObject("lockupMetadataViewModel")
        val title = meta?.optJSONObject("title")?.optString("content").orEmpty()
        if (title.isBlank()) return null

        val metaRows = meta?.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows")
        val channel = metaRows?.optJSONObject(0)
            ?.optJSONArray("metadataParts")
            ?.optJSONObject(0)
            ?.optJSONObject("text")
            ?.optString("content")
            .orEmpty()

        val imgView = obj.optJSONObject("contentImage")
        val sources = imgView?.optJSONObject("collectionThumbnailViewModel")
            ?.optJSONObject("primaryThumbnail")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONObject("image")
            ?.optJSONArray("sources")
            ?: imgView?.optJSONObject("thumbnailViewModel")
                ?.optJSONObject("image")
                ?.optJSONArray("sources")
        var thumb = ""
        if (sources != null && sources.length() > 0) {
            thumb = sources.optJSONObject(sources.length() - 1)?.optString("url").orEmpty()
        }
        if (thumb.isBlank() && !isPlaylist) {
            thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        }

        return YtItem(
            id = id,
            title = title,
            channel = channel,
            thumbnailUrl = thumb,
            isPlaylist = isPlaylist,
            videoCount = if (isPlaylist) "Playlist" else ""
        )
    }

    private fun extractText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString().trim()
    }

    private fun pickThumb(obj: JSONObject?): String {
        val arr = obj?.optJSONArray("thumbnails") ?: return ""
        if (arr.length() == 0) return ""
        // Prefer medium-high (~320-480px) thumbnail instead of huge 1280px maxres
        // to save bandwidth and RAM on scrolling lists.
        for (i in arr.length() - 1 downTo 0) {
            val t = arr.optJSONObject(i) ?: continue
            val w = t.optInt("width", 0)
            val u = t.optString("url")
            if (u.startsWith("http") && w in 240..640) return u
        }
        val last = arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty()
        return if (last.startsWith("//")) "https:$last" else last
    }

    private fun formatViews(count: Long?): String {
        if (count == null || count < 0) return ""
        return when {
            count >= 1_000_000_000L -> String.format("%.1fB views", count / 1_000_000_000.0)
            count >= 1_000_000L -> String.format("%.1fM views", count / 1_000_000.0)
            count >= 1_000L -> String.format("%.1fK views", count / 1_000.0)
            else -> "$count views"
        }
    }
}
