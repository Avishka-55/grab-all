package com.graball

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Lightweight, zero-API-key synced lyrics fetcher for both local music & YouTube audio.
 *
 * Lookup pipeline:
 * 1) Local sidecar .lrc next to MP3/M4A/FLAC (offline, instant)
 * 2) Bounded disk cache in cacheDir/lyrics/ (offline replays, instant)
 * 3) LRCLIB exact match (track + artist + duration)
 * 4) LRCLIB smart search fallback (cleans YouTube title clutter like "(Official Video)",
 *    splits "Artist - Song", picks closest duration with synced lyrics)
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
    private const val USER_AGENT = "GrabAll-Android/1.0 (https://github.com/Avishka-55/grab-all)"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val memCache = LruCache<String, LyricsResult>(24)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
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
        onResult: (LyricsResult?) -> Unit
    ) {
        memCache.get(cacheId)?.let {
            onResult(it)
            return
        }
        val appCtx = context.applicationContext
        executor.execute {
            val result = fetchSync(appCtx, cacheId, rawTitle, rawArtist, durationSec, localFile)
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
        localFile: File?
    ): LyricsResult? {
        // 1) Local .lrc sidecar file next to audio file
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

        // 2) Disk cache
        readDiskCache(context, cacheId)?.let { return it }

        // 3) Clean title & artist (handles YouTube video title noise cleanly)
        val (cleanArtist, cleanTitle) = cleanTrackAndArtist(rawTitle, rawArtist)
        if (cleanTitle.isBlank()) return null

        // 4) LRCLIB exact match
        var result: LyricsResult? = null
        if (cleanArtist.isNotBlank() && durationSec > 10) {
            result = queryLrcLibGet(cleanTitle, cleanArtist, durationSec)
        }

        // 5) LRCLIB smart search with artist + title
        if (result == null && cleanArtist.isNotBlank()) {
            result = queryLrcLibSearch("$cleanArtist $cleanTitle", durationSec)
        }

        // 6) LRCLIB fallback search with cleaned title alone
        if (result == null) {
            result = queryLrcLibSearch(cleanTitle, durationSec)
        }

        if (result != null) {
            writeDiskCache(context, cacheId, result)
        }
        return result
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
                // Skip wildly mismatched durations (>45s off) when we know track length
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

    /**
     * Parse standard LRC text into time-sorted lines.
     * Supports [mm:ss.xx], [mm:ss.xxx], [mm:ss], multi-timestamp chorus lines,
     * and [offset:±ms] headers.
     */
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

    /**
     * Clean YouTube / downloaded filename noise to extract accurate (artist, title).
     */
    fun cleanTrackAndArtist(rawTitle: String, rawArtist: String): Pair<String, String> {
        var title = rawTitle.trim()
        // Drop anything after pipe "|" (e.g. "Song | Movie | Artist")
        if (title.contains("|")) {
            title = title.substringBefore("|").trim()
        }
        // Strip common YouTube bracket/paren tags
        val noisePatterns = listOf(
            Regex("""[\(\[\{][^)\]\}]*(official|video|audio|lyric|lyrics|visualizer|hd|4k|uhd|mv|m/v|live|remaster|explicit|clean|hq|full song|music video|video song|karaoke|slowed|reverb|bass boosted|topic)[^)\]\}]*[\)\]\}]""", RegexOption.IGNORE_CASE),
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
        // If artist looks like a local folder or generic label, ignore it
        if (artist.startsWith("📁") || artist.equals("Local Music", true) ||
            artist.equals("YouTube Audio", true) || artist.equals("GrabAll", true)
        ) {
            artist = ""
        }

        // Split "Artist - Title" when present in YouTube/filename titles
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

    private fun enc(s: String): String =
        try { URLEncoder.encode(s, "UTF-8") } catch (_: Exception) { s }

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
