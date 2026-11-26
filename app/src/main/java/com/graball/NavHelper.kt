package com.graball

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsSeekBar
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.graball.engine.DownloadProgressBus
import kotlin.math.abs

/**
 * Smooth, zero-flicker bottom-navigation tab switcher & horizontal swipe
 * helper shared across the 4 main GrabAll sections:
 * Grab -> Music Player -> Downloads -> Settings.
 */
object NavHelper {

    const val FLOATING_AUDIO_TAG = "graball_floating_audio"

    private val MAIN_TABS: List<Class<out Activity>> = listOf(
        MainActivity::class.java,
        PlayerActivity::class.java,
        DownloadsActivity::class.java,
        SettingsActivity::class.java
    )

    fun switchTab(
        from: Activity,
        target: Class<out Activity>,
        slideDirection: Int = 0
    ) {
        if (from.javaClass == target) return
        val intent = Intent(from, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        from.startActivity(intent)
        @Suppress("DEPRECATION")
        when {
            slideDirection > 0 -> from.overridePendingTransition(
                R.anim.tab_slide_in_right,
                R.anim.tab_slide_out_left
            )
            slideDirection < 0 -> from.overridePendingTransition(
                R.anim.tab_slide_in_left,
                R.anim.tab_slide_out_right
            )
            else -> from.overridePendingTransition(R.anim.tab_enter, R.anim.tab_exit)
        }
    }

    /**
     * Lightweight horizontal swipe recognizer for moving between adjacent
     * bottom-nav tabs without interfering with vertical scroll, edge-back
     * gestures, seekbars, horizontal chip rows, text selection, or dragging
     * the floating audio bubble.
     */
    class TabSwipeHelper(
        private val activity: Activity,
        private val isEnabled: () -> Boolean = { true }
    ) {
        private val density = activity.resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        private val minSwipePx = 72f * density
        private val edgeIgnorePx = 24f * density

        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var tracking = false
        private var aborted = false

        /**
         * Inspects raw Activity touch events. Returns true only when a clear
         * horizontal tab swipe completed and triggered a tab switch.
         */
        fun onDispatchTouchEvent(ev: MotionEvent): Boolean {
            if (!isEnabled()) {
                tracking = false
                return false
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = false
                    aborted = false
                    if (ev.pointerCount > 1) return false
                    val screenW = activity.window.decorView.width
                    if (screenW > 0 && (ev.rawX < edgeIgnorePx || ev.rawX > screenW - edgeIgnorePx)) {
                        // Preserve Android system edge back gesture
                        return false
                    }
                    if (hitsIgnoredView(activity.window.decorView, ev.rawX, ev.rawY)) {
                        return false
                    }
                    downX = ev.rawX
                    downY = ev.rawY
                    downTime = ev.eventTime
                    tracking = true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    aborted = true
                    tracking = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking || aborted) return false
                    val dx = abs(ev.rawX - downX)
                    val dy = abs(ev.rawY - downY)
                    if (dy > touchSlop && dy > dx) {
                        aborted = true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!tracking || aborted) {
                        tracking = false
                        return false
                    }
                    tracking = false
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    val elapsed = ev.eventTime - downTime
                    if (elapsed in 1..650 &&
                        abs(dx) >= minSwipePx &&
                        abs(dx) > abs(dy) * 1.7f
                    ) {
                        val currentIdx = MAIN_TABS.indexOf(activity.javaClass)
                        if (currentIdx >= 0) {
                            val step = if (dx < 0f) 1 else -1
                            val targetIdx = currentIdx + step
                            if (targetIdx in MAIN_TABS.indices) {
                                switchTab(activity, MAIN_TABS[targetIdx], slideDirection = step)
                                return true
                            }
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    aborted = false
                }
            }
            return false
        }

        private fun hitsIgnoredView(root: View, rawX: Float, rawY: Float): Boolean {
            if (root.visibility != View.VISIBLE) return false
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            val x = loc[0]
            val y = loc[1]
            if (rawX < x || rawX > x + root.width || rawY < y || rawY > y + root.height) {
                return false
            }
            if (root.tag == FLOATING_AUDIO_TAG ||
                root is AbsSeekBar ||
                root is FastScrollerView ||
                root is EditText
            ) {
                return true
            }
            if (root is HorizontalScrollView &&
                (root.canScrollHorizontally(-1) || root.canScrollHorizontally(1))
            ) {
                return true
            }
            if (root is ViewGroup) {
                for (i in root.childCount - 1 downTo 0) {
                    if (hitsIgnoredView(root.getChildAt(i), rawX, rawY)) {
                        return true
                    }
                }
            }
            return false
        }
    }

    /**
     * Live active-download badge overlay attached to the bottom-nav `navDownloads`
     * tab across all screens via [GrabAllApp]. Zero XML duplication and zero idle cost.
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
                        (44 * dp).toInt(),
                        (26 * dp).toInt()
                    )
                }
                firstChild.layoutParams = FrameLayout.LayoutParams(
                    (24 * dp).toInt(),
                    (24 * dp).toInt(),
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
