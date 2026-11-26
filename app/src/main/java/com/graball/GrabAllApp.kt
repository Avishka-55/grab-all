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

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                if (isExcludedFromFloatingBubble(activity)) return
                val controller = floatingControllers.getOrPut(activity) {
                    FloatingAudioController(activity)
                }
                controller.attach()
            }

            override fun onActivityPaused(activity: Activity) {
                floatingControllers[activity]?.detach()
            }

            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                floatingControllers.remove(activity)?.detach()
            }
        })
    }

    private fun isExcludedFromFloatingBubble(activity: Activity): Boolean =
        activity is PlayerActivity ||
            activity is YouTubePlayerActivity ||
            activity is LoginActivity
}
