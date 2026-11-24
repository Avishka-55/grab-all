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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * High-speed native Kotlin YouTube downloader for both playlists (100+ tracks)
 * and regular single videos/audio.
 *
 * Why it is dramatically faster than serial yt-dlp:
 * 1. Resolves direct adaptive M4A/H.264 stream URLs in ~300ms via InnerTube
 *    (zero CPython startup, zero QuickJS challenge overhead).
 * 2. Downloads ALL YouTube streams >= 512 KB (both single videos/audio and
 *    playlist tracks) in 1–2 MB ranged chunks using GoogleVideo's native
 *    `&range=start-end` parameter across parallel OkHttp connections to bypass
 *    YouTube's ~50-100 KB/s single-stream throttle.
 * 3. Decouples network downloading (`downloadPool`) from ffmpeg MP3/M4A/MP4
 *    encoding (`convertPool`) in playlists so Wi-Fi stays saturated while CPU
 *    encodes finished tracks in parallel.
 * 4. Optional Fast M4A mode (`fastM4a = true`) uses zero-transcode stream copy
 *    (`ffmpeg -c:a copy`) for ~10x faster audio saving with embedded cover art.
 * 5. Falls back automatically to YtDlpRunner (capped to 2 concurrent processes
 *    with live progress forwarding) only for restricted/age-gated tracks.
 */
