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
import java.io.File

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

    /** Final files from the runner, published on successful finish. */
    private var downloadedFiles: List<File> = emptyList()

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
                    newRunner.download(url, outputTemplate, emptyList(),
                        object : DownloadListener {
                            override fun onProgress(progress: DownloadProgress) {
                                val pct = progress.percent
                                val text = if (pct != null) {
                                    "Downloading… %.1f%%".format(pct)
                                } else {
                                    "Downloading…"
                                }
                                this@DownloadService.updateNotification(text, pct?.toInt())
                                DownloadProgressBus.post(
                                    DownloadProgressBus.State(
                                        url, pct, DownloadProgressBus.Status.DOWNLOADING
                                    )
                                )
                            }

                            override fun onDownloadedFiles(files: List<File>) {
                                downloadedFiles = files
                            }

                            override fun onFinished(exitCode: Int, detail: String) {
                                if (exitCode == 0) {
                                    downloadedFiles.forEach { file ->
                                        if (file.exists() &&
                                            MediaStorePublisher.publish(
                                                this@DownloadService, file, file.name
                                            ) != null
                                        ) {
                                            // Published copy is in Downloads;
                                            // drop the staging copy.
                                            file.delete()
                                        }
                                    }
                                    this@DownloadService.updateNotification(
                                        "Download complete", null
                                    )
                                    DownloadProgressBus.post(
                                        DownloadProgressBus.State(
                                            url, 100f, DownloadProgressBus.Status.COMPLETE
                                        )
                                    )
                                } else {
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
        private const val ACTION_CANCEL = "com.graball.engine.action.CANCEL"

        fun startDownload(context: Context, url: String, outputTemplate: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_OUTPUT, outputTemplate)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
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
