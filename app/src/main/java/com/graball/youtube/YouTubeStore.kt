package com.graball.youtube

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local-only persistence for the NewPipe-style YouTube player:
 * bookmarks, watch history (with resume positions), recent searches,
 * and playback preferences (SponsorBlock, quality, speed, screen-off audio).
 *
 * Uses Android's built-in org.json and SharedPreferences so it adds zero
 * library bloat and minimal RAM.
 */
object YouTubeStore {

    private const val PREFS = "graball_yt_prefs"
    private const val KEY_SPONSORBLOCK = "sponsorblock_enabled"
    private const val KEY_BG_AUDIO_SCREEN_OFF = "bg_audio_screen_off"
    private const val KEY_DEFAULT_HEIGHT = "default_height" // 0 = Auto
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_CAPTION_TEXT_SIZE = "caption_text_size" // 0 = Small, 1 = Medium, 2 = Large
    private const val MAX_HISTORY = 100
    private const val MAX_BOOKMARKS = 200
    private const val MAX_SEARCHES = 15

    private fun storeDir(context: Context): File =
        File(context.filesDir, "youtube").apply { if (!exists()) mkdirs() }

    private fun bookmarksFile(context: Context): File = File(storeDir(context), "bookmarks.json")
    private fun historyFile(context: Context): File = File(storeDir(context), "history.json")
    private fun searchesFile(context: Context): File = File(storeDir(context), "searches.json")
    private fun homeFeedCacheFile(context: Context): File = File(storeDir(context), "home_feed_cache.json")

    data class CachedHomeFeed(
        val items: List<YtItem>,
        val chips: List<YouTubeInnerTube.FeedChip>,
        val savedAtMs: Long
    )

    // ---- Preferences ----

