package com.graball

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Fast, parallel, zero-API-key multi-source lyrics engine for both local MP3s
 * and YouTube audio.
 *
 * Speed-first architecture (typical synced hit in ~250-450ms; guaranteed
 * found/not-found resolution within ~4.5s max so users never wait endlessly):
 * 1) Local .lrc sidecar & 4 MB offline disk cache (0ms, works offline)
 * 2) Parallel race with tight 4s/5s timeouts:
 *    - Primary:  LRCLIB (synced [mm:ss.xx] -> plain) — early-exits immediately on synced hit!
 *    - Fallback: YouTube Music InnerTube (official LyricFind/Musixmatch lyrics)
 *    - Fallback: YouTube Video Timed Captions (timed JSON3 subtitles -> synced lines!)
 *    - Fallback: lyrics.ovh (plain backup for local MP3s & YouTube tracks)
 */
object LyricsFetcher {

    data class LyricLine(
        val timeMs: Long, // -1L for plain/unsynced lyrics
        val text: String
    )

    data class LyricsResult(
        val lines: List<LyricLine>,
        val isSynced: Boolean,
        val source: String
    )

    private const val MAX_DISK_BYTES = 4L * 1024L * 1024L
    private const val USER_AGENT = "GrabAll-Android/1.1 (https://github.com/Avishka-55/grab-all)"
    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val coordinator = Executors.newSingleThreadExecutor()
    private val racePool = Executors.newFixedThreadPool(4)
    private val memCache = LruCache<String, LyricsResult>(32)

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val LRC_TIME_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val LRC_OFFSET_REGEX = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /** Fetch lyrics off the main thread and deliver result on the main thread. */
    fun fetchAsync(
        context: Context,
        cacheId: String,
        rawTitle: String,
        rawArtist: String,
        durationSec: Int,
        localFile: File? = null,
        youtubeVideoId: String? = null,
        forceRefresh: Boolean = false,
        onResult: (LyricsResult?) -> Unit
    ) {
        if (!forceRefresh) {
            memCache.get(cacheId)?.let {
                onResult(it)
                return
            }
        }
        val appCtx = context.applicationContext
        coordinator.execute {
            val result = fetchSync(
                appCtx, cacheId, rawTitle, rawArtist, durationSec,
                localFile, youtubeVideoId, forceRefresh
            )
            if (result != null) {
                memCache.put(cacheId, result)
            }
            mainHandler.post { onResult(result) }
        }
    }

    private fun fetchSync(
        context: Context,
        cacheId: String,
        rawTitle: String,
        rawArtist: String,
        durationSec: Int,
        localFile: File?,
        youtubeVideoId: String?,
        forceRefresh: Boolean
    ): LyricsResult? {
        // 1) Local .lrc sidecar file next to audio file (0ms, offline)
        if (localFile != null) {
            val sidecar = File(localFile.parentFile, "${localFile.nameWithoutExtension}.lrc")
            if (sidecar.isFile && sidecar.length() in 10L..512_000L) {
                try {
                    val parsed = parseLrc(sidecar.readText())
                    if (parsed.isNotEmpty()) {
                        return LyricsResult(parsed, isSynced = true, source = "Local .lrc")
                    }
                } catch (_: Exception) {
                }
            }
        }

        // 2) Disk cache (0ms, offline)
        if (!forceRefresh) {
            readDiskCache(context, cacheId)?.let { return it }
        }

        // 3) Clean title & artist (handles YouTube video title noise cleanly)
        val (cleanArtist, cleanTitle) = cleanTrackAndArtist(rawTitle, rawArtist)
        if (cleanTitle.isBlank() && youtubeVideoId.isNullOrBlank()) return null

        // 4) Parallel race with early exit on synced LRCLIB hit + hard 4.5s cap
        val lrcRes = AtomicReference<LyricsResult?>(null)
        val ytMusicRes = AtomicReference<LyricsResult?>(null)
        val ytCaptionsRes = AtomicReference<LyricsResult?>(null)
        val ovhRes = AtomicReference<LyricsResult?>(null)

        val tasks = mutableListOf<() -> Unit>()
        if (cleanTitle.isNotBlank()) {
            tasks.add { lrcRes.set(fetchFromLrcLib(cleanTitle, cleanArtist, durationSec)) }
        }
        if (!youtubeVideoId.isNullOrBlank()) {
            tasks.add { ytMusicRes.set(fetchFromYouTubeMusic(youtubeVideoId)) }
            tasks.add { ytCaptionsRes.set(fetchFromYouTubeCaptions(youtubeVideoId)) }
        }
        if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank()) {
            tasks.add { ovhRes.set(fetchFromLyricsOvh(cleanArtist, cleanTitle)) }
        }

