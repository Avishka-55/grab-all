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

    private const val VISIONOS_CLIENT_VERSION = "1.03"
    private const val VISIONOS_UA =
        "com.google.visionosyoutube/1.03 (RealityDevice17,1; U; CPU visionOS 26_6_1 like Mac OS X; en_US) gzip"

    private const val ANDROID_VR_CLIENT_VERSION = "1.61.48"
    private const val ANDROID_VR_UA =
        "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    private const val IOS_CLIENT_VERSION = "21.26.4"
    private const val IOS_UA =
        "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

    private const val TV_SAMSUNG_CLIENT_VERSION = "5.20260707"
    private const val TV_SAMSUNG_UA =
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1"

    private const val WEB_REMIX_CLIENT_VERSION = "1.20260707.12.00"

    private val MUSIC_CHIP_LABELS = setOf(
        "music", "música", "musica", "音乐", "音樂", "موسيقى", "संगीत", "музыка", "musique", "musik"
    )

    private const val WEB_SAFARI_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15"

    /** Resets cached session hints after account sign-in/out or network changes. */
    fun resetAnonBotBlockHint() {
        cachedVisitorData = null
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
        val commentsToken: String?,
        val subscriberCount: String = "",
        val channelAvatarUrl: String = "",
        val topCommentPreview: String = ""
    )

    data class CommentsPage(
        val comments: List<YtComment>,
        val continuationToken: String?,
        val createCommentParams: String? = null
    )

    enum class ChannelTab(val params: String) {
        VIDEOS("EgZ2aWRlb3PyBgQKAjoA"),
        PLAYLISTS("EglwbGF5bGlzdHPyBgQKAkIA")
    }

    data class ChannelPage(
        val channelId: String,
        val title: String,
        val handle: String,
        val subscriberCount: String,
        val avatarUrl: String,
        val isSubscribed: Boolean,
        val items: List<YtItem>,
        val continuationToken: String?
    )

    data class YtComment(
        val author: String,
        val text: String,
        val timeText: String,
        val likes: String
    )

    data class FeedChip(
        val label: String,
        val continuationToken: String?,
        val selected: Boolean = false
    )

    data class FeedPage(
        val items: List<YtItem>,
        val continuationToken: String? = null,
        val chips: List<FeedChip> = emptyList()
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

    fun search(query: String, filter: SearchFilter = SearchFilter.ALL): List<YtItem> =
        searchPaged(query, filter, continuationToken = null).items

    /**
     * Paginated InnerTube search supporting continuation tokens for infinite scroll.
     */
    fun searchPaged(
        query: String,
        filter: SearchFilter = SearchFilter.ALL,
        continuationToken: String? = null
    ): FeedPage {
        val effectiveQuery = if (filter == SearchFilter.MUSIC && !query.contains("music", true)) {
            "$query music"
        } else {
            query
        }
        val body = JSONObject().apply {
            put("context", webContext())
            if (!continuationToken.isNullOrBlank()) {
                put("continuation", continuationToken)
            } else {
                put("query", effectiveQuery)
                if (filter.params != null) {
                    put("params", filter.params)
                }
            }
        }
        val root = postInnerTube("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", body)
            ?: return FeedPage(emptyList(), null)
        val items = mutableListOf<YtItem>()
        collectItems(root, items, seen = mutableSetOf())
        val filtered = when (filter) {
            SearchFilter.PLAYLISTS -> items.filter { it.isPlaylist }
            SearchFilter.VIDEOS -> items.filter { !it.isPlaylist }
            else -> items
        }
        val next = findFeedContinuationToken(root)
            ?.takeIf { it != continuationToken }
        return FeedPage(items = filtered, continuationToken = next)
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
     * Fetches one page of the user's real personalized YouTube Home feed
     * (`FEwhat_to_watch`) when signed in, supporting InnerTube continuation
     * tokens so the For You feed can keep loading more recommendations as the
     * user scrolls.
     *
     * On initial load (`continuationToken == null`), if YouTube's first page
     * returns fewer than 24 items and provides a continuation token, eagerly
     * fetches one extra page so the feed immediately opens full and rich.
     */
    fun fetchSignedInHomePage(
        context: Context,
        continuationToken: String? = null,
        forceRefreshConfig: Boolean = false
    ): FeedPage {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
            ?: return FeedPage(emptyList(), null)
        val cfg = YouTubeSessionConfig.ensureWebConfig(forceRefresh = forceRefreshConfig)

        fun requestPage(token: String?): JSONObject? {
            val body = JSONObject().apply {
                put("context", webContext(cfg))
                if (token != null) {
                    put("continuation", token)
                } else {
                    put("browseId", "FEwhat_to_watch")
                }
            }
            return postInnerTube(
                url = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                body = body,
                authHeaders = authHeaders,
                webConfig = cfg
            )
        }

        val collected = mutableListOf<YtItem>()
        val seen = mutableSetOf<String>()
        // For initial load & pull-to-refresh (continuationToken == null), fetch
        // live ytInitialData directly from https://www.youtube.com with the user's
        // session cookies first — matching the official YouTube homepage (fresh
        // videos, playlists, mixes, and randomized refresh every time!), then
        // falling back to InnerTube /browse (FEwhat_to_watch) if needed.
        var root = if (continuationToken == null) {
            fetchHomeInitialDataFromWebPage(cfg, authHeaders) ?: requestPage(null)
        } else {
            requestPage(continuationToken)
        }
        if (root == null) return FeedPage(emptyList(), null)

        val chips = if (continuationToken == null) extractHomeFilterChips(root) else emptyList()
        collectItems(root, collected, seen)
        var nextToken = findFeedContinuationToken(root)

        // Initial load: if the first browse response had only a small batch of
        // items (common on desktop home grids with Shorts/shelves), pull up to
        // 2 more continuation pages immediately so the feed starts rich (~30-50+ items).
        if (continuationToken == null) {
            var extraPages = 0
            while (collected.size < 28 &&
                !nextToken.isNullOrBlank() &&
                extraPages < 2
            ) {
                extraPages++
                val pageRoot = requestPage(nextToken) ?: break
                val before = collected.size
                collectItems(pageRoot, collected, seen)
                val next = findFeedContinuationToken(pageRoot)
                nextToken = if (next != nextToken) next else null
                if (collected.size == before) break
            }
        }

        return FeedPage(items = collected, continuationToken = nextToken, chips = chips)
    }

    /**
     * Fetches one page of the signed-in user's Subscriptions feed
     * (`FEsubscriptions`) with continuation paging.
     */
    fun fetchSubscriptionsPage(
        context: Context,
        continuationToken: String? = null
    ): FeedPage {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
            ?: return FeedPage(emptyList(), null)
        val cfg = YouTubeSessionConfig.ensureWebConfig()
        val body = JSONObject().apply {
            put("context", webContext(cfg))
            if (!continuationToken.isNullOrBlank()) {
                put("continuation", continuationToken)
            } else {
                put("browseId", "FEsubscriptions")
            }
        }
        val root = postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
            body = body,
            authHeaders = authHeaders,
            webConfig = cfg
        ) ?: return FeedPage(emptyList(), null)

        val collected = mutableListOf<YtItem>()
        collectItems(root, collected, seen = mutableSetOf())
        val next = findFeedContinuationToken(root)?.takeIf { it != continuationToken }
        return FeedPage(items = collected.filter { !it.isPlaylist }, continuationToken = next)
    }

    /**
     * Fetches one page of the signed-in user's YouTube Watch Later playlist
     * (`VLWL`) with continuation paging.
     */
    fun fetchWatchLaterPage(
        context: Context,
        continuationToken: String? = null
    ): FeedPage {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
            ?: return FeedPage(emptyList(), null)
        val cfg = YouTubeSessionConfig.ensureWebConfig()
        val body = JSONObject().apply {
            put("context", webContext(cfg))
            if (!continuationToken.isNullOrBlank()) {
                put("continuation", continuationToken)
            } else {
                put("browseId", "VLWL")
            }
        }
        val root = postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
            body = body,
            authHeaders = authHeaders,
            webConfig = cfg
        ) ?: return FeedPage(emptyList(), null)

        val collected = mutableListOf<YtItem>()
        val seen = mutableSetOf<String>()
        collectPlaylistVideos(root, collected, seen)
        if (collected.isEmpty()) {
            collectItems(root, collected, seen)
        }
        val next = findContinuationToken(root)?.takeIf { it != continuationToken }
        return FeedPage(items = collected.filter { !it.isPlaylist }, continuationToken = next)
    }

    /** Signed-in "Liked videos" list (`VLLL`) with continuation paging. */
    fun fetchLikedVideosPage(context: Context, continuationToken: String? = null): FeedPage =
        fetchAccountListPage(context, "VLLL", continuationToken, videosOnly = true)

    /** Signed-in library playlists (`FEplaylist_aggregation`) with continuation paging. */
    fun fetchMyPlaylistsPage(context: Context, continuationToken: String? = null): FeedPage =
        fetchAccountListPage(context, "FEplaylist_aggregation", continuationToken, videosOnly = false)

    private fun fetchAccountListPage(
        context: Context,
        browseId: String,
        continuationToken: String?,
        videosOnly: Boolean
    ): FeedPage {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
            ?: return FeedPage(emptyList(), null)
        val cfg = YouTubeSessionConfig.ensureWebConfig()
        val body = JSONObject().apply {
            put("context", webContext(cfg))
            if (!continuationToken.isNullOrBlank()) {
                put("continuation", continuationToken)
            } else {
                put("browseId", browseId)
            }
        }
        val root = postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
            body = body,
            authHeaders = authHeaders,
            webConfig = cfg
        ) ?: return FeedPage(emptyList(), null)

        val collected = mutableListOf<YtItem>()
        val seen = mutableSetOf<String>()
        if (videosOnly) {
            collectPlaylistVideos(root, collected, seen)
        }
        if (collected.isEmpty()) {
            collectItems(root, collected, seen)
        }
        val next = findContinuationToken(root)?.takeIf { it != continuationToken }
        val items = if (videosOnly) collected.filter { !it.isPlaylist } else collected.filter { it.isPlaylist }
        return FeedPage(items = items, continuationToken = next)
    }

    /**
     * Fetches real personalized YouTube Music recommendations:
     * 1. If [continuationToken] is provided, pages directly via `/youtubei/v1/browse`.
     * 2. When signed in, finds YouTube's real "Music" chip from the home
     *    `feedFilterChipBarRenderer` (or uses [knownMusicChipToken]) and fetches
     *    its personalized feed with continuation paging.
     * 3. Falls back to YouTube Music (`music.youtube.com` `WEB_REMIX` `FEmusic_home`),
     *    and finally to a Music search only if both endpoints return nothing.
     */
    fun fetchPersonalizedMusicPage(
        context: Context,
        continuationToken: String? = null,
        knownMusicChipToken: String? = null
    ): FeedPage {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
        if (!continuationToken.isNullOrBlank() && authHeaders != null) {
            return fetchSignedInHomePage(context, continuationToken = continuationToken)
        }

        if (authHeaders != null) {
            val chipToken = knownMusicChipToken?.takeIf { it.isNotBlank() } ?: run {
                val home = fetchSignedInHomePage(context, continuationToken = null)
                home.chips.firstOrNull { it.label.trim().lowercase() in MUSIC_CHIP_LABELS }?.continuationToken
            }
            if (!chipToken.isNullOrBlank()) {
                val musicPage = fetchSignedInHomePage(context, continuationToken = chipToken)
                if (musicPage.items.isNotEmpty()) {
                    return musicPage
                }
            }
        }

        // Fallback: YouTube Music WEB_REMIX FEmusic_home (real charts/mixes/songs!)
        val ytMusic = fetchYouTubeMusicHome(authHeaders)
        if (ytMusic.isNotEmpty()) {
            return FeedPage(items = ytMusic, continuationToken = null)
        }

        return FeedPage(items = explore(musicMode = true), continuationToken = null)
    }

    private fun fetchYouTubeMusicHome(
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders?
    ): List<YtItem> {
        val cfg = YouTubeSessionConfig.ensureWebConfig()
        val clientObj = JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", WEB_REMIX_CLIENT_VERSION)
            put("hl", cfg.hl)
            put("gl", cfg.gl)
            cfg.visitorData?.takeIf { it.isNotBlank() }?.let { put("visitorData", it) }
        }
        val body = JSONObject().apply {
            put("context", JSONObject().apply { put("client", clientObj) })
            put("browseId", "FEmusic_home")
        }
        val url = if (!cfg.apiKey.isNullOrBlank()) {
            "https://music.youtube.com/youtubei/v1/browse?key=${cfg.apiKey}&prettyPrint=false"
        } else {
            "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        }
        val root = postInnerTube(
            url = url,
            body = body,
            userAgent = WEB_UA,
            clientNameId = "67",
            clientVersion = WEB_REMIX_CLIENT_VERSION,
            visitorData = cfg.visitorData,
            authHeaders = authHeaders,
            webConfig = null
        ) ?: return emptyList()

        val out = mutableListOf<YtItem>()
        val seen = mutableSetOf<String>()
        collectMusicItems(root, out, seen)
        return out.take(40)
    }

    private fun collectMusicItems(node: Any?, out: MutableList<YtItem>, seen: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                if (node.has("musicResponsiveListItemRenderer")) {
                    parseMusicResponsiveItem(node.optJSONObject("musicResponsiveListItemRenderer"))?.let {
                        if (seen.add(it.id)) out.add(it)
                    }
                }
                if (node.has("musicTwoRowItemRenderer")) {
                    parseMusicTwoRowItem(node.optJSONObject("musicTwoRowItemRenderer"))?.let {
                        if (!it.isPlaylist && seen.add(it.id)) out.add(it)
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k in AD_KEYS) continue
                    collectMusicItems(node.opt(k), out, seen)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectMusicItems(node.opt(i), out, seen)
                }
            }
        }
    }

    private fun parseMusicResponsiveItem(obj: JSONObject?): YtItem? {
        if (obj == null) return null
        val vid = obj.optJSONObject("playlistItemData")?.optString("videoId")
            ?.takeIf { it.length == 11 }
            ?: obj.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                ?.takeIf { it.length == 11 }
            ?: return null
        val cols = obj.optJSONArray("flexColumns") ?: return null
        val title = extractText(
            cols.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
        )
        if (title.isBlank()) return null
        val sub = extractText(
            cols.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
        )
        val thumb = pickThumb(
            obj.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
        ).ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" }
        return YtItem(
            id = vid,
            title = title,
            channel = sub,
            thumbnailUrl = thumb,
            isPlaylist = false
        )
    }

    private fun parseMusicTwoRowItem(obj: JSONObject?): YtItem? {
        if (obj == null) return null
        val vid = obj.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
            ?.takeIf { it.length == 11 }
            ?: return null
        val title = extractText(obj.optJSONObject("title"))
        if (title.isBlank()) return null
        val sub = extractText(obj.optJSONObject("subtitle"))
        val thumb = pickThumb(
            obj.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
        ).ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" }
        return YtItem(
            id = vid,
            title = title,
            channel = sub,
            thumbnailUrl = thumb,
            isPlaylist = false
        )
    }

    /**
     * Extracts YouTube's personalized Home topic filter chips from
     * `feedFilterChipBarRenderer` (e.g. All, Music, Gaming, Live, Mixes)
     * along with each chip's continuation token.
     */
    private fun extractHomeFilterChips(root: JSONObject): List<FeedChip> {
        val out = mutableListOf<FeedChip>()
        val seenLabels = mutableSetOf<String>()

        fun scan(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (node.has("feedFilterChipBarRenderer")) {
                        val bar = node.optJSONObject("feedFilterChipBarRenderer")
                        val contents = bar?.optJSONArray("contents")
                        if (contents != null) {
                            for (i in 0 until contents.length()) {
                                val chip = contents.optJSONObject(i)
                                    ?.optJSONObject("chipCloudChipRenderer")
                                    ?: continue
                                val label = extractText(chip.optJSONObject("text")).trim()
                                if (label.isBlank() || !seenLabels.add(label)) continue
                                val selected = chip.optBoolean("isSelected", false)
                                val token = chip.optJSONObject("navigationEndpoint")
                                    ?.optJSONObject("continuationCommand")
                                    ?.optString("token")
                                    ?.takeIf { it.isNotBlank() }
                                // Keep "All" (selected default) or any chip carrying a continuation token
                                if (selected || token != null) {
                                    out.add(FeedChip(label = label, continuationToken = token, selected = selected))
                                }
                            }
                        }
                        return
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k in AD_KEYS) continue
                        scan(node.opt(k))
                        if (out.isNotEmpty()) return
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        scan(node.opt(i))
                        if (out.isNotEmpty()) return
                    }
                }
            }
        }

        scan(root)
        return out.take(14)
    }

    /** Scrapes live `ytInitialData` JSON directly from signed-in `https://www.youtube.com`. */
    private fun fetchHomeInitialDataFromWebPage(
        cfg: YouTubeSessionConfig.WebClientConfig,
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders? = null
    ): JSONObject? {
        return try {
            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/?hl=${cfg.hl}&persist_hl=1")
                .header("User-Agent", WEB_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "${cfg.hl}-${cfg.gl},${cfg.hl};q=0.9")
                .header("Cache-Control", "no-cache")
                .get()
            if (authHeaders != null) {
                reqBuilder.header("Cookie", authHeaders.cookieHeader)
            }
            val httpClient = if (authHeaders != null) anonClient else client
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null
                val marker = "var ytInitialData = "
                val idx = html.indexOf(marker)
                val start = if (idx >= 0) {
                    html.indexOf('{', idx + marker.length)
                } else {
                    val alt = html.indexOf("ytInitialData")
                    if (alt >= 0) html.indexOf('{', alt) else -1
                }
                if (start < 0) return null
                val rawJson = extractBalancedJson(html, start) ?: return null
                JSONObject(rawJson)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractBalancedJson(src: String, startIndex: Int): String? {
        if (startIndex < 0 || startIndex >= src.length || src[startIndex] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        val limit = minOf(src.length, startIndex + 2_500_000)
        for (i in startIndex until limit) {
            val c = src[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return src.substring(startIndex, i + 1)
                    }
                }
            }
        }
        return null
    }

    fun fetchSignedInHomeFeed(context: Context): List<YtItem> =
        fetchSignedInHomePage(context, continuationToken = null).items

    /**
     * Paginated "For You" feed:
     * - When signed in to YouTube (and not in Music filter), loads the user's
     *   real authenticated YouTube Home feed (`FEwhat_to_watch`) with a live
     *   continuation token so scrolling loads endless recommendations.
     * - Otherwise (or as fallback), builds a private on-device feed from local
     *   Watch History, Bookmarks, and Recent Searches, topped up with Trending.
     */
    fun exploreForYouPaged(
        context: Context,
        musicMode: Boolean = false,
        forceRefresh: Boolean = false
    ): FeedPage {
        if (!musicMode && WebViewCookieSync.buildYouTubeAuthHeaders(context) != null) {
            val page = fetchSignedInHomePage(
                context = context,
                continuationToken = null,
                forceRefreshConfig = forceRefresh
            )
            if (page.items.isNotEmpty()) {
                return page.copy(items = withMixCards(context, page.items))
            }
        }
        val local = exploreLocalForYou(context, musicMode)
        return FeedPage(
            items = if (musicMode) local else withMixCards(context, local),
            continuationToken = null
        )
    }

    /**
     * YouTube's current home grid rarely includes playlists/mixes. To keep the
     * feed varied like other YouTube clients, weave in up to 3 "Mix" playlist
     * cards (YouTube's own `RD<videoId>` auto-mix radio) seeded from recently
     * watched / bookmarked videos. Real playlists already in the feed are kept.
     */
    private fun withMixCards(context: Context, items: List<YtItem>): List<YtItem> {
        if (items.count { it.isPlaylist } >= 2) return items
        val seeds = (YouTubeStore.getHistory(context).map { it.item } + YouTubeStore.getBookmarks(context))
            .filter { !it.isPlaylist && it.id.length == 11 }
            .distinctBy { it.id }
            .take(10)
            .shuffled()
            .take(3)
        if (seeds.isEmpty()) return items
        val mixes = seeds.map { s ->
            YtItem(
                id = "RD${s.id}",
                title = "Mix – ${s.title}",
                channel = s.channel.ifBlank { "YouTube Mix" },
                thumbnailUrl = s.thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/${s.id}/hqdefault.jpg" },
                isPlaylist = true,
                videoCount = "Mix"
            )
        }
        val out = items.toMutableList()
        val slots = intArrayOf(3, 9, 16)
        mixes.forEachIndexed { i, mix ->
            val at = slots[i].coerceAtMost(out.size)
            out.add(at, mix)
        }
        return out
    }

    fun exploreForYou(context: Context, musicMode: Boolean = false): List<YtItem> =
        exploreForYouPaged(context, musicMode).items

    private fun exploreLocalForYou(context: Context, musicMode: Boolean = false): List<YtItem> {
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
            tasks += { fetchRelated(vid.id, context).filter { !it.isPlaylist } }
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

    /**
     * Finds the main feed pagination continuation token while skipping header
     * filter chips (`feedFilterChipBarRenderer` / `chipCloudRenderer`) so
     * scrolling always loads the next home page instead of switching categories.
     */
    private fun findFeedContinuationToken(node: Any?): String? {
        val skipKeys = setOf(
            "feedFilterChipBarRenderer",
            "chipCloudRenderer",
            "chipCloudChipRenderer",
            "header"
        )
        fun walk(cur: Any?): String? {
            when (cur) {
                is JSONObject -> {
                    if (cur.has("continuationItemRenderer")) {
                        val renderer = cur.optJSONObject("continuationItemRenderer")
                        val token = renderer?.optJSONObject("continuationEndpoint")
                            ?.optJSONObject("continuationCommand")
                            ?.optString("token")
                            ?.takeIf { it.isNotBlank() }
                        if (token != null) return token
                    }
                    if (cur.has("nextContinuationData")) {
                        val token = cur.optJSONObject("nextContinuationData")
                            ?.optString("continuation")
                            ?.takeIf { it.isNotBlank() }
                        if (token != null) return token
                    }
                    val keys = cur.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key in AD_KEYS || key in skipKeys) continue
                        val found = walk(cur.opt(key))
                        if (found != null) return found
                    }
                }
                is JSONArray -> {
                    // Check from the end first because the feed pagination
                    // continuationItemRenderer sits at the tail of contents/continuationItems.
                    for (i in cur.length() - 1 downTo 0) {
                        val found = walk(cur.opt(i))
                        if (found != null) return found
                    }
                }
            }
            return null
        }
        return walk(node)
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

    /**
     * Fetches related / Up Next videos for [videoId] via `/youtubei/v1/next`
     * with `playlistId = "RD$videoId"` (YouTube's native Music Auto-Mix radio):
     * - For songs/music videos, YouTube automatically returns a 25-song curated
     *   Music Mix so Next (⏭) and Autoplay ALWAYS play real related songs!
     * - For non-music videos, the RD playlist is empty and we cleanly use the
     *   regular related `secondaryResults` in the exact same single request.
     */
    fun fetchRelated(videoId: String, context: Context? = null): List<YtItem> {
        val authHeaders = context?.let { WebViewCookieSync.buildYouTubeAuthHeaders(it) }
        val cfg = YouTubeSessionConfig.ensureWebConfig()
        val body = JSONObject().apply {
            put("context", webContext(cfg))
            put("videoId", videoId)
            put("playlistId", "RD$videoId")
        }
        val root = postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false",
            body = body,
            authHeaders = authHeaders,
            webConfig = cfg
        ) ?: return emptyList()
        return extractNextUpRecommendations(root, videoId)
    }

    /**
     * Fetches Up Next related videos (music-aware via `RD$videoId` Auto-Mix)
     * + engagement metadata (channelId, Like/Subscribe status, comment count,
     * and comments continuation token) in a single `/youtubei/v1/next` call.
     */
    fun fetchWatchData(
        context: Context,
        videoId: String
    ): Pair<List<YtItem>, VideoEngagement?> {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
        val cfg = YouTubeSessionConfig.ensureWebConfig()
        val body = JSONObject().apply {
            put("context", webContext(cfg))
            put("videoId", videoId)
            put("playlistId", "RD$videoId")
        }
        val root = postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false",
            body = body,
            authHeaders = authHeaders,
            webConfig = cfg
        ) ?: return emptyList<YtItem>() to null

        val items = extractNextUpRecommendations(root, videoId)
        val engagement = parseVideoEngagement(root)
        return items to engagement
    }

    /**
     * Extracts ranked Up Next tracks from a `/next` response:
     * 1. Curated `RD` Music Mix (`playlistPanelVideoRenderer`) first when present
     *    (only populated by YouTube for music tracks -> guarantees music-to-music autoplay!).
     * 2. Regular `secondaryResults` related videos appended after (deduped).
     */
    private fun extractNextUpRecommendations(root: JSONObject, currentVideoId: String): List<YtItem> {
        val items = mutableListOf<YtItem>()
        val seen = mutableSetOf(currentVideoId)
        val twoCol = root.optJSONObject("contents")?.optJSONObject("twoColumnWatchNextResults")
        val mixContents = twoCol
            ?.optJSONObject("playlist")
            ?.optJSONObject("playlist")
            ?.optJSONArray("contents")
        if (mixContents != null && mixContents.length() > 0) {
            collectPlaylistVideos(mixContents, items, seen)
        }
        val secondary = twoCol
            ?.optJSONObject("secondaryResults")
            ?.optJSONObject("secondaryResults")
            ?.optJSONArray("results")
        if (secondary != null) {
            collectItems(secondary, items, seen)
        } else {
            collectItems(root, items, seen)
        }
        return items.filter { !it.isPlaylist }.take(30)
    }

    /** Fetches paginated comments for a video using its `/next` continuation token. */
    fun fetchCommentsPaged(context: Context, continuationToken: String): CommentsPage {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
        val cfg = YouTubeSessionConfig.ensureWebConfig()
        val body = JSONObject().apply {
            put("context", webContext(cfg))
            put("continuation", continuationToken)
        }
        val root = postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false",
            body = body,
            authHeaders = authHeaders,
            webConfig = cfg
        ) ?: return CommentsPage(emptyList(), null)

        val out = mutableListOf<YtComment>()
        val seen = mutableSetOf<String>()
        collectComments(root, out, seen)
        val next = findContinuationToken(root)?.takeIf { it != continuationToken }
        val createParams = findCreateCommentParams(root)
        return CommentsPage(comments = out.take(40), continuationToken = next, createCommentParams = createParams)
    }

    private fun findCreateCommentParams(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val direct = node.optString("createCommentParams")
                if (direct.isNotBlank()) return direct
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k in AD_KEYS) continue
                    findCreateCommentParams(node.opt(k))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findCreateCommentParams(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * Posts a top-level comment as the signed-in user via
     * `/youtubei/v1/comment/create_comment`. Returns (success, message).
     */
    fun postComment(context: Context, createCommentParams: String, text: String): Pair<Boolean, String> {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
            ?: return false to "Sign in to YouTube first (👤 on the home screen)"
        val cfg = YouTubeSessionConfig.ensureWebConfig()
        val body = JSONObject().apply {
            put("context", webContext(cfg))
            put("commentText", text)
            put("createCommentParams", createCommentParams)
        }
        val root = postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/comment/create_comment?prettyPrint=false",
            body = body,
            authHeaders = authHeaders,
            webConfig = cfg
        ) ?: return false to "Couldn't post — check your connection or sign in again"
        root.optJSONObject("error")?.let {
            return false to it.optString("message").ifBlank { "YouTube rejected the comment" }
        }
        val actions = root.optJSONArray("actions")
        if (actions != null) {
            for (i in 0 until actions.length()) {
                if (actions.optJSONObject(i)?.has("createCommentAction") == true) return true to "Comment posted"
            }
        }
        val status = root.optString("actionResult")
        return if (actions != null && actions.length() > 0 || status.isNotBlank()) {
            true to "Comment posted"
        } else {
            false to "YouTube didn't confirm the comment — try again"
        }
    }

    /** Fetches top comments for a video using its `/next` continuation token. */
    fun fetchComments(context: Context, continuationToken: String): List<YtComment> =
        fetchCommentsPaged(context, continuationToken).comments

    /**
     * Fetches a YouTube Channel's Videos or Playlists tab (with header metadata,
     * live subscription state, and infinite-scroll continuation token).
     */
    fun fetchChannelTab(
        context: Context,
        channelId: String,
        tab: ChannelTab = ChannelTab.VIDEOS,
        continuationToken: String? = null
    ): ChannelPage? {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
        val cfg = YouTubeSessionConfig.ensureWebConfig()

        fun requestChannel(useParams: Boolean): JSONObject? {
            val body = JSONObject().apply {
                put("context", webContext(cfg))
                if (!continuationToken.isNullOrBlank()) {
                    put("continuation", continuationToken)
                } else {
                    put("browseId", channelId)
                    if (useParams) put("params", tab.params)
                }
            }
            return postInnerTube(
                url = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                body = body,
                authHeaders = authHeaders,
                webConfig = cfg
            )
        }

        var root = requestChannel(useParams = true)
        if (root == null && continuationToken.isNullOrBlank()) {
            root = requestChannel(useParams = false)
        }
        if (root == null) return null

        var title = ""
        var handle = ""
        var subCount = ""
        var avatar = ""
        var isSubscribed = false

        val header = root.optJSONObject("header")
        val c4 = header?.optJSONObject("c4TabbedHeaderRenderer")
        if (c4 != null) {
            title = c4.optString("title").ifBlank { title }
            handle = extractText(c4.optJSONObject("channelHandleText")).ifBlank { handle }
            subCount = extractText(c4.optJSONObject("subscriberCountText")).ifBlank { subCount }
            avatar = pickThumb(c4.optJSONObject("avatar")).ifBlank { avatar }
            val subBtn = c4.optJSONObject("subscribeButton")?.optJSONObject("subscribeButtonRenderer")
            if (subBtn != null) {
                isSubscribed = subBtn.optBoolean("subscribed", isSubscribed)
            }
        }
        val pageHdr = header?.optJSONObject("pageHeaderRenderer")
        if (pageHdr != null) {
            title = pageHdr.optString("pageTitle").ifBlank { title }
            val vm = pageHdr.optJSONObject("content")?.optJSONObject("pageHeaderViewModel")
            if (vm != null) {
                title = vm.optJSONObject("title")?.optJSONObject("dynamicTextViewModel")
                    ?.optJSONObject("text")?.optString("content").orEmpty().ifBlank { title }
                val metaRows = vm.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")
                    ?.optJSONArray("metadataRows")
                if (metaRows != null) {
                    for (r in 0 until metaRows.length()) {
                        val parts = metaRows.optJSONObject(r)?.optJSONArray("metadataParts") ?: continue
                        for (p in 0 until parts.length()) {
                            val txt = parts.optJSONObject(p)?.optJSONObject("text")?.optString("content").orEmpty().trim()
                            if (txt.startsWith("@") && handle.isBlank()) handle = txt
                            else if (txt.contains("subscriber", ignoreCase = true) && subCount.isBlank()) subCount = txt
                        }
                    }
                }
                val avSources = vm.optJSONObject("image")
                    ?.optJSONObject("decoratedAvatarViewModel")
                    ?.optJSONObject("avatar")
                    ?.optJSONObject("avatarViewModel")
                    ?.optJSONObject("image")
                    ?.optJSONArray("sources")
                if (avSources != null && avSources.length() > 0 && avatar.isBlank()) {
                    avatar = avSources.optJSONObject(avSources.length() - 1)?.optString("url").orEmpty()
                }
            }
        }
        // Check live subscriptionStateEntity mutations
        fun scanSubState(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (node.has("subscriptionStateEntity")) {
                        val se = node.optJSONObject("subscriptionStateEntity")
                        if (se != null && se.has("subscribed")) {
                            isSubscribed = se.optBoolean("subscribed", isSubscribed)
                        }
                    }
                    if (node.has("subscribeButtonRenderer")) {
                        val sb = node.optJSONObject("subscribeButtonRenderer")
                        if (sb != null && sb.optString("channelId") == channelId) {
                            isSubscribed = sb.optBoolean("subscribed", isSubscribed)
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k in AD_KEYS) continue
                        scanSubState(node.opt(k))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) scanSubState(node.opt(i))
                }
            }
        }
        scanSubState(root)

        val rawItems = mutableListOf<YtItem>()
        collectItems(root, rawItems, seen = mutableSetOf())
        val items = when (tab) {
            ChannelTab.VIDEOS -> rawItems.filter { !it.isPlaylist }
            ChannelTab.PLAYLISTS -> rawItems.filter { it.isPlaylist }
        }
        val next = findFeedContinuationToken(root)?.takeIf { it != continuationToken }
        return ChannelPage(
            channelId = channelId,
            title = title.ifBlank { "Channel" },
            handle = handle,
            subscriberCount = subCount,
            avatarUrl = avatar,
            isSubscribed = isSubscribed,
            items = items,
            continuationToken = next
        )
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
     * Clean 2-step InnerTube stream resolution for direct HTTP downloads
     * (never spawns Python/yt-dlp — FastYouTubeDownloader falls back cleanly to
     * yt-dlp with exported cookies whenever HD adaptive streams aren't returned):
     * 1. JS-less `VISIONOS 1.03` -> `ANDROID_VR` (~250ms off-VPN, full 144p-4K).
     * 2. Native 165ms V8 `TV_SAMSUNG` (when signed in).
     */
    fun resolveForDownload(
        context: Context,
        videoId: String,
        requestedMaxHeight: Int? = null,
        audioOnly: Boolean = false
    ): ResolveResult {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
        val visitorData by lazy { ensureVisitorData() }
        var tier1Error: String? = null

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
                "osVersion" to "26.6.1"
            )
        )
        if (visionOs.info != null &&
            (visionOs.info.audioStreams.isNotEmpty() || visionOs.info.muxedStreams.isNotEmpty())
        ) {
            return visionOs
        }
        tier1Error = visionOs.error

        if (!looksLikeBotBlock(visionOs.error)) {
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
                return androidVr
            }
            if (tier1Error.isNullOrBlank()) tier1Error = androidVr.error
        }

        if (authHeaders != null) {
            val v8 = resolveViaV8(
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
        }

        return ResolveResult(null, tier1Error ?: "No direct streams")
    }

    // ---- Stream Resolution: Step 1 VISIONOS -> Step 2 Native V8 (web_safari HLS / TV) -> Step 3 yt-dlp ----

    fun resolveStreams(
        context: Context,
        videoId: String,
        onStatus: ((String) -> Unit)? = null
    ): ResolveResult {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
        val hasCookies = authHeaders != null
        val visitorData by lazy { ensureVisitorData() }
        var tier1Error: String? = null

        // Step 1 (~250ms off-VPN, zero WebView, 144p-4K + HLS):
        // Always try VISIONOS first with no 15-minute lockout so turning off VPN
        // immediately restores instant 250ms HD playback.
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
                "osVersion" to "26.6.1"
            )
        )
        if (visionOs.info != null) {
            return visionOs
        }
        tier1Error = visionOs.error

        if (!looksLikeBotBlock(visionOs.error)) {
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
                return androidVr
            }
        }

        // Step 2 (~0.7s on VPN, full 144p-1080p HLS):
        // Uses our ~165ms native V8 player_ias base.js solver (zero AST parsing)
        // to solve `/n/` on `web_safari` 144p-1080p HLS (or `TV_SAMSUNG` adaptive).
        onStatus?.invoke("Loading HD stream…")
        val v8 = resolveViaV8(
            context = context,
            videoId = videoId,
            visitorData = visitorData,
            authHeaders = authHeaders,
            includeHlsFallback = true
        )
        if (v8.info != null) {
            return v8
        }
        Log.d(TAG, "Step 2 native V8 failed ($videoId: ${v8.error}), falling back to yt-dlp")

        // Step 3 (Guaranteed 144p-1080p safety-net via bundled yt-dlp + QuickJS + live cookies):
        onStatus?.invoke(
            if (hasCookies && !hasWarmJsSolverCache(context)) {
                "Preparing YouTube engine (first time only)…"
            } else {
                "Resolving HD stream…"
            }
        )
        val ytDlpResult = resolveViaYtDlp(context, videoId)
        if (ytDlpResult.info != null) return ytDlpResult

        val reason = ytDlpResult.error?.takeIf { it.isNotBlank() }
            ?: v8.error?.takeIf { it.isNotBlank() }
            ?: tier1Error?.takeIf { it.isNotBlank() }
            ?: "Couldn't load stream for this video"
        return ResolveResult(null, reason)
    }

    /**
     * Resolves full 144p-1080p streams using our ~165ms native V8 `base.js` solver:
     * - Playback ([includeHlsFallback] == true): tries `web_safari` 144p-1080p HLS
     *   first (no PO token required once `/n/` is solved via V8!), then signed-in
     *   `TV_SAMSUNG` adaptive HD.
     * - Download ([includeHlsFallback] == false): tries signed-in `TV_SAMSUNG`
     *   direct adaptive MP4/M4A.
     */
    private fun resolveViaV8(
        context: Context,
        videoId: String,
        visitorData: String?,
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders?,
        includeHlsFallback: Boolean
    ): ResolveResult {
        val ready = YouTubeJsSolver.ensureReady(context)
            ?: return ResolveResult(
                null,
                "V8 JS solver unavailable (${YouTubeJsSolver.lastError.ifBlank { "unknown" }})"
            )
        val cfg = YouTubeSessionConfig.ensureWebConfig()
        var effectiveSts = cfg.signatureTimestamp ?: ready.signatureTimestamp
        var lastReason: String? = null

        if (includeHlsFallback) {
            val safariRoot = requestWebSafariPlayer(videoId, visitorData, effectiveSts, authHeaders, cfg)
            if (safariRoot != null) {
                val parsed = parseDecryptedPlayerRoot(videoId, safariRoot, WEB_SAFARI_UA)
                if (parsed.info != null &&
                    (parsed.info.hlsVariants.isNotEmpty() || !parsed.info.hlsUrl.isNullOrBlank())
                ) {
                    return parsed
                }
                lastReason = parsed.error
                Log.d(TAG, "WEB_SAFARI HLS note ($videoId): ${parsed.error}")
            }
        }

        if (authHeaders != null) {
            var tvRoot = requestTvSamsungPlayer(videoId, visitorData, effectiveSts, authHeaders, cfg)
            val firstStatus = tvRoot?.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
            val firstReason = tvRoot?.optJSONObject("playabilityStatus")?.optString("reason").orEmpty()
            if (firstStatus != "OK" && firstReason.contains("reload", ignoreCase = true)) {
                YouTubeJsSolver.invalidate()
                val refreshed = YouTubeJsSolver.ensureReady(context, forceRefresh = true)
                if (refreshed != null) {
                    effectiveSts = refreshed.signatureTimestamp
                    tvRoot = requestTvSamsungPlayer(videoId, visitorData, effectiveSts, authHeaders, cfg)
                }
            }
            if (tvRoot != null) {
                val parsed = parseDecryptedPlayerRoot(videoId, tvRoot, TV_SAMSUNG_UA)
                if (parsed.info != null &&
                    parsed.info.videoStreams.isNotEmpty() &&
                    parsed.info.audioStreams.isNotEmpty()
                ) {
                    return parsed
                }
                if (lastReason.isNullOrBlank()) lastReason = parsed.error
                Log.d(TAG, "TVHTML5 V8 note ($videoId): ${parsed.error}")
            }
        }

        return ResolveResult(null, lastReason?.takeIf { it.isNotBlank() } ?: "V8 InnerTube returned no playable formats")
    }

    private data class DecryptedStreams(
        val videoStreams: List<StreamFormat>,
        val audioStreams: List<StreamFormat>,
        val muxedStreams: List<StreamFormat>
    )

    private fun requestTvSamsungPlayer(
        videoId: String,
        visitorData: String?,
        signatureTimestamp: Int,
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders,
        cfg: YouTubeSessionConfig.WebClientConfig = YouTubeSessionConfig.ensureWebConfig()
    ): JSONObject? {
        val effectiveVisitor = visitorData?.takeIf { it.isNotBlank() } ?: cfg.visitorData
        val clientObj = JSONObject().apply {
            put("clientName", "TVHTML5")
            put("clientVersion", TV_SAMSUNG_CLIENT_VERSION)
            put("userAgent", TV_SAMSUNG_UA)
            put("deviceMake", "Samsung")
            put("deviceModel", "UKS9800")
            put("osName", "Tizen")
            put("osVersion", "2.4.0")
            put("hl", cfg.hl)
            put("gl", cfg.gl)
            if (!effectiveVisitor.isNullOrBlank()) put("visitorData", effectiveVisitor)
        }
        val body = JSONObject().apply {
            put("context", JSONObject().apply { put("client", clientObj) })
            put("videoId", videoId)
            put("playbackContext", JSONObject().apply {
                put("contentPlaybackContext", JSONObject().apply {
                    put("signatureTimestamp", signatureTimestamp)
                    put("html5Preference", "HTML5_PREF_WANTS")
                    put("isInlinePlaybackNoAd", true)
                })
            })
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val baseUrl = if (!cfg.apiKey.isNullOrBlank()) {
            "https://www.youtube.com/youtubei/v1/player?key=${cfg.apiKey}&prettyPrint=false"
        } else {
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        }
        // Do NOT pass desktop WEB's webConfig here so we don't leak WEB's
        // X-YouTube-Identity-Token header onto a TVHTML5 request.
        return postInnerTube(
            url = baseUrl,
            body = body,
            userAgent = TV_SAMSUNG_UA,
            clientNameId = "7",
            clientVersion = TV_SAMSUNG_CLIENT_VERSION,
            visitorData = effectiveVisitor,
            authHeaders = authHeaders,
            webConfig = null
        )
    }

    private fun requestWebSafariPlayer(
        videoId: String,
        visitorData: String?,
        signatureTimestamp: Int,
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders?,
        cfg: YouTubeSessionConfig.WebClientConfig = YouTubeSessionConfig.ensureWebConfig()
    ): JSONObject? {
        val effectiveVisitor = visitorData?.takeIf { it.isNotBlank() } ?: cfg.visitorData
        val clientObj = JSONObject().apply {
            put("clientName", "WEB")
            put("clientVersion", cfg.clientVersion)
            put("userAgent", WEB_SAFARI_UA)
            put("hl", cfg.hl)
            put("gl", cfg.gl)
            if (!effectiveVisitor.isNullOrBlank()) put("visitorData", effectiveVisitor)
        }
        val body = JSONObject().apply {
            put("context", JSONObject().apply { put("client", clientObj) })
            put("videoId", videoId)
            put("playbackContext", JSONObject().apply {
                put("contentPlaybackContext", JSONObject().apply {
                    put("signatureTimestamp", signatureTimestamp)
                    put("html5Preference", "HTML5_PREF_WANTS")
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
            clientVersion = cfg.clientVersion,
            visitorData = effectiveVisitor,
            authHeaders = authHeaders,
            webConfig = cfg,
            useAnonymousClient = (authHeaders == null)
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
        userAgent: String,
        poToken: String? = null
    ): ResolveResult {
        val playStatus = root.optJSONObject("playabilityStatus")
        val status = playStatus?.optString("status") ?: ""
        if (status != "OK") {
            val reason = playStatus?.optString("reason").orEmpty()
            return ResolveResult(null, reason.ifBlank { status.ifBlank { "UNPLAYABLE" } })
        }

        val streamingData = root.optJSONObject("streamingData")
            ?: return ResolveResult(null, "No streaming data returned")
        val rawHlsUrl = streamingData.optString("hlsManifestUrl").takeIf { it.startsWith("http") }
        // Solve `/n/<challenge>/` path segment inside web_safari HLS master manifest
        // via warm V8 solver so all 144p-1080p segments return HTTP 200 (not 403).
        val solvedHlsUrl = rawHlsUrl?.let { YouTubeJsSolver.solveUrlNParam(it) }
        var effectiveHlsUrl: String? = solvedHlsUrl
        var hlsVariants: List<StreamFormat> = emptyList()
        if (solvedHlsUrl != null) {
            val (hlsOk, parsedVariants) = fetchParsedHlsVariants(solvedHlsUrl, userAgent)
            if (!hlsOk) {
                Log.w(TAG, "HLS master manifest failed verification ($videoId); skipping broken HLS")
                effectiveHlsUrl = null
            } else {
                hlsVariants = parsedVariants
            }
        }

        val decrypted = decryptStreamingDataObject(streamingData, poToken = poToken)
        val videoStreams = decrypted?.videoStreams.orEmpty()
        val audioStreams = decrypted?.audioStreams.orEmpty()
        val muxedStreams = decrypted?.muxedStreams.orEmpty()

        if (effectiveHlsUrl == null && hlsVariants.isEmpty() && muxedStreams.isEmpty() &&
            (videoStreams.isEmpty() || audioStreams.isEmpty())
        ) {
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
                hlsUrl = effectiveHlsUrl,
                hlsVariants = hlsVariants,
                videoStreams = videoStreams,
                audioStreams = audioStreams,
                muxedStreams = muxedStreams,
                userAgent = userAgent,
                captionTracks = captions
            )
        )
    }

    /**
     * Fetches and verifies an HLS master manifest (e.g. `web_safari` 144p-1080p)
     * and extracts individual self-contained muxed variant `.m3u8` URLs so
     * ExoPlayer plays the exact chosen resolution without getting stuck on
     * low-res Adaptive Bitrate (ABR).
     *
     * Returns `(manifestReachable, selfContainedMuxedVariants)`. When a manifest
     * uses separate `#EXT-X-MEDIA:TYPE=AUDIO` tracks, returns `(true, emptyList())`
     * so ExoPlayer uses the master manifest with audio intact.
     */
    private fun fetchParsedHlsVariants(
        masterUrl: String,
        userAgent: String
    ): Pair<Boolean, List<StreamFormat>> {
        return try {
            val req = Request.Builder()
                .url(masterUrl)
                .header("User-Agent", userAgent)
                .build()
            anonClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return false to emptyList()
                }
                val body = resp.body?.string().orEmpty()
                if (!body.contains("#EXTM3U")) {
                    return false to emptyList()
                }
                // Separate audio groups require the master manifest so audio stays synced.
                if (body.contains("#EXT-X-MEDIA:") && body.contains("TYPE=AUDIO")) {
                    return true to emptyList()
                }

                val resRegex = Regex("""RESOLUTION=(\d+)x(\d+)""")
                val bwRegex = Regex("""BANDWIDTH=(\d+)""")
                val codecsRegex = Regex("""CODECS="([^"]+)"""")
                val itagRegex = Regex("""/itag/(\d+)""")
                val out = mutableListOf<StreamFormat>()
                val lines = body.lineSequence().map { it.trim() }.toList()
                var pendingInf: String? = null

                for (line in lines) {
                    if (line.isEmpty()) continue
                    if (line.startsWith("#EXT-X-STREAM-INF:")) {
                        pendingInf = line
                        continue
                    }
                    if (line.startsWith("#")) continue
                    val inf = pendingInf ?: continue
                    pendingInf = null

                    val variantUrl = if (line.startsWith("http://") || line.startsWith("https://")) {
                        YouTubeJsSolver.solveUrlNParam(line)
                    } else {
                        continue
                    }
                    val resMatch = resRegex.find(inf) ?: continue
                    val w = resMatch.groupValues[1].toIntOrNull() ?: 0
                    val h = resMatch.groupValues[2].toIntOrNull() ?: 0
                    val shortSide = minOf(w, h)
                    if (shortSide <= 0) continue
                    val normH = VideoStreamInfo.normalizeHeight(shortSide)
                    val bw = bwRegex.find(inf)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val codecs = codecsRegex.find(inf)?.groupValues?.get(1).orEmpty()
                    val itag = itagRegex.find(variantUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    out.add(
                        StreamFormat(
                            itag = itag,
                            url = variantUrl,
                            mimeType = if (codecs.isNotBlank()) {
                                "application/x-mpegURL; codecs=\"$codecs\""
                            } else {
                                "application/x-mpegURL"
                            },
                            qualityLabel = "${normH}p",
                            height = normH,
                            bitrate = bw,
                            isAudioOnly = false,
                            isVideoOnly = false,
                            isMuxed = true
                        )
                    )
                }
                true to out
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchParsedHlsVariants failed: ${e.message}")
            false to emptyList()
        }
    }

    private fun decryptStreamingDataObject(
        streamingData: JSONObject,
        poToken: String? = null
    ): DecryptedStreams? {
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
        if (pending.isEmpty()) return null

        val needSigs = pending.mapNotNull { it.cipherS }.distinct()
        val needNs = pending.mapNotNull { it.rawN }.distinct()
        val solved = if (needSigs.isNotEmpty() || needNs.isNotEmpty()) {
            YouTubeJsSolver.solve(needSigs, needNs) ?: return null
        } else {
            YouTubeJsSolver.SolveResult(emptyMap(), emptyMap())
        }

        val videoStreams = mutableListOf<StreamFormat>()
        val audioStreams = mutableListOf<StreamFormat>()
        val muxedStreams = mutableListOf<StreamFormat>()
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
            if (!poToken.isNullOrBlank() && !finalUrl.contains("pot=")) {
                val sep = if ('?' in finalUrl) "&" else "?"
                finalUrl = "$finalUrl${sep}pot=${urlEncode(poToken)}"
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
        return DecryptedStreams(videoStreams, audioStreams, muxedStreams)
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
            useAnonymousClient = (authHeaders == null),
            callTimeoutMs = 7_000L
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
        val seen = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            var baseUrl = c.optString("baseUrl").trim()
            if (baseUrl.startsWith("//")) {
                baseUrl = "https:$baseUrl"
            } else if (baseUrl.startsWith("/")) {
                baseUrl = "https://www.youtube.com$baseUrl"
            }
            if (!baseUrl.startsWith("http")) continue
            val cleanBase = baseUrl
                .replace(Regex("[&?]fmt=[^&]*"), "")
                .replace(Regex("[&?]tlang=[^&]*"), "")
            val ttmlUrl = if (cleanBase.contains("?")) "$cleanBase&fmt=ttml" else "$cleanBase?fmt=ttml"
            val lang = c.optString("languageCode").ifBlank { "und" }
            val vssId = c.optString("vssId")
            val isAuto = c.optString("kind") == "asr" || vssId.startsWith("a.")
            val rawLabel = extractText(c.optJSONObject("name")).ifBlank { lang }
            val label = if (isAuto && !rawLabel.contains("auto", ignoreCase = true)) {
                "$rawLabel (auto)"
            } else {
                rawLabel
            }
            val dedupeKey = "${lang.lowercase()}|$isAuto"
            if (seen.add(dedupeKey)) {
                out.add(
                    CaptionTrack(
                        langCode = lang,
                        label = label,
                        url = ttmlUrl,
                        mimeType = "application/ttml+xml",
                        isAuto = isAuto
                    )
                )
            }
        }
        // Prefer manual tracks first, then English tracks first, then alphabetical.
        return out.sortedWith(
            compareBy<CaptionTrack> { it.isAuto }
                .thenByDescending { it.langCode.startsWith("en", ignoreCase = true) }
                .thenBy { it.label.lowercase() }
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
            val contentLength = obj.optString("contentLength").toLongOrNull()
                ?: obj.optLong("contentLength", 0L).coerceAtLeast(0L)
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
                isMuxed = isMuxedDefault,
                contentLengthBytes = contentLength
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
        // Always ensure fresh live WebView cookies are exported before invoking yt-dlp!
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
            val hlsVariants = mutableListOf<StreamFormat>()
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
                val fileSize = f.optLong("filesize", 0L).takeIf { it > 0L }
                    ?: f.optLong("filesize_approx", 0L).coerceAtLeast(0L)
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
                    // Keep individual muxed HLS variants (e.g. web_safari itag 91-96)
                    // so ExoPlayer switches resolutions deterministically instead of ABR.
                    if (vcodec != "none" && vcodec.isNotBlank() &&
                        acodec != "none" && acodec.isNotBlank() &&
                        height > 0
                    ) {
                        val normH = VideoStreamInfo.normalizeHeight(height)
                        hlsVariants.add(
                            StreamFormat(
                                itag = f.optString("format_id").toIntOrNull() ?: 0,
                                url = url,
                                mimeType = "application/x-mpegURL; codecs=\"$vcodec, $acodec\"",
                                qualityLabel = "${normH}p",
                                height = normH,
                                bitrate = tbr,
                                isAudioOnly = false,
                                isVideoOnly = false,
                                isMuxed = true,
                                contentLengthBytes = fileSize
                            )
                        )
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
                    isMuxed = hasVideo && hasAudio,
                    contentLengthBytes = fileSize
                )
                when {
                    fmt.isMuxed -> muxedStreams.add(fmt)
                    fmt.isAudioOnly -> audioStreams.add(fmt)
                    fmt.isVideoOnly -> videoStreams.add(fmt)
                }
            }

            if (hlsUrl == null && hlsVariants.isEmpty() && muxedStreams.isEmpty() &&
                (videoStreams.isEmpty() || audioStreams.isEmpty())
            ) {
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
                    hlsVariants = hlsVariants,
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
        val seen = mutableSetOf<String>()
        fun collect(obj: JSONObject?, isAuto: Boolean) {
            if (obj == null) return
            val keys = obj.keys()
            while (keys.hasNext()) {
                val lang = keys.next()
                val arr = obj.optJSONArray(lang) ?: continue
                var bestUrl: String? = null
                var bestMime = "application/ttml+xml"
                var bestName = lang
                for (i in 0 until arr.length()) {
                    val fmt = arr.optJSONObject(i) ?: continue
                    val ext = fmt.optString("ext")
                    val u = fmt.optString("url")
                    if (!u.startsWith("http")) continue
                    if (ext == "ttml") {
                        bestUrl = u
                        bestMime = "application/ttml+xml"
                        bestName = fmt.optString("name").ifBlank { lang }
                        break
                    } else if (ext == "vtt" && bestUrl == null) {
                        bestUrl = u
                        bestMime = "text/vtt"
                        bestName = fmt.optString("name").ifBlank { lang }
                    }
                }
                val finalUrl = bestUrl ?: continue
                val label = if (isAuto && !bestName.contains("auto", ignoreCase = true)) {
                    "$bestName (auto)"
                } else {
                    bestName
                }
                val dedupeKey = "${lang.lowercase()}|$isAuto"
                if (seen.add(dedupeKey)) {
                    out.add(CaptionTrack(lang, label, finalUrl, bestMime, isAuto))
                }
            }
        }
        collect(root.optJSONObject("subtitles"), isAuto = false)
        collect(root.optJSONObject("automatic_captions"), isAuto = true)
        return out.sortedWith(
            compareBy<CaptionTrack> { it.isAuto }
                .thenByDescending { it.langCode.startsWith("en", ignoreCase = true) }
                .thenBy { it.label.lowercase() }
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
        useAnonymousClient: Boolean = false,
        callTimeoutMs: Long = 0L
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
                // Always attach the complete merged Cookie header (youtube.com +
                // www.youtube.com + m.youtube.com) and execute via anonClient so
                // OkHttp's CookieJar never duplicates the header or drops special-char cookies.
                reqBuilder.header("Cookie", authHeaders.cookieHeader)
                reqBuilder.header("Authorization", authHeaders.authorizationHeader)
                reqBuilder.header("X-Origin", "https://www.youtube.com")
                reqBuilder.header("X-Goog-AuthUser", "0")
                if (cfg == null) {
                    webConfig?.identityToken?.takeIf { it.isNotBlank() }?.let {
                        reqBuilder.header("X-YouTube-Identity-Token", it)
                    }
                }
            }

            val req = reqBuilder
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            val baseClient = if (useAnonymousClient || authHeaders != null) anonClient else client
            // Hard overall cap so a hung connection can't stall stream resolution
            // for 12s + 15s per tier.
            val httpClient = if (callTimeoutMs > 0L) {
                baseClient.newBuilder().callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()
            } else {
                baseClient
            }
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
        var subscriberCount = ""
        var channelAvatarUrl = ""
        var isSubscribed = false
        var isLiked = false
        var expectedLikeEntityKey: String? = null
        var likeCount = ""
        var commentCount = ""
        var commentsToken: String? = null
        var topCommentPreview = ""

        fun scan(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (node.has("videoSecondaryInfoRenderer")) {
                        val sec = node.optJSONObject("videoSecondaryInfoRenderer")
                        val owner = sec?.optJSONObject("owner")?.optJSONObject("videoOwnerRenderer")
                        if (owner != null) {
                            channelName = extractText(owner.optJSONObject("title")).ifBlank { channelName }
                            subscriberCount = extractText(owner.optJSONObject("subscriberCountText")).ifBlank { subscriberCount }
                            channelAvatarUrl = pickThumb(owner.optJSONObject("thumbnail")).ifBlank { channelAvatarUrl }
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
                        val defBtn = toggle?.optJSONObject("defaultButtonViewModel")
                            ?.optJSONObject("buttonViewModel")
                        val title = defBtn?.optString("title").orEmpty()
                        if (title.isNotBlank() && likeCount.isBlank()) {
                            likeCount = title
                        }
                        val statusEntityKey = likeVm?.optString("likeStatusEntityKey").orEmpty()
                        if (statusEntityKey.isNotBlank() && expectedLikeEntityKey == null) {
                            expectedLikeEntityKey = statusEntityKey
                        }
                        val inlineStatus = likeVm?.optJSONObject("likeStatusEntity")?.optString("likeStatus")
                        if (inlineStatus == "LIKE") isLiked = true
                    }
                    if (node.has("likeStatusEntity")) {
                        val entity = node.optJSONObject("likeStatusEntity")
                        val entityKey = entity?.optString("key").orEmpty()
                        val status = entity?.optString("likeStatus")
                        if (expectedLikeEntityKey == null || entityKey.isBlank() || entityKey == expectedLikeEntityKey) {
                            if (status == "LIKE") isLiked = true
                        }
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
                        val teaser = extractText(
                            hdr?.optJSONObject("contentRenderer")
                                ?.optJSONObject("commentsEntryPointTeaserRenderer")
                                ?.optJSONObject("teaserContent")
                        )
                        if (teaser.isNotBlank() && topCommentPreview.isBlank()) {
                            topCommentPreview = teaser
                        }
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
            commentsToken = commentsToken,
            subscriberCount = subscriberCount,
            channelAvatarUrl = channelAvatarUrl,
            topCommentPreview = topCommentPreview
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
                        "videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "videoWithContextRenderer" -> {
                            parseVideoRenderer(value as? JSONObject)?.let { item ->
                                if (seen.add(item.id)) out.add(item)
                            }
                        }
                        "playlistRenderer", "compactPlaylistRenderer", "gridPlaylistRenderer",
                        "radioRenderer", "compactRadioRenderer" -> {
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
            .ifBlank { extractText(obj.optJSONObject("headline")) }
        if (title.isBlank()) return null
        val channel = extractText(obj.optJSONObject("ownerText"))
            .ifBlank { extractText(obj.optJSONObject("shortBylineText")) }
            .ifBlank { extractText(obj.optJSONObject("longBylineText")) }
        val duration = extractText(obj.optJSONObject("lengthText"))
        var isLive = duration.equals("LIVE", ignoreCase = true)
        if (!isLive) {
            val badges = obj.optJSONArray("badges")
            if (badges != null) {
                for (i in 0 until badges.length()) {
                    val mr = badges.optJSONObject(i)?.optJSONObject("metadataBadgeRenderer") ?: continue
                    val style = mr.optString("style")
                    val lbl = mr.optString("label")
                    if (style.contains("LIVE", ignoreCase = true) || lbl.equals("LIVE", ignoreCase = true)) {
                        isLive = true
                        break
                    }
                }
            }
        }
        val views = extractText(obj.optJSONObject("shortViewCountText"))
            .ifBlank { extractText(obj.optJSONObject("viewCountText")) }
        val thumb = pickThumb(obj.optJSONObject("thumbnail"))
            .ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }
        val channelId = extractChannelIdFromRuns(obj.optJSONObject("ownerText"))
            .ifBlank { extractChannelIdFromRuns(obj.optJSONObject("shortBylineText")) }
            .ifBlank { extractChannelIdFromRuns(obj.optJSONObject("longBylineText")) }
            .ifBlank {
                obj.optJSONObject("channelThumbnailSupportedRenderers")
                    ?.optJSONObject("channelThumbnailWithLinkRenderer")
                    ?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")
                    ?.takeIf { it.startsWith("UC") }
                    .orEmpty()
            }
            .ifBlank { extractChannelIdFromAvatar(obj.optJSONObject("avatar")) }
        val channelAvatar = extractAvatarUrl(obj.optJSONObject("avatar"))
            .ifBlank {
                pickThumb(
                    obj.optJSONObject("channelThumbnailSupportedRenderers")
                        ?.optJSONObject("channelThumbnailWithLinkRenderer")
                        ?.optJSONObject("thumbnail")
                )
            }
            .ifBlank { pickThumb(obj.optJSONObject("channelThumbnail")) }
        return YtItem(
            id = id,
            title = title,
            channel = channel,
            duration = if (isLive) "LIVE" else duration,
            views = views,
            thumbnailUrl = thumb,
            isPlaylist = false,
            isLive = isLive,
            channelId = channelId,
            channelAvatarUrl = channelAvatar
        )
    }

    private fun parsePlaylistRenderer(obj: JSONObject?): YtItem? {
        if (obj == null) return null
        val id = obj.optString("playlistId")
        if (id.isBlank()) return null
        val title = extractText(obj.optJSONObject("title"))
            .ifBlank { extractText(obj.optJSONObject("headline")) }
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
        val isPlaylist = contentType.contains("PLAYLIST", ignoreCase = true) ||
            contentType.contains("STATION", ignoreCase = true) ||
            contentType.contains("MIX", ignoreCase = true) ||
            contentType.contains("RADIO", ignoreCase = true)
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
        val views = metaRows?.optJSONObject(1)
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

        val metaImage = meta?.optJSONObject("image")
        val channelId = if (!isPlaylist) extractChannelIdFromAvatar(metaImage) else ""
        val channelAvatar = if (!isPlaylist) extractAvatarUrl(metaImage) else ""

        val isLive = !isPlaylist && badgeText.equals("LIVE", ignoreCase = true)
        return YtItem(
            id = id,
            title = title,
            channel = channel,
            duration = if (!isPlaylist) badgeText else "",
            views = if (!isPlaylist) views else "",
            thumbnailUrl = thumb,
            isPlaylist = isPlaylist,
            videoCount = if (isPlaylist) badgeText.ifBlank { "Playlist" } else "",
            isLive = isLive,
            channelId = channelId,
            channelAvatarUrl = channelAvatar
        )
    }

    private fun extractChannelIdFromRuns(textObj: JSONObject?): String {
        val runs = textObj?.optJSONArray("runs") ?: return ""
        for (i in 0 until runs.length()) {
            val bid = runs.optJSONObject(i)
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId")
                .orEmpty()
            if (bid.startsWith("UC")) return bid
        }
        return ""
    }

    private fun extractChannelIdFromAvatar(imageObj: JSONObject?): String {
        if (imageObj == null) return ""
        val decorated = imageObj.optJSONObject("decoratedAvatarViewModel")
            ?: imageObj.optJSONObject("avatarStackViewModel")
            ?: imageObj
        val direct = decorated.optJSONObject("rendererContext")
            ?.optJSONObject("commandContext")
            ?.optJSONObject("onTap")
            ?.optJSONObject("innertubeCommand")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            .orEmpty()
        if (direct.startsWith("UC")) return direct
        return ""
    }

    private fun extractAvatarUrl(imageObj: JSONObject?): String {
        if (imageObj == null) return ""
        val avatarVm = imageObj.optJSONObject("decoratedAvatarViewModel")
            ?.optJSONObject("avatar")
            ?.optJSONObject("avatarViewModel")
            ?: imageObj.optJSONObject("avatarStackViewModel")
                ?.optJSONArray("avatars")
                ?.optJSONObject(0)
                ?.optJSONObject("avatarViewModel")
            ?: imageObj.optJSONObject("avatarViewModel")
            ?: return ""
        val sources = avatarVm.optJSONObject("image")?.optJSONArray("sources") ?: return ""
        if (sources.length() == 0) return ""
        val raw = sources.optJSONObject(sources.length() - 1)?.optString("url").orEmpty()
        return if (raw.startsWith("//")) "https:$raw" else raw
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
        // Prefer crisp 480-720px thumbnails for full-width 16:9 home cards while
        // avoiding huge 1280px maxres assets on scrolling lists.
        for (i in arr.length() - 1 downTo 0) {
            val t = arr.optJSONObject(i) ?: continue
            val w = t.optInt("width", 0)
            val u = t.optString("url")
            if (u.startsWith("http") && w in 420..760) return u
        }
        for (i in arr.length() - 1 downTo 0) {
            val t = arr.optJSONObject(i) ?: continue
            val w = t.optInt("width", 0)
            val u = t.optString("url")
            if (u.startsWith("http") && w in 240..800) return u
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
