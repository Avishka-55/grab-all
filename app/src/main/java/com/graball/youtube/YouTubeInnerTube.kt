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
        val topCommentPreview: String = "",
        val publishDate: String = "",
        val viewCount: String = ""
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
     * Fetches the signed-in Google / YouTube account's profile photo URL via
     * `/youtubei/v1/account/account_menu` (lightweight ~3KB JSON). Returns null
     * when signed out or unavailable.
     */
    fun fetchSignedInAccountAvatar(context: Context): String? {
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context) ?: return null
        val cfg = YouTubeSessionConfig.ensureWebConfig()
        val body = JSONObject().apply {
            put("context", webContext(cfg))
            put("deviceTheme", "DEVICE_THEME_SUPPORTED")
        }
        val root = postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false",
            body = body,
            authHeaders = authHeaders,
            webConfig = cfg,
            callTimeoutMs = 6_000L
        ) ?: return null
        return findAccountPhotoUrl(root)
    }

    private fun findAccountPhotoUrl(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                if (node.has("accountPhoto")) {
                    val u = pickThumb(node.optJSONObject("accountPhoto"))
                    if (u.startsWith("http")) return u
                }
                if (node.has("topbarMenuButtonRenderer")) {
                    val btn = node.optJSONObject("topbarMenuButtonRenderer")
                    val u = pickThumb(btn?.optJSONObject("avatar"))
                    if (u.startsWith("http")) return u
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k in AD_KEYS) continue
                    findAccountPhotoUrl(node.opt(k))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findAccountPhotoUrl(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

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

    fun fetchPlaylist(
        playlistId: String,
        maxItems: Int = 500,
        context: Context? = null
    ): PlaylistDetails? {
        val cleanId = playlistId.removePrefix("VL")
        val browseId = "VL$cleanId"
        val effectiveCtx = context ?: com.graball.GrabAllApp.appContextOrNull()
        val authHeaders = effectiveCtx?.let { WebViewCookieSync.buildYouTubeAuthHeaders(it) }
        val cfg = if (authHeaders != null) YouTubeSessionConfig.ensureWebConfig() else null

        val body = JSONObject().apply {
            put("context", if (cfg != null) webContext(cfg) else webContext())
            put("browseId", browseId)
        }
        var activeAuth = authHeaders
        var activeCfg = cfg
        var root = postInnerTube(
            url = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
            body = body,
            authHeaders = activeAuth,
            webConfig = activeCfg
        )
        // Safety fallback: if signed-in browse returned nothing, retry anonymous for public playlists.
        if (root == null && activeAuth != null) {
            activeAuth = null
            activeCfg = null
            val anonBody = JSONObject().apply {
                put("context", webContext())
                put("browseId", browseId)
            }
            root = postInnerTube(
                url = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                body = anonBody
            )
        }

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
            if (items.isEmpty()) {
                collectItems(root, items, seen)
                items.removeAll { it.isPlaylist }
            }

            // Follow InnerTube continuation tokens so playlists with 100+ / 500+
            // songs load completely (YouTube returns ~100 items per page).
            var nextToken = findContinuationToken(root)
            var pages = 0
            while (!nextToken.isNullOrBlank() && items.size < maxItems && pages < 15) {
                pages++
                val contBody = JSONObject().apply {
                    put("context", if (activeCfg != null) webContext(activeCfg) else webContext())
                    put("continuation", nextToken)
                }
                val contRoot = postInnerTube(
                    url = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                    body = contBody,
                    authHeaders = activeAuth,
                    webConfig = activeCfg
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
                put("context", if (activeCfg != null) webContext(activeCfg) else webContext())
                put("playlistId", cleanId)
            }
            val nextRoot = postInnerTube(
                url = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false",
                body = nextBody,
                authHeaders = activeAuth,
                webConfig = activeCfg
            )
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

        return ResolveResult(null, tier1Error ?: "No direct streams")
    }

    // ---- Stream Resolution: Step 1 VISIONOS -> Step 2 Native V8 (web_safari HLS / TV) -> Step 3 yt-dlp ----

    fun resolveStreams(
        context: Context,
        videoId: String,
        recoveryLevel: Int = 0,
        onStatus: ((String) -> Unit)? = null
    ): ResolveResult {
        if (recoveryLevel >= 1) {
            YouTubeSessionConfig.evictIdleConnections()
            YouTubeJsSolver.evictIdleConnections()
        }
        val authHeaders = WebViewCookieSync.buildYouTubeAuthHeaders(context)
        val hasCookies = authHeaders != null
        val visitorData by lazy { ensureVisitorData() }
        var tier1Error: String? = null

        // Recovery level >= 2 means Tier 1 & Tier 2 (V8) URLs already got HTTP 403
        // from ExoPlayer on this VPN IP: skip directly to bundled yt-dlp + QuickJS!
        if (recoveryLevel < 2) {
            // Step 1 (~250ms off-VPN, zero WebView, 144p-4K + HLS):
            // Always try VISIONOS first with no 15-minute lockout so turning off VPN
            // immediately restores instant 250ms HD playback.
            if (recoveryLevel == 0) {
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
                    val vAudio = visionOs.info.audioStreams
                    val onlyDubbedReturned = vAudio.isNotEmpty() &&
                        vAudio.none { it.isOriginalAudio } &&
                        vAudio.all { it.isDubbedOrDescriptive }
                    if (!onlyDubbedReturned) {
                        return visionOs
                    }
                    Log.d(TAG, "VISIONOS returned only dubbed audio for $videoId; trying next client for original audio")
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
            }

            // Step 2 (~0.7s on VPN, full 144p-1080p HLS / 4K TV_SAMSUNG):
            // Uses our ~165ms native V8 player_ias base.js solver (zero AST parsing).
            // On recoveryLevel == 1 (after HTTP 403), force-refresh base.js & ytcfg
            // so rotated signatures or a new VPN exit IP are healed immediately.
            onStatus?.invoke(
                if (recoveryLevel == 1) "Refreshing stream session…" else "Loading HD stream…"
            )
            val v8 = resolveViaV8(
                context = context,
                videoId = videoId,
                visitorData = visitorData,
                authHeaders = authHeaders,
                includeHlsFallback = true,
                forceRefreshSolver = (recoveryLevel >= 1)
            )
            if (v8.info != null) {
                return v8
            }
            Log.d(TAG, "Step 2 native V8 failed ($videoId: ${v8.error}), falling back to yt-dlp")
            if (tier1Error.isNullOrBlank()) tier1Error = v8.error
        }

        // Step 3 (Guaranteed 144p-1080p safety-net via bundled yt-dlp + QuickJS + live cookies):
        onStatus?.invoke(
            when {
                recoveryLevel >= 2 -> "Switching to fallback engine…"
                hasCookies && !hasWarmJsSolverCache(context) -> "Preparing YouTube engine (first time only)…"
                else -> "Resolving HD stream…"
            }
        )
        val ytDlpResult = resolveViaYtDlp(context, videoId)
        if (ytDlpResult.info != null) return ytDlpResult

        val reason = ytDlpResult.error?.takeIf { it.isNotBlank() }
            ?: tier1Error?.takeIf { it.isNotBlank() }
            ?: "Couldn't load stream for this video"
        return ResolveResult(null, reason)
    }

    /**
     * Resolves full 144p-4K streams using our ~165ms native V8 `base.js` solver:
     * - When signed in (`authHeaders != null`): tries `TV_SAMSUNG` (`TVHTML5`) FIRST!
     *   It requires zero PO token, streams unthrottled via 2 MB range chunks, reaches up to
     *   4K, AND always exposes ALL separate audio tracks (with explicit Original vs AI-dub
     *   metadata) — completely eliminating VPN pre-merged HLS foreign dubbing!
     * - Falls back to `WEB_SAFARI` 144p-1080p HLS (essential for live streams & guest mode),
     *   and `WEB_EMBEDDED_PLAYER` if `WEB_SAFARI` returns dubbed-only or fails for guests.
     */
    private fun resolveViaV8(
        context: Context,
        videoId: String,
        visitorData: String?,
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders?,
        includeHlsFallback: Boolean,
        forceRefreshSolver: Boolean = false
    ): ResolveResult {
        if (forceRefreshSolver) {
            YouTubeJsSolver.invalidate()
            YouTubeSessionConfig.invalidate()
        }
        val ready = YouTubeJsSolver.ensureReady(context, forceRefresh = forceRefreshSolver)
            ?: return ResolveResult(
                null,
                "V8 JS solver unavailable (${YouTubeJsSolver.lastError.ifBlank { "unknown" }})"
            )
        val cfg = YouTubeSessionConfig.ensureWebConfig(forceRefresh = forceRefreshSolver)
        // Always prefer the exact signatureTimestamp extracted from the active V8 base.js
        // so sts and the V8 cipher solver can never drift out of sync during A/B rollouts.
        var effectiveSts = ready.signatureTimestamp
        var lastReason: String? = null

        // Try TV_SAMSUNG (TVHTML5 5.x) FIRST — works both signed-in AND in guest mode
        // with zero PO token, unthrottled 2 MB chunk streaming up to 4K, and full
        // multi-language Original vs AI-dub audio track metadata!
        run {
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
                val parsed = parseDecryptedPlayerRoot(
                    videoId = videoId,
                    root = tvRoot,
                    userAgent = TV_SAMSUNG_UA,
                    authHeaders = authHeaders,
                    sourceClient = "TV_SAMSUNG"
                )
                if (parsed.info != null &&
                    parsed.info.videoStreams.isNotEmpty() &&
                    parsed.info.audioStreams.isNotEmpty()
                ) {
                    val audioProbe = parsed.info.bestAudio()?.url
                    val videoProbe = parsed.info.pickVideoStream(null)?.url ?: parsed.info.videoStreams.first().url
                    val audio403 = audioProbe != null && isStreamUrlRejected403(audioProbe, TV_SAMSUNG_UA)
                    val video403 = !audio403 && isStreamUrlRejected403(videoProbe, TV_SAMSUNG_UA)
                    if (!audio403 && !video403) {
                        return parsed
                    }
                    Log.w(TAG, "TVHTML5 stream probe returned HTTP 403 for $videoId; trying WEB_SAFARI HLS")
                    lastReason = "TV_SAMSUNG stream rejected (HTTP 403)"
                } else {
                    if (lastReason.isNullOrBlank()) lastReason = parsed.error
                    Log.d(TAG, "TVHTML5 V8 note ($videoId): ${parsed.error}")
                }
            }
        }

        if (includeHlsFallback) {
            var safariFallback: ResolveResult? = null
            val safariRoot = requestWebSafariPlayer(videoId, visitorData, effectiveSts, authHeaders, cfg)
            if (safariRoot != null) {
                val parsed = parseDecryptedPlayerRoot(
                    videoId = videoId,
                    root = safariRoot,
                    userAgent = WEB_SAFARI_UA,
                    authHeaders = authHeaders,
                    sourceClient = "WEB_SAFARI"
                )
                if (parsed.info != null &&
                    (parsed.info.hlsVariants.isNotEmpty() || !parsed.info.hlsUrl.isNullOrBlank())
                ) {
                    val variants = parsed.info.hlsVariants
                    val sampleVariantUrl = variants.firstOrNull()?.url
                    val hls403 = sampleVariantUrl != null && isHlsVariantRejected403(sampleVariantUrl, WEB_SAFARI_UA)
                    if (hls403) {
                        Log.w(TAG, "WEB_SAFARI HLS segment probe returned HTTP 403 for $videoId; trying WEB_EMBEDDED_PLAYER")
                        lastReason = "WEB_SAFARI HLS rejected (HTTP 403)"
                    } else {
                        val onlyDubbedVariants = variants.isNotEmpty() &&
                            variants.none { it.isOriginalAudio } &&
                            variants.all { it.isDubbedOrDescriptive }
                        if (!onlyDubbedVariants) {
                            return parsed
                        }
                        Log.d(TAG, "WEB_SAFARI returned only dubbed HLS variants for $videoId; trying WEB_EMBEDDED_PLAYER")
                        safariFallback = parsed
                    }
                } else {
                    if (lastReason.isNullOrBlank()) lastReason = parsed.error
                    Log.d(TAG, "WEB_SAFARI HLS note ($videoId): ${parsed.error}")
                }
            }

            // Guest-mode / dubbed-HLS safety fallback: WEB_EMBEDDED_PLAYER requires no PO token
            // for direct HTTPS adaptive video + multi-language audio on embeddable videos.
            val embeddedRoot = requestWebEmbeddedPlayer(videoId, visitorData, effectiveSts, authHeaders, cfg)
            if (embeddedRoot != null) {
                val parsed = parseDecryptedPlayerRoot(
                    videoId = videoId,
                    root = embeddedRoot,
                    userAgent = WEB_SAFARI_UA,
                    authHeaders = authHeaders,
                    sourceClient = "WEB_EMBEDDED"
                )
                if (parsed.info != null &&
                    ((parsed.info.videoStreams.isNotEmpty() && parsed.info.audioStreams.isNotEmpty()) ||
                        parsed.info.hlsVariants.isNotEmpty() ||
                        !parsed.info.hlsUrl.isNullOrBlank())
                ) {
                    val probeUrl = parsed.info.bestAudio()?.url
                        ?: parsed.info.videoStreams.firstOrNull()?.url
                    if (probeUrl == null || !isStreamUrlRejected403(probeUrl, WEB_SAFARI_UA)) {
                        return parsed
                    }
                    Log.w(TAG, "WEB_EMBEDDED_PLAYER stream probe returned HTTP 403 for $videoId")
                }
            }
            if (safariFallback != null) {
                return safariFallback
            }
        }

        return ResolveResult(null, lastReason?.takeIf { it.isNotBlank() } ?: "V8 InnerTube returned no playable formats")
    }

    private data class DecryptedStreams(
        val videoStreams: List<StreamFormat>,
        val audioStreams: List<StreamFormat>,
        val muxedStreams: List<StreamFormat>
    )

    /**
     * Lightweight ~150ms GoogleVideo 0-byte range probe: returns true ONLY when
     * GoogleVideo explicitly rejects the decrypted stream URL with HTTP 403, so
     * [resolveViaV8] can immediately skip a broken/rejected client and fall back
     * to `WEB_SAFARI` HLS without bouncing through ExoPlayer.
     */
    private fun isStreamUrlRejected403(url: String, userAgent: String): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Range", "bytes=0-0")
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()
            val probeClient = anonClient.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
            probeClient.newCall(req).execute().use { resp ->
                resp.code == 403
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks an HLS variant playlist + its first media segment for HTTP 403 so
     * broken `/n/` or IP-rejected HLS variants don't pass manifest parsing only
     * to fail immediately inside ExoPlayer.
     */
    private fun isHlsVariantRejected403(variantUrl: String, userAgent: String): Boolean {
        return try {
            val probeClient = anonClient.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(variantUrl)
                .header("User-Agent", userAgent)
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()
            val firstSeg = probeClient.newCall(req).execute().use { resp ->
                if (resp.code == 403) return true
                if (!resp.isSuccessful) return false
                resp.body?.string()?.lineSequence()
                    ?.map { it.trim() }
                    ?.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
            } ?: return false
            val solvedSeg = YouTubeJsSolver.solveUrlNParam(firstSeg)
            isStreamUrlRejected403(solvedSeg, userAgent)
        } catch (_: Exception) {
            false
        }
    }

    private fun requestTvSamsungPlayer(
        videoId: String,
        visitorData: String?,
        signatureTimestamp: Int,
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders?,
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
            put("hl", "en")
            put("gl", "US")
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
            webConfig = null,
            useAnonymousClient = (authHeaders == null)
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
            put("hl", "en")
            put("gl", "US")
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

    private fun requestWebEmbeddedPlayer(
        videoId: String,
        visitorData: String?,
        signatureTimestamp: Int,
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders?,
        cfg: YouTubeSessionConfig.WebClientConfig = YouTubeSessionConfig.ensureWebConfig()
    ): JSONObject? {
        val effectiveVisitor = visitorData?.takeIf { it.isNotBlank() } ?: cfg.visitorData
        val clientObj = JSONObject().apply {
            put("clientName", "WEB_EMBEDDED_PLAYER")
            put("clientVersion", cfg.clientVersion)
            put("userAgent", WEB_SAFARI_UA)
            put("hl", "en")
            put("gl", "US")
            if (!effectiveVisitor.isNullOrBlank()) put("visitorData", effectiveVisitor)
        }
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", clientObj)
                put("thirdParty", JSONObject().apply {
                    put("embedUrl", "https://www.youtube.com/")
                })
            })
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
            clientNameId = "56",
            clientVersion = cfg.clientVersion,
            visitorData = effectiveVisitor,
            authHeaders = authHeaders,
            webConfig = null,
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
        poToken: String? = null,
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders? = null,
        sourceClient: String = ""
    ): ResolveResult {
        val playStatus = root.optJSONObject("playabilityStatus")
        val status = playStatus?.optString("status") ?: ""
        if (status != "OK") {
            val reason = playStatus?.optString("reason").orEmpty()
            return ResolveResult(null, reason.ifBlank { status.ifBlank { "UNPLAYABLE" } })
        }

        val streamingData = root.optJSONObject("streamingData")
            ?: return ResolveResult(null, "No streaming data returned")
        val captions = parseInnerTubeCaptions(root)
        val audioHints = extractOriginalAudioHints(streamingData, captions)

        val rawHlsUrl = streamingData.optString("hlsManifestUrl").takeIf { it.startsWith("http") }
        // Solve `/n/<challenge>/` path segment inside web_safari HLS master manifest
        // via warm V8 solver so all 144p-1080p segments return HTTP 200 (not 403).
        val solvedHlsUrl = rawHlsUrl?.let { YouTubeJsSolver.solveUrlNParam(it) }
        var effectiveHlsUrl: String? = solvedHlsUrl
        var hlsVariants: List<StreamFormat> = emptyList()
        var hlsAudioTracks: List<HlsAudioTrack> = emptyList()
        if (solvedHlsUrl != null) {
            val parsedHls = fetchParsedHlsManifest(
                masterUrl = solvedHlsUrl,
                userAgent = userAgent,
                authHeaders = authHeaders,
                originalLangHint = audioHints.originalLang,
                langDisplayNames = audioHints.langDisplayNames
            )
            if (!parsedHls.ok) {
                Log.w(TAG, "HLS master manifest failed verification ($videoId); skipping broken HLS")
                effectiveHlsUrl = null
            } else {
                hlsVariants = parsedHls.muxedVariants
                hlsAudioTracks = parsedHls.audioTracks
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
        val title = extractPlayerTitle(root, details).ifBlank { "YouTube Video" }
        val channel = extractPlayerChannel(root, details)
        val durationSec = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
        val views = formatViews(details?.optString("viewCount")?.toLongOrNull())
        val publishDate = extractPlayerPublishDate(root)
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
                publishDate = publishDate,
                description = description,
                thumbnailUrl = thumb,
                hlsUrl = effectiveHlsUrl,
                hlsVariants = hlsVariants,
                hlsAudioTracks = hlsAudioTracks,
                videoStreams = videoStreams,
                audioStreams = audioStreams,
                muxedStreams = muxedStreams,
                userAgent = userAgent,
                captionTracks = captions,
                sourceClient = sourceClient
            )
        )
    }

    private data class OriginalAudioHints(
        val originalLang: String? = null,
        val defaultLang: String? = null,
        val langDisplayNames: Map<String, String> = emptyMap()
    )

    private fun baseLangTag(code: String?): String =
        code.orEmpty().trim().substringBefore('.').substringBefore('-').substringBefore('_').lowercase()

    private fun langCodeMatches(a: String?, b: String?): Boolean {
        val ba = baseLangTag(a)
        val bb = baseLangTag(b)
        return ba.isNotBlank() && ba == bb
    }

    /**
     * Scans ALL `adaptiveFormats` & `formats` entries (INCLUDING SABR entries that have
     * no `url` or `signatureCipher`!) to determine the video's true original audio language
     * and human-readable display names — just like `yt-dlp`'s `language_map`.
     * Falls back to the video's auto-generated ASR caption language (`kind == "asr"`),
     * which YouTube always transcribes from the creator's original spoken audio track.
     */
    private fun extractOriginalAudioHints(
        streamingData: JSONObject,
        captions: List<CaptionTrack>
    ): OriginalAudioHints {
        var originalLang: String? = null
        var defaultLang: String? = null
        val displayNames = LinkedHashMap<String, String>()

        fun scanArray(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val mime = obj.optString("mimeType")
                if (!mime.startsWith("audio/") && !obj.has("audioTrack")) continue
                val rawUrl = obj.optString("url")
                val meta = extractInnerTubeAudioMeta(obj, rawUrl)
                val baseLang = baseLangTag(
                    meta.id.ifBlank {
                        obj.optJSONObject("audioTrack")?.optString("languageCode").orEmpty()
                    }
                )
                if (baseLang.isNotBlank()) {
                    if (meta.displayName.isNotBlank()) {
                        val existing = displayNames[baseLang]
                        if (existing == null || (meta.isOriginal && !existing.contains("original", ignoreCase = true))) {
                            displayNames[baseLang] = meta.displayName
                        }
                    }
                    if (meta.isOriginal && originalLang == null) {
                        originalLang = baseLang
                    } else if (meta.isDefault && !meta.isDubbedOrDescriptive && defaultLang == null) {
                        defaultLang = baseLang
                    }
                }
            }
        }

        scanArray(streamingData.optJSONArray("adaptiveFormats"))
        scanArray(streamingData.optJSONArray("formats"))

        if (originalLang == null) {
            val asrLang = captions.firstOrNull {
                it.isAuto && it.langCode.isNotBlank() && !it.langCode.equals("und", ignoreCase = true)
            }?.langCode
            if (!asrLang.isNullOrBlank()) {
                originalLang = baseLangTag(asrLang)
            }
        }
        return OriginalAudioHints(
            originalLang = originalLang ?: defaultLang,
            defaultLang = defaultLang,
            langDisplayNames = displayNames
        )
    }

    private fun niceLanguageName(langCode: String): String {
        if (langCode.isBlank()) return ""
        return try {
            java.util.Locale.forLanguageTag(langCode).getDisplayName(java.util.Locale.ENGLISH)
        } catch (_: Exception) {
            langCode.uppercase()
        }.ifBlank { langCode.uppercase() }
    }

    private data class ParsedHlsManifest(
        val ok: Boolean,
        val muxedVariants: List<StreamFormat> = emptyList(),
        val audioTracks: List<HlsAudioTrack> = emptyList()
    )

    /**
     * Fetches and verifies an HLS master manifest (e.g. `web_safari` / `VISIONOS` 144p-1080p).
     *
     * - For separate-audio manifests (`#EXT-X-MEDIA:TYPE=AUDIO`): extracts EVERY audio
     *   track and cross-references [originalLangHint] (from `adaptiveFormats` / ASR captions)
     *   so ExoPlayer locks onto the creator's ORIGINAL track even if YouTube omits `"original"`
     *   from the HLS manifest or sets `DEFAULT=YES` on a VPN country's dub!
     * - For pre-merged manifests (`#EXT-X-STREAM-INF` itag 91-96): parses `xtags`, `lang`,
     *   `acont`, and [originalLangHint] on EVERY variant so multi-language pre-merged HLS
     *   never picks alphabetical `ar` (Arabic) and exposes all languages in the `🔊` picker!
     */
    private fun fetchParsedHlsManifest(
        masterUrl: String,
        userAgent: String,
        authHeaders: WebViewCookieSync.YouTubeAuthHeaders? = null,
        originalLangHint: String? = null,
        langDisplayNames: Map<String, String> = emptyMap()
    ): ParsedHlsManifest {
        return try {
            val reqBuilder = Request.Builder()
                .url(masterUrl)
                .header("User-Agent", userAgent)
                .header("Accept-Language", "en-US,en;q=0.9")
            if (authHeaders != null) {
                reqBuilder.header("Cookie", sanitizeCookieHeaderForPlayer(authHeaders.cookieHeader))
            }
            anonClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return ParsedHlsManifest(ok = false)
                }
                val body = resp.body?.string().orEmpty()
                if (!body.contains("#EXTM3U")) {
                    return ParsedHlsManifest(ok = false)
                }

                // Separate audio groups require the master manifest so audio stays synced.
                // Parse all #EXT-X-MEDIA:TYPE=AUDIO entries and cross-reference originalLangHint!
                if (body.contains("#EXT-X-MEDIA:") &&
                    (body.contains("TYPE=AUDIO") || body.contains("TYPE=\"AUDIO\""))
                ) {
                    val audioTracks = parseHlsAudioTracks(body, originalLangHint, langDisplayNames)
                    return ParsedHlsManifest(
                        ok = true,
                        muxedVariants = emptyList(),
                        audioTracks = audioTracks
                    )
                }

                val resRegex = Regex("""RESOLUTION=(\d+)x(\d+)""")
                val bwRegex = Regex("""BANDWIDTH=(\d+)""")
                val codecsRegex = Regex("""CODECS="([^"]+)"""")
                val langAttrRegex = Regex("""LANGUAGE="([^"]+)"""")
                val nameAttrRegex = Regex("""NAME="([^"]+)"""")
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

                    // Extract language & original/dub metadata from pre-merged HLS variant URL/tags!
                    val uriXtags = extractXTagsFromUrl(variantUrl)
                    val decodedUri = urlDecode(urlDecode(variantUrl)).lowercase()
                    val acont = uriXtags["acont"].orEmpty().lowercase()
                    val attrLang = langAttrRegex.find(inf)?.groupValues?.get(1).orEmpty()
                    val attrName = nameAttrRegex.find(inf)?.groupValues?.get(1).orEmpty()
                    val resolvedLang = (uriXtags["lang"]?.ifBlank { null } ?: attrLang).trim()
                    val baseLang = baseLangTag(resolvedLang)
                    val hasOrigTag = acont == "original" || decodedUri.contains("acont=original")
                    val hasDubTag = acont.contains("dubbed") ||
                        acont.contains("descriptive") ||
                        acont.contains("auto") ||
                        decodedUri.contains("acont=dubbed") ||
                        decodedUri.contains("acont=descriptive") ||
                        decodedUri.contains("acont=auto")
                    val matchesOrigHint = !originalLangHint.isNullOrBlank() &&
                        baseLang.isNotBlank() &&
                        langCodeMatches(baseLang, originalLangHint)
                    val isOrig = hasOrigTag ||
                        attrName.contains("original", ignoreCase = true) ||
                        (matchesOrigHint && !hasDubTag)
                    val isDub = !isOrig && (
                        hasDubTag ||
                            attrName.contains("dubbed", ignoreCase = true) ||
                            attrName.contains("descriptive", ignoreCase = true) ||
                            (!originalLangHint.isNullOrBlank() && baseLang.isNotBlank() && !matchesOrigHint)
                        )
                    val trackId = if (resolvedLang.isNotBlank()) {
                        when {
                            isOrig -> "$baseLang.4"
                            isDub -> "$baseLang.10"
                            else -> baseLang
                        }
                    } else {
                        ""
                    }
                    val displayName = when {
                        attrName.isNotBlank() -> attrName
                        baseLang.isNotBlank() && !langDisplayNames[baseLang].isNullOrBlank() -> langDisplayNames[baseLang]!!
                        resolvedLang.isNotBlank() -> {
                            val nice = niceLanguageName(resolvedLang)
                            when {
                                isOrig -> "$nice (original)"
                                acont.contains("dubbed-auto") -> "$nice (auto-dubbed)"
                                isDub -> "$nice (dubbed)"
                                else -> nice
                            }
                        }
                        else -> ""
                    }
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
                            isMuxed = true,
                            audioTrackId = trackId,
                            audioDisplayName = displayName,
                            isOriginalAudio = isOrig,
                            isDefaultAudio = isOrig,
                            isDubbedOrDescriptive = isDub
                        )
                    )
                }
                ParsedHlsManifest(ok = true, muxedVariants = out, audioTracks = emptyList())
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchParsedHlsManifest failed: ${e.message}")
            ParsedHlsManifest(ok = false)
        }
    }

    private fun parseHlsAudioTracks(
        m3u8Body: String,
        originalLangHint: String? = null,
        langDisplayNames: Map<String, String> = emptyMap()
    ): List<HlsAudioTrack> {
        val groupRegex = Regex("""GROUP-ID="([^"]+)"""")
        val nameRegex = Regex("""NAME="([^"]+)"""")
        val langRegex = Regex("""LANGUAGE="([^"]+)"""")
        val uriRegex = Regex("""URI="([^"]+)"""")
        val defaultRegex = Regex("""DEFAULT=(YES|NO)""")
        val charRegex = Regex("""CHARACTERISTICS="([^"]+)"""")

        val out = mutableListOf<HlsAudioTrack>()
        for (rawLine in m3u8Body.lineSequence()) {
            val line = rawLine.trim()
            if (!line.startsWith("#EXT-X-MEDIA:") ||
                (!line.contains("TYPE=AUDIO") && !line.contains("TYPE=\"AUDIO\""))
            ) continue
            val groupId = groupRegex.find(line)?.groupValues?.get(1).orEmpty()
            val rawName = nameRegex.find(line)?.groupValues?.get(1).orEmpty()
            val lang = langRegex.find(line)?.groupValues?.get(1).orEmpty()
            val uri = uriRegex.find(line)?.groupValues?.get(1).orEmpty()
            val isDefault = defaultRegex.find(line)?.groupValues?.get(1) == "YES"
            val chars = charRegex.find(line)?.groupValues?.get(1).orEmpty().lowercase()

            val decodedUri = urlDecode(urlDecode(uri)).lowercase()
            val uriXtags = extractXTagsFromUrl(uri)
            val acont = uriXtags["acont"].orEmpty().lowercase()
            val resolvedLang = lang.ifBlank { uriXtags["lang"].orEmpty() }
            val baseLang = baseLangTag(resolvedLang)
            val nameLower = rawName.lowercase()
            val hasOrigTag = acont == "original" || decodedUri.contains("acont=original")
            val hasDubTag = acont.contains("dubbed") ||
                acont.contains("descriptive") ||
                acont.contains("auto") ||
                decodedUri.contains("acont=dubbed") ||
                decodedUri.contains("acont=descriptive") ||
                decodedUri.contains("acont=auto")
            val isDescriptive = nameLower.contains("descriptive") || chars.contains("describes-video")
            val matchesOrigHint = !originalLangHint.isNullOrBlank() &&
                baseLang.isNotBlank() &&
                langCodeMatches(baseLang, originalLangHint)
            val isOrig = hasOrigTag ||
                nameLower.contains("original") ||
                (matchesOrigHint && !hasDubTag && !isDescriptive && !nameLower.contains("dubbed"))
            val isDubOrDesc = !isOrig && (
                hasDubTag ||
                    nameLower.contains("dubbed") ||
                    isDescriptive ||
                    (!originalLangHint.isNullOrBlank() && baseLang.isNotBlank() && !matchesOrigHint)
                )
            val resolvedName = rawName.ifBlank {
                langDisplayNames[baseLang].orEmpty().ifBlank { niceLanguageName(resolvedLang) }
            }
            out.add(
                HlsAudioTrack(
                    groupId = groupId,
                    name = resolvedName,
                    language = resolvedLang,
                    url = uri,
                    isOriginal = isOrig,
                    isDefault = isDefault && !isDubOrDesc,
                    isDubbedOrDescriptive = isDubOrDesc
                )
            )
        }
        return out
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
            val contentLength = obj.optString("contentLength").toLongOrNull()
                ?: obj.optLong("contentLength", 0L).coerceAtLeast(0L)
            val qualityLabel = obj.optString("qualityLabel", if (height > 0) "${height}p" else "")
            val isVideo = mime.startsWith("video/")
            val isAudio = mime.startsWith("audio/")
            val audioMeta = if (isAudio || p.isMuxedDefault) extractInnerTubeAudioMeta(obj, finalUrl) else EMPTY_AUDIO_META
            val fmt = StreamFormat(
                itag = itag,
                url = finalUrl,
                mimeType = mime,
                qualityLabel = qualityLabel,
                height = height,
                bitrate = bitrate,
                isAudioOnly = isAudio,
                isVideoOnly = isVideo && !p.isMuxedDefault,
                isMuxed = p.isMuxedDefault,
                contentLengthBytes = contentLength,
                audioTrackId = audioMeta.id,
                audioDisplayName = audioMeta.displayName,
                isOriginalAudio = audioMeta.isOriginal,
                isDefaultAudio = audioMeta.isDefault,
                isDubbedOrDescriptive = audioMeta.isDubbedOrDescriptive
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
        val captions = parseInnerTubeCaptions(root)
        val audioHints = extractOriginalAudioHints(streamingData, captions)

        val rawHlsUrl = streamingData.optString("hlsManifestUrl").takeIf { it.startsWith("http") }
        var effectiveHlsUrl: String? = rawHlsUrl
        var hlsVariants: List<StreamFormat> = emptyList()
        var hlsAudioTracks: List<HlsAudioTrack> = emptyList()
        if (rawHlsUrl != null) {
            val parsedHls = fetchParsedHlsManifest(
                masterUrl = rawHlsUrl,
                userAgent = userAgent,
                originalLangHint = audioHints.originalLang,
                langDisplayNames = audioHints.langDisplayNames
            )
            if (parsedHls.ok) {
                hlsVariants = parsedHls.muxedVariants
                hlsAudioTracks = parsedHls.audioTracks
            }
        }

        val videoStreams = mutableListOf<StreamFormat>()
        val audioStreams = mutableListOf<StreamFormat>()
        val muxedStreams = mutableListOf<StreamFormat>()

        parseInnerTubeFormats(streamingData.optJSONArray("formats"), isMuxedDefault = true, videoStreams, audioStreams, muxedStreams)
        parseInnerTubeFormats(streamingData.optJSONArray("adaptiveFormats"), isMuxedDefault = false, videoStreams, audioStreams, muxedStreams)

        if (effectiveHlsUrl == null && hlsVariants.isEmpty() && muxedStreams.isEmpty() && (videoStreams.isEmpty() || audioStreams.isEmpty())) {
            return ResolveResult(null, "No playable stream URLs")
        }

        val details = root.optJSONObject("videoDetails")
        val title = extractPlayerTitle(root, details).ifBlank { "YouTube Video" }
        val channel = extractPlayerChannel(root, details)
        val durationSec = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
        val views = formatViews(details?.optString("viewCount")?.toLongOrNull())
        val publishDate = extractPlayerPublishDate(root)
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
                publishDate = publishDate,
                description = description,
                thumbnailUrl = thumb,
                hlsUrl = effectiveHlsUrl,
                hlsVariants = hlsVariants,
                hlsAudioTracks = hlsAudioTracks,
                videoStreams = videoStreams,
                audioStreams = audioStreams,
                muxedStreams = muxedStreams,
                userAgent = userAgent,
                captionTracks = captions,
                sourceClient = clientName
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

    private data class AudioTrackMeta(
        val id: String = "",
        val displayName: String = "",
        val isOriginal: Boolean = false,
        val isDefault: Boolean = false,
        val isDubbedOrDescriptive: Boolean = false
    )

    private val EMPTY_AUDIO_META = AudioTrackMeta()

    /**
     * Parses YouTube's `xtags` metadata from either:
     * 1) Plain colon-separated `key=value:key=value` text (e.g. `acont=original:lang=en`), or
     * 2) Base64-URL-safe Protobuf wire format (`repeated Item { 1: name, 2: value }`)
     *    used by modern InnerTube `adaptiveFormats` objects.
     */
    private fun decodeXTagsMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val decodedStr = urlDecode(urlDecode(raw)).trim()
        val out = LinkedHashMap<String, String>()
        if (decodedStr.contains('=')) {
            for (part in decodedStr.split(':', ';', '&')) {
                val eq = part.indexOf('=')
                if (eq > 0 && eq < part.lastIndex) {
                    val k = part.substring(0, eq).trim().lowercase()
                    val v = part.substring(eq + 1).trim()
                    if (k.isNotBlank() && v.isNotBlank()) out[k] = v
                }
            }
            if (out.isNotEmpty()) return out
        }
        // Try Base64 URL-safe Protobuf wire format: repeated field 1 (length-delimited) -> { 1: key, 2: value }
        val bytes = try {
            android.util.Base64.decode(raw.trim(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            try {
                android.util.Base64.decode(raw.trim(), android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                return emptyMap()
            }
        }
        parseXTagsProtobuf(bytes, 0, bytes.size, out)
        return out
    }

    private fun parseXTagsProtobuf(
        buf: ByteArray,
        start: Int,
        end: Int,
        out: MutableMap<String, String>
    ) {
        var pos = start
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < end && shift < 64) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return -1L
        }
        while (pos < end) {
            val tag = readVarint()
            if (tag <= 0L) break
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            when (wireType) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || pos + len > end) break
                    val msgStart = pos
                    val msgEnd = pos + len
                    pos = msgEnd
                    if (fieldNum == 1) {
                        var innerPos = msgStart
                        var itemKey: String? = null
                        var itemVal: String? = null
                        while (innerPos < msgEnd) {
                            var innerTag = 0L
                            var s = 0
                            while (innerPos < msgEnd && s < 64) {
                                val b = buf[innerPos++].toInt() and 0xFF
                                innerTag = innerTag or ((b and 0x7F).toLong() shl s)
                                if ((b and 0x80) == 0) break
                                s += 7
                            }
                            val innerField = (innerTag ushr 3).toInt()
                            val innerWire = (innerTag and 0x07).toInt()
                            if (innerWire == 2) {
                                var strLen = 0
                                var ls = 0
                                while (innerPos < msgEnd && ls < 32) {
                                    val b = buf[innerPos++].toInt() and 0xFF
                                    strLen = strLen or ((b and 0x7F) shl ls)
                                    if ((b and 0x80) == 0) break
                                    ls += 7
                                }
                                if (strLen < 0 || innerPos + strLen > msgEnd) break
                                val text = String(buf, innerPos, strLen, Charsets.UTF_8)
                                innerPos += strLen
                                if (innerField == 1) itemKey = text.trim().lowercase()
                                else if (innerField == 2) itemVal = text.trim()
                            } else {
                                break
                            }
                        }
                        if (!itemKey.isNullOrBlank() && !itemVal.isNullOrBlank()) {
                            out[itemKey] = itemVal
                        }
                    }
                }
                5 -> pos += 4
                else -> break
            }
        }
    }

    private fun extractXTagsFromUrl(url: String): Map<String, String> {
        if (url.isBlank()) return emptyMap()
        val queryMatch = Regex("""[?&]xtags=([^&]+)""").find(url)?.groupValues?.get(1)
        if (!queryMatch.isNullOrBlank()) {
            val parsed = decodeXTagsMap(queryMatch)
            if (parsed.isNotEmpty()) return parsed
        }
        val pathMatch = Regex("""/xtags/([^/?#]+)""").find(url)?.groupValues?.get(1)
        if (!pathMatch.isNullOrBlank()) {
            val parsed = decodeXTagsMap(pathMatch)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyMap()
    }

    private fun extractInnerTubeAudioMeta(obj: JSONObject, url: String): AudioTrackMeta {
        val at = obj.optJSONObject("audioTrack")
        var id = at?.optString("id").orEmpty()
        var displayName = at?.optString("displayName").orEmpty()
        val langCodeFromJson = at?.optString("languageCode").orEmpty()
        val isAutoDubbedFlag = at?.optBoolean("isAutoDubbed", false) == true
        val rawIsDefault = at?.optBoolean("audioIsDefault", false) == true

        // Read BOTH the JSON `xtags` field (often Base64-encoded Protobuf!) AND URL `xtags`
        val xtagsFromJson = decodeXTagsMap(obj.optString("xtags"))
        val xtagsFromUrl = extractXTagsFromUrl(url)
        val acont = (xtagsFromUrl["acont"] ?: xtagsFromJson["acont"]).orEmpty().lowercase()
        val xLang = (xtagsFromUrl["lang"] ?: xtagsFromJson["lang"] ?: langCodeFromJson).orEmpty()

        val decodedUrl = urlDecode(urlDecode(url)).lowercase()
        val hasOrigTag = acont == "original" || decodedUrl.contains("acont=original")
        val hasDubTag = acont.contains("dubbed") ||
            acont.contains("descriptive") ||
            acont.contains("auto") ||
            decodedUrl.contains("acont=dubbed") ||
            decodedUrl.contains("acont=descriptive") ||
            decodedUrl.contains("acont=auto")
        val idIsOriginal = id.endsWith(".4")
        val idIsDubOrDesc = id.endsWith(".10") || id.endsWith(".2") || id.endsWith(".3")
        val nameLower = displayName.lowercase()
        val nameIsOriginal = !isAutoDubbedFlag && nameLower.contains("original")
        val nameIsDubOrDesc = nameLower.contains("dubbed") || nameLower.contains("descriptive")

        val isOriginal = hasOrigTag || idIsOriginal || nameIsOriginal
        val isDubbedOrDesc = !isOriginal && (isAutoDubbedFlag || hasDubTag || idIsDubOrDesc || nameIsDubOrDesc)

        // Synthesize a clean track id & label if the client omitted `audioTrack` JSON but xtags had lang/acont
        if (id.isBlank() && xLang.isNotBlank()) {
            id = when {
                isOriginal -> "$xLang.4"
                isDubbedOrDesc -> "$xLang.10"
                else -> xLang
            }
        }
        if (displayName.isBlank() && xLang.isNotBlank()) {
            val niceLang = try {
                java.util.Locale.forLanguageTag(xLang).getDisplayName(java.util.Locale.ENGLISH)
            } catch (_: Exception) {
                xLang.uppercase()
            }.ifBlank { xLang.uppercase() }
            displayName = when {
                isOriginal -> "$niceLang (original)"
                isAutoDubbedFlag || acont.contains("dubbed-auto") -> "$niceLang (auto-dubbed)"
                isDubbedOrDesc -> "$niceLang (dubbed)"
                else -> niceLang
            }
        }

        return AudioTrackMeta(
            id = id,
            displayName = displayName,
            isOriginal = isOriginal,
            isDefault = rawIsDefault && !isDubbedOrDesc,
            isDubbedOrDescriptive = isDubbedOrDesc
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
            val audioMeta = if (isAudio || isMuxedDefault) extractInnerTubeAudioMeta(obj, url) else EMPTY_AUDIO_META
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
                contentLengthBytes = contentLength,
                audioTrackId = audioMeta.id,
                audioDisplayName = audioMeta.displayName,
                isOriginalAudio = audioMeta.isOriginal,
                isDefaultAudio = audioMeta.isDefault,
                isDubbedOrDescriptive = audioMeta.isDubbedOrDescriptive
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
            if (err.contains("CANNOT LINK EXECUTABLE", ignoreCase = true)) {
                Log.w(TAG, "yt-dlp fallback linker error: $err")
                return ResolveResult(
                    null,
                    "Fallback video engine couldn't start on this device. Try signing in to YouTube or tap Retry."
                )
            }
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
            val rootOriginalLang = baseLangTag(root.optString("language")).takeIf { it.isNotBlank() }

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
                    // Keep individual muxed HLS variants (e.g. web_safari itag 91-96) WITH full
                    // language & original/dub metadata so pickHlsVariant() always chooses Original
                    // while the 🔊 Audio button exposes every available language.
                    if (vcodec != "none" && vcodec.isNotBlank() &&
                        acodec != "none" && acodec.isNotBlank() &&
                        height > 0
                    ) {
                        val m3u8Note = f.optString("format_note").orEmpty().lowercase()
                        val m3u8LangPref = f.optInt("language_preference", 0)
                        val m3u8Xtags = extractXTagsFromUrl(url)
                        val m3u8Lang = f.optString("language").ifBlank { m3u8Xtags["lang"].orEmpty() }
                        val m3u8BaseLang = baseLangTag(m3u8Lang)
                        val m3u8DecodedUrl = urlDecode(urlDecode(url)).lowercase()
                        val m3u8Acont = m3u8Xtags["acont"].orEmpty().lowercase()
                        val m3u8MatchesRootOrig = !rootOriginalLang.isNullOrBlank() &&
                            m3u8BaseLang.isNotBlank() &&
                            langCodeMatches(m3u8BaseLang, rootOriginalLang)
                        val m3u8HasDubTag = m3u8LangPref < 0 ||
                            m3u8Note.contains("dubbed") ||
                            m3u8Note.contains("descriptive") ||
                            m3u8Acont.contains("dubbed") ||
                            m3u8Acont.contains("descriptive") ||
                            m3u8Acont.contains("auto") ||
                            m3u8DecodedUrl.contains("acont=dubbed") ||
                            m3u8DecodedUrl.contains("acont=descriptive")
                        val m3u8Orig = m3u8LangPref >= 5 ||
                            m3u8Note.contains("original") ||
                            m3u8Acont == "original" ||
                            m3u8DecodedUrl.contains("acont=original") ||
                            (m3u8MatchesRootOrig && !m3u8HasDubTag)
                        val m3u8Dub = !m3u8Orig && (
                            m3u8HasDubTag ||
                                (!rootOriginalLang.isNullOrBlank() && m3u8BaseLang.isNotBlank() && !m3u8MatchesRootOrig)
                            )
                        val m3u8TrackId = if (m3u8BaseLang.isNotBlank()) {
                            when {
                                m3u8Orig -> "$m3u8BaseLang.4"
                                m3u8Dub -> "$m3u8BaseLang.10"
                                else -> m3u8BaseLang
                            }
                        } else {
                            ""
                        }
                        val m3u8DisplayName = if (m3u8Lang.isNotBlank()) {
                            val nice = niceLanguageName(m3u8Lang)
                            when {
                                m3u8Orig -> "$nice (original)"
                                m3u8Dub -> "$nice (dubbed)"
                                else -> nice
                            }
                        } else {
                            ""
                        }
                        val normH = VideoStreamInfo.normalizeHeight(height)
                        hlsVariants.add(
                            StreamFormat(
                                itag = f.optString("format_id").substringBefore('-').toIntOrNull() ?: 0,
                                url = url,
                                mimeType = "application/x-mpegURL; codecs=\"$vcodec, $acodec\"",
                                qualityLabel = "${normH}p",
                                height = normH,
                                bitrate = tbr,
                                isAudioOnly = false,
                                isVideoOnly = false,
                                isMuxed = true,
                                contentLengthBytes = fileSize,
                                audioTrackId = m3u8TrackId,
                                audioDisplayName = m3u8DisplayName,
                                isOriginalAudio = m3u8Orig,
                                isDefaultAudio = m3u8Orig || (!m3u8Dub && m3u8Note.contains("default")),
                                isDubbedOrDescriptive = m3u8Dub
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
                val isAudioOnlyFmt = !hasVideo && hasAudio
                val ytLang = if (isAudioOnlyFmt) f.optString("language").orEmpty() else ""
                val ytNote = if (isAudioOnlyFmt) f.optString("format_note").orEmpty() else ""
                val ytLangPref = if (isAudioOnlyFmt) f.optInt("language_preference", 0) else 0
                val urlLower = url.lowercase()
                val noteLower = ytNote.lowercase()
                val ytOrig = isAudioOnlyFmt && (
                    ytLangPref >= 5 ||
                        noteLower.contains("original") ||
                        urlLower.contains("acont%3doriginal") ||
                        urlLower.contains("acont=original")
                    )
                val ytDefault = isAudioOnlyFmt && (ytOrig || noteLower.contains("default"))
                val ytDubOrDesc = isAudioOnlyFmt && !ytOrig && (
                    ytLangPref < 0 ||
                        noteLower.contains("dubbed") ||
                        noteLower.contains("descriptive") ||
                        urlLower.contains("acont%3ddubbed") ||
                        urlLower.contains("acont=dubbed") ||
                        urlLower.contains("acont%3ddescriptive")
                    )
                val ytTrackId = if (isAudioOnlyFmt && ytLang.isNotBlank()) {
                    if (ytOrig) "$ytLang.4" else ytLang
                } else {
                    ""
                }
                val ytTrackName = if (isAudioOnlyFmt && ytLang.isNotBlank()) {
                    if (ytOrig) "${ytLang.uppercase()} (original)" else ytLang.uppercase()
                } else {
                    ""
                }
                val fmt = StreamFormat(
                    itag = f.optString("format_id").substringBefore('-').toIntOrNull() ?: 0,
                    url = url,
                    mimeType = mime,
                    qualityLabel = if (height > 0) "${height}p" else ext,
                    height = height,
                    bitrate = tbr,
                    isAudioOnly = isAudioOnlyFmt,
                    isVideoOnly = hasVideo && !hasAudio,
                    isMuxed = hasVideo && hasAudio,
                    contentLengthBytes = fileSize,
                    audioTrackId = ytTrackId,
                    audioDisplayName = ytTrackName,
                    isOriginalAudio = ytOrig,
                    isDefaultAudio = ytDefault,
                    isDubbedOrDescriptive = ytDubOrDesc
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

            val effectiveOriginalLang = rootOriginalLang
                ?: audioStreams.firstOrNull { it.isOriginalAudio }?.audioTrackId?.let { baseLangTag(it) }?.takeIf { it.isNotBlank() }
            val parsedHlsAudio = if (!hlsUrl.isNullOrBlank()) {
                fetchParsedHlsManifest(
                    masterUrl = hlsUrl,
                    userAgent = detectedUa,
                    originalLangHint = effectiveOriginalLang
                ).audioTracks
            } else {
                emptyList()
            }
            val captions = parseYtDlpCaptions(root)

            ResolveResult(
                VideoStreamInfo(
                    videoId = videoId,
                    title = root.optString("title").orEmpty().ifBlank { "YouTube Video" },
                    channel = root.optString("uploader", root.optString("channel", "")),
                    durationSec = root.optLong("duration", 0L),
                    viewCount = formatViews(root.optLong("view_count", -1L).takeIf { it >= 0 }),
                    publishDate = formatPublishDate(root.optString("upload_date")),
                    description = root.optString("description", ""),
                    thumbnailUrl = root.optString("thumbnail")
                        .ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
                    hlsUrl = hlsUrl,
                    hlsVariants = hlsVariants,
                    hlsAudioTracks = parsedHlsAudio,
                    videoStreams = videoStreams,
                    audioStreams = audioStreams,
                    muxedStreams = muxedStreams,
                    userAgent = detectedUa,
                    captionTracks = captions,
                    sourceClient = "yt-dlp"
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

    /**
     * Rewrites only the `PREF` cookie inside an outgoing `/youtubei/v1/player`
     * `Cookie` header to `hl=en&gl=US&tz=UTC` (preserving all auth cookies and
     * other `PREF` flags, without mutating WebView's stored cookies). Matches
     * `yt-dlp`'s `_initialize_pref` so a VPN-localized `PREF` cookie never
     * causes YouTube to bake Arabic or another foreign AI dub into HLS streams.
     */
    private fun sanitizeCookieHeaderForPlayer(cookieHeader: String): String {
        if (cookieHeader.isBlank()) return "PREF=hl=en&gl=US&tz=UTC"
        val out = mutableListOf<String>()
        var sawPref = false
        for (part in cookieHeader.split(';')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val eq = trimmed.indexOf('=')
            if (eq > 0 && trimmed.substring(0, eq).trim().equals("PREF", ignoreCase = true)) {
                sawPref = true
                val rawVal = trimmed.substring(eq + 1).trim()
                val prefParams = LinkedHashMap<String, String>()
                for (kv in rawVal.split('&')) {
                    val peq = kv.indexOf('=')
                    if (peq > 0) {
                        prefParams[kv.substring(0, peq)] = kv.substring(peq + 1)
                    }
                }
                prefParams["hl"] = "en"
                prefParams["gl"] = "US"
                prefParams["tz"] = "UTC"
                out.add("PREF=" + prefParams.entries.joinToString("&") { "${it.key}=${it.value}" })
            } else {
                out.add(trimmed)
            }
        }
        if (!sawPref) {
            out.add("PREF=hl=en&gl=US&tz=UTC")
        }
        return out.joinToString("; ")
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
            // On VPN, never let a foreign VPN country's Accept-Language leak onto /player
            // requests (otherwise YouTube bakes that VPN country's AI dub into muxed HLS!).
            if (url.contains("/youtubei/v1/player")) {
                reqBuilder.header("Accept-Language", "en-US,en;q=0.9")
            }

            if (authHeaders != null) {
                // Always attach the complete merged Cookie header (youtube.com +
                // www.youtube.com + m.youtube.com) and execute via anonClient so
                // OkHttp's CookieJar never duplicates the header or drops special-char cookies.
                // On /player requests, normalize PREF to hl=en&gl=US&tz=UTC (just like yt-dlp's
                // _initialize_pref) so a VPN-localized PREF cookie never triggers Arabic/foreign dubs.
                val effectiveCookies = if (url.contains("/youtubei/v1/player")) {
                    sanitizeCookieHeaderForPlayer(authHeaders.cookieHeader)
                } else {
                    authHeaders.cookieHeader
                }
                reqBuilder.header("Cookie", effectiveCookies)
                reqBuilder.header("Authorization", authHeaders.authorizationHeader)
                reqBuilder.header("X-Origin", "https://www.youtube.com")
                reqBuilder.header("X-Goog-AuthUser", "0")
                if (cfg == null) {
                    webConfig?.identityToken?.takeIf { it.isNotBlank() }?.let {
                        reqBuilder.header("X-YouTube-Identity-Token", it)
                    }
                }
            } else if (url.contains("/youtubei/v1/player")) {
                reqBuilder.header("Cookie", "PREF=hl=en&gl=US&tz=UTC")
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
        var publishDate = ""
        var viewCount = ""

        fun scan(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (node.has("videoPrimaryInfoRenderer")) {
                        val pri = node.optJSONObject("videoPrimaryInfoRenderer")
                        val relDate = extractText(pri?.optJSONObject("relativeDateText"))
                        val absDate = extractText(pri?.optJSONObject("dateText"))
                        val chosenDate = relDate.ifBlank { absDate }
                        if (chosenDate.isNotBlank() && publishDate.isBlank()) {
                            publishDate = chosenDate
                        }
                        val vc = pri?.optJSONObject("viewCount")?.optJSONObject("videoViewCountRenderer")
                        val viewsTxt = extractText(vc?.optJSONObject("shortViewCount"))
                            .ifBlank { extractText(vc?.optJSONObject("viewCount")) }
                        if (viewsTxt.isNotBlank() && viewCount.isBlank()) {
                            viewCount = viewsTxt
                        }
                    }
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
            topCommentPreview = topCommentPreview,
            publishDate = publishDate,
            viewCount = viewCount
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
        var views = extractText(obj.optJSONObject("shortViewCountText"))
            .ifBlank { extractText(obj.optJSONObject("viewCountText")) }
        var publishedTime = extractText(obj.optJSONObject("publishedTimeText"))
        if (views.isBlank() || publishedTime.isBlank()) {
            // playlistVideoRenderer packs views + posted date inside videoInfo.runs
            val infoRuns = obj.optJSONObject("videoInfo")?.optJSONArray("runs")
            if (infoRuns != null) {
                for (i in 0 until infoRuns.length()) {
                    val part = infoRuns.optJSONObject(i)?.optString("text").orEmpty()
                        .trim().trim('•', '·', ' ')
                    if (part.isBlank()) continue
                    val low = part.lowercase()
                    when {
                        views.isBlank() && (low.contains("view") || low.contains("watching")) -> views = part
                        publishedTime.isBlank() && looksLikeRelativeOrDate(low) -> publishedTime = part
                    }
                }
            }
        }
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
            publishedTime = publishedTime,
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
        val allParts = mutableListOf<String>()
        if (metaRows != null) {
            for (r in 0 until metaRows.length()) {
                val parts = metaRows.optJSONObject(r)?.optJSONArray("metadataParts") ?: continue
                for (p in 0 until parts.length()) {
                    val txt = parts.optJSONObject(p)
                        ?.optJSONObject("text")
                        ?.optString("content")
                        .orEmpty()
                        .trim()
                    if (txt.isNotBlank()) allParts.add(txt)
                }
            }
        }
        var channel = ""
        var views = ""
        var publishedTime = ""
        for (part in allParts) {
            val low = part.lowercase()
            when {
                views.isBlank() && (low.contains("view") || low.contains("watching")) -> views = part
                publishedTime.isBlank() && looksLikeRelativeOrDate(low) -> publishedTime = part
                channel.isBlank() -> channel = part
            }
        }
        if (channel.isBlank()) {
            channel = allParts.firstOrNull().orEmpty()
        }

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
            publishedTime = if (!isPlaylist) publishedTime else "",
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

    private fun extractPlayerTitle(root: JSONObject, details: JSONObject?): String {
        val fromDetails = details?.optString("title").orEmpty().trim()
        if (fromDetails.isNotBlank()) return fromDetails
        val micro = root.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
        val titleObj = micro?.optJSONObject("title")
        val simple = titleObj?.optString("simpleText").orEmpty().trim()
        if (simple.isNotBlank()) return simple
        val runs = titleObj?.optJSONArray("runs")
        if (runs != null) {
            val sb = StringBuilder()
            for (i in 0 until runs.length()) {
                sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            }
            val joined = sb.toString().trim()
            if (joined.isNotBlank()) return joined
        }
        return ""
    }

    private fun extractPlayerChannel(root: JSONObject, details: JSONObject?): String {
        val fromDetails = details?.optString("author").orEmpty().trim()
        if (fromDetails.isNotBlank()) return fromDetails
        val micro = root.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
        return micro?.optString("ownerChannelName").orEmpty().trim()
    }

    private fun looksLikeRelativeOrDate(lower: String): Boolean {
        return lower.contains("ago") ||
            lower.contains("streamed") ||
            lower.contains("premiered") ||
            lower.contains("yesterday") ||
            lower.contains("today") ||
            Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\b""").containsMatchIn(lower)
    }

    private fun extractPlayerPublishDate(root: JSONObject): String {
        val micro = root.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
        val raw = micro?.optString("publishDate").orEmpty()
            .ifBlank { micro?.optString("uploadDate").orEmpty() }
        return formatPublishDate(raw)
    }

    /** Formats YouTube dates (`2025-11-12`, `2025-11-12T...`, or `20251112`) into friendly labels (`Nov 12, 2025` / `2 days ago`). */
    private fun formatPublishDate(raw: String?): String {
        val clean = raw?.trim()?.substringBefore('T').orEmpty()
        if (clean.isBlank()) return ""
        val cal = try {
            val parts = if (clean.length == 8 && clean.all(Char::isDigit)) {
                Triple(clean.substring(0, 4).toInt(), clean.substring(4, 6).toInt(), clean.substring(6, 8).toInt())
            } else {
                val segs = clean.split('-')
                if (segs.size < 3) return ""
                Triple(segs[0].toInt(), segs[1].toInt(), segs[2].take(2).toInt())
            }
            java.util.Calendar.getInstance().apply {
                set(parts.first, (parts.second - 1).coerceIn(0, 11), parts.third.coerceIn(1, 31), 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
        } catch (_: Exception) {
            return ""
        }
        val diffDays = ((System.currentTimeMillis() - cal.timeInMillis) / 86_400_000L)
        return when {
            diffDays == 0L -> "Today"
            diffDays == 1L -> "Yesterday"
            diffDays in 2L..6L -> "$diffDays days ago"
            else -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.ENGLISH).format(cal.time)
        }
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
