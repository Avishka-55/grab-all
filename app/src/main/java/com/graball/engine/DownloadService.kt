package com.graball.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import java.io.File
import java.util.Collections

/**
 * Foreground service that runs one yt-dlp download at a time.
 *
 * Flow: on start, show a "Preparing…" notification immediately (a foreground
 * service must call startForeground quickly), extract the native runtime if
 * needed (off the main thread), then run YtDlpRunner and mirror its progress
 * into the notification. The service stops itself on finish, error, or
 * cancellation.
 *
 * The download output template points at RuntimePaths' staging dir; the
 * MediaStore publish step that moves finished files into the public
 * Downloads folder is still TODO.
 */
class DownloadService : Service() {

    @Volatile
    private var runner: YtDlpRunner? = null

    /** Final files from the runner, used as a fallback at finish time. */
    private var downloadedFiles: List<File> = emptyList()

    /**
     * Paths already published to Downloads for the current download.
     * Each song of an audio playlist is published the moment it
     * finishes (see onDownloadedFiles below), so a cancelled playlist
     * keeps its finished songs instead of losing everything — and the
     * end-of-run scan must not publish the same file twice.
     */
    private val publishedPaths = mutableSetOf<String>()

    /** Publish one file unless it was already published this run. */
    private fun publishFile(file: File): Boolean {
        if (publishedPaths.contains(file.absolutePath)) {
            return true
        }
        val ok = MediaStorePublisher.publish(this, file, file.name) != null
        if (ok) {
            publishedPaths.add(file.absolutePath)
        }
        return ok
    }

    /**
     * URLs with a download currently running. A second tap on Download
     * for the same URL (or a re-tap after reopening the app, when the
     * queue rows are gone) must NOT start a second yt-dlp: the service
     * holds a single runner var, so the first process would be
     * orphaned — still downloading and publishing in the background
     * while the row follows the second one, which looks exactly like
     * "after finishing it starts again".
     */
    private val activeUrls =
        Collections.synchronizedSet(mutableSetOf<String>())

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager().createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: return START_NOT_STICKY
        if (safeIntent.action == ACTION_CANCEL) {
            runner?.cancel()
            return START_NOT_STICKY
        }
        val url = safeIntent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val outputTemplate = safeIntent.getStringExtra(EXTRA_OUTPUT)
            ?: return START_NOT_STICKY
        val audioOnly = safeIntent.getBooleanExtra(EXTRA_AUDIO_ONLY, false)
        val maxHeight =
            if (safeIntent.hasExtra(EXTRA_MAX_HEIGHT)) {
                safeIntent.getIntExtra(EXTRA_MAX_HEIGHT, 0).takeIf { it > 0 }
            } else {
                null
            }
        val playlistMode =
            safeIntent.getStringExtra(EXTRA_PLAYLIST_MODE) ?: "single"

        // Guard after all early returns, so a rejected intent never
        // leaves the URL stuck in the set.
        if (!activeUrls.add(url)) {
            Toast.makeText(this, "Already downloading this", Toast.LENGTH_SHORT).show()
            return START_NOT_STICKY
        }
        publishedPaths.clear()

