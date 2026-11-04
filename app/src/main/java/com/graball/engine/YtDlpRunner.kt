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
    private val cookiesFile: File?
) {
    @Volatile
    private var process: Process? = null

    /**
     * CPython is built --enable-shared, so the interpreter itself and
     * ctypes both need libpython3.13.so. The loader finds it via
     * LD_LIBRARY_PATH pointing at the extracted python lib dir; without
     * it the process dies at startup (and ctypes dies with
     * "libpython3.13.so not found", as seen on device in v0.2.4).
     */
    private fun pythonProcessBuilder(command: List<String>): ProcessBuilder {
        val builder = ProcessBuilder(command)
        val pythonDir = pythonBinary.parentFile?.parentFile
        if (pythonDir != null) {
            builder.environment()["LD_LIBRARY_PATH"] =
                File(pythonDir, "lib").absolutePath
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
        // enforce executability on every run, not just during extraction.
        if (!RuntimeExtractor.ensureExecutable(pythonBinary)) {
            listener.onError(
                "Python engine not executable: " + pythonBinary.absolutePath
            )
            return
        }
        Thread {
            try {
                val command = buildCommand(url, outputTemplate, extraArgs)
                Log.d(TAG, "Running: " + command.joinToString(" "))
                val proc = pythonProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                process = proc
                val destinations = mutableListOf<File>()
                var merged: File? = null
                // Last non-progress output lines: the evidence when the
                // run fails. Progress lines are noise; error lines from
                // yt-dlp, Python tracebacks, and linker errors are signal.
                val tail = ArrayDeque<String>()
                val skipped = mutableListOf<File>()
                // Playlist position, from yt-dlp's own
                // "[download] Downloading item N of M" lines. Without
                // it, a playlist looks like one download that keeps
                // restarting: each song's percent runs 0→100 and the
                // row gives no hint which song is playing.
                var currentItem: String? = null
                // The last destination seen, emitted as finished when
                // the NEXT destination appears (yt-dlp prints
                // Destination when it starts a file, so a file is
                // certainly done once the next one starts). The
                // service publishes these immediately for audio-only
                // downloads, where a destination IS the final file —
                // so each song appears in Downloads as it finishes
                // instead of only when the whole playlist ends. For
                // merged video downloads the service ignores these
                // mid-run emissions (they are parts, not finals) and
                // publishes only the final file at the end.
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
                        }
                        // [ExtractAudio] Destination lines REPLACE the
                        // pending destination instead of emitting it:
                        // the original file is deleted after conversion,
                        // so publishing it would save a file yt-dlp is
                        // about to remove (and duplicate the mp3 that
                        // follows). The mp3 becomes pending, and is
                        // emitted when the next song starts — by then
                        // its conversion is done.
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
                        // A resume run skips files that already exist
                        // complete in the stable staging dir. The
                        // service must know them so the end-of-run
                        // scan does not publish them a second time.
                        SKIPPED_REGEX.find(raw)?.groupValues?.get(1)?.let {
                            skipped += File(it.trim())
                        }
                    }
                }
                val exitCode = proc.waitFor()
                process = null
                // The merged file replaces its parts (yt-dlp deletes them),
                // so prefer it when present; otherwise use the destinations.
                val finalFiles = merged?.let { listOf(it) } ?: destinations
                if (finalFiles.isNotEmpty()) {
                    listener.onDownloadedFiles(finalFiles)
                }
                if (skipped.isNotEmpty()) {
                    listener.onSkippedFiles(skipped)
                }
                val detail = if (exitCode == 0) "" else tail.joinToString("\n")
                listener.onFinished(exitCode, detail)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                listener.onError(e.message ?: "Unknown error")
            }
        }.start()
    }

    /** Kill the running download, if any. */
    fun cancel() {
        process?.destroy()
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
