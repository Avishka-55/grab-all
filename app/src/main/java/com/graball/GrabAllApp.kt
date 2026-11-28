package com.graball

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.graball.engine.DownloadProgressBus
import com.graball.engine.RuntimePaths
import com.graball.youtube.FloatingAudioController
import com.graball.youtube.ThumbnailLoader
import com.graball.youtube.YouTubeJsSolver
import com.graball.youtube.YouTubePlaybackService
import com.graball.youtube.YouTubePlayerActivity
import com.graball.youtube.YouTubePoTokenProvider
import com.graball.youtube.YouTubeSessionConfig
import com.graball.youtube.YouTubeSubtitleParser
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Application entry point that attaches GrabAll's lightweight, zero-idle-CPU
 * [FloatingAudioController] and live download badge across screens automatically,
 * and evicts stale pooled HTTP sockets when the network/VPN changes or the app
 * wakes from background/screen-off.
 */
class GrabAllApp : Application() {

    private val floatingControllers = WeakHashMap<Activity, FloatingAudioController>()
    private val downloadBadges = WeakHashMap<Activity, NavHelper.DownloadBadgeController>()
    private val netCleanupExecutor = Executors.newSingleThreadExecutor()
    private var resumedActivityCount = 0
    private var lastBackgroundedAtMs = 0L
    private var lastEvictAtMs = 0L
    private var hasSeenInitialNetwork = false
    private var lastVpnTransport: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerNetworkResetCallback()
        scheduleIdleCacheHousekeeping()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is YouTubePlayerActivity) {
                    otherActivityCount++
                }
            }
            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                val wasBackgrounded = resumedActivityCount == 0
                resumedActivityCount++
                if (wasBackgrounded && lastBackgroundedAtMs > 0L) {
                    val awayMs = SystemClock.elapsedRealtime() - lastBackgroundedAtMs
                    if (awayMs >= FOREGROUND_EVICT_THRESHOLD_MS) {
                        evictStaleNetworkState("foreground_resume_${awayMs}ms")
                    }
                }
                val badge = downloadBadges.getOrPut(activity) {
                    NavHelper.DownloadBadgeController(activity)
                }
                badge.attach()
                syncFloatingBubble(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivityCount = (resumedActivityCount - 1).coerceAtLeast(0)
                if (resumedActivityCount == 0) {
                    lastBackgroundedAtMs = SystemClock.elapsedRealtime()
                }
                downloadBadges[activity]?.detach()
                floatingControllers[activity]?.detach()
            }

            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                downloadBadges.remove(activity)?.detach()
                floatingControllers.remove(activity)?.detach()
                if (activity !is YouTubePlayerActivity) {
                    otherActivityCount = (otherActivityCount - 1).coerceAtLeast(0)
                }
            }
        })
    }

    private fun registerNetworkResetCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (hasSeenInitialNetwork) {
                        evictStaleNetworkState("network_available")
                    } else {
                        hasSeenInitialNetwork = true
                    }
                }

                override fun onLost(network: Network) {
                    evictStaleNetworkState("network_lost")
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val vpnNow = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    val prev = lastVpnTransport
                    lastVpnTransport = vpnNow
                    if (prev != null && prev != vpnNow) {
                        evictStaleNetworkState(if (vpnNow) "vpn_connected" else "vpn_disconnected")
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(req, callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "NetworkCallback registration failed: ${e.message}")
        }
    }

    private fun scheduleIdleCacheHousekeeping() {
        Handler(Looper.getMainLooper()).postDelayed({
            netCleanupExecutor.execute {
                try {
                    val now = System.currentTimeMillis()
                    // 1) Remove abandoned download staging dirs older than 7 days (only when idle).
                    if (DownloadProgressBus.activeCount() == 0) {
                        val stagingRoot = RuntimePaths.stagingDir(applicationContext)
                        val stagingCutoff = now - 7L * 24L * 60L * 60L * 1000L
                        stagingRoot.listFiles()?.forEach { child ->
                            if (latestModifiedMs(child) < stagingCutoff) {
                                child.deleteRecursively()
                            }
                        }
                    }
                    // 2) Prune old yt-dlp player JS cache entries older than 14 days.
                    val ytDlpCache = RuntimePaths.ytDlpCacheDir(applicationContext)
                    val ytCacheCutoff = now - 14L * 24L * 60L * 60L * 1000L
                    pruneOldFiles(ytDlpCache, ytCacheCutoff)

                    // 3) Evict expired (>6h old) YouTube stream chunks from ExoPlayer's SimpleCache.
                    YouTubePlaybackService.pruneExpiredMediaCache(applicationContext)
                } catch (e: Throwable) {
                    Log.w(TAG, "Idle cache housekeeping error: ${e.message}")
                }
            }
        }, 6_000L)
    }

    private fun latestModifiedMs(file: java.io.File): Long {
        if (!file.isDirectory) return file.lastModified()
        var latest = file.lastModified()
        file.listFiles()?.forEach { child ->
            val m = latestModifiedMs(child)
            if (m > latest) latest = m
        }
        return latest
    }

    private fun pruneOldFiles(dir: java.io.File, cutoffMs: Long) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                pruneOldFiles(child, cutoffMs)
                if (child.listFiles()?.isEmpty() == true) {
                    child.delete()
                }
            } else if (child.lastModified() in 1L until cutoffMs) {
                child.delete()
            }
        }
    }

    private fun evictStaleNetworkState(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEvictAtMs < EVICT_DEBOUNCE_MS) return
        lastEvictAtMs = now
        networkGeneration++
        Log.d(TAG, "Evicting stale HTTP connections ($reason, gen=$networkGeneration)")
        netCleanupExecutor.execute {
            try {
                YouTubeSessionConfig.evictIdleConnections()
                ThumbnailLoader.evictIdleConnections()
                YouTubeJsSolver.evictIdleConnections()
                YouTubeSubtitleParser.evictIdleConnections()
                YouTubePoTokenProvider.invalidate()
                YouTubePlaybackService.onNetworkChanged()
            } catch (_: Throwable) {
            }
        }
    }

    private fun syncFloatingBubble(activity: Activity) {
        val allowed = !isExcludedFromFloatingBubble(activity) &&
            !(activity is MainActivity && activity.isMusicTabActive())
        if (!allowed) {
            floatingControllers[activity]?.detach()
            return
        }
        val controller = floatingControllers.getOrPut(activity) {
            FloatingAudioController(activity)
        }
        controller.attach()
    }

    private fun isExcludedFromFloatingBubble(activity: Activity): Boolean =
        activity is PlayerActivity ||
            activity is YouTubePlayerActivity ||
            activity is LoginActivity

    companion object {
        private const val TAG = "GrabAllApp"
        private const val FOREGROUND_EVICT_THRESHOLD_MS = 15_000L
        private const val EVICT_DEBOUNCE_MS = 1_500L

        @Volatile
        private var instance: GrabAllApp? = null

        @Volatile
        private var otherActivityCount: Int = 0

        /** Incremented whenever VPN/network switches or the app wakes after idle. */
        @Volatile
        var networkGeneration: Long = 0L
            private set

        fun hasOtherAliveActivity(): Boolean = otherActivityCount > 0

        fun appContextOrNull(): Context? = instance?.applicationContext

        /** Called by [MainActivity] when switching into or out of the Music tab. */
        fun refreshFloatingBubble(activity: Activity) {
            instance?.syncFloatingBubble(activity)
        }
    }
}
