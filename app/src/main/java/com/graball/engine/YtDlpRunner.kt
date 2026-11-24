package com.graball.engine

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Progress parsed from yt-dlp's stdout. Percent is null for lines that are
 * not download-progress lines (status messages, warnings, etc.). [item]
 * is yt-dlp's playlist position ("Item 5 of 20") when the current
 * download is one entry of a playlist, else null.
 */
data class DownloadProgress(
    val percent: Float?,
    val speed: String?,
    val eta: String?,
    val rawLine: String,
    val item: String? = null
)

interface DownloadListener {
    /** Called on the runner's worker thread; post to main before touching UI. */
    fun onProgress(progress: DownloadProgress)
    /** [detail] carries the last error lines when [exitCode] is nonzero. */
    fun onFinished(exitCode: Int, detail: String = "")
    fun onError(message: String)
    /** Final output files, delivered just before onFinished when known. */
    fun onDownloadedFiles(files: List<File>) {}
    /** Files yt-dlp skipped because they already exist (resume runs). */
    fun onSkippedFiles(files: List<File>) {}
    /** Full non-progress log (capped), delivered just before onFinished. */
    fun onLog(log: String) {}
}

/**
 * Runs yt-dlp as a Python script under the app's embedded CPython runtime.
 *
 * This follows the proven youtubedl-android pattern: ProcessBuilder launches
 * the extracted Python interpreter with the yt-dlp script, and progress is
 * read from the process's stdout. All paths are injected (see RuntimePaths)
 * so this class never depends on resources that do not exist yet.
 *
 * Requirements on the runtime side (see ENGINE_PLAN.md):
 * - pythonBinary: extracted CPython executable (newer than 3.8).
 * - ytDlpScript: extracted yt-dlp script, WITH its yt-dlp-ejs data files.
 * - ffmpegDir: directory holding an ffmpeg executable (yt-dlp merges and
 *   converts by shelling out to it; --ffmpeg-location points here).
 * - quickJsBinary: qjs executable cross-compiled for armeabi-v7a, used as
 *   yt-dlp's JS runtime for YouTube's JS challenges.
 * - cookiesFile: Netscape-format cookies.txt in private storage, or null
 *   for anonymous downloads.
 */
