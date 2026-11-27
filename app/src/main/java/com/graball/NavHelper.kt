package com.graball

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.graball.engine.DownloadProgressBus

/**
 * Shared bottom-navigation routing & live active-download badge overlay across
 * GrabAll's single-activity tab shell (`MainActivity`) and secondary screens
 * (like `StatusActivity`).
 */
object NavHelper {

    const val FLOATING_AUDIO_TAG = "graball_floating_audio"

    fun switchTab(
        from: Activity,
        target: Class<out Activity>
    ) {
        val tabIndex = when (target) {
            PlayerActivity::class.java -> MainActivity.TAB_MUSIC
            DownloadsActivity::class.java -> MainActivity.TAB_DOWNLOADS
            SettingsActivity::class.java -> MainActivity.TAB_SETTINGS
            else -> MainActivity.TAB_GRAB
        }
        if (from is MainActivity) {
            from.selectBottomTab(tabIndex, animate = true)
            return
        }
        val intent = MainActivity.createTabIntent(from, tabIndex)
        from.startActivity(intent)
        @Suppress("DEPRECATION")
        from.overridePendingTransition(R.anim.tab_enter, R.anim.tab_exit)
    }

    /**
     * Live active-download badge overlay attached to the bottom-nav `navDownloads`
     * tab across screens via [GrabAllApp]. Zero XML duplication and zero idle cost.
     */
    class DownloadBadgeController(private val activity: Activity) {
        private var badgeView: TextView? = null
        private var attached = false

        private val listener: (Int) -> Unit = { count ->
            renderCount(count)
        }

        fun attach() {
            if (attached) {
                renderCount(DownloadProgressBus.activeCount())
                return
            }
            ensureBadgeView() ?: return
            attached = true
            DownloadProgressBus.subscribeActiveCount(listener)
        }

        fun detach() {
            if (!attached) return
            attached = false
            DownloadProgressBus.unsubscribeActiveCount(listener)
        }

        private fun ensureBadgeView(): TextView? {
            badgeView?.let { return it }
            val navTab = activity.findViewById<LinearLayout>(R.id.navDownloads) ?: return null
            val firstChild = navTab.getChildAt(0) ?: return null
            val existing = navTab.findViewWithTag<TextView>("graball_dl_badge")
            if (existing != null) {
                badgeView = existing
                return existing
            }
            val dp = activity.resources.displayMetrics.density
            val iconHolder: FrameLayout = if (firstChild is FrameLayout) {
                firstChild
            } else if (firstChild is ImageView) {
                navTab.removeViewAt(0)
                val wrapper = FrameLayout(activity).apply {
                    clipChildren = false
                    clipToPadding = false
                    layoutParams = LinearLayout.LayoutParams(
                        (56 * dp).toInt(),
                        (30 * dp).toInt()
                    )
                }
                firstChild.layoutParams = FrameLayout.LayoutParams(
                    (22 * dp).toInt(),
                    (22 * dp).toInt(),
                    Gravity.CENTER
                )
                wrapper.addView(firstChild)
                navTab.addView(wrapper, 0)
                wrapper
            } else {
                return null
            }

            val badge = TextView(activity).apply {
                tag = "graball_dl_badge"
                textSize = 9.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                minWidth = (16 * dp).toInt()
                setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                background = GradientDrawable().apply {
                    cornerRadius = 9 * dp
                    setColor(ContextCompat.getColor(activity, R.color.graball_orange))
                }
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (16 * dp).toInt(),
                    Gravity.TOP or Gravity.END
                )
            }
            iconHolder.addView(badge)
            badgeView = badge
            return badge
        }

        private fun renderCount(count: Int) {
            val badge = badgeView ?: return
            if (count > 0) {
                badge.text = if (count > 9) "9+" else count.toString()
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }
        }
    }
}
