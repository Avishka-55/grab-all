package com.graball.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
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

    /**
     * Everything one download owns. The old code kept a single
     * runner/published/skipped set for the whole service, so a second
     * download overwrote the first — and Cancel (which carried no URL)
     * killed whichever download started last, not the row tapped.
     * That is exactly the reported bug: cancelling the top video
     * failed the other one with "read interrupted". Each URL now has
     * its own session, and cancel/pause address it by URL.
     */
    private class Session(
        val url: String,
        val outputTemplate: String,
        val audioOnly: Boolean
    ) {
        var runner: YtDlpRunner? = null

        /** Final files from the runner, used as a fallback at finish time. */
        var downloadedFiles: List<File> = emptyList()

        /**
         * Paths already published to Downloads for this download.
         * Each song of an audio playlist is published the moment it
         * finishes (see onDownloadedFiles below), so a cancelled playlist
         * keeps its finished songs instead of losing everything — and the
         * end-of-run scan must not publish the same file twice.
         */
        val publishedPaths = mutableSetOf<String>()

        /**
         * Basenames yt-dlp skipped this run (already complete from an
         * earlier run). The end-of-run scan must not publish them again,
         * or a resumed playlist would duplicate its finished songs.
         */
        val skippedNames = mutableSetOf<String>()

        /**
         * Published files for this download, relative to
         * Downloads/GrabAll ("video.mp4", "My Playlist/01 - Song.mp3").
         * The COMPLETE state carries the latest one, so the Downloads
         * screen's Open button knows what to open.
         */
        val publishedRelPaths = mutableListOf<String>()
    }

    /** Sessions by URL. Guarded by the map itself (synchronizedMap). */
    private val sessions =
        Collections.synchronizedMap(mutableMapOf<String, Session>())

    /** Stop the service only when the last session has ended. */
    private fun stopIfIdle() {
        if (sessions.isEmpty()) {
            stopSelf()
        }
    }

    /**
     * URLs paused by the user (or by a network switch). A pause kills
     * the yt-dlp process, so onFinished runs with a nonzero exit — but
     * a paused download must report PAUSED, not FAILED, and must keep
     * its staging dir fully intact for the resume. The entry is
     * cleared when the pause is reported, and again when a new
     * download for the same URL starts, so a later real failure can
     * never be misreported as a pause.
     */
    private val pausedUrls =
        Collections.synchronizedSet(mutableSetOf<String>())

    /** Pause reason per URL (\"Network changed\", or null for manual). */
    private val pauseReasons = mutableMapOf<String, String?>()

    /** Network callback for auto-pause on a network switch. */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Default network the current download started on, if known. */
    private var startNetwork: Network? = null

    /** Publish one file unless it was already published this run. */
    private fun publishFile(session: Session, file: File): Boolean {
        if (session.publishedPaths.contains(file.absolutePath)) {
            return true
        }
        // Playlist files live in a subfolder under staging
        // (see the %(playlist_title)s output template): keep that
        // folder when publishing, so a playlist lands in
        // Downloads/GrabAll/<Playlist Title> instead of flat.
        val stagingDir = File(session.outputTemplate).parentFile
        val subfolder = try {
            val parent = file.parentFile?.absolutePath ?: ""
            val root = stagingDir?.absolutePath ?: ""
            parent.removePrefix(root).trim('/').takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
        val uri = MediaStorePublisher.publish(this, file, file.name, subfolder)
        if (uri != null) {
            session.publishedPaths.add(file.absolutePath)
            // On Android 10+ (scoped storage), keep the MediaStore
            // content:// URI directly so Open works without storage
            // permission and even if MediaStore auto-renamed a duplicate.
            // On Android 5-9, keep the relative path under Downloads/GrabAll.
            if (uri.scheme == "content") {
                session.publishedRelPaths.add(uri.toString())
            } else {
                val safeFolder = subfolder?.let { MediaStorePublisher.sanitizeSubfolder(it) }
                val rel = if (safeFolder != null) "$safeFolder/${file.name}" else file.name
                session.publishedRelPaths.add(rel)
            }
            return true
        }
        return false
    }

    /**
     * URLs with a download currently running. A second tap on Download
     * for the same URL (or a re-tap after reopening the app, when the
     * queue rows are gone) must NOT start a second yt-dlp for that
     * URL: two runners for one URL would fight over the same staging
     * dir and the row would follow only the second one, which looks
     * exactly like "after finishing it starts again".
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
        registerNetworkCallback()
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        super.onDestroy()
    }

    /**
     * Auto-pause on a network switch. A switch (WiFi↔mobile, VPN
     * on/off) kills the download's socket; yt-dlp's retries then go
     * out over the NEW network, where a VPN/datacenter IP can get
     * bot-checked and fail again and again. Pausing instead keeps
     * the half-download and lets the user resume deliberately —
     * ideally with the VPN off. A pause is harmless if the switch
     * would have survived: one tap resumes from the .part file.
     */
    private fun registerNetworkCallback() {
        // registerDefaultNetworkCallback requires API 24 (Android 7.0+);
        // calling it on API 21-23 throws NoSuchMethodError (an Error, not
        // an Exception) and crashes on Android 5/6 devices.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val manager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val start = startNetwork
                if (start != null && network != start) {
                    autoPause("Network changed")
                }
            }

            override fun onLost(network: Network) {
                if (network == startNetwork) {
                    autoPause("Network changed")
                }
            }
        }
        networkCallback = callback
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (_: Throwable) {
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            val manager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            manager?.unregisterNetworkCallback(callback)
        } catch (_: Throwable) {
        }
    }

    private fun autoPause(reason: String) {
        // A switch kills every download's socket, so pause all of
        // them — each keeps its own .part and resumes separately.
        val snapshot = sessions.values.toList()
        if (snapshot.isEmpty()) return
        snapshot.forEach { session ->
            pausedUrls.add(session.url)
            pauseReasons[session.url] = reason
            session.runner?.cancel()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: return START_NOT_STICKY
        if (safeIntent.action == ACTION_CANCEL) {
            // Addressed by URL: cancelling one row must never kill
            // another download that started later.
            val url = safeIntent.getStringExtra(EXTRA_URL)
            if (url != null) {
                sessions[url]?.runner?.cancel()
            }
            return START_NOT_STICKY
        }
        if (safeIntent.action == ACTION_PAUSE) {
            val url = safeIntent.getStringExtra(EXTRA_URL)
                ?: return START_NOT_STICKY
            val session = sessions[url] ?: return START_NOT_STICKY
            if (session.runner == null) return START_NOT_STICKY
            pausedUrls.add(url)
            pauseReasons[url] = null
            session.runner?.cancel()
            return START_NOT_STICKY
        }
        if (safeIntent.action == ACTION_RESUME) {
            val url = safeIntent.getStringExtra(EXTRA_URL)
                ?: return START_NOT_STICKY
            val params = paramsByUrl[url]
            if (params == null) {
                Toast.makeText(
                    this,
                    "Start the download again from the link",
                    Toast.LENGTH_SHORT
                ).show()
                return START_NOT_STICKY
            }
            startDownload(
                this, url, params.outputTemplate, params.audioOnly,
                params.maxHeight, params.playlistMode
            )
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
        // A fresh start clears any stale pause mark, so a later real
        // failure is never misreported as a pause.
        pausedUrls.remove(url)
        pauseReasons.remove(url)
        logsByUrl.remove(url)
        val session = Session(url, outputTemplate, audioOnly)
        sessions[url] = session
        paramsByUrl[url] = DownloadParams(
            outputTemplate, audioOnly, maxHeight, playlistMode
        )
        // The network this download starts on: a switch away from it
        // auto-pauses (see registerNetworkCallback). activeNetwork is
        // API 23+ (Android 6.0+), so guard it to avoid NoSuchMethodError
        // on Android 5.0/5.1.
        startNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.activeNetwork
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        startForeground(NOTIFICATION_ID, buildNotification("Preparing download…", null))
        // The row is born NOW, not on the first progress tick minutes
        // later: the Downloads screen shows "Preparing…" at once, and
        // a screen opened mid-download replays this from the bus.
        DownloadProgressBus.post(
            DownloadProgressBus.State(
                url, null, DownloadProgressBus.Status.PREPARING
            )
        )
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
                    sessions.remove(url)
                    stopIfIdle()
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
                        cookiesFile = cookies,
                        cacheDir = cacheDir,
                        homeDir = filesDir,
                        certFile = RuntimePaths.certFile(this)
                    )
                    session.runner = newRunner
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
                                    files.forEach { publishFile(session, it) }
                                } else {
                                    session.downloadedFiles = files
                                }
                            }

                            override fun onSkippedFiles(files: List<File>) {
                                files.forEach { session.skippedNames.add(it.name) }
                            }

                            override fun onLog(log: String) {
                                logsByUrl[url] = log
                            }

                            override fun onFinished(exitCode: Int, detail: String) {
                                activeUrls.remove(url)
                                sessions.remove(url)
                                if (pausedUrls.contains(url)) {
                                    // Pause, not failure: the user (or
                                    // a network switch) stopped it
                                    // deliberately. Keep staging fully
                                    // intact — the resume restarts this
                                    // same download in the same dir,
                                    // and yt-dlp continues from .part.
                                    pausedUrls.remove(url)
                                    val reason = pauseReasons.remove(url)
                                    this@DownloadService.updateNotification(
                                        "Download paused", null
                                    )
                                    DownloadProgressBus.post(
                                        DownloadProgressBus.State(
                                            url, null,
                                            DownloadProgressBus.Status.PAUSED,
                                            reason
                                        )
                                    )
                                    this@DownloadService.stopIfIdle()
                                    return
                                }
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
                                    // Recursive walk: playlist files live
                                    // in a %(playlist_title)s subfolder
                                    // under staging, not flat beside it.
                                    val outDir = File(outputTemplate).parentFile
                                    val scanned = outDir?.walkTopDown()
                                        ?.filter { it.isFile && it.length() > 0 &&
                                            !it.name.endsWith(".part") &&
                                            !it.name.endsWith(".ytdl") &&
                                            !session.skippedNames.contains(it.name) }
                                        ?.toList()
                                        ?: emptyList()
                                    val candidates = if (scanned.isNotEmpty()) {
                                        scanned
                                    } else {
                                        session.downloadedFiles.filter {
                                            it.exists() &&
                                                !session.skippedNames.contains(it.name)
                                        }
                                    }
                                    candidates.forEach { file ->
                                        publishFile(session, file)
                                    }
                                    val published = session.publishedPaths.size
                                    // Drop the whole staging dir, so a
                                    // later download of the same video can
                                    // never "skip" off a leftover.
                                    outDir?.deleteRecursively()
                                    // A run that only skipped files is
                                    // still a success: everything is
                                    // already in Downloads from the
                                    // earlier run.
                                    if (published > 0 ||
                                        session.skippedNames.isNotEmpty()
                                    ) {
                                        this@DownloadService.updateNotification(
                                            "Download complete", null
                                        )
                                        DownloadProgressBus.post(
                                            DownloadProgressBus.State(
                                                url, 100f,
                                                DownloadProgressBus.Status.COMPLETE,
                                                output = session.publishedRelPaths.lastOrNull()
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
                                    // (published mid-run), so drop only
                                    // the unpublished leftovers — but
                                    // KEEP .part/.ytdl files and the
                                    // staging originals of songs
                                    // already published. They are what
                                    // a re-tap resumes from (the .part
                                    // for the interrupted song, the
                                    // complete files yt-dlp will skip),
                                    // and keeping them avoids both a
                                    // restart-from-zero and duplicates.
                                    val outDir = File(outputTemplate).parentFile
                                    // Recursive: with playlist subfolders,
                                    // leftovers can sit a level down too.
                                    // Only delete unpublished, non-resume
                                    // files; .part/.ytdl and published
                                    // originals stay for the resume.
                                    outDir?.walkTopDown()
                                        ?.filter { file ->
                                            file.isFile &&
                                                !file.name.endsWith(".part") &&
                                                !file.name.endsWith(".ytdl") &&
                                                !session.publishedPaths.contains(
                                                    file.absolutePath
                                                )
                                        }
                                        ?.forEach { it.delete() }
                                    // Prune subfolders left empty by that.
                                    outDir?.walkBottomUp()
                                        ?.filter { it.isDirectory && it != outDir }
                                        ?.forEach { dir ->
                                            if (dir.listFiles()?.isEmpty() == true) {
                                                dir.delete()
                                            }
                                        }
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
                                this@DownloadService.stopIfIdle()
                            }

                            override fun onError(message: String) {
                                activeUrls.remove(url)
                                sessions.remove(url)
                                this@DownloadService.updateNotification(
                                    "Download error: $message", null
                                )
                                DownloadProgressBus.post(
                                    DownloadProgressBus.State(
                                        url, null, DownloadProgressBus.Status.FAILED, message
                                    )
                                )
                                this@DownloadService.stopIfIdle()
                            }
                        })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download service failed", e)
                activeUrls.remove(url)
                sessions.remove(url)
                updateNotification("Download error: " + (e.message ?: "unknown"), null)
                DownloadProgressBus.post(
                    DownloadProgressBus.State(
                        url, null, DownloadProgressBus.Status.FAILED,
                        e.message ?: "unknown error"
                    )
                )
                stopIfIdle()
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
        const val RUNTIME_VERSION = "python3.13-ytdlp2026.10.26-v5"

        private const val EXTRA_URL = "com.graball.engine.extra.URL"
        private const val EXTRA_OUTPUT = "com.graball.engine.extra.OUTPUT"
        private const val EXTRA_AUDIO_ONLY = "com.graball.engine.extra.AUDIO_ONLY"
        private const val EXTRA_MAX_HEIGHT = "com.graball.engine.extra.MAX_HEIGHT"
        private const val EXTRA_PLAYLIST_MODE = "com.graball.engine.extra.PLAYLIST_MODE"
        private const val ACTION_CANCEL = "com.graball.engine.action.CANCEL"
        private const val ACTION_PAUSE = "com.graball.engine.action.PAUSE"
        private const val ACTION_RESUME = "com.graball.engine.action.RESUME"

        /**
         * Everything needed to restart a download exactly as it
         * started (same quality, audio mode, playlist size, staging
         * dir). Kept per URL so a paused row can resume without the
         * activity remembering anything — including rows the browser
         * started. Lives in the companion, so it survives the service
         * stopping after a pause; it does NOT survive a process death,
         * in which case resume falls back to "start again from the
         * link".
         */
        private data class DownloadParams(
            val outputTemplate: String,
            val audioOnly: Boolean,
            val maxHeight: Int?,
            val playlistMode: String
        )

        private val paramsByUrl = mutableMapOf<String, DownloadParams>()

        /** Full non-progress log per URL, for the copy-log button. */
        private val logsByUrl = mutableMapOf<String, String>()

        fun getLog(url: String): String? = logsByUrl[url]

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
         * (stream copy, no transcode). Audio-only takes the best audio
         * stream and converts it to real MP3 via the bundled ffmpeg
         * (built with libmp3lame) — the old m4a output confused him,
         * since he calls the files mp3.
         */
        private fun formatArgs(audioOnly: Boolean, maxHeight: Int?): List<String> {
            if (audioOnly) {
                return listOf(
                    "-f", "bestaudio",
                    "--extract-audio", "--audio-format", "mp3"
                )
            }
            val cap = if (maxHeight != null) "[height<=$maxHeight]" else ""
            // Prefer H.264 (avc1) + AAC (m4a) first so the remuxed MP4
            // plays with hardware video AND working audio on every device
            // from Android 5 through Android 16 (older players fail on
            // Opus-in-MP4), falling back cleanly to any audio/video.
            val format = "bestvideo$cap[vcodec^=avc1]+bestaudio[ext=m4a]/" +
                "bestvideo$cap[vcodec^=avc1]+bestaudio/" +
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

        /**
         * Cancel one download, addressed by its URL.
         * Uses startService (not startForegroundService): onStartCommand
         * returns early for control actions without calling startForeground(),
         * which would throw ForegroundServiceDidNotStartInTimeException on
         * Android 8+ if startForegroundService were used when idle.
         */
        fun cancel(context: Context, url: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_URL, url)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        /** Pause one download, addressed by its URL. */
        fun pause(context: Context, url: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_URL, url)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        /** Resume a paused download with its stored parameters. */
        fun resume(context: Context, url: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME
                putExtra(EXTRA_URL, url)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
