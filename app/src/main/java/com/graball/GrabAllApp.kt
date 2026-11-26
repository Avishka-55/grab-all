package com.graball

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.graball.youtube.FloatingAudioController
import com.graball.youtube.YouTubePlayerActivity
import java.util.WeakHashMap

/**
 * Application entry point that attaches GrabAll's lightweight, zero-idle-CPU
 * [FloatingAudioController] across all non-player screens automatically (for
 * both YouTube audio-only playback and local songs).
 */
class GrabAllApp : Application() {

    private val floatingControllers = WeakHashMap<Activity, FloatingAudioController>()
    private val downloadBadges = WeakHashMap<Activity, NavHelper.DownloadBadgeController>()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is YouTubePlayerActivity) {
                    otherActivityCount++
                }
            }
            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                val badge = downloadBadges.getOrPut(activity) {
                    NavHelper.DownloadBadgeController(activity)
                }
                badge.attach()
                if (isExcludedFromFloatingBubble(activity)) return
                val controller = floatingControllers.getOrPut(activity) {
                    FloatingAudioController(activity)
                }
                controller.attach()
            }

            override fun onActivityPaused(activity: Activity) {
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

    private fun isExcludedFromFloatingBubble(activity: Activity): Boolean =
        activity is PlayerActivity ||
            activity is YouTubePlayerActivity ||
            activity is LoginActivity

    companion object {
        @Volatile
        private var otherActivityCount: Int = 0

        fun hasOtherAliveActivity(): Boolean = otherActivityCount > 0
    }
}