class FastYouTubeDownloader(
    private val context: Context,
    private val url: String,
    private val stagingDir: File,
    private val audioOnly: Boolean,
    private val maxHeight: Int?,
    private val playlistMode: String,
    private val concurrency: Int,
    private val selectedVideoIds: Set<String>? = null,
    private val fastM4a: Boolean = true
) {
    interface Callback {
        fun onTitleResolved(title: String) {}
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

    private data class RawAudioStage(
        val info: VideoStreamInfo,
        val rawFile: File,
        val thumbFile: File,
        val hasThumb: Boolean,
        val audioMimeType: String
    ) {
        fun cleanup() {
            runCatching { rawFile.delete() }
            runCatching { File(rawFile.absolutePath + ".part").delete() }
            runCatching { thumbFile.delete() }
        }
    }

    private data class RawVideoStage(
        val info: VideoStreamInfo,
        val videoFile: File,
        val audioFile: File,
        val audioMimeType: String
    ) {
        fun cleanup() {
            runCatching { videoFile.delete() }
            runCatching { File(videoFile.absolutePath + ".part").delete() }
            runCatching { audioFile.delete() }
            runCatching { File(audioFile.absolutePath + ".part").delete() }
        }
    }

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

        val resolved = YouTubeInnerTube.resolveForDownload(
            context = context,
            videoId = videoId,
            requestedMaxHeight = maxHeight,
            audioOnly = audioOnly
        )
        val info = resolved.info
        if (info == null) {
            callback.onLogLine("[FastYT] Direct stream unavailable (${resolved.error}), using yt-dlp engine")
            WebViewCookieSync.exportYouTubeCookiesIfLoggedIn(context)
            return RunResult(handled = false)
        }

        // Strict quality guard: NEVER silently download a 360p-only muxed stream
        // when the user requested Best / 1080p / 720p / 480p! Hand off cleanly
        // to yt-dlp (with logged-in cookies) so 1080p downloads are genuinely 1080p.
        if (!audioOnly && !satisfiesRequestedVideoQuality(info, maxHeight)) {
            val bestAvail = info.availableHeights().firstOrNull() ?: 0
            callback.onLogLine(
                "[FastYT] Fast path only resolved ${bestAvail}p (requested ${maxHeight?.let { "${it}p" } ?: "Best"}) — using yt-dlp for full HD"
            )
            WebViewCookieSync.exportYouTubeCookiesIfLoggedIn(context)
            return RunResult(handled = false)
        }

        val parallelConns = concurrency.coerceIn(4, 6)
        if (info.title.isNotBlank()) {
            callback.onTitleResolved(info.title)
        }
        val safeTitle = sanitizeFileName(info.title).ifBlank { videoId }
        val resultFile = if (audioOnly) {
            downloadAudioTrack(
                info = info,
                targetDir = stagingDir,
                fileBaseName = safeTitle,
                albumTitle = "",
                tempTag = "s_${info.videoId}",
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
                tempTag = "s_${info.videoId}",
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

    // ---- Decoupled Parallel YouTube Playlist Engine ----

    private fun runPlaylist(playlistId: String, callback: Callback): RunResult {
        if (cancelled) return RunResult(handled = true, exitCode = 1, detail = "Cancelled")
        val maxItems = when (playlistMode) {
            "20" -> 20
            "30" -> 30
            "50" -> 50
            else -> 1500
        }
        callback.onLogLine("[FastYT] Loading playlist $playlistId (limit=$maxItems, workers=$concurrency, fastM4a=$fastM4a)…")
        callback.onProgress(null, null, null, null, "Loading playlist items…")

        val details = YouTubeInnerTube.fetchPlaylist(playlistId, maxItems = maxItems)
        if (details == null || details.items.isEmpty()) {
            callback.onLogLine("[FastYT] Playlist couldn't be loaded via fast API (private/cookies?), using yt-dlp")
            return RunResult(handled = false)
        }

        val fullItems = details.items.take(maxItems)
        val indexedTargets: List<Pair<Int, YtItem>> = if (!selectedVideoIds.isNullOrEmpty()) {
            fullItems.mapIndexedNotNull { idx, item ->
                if (item.id in selectedVideoIds) (idx + 1) to item else null
            }
        } else {
            fullItems.mapIndexed { idx, item -> (idx + 1) to item }
        }
        if (indexedTargets.isEmpty()) {
            return RunResult(handled = true, exitCode = 1, detail = "None of the selected tracks were found")
        }
        val total = indexedTargets.size
        if (details.title.isNotBlank()) {
            callback.onTitleResolved(details.title)
        }
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
        val convertThreads = min(workerCount, 3).coerceAtLeast(1)
        val perTrackConns = if (workerCount >= 3) 2 else 4
        callback.onLogLine(
            "[FastYT] Playlist \"$playlistTitle\": $total tracks · $workerCount downloaders + $convertThreads converters (${completedIds.size} already done)"
        )

        val completedCount = AtomicInteger(0)
        val producedCount = AtomicInteger(0)
        val skippedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val activeDownloaders = AtomicInteger(0)
        val convertingWorkers = AtomicInteger(0)
        val nextIndex = AtomicInteger(0)
        val startedAtMs = System.currentTimeMillis()
        val indexWidth = fullItems.size.toString().length.coerceAtLeast(2)

        // Tracks live fractional progress (0.0..0.95) for each active playlist index
        // so the UI ticks smoothly right from track #1 instead of hanging at 0%.
        val inFlightProgress = ConcurrentHashMap<Int, Float>()
        val latestStatusDetail = AtomicReference<String?>("Resolving streams…")

        // Bounded backpressure so downloaders stay well ahead of ffmpeg without
        // filling disk (24 raw audio tracks ~= 70 MB temp, deleted right after publish),
        // and cap yt-dlp fallback subprocesses to 2 so mobile CPUs never thrash.
        val convertBackpressure = Semaphore(if (audioOnly) 24 else workerCount * 2)
        val ytDlpFallbackGate = Semaphore(2)

        fun acquirePermit(sem: Semaphore): Boolean {
            while (!cancelled) {
                try {
                    if (sem.tryAcquire(250L, TimeUnit.MILLISECONDS)) return true
                } catch (_: InterruptedException) {
                    return false
                }
            }
            return false
        }

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
                if (!force && now - lastProgressEmitMs < 300L) return
                lastProgressEmitMs = now
                val done = completedCount.get()
                val activeDl = activeDownloaders.get()
                val activeConv = convertingWorkers.get()
                val remainingTracks = (total - done).coerceAtLeast(0)
                val inFlightSum = inFlightProgress.values.sum()
                    .coerceIn(0f, remainingTracks.toFloat())
                val pct = ((done.toFloat() + inFlightSum) * 100f / total.toFloat())
                    .coerceIn(0f, 99.9f)
                val speedBps = computeAggregateSpeedBps(now)
                val speedStr = if (speedBps > 0L) formatSpeed(speedBps) else lastSpeedText
                val newlyDone = (done - skippedCount.get()).coerceAtLeast(0).toDouble() + inFlightSum.toDouble()
                val elapsedSec = ((now - startedAtMs) / 1000L).coerceAtLeast(1L)
                val remainingEffective = (total - done - inFlightSum).coerceAtLeast(0f).toDouble()
                val etaStr = if (newlyDone >= 1.5 && remainingEffective > 0.05) {
                    val secPerTrack = elapsedSec.toDouble() / newlyDone
                    formatEta((remainingEffective * secPerTrack).toLong())
                } else {
                    null
                }
                val itemLabel = when {
                    activeDl > 0 && activeConv > 0 ->
                        "$done of $total · $activeDl dl · $activeConv conv"
                    activeDl > 0 ->
                        "$done of $total · $activeDl parallel"
                    activeConv > 0 ->
                        "$done of $total · converting ($activeConv)"
                    else ->
                        "$done of $total"
                }
                val fallbackDetail = when {
                    activeDl == 0 && activeConv > 0 ->
                        if (fastM4a) "Saving M4A ($activeConv)…" else "Converting MP3 ($activeConv)…"
                    speedStr == null -> latestStatusDetail.get()
                    else -> null
                }
                callback.onProgress(pct, if (activeDl == 0 && activeConv > 0) null else speedStr, etaStr, itemLabel, fallbackDetail)
            }
        }

        fun finishTrackResult(idx: Int, track: YtItem, okFile: File?) {
            if (!cancelled) {
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
            } else if (okFile != null) {
                runCatching { okFile.delete() }
            }
            inFlightProgress.remove(idx)
            emitPlaylistProgress(force = true)
        }

        fun runYtDlpFallbackForTrack(idx: Int, track: YtItem, baseName: String) {
            if (cancelled) {
                inFlightProgress.remove(idx)
                return
            }
            callback.onLogLine("[FastYT] Fallback to yt-dlp for track: ${track.title}")
            latestStatusDetail.set("Fallback engine: ${track.title.take(36)}")
            emitPlaylistProgress(force = false)
            if (!acquirePermit(ytDlpFallbackGate)) {
                inFlightProgress.remove(idx)
                return
            }
            try {
                val okFile = fallbackSingleTrackViaYtDlp(
                    item = track,
                    playlistDir = playlistDir,
                    baseName = baseName,
                    onProgress = { trackPct, status ->
                        if (trackPct != null) {
                            inFlightProgress[idx] = 0.05f + (trackPct.coerceIn(0f, 100f) / 100f) * 0.88f
                        }
                        if (!status.isNullOrBlank()) {
                            latestStatusDetail.set(status)
                        }
                        emitPlaylistProgress(force = false)
                    }
                )
                finishTrackResult(idx, track, okFile)
            } finally {
                ytDlpFallbackGate.release()
            }
        }

        val downloadPool = Executors.newFixedThreadPool(workerCount)
        val convertPool = Executors.newFixedThreadPool(convertThreads)
        val downloadLatch = CountDownLatch(workerCount)

        for (w in 0 until workerCount) {
            downloadPool.execute {
                try {
                    while (!cancelled) {
                        val idx = nextIndex.getAndIncrement()
                        if (idx >= total) break
                        val (origTrackNum, track) = indexedTargets[idx]
                        if (completedIds.contains(track.id)) {
                            skippedCount.incrementAndGet()
                            completedCount.incrementAndGet()
                            emitPlaylistProgress(force = false)
                            continue
                        }
                        activeDownloaders.incrementAndGet()
                        inFlightProgress[idx] = 0.02f
                        emitPlaylistProgress(force = false)
                        try {
                            val prefix = String.format("%0${indexWidth}d", origTrackNum)
                            val safeTitle = sanitizeFileName(track.title).ifBlank { track.id }
                            val baseName = "$prefix - $safeTitle"
                            val tempTag = "t${idx}_${track.id}"

                            val resolved = YouTubeInnerTube.resolveForDownload(
                                context = context,
                                videoId = track.id,
                                requestedMaxHeight = maxHeight,
                                audioOnly = audioOnly
                            )
                            val info = resolved.info
                            if (cancelled) {
                                inFlightProgress.remove(idx)
                                break
                            }

                            if (info != null && audioOnly) {
                                val rawStage = fetchRawAudioAndThumb(
                                    info = info,
                                    targetDir = playlistDir,
                                    tempTag = tempTag,
                                    parallelConns = perTrackConns,
                                    onByteProgress = { doneBytes, totalBytes ->
                                        if (totalBytes > 0L) {
                                            val frac = (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                            inFlightProgress[idx] = 0.05f + frac * 0.70f
                                        }
                                        emitPlaylistProgress(force = false)
                                    }
                                )
                                if (cancelled) {
                                    rawStage?.cleanup()
                                    inFlightProgress.remove(idx)
                                    break
                                }
                                if (rawStage != null) {
                                    inFlightProgress[idx] = 0.78f
                                    emitPlaylistProgress(force = false)
                                    if (!acquirePermit(convertBackpressure)) {
                                        rawStage.cleanup()
                                        inFlightProgress.remove(idx)
                                        break
                                    }
                                    convertingWorkers.incrementAndGet()
                                    try {
                                        convertPool.execute {
                                            try {
                                                if (cancelled) {
                                                    rawStage.cleanup()
                                                    inFlightProgress.remove(idx)
                                                    return@execute
                                                }
                                                inFlightProgress[idx] = 0.85f
                                                emitPlaylistProgress(force = false)
                                                val encoded = encodeAudioTrack(
                                                    stage = rawStage,
                                                    targetDir = playlistDir,
                                                    fileBaseName = baseName,
                                                    albumTitle = details.title,
                                                    onStage = { },
                                                    callback = callback
                                                )
                                                if (encoded != null && encoded.isFile && encoded.length() > 0L) {
                                                    finishTrackResult(idx, track, encoded)
                                                } else if (!cancelled) {
                                                    runYtDlpFallbackForTrack(idx, track, baseName)
                                                } else {
                                                    inFlightProgress.remove(idx)
                                                }
                                            } finally {
                                                convertingWorkers.decrementAndGet()
                                                convertBackpressure.release()
                                                emitPlaylistProgress(force = false)
                                            }
                                        }
                                    } catch (_: Exception) {
                                        rawStage.cleanup()
                                        inFlightProgress.remove(idx)
                                        convertingWorkers.decrementAndGet()
                                        convertBackpressure.release()
                                    }
                                    continue
                                }
                            } else if (info != null && !audioOnly && satisfiesRequestedVideoQuality(info, maxHeight)) {
                                val hasAdaptive = info.pickVideoStream(maxHeight, forDownload = true) != null && info.bestAudio() != null
                                if (hasAdaptive) {
                                    val rawVideo = fetchRawVideoStreams(
                                        info = info,
                                        targetDir = playlistDir,
                                        tempTag = tempTag,
                                        parallelConns = perTrackConns,
                                        onByteProgress = { doneBytes, totalBytes ->
                                            if (totalBytes > 0L) {
                                                val frac = (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                                inFlightProgress[idx] = 0.05f + frac * 0.78f
                                            }
                                            emitPlaylistProgress(force = false)
                                        }
                                    )
                                    if (cancelled) {
                                        rawVideo?.cleanup()
                                        inFlightProgress.remove(idx)
                                        break
                                    }
                                    if (rawVideo != null) {
                                        inFlightProgress[idx] = 0.86f
                                        emitPlaylistProgress(force = false)
                                        if (!acquirePermit(convertBackpressure)) {
                                            rawVideo.cleanup()
                                            inFlightProgress.remove(idx)
                                            break
                                        }
                                        convertingWorkers.incrementAndGet()
                                        try {
                                            convertPool.execute {
                                                try {
                                                    if (cancelled) {
                                                        rawVideo.cleanup()
                                                        inFlightProgress.remove(idx)
                                                        return@execute
                                                    }
                                                    inFlightProgress[idx] = 0.90f
                                                    emitPlaylistProgress(force = false)
                                                    val muxed = muxVideoTrack(
                                                        stage = rawVideo,
                                                        targetDir = playlistDir,
                                                        fileBaseName = baseName,
                                                        onStage = { }
                                                    )
                                                    if (muxed != null && muxed.isFile && muxed.length() > 0L) {
                                                        finishTrackResult(idx, track, muxed)
                                                    } else if (!cancelled) {
                                                        runYtDlpFallbackForTrack(idx, track, baseName)
                                                    } else {
                                                        inFlightProgress.remove(idx)
                                                    }
                                                } finally {
                                                    convertingWorkers.decrementAndGet()
                                                    convertBackpressure.release()
                                                    emitPlaylistProgress(force = false)
                                                }
                                            }
                                        } catch (_: Exception) {
                                            rawVideo.cleanup()
                                            inFlightProgress.remove(idx)
                                            convertingWorkers.decrementAndGet()
                                            convertBackpressure.release()
                                        }
                                        continue
                                    }
                                } else {
                                    val muxedFile = downloadMuxedVideoTrack(
                                        info = info,
                                        targetDir = playlistDir,
                                        fileBaseName = baseName,
                                        parallelConns = perTrackConns,
                                        onByteProgress = { doneBytes, totalBytes ->
                                            if (totalBytes > 0L) {
                                                val frac = (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                                inFlightProgress[idx] = 0.05f + frac * 0.90f
                                            }
                                            emitPlaylistProgress(force = false)
                                        }
                                    )
                                    if (cancelled) {
                                        inFlightProgress.remove(idx)
                                        break
                                    }
                                    if (muxedFile != null && muxedFile.isFile && muxedFile.length() > 0L) {
                                        finishTrackResult(idx, track, muxedFile)
                                        continue
                                    }
                                }
                            }

                            if (!cancelled) {
                                runYtDlpFallbackForTrack(idx, track, baseName)
                            } else {
                                inFlightProgress.remove(idx)
                            }
                        } finally {
                            activeDownloaders.decrementAndGet()
                        }
                    }
                } finally {
                    downloadLatch.countDown()
                }
            }
        }

        try {
            downloadLatch.await()
        } catch (_: InterruptedException) {
            cancel()
        } finally {
            downloadPool.shutdownNow()
        }

        convertPool.shutdown()
        try {
            while (!convertPool.awaitTermination(300L, TimeUnit.MILLISECONDS)) {
                if (cancelled) {
                    convertPool.shutdownNow()
                    break
                }
            }
        } catch (_: InterruptedException) {
            cancel()
            convertPool.shutdownNow()
        }

        // Remove any leftover staging temp files if cancelled mid-flight
        if (cancelled) {
            runCatching {
                playlistDir.listFiles()
                    ?.filter { it.name.startsWith(".tmp_") || it.name.endsWith(".part") }
                    ?.forEach { it.delete() }
            }
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

    // ---- Audio Track Download + Fast Single-Pass MP3 / Zero-Transcode M4A + Cover Art ----

    private fun downloadAudioTrack(
        info: VideoStreamInfo,
        targetDir: File,
        fileBaseName: String,
        albumTitle: String,
        tempTag: String,
        parallelConns: Int,
        onByteProgress: (Long, Long) -> Unit,
        onStage: (String) -> Unit,
        callback: Callback
    ): File? {
        val stage = fetchRawAudioAndThumb(
            info = info,
            targetDir = targetDir,
            tempTag = tempTag,
            parallelConns = parallelConns,
            onByteProgress = onByteProgress
        ) ?: return null
        return encodeAudioTrack(
            stage = stage,
            targetDir = targetDir,
            fileBaseName = fileBaseName,
            albumTitle = albumTitle,
            onStage = onStage,
            callback = callback
        )
    }

    private fun fetchRawAudioAndThumb(
        info: VideoStreamInfo,
        targetDir: File,
        tempTag: String,
        parallelConns: Int,
        onByteProgress: (Long, Long) -> Unit
    ): RawAudioStage? {
        val audio = info.bestAudio() ?: return null
        val rawExt = if (audio.mimeType.contains("webm", true)) "webm" else "m4a"
        val rawFile = File(targetDir, ".tmp_${tempTag}_audio.$rawExt")
        val thumbFile = File(targetDir, ".tmp_${tempTag}_thumb.jpg")

        val ok = downloadUrlFast(
            url = audio.url,
            userAgent = info.userAgent,
            destFile = rawFile,
            parallelConns = parallelConns,
            onByteProgress = onByteProgress
        )
        if (!ok || cancelled) {
            runCatching { rawFile.delete() }
            runCatching { File(rawFile.absolutePath + ".part").delete() }
            return null
        }

        val hasThumb = !cancelled && info.thumbnailUrl.startsWith("http") &&
            downloadSmallFile(info.thumbnailUrl, info.userAgent, thumbFile)
        if (cancelled) {
            runCatching { rawFile.delete() }
            runCatching { thumbFile.delete() }
            return null
        }
        return RawAudioStage(
            info = info,
            rawFile = rawFile,
            thumbFile = thumbFile,
            hasThumb = hasThumb,
            audioMimeType = audio.mimeType
        )
    }

    private fun encodeAudioTrack(
        stage: RawAudioStage,
        targetDir: File,
        fileBaseName: String,
        albumTitle: String,
        onStage: (String) -> Unit,
        callback: Callback
    ): File? {
        val info = stage.info
        val outFile = File(targetDir, if (fastM4a) "$fileBaseName.m4a" else "$fileBaseName.mp3")
        try {
            if (cancelled) return null
            val ffmpeg = resolveFfmpegBinary()
            if (ffmpeg == null) {
                callback.onLogLine("[FastYT] ffmpeg binary missing, falling back to yt-dlp")
                return null
            }

            if (fastM4a) {
                onStage("Saving fast M4A & cover…")
                val isMp4Audio = stage.audioMimeType.contains("mp4", true) ||
                    stage.audioMimeType.contains("aac", true) ||
                    stage.audioMimeType.contains("m4a", true)
                val audioCodecArgs = if (isMp4Audio) {
                    listOf("-c:a", "copy")
                } else {
                    listOf("-c:a", "aac", "-b:a", "160k", "-threads", "2")
                }

                var encoded = false
                if (stage.hasThumb && stage.thumbFile.length() > 512L) {
                    val cmd = mutableListOf(
                        ffmpeg.absolutePath, "-y", "-nostdin", "-loglevel", "error",
                        "-i", stage.rawFile.absolutePath,
                        "-i", stage.thumbFile.absolutePath,
                        "-map", "0:a", "-map", "1:v"
                    )
                    cmd += audioCodecArgs
                    cmd += listOf(
                        "-c:v", "mjpeg",
                        "-disposition:v", "attached_pic",
                        "-metadata", "title=${info.title}",
                        "-metadata", "artist=${info.channel}"
                    )
                    if (albumTitle.isNotBlank()) {
                        cmd += listOf("-metadata", "album=$albumTitle")
                    }
                    cmd += listOf("-movflags", "+faststart", outFile.absolutePath)
                    encoded = runFfmpeg(cmd) && outFile.isFile && outFile.length() > 0L
                }

                if (!encoded && !cancelled) {
                    runCatching { outFile.delete() }
                    val cmd = mutableListOf(
                        ffmpeg.absolutePath, "-y", "-nostdin", "-loglevel", "error",
                        "-i", stage.rawFile.absolutePath,
                        "-vn"
                    )
                    cmd += audioCodecArgs
                    cmd += listOf(
                        "-metadata", "title=${info.title}",
                        "-metadata", "artist=${info.channel}"
                    )
                    if (albumTitle.isNotBlank()) {
                        cmd += listOf("-metadata", "album=$albumTitle")
                    }
                    cmd += listOf("-movflags", "+faststart", outFile.absolutePath)
                    encoded = runFfmpeg(cmd) && outFile.isFile && outFile.length() > 0L
                }
                return if (encoded) outFile else null
            }

            // Standard universal MP3 encode with fast LAME VBR (-q:a 4, ~165 kbps) + ID3 + cover art
            onStage("Converting to MP3 & embedding cover…")
            var encoded = false
            if (stage.hasThumb && stage.thumbFile.length() > 512L) {
                val cmd = mutableListOf(
                    ffmpeg.absolutePath, "-y", "-nostdin", "-loglevel", "error",
                    "-i", stage.rawFile.absolutePath,
                    "-i", stage.thumbFile.absolutePath,
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
                    "-i", stage.rawFile.absolutePath,
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
            stage.cleanup()
        }
    }

    // ---- Video Track Download + Instant Stream-Copy MP4 Mux ----

    private fun downloadVideoTrack(
        info: VideoStreamInfo,
        targetDir: File,
        fileBaseName: String,
        tempTag: String,
        parallelConns: Int,
        onByteProgress: (Long, Long) -> Unit,
        onStage: (String) -> Unit,
        callback: Callback
    ): File? {
        val rawStage = fetchRawVideoStreams(
            info = info,
            targetDir = targetDir,
            tempTag = tempTag,
            parallelConns = parallelConns,
            onByteProgress = onByteProgress
        )
        if (rawStage != null) {
            return muxVideoTrack(rawStage, targetDir, fileBaseName, onStage)
        }
        if (cancelled) return null
        return downloadMuxedVideoTrack(info, targetDir, fileBaseName, parallelConns, onByteProgress)
    }

    /**
     * Ensures we never silently settle for a 360p-only muxed stream when the
     * user explicitly asked for Best, 1080p, 720p, or 480p.
     */
    private fun satisfiesRequestedVideoQuality(info: VideoStreamInfo, requestedMaxHeight: Int?): Boolean {
        val wantsHd = requestedMaxHeight == null || requestedMaxHeight > 360
        if (!wantsHd) return true
        val video = info.pickVideoStream(requestedMaxHeight, forDownload = true)
        val audio = info.bestAudio()
        if (video != null && audio != null && video.height > 360) {
            return true
        }
        val muxed = info.pickMuxedStream(requestedMaxHeight)
        return muxed != null && muxed.height > 360
    }

    private fun fetchRawVideoStreams(
        info: VideoStreamInfo,
        targetDir: File,
        tempTag: String,
        parallelConns: Int,
        onByteProgress: (Long, Long) -> Unit
    ): RawVideoStage? {
        val video = info.pickVideoStream(maxHeight, forDownload = true) ?: return null
        val audio = info.bestAudio() ?: return null
        val vExt = if (video.mimeType.contains("webm", true)) "webm" else "mp4"
        val aExt = if (audio.mimeType.contains("webm", true)) "webm" else "m4a"
        val vFile = File(targetDir, ".tmp_${tempTag}_video.$vExt")
        val aFile = File(targetDir, ".tmp_${tempTag}_audio.$aExt")

        val vDone = AtomicLong(0L)
        val vTotal = AtomicLong(0L)
        val aDone = AtomicLong(0L)
        val aTotal = AtomicLong(0L)
        val audioOk = AtomicBoolean(false)

        // Download video & audio simultaneously; audio also uses 2 ranged connections
        // so it never hits YouTube's single-stream throttle.
        val audioConns = min(2, parallelConns).coerceAtLeast(1)
        val audioThread = Thread {
            audioOk.set(
                downloadUrlFast(
                    url = audio.url,
                    userAgent = info.userAgent,
                    destFile = aFile,
                    parallelConns = audioConns,
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
            runCatching { vFile.delete() }
            runCatching { aFile.delete() }
            return null
        }

        if (!videoOk || !audioOk.get() || cancelled) {
            runCatching { vFile.delete() }
            runCatching { File(vFile.absolutePath + ".part").delete() }
            runCatching { aFile.delete() }
            runCatching { File(aFile.absolutePath + ".part").delete() }
            return null
        }
        return RawVideoStage(info, vFile, aFile, audio.mimeType)
    }

    private fun muxVideoTrack(
        stage: RawVideoStage,
        targetDir: File,
        fileBaseName: String,
        onStage: (String) -> Unit
    ): File? {
        val outFile = File(targetDir, "$fileBaseName.mp4")
        try {
            if (cancelled) return null
            onStage("Merging video & audio…")
            val ffmpeg = resolveFfmpegBinary() ?: return null
            val isMp4Audio = stage.audioMimeType.contains("mp4", true) ||
                stage.audioMimeType.contains("aac", true)
            val cmd = mutableListOf(
                ffmpeg.absolutePath, "-y", "-nostdin", "-loglevel", "error",
                "-i", stage.videoFile.absolutePath,
                "-i", stage.audioFile.absolutePath,
                "-map", "0:v:0", "-map", "1:a:0",
                "-c:v", "copy",
                "-c:a", if (isMp4Audio) "copy" else "aac",
                "-movflags", "+faststart",
                outFile.absolutePath
            )
            val muxed = runFfmpeg(cmd) && outFile.isFile && outFile.length() > 0L
            return if (muxed) outFile else null
        } finally {
            stage.cleanup()
        }
    }

    private fun downloadMuxedVideoTrack(
        info: VideoStreamInfo,
        targetDir: File,
        fileBaseName: String,
        parallelConns: Int,
        onByteProgress: (Long, Long) -> Unit
    ): File? {
        val muxed = info.pickMuxedStream(maxHeight) ?: return null
        val outFile = File(targetDir, "$fileBaseName.mp4")
        val ok = downloadUrlFast(
            url = muxed.url,
            userAgent = info.userAgent,
            destFile = outFile,
            parallelConns = parallelConns,
            onByteProgress = onByteProgress
        )
        return if (ok && !cancelled && outFile.isFile && outFile.length() > 0L) outFile else null
    }

    // ---- Multi-Connection Ranged Downloader (Unthrottles ALL YouTube Downloads!) ----

    private fun downloadUrlFast(
        url: String,
        userAgent: String,
        destFile: File,
        parallelConns: Int,
        onByteProgress: (Long, Long) -> Unit
    ): Boolean {
        if (cancelled) return false
        destFile.parentFile?.mkdirs()

        // Read `clen=` directly from GoogleVideo URL when present (0ms, no probe round-trip),
        // falling back to a 1-byte Range probe otherwise.
        val totalBytes = extractClenFromUrl(url).takeIf { it > 0L }
            ?: probeContentLength(url, userAgent)
        if (cancelled) return false

        // Always use ranged chunking for GoogleVideo URLs >= 512 KB (even with 1 connection)
        // because YouTube throttles un-ranged full-file GETs to ~50-100 KB/s.
        if (totalBytes >= MULTI_CHUNK_THRESHOLD_BYTES && (parallelConns > 1 || isGoogleVideoUrl(url))) {
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

        val chunkSize = if (totalBytes < SMALL_FILE_THRESHOLD_BYTES) {
            SMALL_RANGE_CHUNK_BYTES
        } else {
            LARGE_RANGE_CHUNK_BYTES
        }
        val useQueryRange = isGoogleVideoUrl(url)
        val numChunks = ((totalBytes + chunkSize - 1L) / chunkSize).toInt()
        val workers = min(parallelConns.coerceAtLeast(1), numChunks).coerceAtLeast(1)
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
                            val expectedLen = end - start + 1L
                            var chunkOk = false
                            for (attempt in 0 until 3) {
                                if (cancelled || failed.get()) break
                                var writtenInAttempt = 0L
                                val req = if (useQueryRange) {
                                    // YouTube CDN (`googlevideo.com`) serves `&range=start-end`
                                    // at full unthrottled speed (HTTP 200 with exact chunk bytes).
                                    Request.Builder()
                                        .url(withGoogleVideoRange(url, start, end))
                                        .header("User-Agent", userAgent)
                                        .build()
                                } else {
                                    Request.Builder()
                                        .url(url)
                                        .header("User-Agent", userAgent)
                                        .header("Range", "bytes=$start-$end")
                                        .build()
                                }
                                val call = httpClient.newCall(req)
                                activeCalls.add(call)
                                try {
                                    call.execute().use { resp ->
                                        if (resp.code != 206 && !resp.isSuccessful) {
                                            return@use
                                        }
                                        // If a non-GoogleVideo server ignored Range and returned the
                                        // full file, abort ranged mode so single-stream downloads it once.
                                        if (!useQueryRange && resp.code == 200 && totalBytes > expectedLen) {
                                            failed.set(true)
                                            return@use
                                        }
                                        val body = resp.body ?: return@use
                                        raf.seek(start)
                                        body.byteStream().use { input ->
                                            var n: Int
                                            while (input.read(buf).also { n = it } != -1) {
                                                if (cancelled || failed.get()) return@use
                                                val remainingAllowed = expectedLen - writtenInAttempt
                                                if (remainingAllowed <= 0L) break
                                                val toWrite = min(n.toLong(), remainingAllowed).toInt()
                                                raf.write(buf, 0, toWrite)
                                                writtenInAttempt += toWrite
                                                val nowFile = fileBytesDone.addAndGet(toWrite.toLong())
                                                totalBytesCounter.addAndGet(toWrite.toLong())
                                                onByteProgress(nowFile, totalBytes)
                                            }
                                        }
                                        if (writtenInAttempt == expectedLen) {
                                            chunkOk = true
                                        }
                                    }
                                } catch (_: Exception) {
                                } finally {
                                    activeCalls.remove(call)
                                    if (!chunkOk && writtenInAttempt > 0L) {
                                        fileBytesDone.addAndGet(-writtenInAttempt)
                                    }
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
        baseName: String,
        onProgress: (Float?, String?) -> Unit = { _, _ -> }
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
            if (fastM4a) {
                listOf(
                    "-f", "bestaudio[ext=m4a]/bestaudio",
                    "--extract-audio", "--audio-format", "m4a",
                    "--embed-metadata", "--embed-thumbnail",
                    "--no-playlist"
                )
            } else {
                listOf(
                    "-f", "bestaudio",
                    "--extract-audio", "--audio-format", "mp3", "--audio-quality", "4",
                    "--embed-metadata", "--embed-thumbnail",
                    "--no-playlist"
                )
            }
        } else {
            val cap = if (maxHeight != null) "[height<=$maxHeight]" else ""
            val fmt = "bestvideo$cap[vcodec^=avc1]+bestaudio[ext=m4a]/" +
                "bestvideo$cap[vcodec^=avc1]+bestaudio/best$cap/best"
            listOf("-f", fmt, "--remux-video", "mp4", "--no-playlist")
        }
        try {
            runner.download(item.watchUrl, outputTemplate, extraArgs, object : DownloadListener {
                override fun onProgress(progress: DownloadProgress) {
                    val status = when {
                        !progress.speed.isNullOrBlank() ->
                            listOfNotNull(progress.speed, progress.eta?.let { "ETA $it" }).joinToString(" · ")
                        progress.rawLine.isNotBlank() ->
                            progress.rawLine.trim().take(80)
                        else -> null
                    }
                    onProgress(progress.percent, status)
                }

                override fun onDownloadedFiles(files: List<File>) {
                    files.lastOrNull { it.isFile && it.length() > 0L }?.let { produced = it }
                }

                override fun onFinished(exitCode: Int, detail: String) {
                    if (exitCode == 0 && produced == null) {
                        val expectedExt = when {
                            audioOnly && fastM4a -> ".m4a"
                            audioOnly -> ".mp3"
                            else -> ".mp4"
                        }
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

        // Lowered from 3.5 MB -> 512 KB so normal 2–4 MB audio tracks & short videos
        // ALSO use ranged chunks and bypass YouTube's single-stream throttle.
        private const val MULTI_CHUNK_THRESHOLD_BYTES = 512_000L
        private const val SMALL_FILE_THRESHOLD_BYTES = 20L * 1024L * 1024L
        private const val SMALL_RANGE_CHUNK_BYTES = 1L * 1024L * 1024L
        private const val LARGE_RANGE_CHUNK_BYTES = 2L * 1024L * 1024L

        private val CLEN_REGEX = Regex("""[?&]clen=(\d+)""")
        private val RANGE_PARAM_REGEX = Regex("""([?&])range=[^&]*(&?)""")

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

        internal fun isGoogleVideoUrl(url: String): Boolean =
            url.contains("googlevideo.com", ignoreCase = true)

        internal fun extractClenFromUrl(url: String): Long =
            CLEN_REGEX.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L

        internal fun withGoogleVideoRange(url: String, start: Long, end: Long): String {
            val cleaned = RANGE_PARAM_REGEX.replace(url) { m ->
                val prefix = m.groupValues[1]
                val trailing = m.groupValues[2]
                if (trailing == "&") prefix else ""
            }
            val sep = if (cleaned.contains('?')) "&" else "?"
            return "$cleaned${sep}range=$start-$end"
        }

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