        if (tasks.isEmpty()) return null

        val doneLatch = CountDownLatch(tasks.size)
        val earlySyncedLatch = CountDownLatch(1)

        tasks.forEachIndexed { idx, work ->
            racePool.execute {
                try {
                    work()
                    // Early-exit signal the instant LRCLIB returns time-synced lyrics!
                    if (idx == 0 && lrcRes.get()?.isSynced == true) {
                        earlySyncedLatch.countDown()
                    }
                } catch (_: Throwable) {
                } finally {
                    doneLatch.countDown()
                    if (doneLatch.count == 0L) {
                        earlySyncedLatch.countDown()
                    }
                }
            }
        }

        // Wait until either: (a) LRCLIB synced hit arrives, (b) all sources finish, or (c) 4.5s cap
        try {
            earlySyncedLatch.await(4500L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }

        // Pick best available result in strict quality priority
        val best = lrcRes.get()?.takeIf { it.isSynced }
            ?: ytCaptionsRes.get()?.takeIf { it.isSynced }
            ?: ytMusicRes.get()
            ?: lrcRes.get()
            ?: ovhRes.get()

        if (best != null) {
            writeDiskCache(context, cacheId, best)
        }
        return best
    }

    // ---- Source 1: LRCLIB (Primary Synced) ----

    private fun fetchFromLrcLib(title: String, artist: String, durationSec: Int): LyricsResult? {
        if (artist.isNotBlank() && durationSec > 10) {
            queryLrcLibGet(title, artist, durationSec)?.let { if (it.isSynced) return it }
        }
        if (artist.isNotBlank()) {
            queryLrcLibSearch("$artist $title", durationSec)?.let { return it }
        }
        return queryLrcLibSearch(title, durationSec)
    }

    private fun queryLrcLibGet(title: String, artist: String, durationSec: Int): LyricsResult? {
        val url = "https://lrclib.net/api/get" +
            "?track_name=${enc(title)}" +
            "&artist_name=${enc(artist)}" +
            "&duration=$durationSec"
        val body = httpGet(url) ?: return null
        return try {
            parseLrcLibItem(JSONObject(body))
        } catch (_: Exception) {
            null
        }
    }

