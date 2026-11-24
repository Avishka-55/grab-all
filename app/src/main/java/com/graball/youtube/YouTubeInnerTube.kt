package com.graball.youtube

import android.content.Context
import android.util.Log
import com.graball.engine.DownloadService
import com.graball.engine.RuntimeExtractor
import com.graball.engine.RuntimePaths
import com.graball.engine.WebViewCookieSync
import com.graball.engine.YtDlpRunner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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

    private const val WEB_CLIENT_VERSION = YouTubeSessionConfig.FALLBACK_WEB_CLIENT_VERSION
    private const val WEB_UA = YouTubeSessionConfig.DESKTOP_WEB_UA

    private const val VISIONOS_CLIENT_VERSION = "1.02"
    private const val VISIONOS_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    private const val ANDROID_VR_CLIENT_VERSION = "1.61.48"
    private const val ANDROID_VR_UA =
        "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    private const val IOS_CLIENT_VERSION = "21.26.4"
    private const val IOS_UA =
        "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

    private const val TV_SAMSUNG_CLIENT_VERSION = "5.20260707"
    private const val TV_SAMSUNG_UA =
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1"

    private const val WEB_SAFARI_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15"

    /** Cooldown during which anonymous Tier 1 is skipped after a VPN bot-check. */
    private const val ANON_BOT_BLOCK_COOLDOWN_MS = 15L * 60L * 1000L

    /** Cooldown during which Tier 2A (V8 TVHTML5) is skipped after failing on the current network. */
    private const val V8_FAIL_COOLDOWN_MS = 15L * 60L * 1000L

    @Volatile
    private var lastAnonBotBlockedAtMs: Long = 0L

    @Volatile
    private var lastV8FailedAtMs: Long = 0L

    fun resetAnonBotBlockHint() {
        lastAnonBotBlockedAtMs = 0L
        lastV8FailedAtMs = 0L
    }

    private fun isAnonBotBlockedRecently(): Boolean {
        val ts = lastAnonBotBlockedAtMs
        return ts > 0L && (System.currentTimeMillis() - ts) < ANON_BOT_BLOCK_COOLDOWN_MS
    }

    private fun isV8FailedRecently(): Boolean {
        val ts = lastV8FailedAtMs
        return ts > 0L && (System.currentTimeMillis() - ts) < V8_FAIL_COOLDOWN_MS
    }

    private fun looksLikeBotBlock(error: String?): Boolean {
        val lower = error?.lowercase() ?: return false
        return lower.contains("robot") ||
            lower.contains("not a bot") ||
            lower.contains("sign in to confirm") ||
            lower.contains("login_required")
    }

    private fun hasWarmJsSolverCache(context: Context): Boolean {
        val solverDir = java.io.File(RuntimePaths.ytDlpCacheDir(context), "challenge-solver")
        return solverDir.isDirectory &&
            (solverDir.listFiles { f -> f.isFile && f.name.startsWith("player") }?.isNotEmpty() == true)
    }

    data class ResolveResult(
        val info: VideoStreamInfo?,
        val error: String? = null
    )

    data class VideoEngagement(
        val channelId: String?,
        val channelName: String,
        val isSubscribed: Boolean,
        val isLiked: Boolean,
        val likeCount: String,
        val commentCount: String,
        val commentsToken: String?
    )

    data class YtComment(
        val author: String,
        val text: String,
        val timeText: String,
        val likes: String
    )

    private val client: OkHttpClient
        get() = YouTubeSessionConfig.sharedOkHttpClient

    private val anonClient: OkHttpClient
        get() = YouTubeSessionConfig.anonymousOkHttpClient

    @Volatile
    private var cachedVisitorData: String? = null

    enum class SearchFilter(val params: String?) {
        ALL(null),
        VIDEOS("EgIQAQ=="),
        PLAYLISTS("EgIQAw=="),
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
            anonClient.newCall(req).execute().use { resp ->
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

    /**
     * Fetches the user's real personalized YouTube Home feed (`FEwhat_to_watch`)
     * when signed in via GrabAll's on-device WebView session, using live scraped
     * `ytcfg` headers + `SAPISIDHASH` auth + the shared CookieManager jar.
     */
    fun fetchSignedInHomeFeed(context: Context): List<YtItem> {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context) ?: return emptyList()
        val cfg = YouTubeSessionConfig.ensureWebConfig()
        val body = JSONObject().apply {
            put("context", webContext(cfg))
            put("browseId", "FEwhat_to_watch")
        }
        val root = postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
            body = body,
            authHeaders = authHeaders,
            webConfig = cfg
        ) ?: return emptyList()
        val items = mutableListOf<YtItem>()
        collectItems(root, items, seen = mutableSetOf())
        return items.filter { !it.isPlaylist }.take(40)
    }

    /**
     * Personalized "For You" feed:
     * - When signed in to YouTube (and not in Music filter), loads the user's
     *   real authenticated YouTube Home feed (`FEwhat_to_watch`) first.
     * - Otherwise (or as fallback), builds a private on-device feed from local
     *   Watch History, Bookmarks, and Recent Searches, topped up with Trending.
     */
    fun exploreForYou(context: Context, musicMode: Boolean = false): List<YtItem> {
        if (!musicMode && WebViewCookieSync.buildYouTubeAuthHeaders(context) != null) {
            val signedInHome = fetchSignedInHomeFeed(context)
            if (signedInHome.size >= 8) {
                return signedInHome
            }
        }
        val history = YouTubeStore.getHistory(context)
        val bookmarks = YouTubeStore.getBookmarks(context)
        val searches = YouTubeStore.getRecentSearches(context)

        val watchedIds = history.take(30).map { it.item.id }.toSet()
        val seedVideos = (history.map { it.item } + bookmarks)
            .filter { !it.isPlaylist && it.id.length == 11 }
            .distinctBy { it.id }
            .take(2)
        val seedQuery = searches.firstOrNull { it.length >= 2 }

        if (seedVideos.isEmpty() && seedQuery == null) {
            return explore(musicMode)
        }

        val tasks = mutableListOf<() -> List<YtItem>>()
        seedVideos.forEach { vid ->
            tasks += { fetchRelated(vid.id).filter { !it.isPlaylist } }
        }
        if (seedQuery != null) {
            tasks += {
                val filter = if (musicMode) SearchFilter.MUSIC else SearchFilter.VIDEOS
                search(seedQuery, filter).filter { !it.isPlaylist }
            }
        }

        val buckets = Array<List<YtItem>>(tasks.size) { emptyList() }
        val pool = Executors.newFixedThreadPool(tasks.size.coerceIn(1, 3))
        val latch = CountDownLatch(tasks.size)
        tasks.forEachIndexed { idx, task ->
            pool.execute {
                try {
                    buckets[idx] = task()
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }
        }
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        } finally {
            pool.shutdownNow()
        }

        // Round-robin interleave across seeds so the feed stays diverse & fresh
        val seen = mutableSetOf<String>()
        val fresh = mutableListOf<YtItem>()
        val maxLen = buckets.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            for (b in buckets) {
                val item = b.getOrNull(i) ?: continue
                if (item.id in watchedIds) continue
                if (seen.add(item.id)) fresh.add(item)
            }
        }

        // Top up with Trending/Music if personalized pool is small
        if (fresh.size < 12) {
            explore(musicMode).forEach { item ->
                if (seen.add(item.id)) fresh.add(item)
            }
        }
        return fresh.take(40)
    }

    fun explorePlaylists(): List<YtItem> {
        val playlists = search("top music hits playlist 2026", SearchFilter.PLAYLISTS)
        if (playlists.isNotEmpty()) return playlists
        return search("popular music playlist", SearchFilter.PLAYLISTS)
    }

    fun fetchPlaylist(playlistId: String, maxItems: Int = 500): PlaylistDetails? {
        val cleanId = playlistId.removePrefix("VL")
        val browseId = "VL$cleanId"
        val body = JSONObject().apply {
            put("context", webContext())
            put("browseId", browseId)
        }
        val root = postInnerTube("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body)

        var title = ""
        var channel = ""
        val items = mutableListOf<YtItem>()
        val seen = mutableSetOf<String>()

        if (root != null) {
            val header = root.optJSONObject("header")
            title = header?.optJSONObject("playlistHeaderRenderer")
                ?.let { extractText(it.optJSONObject("title")) }
                ?.takeIf { it.isNotBlank() }
                ?: header?.optJSONObject("pageHeaderRenderer")
                    ?.optString("pageTitle")
                    ?.takeIf { it.isNotBlank() }
                ?: root.optJSONObject("metadata")
                    ?.optJSONObject("playlistMetadataRenderer")
                    ?.optString("title")
                    .orEmpty()
            channel = header?.optJSONObject("playlistHeaderRenderer")
                ?.let { extractText(it.optJSONObject("ownerText")) }
                .orEmpty()
            collectPlaylistVideos(root, items, seen)

            // Follow InnerTube continuation tokens so playlists with 100+ / 500+
            // songs load completely (YouTube returns ~100 items per page).
            var nextToken = findContinuationToken(root)
            var pages = 0
            while (!nextToken.isNullOrBlank() && items.size < maxItems && pages < 15) {
                pages++
                val contBody = JSONObject().apply {
                    put("context", webContext())
                    put("continuation", nextToken)
                }
                val contRoot = postInnerTube(
                    "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                    contBody
                ) ?: break
                val before = items.size
                collectPlaylistVideos(contRoot, items, seen)
                if (items.size == before) break
                nextToken = findContinuationToken(contRoot)
            }
        }

        // Fallback to /next (handles YouTube Mix / Radio RD... playlists and
        // any playlists where /browse omits items).
        if (items.isEmpty()) {
            val nextBody = JSONObject().apply {
                put("context", webContext())
                put("playlistId", cleanId)
            }
            val nextRoot = postInnerTube("https://www.youtube.com/youtubei/v1/next?prettyPrint=false", nextBody)
            if (nextRoot != null) {
                if (title.isBlank()) {
                    title = nextRoot.optJSONObject("contents")
                        ?.optJSONObject("twoColumnWatchNextResults")
                        ?.optJSONObject("playlist")
                        ?.optJSONObject("playlist")
                        ?.optString("title")
                        .orEmpty()
                }
                collectPlaylistVideos(nextRoot, items, seen)
            }
        }

        if (items.isEmpty()) return null
        val capped = if (items.size > maxItems) items.subList(0, maxItems).toList() else items
        return PlaylistDetails(
            playlistId = cleanId,
            title = title.ifBlank { "Playlist" },
            channel = channel,
            items = capped
        )
    }

    private fun findContinuationToken(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                if (node.has("continuationItemRenderer")) {
                    val renderer = node.optJSONObject("continuationItemRenderer")
                    val token = renderer?.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token")
                        ?.takeIf { it.isNotBlank() }
                    if (token != null) return token
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key in AD_KEYS) continue
                    val found = findContinuationToken(node.opt(key))
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = findContinuationToken(node.opt(i))
                    if (found != null) return found
                }
            }
        }
        return null
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

    /**
     * Fetches Up Next related videos + engagement metadata (channelId, Like
     * status, Subscribe status, comment count, and comments continuation token)
     * in a single `/youtubei/v1/next` call (authenticated when signed in).
     */
    fun fetchWatchData(
        context: Context,
        videoId: String
    ): Pair<List<YtItem>, VideoEngagement?> {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
        val body = JSONObject().apply {
            put("context", webContext())
            put("videoId", videoId)
        }
        val root = postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false",
            body = body,
            authHeaders = authHeaders
        ) ?: return emptyList<YtItem>() to null

        val items = mutableListOf<YtItem>()
        val seen = mutableSetOf(videoId)
        collectItems(root, items, seen)
        val engagement = parseVideoEngagement(root)
        return items.filter { !it.isPlaylist }.take(30) to engagement
    }

    /** Fetches top comments for a video using its `/next` continuation token. */
    fun fetchComments(context: Context, continuationToken: String): List<YtComment> {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
        val body = JSONObject().apply {
            put("context", webContext())
            put("continuation", continuationToken)
        }
        val root = postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false",
            body = body,
            authHeaders = authHeaders
        ) ?: return emptyList()

        val out = mutableListOf<YtComment>()
        val seen = mutableSetOf<String>()
        collectComments(root, out, seen)
        return out.take(40)
    }

    /** Likes or removes like on a video using the user's on-device YouTube session. */
    fun setLike(context: Context, videoId: String, like: Boolean): Boolean {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context) ?: return false
        val endpoint = if (like) {
            "https://www.youtube.com/youtubei/v1/like/like?prettyPrint=false"
        } else {
            "https://www.youtube.com/youtubei/v1/like/removelike?prettyPrint=false"
        }
        val body = JSONObject().apply {
            put("context", webContext())
            put("target", JSONObject().apply { put("videoId", videoId) })
        }
        return postInnerTube(endpoint, body, authHeaders = authHeaders) != null
    }

    /** Subscribes or unsubscribes to a channel using the user's on-device YouTube session. */
    fun setSubscribe(context: Context, channelId: String, subscribe: Boolean): Boolean {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context) ?: return false
        val endpoint = if (subscribe) {
            "https://www.youtube.com/youtubei/v1/subscription/subscribe?prettyPrint=false"
        } else {
            "https://www.youtube.com/youtubei/v1/subscription/unsubscribe?prettyPrint=false"
        }
        val body = JSONObject().apply {
            put("context", webContext())
            put("channelIds", JSONArray().put(channelId))
        }
        return postInnerTube(endpoint, body, authHeaders = authHeaders) != null
    }

    /**
     * Fast InnerTube-only stream resolution optimized for direct HTTP downloads
     * (never spawns Python/yt-dlp):
     * - Tier 1 (No-VPN instant bypass, ~300ms): Anonymous ANDROID_VR -> IOS -> VISIONOS.
     * - Tier 2 (VPN / logged-in fast path, ~0.5-1.5s): Cookie-authenticated TVHTML5
     *   with persistent hidden WebView V8 JIT solver (<10ms per video when warm).
     */
    fun resolveForDownload(context: Context, videoId: String): ResolveResult {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
        val hasCookies = authHeaders != null
        val visitorData by lazy { ensureVisitorData() }
        var tier1Error: String? = null

        if (!hasCookies || !isAnonBotBlockedRecently()) {
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
            if (androidVr.info != null &&
                (androidVr.info.audioStreams.isNotEmpty() || androidVr.info.muxedStreams.isNotEmpty())
            ) {
                lastAnonBotBlockedAtMs = 0L
                return androidVr
            }
            tier1Error = androidVr.error

            if (hasCookies && looksLikeBotBlock(androidVr.error)) {
                lastAnonBotBlockedAtMs = System.currentTimeMillis()
            } else {
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
                if (ios.info != null &&
                    (ios.info.audioStreams.isNotEmpty() || ios.info.muxedStreams.isNotEmpty())
                ) {
                    lastAnonBotBlockedAtMs = 0L
                    return ios
                }
                if (hasCookies && looksLikeBotBlock(ios.error)) {
                    lastAnonBotBlockedAtMs = System.currentTimeMillis()
                } else {
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
                    if (visionOs.info != null &&
                        (visionOs.info.audioStreams.isNotEmpty() || visionOs.info.muxedStreams.isNotEmpty())
                    ) {
                        lastAnonBotBlockedAtMs = 0L
                        return visionOs
                    }
                    if (hasCookies && looksLikeBotBlock(visionOs.error)) {
                        lastAnonBotBlockedAtMs = System.currentTimeMillis()
                    }
                    if (tier1Error.isNullOrBlank()) {
                        tier1Error = ios.error ?: visionOs.error
                    }
                }
            }
        }

        // Tier 2A: Only run inline when V8 is already warm in memory and not cooled down.
        if (authHeaders != null && !isV8FailedRecently()) {
            if (YouTubeJsSolver.isWarmInMemory()) {
                val v8 = resolveViaAuthedV8(
                    context = context,
                    videoId = videoId,
                    visitorData = visitorData,
                    authHeaders = authHeaders,
                    includeHlsFallback = false
                )
                if (v8.info != null &&
                    (v8.info.audioStreams.isNotEmpty() || v8.info.muxedStreams.isNotEmpty())
                ) {
                    return v8
                }
                lastV8FailedAtMs = System.currentTimeMillis()
                Log.d(TAG, "Download Tier 2A V8 failed ($videoId: ${v8.error}), switching to Tier 2B WebView capture")
            } else {
                YouTubeJsSolver.prewarmInBackground(context)
            }
        }

        // Tier 2B: Real logged-in m.youtube.com stream capture (~1.5-2.5s on VPN!)
        if (authHeaders != null || WebViewCookieSync.hasLoggedInYouTubeSession()) {
            val webCap = YouTubeWebStreamExtractor.extract(context, videoId)
            if (webCap.info != null &&
                (webCap.info.audioStreams.isNotEmpty() || webCap.info.muxedStreams.isNotEmpty())
            ) {
                return webCap
            }
        }

        return ResolveResult(null, tier1Error ?: "No direct streams")
    }

    // ---- Stream Resolution (Tier 1 Anonymous -> Tier 2 V8 Authenticated -> Tier 3 yt-dlp) ----

    fun resolveStreams(
        context: Context,
        videoId: String,
        onStatus: ((String) -> Unit)? = null
    ): ResolveResult {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
        val hasCookies = authHeaders != null
        val visitorData by lazy { ensureVisitorData() }
        var tier1Error: String? = null

        // Tier 1 (No-VPN instant bypass, ~300ms): try anonymous fast clients first
        // whenever YouTube didn't just bot-block this IP.
        if (!hasCookies || !isAnonBotBlockedRecently()) {
            onStatus?.invoke("Connecting to YouTube…")
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
            if (visionOs.info != null) {
                lastAnonBotBlockedAtMs = 0L
                return visionOs
            }
            tier1Error = visionOs.error

            if (hasCookies && looksLikeBotBlock(visionOs.error)) {
                lastAnonBotBlockedAtMs = System.currentTimeMillis()
            } else {
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
                if (androidVr.info != null) {
                    lastAnonBotBlockedAtMs = 0L
                    return androidVr
                }

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
                if (ios.info != null) {
                    lastAnonBotBlockedAtMs = 0L
                    return ios
                }
                if (hasCookies && (looksLikeBotBlock(androidVr.error) || looksLikeBotBlock(ios.error))) {
                    lastAnonBotBlockedAtMs = System.currentTimeMillis()
                }
            }
        }

        // Tier 2A (VPN ultra-fast path, ~0.4s): ONLY run inline when V8 is already
        // warm in RAM and hasn't failed on this VPN! Never make the user wait on a
        // cold 5-15s V8 compile — instead pre-warm V8 silently in the background
        // while Tier 2B starts playing immediately.
        if (authHeaders != null && !isV8FailedRecently()) {
            if (YouTubeJsSolver.isWarmInMemory()) {
                onStatus?.invoke("Loading video…")
                val v8 = resolveViaAuthedV8(
                    context = context,
                    videoId = videoId,
                    visitorData = visitorData,
                    authHeaders = authHeaders,
                    includeHlsFallback = true
                )
                if (v8.info != null) {
                    return v8
                }
                lastV8FailedAtMs = System.currentTimeMillis()
                Log.d(TAG, "Tier 2A V8 authed InnerTube failed ($videoId: ${v8.error}), cooling down 15m and switching to WebView capture")
            } else {
                YouTubeJsSolver.prewarmInBackground(context)
            }
        }

        // Tier 2B (Fast logged-in m.youtube.com WebView capture, ~1-2s on VPN):
        // Uses YouTube's official mobile player JS with your real login cookies
        // (with images/CSS/fonts/ads blocked) to hand a clean stream to ExoPlayer.
        if (authHeaders != null || WebViewCookieSync.hasLoggedInYouTubeSession()) {
            onStatus?.invoke("Loading video…")
            val webCap = YouTubeWebStreamExtractor.extract(context, videoId)
            if (webCap.info != null) {
                return webCap
            }
            Log.d(TAG, "Tier 2B WebView capture failed ($videoId: ${webCap.error}), falling back to yt-dlp")
        }

        // Tier 3: Safety-net fallback via bundled yt-dlp + QuickJS + cookies.txt
        onStatus?.invoke(
            if (hasCookies && !hasWarmJsSolverCache(context)) {
                "Preparing YouTube engine (first time only)…"
            } else {
                "Resolving stream…"
            }
        )
        Log.d(TAG, "InnerTube fast clients skipped/failed ($videoId: $tier1Error), trying yt-dlp")
        val ytDlpResult = resolveViaYtDlp(context, videoId)
        if (ytDlpResult.info != null) return ytDlpResult

        val reason = ytDlpResult.error?.takeIf { it.isNotBlank() }
            ?: tier1Error?.takeIf { it.isNotBlank() }
            ?: "Couldn't load stream for this video"
        return ResolveResult(null, reason)
    }

    /**
     * Resolves streams directly via cookie-authenticated `TVHTML5` (Samsung
     * Tizen) + `signatureTimestamp` and decrypts `signatureCipher` + `n`
     * parameters in <10ms using [YouTubeJsSolver] (Android WebView V8 JIT).
     */
    private fun resolveViaAuthedV8(
        context: Context,
        videoId: String,
        visitorData: String?,
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders,
        includeHlsFallback: Boolean
    ): ResolveResult {
        var ready = YouTubeJsSolver.ensureReady(context)
            ?: return ResolveResult(
                null,
                "V8 JS solver unavailable (${YouTubeJsSolver.lastError.ifBlank { "unknown" }})"
            )

        var root = requestTvSamsungPlayer(videoId, visitorData, ready.signatureTimestamp, authHeaders)
        val firstStatus = root?.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        val firstReason = root?.optJSONObject("playabilityStatus")?.optString("reason").orEmpty()
        if (firstStatus != "OK" && firstReason.contains("reload", ignoreCase = true)) {
            // Player signatureTimestamp may have just rotated — refresh once.
            YouTubeJsSolver.invalidate()
            ready = YouTubeJsSolver.ensureReady(context, forceRefresh = true)
                ?: return ResolveResult(null, firstReason.ifBlank { "V8 refresh failed" })
            root = requestTvSamsungPlayer(videoId, visitorData, ready.signatureTimestamp, authHeaders)
        }

        if (root != null) {
            val parsed = parseDecryptedPlayerRoot(videoId, root, TV_SAMSUNG_UA)
            if (parsed.info != null) return parsed
            Log.d(TAG, "TVHTML5 V8 parse note ($videoId): ${parsed.error}")
        }

        // Optional HLS fallback via authenticated WEB (Safari UA) for playback
        if (includeHlsFallback) {
            val webRoot = requestWebSafariPlayer(videoId, visitorData, ready.signatureTimestamp, authHeaders)
            if (webRoot != null) {
                val parsed = parseDecryptedPlayerRoot(videoId, webRoot, WEB_SAFARI_UA)
                if (parsed.info != null) return parsed
            }
        }

        val err = root?.optJSONObject("playabilityStatus")?.optString("reason")
            ?.takeIf { it.isNotBlank() }
            ?: "Authenticated InnerTube returned no playable formats"
        return ResolveResult(null, err)
    }

    private fun requestTvSamsungPlayer(
        videoId: String,
        visitorData: String?,
        signatureTimestamp: Int,
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders
    ): JSONObject? {
        val clientObj = JSONObject().apply {
            put("clientName", "TVHTML5")
            put("clientVersion", TV_SAMSUNG_CLIENT_VERSION)
            put("deviceMake", "Samsung")
            put("deviceModel", "UKS9800")
            put("osName", "Tizen")
            put("osVersion", "2.4.0")
            put("hl", "en")
            put("gl", "US")
            if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
        }
        val body = JSONObject().apply {
            put("context", JSONObject().apply { put("client", clientObj) })
            put("videoId", videoId)
            put("playbackContext", JSONObject().apply {
                put("contentPlaybackContext", JSONObject().apply {
                    put("signatureTimestamp", signatureTimestamp)
                })
            })
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        return postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            body = body,
            userAgent = TV_SAMSUNG_UA,
            clientNameId = "7",
            clientVersion = TV_SAMSUNG_CLIENT_VERSION,
            visitorData = visitorData,
            authHeaders = authHeaders
        )
    }

    private fun requestWebSafariPlayer(
        videoId: String,
        visitorData: String?,
        signatureTimestamp: Int,
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders
    ): JSONObject? {
        val clientObj = JSONObject().apply {
            put("clientName", "WEB")
            put("clientVersion", WEB_CLIENT_VERSION)
            put("hl", "en")
            put("gl", "US")
            if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
        }
        val body = JSONObject().apply {
            put("context", JSONObject().apply { put("client", clientObj) })
            put("videoId", videoId)
            put("playbackContext", JSONObject().apply {
                put("contentPlaybackContext", JSONObject().apply {
                    put("signatureTimestamp", signatureTimestamp)
                })
            })
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        return postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            body = body,
            userAgent = WEB_SAFARI_UA,
            clientNameId = "1",
            clientVersion = WEB_CLIENT_VERSION,
            visitorData = visitorData,
            authHeaders = authHeaders
        )
    }

    private data class PendingCipherFormat(
        val obj: JSONObject,
        val isMuxedDefault: Boolean,
        val baseUrl: String,
        val cipherS: String?,
        val cipherSp: String,
        val rawN: String?
    )

    private fun parseDecryptedPlayerRoot(
        videoId: String,
        root: JSONObject,
        userAgent: String
    ): ResolveResult {
        val playStatus = root.optJSONObject("playabilityStatus")
        val status = playStatus?.optString("status") ?: ""
        if (status != "OK") {
            val reason = playStatus?.optString("reason").orEmpty()
            return ResolveResult(null, reason.ifBlank { status.ifBlank { "UNPLAYABLE" } })
        }

        val streamingData = root.optJSONObject("streamingData")
            ?: return ResolveResult(null, "No streaming data returned")
        val hlsUrl = streamingData.optString("hlsManifestUrl").takeIf { it.startsWith("http") }

        val pending = mutableListOf<PendingCipherFormat>()
        val nParamRegex = Regex("""[?&]n=([^&]+)""")

        fun collectRaw(arr: JSONArray?, isMuxed: Boolean) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                var baseUrl = obj.optString("url")
                var cipherS: String? = null
                var cipherSp = "signature"
                if (!baseUrl.startsWith("http")) {
                    val cipher = obj.optString("signatureCipher").ifBlank { obj.optString("cipher") }
                    if (cipher.isBlank()) continue
                    val parts = parseQueryParams(cipher)
                    baseUrl = parts["url"].orEmpty()
                    cipherS = parts["s"]
                    cipherSp = parts["sp"].orEmpty().ifBlank { "signature" }
                    if (!baseUrl.startsWith("http") || cipherS.isNullOrBlank()) continue
                }
                val rawN = nParamRegex.find(baseUrl)?.groupValues?.get(1)?.let { urlDecode(it) }
                pending.add(PendingCipherFormat(obj, isMuxed, baseUrl, cipherS, cipherSp, rawN))
            }
        }

        collectRaw(streamingData.optJSONArray("formats"), isMuxed = true)
        collectRaw(streamingData.optJSONArray("adaptiveFormats"), isMuxed = false)

        val needSigs = pending.mapNotNull { it.cipherS }.distinct()
        val needNs = pending.mapNotNull { it.rawN }.distinct()
        val solved = if (needSigs.isNotEmpty() || needNs.isNotEmpty()) {
            YouTubeJsSolver.solve(needSigs, needNs)
        } else {
            YouTubeJsSolver.SolveResult(emptyMap(), emptyMap())
        }

        val videoStreams = mutableListOf<StreamFormat>()
        val audioStreams = mutableListOf<StreamFormat>()
        val muxedStreams = mutableListOf<StreamFormat>()

        if (solved != null) {
            val replaceNRegex = Regex("""([?&]n=)[^&]+""")
            for (p in pending) {
                var finalUrl = p.baseUrl
                if (p.rawN != null) {
                    val solvedN = solved.ns[p.rawN]
                    if (!solvedN.isNullOrBlank()) {
                        val encN = urlEncode(solvedN)
                        val match = replaceNRegex.find(finalUrl)
                        if (match != null) {
                            finalUrl = finalUrl.replaceRange(match.range, match.groupValues[1] + encN)
                        }
                    }
                }
                if (p.cipherS != null) {
                    val solvedSig = solved.sigs[p.cipherS] ?: continue
                    val sep = if ('?' in finalUrl) "&" else "?"
                    finalUrl = "$finalUrl$sep${p.cipherSp}=${urlEncode(solvedSig)}"
                }

                val obj = p.obj
                val mime = obj.optString("mimeType")
                val itag = obj.optInt("itag", 0)
                val height = obj.optInt("height", 0)
                val bitrate = obj.optInt("bitrate", 0)
                val qualityLabel = obj.optString("qualityLabel", if (height > 0) "${height}p" else "")
                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")
                val fmt = StreamFormat(
                    itag = itag,
                    url = finalUrl,
                    mimeType = mime,
                    qualityLabel = qualityLabel,
                    height = height,
                    bitrate = bitrate,
                    isAudioOnly = isAudio,
                    isVideoOnly = isVideo && !p.isMuxedDefault,
                    isMuxed = p.isMuxedDefault
                )
                when {
                    p.isMuxedDefault -> muxedStreams.add(fmt)
                    isAudio -> audioStreams.add(fmt)
                    isVideo -> videoStreams.add(fmt)
                }
            }
        }

        if (hlsUrl == null && muxedStreams.isEmpty() && (videoStreams.isEmpty() || audioStreams.isEmpty())) {
            return ResolveResult(null, "No playable stream URLs after V8 decryption")
        }

        val details = root.optJSONObject("videoDetails")
        val title = details?.optString("title").orEmpty().ifBlank { "YouTube Video" }
        val channel = details?.optString("author").orEmpty()
        val durationSec = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
        val views = formatViews(details?.optString("viewCount")?.toLongOrNull())
        val description = details?.optString("shortDescription").orEmpty()
        val thumb = pickThumb(details?.optJSONObject("thumbnail"))
            .ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
        val captions = parseInnerTubeCaptions(root)

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
                userAgent = userAgent,
                captionTracks = captions
            )
        )
    }

    private fun parseQueryParams(qs: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in qs.split('&')) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = urlDecode(pair.substring(0, eq))
                val v = urlDecode(pair.substring(eq + 1))
                out[k] = v
            }
        }
        return out
    }

    private fun urlDecode(s: String): String =
        try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun urlEncode(s: String): String =
        try { java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20") } catch (_: Exception) { s }

    private fun ensureVisitorData(): String? {
        cachedVisitorData?.takeIf { it.isNotBlank() }?.let { return it }
        val fromCfg = YouTubeSessionConfig.ensureWebConfig().visitorData
        if (!fromCfg.isNullOrBlank()) {
            cachedVisitorData = fromCfg
            return fromCfg
        }
        val body = JSONObject().apply { put("context", webContext()) }
        postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false",
            body = body,
            useAnonymousClient = true
        )
        return cachedVisitorData
    }

    private fun resolveViaClient(
        videoId: String,
        clientName: String,
        clientVersion: String,
        clientNameId: String,
        userAgent: String,
        visitorData: String?,
        extraClientFields: Map<String, Any>,
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders? = null
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
            visitorData = visitorData,
            authHeaders = authHeaders,
            useAnonymousClient = (authHeaders == null)
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
        val captions = parseInnerTubeCaptions(root)

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
                userAgent = userAgent,
                captionTracks = captions
            )
        )
    }

    private fun parseInnerTubeCaptions(root: JSONObject): List<CaptionTrack> {
        val arr = root.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")
            ?: return emptyList()
        val out = mutableListOf<CaptionTrack>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val baseUrl = c.optString("baseUrl")
            if (!baseUrl.startsWith("http")) continue
            val vttUrl = if (baseUrl.contains("fmt=")) {
                baseUrl.replace(Regex("fmt=[^&]+"), "fmt=vtt")
            } else {
                "$baseUrl&fmt=vtt"
            }
            val lang = c.optString("languageCode").ifBlank { "und" }
            val label = extractText(c.optJSONObject("name")).ifBlank { lang }
            val isAuto = c.optString("kind") == "asr"
            out.add(CaptionTrack(langCode = lang, label = label, vttUrl = vttUrl, isAuto = isAuto))
        }
        // Prefer manual tracks first, then English tracks first
        return out.sortedWith(
            compareBy<CaptionTrack> { it.isAuto }
                .thenByDescending { it.langCode.startsWith("en", ignoreCase = true) }
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

            val captions = parseYtDlpCaptions(root)

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
                    userAgent = detectedUa,
                    captionTracks = captions
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing yt-dlp JSON", e)
            ResolveResult(null, e.message ?: "Stream parse failed")
        }
    }

    private fun parseYtDlpCaptions(root: JSONObject): List<CaptionTrack> {
        val out = mutableListOf<CaptionTrack>()
        fun collect(obj: JSONObject?, isAuto: Boolean) {
            if (obj == null) return
            val keys = obj.keys()
            while (keys.hasNext()) {
                val lang = keys.next()
                val arr = obj.optJSONArray(lang) ?: continue
                for (i in 0 until arr.length()) {
                    val fmt = arr.optJSONObject(i) ?: continue
                    if (fmt.optString("ext") == "vtt") {
                        val u = fmt.optString("url")
                        if (u.startsWith("http")) {
                            val name = fmt.optString("name").ifBlank { lang }
                            out.add(CaptionTrack(lang, name, u, isAuto))
                            break
                        }
                    }
                }
            }
        }
        collect(root.optJSONObject("subtitles"), isAuto = false)
        collect(root.optJSONObject("automatic_captions"), isAuto = true)
        return out.sortedWith(
            compareBy<CaptionTrack> { it.isAuto }
                .thenByDescending { it.langCode.startsWith("en", ignoreCase = true) }
        )
    }

    // ---- HTTP & JSON Traversal Helpers ----

    private fun webContext(
        config: YouTubeSessionConfig.WebClientConfig = YouTubeSessionConfig.ensureWebConfig()
    ): JSONObject = JSONObject().apply {
        put(
            "client",
            JSONObject().apply {
                put("clientName", "WEB")
                put("clientVersion", config.clientVersion)
                put("hl", config.hl)
                put("gl", config.gl)
                put("originalUrl", "https://www.youtube.com")
                put("platform", "DESKTOP")
                config.visitorData?.takeIf { it.isNotBlank() }?.let {
                    put("visitorData", it)
                }
            }
        )
        put("user", JSONObject().apply { put("lockedSafetyMode", false) })
        put("request", JSONObject().apply { put("useSsl", true) })
    }

    private fun postInnerTube(
        url: String,
        body: JSONObject,
        userAgent: String = WEB_UA,
        clientNameId: String = "1",
        clientVersion: String = WEB_CLIENT_VERSION,
        visitorData: String? = cachedVisitorData,
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders? = null,
        webConfig: YouTubeSessionConfig.WebClientConfig? = null,
        useAnonymousClient: Boolean = false
    ): JSONObject? {
        return try {
            val isWebDesktop = !useAnonymousClient && clientNameId == "1"
            val cfg = if (isWebDesktop) {
                webConfig ?: YouTubeSessionConfig.ensureWebConfig()
            } else {
                null
            }
            val effectiveUrl = if (cfg != null && !cfg.apiKey.isNullOrBlank() && !url.contains("key=")) {
                val sep = if ('?' in url) "&" else "?"
                "$url${sep}key=${cfg.apiKey}"
            } else {
                url
            }

            val reqBuilder = Request.Builder()
                .url(effectiveUrl)
                .header("User-Agent", userAgent)
                .header("Origin", "https://www.youtube.com")

            if (cfg != null) {
                YouTubeSessionConfig.injectWebHeaders(reqBuilder, cfg)
                if (!visitorData.isNullOrBlank() && cfg.visitorData.isNullOrBlank()) {
                    reqBuilder.header("X-Goog-Visitor-Id", visitorData)
                }
            } else {
                reqBuilder
                    .header("X-YouTube-Client-Name", clientNameId)
                    .header("X-YouTube-Client-Version", clientVersion)
                if (!visitorData.isNullOrBlank()) {
                    reqBuilder.header("X-Goog-Visitor-Id", visitorData)
                }
            }

            if (authHeaders != null) {
                // When live WebView CookieManager has the session, sharedOkHttpClient's
                // CookieJar automatically sends cookies and persists Set-Cookie updates.
                // Only attach the explicit Cookie header when falling back to cookies.txt.
                if (useAnonymousClient || !WebViewCookieSync.hasLoggedInYouTubeSession()) {
                    reqBuilder.header("Cookie", authHeaders.cookieHeader)
                }
                reqBuilder.header("Authorization", authHeaders.authorizationHeader)
                reqBuilder.header("X-Origin", "https://www.youtube.com")
                reqBuilder.header("X-Goog-AuthUser", "0")
            }

            val req = reqBuilder
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            val httpClient = if (useAnonymousClient) anonClient else client
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val txt = resp.body?.string() ?: return null
                val json = JSONObject(txt)
                val vd = json.optJSONObject("responseContext")?.optString("visitorData")
                if (!vd.isNullOrBlank()) {
                    cachedVisitorData = vd
                    YouTubeSessionConfig.updateVisitorData(vd)
                }
                json
            }
        } catch (e: Exception) {
            Log.w(TAG, "InnerTube request failed: ${e.message}")
            null
        }
    }

    private fun parseVideoEngagement(root: JSONObject): VideoEngagement? {
        var channelId: String? = null
        var channelName = ""
        var isSubscribed = false
        var isLiked = false
        var likeCount = ""
        var commentCount = ""
        var commentsToken: String? = null

        fun scan(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (node.has("videoSecondaryInfoRenderer")) {
                        val sec = node.optJSONObject("videoSecondaryInfoRenderer")
                        val owner = sec?.optJSONObject("owner")?.optJSONObject("videoOwnerRenderer")
                        if (owner != null) {
                            channelName = extractText(owner.optJSONObject("title")).ifBlank { channelName }
                            val cid = owner.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("browseEndpoint")
                                ?.optString("browseId")
                                ?.takeIf { it.startsWith("UC") }
                            if (cid != null) channelId = cid
                        }
                        val sub = sec?.optJSONObject("subscribeButton")?.optJSONObject("subscribeButtonRenderer")
                        if (sub != null) {
                            isSubscribed = sub.optBoolean("subscribed", false)
                            val cid = sub.optString("channelId").takeIf { it.startsWith("UC") }
                            if (cid != null) channelId = cid
                        }
                    }
                    if (node.has("segmentedLikeDislikeButtonViewModel")) {
                        val seg = node.optJSONObject("segmentedLikeDislikeButtonViewModel")
                        val likeVm = seg?.optJSONObject("likeButtonViewModel")
                            ?.optJSONObject("likeButtonViewModel")
                        val toggle = likeVm?.optJSONObject("toggleButtonViewModel")
                            ?.optJSONObject("toggleButtonViewModel")
                        val toggled = toggle?.optBoolean("isTogglingDisabled", false) == false
                        val defBtn = toggle?.optJSONObject("defaultButtonViewModel")
                            ?.optJSONObject("buttonViewModel")
                        val title = defBtn?.optString("title").orEmpty()
                        if (title.isNotBlank() && likeCount.isBlank()) {
                            likeCount = title
                        }
                        val statusEntity = likeVm?.optString("likeStatusEntityKey").orEmpty()
                        if (toggled && statusEntity.isNotBlank()) {
                            // Resolved below from frameworkUpdates if present
                        }
                    }
                    if (node.has("likeStatusEntity")) {
                        val status = node.optJSONObject("likeStatusEntity")?.optString("likeStatus")
                        if (status == "LIKE") isLiked = true
                    }
                    if (node.has("subscriptionStateEntity")) {
                        val subEntity = node.optJSONObject("subscriptionStateEntity")
                        if (subEntity != null && subEntity.has("subscribed")) {
                            isSubscribed = subEntity.optBoolean("subscribed", isSubscribed)
                        }
                    }
                    if (node.has("itemSectionRenderer")) {
                        val sec = node.optJSONObject("itemSectionRenderer")
                        val sectionId = sec?.optString("sectionIdentifier").orEmpty()
                        val targetId = sec?.optString("targetId").orEmpty()
                        if (sectionId == "comment-item-section" || targetId == "comments-section") {
                            val token = findContinuationToken(sec)
                            if (!token.isNullOrBlank()) commentsToken = token
                        }
                    }
                    if (node.has("commentsEntryPointHeaderRenderer")) {
                        val hdr = node.optJSONObject("commentsEntryPointHeaderRenderer")
                        val c = extractText(hdr?.optJSONObject("commentCount"))
                        if (c.isNotBlank()) commentCount = c
                    }
                    if (node.has("engagementPanelSectionListRenderer")) {
                        val panel = node.optJSONObject("engagementPanelSectionListRenderer")
                        if (panel?.optString("panelIdentifier") == "engagement-panel-comments-section") {
                            val hdr = panel.optJSONObject("header")
                                ?.optJSONObject("engagementPanelTitleHeaderRenderer")
                            val c = extractText(hdr?.optJSONObject("contextualInfo"))
                            if (c.isNotBlank() && commentCount.isBlank()) commentCount = c
                            val token = findContinuationToken(panel)
                            if (!token.isNullOrBlank() && commentsToken == null) commentsToken = token
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k in AD_KEYS) continue
                        scan(node.opt(k))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        scan(node.opt(i))
                    }
                }
            }
        }

        scan(root)
        return VideoEngagement(
            channelId = channelId,
            channelName = channelName,
            isSubscribed = isSubscribed,
            isLiked = isLiked,
            likeCount = likeCount,
            commentCount = commentCount,
            commentsToken = commentsToken
        )
    }

    private fun collectComments(
        node: Any?,
        out: MutableList<YtComment>,
        seen: MutableSet<String>
    ) {
        when (node) {
            is JSONObject -> {
                // Modern YouTube commentEntityPayload (2024-2026 InnerTube format)
                if (node.has("commentEntityPayload")) {
                    val p = node.optJSONObject("commentEntityPayload")
                    val props = p?.optJSONObject("properties")
                    val authorObj = p?.optJSONObject("author")
                    val toolbar = p?.optJSONObject("toolbar")
                    val text = props?.optJSONObject("content")?.optString("content").orEmpty().trim()
                    val author = authorObj?.optString("displayName").orEmpty().trim()
                    val timeText = props?.optString("publishedTime").orEmpty().trim()
                    val likes = toolbar?.optString("likeCountNotliked").orEmpty().trim()
                    val key = props?.optString("commentId").orEmpty().ifBlank { "$author|$text" }
                    if (text.isNotBlank() && seen.add(key)) {
                        out.add(
                            YtComment(
                                author = author.ifBlank { "YouTube User" },
                                text = text,
                                timeText = timeText,
                                likes = likes
                            )
                        )
                    }
                }
                // Legacy commentRenderer fallback
                if (node.has("commentRenderer")) {
                    val c = node.optJSONObject("commentRenderer")
                    val text = extractText(c?.optJSONObject("contentText"))
                    val author = extractText(c?.optJSONObject("authorText"))
                    val timeText = extractText(c?.optJSONObject("publishedTimeText"))
                    val likes = extractText(c?.optJSONObject("voteCount"))
                    val key = c?.optString("commentId").orEmpty().ifBlank { "$author|$text" }
                    if (text.isNotBlank() && seen.add(key)) {
                        out.add(
                            YtComment(
                                author = author.ifBlank { "YouTube User" },
                                text = text,
                                timeText = timeText,
                                likes = likes
                            )
                        )
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k in AD_KEYS) continue
                    collectComments(node.opt(k), out, seen)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectComments(node.opt(i), out, seen)
                }
            }
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
                    } else if (key == "lockupViewModel") {
                        parseLockupViewModel(value as? JSONObject)?.let { item ->
                            if (!item.isPlaylist && seen.add(item.id)) out.add(item)
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
        val thumbModel = imgView?.optJSONObject("collectionThumbnailViewModel")
            ?.optJSONObject("primaryThumbnail")
            ?.optJSONObject("thumbnailViewModel")
            ?: imgView?.optJSONObject("thumbnailViewModel")
        val sources = thumbModel?.optJSONObject("image")?.optJSONArray("sources")
        var badgeText = ""
        val overlays = thumbModel?.optJSONArray("overlays")
        if (overlays != null) {
            for (i in 0 until overlays.length()) {
                val badges = overlays.optJSONObject(i)
                    ?.optJSONObject("thumbnailOverlayBadgeViewModel")
                    ?.optJSONArray("thumbnailBadges")
                    ?: continue
                val t = badges.optJSONObject(0)
                    ?.optJSONObject("thumbnailBadgeViewModel")
                    ?.optString("text")
                    .orEmpty()
                if (t.isNotBlank()) {
                    badgeText = t
                    break
                }
            }
        }
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
            duration = if (!isPlaylist) badgeText else "",
            thumbnailUrl = thumb,
            isPlaylist = isPlaylist,
            videoCount = if (isPlaylist) badgeText.ifBlank { "Playlist" } else ""
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
