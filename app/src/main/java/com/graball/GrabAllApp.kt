package com.graball

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.graball.youtube.FloatingAudioController
import com.graball.youtube.YouTubePlayerActivity
import java.util.WeakHashMap

/**
 * Application entry point that attaches GrabAll's lightweight, zero-idle-CPU
 * [FloatingAudioController] and live download badge across screens automatically.
 */
class GrabAllApp : Application() {

    private val floatingControllers = WeakHashMap<Activity, FloatingAudioController>()
    private val downloadBadges = WeakHashMap<Activity, NavHelper.DownloadBadgeController>()

    override fun onCreate() {
        super.onCreate()
        instance = this
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
                syncFloatingBubble(activity)
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
        @Volatile
        private var instance: GrabAllApp? = null

        @Volatile
        private var otherActivityCount: Int = 0

        fun hasOtherAliveActivity(): Boolean = otherActivityCount > 0

        /** Called by [MainActivity] when switching into or out of the Music tab. */
        fun refreshFloatingBubble(activity: Activity) {
            instance?.syncFloatingBubble(activity)
        }
    }
}