    private fun queryLrcLibSearch(query: String, durationSec: Int): LyricsResult? {
        val url = "https://lrclib.net/api/search?q=${enc(query)}"
        val body = httpGet(url) ?: return null
        return try {
            val arr = JSONArray(body)
            var bestSynced: LyricsResult? = null
            var bestSyncedDiff = Int.MAX_VALUE
            var bestPlain: LyricsResult? = null
            var bestPlainDiff = Int.MAX_VALUE

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optBoolean("instrumental", false)) continue
                val dur = obj.optDouble("duration", 0.0).toInt()
                val diff = if (durationSec > 10 && dur > 0) abs(dur - durationSec) else 0
                if (durationSec > 20 && dur > 0 && diff > 45) continue

                val parsed = parseLrcLibItem(obj) ?: continue
                if (parsed.isSynced) {
                    if (diff < bestSyncedDiff) {
                        bestSyncedDiff = diff
                        bestSynced = parsed
                    }
                } else if (diff < bestPlainDiff) {
                    bestPlainDiff = diff
                    bestPlain = parsed
                }
            }
            bestSynced ?: bestPlain
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLrcLibItem(obj: JSONObject): LyricsResult? {
        val synced = obj.optString("syncedLyrics").trim()
        if (synced.isNotBlank() && synced != "null") {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) {
                return LyricsResult(lines, isSynced = true, source = "LRCLIB · Synced")
            }
        }
        val plain = obj.optString("plainLyrics").trim()
        if (plain.isNotBlank() && plain != "null") {
            val lines = plain.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { LyricLine(-1L, it) }
            if (lines.isNotEmpty()) {
                return LyricsResult(lines, isSynced = false, source = "LRCLIB · Plain")
            }
        }
        return null
    }

    // ---- Source 2: YouTube Music InnerTube (LyricFind / Musixmatch) ----

    private fun fetchFromYouTubeMusic(videoId: String): LyricsResult? {
        return try {
            val ctx = JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", "WEB_REMIX")
                    .put("clientVersion", "1.20260708.01.00")
                    .put("hl", "en")
                    .put("gl", "US")
            )
            val nextBody = JSONObject().put("context", ctx).put("videoId", videoId)
            val nextJson = httpPostJson(
                "https://music.youtube.com/youtubei/v1/next?prettyPrint=false",
                nextBody,
                "https://music.youtube.com"
            ) ?: return null

            val browseId = findStringValueStartingWith(nextJson, "browseId", "MPLY") ?: return null
            val browseBody = JSONObject().put("context", ctx).put("browseId", browseId)
            val browseJson = httpPostJson(
                "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false",
                browseBody,
                "https://music.youtube.com"
            ) ?: return null

            val shelf = findObjectByKey(browseJson, "musicDescriptionShelfRenderer") ?: return null
            val desc = shelf.optJSONObject("description") ?: return null
            val runs = desc.optJSONArray("runs") ?: return null
            val sb = StringBuilder()
            for (i in 0 until runs.length()) {
                sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            }
            val lines = sb.toString().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { LyricLine(-1L, it) }
            if (lines.size >= 3) {
                LyricsResult(lines, isSynced = false, source = "YouTube Music")
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---- Source 3: YouTube Video Timed Captions (Synced, great for regional songs) ----

    private fun fetchFromYouTubeCaptions(videoId: String): LyricsResult? {
        return try {
            val body = JSONObject().apply {
                put(
                    "context",
                    JSONObject().put(
                        "client",
                        JSONObject()
                            .put("clientName", "WEB")
                            .put("clientVersion", "2.20260708.00.00")
                            .put("hl", "en")
                            .put("gl", "US")
                    )
                )
                put("videoId", videoId)
            }
            val playerJson = httpPostJson(
                "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                body,
                "https://www.youtube.com"
            ) ?: return null

            val tracks = playerJson.optJSONObject("captions")
                ?.optJSONObject("playerCaptionsTracklistRenderer")
                ?.optJSONArray("captionTracks")
                ?: return null
            if (tracks.length() == 0) return null

            // Prefer manual (non-ASR) captions over auto-generated speech captions
            var chosenUrl: String? = null
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                val baseUrl = t.optString("baseUrl")
                if (!baseUrl.startsWith("http")) continue
                val isAsr = t.optString("kind") == "asr"
                if (!isAsr) {
                    chosenUrl = baseUrl
                    break
                }
            }
            // Only use human-authored captions (skip noisy auto-generated [Music] ASR)
            val url = chosenUrl ?: return null
            val json3Url = if (url.contains("fmt=")) url else "$url&fmt=json3"
            val raw = httpGet(json3Url) ?: return null
            val events = JSONObject(raw).optJSONArray("events") ?: return null
            val out = mutableListOf<LyricLine>()
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                val startMs = ev.optLong("tStartMs", -1L)
                if (startMs < 0L) continue
                val segs = ev.optJSONArray("segs") ?: continue
                val sb = StringBuilder()
                for (j in 0 until segs.length()) {
                    sb.append(segs.optJSONObject(j)?.optString("utf8").orEmpty())
                }
                val text = sb.toString().replace("\n", " ").replace(Regex("\\s+"), " ").trim()
                if (text.isNotEmpty() && !text.equals("[Music]", ignoreCase = true) && text != "♪") {
                    out.add(LyricLine(startMs, text))
                }
            }
            if (out.size >= 4) {
                LyricsResult(out.sortedBy { it.timeMs }, isSynced = true, source = "YouTube Captions · Synced")
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---- Source 4: lyrics.ovh (Plain Fallback) ----

    private fun fetchFromLyricsOvh(artist: String, title: String): LyricsResult? {
        return try {
            val url = "https://api.lyrics.ovh/v1/${encPath(artist)}/${encPath(title)}"
            val raw = httpGet(url) ?: return null
            val lyrics = JSONObject(raw).optString("lyrics").trim()
            if (lyrics.isBlank()) return null
            val lines = lyrics.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("Paroles de la chanson", ignoreCase = true) }
                .map { LyricLine(-1L, it) }
            if (lines.size >= 3) {
                LyricsResult(lines, isSynced = false, source = "lyrics.ovh · Plain")
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---- LRC & Title Helpers ----

    fun parseLrc(lrc: String): List<LyricLine> {
        var offsetMs = 0L
        LRC_OFFSET_REGEX.find(lrc)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
            offsetMs = it
        }
        val out = mutableListOf<LyricLine>()
        for (rawLine in lrc.lines()) {
            val matches = LRC_TIME_REGEX.findAll(rawLine).toList()
            if (matches.isEmpty()) continue
            val text = rawLine.replace(LRC_TIME_REGEX, "").trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = m.groupValues[3]
                val fracMs = when (fracStr.length) {
                    0 -> 0L
                    1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                    else -> fracStr.take(3).toLongOrNull() ?: 0L
                }
                val t = (min * 60_000L + sec * 1000L + fracMs - offsetMs).coerceAtLeast(0L)
                out.add(LyricLine(t, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    fun cleanTrackAndArtist(rawTitle: String, rawArtist: String): Pair<String, String> {
        var title = rawTitle.trim()
        if (title.contains("|")) {
            title = title.substringBefore("|").trim()
        }
        val noisePatterns = listOf(
            Regex("""[\(\[\{][^)\]\}]*(official|video|audio|lyric|lyrics|visualizer|hd|4k|uhd|mv|m/v|live|remaster|explicit|clean|hq|full song|music video|video song|karaoke|slowed|reverb|bass boosted|topic|mtv|vma)[^)\]\}]*[\)\]\}]""", RegexOption.IGNORE_CASE),
            Regex("""\s*(ft\.?|feat\.?|featuring)\s+[^-\(\[\|]+""", RegexOption.IGNORE_CASE),
            Regex("""#\S+""")
        )
        for (p in noisePatterns) {
            title = title.replace(p, " ")
        }
        title = title.replace(Regex("""\s{2,}"""), " ").trim(' ', '-', '–', '—', '_')

        var artist = rawArtist
            .replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""VEVO$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""Official$""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (artist.startsWith("📁") || artist.equals("Local Music", true) ||
            artist.equals("YouTube Audio", true) || artist.equals("GrabAll", true)
        ) {
            artist = ""
        }

        val dashSplit = Regex("""\s+[-–—]\s+""").split(title, limit = 2)
        if (dashSplit.size == 2 && dashSplit[0].length in 2..45 && dashSplit[1].length >= 2) {
            artist = dashSplit[0].trim()
            title = dashSplit[1].trim()
        }

        return artist to title
    }

    private fun httpGet(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun httpPostJson(url: String, body: JSONObject, origin: String): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", WEB_UA)
                .header("Origin", origin)
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val txt = resp.body?.string() ?: return null
                JSONObject(txt)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun findStringValueStartingWith(node: Any?, keyName: String, prefix: String): String? {
        when (node) {
            is JSONObject -> {
                val direct = node.optString(keyName)
                if (direct.startsWith(prefix)) return direct
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = findStringValueStartingWith(node.opt(keys.next()), keyName, prefix)
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = findStringValueStartingWith(node.opt(i), keyName, prefix)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun findObjectByKey(node: Any?, keyName: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(keyName)?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = findObjectByKey(node.opt(keys.next()), keyName)
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = findObjectByKey(node.opt(i), keyName)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun enc(s: String): String =
        try { URLEncoder.encode(s, "UTF-8") } catch (_: Exception) { s }

    private fun encPath(s: String): String =
        enc(s).replace("+", "%20")

    // ---- Disk Cache (bounded to 4 MB) ----

    private fun cacheFileFor(context: Context, cacheId: String): File {
        val dir = File(context.cacheDir, "lyrics").apply { if (!exists()) mkdirs() }
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(cacheId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 20)
        return File(dir, "$hash.json")
    }

    private fun readDiskCache(context: Context, cacheId: String): LyricsResult? {
        val f = cacheFileFor(context, cacheId)
        if (!f.isFile || f.length() == 0L) return null
        return try {
            val obj = JSONObject(f.readText())
            val synced = obj.optBoolean("synced", false)
            val source = obj.optString("source", "Cached")
            val arr = obj.optJSONArray("lines") ?: return null
            val lines = mutableListOf<LyricLine>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                lines.add(LyricLine(item.optLong("t", -1L), item.optString("s")))
            }
            if (lines.isEmpty()) null else LyricsResult(lines, synced, source)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDiskCache(context: Context, cacheId: String, result: LyricsResult) {
        try {
            val obj = JSONObject()
            obj.put("synced", result.isSynced)
            obj.put("source", result.source)
            val arr = JSONArray()
            for (l in result.lines) {
                arr.put(JSONObject().put("t", l.timeMs).put("s", l.text))
            }
            obj.put("lines", arr)
            val f = cacheFileFor(context, cacheId)
            f.writeText(obj.toString())
            pruneCacheIfNeeded(f.parentFile)
        } catch (_: Exception) {
        }
    }

    private fun pruneCacheIfNeeded(dir: File?) {
        if (dir == null || !dir.isDirectory) return
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            val len = f.length()
            if (f.delete()) total -= len
            if (total <= MAX_DISK_BYTES * 3 / 4) break
        }
    }
}
