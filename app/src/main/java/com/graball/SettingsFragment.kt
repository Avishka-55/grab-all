package com.graball

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.graball.engine.AppUpdateChecker
import com.graball.engine.DownloadPrefs
import com.graball.engine.WebViewCookieSync
import com.graball.engine.YtDlpUpdater
import com.graball.youtube.ThumbnailLoader
import com.graball.youtube.YouTubeInnerTube
import com.graball.youtube.YouTubePlaybackService
import com.graball.youtube.YouTubeStore
import java.io.File
import java.util.concurrent.Executors

/**
 * Compact Material 3 Settings tab hosted inside [MainActivity]'s single-activity shell.
 * Runs cache-size disk walks, cookie checks, and avatar lookups off the UI thread so
 * tab switches stay instant (<5 ms).
 */
class SettingsFragment : Fragment(), MainActivity.TabFragment {

    private lateinit var accountAvatarBox: FrameLayout
    private lateinit var accountAvatar: ImageView
    private lateinit var cookiesStatus: TextView
    private lateinit var loginBtn: TextView
    private lateinit var logoutBtn: TextView
    private lateinit var cacheSizeText: TextView
    private lateinit var ytDlpVersionText: TextView
    private lateinit var appVersionText: TextView
    private lateinit var appUpdateStatusText: TextView
    private lateinit var appUpdateBtn: TextView

    @Volatile
    private var cachedSignedIn = false
    private var loadedAvatarUrl: String? = null
    private val settingsIo = Executors.newSingleThreadExecutor()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.activity_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        accountAvatarBox = view.findViewById(R.id.settingsAccountAvatarBox)
        accountAvatar = view.findViewById(R.id.settingsAccountAvatar)
        cookiesStatus = view.findViewById(R.id.settingsCookiesStatus)
        loginBtn = view.findViewById(R.id.settingsLoginButton)
        logoutBtn = view.findViewById(R.id.settingsLogoutButton)
        cacheSizeText = view.findViewById(R.id.settingsCacheSize)
        ytDlpVersionText = view.findViewById(R.id.settingsYtDlpVersion)
        appVersionText = view.findViewById(R.id.settingsAppVersion)
        appUpdateStatusText = view.findViewById(R.id.settingsAppUpdateStatus)
        appUpdateBtn = view.findViewById(R.id.settingsCheckAppUpdate)

