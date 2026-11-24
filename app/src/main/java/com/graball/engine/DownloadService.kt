package com.graball.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

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
        @Volatile
        var runner: YtDlpRunner? = null

        @Volatile
        var fastDownloader: FastYouTubeDownloader? = null

        @Volatile
        var isQueued: Boolean = false

        fun cancelActive() {
            fastDownloader?.cancel()
            runner?.cancel()
        }

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

    /** Dynamic concurrency gate honoring Settings -> Concurrent Downloads. */
    private val slotLock = ReentrantLock()
    private val slotCondition = slotLock.newCondition()
    private var activeSlotCount = 0

    private fun acquireDownloadSlot(session: Session): Boolean {
        slotLock.lock()
        try {
            session.isQueued = true
            var announcedQueued = false
            while (true) {
                if (pausedUrls.contains(session.url) || cancelledUrls.contains(session.url)) {
                    session.isQueued = false
                    return false
                }
                val limit = DownloadPrefs.resolvedConcurrency(this)
                if (activeSlotCount < limit) {
                    activeSlotCount++
                    session.isQueued = false
                    return true
                }
                if (!announcedQueued) {
                    announcedQueued = true
                    DownloadProgressBus.post(
                        DownloadProgressBus.State(
                            session.url,
                            null,
                            DownloadProgressBus.Status.PREPARING,
                            detail = "Queued (max $limit active)…"
                        )
                    )
                }
                try {
                    slotCondition.await(1000L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    session.isQueued = false
                    return false
                }
            }
        } finally {
            slotLock.unlock()
        }
    }

    private fun releaseDownloadSlot() {
        slotLock.lock()
        try {
            if (activeSlotCount > 0) activeSlotCount--
            slotCondition.signalAll()
        } finally {
            slotLock.unlock()
        }
    }

    private fun wakeSlotWaiters() {
        slotLock.lock()
        try {
            slotCondition.signalAll()
        } finally {
            slotLock.unlock()
        }
    }

    /** Stop the service only when the last session has ended. */
    private fun stopIfIdle() {
        if (sessions.isEmpty()) {
            releasePowerLocks()
            stopSelf()
        }
    }

    // ---- WakeLock & WifiLock (keeps downloads running smoothly when screen turns off) ----
    private val powerLockGuard = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private fun acquirePowerLocks() {
        synchronized(powerLockGuard) {
            try {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    wakeLock = pm?.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "GrabAll::DownloadWakeLock"
                    )?.apply { setReferenceCounted(false) }
                }
                if (wakeLock?.isHeld == false) {
                    // Safety cap of 6 hours per active acquisition; released immediately in stopIfIdle()
                    wakeLock?.acquire(6L * 60L * 60L * 1000L)
                }
            } catch (_: Throwable) {
            }
            try {
                if (wifiLock == null) {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    @Suppress("DEPRECATION")
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    wifiLock = wm?.createWifiLock(mode, "GrabAll::DownloadWifiLock")
                        ?.apply { setReferenceCounted(false) }
                }
                if (wifiLock?.isHeld == false) {
                    wifiLock?.acquire()
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun releasePowerLocks() {
        synchronized(powerLockGuard) {
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (_: Throwable) {
            }
            try {
                if (wifiLock?.isHeld == true) wifiLock?.release()
            } catch (_: Throwable) {
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingNetworkPause: Runnable? = null

    private fun scheduleNetworkAutoPause(reason: String) {
        pendingNetworkPause?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            pendingNetworkPause = null
            if (sessions.isNotEmpty()) {
                autoPause(reason)
            }
        }
        pendingNetworkPause = task
        // 4s grace window: brief screen-off Wi-Fi power-save blips reconnect
        // within 1-2s and must NOT pause active downloads.
        mainHandler.postDelayed(task, 4_000L)
    }

    private fun cancelScheduledNetworkAutoPause() {
        pendingNetworkPause?.let { mainHandler.removeCallbacks(it) }
        pendingNetworkPause = null
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

    /** URLs explicitly cancelled by the user (staging dir gets wiped cleanly). */
    private val cancelledUrls =
        Collections.synchronizedSet(mutableSetOf<String>())

    /** Pause reason per URL (\"Network changed\", or null for manual). */
    private val pauseReasons = mutableMapOf<String, String?>()

    /** Network callback for auto-pause on a network switch. */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Default network the current download started on, if known. */
    private var startNetwork: Network? = null

    /** Publish one file unless it was already published this run (thread-safe for parallel workers). */
    private fun publishFile(session: Session, file: File): Boolean {
        synchronized(session) {
            if (session.publishedPaths.contains(file.absolutePath)) {
                return true
            }
            // Playlist files live in a subfolder under the URL's staging dir
            // (e.g. <staging>/<Playlist Title>/01 - Song.mp3). Route every
            // download into its platform folder under Downloads/GrabAll/
            // (YouTube/Audio, YouTube/Video, YouTube/<Playlist>, TikTok, etc.).
            val stagingRoot = RuntimePaths.stagingDirFor(this, session.url)
            val playlistSubfolder = try {
                val parent = file.parentFile?.absolutePath ?: ""
                val root = stagingRoot.absolutePath
                if (parent.startsWith(root)) {
                    parent.removePrefix(root).trim('/').takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
            val targetSubfolder = MediaStorePublisher.targetSubfolder(
                url = session.url,
                audioOnly = session.audioOnly,
                playlistSubfolder = playlistSubfolder
            )
            val uri = MediaStorePublisher.publish(this, file, file.name, targetSubfolder)
            if (uri != null) {
                session.publishedPaths.add(file.absolutePath)
                if (DownloadProgressBus.titles[session.url].isNullOrBlank()) {
                    val inferred = playlistSubfolder?.takeIf { it.isNotBlank() }
                        ?: file.nameWithoutExtension.takeIf { it.isNotBlank() }
                    if (inferred != null) {
                        DownloadProgressBus.titles[session.url] = inferred
                    }
                }
                if (uri.scheme == "content") {
                    session.publishedRelPaths.add(uri.toString())
                } else {
                    val safeFolder = MediaStorePublisher.sanitizeSubfolder(targetSubfolder)
                    val rel = "$safeFolder/${file.name}"
                    session.publishedRelPaths.add(rel)
                }
                return true
            }
            return false
        }
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
        cancelScheduledNetworkAutoPause()
        unregisterNetworkCallback()
        releasePowerLocks()
        super.onDestroy()
    }

    /**
     * Auto-pause on a real network switch (WiFi↔mobile, VPN on/off) with a
     * 4-second grace debounce so screen-off Wi-Fi power-save blips never
     * falsely pause running downloads.
     */
    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val manager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val start = startNetwork
                if (start == null || network == start) {
                    cancelScheduledNetworkAutoPause()
                } else {
                    // Genuine switch to a different network interface (e.g. Wi-Fi <-> Mobile)
                    scheduleNetworkAutoPause("Network changed")
                }
            }

            override fun onLost(network: Network) {
                if (network == startNetwork) {
                    scheduleNetworkAutoPause("Connection lost")
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
            session.cancelActive()
        }
        wakeSlotWaiters()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: return START_NOT_STICKY
        if (safeIntent.action == ACTION_CANCEL) {
            // Addressed by URL: cancelling one row must never kill
            // another download that started later. Mark as cancelled so
            // staging .part files are cleaned up instead of leaking disk.
            val url = safeIntent.getStringExtra(EXTRA_URL)
            if (url != null) {
                cancelledUrls.add(url)
                pausedUrls.remove(url)
                pauseReasons.remove(url)
                clearSavedParams(this, url)
                val session = sessions[url]
                if (session != null && (session.runner != null || session.fastDownloader != null || session.isQueued)) {
                    session.cancelActive()
                    wakeSlotWaiters()
                } else {
                    // Already paused — wipe its kept staging dir now
                    RuntimePaths.stagingDirFor(this, url).deleteRecursively()
                }
            }
            return START_NOT_STICKY
        }
        if (safeIntent.action == ACTION_PAUSE) {
            val url = safeIntent.getStringExtra(EXTRA_URL)
                ?: return START_NOT_STICKY
            val session = sessions[url] ?: return START_NOT_STICKY
            if (session.runner == null && session.fastDownloader == null && !session.isQueued) {
                return START_NOT_STICKY
            }
            pausedUrls.add(url)
            pauseReasons[url] = null
            session.cancelActive()
            wakeSlotWaiters()
            return START_NOT_STICKY
        }
        if (safeIntent.action == ACTION_RESUME) {
            val url = safeIntent.getStringExtra(EXTRA_URL)
                ?: return START_NOT_STICKY
            val params = paramsByUrl[url] ?: loadSavedParams(this, url)
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
        cancelledUrls.remove(url)
        pauseReasons.remove(url)
        logsByUrl.remove(url)
        val session = Session(url, outputTemplate, audioOnly)
        sessions[url] = session
        val params = DownloadParams(outputTemplate, audioOnly, maxHeight, playlistMode)
        paramsByUrl[url] = params
        saveParams(this, url, params)
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

        val prepNotif = buildNotification("Preparing download…", null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                prepNotif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, prepNotif)
        }
        acquirePowerLocks()
        // The row is born NOW, not on the first progress tick minutes
        // later: the Downloads screen shows "Preparing…" at once, and
        // a screen opened mid-download replays this from the bus.
        DownloadProgressBus.post(
            DownloadProgressBus.State(
                url, null, DownloadProgressBus.Status.PREPARING
            )
        )
        Thread {
            if (!acquireDownloadSlot(session)) {
                activeUrls.remove(url)
                sessions.remove(url)
                if (pausedUrls.remove(url)) {
                    val reason = pauseReasons.remove(url)
                    updateNotification("Download paused", null)
                    DownloadProgressBus.post(
                        DownloadProgressBus.State(
                            url, null, DownloadProgressBus.Status.PAUSED, reason
                        )
                    )
                } else if (cancelledUrls.remove(url)) {
                    RuntimePaths.stagingDirFor(this, url).deleteRecursively()
                    clearSavedParams(this, url)
                    updateNotification("Download cancelled", null)
                    DownloadProgressBus.post(
                        DownloadProgressBus.State(
                            url, null, DownloadProgressBus.Status.FAILED, "Cancelled"
                        )
                    )
                }
                stopIfIdle()
                return@Thread
            }
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

                    // ---- Tier 1: Fast Native Kotlin YouTube Accelerator ----
                    // Multi-connection Range chunks for single videos & N-worker
                    // parallel engine for YouTube playlists (100+ songs in mins).
                    if (FastYouTubeDownloader.isYouTubeUrl(url)) {
                        val stagingDir = RuntimePaths.stagingDirFor(this, url)
                        val fastLog = StringBuilder()
                        val fast = FastYouTubeDownloader(
                            context = this,
                            url = url,
                            stagingDir = stagingDir,
                            audioOnly = audioOnly,
                            maxHeight = maxHeight,
                            playlistMode = playlistMode,
                            concurrency = DownloadPrefs.resolvedConcurrency(this)
                        )
                        session.fastDownloader = fast
                        val fastResult = fast.run(object : FastYouTubeDownloader.Callback {
                            override fun onTitleResolved(title: String) {
                                DownloadProgressBus.titles[url] = title
                            }

                            override fun onProgress(
                                percent: Float?,
                                speed: String?,
                                eta: String?,
                                item: String?,
                                detail: String?
                            ) {
                                val itemSuffix = item?.let { " · $it" } ?: ""
                                val text = if (percent != null) {
                                    "Downloading… %.1f%%".format(percent) + itemSuffix
                                } else {
                                    (detail ?: "Downloading…") + itemSuffix
                                }
                                this@DownloadService.updateNotification(text, percent?.toInt())
                                val liveDetail = detail ?: listOfNotNull(
                                    speed,
                                    eta?.let { "ETA $it" }
                                ).joinToString(" · ").takeIf { it.isNotBlank() }
                                DownloadProgressBus.post(
                                    DownloadProgressBus.State(
                                        url,
                                        percent,
                                        DownloadProgressBus.Status.DOWNLOADING,
                                        detail = liveDetail,
                                        item = item
                                    )
                                )
                            }

                            override fun onFileReady(file: File) {
                                publishFile(session, file)
                            }

                            override fun onLogLine(line: String) {
                                synchronized(fastLog) {
                                    if (fastLog.length < 32_000) {
                                        fastLog.append(line).append('\n')
                                    }
                                    logsByUrl[url] = fastLog.toString()
                                }
                            }
                        })
                        session.fastDownloader = null
                        if (fastResult.handled) {
                            activeUrls.remove(url)
                            sessions.remove(url)
                            if (pausedUrls.contains(url)) {
                                pausedUrls.remove(url)
                                val reason = pauseReasons.remove(url)
                                updateNotification("Download paused", null)
                                DownloadProgressBus.post(
                                    DownloadProgressBus.State(
                                        url, null, DownloadProgressBus.Status.PAUSED, reason
                                    )
                                )
                            } else if (fastResult.exitCode == 0) {
                                RuntimePaths.stagingDirFor(this, url).deleteRecursively()
                                clearSavedParams(this, url)
                                if (session.publishedPaths.isNotEmpty() || fastResult.skippedCount > 0) {
                                    updateNotification("Download complete", null)
                                    DownloadProgressBus.post(
                                        DownloadProgressBus.State(
                                            url,
                                            100f,
                                            DownloadProgressBus.Status.COMPLETE,
                                            output = session.publishedRelPaths.lastOrNull()
                                        )
                                    )
                                } else {
                                    updateNotification("Download failed (no file produced)", null)
                                    DownloadProgressBus.post(
                                        DownloadProgressBus.State(
                                            url, null, DownloadProgressBus.Status.FAILED,
                                            "Download finished but no file was produced."
                                        )
                                    )
                                }
                            } else if (cancelledUrls.remove(url)) {
                                RuntimePaths.stagingDirFor(this, url).deleteRecursively()
                                clearSavedParams(this, url)
                                updateNotification("Download cancelled", null)
                                DownloadProgressBus.post(
                                    DownloadProgressBus.State(
                                        url, null, DownloadProgressBus.Status.FAILED, "Cancelled"
                                    )
                                )
                            } else {
                                updateNotification("Download failed", null)
                                DownloadProgressBus.post(
                                    DownloadProgressBus.State(
                                        url, null, DownloadProgressBus.Status.FAILED,
                                        fastResult.detail.ifBlank { "Download failed" }
                                    )
                                )
                            }
                            stopIfIdle()
                            return@Thread
                        }
                    }

                    // ---- Tier 2: Turbo yt-dlp Engine (all other sites & restricted YT fallback) ----
                    val cookies = cookiesFile.takeIf { it.exists() }
                    val newRunner = YtDlpRunner(
                        pythonBinary = RuntimePaths.pythonBinary(this),
                        ytDlpScript = RuntimePaths.ytDlpScript(this),
                        ffmpegDir = RuntimePaths.ffmpegDir(this),
                        quickJsBinary = RuntimePaths.quickJsBinary(this),
                        cookiesFile = cookies,
                        cacheDir = cacheDir,
                        homeDir = filesDir,
                        certFile = RuntimePaths.certFile(this),
                        pythonHomeDir = RuntimePaths.pythonHomeDir(this),
                        pythonLibDir = RuntimePaths.pythonLibDir(this),
                        pythonPathDir = RuntimePaths.ytDlpBundleDir(this)
                    )
                    session.runner = newRunner
                    val runnerDone = CountDownLatch(1)
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
                                // Surface the latest yt-dlp status line (e.g.
                                // Extracting URL / Merging / Solving JS) when no
                                // percentage is active yet so the row never looks hung.
                                val liveDetail = if (pct == null) {
                                    progress.rawLine.trim().take(120).takeIf { it.isNotBlank() }
                                } else {
                                    listOfNotNull(progress.speed, progress.eta?.let { "ETA $it" })
                                        .joinToString(" · ")
                                        .takeIf { it.isNotBlank() }
                                }
                                DownloadProgressBus.post(
                                    DownloadProgressBus.State(
                                        url, pct, DownloadProgressBus.Status.DOWNLOADING,
                                        detail = liveDetail,
                                        item = progress.item
                                    )
                                )
                            }

                            override fun onDownloadedFiles(files: List<File>) {
                                if (audioOnly) {
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
                                try {
                                    if (pausedUrls.contains(url)) {
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
                                        val outDir = RuntimePaths.stagingDirFor(this@DownloadService, url)
                                        val scanned = outDir.walkTopDown()
                                            .filter { it.isFile && it.length() > 0 &&
                                                !it.name.endsWith(".part") &&
                                                !it.name.endsWith(".ytdl") &&
                                                !session.skippedNames.contains(it.name) }
                                            .toList()
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
                                        outDir.deleteRecursively()
                                        clearSavedParams(this@DownloadService, url)
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
                                    } else if (cancelledUrls.remove(url)) {
                                        RuntimePaths.stagingDirFor(this@DownloadService, url).deleteRecursively()
                                        clearSavedParams(this@DownloadService, url)
                                        this@DownloadService.updateNotification(
                                            "Download cancelled", null
                                        )
                                        DownloadProgressBus.post(
                                            DownloadProgressBus.State(
                                                url, null, DownloadProgressBus.Status.FAILED,
                                                "Cancelled"
                                            )
                                        )
                                    } else {
                                        val outDir = RuntimePaths.stagingDirFor(this@DownloadService, url)
                                        outDir.walkTopDown()
                                            .filter { file ->
                                                file.isFile &&
                                                    !file.name.endsWith(".part") &&
                                                    !file.name.endsWith(".ytdl") &&
                                                    !session.publishedPaths.contains(
                                                        file.absolutePath
                                                    )
                                            }
                                            .forEach { it.delete() }
                                        outDir.walkBottomUp()
                                            .filter { it.isDirectory && it != outDir }
                                            .forEach { dir ->
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
                                } finally {
                                    runnerDone.countDown()
                                }
                            }

                            override fun onError(message: String) {
                                activeUrls.remove(url)
                                sessions.remove(url)
                                try {
                                    this@DownloadService.updateNotification(
                                        "Download error: $message", null
                                    )
                                    DownloadProgressBus.post(
                                        DownloadProgressBus.State(
                                            url, null, DownloadProgressBus.Status.FAILED, message
                                        )
                                    )
                                    this@DownloadService.stopIfIdle()
                                } finally {
                                    runnerDone.countDown()
                                }
                            }
                        })
                    try {
                        runnerDone.await()
                    } catch (_: InterruptedException) {
                    }
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
            } finally {
                releaseDownloadSlot()
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
        val openDownloadsIntent = Intent(this, com.graball.DownloadsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPi = PendingIntent.getActivity(this, 101, openDownloadsIntent, piFlags)
        val active = sessions.isNotEmpty()

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setContentTitle("GrabAll")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentPi)
            .setOngoing(active)
            .setAutoCancel(!active)
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
        const val RUNTIME_VERSION = "python3.13-ytdlp2026.10.26-v6"

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

        private const val PARAMS_PREFS = "graball_download_params"

        private fun saveParams(context: Context, url: String, p: DownloadParams) {
            try {
                val encoded = listOf(
                    p.outputTemplate,
                    p.audioOnly.toString(),
                    (p.maxHeight ?: 0).toString(),
                    p.playlistMode
                ).joinToString("\n")
                context.getSharedPreferences(PARAMS_PREFS, Context.MODE_PRIVATE)
                    .edit().putString(url, encoded).apply()
            } catch (_: Exception) {
            }
        }

        private fun loadSavedParams(context: Context, url: String): DownloadParams? {
            return try {
                val raw = context.getSharedPreferences(PARAMS_PREFS, Context.MODE_PRIVATE)
                    .getString(url, null) ?: return null
                val parts = raw.split("\n")
                if (parts.size < 4) return null
                DownloadParams(
                    outputTemplate = parts[0],
                    audioOnly = parts[1] == "true",
                    maxHeight = parts[2].toIntOrNull()?.takeIf { it > 0 },
                    playlistMode = parts[3]
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun clearSavedParams(context: Context, url: String) {
            paramsByUrl.remove(url)
            try {
                context.getSharedPreferences(PARAMS_PREFS, Context.MODE_PRIVATE)
                    .edit().remove(url).apply()
            } catch (_: Exception) {
            }
        }

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
                    "--extract-audio", "--audio-format", "mp3", "--audio-quality", "4",
                    "--embed-metadata", "--embed-thumbnail"
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
                    "--yes-playlist", "--ignore-errors", "--playlist-end", playlistMode
                )
                "all" -> listOf("--yes-playlist", "--ignore-errors")
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
