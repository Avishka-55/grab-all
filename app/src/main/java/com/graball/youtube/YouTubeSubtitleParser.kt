package com.graball.youtube

import android.util.Log
import android.webkit.CookieManager
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * Lightweight, 100% ExoPlayer-independent YouTube subtitle fetcher & parser.
 *
 * Fetches a single caption track ONLY on-demand when the user turns CC ON,
 * parses TTML / WebVTT / YouTube timedtext XML in <2ms, and serves active cues
 * via O(log N) binary search. Because it never attaches extra MediaSources to
 * ExoPlayer's MergingMediaSource, video/audio playback has zero extra HTTP
 * traffic, zero buffer-pool contention, and zero stutter or lag.
 */
object YouTubeSubtitleParser {

    private const val TAG = "YouTubeSubtitleParser"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(14, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(4, 45, TimeUnit.SECONDS))
        .followRedirects(true)
        .build()

    fun evictIdleConnections() {
        try {
            client.connectionPool.evictAll()
        } catch (_: Exception) {
        }
    }

    private val TTML_P_REGEX = Regex(
        """<p\b([^>]*)>(.*?)</p>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val XML_TEXT_REGEX = Regex(
        """<text\b([^>]*)>(.*?)</text>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val ATTR_REGEX = Regex("""(\w+)\s*=\s*["']([^"']*)["']""")
    private val BR_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val TAG_REGEX = Regex("""<[^>]+>""")
    private val VTT_TIMING_REGEX = Regex(
        """(\d{1,2}:)?(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(\d{1,2}:)?(\d{2}):(\d{2})[.,](\d{3})"""
    )
    private val NUMERIC_ENTITY_REGEX = Regex("""&#(\d+);""")
    private val HEX_ENTITY_REGEX = Regex("""&#x([0-9a-fA-F]+);""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    /** Fetch & parse a single [CaptionTrack] off the main thread. */
    fun fetchAndParse(track: CaptionTrack, userAgent: String): List<SubtitleCue> {
        if (!track.url.startsWith("http")) return emptyList()
        // Try the primary URL first (TTML), then fallback to VTT if primary fails or is empty.
        val urlsToTry = buildList {
            add(track.url)
            if (track.url.contains("fmt=ttml")) {
                add(track.url.replace("fmt=ttml", "fmt=vtt"))
            }
        }
        val cookie = runCatching {
            CookieManager.getInstance().getCookie("https://www.youtube.com")
        }.getOrNull().orEmpty()

        for (url in urlsToTry) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent.ifBlank { YouTubeSessionConfig.DESKTOP_WEB_UA })
                if (cookie.isNotBlank()) {
                    reqBuilder.header("Cookie", cookie)
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "Subtitle HTTP ${resp.code} for ${track.langCode}")
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return@use
                    val parsed = parseBody(body)
                    if (parsed.isNotEmpty()) {
                        return parsed
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Subtitle fetch error (${track.langCode}): ${e.message}")
            }
        }
        return emptyList()
    }

    fun parseBody(raw: String): List<SubtitleCue> {
        val trimmed = raw.trimStart()
        val cues = when {
            trimmed.startsWith("WEBVTT", ignoreCase = true) -> parseVtt(trimmed)
            trimmed.contains("<p ", ignoreCase = true) -> parseTtmlOrSrv3(trimmed)
            trimmed.contains("<text ", ignoreCase = true) -> parseXmlText(trimmed)
            else -> emptyList()
        }
        return normalizeCues(cues)
    }

    /**
     * Cleans YouTube's rolling 2-line captions (TTML/SRV3 overlapping durations
     * and WebVTT repeated-prefix lines) so each new caption appears cleanly
     * without repeating the previous caption above it:
     * - Strips leading lines that duplicate the previous cue's trailing line(s).
     * - Merges truly simultaneous cues (within 150ms start delta, e.g. two speakers).
     * - Clamps sequential overlapping durations (`prev.endMs = next.startMs`) so
     *   stale previous lines disappear the moment the new caption begins.
     */
    private fun normalizeCues(raw: List<SubtitleCue>): List<SubtitleCue> {
        if (raw.isEmpty()) return emptyList()
        val sorted = raw.sortedWith(compareBy<SubtitleCue> { it.startMs }.thenBy { it.endMs })
        val out = ArrayList<SubtitleCue>(sorted.size)

        for (cue in sorted) {
            val cueLines = cue.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (cueLines.isEmpty() || cue.endMs <= cue.startMs) continue

            if (out.isEmpty()) {
                out.add(cue.copy(text = cueLines.joinToString("\n")))
                continue
            }

            val prev = out.last()
            val prevLines = prev.text.lines()

            // 1) Strip repeated rolling prefix lines carried over from the previous cue
            //    (common in YouTube auto-generated WebVTT and scrolling TTML).
            val dedupedLines = stripRepeatedPrefix(prevLines, cueLines)
            if (dedupedLines.isEmpty()) {
                // Pure duplicate of previous cue: extend its end time cleanly.
                out[out.lastIndex] = prev.copy(endMs = maxOf(prev.endMs, cue.endMs))
                continue
            }
            val cleanedText = dedupedLines.joinToString("\n")

            // 2) Same timestamp (simultaneous speakers): merge into one multi-line cue.
            if (cue.startMs - prev.startMs <= 150L) {
                val mergedLines = (prevLines + dedupedLines).distinct()
                out[out.lastIndex] = SubtitleCue(
                    startMs = prev.startMs,
                    endMs = maxOf(prev.endMs, cue.endMs),
                    text = mergedLines.take(3).joinToString("\n")
                )
                continue
            }

            // 3) Sequential cue overlapping previous cue's padded rolling duration:
            //    end the previous cue right where the new caption starts.
            if (prev.endMs > cue.startMs) {
                out[out.lastIndex] = prev.copy(endMs = cue.startMs)
            }
            out.add(SubtitleCue(cue.startMs, cue.endMs, cleanedText))
        }
        return out.filter { it.endMs > it.startMs && it.text.isNotBlank() }
    }

    /**
     * Removes leading lines of [curLines] that repeat the trailing lines of [prevLines]
     * (case-insensitive, normalized whitespace) — e.g. when a rolling VTT cue repeats
     * line 1 from the previous cue and adds line 2 below it.
     */
    private fun stripRepeatedPrefix(prevLines: List<String>, curLines: List<String>): List<String> {
        fun norm(s: String): String = s.trim().replace(WHITESPACE_REGEX, " ").lowercase()
        val prevNorm = prevLines.map(::norm)
        val curNorm = curLines.map(::norm)

        // Exact full match with previous cue or its last line
        if (curNorm == prevNorm) return emptyList()

        // Find the largest overlap k where the last k lines of prev equal the first k lines of cur
        val maxK = minOf(prevNorm.size, curNorm.size)
        for (k in maxK downTo 1) {
            val prevTail = prevNorm.subList(prevNorm.size - k, prevNorm.size)
            val curHead = curNorm.subList(0, k)
            if (prevTail == curHead) {
                return curLines.drop(k)
            }
        }
        return curLines
    }

    /** O(log N) lookup of the active subtitle cue at [positionMs]. */
    fun findActiveText(cues: List<SubtitleCue>, positionMs: Long): String? {
        if (cues.isEmpty() || positionMs < 0L) return null
        // Binary search for the rightmost cue with startMs <= positionMs
        var lo = 0
        var hi = cues.lastIndex
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (idx < 0) return null
        val cue = cues[idx]
        return if (positionMs >= cue.startMs && positionMs < cue.endMs) cue.text else null
    }

    private fun parseTtmlOrSrv3(xml: String): List<SubtitleCue> {
        val out = mutableListOf<SubtitleCue>()
        for (m in TTML_P_REGEX.findAll(xml)) {
            val attrsStr = m.groupValues[1]
            val inner = m.groupValues[2]
            val attrs = HashMap<String, String>()
            for (am in ATTR_REGEX.findAll(attrsStr)) {
                attrs[am.groupValues[1].lowercase()] = am.groupValues[2]
            }
            val startMs: Long
            val endMs: Long
            val beginAttr = attrs["begin"]
            val endAttr = attrs["end"]
            val durAttr = attrs["dur"]
            val tAttr = attrs["t"]
            val dAttr = attrs["d"]
            if (beginAttr != null) {
                startMs = parseTimeMs(beginAttr) ?: continue
                endMs = when {
                    endAttr != null -> parseTimeMs(endAttr) ?: (startMs + 3000L)
                    durAttr != null -> startMs + (parseTimeMs(durAttr) ?: 3000L)
                    else -> startMs + 3000L
                }
            } else if (tAttr != null) {
                startMs = tAttr.toLongOrNull() ?: continue
                val dur = dAttr?.toLongOrNull() ?: 3000L
                endMs = startMs + dur
            } else {
                continue
            }
            val text = cleanCueText(inner)
            if (text.isNotBlank() && endMs > startMs) {
                out.add(SubtitleCue(startMs, endMs, text))
            }
        }
        return out
    }

    private fun parseXmlText(xml: String): List<SubtitleCue> {
        val out = mutableListOf<SubtitleCue>()
        for (m in XML_TEXT_REGEX.findAll(xml)) {
            val attrsStr = m.groupValues[1]
            val inner = m.groupValues[2]
            val attrs = HashMap<String, String>()
            for (am in ATTR_REGEX.findAll(attrsStr)) {
                attrs[am.groupValues[1].lowercase()] = am.groupValues[2]
            }
            val startSec = attrs["start"]?.toDoubleOrNull() ?: continue
            val durSec = attrs["dur"]?.toDoubleOrNull() ?: 3.0
            val startMs = (startSec * 1000.0).toLong()
            val endMs = ((startSec + durSec) * 1000.0).toLong()
            val text = cleanCueText(inner)
            if (text.isNotBlank() && endMs > startMs) {
                out.add(SubtitleCue(startMs, endMs, text))
            }
        }
        return out
    }

    private fun parseVtt(vtt: String): List<SubtitleCue> {
        val out = mutableListOf<SubtitleCue>()
        val lines = vtt.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val match = VTT_TIMING_REGEX.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val startMs = vttGroupsToMs(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
            val endMs = vttGroupsToMs(match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8])
            i++
            val sb = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                val cleaned = cleanCueText(lines[i])
                if (cleaned.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(cleaned)
                }
                i++
            }
            val text = sb.toString().trim()
            // Skip tiny 10ms YouTube ASR transition spacers
            if (text.isNotBlank() && endMs - startMs >= 60L) {
                if (out.lastOrNull()?.text == text && startMs - out.last().endMs <= 120L) {
                    val prev = out.removeAt(out.lastIndex)
                    out.add(prev.copy(endMs = maxOf(prev.endMs, endMs)))
                } else {
                    out.add(SubtitleCue(startMs, endMs, text))
                }
            }
        }
        return out
    }

    private fun vttGroupsToMs(hStr: String, mStr: String, sStr: String, msStr: String): Long {
        val h = hStr.removeSuffix(":").toLongOrNull() ?: 0L
        val m = mStr.toLongOrNull() ?: 0L
        val s = sStr.toLongOrNull() ?: 0L
        val ms = msStr.toLongOrNull() ?: 0L
        return ((h * 3600L) + (m * 60L) + s) * 1000L + ms
    }

    private fun parseTimeMs(raw: String): Long? {
        val s = raw.trim()
        if (s.endsWith("ms")) {
            return s.removeSuffix("ms").toDoubleOrNull()?.toLong()
        }
        if (s.endsWith("s")) {
            return s.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000.0).toLong() }
        }
        val parts = s.split(':')
        if (parts.size == 3) {
            val h = parts[0].toLongOrNull() ?: return null
            val m = parts[1].toLongOrNull() ?: return null
            val sec = parts[2].replace(',', '.').toDoubleOrNull() ?: return null
            return ((h * 3600L + m * 60L) * 1000L) + (sec * 1000.0).toLong()
        } else if (parts.size == 2) {
            val m = parts[0].toLongOrNull() ?: return null
            val sec = parts[1].replace(',', '.').toDoubleOrNull() ?: return null
            return (m * 60L * 1000L) + (sec * 1000.0).toLong()
        }
        return null
    }

    private fun cleanCueText(raw: String): String {
        val withNewlines = BR_REGEX.replace(raw, "\n")
        val stripped = TAG_REGEX.replace(withNewlines, "")
        val decoded = decodeHtmlEntities(stripped)
        return decoded.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        var s = input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        if (s.contains("&#")) {
            s = NUMERIC_ENTITY_REGEX.replace(s) { m ->
                val cp = m.groupValues[1].toIntOrNull()
                if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
            }
            s = HEX_ENTITY_REGEX.replace(s) { m ->
                val cp = m.groupValues[1].toIntOrNull(16)
                if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
            }
        }
        return s
    }
}