        startForeground(NOTIFICATION_ID, buildNotification("Preparing download…", null))
        Thread {
            try {
                if (!RuntimeExtractor.extractIfNeeded(this, RUNTIME_VERSION)) {
                    updateNotification("Runtime setup failed", null)
                    DownloadProgressBus.post(
                        DownloadProgressBus.State(
                            url, null, DownloadProgressBus.Status.FAILED,
                            RuntimeExtractor.lastError ?: "Runtime setup failed"
                        )
                    )
                    activeUrls.remove(url)
                    stopSelf()
                } else {
                    // Repair cookies files saved by older imports, which
                    // dropped the "# Netscape HTTP Cookie File" header
                    // that yt-dlp requires. Only prepends when the file
                    // exists and lacks the header; never touches a good file.
                    val cookiesFile = RuntimePaths.cookiesFile(this)
                    if (cookiesFile.exists()) {
                        try {
                            val first = cookiesFile.bufferedReader().readLine()
                            if (first == null ||
                                !first.startsWith("# Netscape HTTP Cookie File")
                            ) {
                                val body = cookiesFile.readText()
                                cookiesFile.writeText(
                                    "# Netscape HTTP Cookie File\n" + body
                                )
                            }
                        } catch (_: Exception) {
                        }
                    }
                    val cookies = cookiesFile.takeIf { it.exists() }
                    val newRunner = YtDlpRunner(
                        pythonBinary = RuntimePaths.pythonBinary(this),
                        ytDlpScript = RuntimePaths.ytDlpScript(this),
                        ffmpegDir = RuntimePaths.ffmpegDir(this),
                        quickJsBinary = RuntimePaths.quickJsBinary(this),
                        cookiesFile = cookies
                    )
                    runner = newRunner
                    newRunner.download(
                        url, outputTemplate,
                        formatArgs(audioOnly, maxHeight) +
                            playlistArgs(playlistMode),
                        object : DownloadListener {
                            override fun onProgress(progress: DownloadProgress) {
                                val pct = progress.percent
                                val itemSuffix = progress.item?.let { " · $it" } ?: ""
                                val text = if (pct != null) {
                                    "Downloading… %.1f%%".format(pct) + itemSuffix
                                } else {
                                    "Downloading…" + itemSuffix
                                }
                                this@DownloadService.updateNotification(text, pct?.toInt())
                                DownloadProgressBus.post(
                                    DownloadProgressBus.State(
                                        url, pct, DownloadProgressBus.Status.DOWNLOADING,
                                        item = progress.item
                                    )
                                )
                            }

                            override fun onDownloadedFiles(files: List<File>) {
                                if (audioOnly) {
                                    // Audio downloads have no merge or
                                    // remux step, so a destination the
                                    // runner reports mid-run IS a
                                    // finished song: publish it now so
                                    // it shows up in Downloads at once.
                                    // Video downloads keep the old
                                    // behavior (publish only the final
                                    // merged file at the end).
                                    files.forEach { publishFile(it) }
                                } else {
                                    downloadedFiles = files
                                }
                            }

                            override fun onFinished(exitCode: Int, detail: String) {
                                activeUrls.remove(url)
                                if (exitCode == 0) {
                                    // The runner's Destination/Merger log
                                    // lines go STALE when --remux-video
                                    // runs: yt-dlp deletes the merged file
                                    // after remuxing, so the recorded path
                                    // no longer exists — and yt-dlp can
                                    // also exit 0 having produced nothing
                                    // (an "already downloaded" skip off a
                                    // leftover in shared staging). The old
                                    // code published whatever path it was
                                    // given, skipped missing files
                                    // silently, and still reported Done —
                                    // exactly the v0.2.7 "Done but no file"
                                    // bug: audio-only worked (no remux),
                                    // a capped resolution could pick a
                                    // webm combo, remux to mp4, and leave
                                    // nothing publishable behind. So the
                                    // truth is the output directory
                                    // itself, and Done requires at least
                                    // one file actually published.
                                    val outDir = File(outputTemplate).parentFile
                                    val scanned = outDir?.listFiles()
                                        ?.filter { it.isFile && it.length() > 0 &&
                                            !it.name.endsWith(".part") &&
                                            !it.name.endsWith(".ytdl") }
                                        ?: emptyList()
                                    val candidates = if (scanned.isNotEmpty()) {
                                        scanned
                                    } else {
                                        downloadedFiles.filter { it.exists() }
                                    }
                                    candidates.forEach { file ->
                                        publishFile(file)
                                    }
                                    val published = publishedPaths.size
                                    // Drop the whole staging dir, so a
                                    // later download of the same video can
                                    // never "skip" off a leftover.
                                    outDir?.listFiles()?.forEach { it.delete() }
                                    outDir?.delete()
                                    if (published > 0) {
                                        this@DownloadService.updateNotification(
                                            "Download complete", null
                                        )
                                        DownloadProgressBus.post(
                                            DownloadProgressBus.State(
                                                url, 100f,
                                                DownloadProgressBus.Status.COMPLETE
                                            )
                                        )
                                    } else {
                                        this@DownloadService.updateNotification(
                                            "Download failed (no file produced)", null
                                        )
                                        DownloadProgressBus.post(
                                            DownloadProgressBus.State(
                                                url, null,
                                                DownloadProgressBus.Status.FAILED,
                                                "Download finished but no file " +
                                                    "was produced. Try again, " +
                                                    "or pick a different quality."
                                            )
                                        )
                                    }
                                } else {
                                    // Failed or cancelled: finished
                                    // songs are already in Downloads
                                    // (published mid-run), so dropping
                                    // staging only removes partials and
                                    // unpublished leftovers.
                                    val outDir = File(outputTemplate).parentFile
                                    outDir?.listFiles()?.forEach { it.delete() }
                                    outDir?.delete()
                                    this@DownloadService.updateNotification(
                                        "Download failed (exit $exitCode)", null
                                    )
                                    DownloadProgressBus.post(
                                        DownloadProgressBus.State(
                                            url, null, DownloadProgressBus.Status.FAILED,
                                            detail.ifBlank { "yt-dlp exited $exitCode" }
                                        )
                                    )
                                }
                                this@DownloadService.stopSelf()
                            }

                            override fun onError(message: String) {
                                activeUrls.remove(url)
                                this@DownloadService.updateNotification(
                                    "Download error: $message", null
                                )
                                DownloadProgressBus.post(
                                    DownloadProgressBus.State(
                                        url, null, DownloadProgressBus.Status.FAILED, message
                                    )
                                )
                                this@DownloadService.stopSelf()
                            }
                        })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download service failed", e)
                activeUrls.remove(url)
                updateNotification("Download error: " + (e.message ?: "unknown"), null)
                DownloadProgressBus.post(
                    DownloadProgressBus.State(
                        url, null, DownloadProgressBus.Status.FAILED,
                        e.message ?: "unknown error"
                    )
                )
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun notificationManager(): NotificationManager {
        return getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun updateNotification(text: String, percent: Int?) {
        notificationManager().notify(NOTIFICATION_ID, buildNotification(text, percent))
    }

    private fun buildNotification(text: String, percent: Int?): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setContentTitle("GrabAll")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
        if (percent != null) {
            builder.setProgress(100, percent, false)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "graball_downloads"
        private const val NOTIFICATION_ID = 1

        /** Bump when the bundled native assets change, to force re-extraction. */
        private const val RUNTIME_VERSION = "python3.13-ytdlp2026.10.26-v3"

        private const val EXTRA_URL = "com.graball.engine.extra.URL"
        private const val EXTRA_OUTPUT = "com.graball.engine.extra.OUTPUT"
        private const val EXTRA_AUDIO_ONLY = "com.graball.engine.extra.AUDIO_ONLY"
        private const val EXTRA_MAX_HEIGHT = "com.graball.engine.extra.MAX_HEIGHT"
        private const val EXTRA_PLAYLIST_MODE = "com.graball.engine.extra.PLAYLIST_MODE"
        private const val ACTION_CANCEL = "com.graball.engine.action.CANCEL"

        fun startDownload(
            context: Context,
            url: String,
            outputTemplate: String,
            audioOnly: Boolean = false,
            maxHeight: Int? = null,
            playlistMode: String = "single"
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_OUTPUT, outputTemplate)
                putExtra(EXTRA_AUDIO_ONLY, audioOnly)
                if (maxHeight != null) putExtra(EXTRA_MAX_HEIGHT, maxHeight)
                putExtra(EXTRA_PLAYLIST_MODE, playlistMode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * H.264-first formats. YouTube's plain "best" is often AV1, which
         * his old phone cannot decode in hardware: the system player
         * refuses it ("Codec not supported: AV1") and VLC's software
         * decode stalls. So prefer avc1 (H.264) at or below the chosen
         * height, fall back to anything at that height, then to best.
         * Remux to mp4 so the container is always player-friendly
         * (stream copy, no transcode). Audio-only takes the m4a stream
         * directly — no MP3 step, since the bundled ffmpeg has no
         * libmp3lame.
         */
        private fun formatArgs(audioOnly: Boolean, maxHeight: Int?): List<String> {
            if (audioOnly) {
                return listOf("-f", "bestaudio[ext=m4a]/bestaudio")
            }
            val cap = if (maxHeight != null) "[height<=$maxHeight]" else ""
            val format = "bestvideo$cap[vcodec^=avc1]+bestaudio/" +
                "best$cap[vcodec^=avc1]/best$cap/best"
            return listOf("-f", format, "--remux-video", "mp4")
        }

        /**
         * Playlist flags. "single" forces one video even for a URL that
         * carries a playlist (yt-dlp's default for a watch URL is single
         * anyway; --no-playlist makes it explicit). A number caps the
         * playlist; "all" takes everything. Callers pick an indexed
         * output template for playlist modes so same-title videos in
         * one playlist don't overwrite each other.
         */
        private fun playlistArgs(playlistMode: String): List<String> {
            return when (playlistMode) {
                "20", "30", "50" -> listOf(
                    "--yes-playlist", "--playlist-end", playlistMode
                )
                "all" -> listOf("--yes-playlist")
                else -> listOf("--no-playlist")
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
