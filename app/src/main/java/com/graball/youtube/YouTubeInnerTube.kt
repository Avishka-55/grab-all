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

    private const val ANDROID_VR_CLIENT_VERSION = "1.61.48"
    private const val ANDROID_VR_UA =
        "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    private const val IOS_CLIENT_VERSION = "21.26.4"
    private const val IOS_UA =
        "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

    data class ResolveResult(
        val info: VideoStreamInfo?,
        val error: String? = null
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedVisitorData: String? = null

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

    /** Lightweight YouTube autocomplete suggestions (~25ms, <1KB JSON). */
    fun fetchSearchSuggestions(query: String): List<String> {
        val clean = query.trim()
        if (clean.length < 2) return emptyList()
        return try {
            val encoded = java.net.URLEncoder.encode(clean, "UTF-8")
            val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", WEB_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val arr = JSONArray(body)
                val suggestions = arr.optJSONArray(1) ?: return emptyList()
                val out = mutableListOf<String>()
                for (i in 0 until minOf(6, suggestions.length())) {
                    val s = suggestions.optString(i).trim()
                    if (s.isNotBlank()) out.add(s)
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

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

    // ---- Stream Resolution (Multi-Client InnerTube + Bulletproof yt-dlp Fallback) ----

    fun resolveStreams(
        context: Context,
        videoId: String,
        onStatus: ((String) -> Unit)? = null
    ): ResolveResult {
        onStatus?.invoke("Connecting to YouTube…")
        val visitorData = ensureVisitorData()

        // Tier 1: Fast direct InnerTube /player clients (~300ms each) with
        // real session visitorData + X-Goog-Visitor-Id (same headers yt-dlp sends).
        val visionOs = resolveViaClient(
            videoId = videoId,
            clientName = "VISIONOS",
            clientVersion = VISIONOS_CLIENT_VERSION,
            clientNameId = "101",
            userAgent = VISIONOS_UA,
            visitorData = visitorData,
            extraClientFields = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "RealityDevice17,1",
                "osName" to "visionOS",
                "osVersion" to "26.5.23O471"
            )
        )
        if (visionOs.info != null) return visionOs

        val androidVr = resolveViaClient(
            videoId = videoId,
            clientName = "ANDROID_VR",
            clientVersion = ANDROID_VR_CLIENT_VERSION,
            clientNameId = "28",
            userAgent = ANDROID_VR_UA,
            visitorData = visitorData,
            extraClientFields = mapOf(
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
                "androidSdkVersion" to 32,
                "osName" to "Android",
                "osVersion" to "12L"
            )
        )
        if (androidVr.info != null) return androidVr

        val ios = resolveViaClient(
            videoId = videoId,
            clientName = "IOS",
            clientVersion = IOS_CLIENT_VERSION,
            clientNameId = "5",
            userAgent = IOS_UA,
            visitorData = visitorData,
            extraClientFields = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "18.3.2.22D82"
            )
        )
        if (ios.info != null) return ios

        // Tier 2: Bulletproof fallback via bundled yt-dlp + QuickJS + cookies.txt
        onStatus?.invoke("Resolving via yt-dlp engine…")
        Log.d(TAG, "InnerTube fast clients failed ($videoId: ${visionOs.error}), trying yt-dlp")
        val ytDlpResult = resolveViaYtDlp(context, videoId)
        if (ytDlpResult.info != null) return ytDlpResult

        val reason = ytDlpResult.error?.takeIf { it.isNotBlank() }
            ?: visionOs.error?.takeIf { it.isNotBlank() }
            ?: "Couldn't load stream for this video"
        return ResolveResult(null, reason)
    }

    private fun ensureVisitorData(): String? {
        cachedVisitorData?.takeIf { it.isNotBlank() }?.let { return it }
        val body = JSONObject().apply { put("context", webContext()) }
        postInnerTube("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false", body)
        return cachedVisitorData
    }

    private fun resolveViaClient(
        videoId: String,
        clientName: String,
        clientVersion: String,
        clientNameId: String,
        userAgent: String,
        visitorData: String?,
        extraClientFields: Map<String, Any>
    ): ResolveResult {
        val clientObj = JSONObject().apply {
            put("clientName", clientName)
            put("clientVersion", clientVersion)
            put("hl", "en")
            put("gl", "US")
            if (!visitorData.isNullOrBlank()) {
                put("visitorData", visitorData)
            }
            extraClientFields.forEach { (k, v) -> put(k, v) }
        }
        val body = JSONObject().apply {
            put("context", JSONObject().apply { put("client", clientObj) })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val root = postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            body = body,
            userAgent = userAgent,
            clientNameId = clientNameId,
            clientVersion = clientVersion,
            visitorData = visitorData
        ) ?: return ResolveResult(null, "Network error reaching YouTube")

        val playStatus = root.optJSONObject("playabilityStatus")
        val status = playStatus?.optString("status") ?: ""
        if (status != "OK") {
            val reason = playStatus?.optString("reason").orEmpty()
            return ResolveResult(null, reason.ifBlank { status.ifBlank { "UNPLAYABLE" } })
        }

        val streamingData = root.optJSONObject("streamingData")
            ?: return ResolveResult(null, "No streaming data returned")
        val hlsUrl = streamingData.optString("hlsManifestUrl").takeIf { it.startsWith("http") }

        val videoStreams = mutableListOf<StreamFormat>()
        val audioStreams = mutableListOf<StreamFormat>()
        val muxedStreams = mutableListOf<StreamFormat>()

        parseInnerTubeFormats(streamingData.optJSONArray("formats"), isMuxedDefault = true, videoStreams, audioStreams, muxedStreams)
        parseInnerTubeFormats(streamingData.optJSONArray("adaptiveFormats"), isMuxedDefault = false, videoStreams, audioStreams, muxedStreams)

        if (hlsUrl == null && muxedStreams.isEmpty() && (videoStreams.isEmpty() || audioStreams.isEmpty())) {
            return ResolveResult(null, "No playable stream URLs")
        }

        val details = root.optJSONObject("videoDetails")
        val title = details?.optString("title").orEmpty().ifBlank { "YouTube Video" }
        val channel = details?.optString("author").orEmpty()
        val durationSec = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
        val views = formatViews(details?.optString("viewCount")?.toLongOrNull())
        val description = details?.optString("shortDescription").orEmpty()
        val thumb = pickThumb(details?.optJSONObject("thumbnail"))
            .ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }

        return ResolveResult(
            VideoStreamInfo(
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
                userAgent = userAgent
            )
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

    private fun resolveViaYtDlp(context: Context, videoId: String): ResolveResult {
        if (!RuntimeExtractor.extractIfNeeded(context, DownloadService.RUNTIME_VERSION)) {
            return ResolveResult(null, RuntimeExtractor.lastError ?: "Runtime setup failed")
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
            certFile = RuntimePaths.certFile(context),
            pythonHomeDir = RuntimePaths.pythonHomeDir(context),
            pythonLibDir = RuntimePaths.pythonLibDir(context),
            pythonPathDir = RuntimePaths.ytDlpBundleDir(context)
        )
        val (raw, err) = runner.extractInfoJson("https://www.youtube.com/watch?v=$videoId")
        if (raw == null) {
            return ResolveResult(null, err.ifBlank { "yt-dlp failed to extract video" })
        }
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
                    val master = f.optString("manifest_url")
                    if (hlsUrl == null && master.startsWith("http")) {
                        hlsUrl = master
                    } else if (hlsUrl == null && vcodec != "none" && acodec != "none") {
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
                return ResolveResult(null, "No playable formats found")
            }

            ResolveResult(
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
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing yt-dlp JSON", e)
            ResolveResult(null, e.message ?: "Stream parse failed")
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
        clientVersion: String = WEB_CLIENT_VERSION,
        visitorData: String? = cachedVisitorData
    ): JSONObject? {
        return try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Origin", "https://www.youtube.com")
                .header("X-YouTube-Client-Name", clientNameId)
                .header("X-YouTube-Client-Version", clientVersion)
            if (!visitorData.isNullOrBlank()) {
                reqBuilder.header("X-Goog-Visitor-Id", visitorData)
            }
            val req = reqBuilder
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val txt = resp.body?.string() ?: return null
                val json = JSONObject(txt)
                val vd = json.optJSONObject("responseContext")?.optString("visitorData")
                if (!vd.isNullOrBlank()) {
                    cachedVisitorData = vd
                }
                json
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