    fun isSponsorBlockEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SPONSORBLOCK, true)

    fun setSponsorBlockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SPONSORBLOCK, enabled)
            .apply()
    }

    fun isScreenOffAudioOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BG_AUDIO_SCREEN_OFF, true)

    fun setScreenOffAudioOnly(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BG_AUDIO_SCREEN_OFF, enabled)
            .apply()
    }

    /** 0 means Auto; otherwise 360, 480, 720, 1080. */
    fun getDefaultHeight(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_DEFAULT_HEIGHT, 0)

    fun setDefaultHeight(context: Context, height: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DEFAULT_HEIGHT, height)
            .apply()
    }

    fun getPlaybackSpeed(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED, 1.0f)

    fun setPlaybackSpeed(context: Context, speed: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SPEED, speed)
            .apply()
    }

    /** 0 = Small, 1 = Medium (default), 2 = Large. */
    fun getCaptionTextSize(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CAPTION_TEXT_SIZE, 1)
            .coerceIn(0, 2)

    fun setCaptionTextSize(context: Context, size: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CAPTION_TEXT_SIZE, size.coerceIn(0, 2))
            .apply()
    }

    // ---- Bookmarks ----

    @Synchronized
    fun getBookmarks(context: Context): List<YtItem> {
        val file = bookmarksFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i -> itemFromJson(arr.optJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun isBookmarked(context: Context, id: String): Boolean =
        getBookmarks(context).any { it.id == id }

    /** Toggle bookmark and return new bookmarked state. */
    @Synchronized
    fun toggleBookmark(context: Context, item: YtItem): Boolean {
        val current = getBookmarks(context).toMutableList()
        val existed = current.removeAll { it.id == item.id }
        val nowBookmarked = if (existed) {
            false
        } else {
            current.add(0, item)
            while (current.size > MAX_BOOKMARKS) current.removeAt(current.lastIndex)
            true
        }
        saveItems(bookmarksFile(context), current)
        return nowBookmarked
    }

    // ---- Watch History (with resume positions) ----

    @Synchronized
    fun getHistory(context: Context): List<WatchHistoryEntry> {
        val file = historyFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val item = itemFromJson(obj.optJSONObject("item")) ?: return@mapNotNull null
                WatchHistoryEntry(
                    item = item,
                    positionMs = obj.optLong("pos", 0L),
                    watchedAt = obj.optLong("ts", 0L)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun recordHistory(context: Context, item: YtItem, positionMs: Long = 0L) {
        if (item.isPlaylist) return
        val list = getHistory(context).toMutableList()
        val existingPos = list.firstOrNull { it.item.id == item.id }?.positionMs ?: 0L
        list.removeAll { it.item.id == item.id }
        list.add(
            0,
            WatchHistoryEntry(
                item = item,
                positionMs = if (positionMs > 0L) positionMs else existingPos,
                watchedAt = System.currentTimeMillis()
            )
        )
        while (list.size > MAX_HISTORY) list.removeAt(list.lastIndex)
        try {
            val arr = JSONArray()
            list.forEach { entry ->
                arr.put(
                    JSONObject().apply {
                        put("item", itemToJson(entry.item))
                        put("pos", entry.positionMs)
                        put("ts", entry.watchedAt)
                    }
                )
            }
            historyFile(context).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun getSavedPosition(context: Context, videoId: String): Long =
        getHistory(context).firstOrNull { it.item.id == videoId }?.positionMs ?: 0L

    @Synchronized
    fun clearHistory(context: Context) {
        try {
            historyFile(context).delete()
        } catch (_: Exception) {
        }
    }

    // ---- Search History ----

    @Synchronized
    fun getRecentSearches(context: Context): List<String> {
        val file = searchesFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun recordSearch(context: Context, query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        val list = getRecentSearches(context).toMutableList()
        list.removeAll { it.equals(clean, ignoreCase = true) }
        list.add(0, clean)
        while (list.size > MAX_SEARCHES) list.removeAt(list.lastIndex)
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            searchesFile(context).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    // ---- Home Feed Disk Cache (instant 0ms open) ----

    @Synchronized
    fun saveHomeFeedCache(
        context: Context,
        accountKey: String,
        items: List<YtItem>,
        chips: List<YouTubeInnerTube.FeedChip>
    ) {
        if (items.isEmpty()) return
        try {
            val root = JSONObject().apply {
                put("account", accountKey)
                put("ts", System.currentTimeMillis())
                val itemsArr = JSONArray()
                items.take(60).forEach { itemsArr.put(itemToJson(it)) }
                put("items", itemsArr)
                val chipsArr = JSONArray()
                chips.take(16).forEach { chip ->
                    chipsArr.put(
                        JSONObject().apply {
                            put("label", chip.label)
                            if (!chip.continuationToken.isNullOrBlank()) {
                                put("token", chip.continuationToken)
                            }
                            put("selected", chip.selected)
                        }
                    )
                }
                put("chips", chipsArr)
            }
            homeFeedCacheFile(context).writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun loadHomeFeedCache(context: Context, accountKey: String): CachedHomeFeed? {
        val file = homeFeedCacheFile(context)
        if (!file.isFile) return null
        return try {
            val root = JSONObject(file.readText())
            if (root.optString("account") != accountKey) return null
            val itemsArr = root.optJSONArray("items") ?: return null
            val items = (0 until itemsArr.length()).mapNotNull { i -> itemFromJson(itemsArr.optJSONObject(i)) }
            if (items.isEmpty()) return null
            val chipsArr = root.optJSONArray("chips")
            val chips = if (chipsArr != null) {
                (0 until chipsArr.length()).mapNotNull { i ->
                    val c = chipsArr.optJSONObject(i) ?: return@mapNotNull null
                    val label = c.optString("label").trim()
                    if (label.isBlank()) return@mapNotNull null
                    YouTubeInnerTube.FeedChip(
                        label = label,
                        continuationToken = c.optString("token").takeIf { it.isNotBlank() },
                        selected = c.optBoolean("selected", false)
                    )
                }
            } else {
                emptyList()
            }
            CachedHomeFeed(
                items = items,
                chips = chips,
                savedAtMs = root.optLong("ts", 0L)
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---- JSON helpers ----

    private fun saveItems(file: File, items: List<YtItem>) {
        try {
            val arr = JSONArray()
            items.forEach { arr.put(itemToJson(it)) }
            file.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun itemToJson(item: YtItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("title", item.title)
        put("channel", item.channel)
        put("duration", item.duration)
        put("views", item.views)
        put("thumb", item.thumbnailUrl)
        put("playlist", item.isPlaylist)
        put("count", item.videoCount)
        if (item.isLive) put("live", true)
        if (item.channelId.isNotBlank()) put("cid", item.channelId)
        if (item.channelAvatarUrl.isNotBlank()) put("cavatar", item.channelAvatarUrl)
    }

    private fun itemFromJson(obj: JSONObject?): YtItem? {
        if (obj == null) return null
        val id = obj.optString("id")
        if (id.isBlank()) return null
        return YtItem(
            id = id,
            title = obj.optString("title", "Untitled"),
            channel = obj.optString("channel", ""),
            duration = obj.optString("duration", ""),
            views = obj.optString("views", ""),
            thumbnailUrl = obj.optString("thumb", ""),
            isPlaylist = obj.optBoolean("playlist", false),
            videoCount = obj.optString("count", ""),
            isLive = obj.optBoolean("live", false),
            channelId = obj.optString("cid", ""),
            channelAvatarUrl = obj.optString("cavatar", "")
        )
    }
}