class YtDlpRunner(
    private val pythonBinary: File,
    private val ytDlpScript: File,
    private val ffmpegDir: File?,
    private val quickJsBinary: File?,
    private val cookiesFile: File?,
    private val cacheDir: File? = null,
    private val homeDir: File? = null,
    private val certFile: File? = null,
    private val pythonHomeDir: File? = null,
    private val pythonLibDir: File? = null,
    private val pythonPathDir: File? = null
) {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var cancelled: Boolean = false

    /**
     * Configures environment for our bundled CPython 3.13 runtime:
     * - LD_LIBRARY_PATH -> nativeLibraryDir (or runtime/python/lib) for libpython3.13.so
     * - PYTHONHOME      -> runtime/python so stdlib resolves even when bin lives in nativeLibraryDir
     * - PYTHONPATH      -> runtime/ytdlp so an updated yt-dlp zipapp still sees certifi/mutagen/requests
     * - TMPDIR / HOME   -> app private dirs (Android has no writable /tmp)
     * - SSL_CERT_FILE   -> bundled certifi/cacert.pem (works on Android 5-16)
     */
    private fun pythonProcessBuilder(command: List<String>): ProcessBuilder {
        val builder = ProcessBuilder(command)
        val env = builder.environment()
        val inferredHome = pythonHomeDir ?: pythonBinary.parentFile?.parentFile
        val inferredLib = pythonLibDir ?: inferredHome?.let { File(it, "lib") }
        if (inferredLib != null) {
            env["LD_LIBRARY_PATH"] = inferredLib.absolutePath
        }
        if (inferredHome != null) {
            env["PYTHONHOME"] = inferredHome.absolutePath
        }
        if (pythonPathDir != null && pythonPathDir.exists()) {
            env["PYTHONPATH"] = pythonPathDir.absolutePath
        }
        if (cacheDir != null) {
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            env["TMPDIR"] = cacheDir.absolutePath
        }
        if (homeDir != null) {
            env["HOME"] = homeDir.absolutePath
        }
        if (certFile != null && certFile.exists()) {
            env["SSL_CERT_FILE"] = certFile.absolutePath
        }
        return builder
    }

    /** Download [url] to [outputTemplate] (yt-dlp -o template). */
    fun download(
        url: String,
        outputTemplate: String,
        extraArgs: List<String> = emptyList(),
        listener: DownloadListener
    ) {
        // The version marker can skip extraction while the bits are
        // wrong (v0.2.0 left a non-executable python binary behind), so
        // enforce executability on every run for python, ffmpeg/ffprobe,
        // and QuickJS — not just during extraction.
        if (!RuntimeExtractor.ensureExecutable(pythonBinary)) {
            listener.onError(
                "Python engine not executable: " + pythonBinary.absolutePath
            )
            return
        }
        if (quickJsBinary != null && quickJsBinary.exists()) {
            RuntimeExtractor.ensureExecutable(quickJsBinary)
        }
        if (ffmpegDir != null) {
            val ffmpeg = File(ffmpegDir, "ffmpeg")
            if (ffmpeg.exists()) {
                RuntimeExtractor.ensureExecutable(ffmpeg)
            }
            val ffprobe = File(ffmpegDir, "ffprobe")
            if (ffprobe.exists()) {
                RuntimeExtractor.ensureExecutable(ffprobe)
            }
        }
        cancelled = false
        Thread {
            // Declared outside the try so the exception path can
            // still deliver it: a download killed mid-read (cancel,
            // process death) exits through catch, and the old code
            // only delivered the log on the normal path — so exactly
            // the failed downloads a user most wants to copy from
            // reported "No log to copy".
            val logLines = ArrayDeque<String>()
            var logDelivered = false
            fun deliverLog() {
                if (!logDelivered && logLines.isNotEmpty()) {
                    logDelivered = true
                    listener.onLog(logLines.joinToString("\n"))
                }
            }
            try {
                // Fast-path for YouTube:
                // - When signed in (cookiesFile exists): go STRAIGHT to yt-dlp's
                //   cookie-capable clients (default authed clients: web_embedded,
                //   tv_samsung, web — then web_safari,mweb fallback). Never waste
                //   Attempt 1 on android_vr/visionos, which have SUPPORTS_COOKIES=False
                //   and ignore the user's login on VPN IPs!
                // - When anonymous: try android_vr,visionos (fast JS-free) first,
                //   then fall back to default full clients.
                val isYouTube = isYouTubeUrl(url)
                val hasCookies = cookiesFile != null && cookiesFile.exists() && cookiesFile.length() > 0L
                val attempts: List<List<String>> = when {
                    isYouTube && hasCookies -> listOf(
                        extraArgs,
                        listOf("--extractor-args", "youtube:player_client=web_safari,mweb") + extraArgs
                    )
                    isYouTube -> listOf(
                        listOf("--extractor-args", "youtube:player_client=android_vr,visionos") + extraArgs,
                        extraArgs
                    )
                    else -> listOf(extraArgs)
                }

                val destinations = mutableListOf<File>()
                var merged: File? = null
                val tail = ArrayDeque<String>()
                val skipped = mutableListOf<File>()
                var exitCode = -1

                for ((attemptIdx, attemptArgs) in attempts.withIndex()) {
                    if (cancelled) break
                    if (attemptIdx > 0) {
                        val fallbackMsg = "[GrabAll] Fast client unavailable, retrying with full engine…"
                        logLines.addLast(fallbackMsg)
                        listener.onProgress(DownloadProgress(null, null, null, fallbackMsg, null))
                        tail.clear()
                    }
                    val command = buildCommand(url, outputTemplate, attemptArgs)
                    Log.d(TAG, "Running (attempt ${attemptIdx + 1}): " + command.joinToString(" "))
                    val proc = pythonProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start()
                    process = proc

                    var currentItem: String? = null
                    var lastDestination: File? = null
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val raw = line ?: break
                            ITEM_REGEX.find(raw)?.let { match ->
                                currentItem = "Item " + match.groupValues[1] +
                                    " of " + match.groupValues[2]
                                listener.onProgress(
                                    DownloadProgress(
                                        null, null, null, raw, currentItem
                                    )
                                )
                            }
                            listener.onProgress(parseProgress(raw, currentItem))
                            if (!PROGRESS_REGEX.containsMatchIn(raw)) {
                                tail.addLast(raw)
                                while (tail.size > 8) tail.removeFirst()
                                logLines.addLast(raw)
                                while (logLines.size > 500) logLines.removeFirst()
                            }
                            val extractDest =
                                EXTRACT_AUDIO_REGEX.find(raw)?.groupValues?.get(1)
                            if (extractDest != null) {
                                val dest = File(extractDest.trim())
                                lastDestination = dest
                                destinations += dest
                            } else {
                                DESTINATION_REGEX.find(raw)?.groupValues?.get(1)?.let {
                                    val dest = File(it.trim())
                                    lastDestination?.let { prev ->
                                        listener.onDownloadedFiles(listOf(prev))
                                    }
                                    lastDestination = dest
                                    destinations += dest
                                }
                            }
                            MERGER_REGEX.find(raw)?.groupValues?.get(1)?.let {
                                merged = File(it.trim())
                            }
                            SKIPPED_REGEX.find(raw)?.groupValues?.get(1)?.let {
                                skipped += File(it.trim())
                            }
                        }
                    }
                    exitCode = proc.waitFor()
                    process = null
                    if (exitCode == 0 || cancelled) {
                        break
                    }
                    // If a playlist already downloaded or skipped tracks on the fast
                    // client (e.g. 1 private video caused a non-zero exit with -i),
                    // don't re-run the entire playlist on the slow fallback client.
                    if (attemptIdx == 0 && (destinations.isNotEmpty() || merged != null || skipped.isNotEmpty())) {
                        exitCode = 0
                        break
                    }
                }
                // The merged file replaces its parts (yt-dlp deletes them),
                // so prefer it when present; otherwise use the destinations.
                val finalFiles = merged?.let { listOf(it) } ?: destinations
                if (finalFiles.isNotEmpty()) {
                    listener.onDownloadedFiles(finalFiles)
                }
                if (skipped.isNotEmpty()) {
                    listener.onSkippedFiles(skipped)
                }
                deliverLog()
                val detail = if (exitCode == 0) "" else tail.joinToString("\n")
                listener.onFinished(exitCode, detail)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                // Deliver the log even on the killed/interrupted path,
                // before reporting the error, or Copy log finds nothing.
                deliverLog()
                listener.onError(e.message ?: "Unknown error")
            }
        }.start()
    }

    /** Kill the running download, if any. */
    fun cancel() {
        cancelled = true
        process?.destroy()
    }

    private fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/") || lower.contains("youtu.be/")
    }

    /**
     * Synchronous metadata + direct stream extraction (--dump-single-json).
     * Used as a bulletproof fallback by the native YouTube player whenever
     * InnerTube direct extraction is restricted (e.g. Made-for-Kids, age-gated
     * with cookies, or bot-checked IP). Call off the main thread.
     *
     * Drains stderr on a helper thread (preventing pipe buffer deadlock) and
     * enforces a hard timeout so a stalled process can never spin forever.
     */
    fun extractInfoJson(url: String, timeoutSec: Long = 25L): Pair<String?, String> {
        if (!RuntimeExtractor.ensureExecutable(pythonBinary)) {
            return null to "Python runtime not executable"
        }
        if (quickJsBinary != null && quickJsBinary.exists()) {
            RuntimeExtractor.ensureExecutable(quickJsBinary)
        }
        // When cookies exist, skip cookie-less visionos and go straight to
        // yt-dlp's authenticated clients with a generous 60s timeout so QuickJS
        // can finish solving YouTube's JS challenge on ARM CPUs without being killed.
        val hasCookies = cookiesFile != null && cookiesFile.exists() && cookiesFile.length() > 0L
        val jsTimeoutSec = maxOf(timeoutSec, 60L)
        val fastAttempts: List<Pair<List<String>, Long>> = when {
            isYouTubeUrl(url) && hasCookies -> listOf(
                emptyList<String>() to jsTimeoutSec,
                listOf("--extractor-args", "youtube:player_client=web_safari,mweb") to jsTimeoutSec
            )
            isYouTubeUrl(url) -> listOf(
                listOf("--extractor-args", "youtube:player_client=visionos") to timeoutSec,
                emptyList<String>() to jsTimeoutSec
            )
            else -> listOf(emptyList<String>() to jsTimeoutSec)
        }

        var lastErr = "yt-dlp returned no stream info"
        for ((extraClientArgs, attemptTimeout) in fastAttempts) {
            val (json, err) = runExtractOnce(url, extraClientArgs, attemptTimeout)
            if (json != null) return json to ""
            if (err.isNotBlank()) lastErr = err
        }
        return null to lastErr
    }

    private fun runExtractOnce(
        url: String,
        extraArgs: List<String>,
        timeoutSec: Long
    ): Pair<String?, String> {
        var proc: Process? = null
        return try {
            val cmd = mutableListOf(
                pythonBinary.absolutePath,
                ytDlpScript.absolutePath,
                "--dump-single-json",
                "--no-playlist",
                "--no-warnings",
                "--skip-download",
                "--socket-timeout", "15"
            )
            cmd += extraArgs
            if (quickJsBinary != null && quickJsBinary.exists()) {
                cmd += listOf("--js-runtimes", "quickjs:" + quickJsBinary.absolutePath)
            }
            if (cookiesFile != null && cookiesFile.exists()) {
                cmd += listOf("--cookies", cookiesFile.absolutePath)
            }
            cmd += url
            val started = pythonProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start()
            proc = started

            val errBuf = StringBuilder()
            val errThread = Thread {
                try {
                    BufferedReader(InputStreamReader(started.errorStream)).use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            if (errBuf.length < 1000) {
                                errBuf.append(line).append('\n')
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }

            // Watchdog thread (works on API 21+ unlike Process.waitFor(timeout))
            val watchdog = Thread {
                try {
                    Thread.sleep(timeoutSec * 1000L)
                    started.destroy()
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; start() }

            val stdout = BufferedReader(InputStreamReader(started.inputStream)).use { it.readText() }
            started.waitFor()
            watchdog.interrupt()
            errThread.join(1000)

            val trimmed = stdout.trim()
            if (trimmed.startsWith("{")) {
                trimmed to ""
            } else {
                val errMsg = errBuf.toString().trim().lines().lastOrNull().orEmpty()
                null to errMsg.ifBlank { "yt-dlp returned no stream info" }
            }
        } catch (e: Throwable) {
            proc?.destroy()
            Log.e(TAG, "extractInfoJson failed", e)
            null to (e.message ?: "yt-dlp error")
        }
    }

    /**
     * Synchronous runtime health check. Returns the yt-dlp version string,
     * or null if the runtime does not run. Call off the main thread.
     */
    fun version(): String? {
        return try {
            val proc = pythonProcessBuilder(
                listOf(pythonBinary.absolutePath, ytDlpScript.absolutePath, "--version")
            ).redirectErrorStream(true).start()
            val first = BufferedReader(InputStreamReader(proc.inputStream)).readLine()
            proc.waitFor()
            first?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Runtime check failed", e)
            null
        }
    }

    private fun buildCommand(
        url: String,
        outputTemplate: String,
        extraArgs: List<String>
    ): List<String> {
        val cmd = mutableListOf(
            pythonBinary.absolutePath,
            ytDlpScript.absolutePath,
            "--newline",
            // Multi-fragment & 2M chunked HTTP acceleration (saturates 50+ Mbps
            // links on HLS/DASH and bypasses YouTube's per-stream throttle).
            "--concurrent-fragments", "6",
            "--http-chunk-size", "2M",
            "--buffer-size", "16K",
            // Resume resilience: a network switch kills the socket, so
            // let yt-dlp itself retry (from the .part file, in the same
            // staging dir) instead of failing at the first drop, and
            // time out stalled sockets so a dead connection is noticed.
            "--retries", "30",
            "--retry-sleep", "linear=1:10",
            "--socket-timeout", "30",
            "-o", outputTemplate
        )
        if (ffmpegDir != null) {
            cmd += listOf("--ffmpeg-location", ffmpegDir.absolutePath)
        }
        if (quickJsBinary != null) {
            cmd += listOf("--js-runtimes", "quickjs:" + quickJsBinary.absolutePath)
        }
        if (cookiesFile != null && cookiesFile.exists()) {
            cmd += listOf("--cookies", cookiesFile.absolutePath)
        }
        cmd += extraArgs
        cmd += url
        return cmd
    }

    private fun parseProgress(raw: String, item: String?): DownloadProgress {
        // Typical line: [download]  42.3% of 12.34MiB at  1.23MiB/s ETA 00:07
        val percent = PROGRESS_REGEX
            .find(raw)?.groupValues?.get(1)?.toFloatOrNull()
        val speed = Regex("""at\s+(\S+/s)""").find(raw)?.groupValues?.get(1)
        val eta = Regex("""ETA\s+(\S+)""").find(raw)?.groupValues?.get(1)
        return DownloadProgress(percent, speed, eta, raw, item)
    }

    companion object {
        private const val TAG = "YtDlpRunner"

        /** Matches yt-dlp progress lines, which are noise for error tails. */
        private val PROGRESS_REGEX = Regex("""\[download\]\s+(\d+(?:\.\d+)?)%""")
        private val ITEM_REGEX =
            Regex("""\[download\] Downloading item (\d+) of (\d+)""")

        /** "[download] Destination: /path" and "[ExtractAudio] Destination:". */
        private val DESTINATION_REGEX =
            Regex("""\[(?:download|ExtractAudio)\] Destination: (.+)$""")

        /** "[ExtractAudio] Destination: /path" on its own. */
        private val EXTRACT_AUDIO_REGEX =
            Regex("""\[ExtractAudio\] Destination: (.+)$""")

        /** `[Merger] Merging formats into "/path"`. */
        private val MERGER_REGEX =
            Regex("""\[Merger\] Merging formats into "(.+)"""")

        /** "[download] /path has already been downloaded" (resume skip). */
        private val SKIPPED_REGEX =
            Regex("""\[download\] (.+) has already been downloaded""")
    }
}
