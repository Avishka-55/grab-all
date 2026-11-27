package com.graball

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import com.graball.engine.AppUpdateChecker
import com.graball.engine.YtDlpUpdater

/**
 * Single-Activity shell hosting Grab, Music, Downloads, and Settings as 4 cached
 * Fragments above ONE rock-solid pinned Material 3 bottom navigation bar.
 *
 * Switching tabs runs a crisp Material 3 Fade-Through (subtle zoom + fade) on the
 * content above the pinned bar plus an animated orange pill & icon tap bounce —
 * with zero Activity IPC overhead and zero horizontal swipe tab switching.
 */
class MainActivity : AppCompatActivity() {

    interface TabFragment {
        fun onTabSelected() {}
        fun onTabUnselected() {}
        fun handleBackPressed(): Boolean = false
    }

    private data class NavSlot(
        val index: Int,
        val container: LinearLayout,
        val pill: View,
        val icon: ImageView,
        val label: TextView
    )

    private lateinit var bottomNav: LinearLayout
    private lateinit var bottomNavDivider: View
    private lateinit var navSettingsDot: View
    private lateinit var navSlots: List<NavSlot>

    private var activeTab = TAB_GRAB
    private var navAnimGeneration = 0

    private val fadeThroughIn = PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f)
    private val fadeOutInterp = AccelerateInterpolator(1.2f)
    private val pillExpandInterp = OvershootInterpolator(1.1f)
    private val pillCollapseInterp = DecelerateInterpolator()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        bottomNav = findViewById(R.id.bottomNav)
        bottomNavDivider = findViewById(R.id.bottomNavDivider)
        navSettingsDot = findViewById(R.id.navSettingsDot)
        navSlots = listOf(
            NavSlot(
                TAB_GRAB,
                findViewById(R.id.navGrab),
                findViewById(R.id.navGrabPill),
                findViewById(R.id.navGrabIcon),
                findViewById(R.id.navGrabLabel)
            ),
            NavSlot(
                TAB_MUSIC,
                findViewById(R.id.navPlayer),
                findViewById(R.id.navPlayerPill),
                findViewById(R.id.navPlayerIcon),
                findViewById(R.id.navPlayerLabel)
            ),
            NavSlot(
                TAB_DOWNLOADS,
                findViewById(R.id.navDownloads),
                findViewById(R.id.navDownloadsPill),
                findViewById(R.id.navDownloadsIcon),
                findViewById(R.id.navDownloadsLabel)
            ),
            NavSlot(
                TAB_SETTINGS,
                findViewById(R.id.navSettings),
                findViewById(R.id.navSettingsPill),
                findViewById(R.id.navSettingsIcon),
                findViewById(R.id.navSettingsLabel)
            )
        )

        navSlots.forEach { slot ->
            slot.container.setOnClickListener {
                selectBottomTab(slot.index, animate = true)
            }
        }

        val initialTab = savedInstanceState?.getInt(STATE_ACTIVE_TAB, TAB_GRAB)
            ?: resolveTabFromIntent(intent)
        activeTab = initialTab
        ensureTabFragment(initialTab, showImmediately = true)
        renderBottomNavState(initialTab, animate = false)
        GrabAllApp.refreshFloatingBubble(this)

        handleIncomingIntent(intent, isInitialCreate = true)
        refreshSettingsUpdateDot()
        maybeCheckAppUpdate()
        scheduleBackgroundYtDlpAutoUpdate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_ACTIVE_TAB, activeTab)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent, isInitialCreate = false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && activeTab == TAB_GRAB) {
            (findTabFragment(TAB_GRAB) as? GrabFragment)?.onHostWindowFocusGained()
        }
    }

    fun isMusicTabActive(): Boolean = activeTab == TAB_MUSIC

    /** Show or hide the pinned bottom bar (used when Music Now Playing / Lyrics goes full-screen). */
    fun setBottomNavVisible(visible: Boolean) {
        if (!::bottomNav.isInitialized) return
        val vis = if (visible) View.VISIBLE else View.GONE
        bottomNav.visibility = vis
        bottomNavDivider.visibility = vis
    }

    /** Switch straight to the Music tab and deliver an audio/folder/Now-Playing intent. */
    fun openMusicWithIntent(musicIntent: Intent) {
        selectBottomTab(TAB_MUSIC, animate = true)
        (findTabFragment(TAB_MUSIC) as? MusicFragment)?.handleMusicIntent(musicIntent)
    }

    /** Switch to one of the 4 bottom-nav tabs with Material 3 Fade-Through + pill bounce. */
    fun selectBottomTab(targetTab: Int, animate: Boolean = true) {
        if (targetTab !in TAB_GRAB..TAB_SETTINGS) return
        if (targetTab == activeTab && findTabFragment(targetTab)?.isVisible == true) {
            // Subtle tap feedback if tapping already-active tab
            if (animate) animateIconBounce(navSlots[targetTab].icon)
            return
        }

        val prevTab = activeTab
        val oldFrag = findTabFragment(prevTab)
        val newFrag = ensureTabFragment(targetTab, showImmediately = false)
        activeTab = targetTab
        setBottomNavVisible(true)
        renderBottomNavState(targetTab, animate = animate)
        GrabAllApp.refreshFloatingBubble(this)

        val gen = ++navAnimGeneration
        (oldFrag as? TabFragment)?.onTabUnselected()

        val tx = supportFragmentManager.beginTransaction()
        if (newFrag.isHidden) tx.show(newFrag)
        if (!animate && oldFrag != null && oldFrag !== newFrag && !oldFrag.isHidden) {
            tx.hide(oldFrag)
        }
        tx.commitNowAllowingStateLoss()

        if (!animate || oldFrag == null || oldFrag === newFrag) {
            newFrag.view?.let { v ->
                v.animate().cancel()
                v.alpha = 1f
                v.scaleX = 1f
                v.scaleY = 1f
                v.translationY = 0f
            }
            (newFrag as? TabFragment)?.onTabSelected()
            return
        }

        val dp = resources.displayMetrics.density
        // Fade out outgoing tab quickly (90 ms) then hide cleanly
        oldFrag.view?.let { oldView ->
            oldView.animate().cancel()
            oldView.animate()
                .alpha(0f)
                .setDuration(90L)
                .setInterpolator(fadeOutInterp)
                .withEndAction {
                    if (isFinishing || isDestroyed) return@withEndAction
                    if (activeTab != prevTab && oldFrag.isAdded && !oldFrag.isHidden) {
                        supportFragmentManager.beginTransaction()
                            .hide(oldFrag)
                            .commitNowAllowingStateLoss()
                    }
                    oldView.alpha = 1f
                }
                .start()
        }

        // Material 3 Fade-Through enter on incoming tab: subtle 96.5% -> 100% zoom + 6dp rise + fade
        newFrag.view?.let { newView ->
            newView.animate().cancel()
            newView.alpha = 0f
            newView.scaleX = 0.965f
            newView.scaleY = 0.965f
            newView.translationY = 6f * dp
            newView.bringToFront()
            newView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(175L)
                .setInterpolator(fadeThroughIn)
                .withEndAction {
                    if (gen != navAnimGeneration) return@withEndAction
                    newView.alpha = 1f
                    newView.scaleX = 1f
                    newView.scaleY = 1f
                    newView.translationY = 0f
                }
                .start()
        }

        (newFrag as? TabFragment)?.onTabSelected()
    }

    private fun renderBottomNavState(selectedIndex: Int, animate: Boolean) {
        val orange = ContextCompat.getColor(this, R.color.graball_orange)
        val muted = ContextCompat.getColor(this, R.color.graball_tab_text)
        navSlots.forEach { slot ->
            val selected = slot.index == selectedIndex
            ImageViewCompat.setImageTintList(
                slot.icon,
                ColorStateList.valueOf(if (selected) orange else muted)
            )
            slot.label.setTextColor(if (selected) orange else muted)
            slot.label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)

            slot.pill.animate().cancel()
            if (selected) {
                if (animate) {
                    slot.pill.scaleX = 0.45f
                    slot.pill.alpha = 0f
                    slot.pill.animate()
                        .scaleX(1f)
                        .alpha(1f)
                        .setDuration(200L)
                        .setInterpolator(pillExpandInterp)
                        .start()
                    animateIconBounce(slot.icon)
                } else {
                    slot.pill.scaleX = 1f
                    slot.pill.alpha = 1f
                }
            } else {
                if (animate && slot.pill.alpha > 0f) {
                    slot.pill.animate()
                        .scaleX(0.6f)
                        .alpha(0f)
                        .setDuration(110L)
                        .setInterpolator(pillCollapseInterp)
                        .start()
                } else {
                    slot.pill.scaleX = 1f
                    slot.pill.alpha = 0f
                }
            }
        }
    }

    private fun animateIconBounce(icon: ImageView) {
        icon.animate().cancel()
        icon.scaleX = 0.88f
        icon.scaleY = 0.88f
        icon.animate()
            .scaleX(1.06f)
            .scaleY(1.06f)
            .setDuration(105L)
            .withEndAction {
                icon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(90L)
                    .start()
            }
            .start()
    }

    private fun tabTag(tabIndex: Int): String = "tab_$tabIndex"

    private fun findTabFragment(tabIndex: Int): Fragment? =
        supportFragmentManager.findFragmentByTag(tabTag(tabIndex))

    private fun ensureTabFragment(tabIndex: Int, showImmediately: Boolean): Fragment {
        findTabFragment(tabIndex)?.let { existing ->
            if (showImmediately && existing.isHidden) {
                supportFragmentManager.beginTransaction()
                    .show(existing)
                    .commitNowAllowingStateLoss()
            }
            return existing
        }
        val created: Fragment = when (tabIndex) {
            TAB_MUSIC -> MusicFragment()
            TAB_DOWNLOADS -> DownloadsFragment()
            TAB_SETTINGS -> SettingsFragment()
            else -> GrabFragment()
        }
        val tx = supportFragmentManager.beginTransaction()
            .add(R.id.tabContainer, created, tabTag(tabIndex))
        if (!showImmediately) {
            tx.hide(created)
        }
        tx.commitNowAllowingStateLoss()
        return created
    }

    private fun resolveTabFromIntent(intent: Intent?): Int {
        if (intent == null) return TAB_GRAB
        if (intent.action == Intent.ACTION_SEND) return TAB_GRAB
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) return TAB_MUSIC
        if (intent.getBooleanExtra("open_now_playing", false) ||
            !intent.getStringExtra(PlayerActivity.EXTRA_OPEN_FOLDER_PATH).isNullOrBlank() ||
            !intent.getStringExtra(PlayerActivity.EXTRA_OPEN_FOLDER_NAME).isNullOrBlank()
        ) {
            return TAB_MUSIC
        }
        return intent.getIntExtra(EXTRA_TAB, TAB_GRAB).coerceIn(TAB_GRAB, TAB_SETTINGS)
    }

    private fun handleIncomingIntent(intent: Intent?, isInitialCreate: Boolean) {
        if (intent == null) return
        val hasExplicitTab = intent.hasExtra(EXTRA_TAB)
        val isShare = intent.action == Intent.ACTION_SEND
        val isMusicPayload = (intent.action == Intent.ACTION_VIEW && intent.data != null) ||
            intent.getBooleanExtra("open_now_playing", false) ||
            !intent.getStringExtra(PlayerActivity.EXTRA_OPEN_FOLDER_PATH).isNullOrBlank() ||
            !intent.getStringExtra(PlayerActivity.EXTRA_OPEN_FOLDER_NAME).isNullOrBlank()

        if (isShare) {
            selectBottomTab(TAB_GRAB, animate = !isInitialCreate)
            (findTabFragment(TAB_GRAB) as? GrabFragment)?.handleShareIntent(intent)
            return
        }
        if (isMusicPayload) {
            selectBottomTab(TAB_MUSIC, animate = !isInitialCreate)
            (findTabFragment(TAB_MUSIC) as? MusicFragment)?.handleMusicIntent(intent)
            return
        }
        if (hasExplicitTab) {
            val target = intent.getIntExtra(EXTRA_TAB, TAB_GRAB).coerceIn(TAB_GRAB, TAB_SETTINGS)
            selectBottomTab(target, animate = !isInitialCreate)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATE, false)) {
            intent.removeExtra(EXTRA_OPEN_UPDATE)
            selectBottomTab(TAB_SETTINGS, animate = !isInitialCreate)
            Handler(Looper.getMainLooper()).post {
                if (!isFinishing && !isDestroyed) {
                    (findTabFragment(TAB_SETTINGS) as? SettingsFragment)?.openAvailableUpdateSheet()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val current = findTabFragment(activeTab) as? TabFragment
        if (current?.handleBackPressed() == true) {
            return
        }
        if (activeTab != TAB_GRAB) {
            selectBottomTab(TAB_GRAB, animate = true)
            return
        }
        super.onBackPressed()
    }

    fun refreshSettingsUpdateDot() {
        if (!::navSettingsDot.isInitialized) return
        val hasUpdate = AppUpdateChecker.hasCachedUpdate(this)
        navSettingsDot.visibility = if (hasUpdate) View.VISIBLE else View.GONE
    }

    private fun maybeCheckAppUpdate() {
        if (!AppUpdateChecker.shouldAutoCheck(this)) return
        Thread {
            val release = AppUpdateChecker.check(applicationContext, markChecked = true)
            if (release != null && release.isNewer) {
                AppUpdateChecker.notifyOnceIfEligible(applicationContext, release)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshSettingsUpdateDot()
                (findTabFragment(TAB_SETTINGS) as? SettingsFragment)?.refreshUpdateRow()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun scheduleBackgroundYtDlpAutoUpdate() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            Thread {
                YtDlpUpdater.maybeAutoUpdateInBackground(applicationContext)
            }.apply { isDaemon = true }.start()
        }, 3500L)
    }

    companion object {
        const val TAB_GRAB = 0
        const val TAB_MUSIC = 1
        const val TAB_DOWNLOADS = 2
        const val TAB_SETTINGS = 3
        const val EXTRA_TAB = "graball_tab"
        const val EXTRA_OPEN_UPDATE = "graball_open_update"
        private const val STATE_ACTIVE_TAB = "state_active_tab"

        fun createTabIntent(context: Context, tabIndex: Int): Intent {
            return Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_TAB, tabIndex.coerceIn(TAB_GRAB, TAB_SETTINGS))
            }
        }

        fun createUpdateIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_TAB, TAB_SETTINGS)
                putExtra(EXTRA_OPEN_UPDATE, true)
            }
        }
    }
}
