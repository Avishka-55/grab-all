package com.graball.engine

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Progress parsed from yt-dlp's stdout. Percent is null for lines that are
 * not download-progress lines (status messages, warnings, etc.).
 */
data class DownloadProgress(
    val percent: Float?,
    val speed: String?,
    val eta: String?,
    val rawLine: String
)

interface DownloadListener {
    /** Called on the runner's worker thread; post to main before touching UI. */
    fun onProgress(progress: DownloadProgress)
    /** [detail] carries the last error lines when [exitCode] is nonzero. */
    fun onFinished(exitCode: Int, detail: String = "")
    fun onError(message: String)
    /** Final output files, delivered just before onFinished when known. */
    fun onDownloadedFiles(files: List<File>) {}
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
                val proc = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                process = proc
                val destinations = mutableListOf<File>()
                var merged: File? = null
                // Last non-progress output lines: the evidence when the
                // run fails. Progress lines are noise; error lines from
                // yt-dlp, Python tracebacks, and linker errors are signal.
                val tail = ArrayDeque<String>()
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val raw = line ?: break
                        listener.onProgress(parseProgress(raw))
                        if (!PROGRESS_REGEX.containsMatchIn(raw)) {
                            tail.addLast(raw)
                            while (tail.size > 8) tail.removeFirst()
                        }
                        DESTINATION_REGEX.find(raw)?.groupValues?.get(1)?.let {
                            destinations += File(it.trim())
                        }
                        MERGER_REGEX.find(raw)?.groupValues?.get(1)?.let {
                            merged = File(it.trim())
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
            val proc = ProcessBuilder(
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

    private fun parseProgress(raw: String): DownloadProgress {
        // Typical line: [download]  42.3% of 12.34MiB at  1.23MiB/s ETA 00:07
        val percent = PROGRESS_REGEX
            .find(raw)?.groupValues?.get(1)?.toFloatOrNull()
        val speed = Regex("""at\s+(\S+/s)""").find(raw)?.groupValues?.get(1)
        val eta = Regex("""ETA\s+(\S+)""").find(raw)?.groupValues?.get(1)
        return DownloadProgress(percent, speed, eta, raw)
    }

    companion object {
        private const val TAG = "YtDlpRunner"

        /** Matches yt-dlp progress lines, which are noise for error tails. */
        private val PROGRESS_REGEX = Regex("""\[download\]\s+(\d+(?:\.\d+)?)%""")

        /** "[download] Destination: /path" and "[ExtractAudio] Destination:". */
        private val DESTINATION_REGEX =
            Regex("""\[(?:download|ExtractAudio)\] Destination: (.+)$""")

        /** `[Merger] Merging formats into "/path"`. */
        private val MERGER_REGEX =
            Regex("""\[Merger\] Merging formats into "(.+)"""")
    }
}