        accountAvatar.clipToOutline = true
        accountAvatar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }

        loginBtn.setOnClickListener {
            val c = context ?: return@setOnClickListener
            startActivity(
                if (cachedSignedIn) {
                    LoginActivity.createSwitchAccountIntent(c)
                } else {
                    LoginActivity.createSignInIntent(c)
                }
            )
        }
        logoutBtn.setOnClickListener {
            context?.let { startActivity(LoginActivity.createLogoutIntent(it)) }
        }

        // ---- Playback Switches ----
        val screenOffSwitch = view.findViewById<SwitchCompat>(R.id.settingsScreenOffAudio)
        styleSwitch(ctx, screenOffSwitch)
        screenOffSwitch.isChecked = YouTubeStore.isScreenOffAudioOnly(ctx)
        screenOffSwitch.setOnCheckedChangeListener { _, checked ->
            context?.let { YouTubeStore.setScreenOffAudioOnly(it, checked) }
        }
        view.findViewById<View>(R.id.rowScreenOffAudio).setOnClickListener {
            screenOffSwitch.toggle()
        }

        val sbSwitch = view.findViewById<SwitchCompat>(R.id.settingsSponsorBlock)
        styleSwitch(ctx, sbSwitch)
        sbSwitch.isChecked = YouTubeStore.isSponsorBlockEnabled(ctx)
        sbSwitch.setOnCheckedChangeListener { _, checked ->
            context?.let { YouTubeStore.setSponsorBlockEnabled(it, checked) }
        }
        view.findViewById<View>(R.id.rowSponsorBlock).setOnClickListener {
            sbSwitch.toggle()
        }

        val prebufferSwitch = view.findViewById<SwitchCompat>(R.id.settingsAudioPrebuffer)
        styleSwitch(ctx, prebufferSwitch)
        prebufferSwitch.isChecked = YouTubeStore.isAudioPrebufferEnabled(ctx)
        prebufferSwitch.setOnCheckedChangeListener { _, checked ->
            context?.let { YouTubeStore.setAudioPrebufferEnabled(it, checked) }
        }
        view.findViewById<View>(R.id.rowAudioPrebuffer).setOnClickListener {
            prebufferSwitch.toggle()
        }

        // ---- Parallel Downloads Selector ----
        val concStatusText = view.findViewById<TextView>(R.id.settingsConcurrencyStatus)
        val concChips = listOf(
            0 to view.findViewById<TextView>(R.id.chipConcAuto),
            1 to view.findViewById<TextView>(R.id.chipConc1),
            2 to view.findViewById<TextView>(R.id.chipConc2),
            3 to view.findViewById<TextView>(R.id.chipConc3),
            4 to view.findViewById<TextView>(R.id.chipConc4),
            6 to view.findViewById<TextView>(R.id.chipConc6)
        )
        fun refreshConcurrencyChips() {
            val c = context ?: return
            val selected = DownloadPrefs.getConcurrentDownloads(c)
            val resolved = DownloadPrefs.resolvedConcurrency(c)
            concChips.forEach { (value, chip) ->
                val isSel = value == selected
                chip.setBackgroundResource(
                    if (isSel) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg
                )
                chip.setTextColor(
                    ContextCompat.getColor(
                        c,
                        if (isSel) R.color.graball_text else R.color.graball_subtext
                    )
                )
            }
            concStatusText.text = when (selected) {
                0 -> "Auto · ${resolved}×"
                1 -> "1× (Low CPU)"
                else -> "${selected}× parallel"
            }
        }
        concChips.forEach { (value, chip) ->
            chip.setOnClickListener {
                context?.let { DownloadPrefs.setConcurrentDownloads(it, value) }
                refreshConcurrencyChips()
            }
        }
        refreshConcurrencyChips()

        // ---- Audio Download Format (M4A vs MP3) ----
        val chipMp3 = view.findViewById<TextView>(R.id.chipAudioMp3)
        val chipM4a = view.findViewById<TextView>(R.id.chipAudioM4a)
        val audioFmtStatus = view.findViewById<TextView>(R.id.settingsAudioFormatStatus)
        fun refreshAudioFormatChips() {
            val c = context ?: return
            val isM4a = DownloadPrefs.isFastM4a(c)
            chipMp3.setBackgroundResource(if (!isM4a) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
            chipMp3.setTextColor(ContextCompat.getColor(c, if (!isM4a) R.color.graball_text else R.color.graball_subtext))
            chipM4a.setBackgroundResource(if (isM4a) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
            chipM4a.setTextColor(ContextCompat.getColor(c, if (isM4a) R.color.graball_text else R.color.graball_subtext))
            audioFmtStatus.text = if (isM4a) {
                "M4A skips re-encoding (~10× faster)"
            } else {
                "MP3 works everywhere (ID3 + cover)"
            }
        }
        chipMp3.setOnClickListener {
            context?.let { DownloadPrefs.setAudioFormat(it, DownloadPrefs.AUDIO_FORMAT_MP3) }
            refreshAudioFormatChips()
        }
        chipM4a.setOnClickListener {
            context?.let { DownloadPrefs.setAudioFormat(it, DownloadPrefs.AUDIO_FORMAT_M4A) }
            refreshAudioFormatChips()
        }
        refreshAudioFormatChips()

        // ---- Cache Clear ----
        view.findViewById<TextView>(R.id.settingsClearCache).setOnClickListener {
            val c = context ?: return@setOnClickListener
            ThumbnailLoader.clearAll(c)
            YouTubePlaybackService.clearMediaCache(c)
            refreshCacheSizeAsync()
            Toast.makeText(c, "Cache cleared", Toast.LENGTH_SHORT).show()
        }

        // ---- yt-dlp Engine Bottom Sheet ----
        view.findViewById<View>(R.id.rowYtDlpEngine).setOnClickListener {
            showYtDlpSheet()
        }

        // ---- App Update Row + Quiet Notification Switch ----
        appVersionText.text = "GrabAll v${AppUpdateChecker.currentVersionName(ctx)}"
        appUpdateBtn.setOnClickListener { onAppUpdateActionClicked() }
        view.findViewById<View>(R.id.rowAppUpdate).setOnClickListener { onAppUpdateActionClicked() }

        val notifySwitch = view.findViewById<SwitchCompat>(R.id.settingsUpdateNotify)
        styleSwitch(ctx, notifySwitch)
        notifySwitch.isChecked = AppUpdateChecker.isNotifyEnabled(ctx)
        notifySwitch.setOnCheckedChangeListener { _, checked ->
            context?.let { AppUpdateChecker.setNotifyEnabled(it, checked) }
        }
        view.findViewById<View>(R.id.rowUpdateNotify).setOnClickListener {
            notifySwitch.toggle()
        }

        refreshAllAsync()
    }

    override fun onResume() {
        super.onResume()
        refreshAllAsync()
    }

    override fun onTabSelected() {
        refreshAllAsync()
    }

    override fun onDestroy() {
        settingsIo.shutdownNow()
        super.onDestroy()
    }

    fun refreshUpdateRow() {
        val ctx = context ?: return
        if (!::appUpdateStatusText.isInitialized) return
        val cached = AppUpdateChecker.getCachedAvailableRelease(ctx)
        if (cached != null) {
            appUpdateStatusText.text = "v${cached.version} available — tap to view"
            appUpdateStatusText.setTextColor(ContextCompat.getColor(ctx, R.color.graball_orange))
            appUpdateBtn.text = "Update"
            appUpdateBtn.setBackgroundResource(R.drawable.btn_orange)
            appUpdateBtn.setTextColor(ContextCompat.getColor(ctx, R.color.graball_text))
        } else {
            appUpdateStatusText.text = "Tap Check to look for new releases"
            appUpdateStatusText.setTextColor(ContextCompat.getColor(ctx, R.color.graball_subtext))
            appUpdateBtn.text = "Check"
            appUpdateBtn.setBackgroundResource(R.drawable.yt_chip_bg)
            appUpdateBtn.setTextColor(ContextCompat.getColor(ctx, R.color.graball_text))
        }
        (activity as? MainActivity)?.refreshSettingsUpdateDot()
    }

    /** Called by MainActivity when the user taps the silent update notification. */
    fun openAvailableUpdateSheet() {
        val ctx = context ?: return
        val cached = AppUpdateChecker.getCachedAvailableRelease(ctx)
        if (cached != null) {
            showAppUpdateSheet(cached)
        } else {
            onAppUpdateActionClicked()
        }
    }

    private fun onAppUpdateActionClicked() {
        val ctx = context ?: return
        val cached = AppUpdateChecker.getCachedAvailableRelease(ctx)
        if (cached != null) {
            showAppUpdateSheet(cached)
            return
        }
        val appCtx = ctx.applicationContext
        appUpdateBtn.isEnabled = false
        appUpdateStatusText.setTextColor(ContextCompat.getColor(ctx, R.color.graball_orange))
        appUpdateStatusText.text = "Checking GitHub releases…"
        Thread {
            val release = AppUpdateChecker.check(appCtx, markChecked = true)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val c = context ?: return@runOnUiThread
                appUpdateBtn.isEnabled = true
                (activity as? MainActivity)?.refreshSettingsUpdateDot()
                if (release == null) {
                    appUpdateStatusText.setTextColor(ContextCompat.getColor(c, R.color.graball_subtext))
                    appUpdateStatusText.text = "Could not check — verify internet"
                } else if (release.isNewer) {
                    refreshUpdateRow()
                    showAppUpdateSheet(release)
                } else {
                    appUpdateStatusText.setTextColor(ContextCompat.getColor(c, R.color.graball_subtext))
                    appUpdateStatusText.text = "Up to date (v${release.currentVersion}) ✓"
                }
            }
        }.start()
    }

    private fun refreshAllAsync() {
        if (!::cookiesStatus.isInitialized) return
        refreshYtDlpSummary()
        refreshUpdateRow()
        refreshSessionAndCacheAsync()
    }

    private fun refreshSessionAndCacheAsync() {
        val appCtx = context?.applicationContext ?: return
        settingsIo.execute {
            val signedIn = WebViewCookieSync.hasLoggedInYouTubeSession() ||
                WebViewCookieSync.buildYouTubeAuthHeaders(appCtx) != null
            cachedSignedIn = signedIn
            val token = if (signedIn) WebViewCookieSync.getLoginInfoToken().orEmpty() else ""
            val fp = "$signedIn:$token"
            var avatarUrl = if (signedIn) YouTubeStore.getCachedAccountAvatar(appCtx, fp) else null
            if (signedIn && avatarUrl.isNullOrBlank()) {
                val fetched = YouTubeInnerTube.fetchSignedInAccountAvatar(appCtx)
                if (!fetched.isNullOrBlank()) {
                    YouTubeStore.saveCachedAccountAvatar(appCtx, fp, fetched)
                    avatarUrl = fetched
                }
            }
            val bytes = dirSize(File(appCtx.cacheDir, "yt_thumbs")) +
                dirSize(File(appCtx.cacheDir, "yt_media_cache"))
            val mb = bytes / (1024.0 * 1024.0)
            val cacheLabel = String.format("%.1f MB used", mb)

            activity?.runOnUiThread {
                if (!isAdded || !::cookiesStatus.isInitialized) return@runOnUiThread
                val c = context ?: return@runOnUiThread
                if (signedIn) {
                    cookiesStatus.text = "Signed in · 1080p & personalized feed"
                    cookiesStatus.setTextColor(ContextCompat.getColor(c, R.color.graball_orange))
                    loginBtn.text = "Switch"
                    loginBtn.setBackgroundResource(R.drawable.yt_chip_bg)
                    logoutBtn.visibility = View.VISIBLE
                    accountAvatarBox.setBackgroundResource(R.drawable.yt_avatar_ring)
                    if (!avatarUrl.isNullOrBlank()) {
                        if (loadedAvatarUrl != avatarUrl) {
                            loadedAvatarUrl = avatarUrl
                            ThumbnailLoader.load(avatarUrl, accountAvatar, targetWidthPx = 128)
                        }
                    } else {
                        accountAvatar.setImageResource(R.drawable.ic_account_circle)
                    }
                } else {
                    loadedAvatarUrl = null
                    cookiesStatus.text = "Not signed in"
                    cookiesStatus.setTextColor(ContextCompat.getColor(c, R.color.graball_subtext))
                    loginBtn.text = "Sign in"
                    loginBtn.setBackgroundResource(R.drawable.btn_orange)
                    logoutBtn.visibility = View.GONE
                    accountAvatarBox.setBackgroundResource(R.drawable.yt_chip_bg)
                    accountAvatar.setImageResource(R.drawable.ic_account_circle)
                }
                cacheSizeText.text = cacheLabel
            }
        }
    }

    private fun refreshCacheSizeAsync() {
        val appCtx = context?.applicationContext ?: return
        settingsIo.execute {
            val bytes = dirSize(File(appCtx.cacheDir, "yt_thumbs")) +
                dirSize(File(appCtx.cacheDir, "yt_media_cache"))
            val mb = bytes / (1024.0 * 1024.0)
            val cacheLabel = String.format("%.1f MB used", mb)
            activity?.runOnUiThread {
                if (isAdded && ::cacheSizeText.isInitialized) {
                    cacheSizeText.text = cacheLabel
                }
            }
        }
    }

    private fun refreshYtDlpSummary() {
        val ctx = context ?: return
        val ver = YtDlpUpdater.getCachedVersion(ctx)
        val autoTag = if (YtDlpUpdater.isAutoUpdateEnabled(ctx)) "Auto Wi-Fi" else "Manual"
        val chanTag = if (YtDlpUpdater.getChannel(ctx) == YtDlpUpdater.CHANNEL_NIGHTLY) " · Nightly" else ""
        ytDlpVersionText.text = "$ver · $autoTag$chanTag"
    }

    // ---- Sleek Dark yt-dlp Engine Bottom Sheet ----

    private fun showYtDlpSheet() {
        val ctx = context ?: return
        val dp = ctx.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()

        val dialog = DarkBottomSheet.createDialog(ctx)
        val root = DarkBottomSheet.createContainer(ctx, horizontalPadDp = 18)

        // Header
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = d(12) }
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(ctx).apply {
            text = "yt-dlp Engine"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        val verLabel = TextView(ctx).apply {
            text = "Active: ${YtDlpUpdater.getCachedVersion(ctx)}"
            textSize = 12.5f
            setTextColor(Color.parseColor("#B0B4CC"))
        }
        titleCol.addView(verLabel)
        headerRow.addView(titleCol)

        val closeBtn = TextView(ctx).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Color.parseColor("#B0B4CC"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#24263B"))
                cornerRadius = 16 * dp
            }
            layoutParams = LinearLayout.LayoutParams(d(32), d(32))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(closeBtn)
        root.addView(headerRow)

        // Switches Card
        val switchCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#202236"))
                cornerRadius = 14 * dp
            }
            setPadding(d(14), d(4), d(14), d(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = d(12) }
        }

        val autoSwitch = SwitchCompat(ctx).apply {
            styleSwitch(ctx, this)
            isChecked = YtDlpUpdater.isAutoUpdateEnabled(ctx)
            setOnCheckedChangeListener { _, checked ->
                YtDlpUpdater.setAutoUpdateEnabled(ctx, checked)
                refreshYtDlpSummary()
            }
        }
        switchCard.addView(buildSheetSwitchRow(
            ctx, dp,
            title = "Auto-update on Wi-Fi",
            subtitle = "Weekly · Stable channel · idle only",
            sw = autoSwitch
        ))

        switchCard.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1AFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(1))
        })

        val nightlySwitch = SwitchCompat(ctx).apply {
            styleSwitch(ctx, this)
            isChecked = YtDlpUpdater.getChannel(ctx) == YtDlpUpdater.CHANNEL_NIGHTLY
            setOnCheckedChangeListener { _, checked ->
                YtDlpUpdater.setChannel(
                    ctx,
                    if (checked) YtDlpUpdater.CHANNEL_NIGHTLY else YtDlpUpdater.CHANNEL_STABLE
                )
                refreshYtDlpSummary()
            }
        }
        switchCard.addView(buildSheetSwitchRow(
            ctx, dp,
            title = "Nightly channel (manual)",
            subtitle = "Fastest fixes for YouTube & social sites",
            sw = nightlySwitch
        ))
        root.addView(switchCard)

        val statusView = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.graball_orange))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = d(10) }
        }
        root.addView(statusView)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val updateBtn = TextView(ctx).apply {
            text = "Update yt-dlp Now"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(ctx, R.color.graball_orange))
                cornerRadius = 12 * dp
            }
            layoutParams = LinearLayout.LayoutParams(0, d(44), 1f)
        }
        val resetBtn = TextView(ctx).apply {
            text = "Reset"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#D0D4E6"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#25273E"))
                cornerRadius = 12 * dp
            }
            setPadding(d(16), 0, d(16), 0)
            visibility = if (YtDlpUpdater.isUsingOverride(ctx)) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                d(44)
            ).apply { marginStart = d(8) }
        }

        updateBtn.setOnClickListener {
            val appCtx = ctx.applicationContext
            updateBtn.isEnabled = false
            updateBtn.alpha = 0.6f
            resetBtn.isEnabled = false
            statusView.visibility = View.VISIBLE
            statusView.text = "Checking for yt-dlp update…"
            Thread {
                val res = YtDlpUpdater.checkAndUpdate(appCtx) { status ->
                    activity?.runOnUiThread {
                        if (dialog.isShowing) statusView.text = status
                    }
                }
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (dialog.isShowing) {
                        updateBtn.isEnabled = true
                        updateBtn.alpha = 1f
                        resetBtn.isEnabled = true
                        statusView.text = res.message
                        verLabel.text = "Active: ${YtDlpUpdater.getCachedVersion(appCtx)}"
                        resetBtn.visibility = if (YtDlpUpdater.isUsingOverride(appCtx)) View.VISIBLE else View.GONE
                    }
                    refreshYtDlpSummary()
                }
            }.start()
        }

        resetBtn.setOnClickListener {
            YtDlpUpdater.resetToBundled(ctx)
            statusView.visibility = View.VISIBLE
            statusView.text = "Reset to bundled yt-dlp ✓"
            verLabel.text = "Active: ${YtDlpUpdater.getCachedVersion(ctx)}"
            resetBtn.visibility = View.GONE
            refreshYtDlpSummary()
        }

        btnRow.addView(updateBtn)
        btnRow.addView(resetBtn)
        root.addView(btnRow)

        DarkBottomSheet.show(dialog, root)
    }

    // ---- Sleek Dark App Update Bottom Sheet ----

    private fun showAppUpdateSheet(release: AppUpdateChecker.AppRelease) {
        val ctx = context ?: return
        val dp = ctx.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()

        val dialog = DarkBottomSheet.createDialog(ctx)
        val root = DarkBottomSheet.createContainer(ctx, horizontalPadDp = 18)

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = d(10) }
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(ctx).apply {
            text = "GrabAll v${release.version} Available"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        val sizeStr = if (release.sizeBytes > 0) {
            String.format(" · %.1f MB", release.sizeBytes / (1024.0 * 1024.0))
        } else {
            ""
        }
        titleCol.addView(TextView(ctx).apply {
            text = "v${release.currentVersion} → v${release.version}$sizeStr"
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.graball_orange))
            setTypeface(null, Typeface.BOLD)
        })
        headerRow.addView(titleCol)

        headerRow.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Color.parseColor("#B0B4CC"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#24263B"))
                cornerRadius = 16 * dp
            }
            layoutParams = LinearLayout.LayoutParams(d(32), d(32))
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(headerRow)

        if (release.releaseNotes.isNotBlank()) {
            root.addView(TextView(ctx).apply {
                text = release.releaseNotes
                textSize = 12.5f
                setTextColor(Color.parseColor("#C7CBE0"))
                setLineSpacing(3 * dp, 1f)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#202236"))
                    cornerRadius = 12 * dp
                }
                setPadding(d(12), d(10), d(12), d(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = d(14) }
            })
        }

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        btnRow.addView(TextView(ctx).apply {
            text = "Later"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#B0B4CC"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#25273E"))
                cornerRadius = 12 * dp
            }
            setPadding(d(18), 0, d(18), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                d(44)
            ).apply { marginEnd = d(8) }
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(ctx).apply {
            text = "⬇ Download APK"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(ctx, R.color.graball_orange))
                cornerRadius = 12 * dp
            }
            layoutParams = LinearLayout.LayoutParams(0, d(44), 1f)
            setOnClickListener {
                dialog.dismiss()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)))
                } catch (_: Exception) {
                    Toast.makeText(ctx, "Could not open browser", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(btnRow)

        DarkBottomSheet.show(dialog, root)
    }

    private fun buildSheetSwitchRow(
        ctx: Context,
        dp: Float,
        title: String,
        subtitle: String,
        sw: SwitchCompat
    ): LinearLayout {
        fun d(v: Int) = (v * dp).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(10), 0, d(10))
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = d(10)
                }
            }
            textCol.addView(TextView(ctx).apply {
                text = title
                textSize = 14f
                setTextColor(Color.WHITE)
            })
            textCol.addView(TextView(ctx).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(Color.parseColor("#9DA2BD"))
            })
            addView(textCol)
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
    }

    private fun styleSwitch(ctx: Context, sw: SwitchCompat) {
        val orange = ContextCompat.getColor(ctx, R.color.graball_orange)
        val thumbStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )
        sw.thumbTintList = ColorStateList(thumbStates, intArrayOf(Color.WHITE, Color.parseColor("#B5B9D0")))
        sw.trackTintList = ColorStateList(thumbStates, intArrayOf(orange, Color.parseColor("#353852")))
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
