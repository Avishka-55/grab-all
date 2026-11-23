package com.graball.engine

import android.content.Context
import android.util.Log
import com.graball.youtube.VideoStreamInfo
import com.graball.youtube.YouTubeInnerTube
import com.graball.youtube.YtItem
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * High-speed native Kotlin YouTube downloader for both playlists (100+ tracks)
 * and regular single videos/audio.
 *
 * Why it is dramatically faster than serial yt-dlp:
 * 1. Resolves direct adaptive M4A/H.264 stream URLs in ~300ms via InnerTube
 *    (zero CPython startup, zero QuickJS challenge overhead).
 * 2. Downloads single videos & large tracks across 4-6 parallel HTTP Range
 *    connections (2 MB chunks) via OkHttp to saturate 50+ Mbps connections and
 *    bypass YouTube's per-stream throttle.
 * 3. Processes playlists across N parallel worker threads (user-configurable in
 *    Settings: Auto / 1..6), overlapping network I/O with multi-core ffmpeg
 *    MP3/MP4 muxing and publishing each finished track immediately.
 * 4. Falls back automatically to YtDlpRunner (with cookies + QuickJS) only for
 *    individual restricted/age-gated tracks or private playlists.
 */
class FastYouTubeDownloader(
    private val context: Context,
    private val url: String,
    private val stagingDir: File,
    private val audioOnly: Boolean,
    private val maxHeight: Int?,
    private val playlistMode: String,
    private val concurrency: Int
) {
    interface Callback {
        fun onProgress(percent: Float?, speed: String?, eta: String?, item: String?, detail: String?)
        fun onFileReady(file: File)
        fun onLogLine(line: String)
    }

    data class RunResult(
        /** False if this URL should fall back completely to YtDlpRunner. */
        val handled: Boolean,
        val exitCode: Int = 0,
        val detail: String = "",
        val producedCount: Int = 0,
        val skippedCount: Int = 0
    )

    @Volatile
    private var cancelled = false

    private val activeCalls = Collections.synchronizedSet(mutableSetOf<Call>())
    private val activeProcesses = Collections.synchronizedSet(mutableSetOf<Process>())
    private val activeFallbackRunners = Collections.synchronizedSet(mutableSetOf<YtDlpRunner>())

    private val totalBytesCounter = AtomicLong(0L)
    private var speedWindowStartMs = System.currentTimeMillis()
    private var speedWindowStartBytes = 0L
    @Volatile
    private var lastSpeedText: String? = null
    private val progressLock = Any()
    private var lastProgressEmitMs = 0L

    fun cancel() {
        cancelled = true
        synchronized(activeCalls) {
            activeCalls.toList().forEach { runCatching { it.cancel() } }
            activeCalls.clear()
        }
        synchronized(activeProcesses) {
            activeProcesses.toList().forEach { runCatching { it.destroy() } }
            activeProcesses.clear()
        }
        synchronized(activeFallbackRunners) {
            activeFallbackRunners.toList().forEach { runCatching { it.cancel() } }
            activeFallbackRunners.clear()
        }
    }

    /**
     * Runs synchronously on the calling worker thread.
     * Returns [RunResult.handled] == false if this URL isn't suited for the
     * fast native engine (caller should transparently run YtDlpRunner).
     */
    fun run(callback: Callback): RunResult {
        if (!isYouTubeUrl(url)) return RunResult(handled = false)
        stagingDir.mkdirs()

        val isPlaylist = playlistMode != "single"
        val playlistId = extractPlaylistId(url)
        return if (isPlaylist && playlistId != null) {
            runPlaylist(playlistId, callback)
        } else {
            val videoId = extractVideoId(url) ?: return RunResult(handled = false)
            runSingleVideo(videoId, callback)
        }
    }

    // ---- Single YouTube Video / Audio (Multi-Connection Range Accelerated) ----

    private fun runSingleVideo(videoId: String, callback: Callback): RunResult {
        if (cancelled) return RunResult(handled = true, exitCode = 1, detail = "Cancelled")
        callback.onLogLine("[FastYT] Resolving direct stream for $videoId…")
        callback.onProgress(null, null, null, null, "Resolving fast stream…")

        val resolved = YouTubeInnerTube.resolveForDownload(videoId)
        val info = resolved.info
        if (info == null) {
            callback.onLogLine("[FastYT] Direct stream unavailable (${resolved.error}), using yt-dlp engine")
            return RunResult(handled = false)
        }

        val parallelConns = concurrency.coerceIn(4, 6)
        val safeTitle = sanitizeFileName(info.title).ifBlank { videoId }
        val resultFile = if (audioOnly) {
            downloadAudioTrack(
                info = info,
                targetDir = stagingDir,
                fileBaseName = safeTitle,
                albumTitle = "",
                parallelConns = parallelConns,
                onByteProgress = { done, total ->
                    emitSingleProgress(done, total, callback)
                },
                onStage = { stage ->
                    callback.onProgress(99f, null, null, null, stage)
                },
                callback = callback
            )
        } else {
            downloadVideoTrack(
                info = info,
                targetDir = stagingDir,
                fileBaseName = safeTitle,
                parallelConns = parallelConns,
                onByteProgress = { done, total ->
                    emitSingleProgress(done, total, callback)
                },
                onStage = { stage ->
                    callback.onProgress(99f, null, null, null, stage)
                },
                callback = callback
            )
        }

        if (cancelled) {
            return RunResult(handled = true, exitCode = 1, detail = "Cancelled")
        }
        if (resultFile != null && resultFile.isFile && resultFile.length() > 0L) {
            callback.onFileReady(resultFile)
            callback.onProgress(100f, null, null, null, "Finishing…")
            return RunResult(handled = true, exitCode = 0, producedCount = 1)
        }
        callback.onLogLine("[FastYT] Native download incomplete, falling back to yt-dlp")
        return RunResult(handled = false)
    }

    private fun emitSingleProgress(doneBytes: Long, totalBytes: Long, callback: Callback) {
        val now = System.currentTimeMillis()
        synchronized(progressLock) {
            if (now - lastProgressEmitMs < 250L && (totalBytes <= 0L || doneBytes < totalBytes)) {
                return
            }
            lastProgressEmitMs = now
            val speedBps = computeAggregateSpeedBps(now)
            val speedStr = if (speedBps > 0L) formatSpeed(speedBps) else lastSpeedText
            val pct = if (totalBytes > 0L) {
                (doneBytes.toFloat() * 98f / totalBytes.toFloat()).coerceIn(0f, 98f)
            } else {
                null
            }
            val etaStr = if (totalBytes > doneBytes && speedBps > 0L) {
                formatEta((totalBytes - doneBytes) / speedBps)
            } else {
                null
            }
            callback.onProgress(pct, speedStr, etaStr, null, null)
        }
    }

    // ---- Parallel YouTube Playlist Engine (N Concurrent Workers) ----

    private fun runPlaylist(playlistId: String, callback: Callback): RunResult {
        if (cancelled) return RunResult(handled = true, exitCode = 1, detail = "Cancelled")
        val maxItems = when (playlistMode) {
            "20" -> 20
            "30" -> 30
            "50" -> 50
            else -> 1500
        }
        callback.onLogLine("[FastYT] Loading playlist $playlistId (limit=$maxItems, workers=$concurrency)…")
        callback.onProgress(null, null, null, null, "Loading playlist items…")

        val details = YouTubeInnerTube.fetchPlaylist(playlistId, maxItems = maxItems)
        if (details == null || details.items.isEmpty()) {
            callback.onLogLine("[FastYT] Playlist couldn't be loaded via fast API (private/cookies?), using yt-dlp")
            return RunResult(handled = false)
        }

        val items = details.items.take(maxItems)
        val total = items.size
        val playlistTitle = sanitizeFileName(details.title).ifBlank { "Playlist" }
        val playlistDir = File(stagingDir, playlistTitle).apply { mkdirs() }
        val completedFile = File(stagingDir, "completed_ids.txt")
        val completedIds = Collections.synchronizedSet(mutableSetOf<String>())
        if (completedFile.isFile) {
            runCatching {
                completedFile.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { completedIds.add(it) }
            }
        }

        val workerCount = concurrency.coerceIn(1, 6).coerceAtMost(total)
        val perTrackConns = if (workerCount >= 3) 2 else 4
        callback.onLogLine(
            "[FastYT] Playlist \"$playlistTitle\": $total tracks · $workerCount parallel workers (${completedIds.size} already done)"
        )

        val completedCount = AtomicInteger(0)
        val producedCount = AtomicInteger(0)
        val skippedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val activeWorkers = AtomicInteger(0)
        val nextIndex = AtomicInteger(0)
        val startedAtMs = System.currentTimeMillis()
        val indexWidth = total.toString().length.coerceAtLeast(2)

        fun markCompleted(videoId: String) {
            if (completedIds.add(videoId)) {
                synchronized(completedFile) {
                    runCatching { completedFile.appendText("$videoId\n") }
                }
            }
        }

        fun emitPlaylistProgress(force: Boolean = false) {
            val now = System.currentTimeMillis()
            synchronized(progressLock) {
                if (!force && now - lastProgressEmitMs < 350L) return
                lastProgressEmitMs = now
                val done = completedCount.get()
                val active = activeWorkers.get()
                val pct = (done.toFloat() * 100f / total.toFloat()).coerceIn(0f, 99.9f)
                val speedBps = computeAggregateSpeedBps(now)
                val speedStr = if (speedBps > 0L) formatSpeed(speedBps) else lastSpeedText
                val newlyDone = (done - skippedCount.get()).coerceAtLeast(0)
                val elapsedSec = ((now - startedAtMs) / 1000L).coerceAtLeast(1L)
                val remaining = (total - done).coerceAtLeast(0)
                val etaStr = if (newlyDone >= 2 && remaining > 0) {
                    val secPerTrack = elapsedSec.toDouble() / newlyDone.toDouble()
                    formatEta((remaining * secPerTrack).toLong())
                } else {
                    null
                }
                val itemLabel = if (active > 0) {
                    "$done of $total · $active parallel"
                } else {
                    "$done of $total"
                }
                callback.onProgress(pct, speedStr, etaStr, itemLabel, null)
            }
        }

        val pool = Executors.newFixedThreadPool(workerCount)
        val latch = CountDownLatch(workerCount)

        for (w in 0 until workerCount) {
            pool.execute {
                try {
                    while (!cancelled) {
                        val idx = nextIndex.getAndIncrement()
                        if (idx >= total) break
                        val track = items[idx]
                        if (completedIds.contains(track.id)) {
                            skippedCount.incrementAndGet()
                            completedCount.incrementAndGet()
                            emitPlaylistProgress(force = false)
                            continue
                        }
                        activeWorkers.incrementAndGet()
                        emitPlaylistProgress(force = false)
                        try {
                            val prefix = String.format("%0${indexWidth}d", idx + 1)
                            val safeTitle = sanitizeFileName(track.title).ifBlank { track.id }
                            val baseName = "$prefix - $safeTitle"
                            val okFile = processPlaylistTrack(
                                item = track,
                                playlistDir = playlistDir,
                                baseName = baseName,
                                albumTitle = details.title,
                                parallelConns = perTrackConns,
                                onTick = { emitPlaylistProgress(force = false) },
                                callback = callback
                            )
                            if (cancelled) break
                            if (okFile != null && okFile.isFile && okFile.length() > 0L) {
                                callback.onFileReady(okFile)
                                // Remove staging copy once published to keep storage small
                                // while completed_ids.txt handles resume deduplication.
                                runCatching { okFile.delete() }
                                markCompleted(track.id)
                                producedCount.incrementAndGet()
                            } else {
                                failedCount.incrementAndGet()
                                callback.onLogLine("[FastYT] Skipped unavailable track ${idx + 1}/$total: ${track.title}")
                            }
                            completedCount.incrementAndGet()
                            emitPlaylistProgress(force = true)
                        } finally {
                            activeWorkers.decrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        try {
            latch.await()
        } catch (_: InterruptedException) {
            cancel()
        } finally {
            pool.shutdownNow()
        }

        if (cancelled) {
            return RunResult(
                handled = true,
                exitCode = 1,
                detail = "Cancelled",
                producedCount = producedCount.get(),
                skippedCount = skippedCount.get()
            )
        }

        val produced = producedCount.get()
        val skipped = skippedCount.get()
        val failed = failedCount.get()
        callback.onLogLine("[FastYT] Finished playlist: $produced downloaded, $skipped resumed/skipped, $failed unavailable")
        val ok = produced > 0 || skipped > 0
        return RunResult(
            handled = true,
            exitCode = if (ok) 0 else 1,
            detail = if (ok) "" else "No tracks could be downloaded from this playlist",
            producedCount = produced,
            skippedCount = skipped
        )
    }

    private fun processPlaylistTrack(
        item: YtItem,
        playlistDir: File,
        baseName: String,
        albumTitle: String,
        parallelConns: Int,
        onTick: () -> Unit,
        callback: Callback
    ): File? {
        if (cancelled) return null
        val resolved = YouTubeInnerTube.resolveForDownload(item.id)
        val info = resolved.info
        if (info != null) {
            val out = if (audioOnly) {
                downloadAudioTrack(
                    info = info,
                    targetDir = playlistDir,
                    fileBaseName = baseName,
                    albumTitle = albumTitle,
                    parallelConns = parallelConns,
                    onByteProgress = { _, _ -> onTick() },
                    onStage = { },
                    callback = callback
                )
            } else {
                downloadVideoTrack(
                    info = info,
                    targetDir = playlistDir,
                    fileBaseName = baseName,
                    parallelConns = parallelConns,
                    onByteProgress = { _, _ -> onTick() },
                    onStage = { },
                    callback = callback
                )
            }
            if (out != null && out.isFile && out.length() > 0L) {
                return out
            }
        }
        if (cancelled) return null

        // Per-track bulletproof fallback to yt-dlp (e.g. age-gated / cookies / Made-for-Kids)
        callback.onLogLine("[FastYT] Fallback to yt-dlp for track: ${item.title}")
        return fallbackSingleTrackViaYtDlp(item, playlistDir, baseName)
    }

    // ---- Audio Track Download + Fast Single-Pass MP3 + Cover Art ----

    private fun downloadAudioTrack(
        info: VideoStreamInfo,
        targetDir: File,
        fileBaseName: String,
        albumTitle: String,
        parallelConns: Int,
        onByteProgress: (Long, Long) -> Unit,
        onStage: (String) -> Unit,
        callback: Callback
    ): File? {
        val audio = info.bestAudio() ?: return null
        val rawExt = if (audio.mimeType.contains("webm", true)) "webm" else "m4a"
        val rawFile = File(targetDir, ".tmp_${info.videoId}_audio.$rawExt")
        val thumbFile = File(targetDir, ".tmp_${info.videoId}_thumb.jpg")
        val outFile = File(targetDir, "$fileBaseName.mp3")

        try {
            val ok = downloadUrlFast(
                url = audio.url,
                userAgent = info.userAgent,
                destFile = rawFile,
                parallelConns = parallelConns,
                onByteProgress = onByteProgress
            )
            if (!ok || cancelled) return null

            // Download thumbnail in parallel / quickly for embedded album art
            val hasThumb = !cancelled && info.thumbnailUrl.startsWith("http") &&
                downloadSmallFile(info.thumbnailUrl, info.userAgent, thumbFile)

            if (cancelled) return null
            onStage("Converting to MP3 & embedding cover…")

            val ffmpeg = resolveFfmpegBinary()
            if (ffmpeg == null) {
                callback.onLogLine("[FastYT] ffmpeg binary missing, falling back to yt-dlp")
                return null
            }

            // Single-pass MP3 encode with fast LAME VBR (-q:a 4, ~165 kbps) + ID3 + cover art
            var encoded = false
            if (hasThumb && thumbFile.length() > 512L) {
                val cmd = mutableListOf(
                    ffmpeg.absolutePath, "-y", "-nostdin", "-loglevel", "error",
                    "-i", rawFile.absolutePath,
                    "-i", thumbFile.absolutePath,
                    "-map", "0:a", "-map", "1:v",
                    "-c:a", "libmp3lame", "-q:a", "4", "-threads", "2",
                    "-c:v", "mjpeg", "-id3v2_version", "3",
                    "-metadata", "title=${info.title}",
                    "-metadata", "artist=${info.channel}"
                )
                if (albumTitle.isNotBlank()) {
                    cmd += listOf("-metadata", "album=$albumTitle")
                }
                cmd += listOf(
                    "-metadata:s:v", "title=Album cover",
                    "-metadata:s:v", "comment=Cover (front)",
                    "-disposition:v", "attached_pic",
                    outFile.absolutePath
                )
                encoded = runFfmpeg(cmd) && outFile.isFile && outFile.length() > 0L
            }

            // Clean fallback without cover art if thumbnail was WebP/corrupt
            if (!encoded && !cancelled) {
                runCatching { outFile.delete() }
                val cmd = mutableListOf(
                    ffmpeg.absolutePath, "-y", "-nostdin", "-loglevel", "error",
                    "-i", rawFile.absolutePath,
                    "-vn",
                    "-c:a", "libmp3lame", "-q:a", "4", "-threads", "2",
                    "-id3v2_version", "3",
                    "-metadata", "title=${info.title}",
                    "-metadata", "artist=${info.channel}"
                )
                if (albumTitle.isNotBlank()) {
                    cmd += listOf("-metadata", "album=$albumTitle")
                }
                cmd += outFile.absolutePath
                encoded = runFfmpeg(cmd) && outFile.isFile && outFile.length() > 0L
            }

            return if (encoded) outFile else null
        } finally {
            runCatching { rawFile.delete() }
            runCatching { File(rawFile.absolutePath + ".part").delete() }
            runCatching { thumbFile.delete() }
        }
    }

    // ---- Video Track Download + Instant Stream-Copy MP4 Mux ----

    private fun downloadVideoTrack(
        info: VideoStreamInfo,
        targetDir: File,
        fileBaseName: String,
        parallelConns: Int,
        onByteProgress: (Long, Long) -> Unit,
        onStage: (String) -> Unit,
        callback: Callback
    ): File? {
        val video = info.pickVideoStream(maxHeight)
        val audio = info.bestAudio()
        val outFile = File(targetDir, "$fileBaseName.mp4")

        if (video != null && audio != null) {
            val vExt = if (video.mimeType.contains("webm", true)) "webm" else "mp4"
            val aExt = if (audio.mimeType.contains("webm", true)) "webm" else "m4a"
            val vFile = File(targetDir, ".tmp_${info.videoId}_video.$vExt")
            val aFile = File(targetDir, ".tmp_${info.videoId}_audio.$aExt")
            try {
                // Download video & audio simultaneously!
                val vDone = AtomicLong(0L)
                val vTotal = AtomicLong(0L)
                val aDone = AtomicLong(0L)
                val aTotal = AtomicLong(0L)
                val audioOk = AtomicBoolean(false)

                val audioThread = Thread {
                    audioOk.set(
                        downloadUrlFast(
                            url = audio.url,
                            userAgent = info.userAgent,
                            destFile = aFile,
                            parallelConns = 1,
                            onByteProgress = { d, t ->
                                aDone.set(d)
                                if (t > 0L) aTotal.set(t)
                                onByteProgress(vDone.get() + d, vTotal.get() + aTotal.get())
                            }
                        )
                    )
                }.apply { start() }

                val videoOk = downloadUrlFast(
                    url = video.url,
                    userAgent = info.userAgent,
                    destFile = vFile,
                    parallelConns = parallelConns,
                    onByteProgress = { d, t ->
                        vDone.set(d)
                        if (t > 0L) vTotal.set(t)
                        onByteProgress(d + aDone.get(), t + aTotal.get())
                    }
                )
                try {
                    audioThread.join()
                } catch (_: InterruptedException) {
                    return null
                }
                if (!videoOk || !audioOk.get() || cancelled) return null

                onStage("Merging video & audio…")
                val ffmpeg = resolveFfmpegBinary() ?: return null
                // Pure stream copy if H.264/MP4 + M4A; fallback to AAC audio copy if Opus
                val isMp4Audio = audio.mimeType.contains("mp4", true) || audio.mimeType.contains("aac", true)
                val cmd = mutableListOf(
                    ffmpeg.absolutePath, "-y", "-nostdin", "-loglevel", "error",
                    "-i", vFile.absolutePath,
                    "-i", aFile.absolutePath,
                    "-map", "0:v:0", "-map", "1:a:0",
                    "-c:v", "copy",
                    "-c:a", if (isMp4Audio) "copy" else "aac",
                    "-movflags", "+faststart",
                    outFile.absolutePath
                )
                val muxed = runFfmpeg(cmd) && outFile.isFile && outFile.length() > 0L
                return if (muxed) outFile else null
            } finally {
                runCatching { vFile.delete() }
                runCatching { File(vFile.absolutePath + ".part").delete() }
                runCatching { aFile.delete() }
                runCatching { File(aFile.absolutePath + ".part").delete() }
            }
        }

        // Fallback to muxed stream
        val muxed = info.pickMuxedStream(maxHeight) ?: return null
        val ok = downloadUrlFast(
            url = muxed.url,
            userAgent = info.userAgent,
            destFile = outFile,
            parallelConns = parallelConns,
            onByteProgress = onByteProgress
        )
        return if (ok && !cancelled && outFile.isFile && outFile.length() > 0L) outFile else null
    }

    // ---- Multi-Connection HTTP Range Downloader (Saturates 50+ Mbps!) ----

    private fun downloadUrlFast(
        url: String,
        userAgent: String,
        destFile: File,
        parallelConns: Int,
        onByteProgress: (Long, Long) -> Unit
    ): Boolean {
        if (cancelled) return false
        destFile.parentFile?.mkdirs()

        // Probe total length & Range support with a tiny 1-byte Range GET
        val totalBytes = probeContentLength(url, userAgent)
        if (cancelled) return false

        if (totalBytes >= MULTI_CHUNK_THRESHOLD_BYTES && parallelConns > 1) {
            if (downloadRangedParallel(url, userAgent, destFile, totalBytes, parallelConns, onByteProgress)) {
                return true
            }
            if (cancelled) return false
        }

        return downloadSingleStream(url, userAgent, destFile, totalBytes, onByteProgress)
    }

    private fun probeContentLength(url: String, userAgent: String): Long {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Range", "bytes=0-0")
            .build()
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        return try {
            call.execute().use { resp ->
                if (resp.code == 206) {
                    val cr = resp.header("Content-Range") ?: return -1L
                    // Format: "bytes 0-0/12345678"
                    cr.substringAfter('/').toLongOrNull() ?: -1L
                } else if (resp.isSuccessful) {
                    resp.body?.contentLength() ?: -1L
                } else {
                    -1L
                }
            }
        } catch (_: Exception) {
            -1L
        } finally {
            activeCalls.remove(call)
        }
    }

    private fun downloadRangedParallel(
        url: String,
        userAgent: String,
        destFile: File,
        totalBytes: Long,
        parallelConns: Int,
        onByteProgress: (Long, Long) -> Unit
    ): Boolean {
        val partFile = File(destFile.absolutePath + ".part")
        try {
            RandomAccessFile(partFile, "rw").use { it.setLength(totalBytes) }
        } catch (e: Exception) {
            Log.w(TAG, "Pre-allocate failed: ${e.message}")
            return false
        }

        val chunkSize = RANGE_CHUNK_BYTES
        val numChunks = ((totalBytes + chunkSize - 1L) / chunkSize).toInt()
        val workers = min(parallelConns, numChunks).coerceAtLeast(1)
        val nextChunk = AtomicInteger(0)
        val fileBytesDone = AtomicLong(0L)
        val failed = AtomicBoolean(false)

        val pool = Executors.newFixedThreadPool(workers)
        val latch = CountDownLatch(workers)

        for (w in 0 until workers) {
            pool.execute {
                try {
                    RandomAccessFile(partFile, "rw").use { raf ->
                        val buf = ByteArray(64 * 1024)
                        while (!cancelled && !failed.get()) {
                            val idx = nextChunk.getAndIncrement()
                            if (idx >= numChunks) break
                            val start = idx.toLong() * chunkSize
                            val end = min(totalBytes - 1L, start + chunkSize - 1L)
                            var chunkOk = false
                            for (attempt in 0 until 3) {
                                if (cancelled || failed.get()) break
                                var writtenInAttempt = 0L
                                val req = Request.Builder()
                                    .url(url)
                                    .header("User-Agent", userAgent)
                                    .header("Range", "bytes=$start-$end")
                                    .build()
                                val call = httpClient.newCall(req)
                                activeCalls.add(call)
                                try {
                                    call.execute().use { resp ->
                                        if (resp.code != 206 && !resp.isSuccessful) {
                                            return@use
                                        }
                                        val body = resp.body ?: return@use
                                        raf.seek(start)
                                        body.byteStream().use { input ->
                                            var n: Int
                                            while (input.read(buf).also { n = it } != -1) {
                                                if (cancelled || failed.get()) return@use
                                                raf.write(buf, 0, n)
                                                writtenInAttempt += n
                                                val nowFile = fileBytesDone.addAndGet(n.toLong())
                                                totalBytesCounter.addAndGet(n.toLong())
                                                onByteProgress(nowFile, totalBytes)
                                            }
                                        }
                                        if (writtenInAttempt == (end - start + 1L)) {
                                            chunkOk = true
                                        }
                                    }
                                } catch (_: Exception) {
                                    // Roll back partial attempt count before retry
                                    if (writtenInAttempt > 0L) {
                                        fileBytesDone.addAndGet(-writtenInAttempt)
                                    }
                                } finally {
                                    activeCalls.remove(call)
                                }
                                if (chunkOk) break
                                try {
                                    Thread.sleep(300L * (attempt + 1))
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                            if (!chunkOk) {
                                failed.set(true)
                                break
                            }
                        }
                    }
                } catch (_: Exception) {
                    failed.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        try {
            latch.await()
        } catch (_: InterruptedException) {
            failed.set(true)
        } finally {
            pool.shutdownNow()
        }

        if (cancelled || failed.get()) {
            runCatching { partFile.delete() }
            return false
        }
        if (destFile.exists()) destFile.delete()
        return partFile.renameTo(destFile) && destFile.length() == totalBytes
    }

    private fun downloadSingleStream(
        url: String,
        userAgent: String,
        destFile: File,
        knownTotal: Long,
        onByteProgress: (Long, Long) -> Unit
    ): Boolean {
        val partFile = File(destFile.absolutePath + ".part")
        val buf = ByteArray(64 * 1024)
        for (attempt in 0 until 3) {
            if (cancelled) return false
            val existing = if (partFile.isFile) partFile.length() else 0L
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
            if (existing > 0L) {
                reqBuilder.header("Range", "bytes=$existing-")
            }
            val call = httpClient.newCall(reqBuilder.build())
            activeCalls.add(call)
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) {
                        if (resp.code == 416) {
                            runCatching { partFile.delete() }
                        }
                        return@use
                    }
                    val append = resp.code == 206 && existing > 0L
                    val body = resp.body ?: return@use
                    val contentLen = body.contentLength()
                    val total = when {
                        knownTotal > 0L -> knownTotal
                        contentLen > 0L -> (if (append) existing else 0L) + contentLen
                        else -> -1L
                    }
                    var written = if (append) existing else 0L
                    FileOutputStream(partFile, append).use { out ->
                        body.byteStream().use { input ->
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                if (cancelled) return false
                                out.write(buf, 0, n)
                                written += n
                                totalBytesCounter.addAndGet(n.toLong())
                                onByteProgress(written, total)
                            }
                        }
                    }
                    if (written > 0L && (total <= 0L || written >= total)) {
                        if (destFile.exists()) destFile.delete()
                        if (partFile.renameTo(destFile)) {
                            return true
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                activeCalls.remove(call)
            }
            if (!cancelled) {
                try {
                    Thread.sleep(400L * (attempt + 1))
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return false
    }

    private fun downloadSmallFile(url: String, userAgent: String, destFile: File): Boolean {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        return try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) return false
                val bytes = resp.body?.bytes() ?: return false
                destFile.writeBytes(bytes)
                bytes.isNotEmpty()
            }
        } catch (_: Exception) {
            false
        } finally {
            activeCalls.remove(call)
        }
    }

    // ---- Bundled ffmpeg Subprocess Helper ----

    private fun resolveFfmpegBinary(): File? {
        val jni = File(RuntimePaths.nativeLibDir(context), "libffmpeg.so")
        if (jni.exists() && RuntimeExtractor.ensureExecutable(jni)) return jni
        val linked = File(RuntimePaths.ffmpegDir(context), "ffmpeg")
        if (linked.exists() && RuntimeExtractor.ensureExecutable(linked)) return linked
        return null
    }

    private fun runFfmpeg(command: List<String>): Boolean {
        if (cancelled) return false
        var proc: Process? = null
        return try {
            val pb = ProcessBuilder(command).redirectErrorStream(true)
            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = RuntimePaths.pythonLibDir(context).absolutePath
            env["TMPDIR"] = context.cacheDir.absolutePath
            val started = pb.start()
            proc = started
            activeProcesses.add(started)
            // Drain stdout/stderr so pipe buffer can never deadlock
            BufferedReader(InputStreamReader(started.inputStream)).use { r ->
                while (r.readLine() != null) {
                    if (cancelled) {
                        started.destroy()
                        break
                    }
                }
            }
            val code = started.waitFor()
            code == 0 && !cancelled
        } catch (e: Exception) {
            Log.w(TAG, "ffmpeg failed: ${e.message}")
            false
        } finally {
            proc?.let { activeProcesses.remove(it) }
        }
    }

    // ---- Per-Track yt-dlp Fallback (for restricted/age-gated tracks in a playlist) ----

    private fun fallbackSingleTrackViaYtDlp(
        item: YtItem,
        playlistDir: File,
        baseName: String
    ): File? {
        if (cancelled) return null
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
        activeFallbackRunners.add(runner)
        val latch = CountDownLatch(1)
        var produced: File? = null
        val outputTemplate = File(playlistDir, "$baseName.%(ext)s").absolutePath
        val extraArgs = if (audioOnly) {
            listOf(
                "-f", "bestaudio",
                "--extract-audio", "--audio-format", "mp3", "--audio-quality", "4",
                "--embed-metadata", "--embed-thumbnail",
                "--no-playlist"
            )
        } else {
            val cap = if (maxHeight != null) "[height<=$maxHeight]" else ""
            val fmt = "bestvideo$cap[vcodec^=avc1]+bestaudio[ext=m4a]/" +
                "bestvideo$cap[vcodec^=avc1]+bestaudio/best$cap/best"
            listOf("-f", fmt, "--remux-video", "mp4", "--no-playlist")
        }
        try {
            runner.download(item.watchUrl, outputTemplate, extraArgs, object : DownloadListener {
                override fun onProgress(progress: DownloadProgress) {}
                override fun onDownloadedFiles(files: List<File>) {
                    files.lastOrNull { it.isFile && it.length() > 0L }?.let { produced = it }
                }
                override fun onFinished(exitCode: Int, detail: String) {
                    if (exitCode == 0 && produced == null) {
                        val expectedExt = if (audioOnly) ".mp3" else ".mp4"
                        val candidate = File(playlistDir, "$baseName$expectedExt")
                        if (candidate.isFile && candidate.length() > 0L) {
                            produced = candidate
                        }
                    }
                    latch.countDown()
                }
                override fun onError(message: String) {
                    latch.countDown()
                }
            })
            latch.await(180, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            runner.cancel()
        } finally {
            activeFallbackRunners.remove(runner)
        }
        return if (cancelled) null else produced
    }

    // ---- Throughput & Formatting Helpers ----

    private fun computeAggregateSpeedBps(nowMs: Long): Long {
        val elapsedMs = nowMs - speedWindowStartMs
        if (elapsedMs < 800L) return 0L
        val currentBytes = totalBytesCounter.get()
        val delta = (currentBytes - speedWindowStartBytes).coerceAtLeast(0L)
        speedWindowStartMs = nowMs
        speedWindowStartBytes = currentBytes
        val bps = (delta * 1000L) / elapsedMs.coerceAtLeast(1L)
        if (bps > 0L) {
            lastSpeedText = formatSpeed(bps)
        }
        return bps
    }

    companion object {
        private const val TAG = "FastYouTubeDownloader"
        private const val MULTI_CHUNK_THRESHOLD_BYTES = 3_500_000L
        private const val RANGE_CHUNK_BYTES = 2L * 1024L * 1024L

        private val httpClient: OkHttpClient = OkHttpClient.Builder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 48
                    maxRequestsPerHost = 16
                }
            )
            .connectionPool(ConnectionPool(16, 2, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        private val VIDEO_ID_REGEX = Regex(
            """(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|embed/|live/)|youtu\.be/)([A-Za-z0-9_-]{11})"""
        )
        private val PLAYLIST_ID_REGEX = Regex("""[?&]list=([A-Za-z0-9_-]+)""")

        fun isYouTubeUrl(url: String): Boolean {
            val lower = url.lowercase()
            return lower.contains("youtube.com/") || lower.contains("youtu.be/")
        }

        fun extractVideoId(url: String): String? =
            VIDEO_ID_REGEX.find(url)?.groupValues?.getOrNull(1)

        fun extractPlaylistId(url: String): String? =
            PLAYLIST_ID_REGEX.find(url)?.groupValues?.getOrNull(1)

        fun sanitizeFileName(raw: String): String {
            return raw
                .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .trim('.')
                .take(100)
        }

        private fun formatSpeed(bytesPerSec: Long): String {
            return when {
                bytesPerSec >= 1024L * 1024L ->
                    String.format("%.1f MiB/s", bytesPerSec / (1024.0 * 1024.0))
                bytesPerSec >= 1024L ->
                    String.format("%.0f KiB/s", bytesPerSec / 1024.0)
                else -> "${bytesPerSec} B/s"
            }
        }

        private fun formatEta(seconds: Long): String {
            val s = seconds.coerceAtLeast(0L)
            val h = s / 3600L
            val m = (s % 3600L) / 60L
            val sec = s % 60L
            return if (h > 0L) {
                String.format("%d:%02d:%02d", h, m, sec)
            } else {
                String.format("%02d:%02d", m, sec)
            }
        }
    }
}
