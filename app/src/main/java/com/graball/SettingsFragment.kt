package com.graball

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.graball.engine.AppUpdateChecker
import com.graball.engine.DownloadPrefs
import com.graball.engine.WebViewCookieSync
import com.graball.engine.YtDlpUpdater
import com.graball.youtube.ThumbnailLoader
import com.graball.youtube.YouTubePlaybackService
import com.graball.youtube.YouTubeStore
import java.io.File
import java.util.concurrent.Executors

/**
 * Settings tab hosted inside [MainActivity]'s single-activity shell.
 * Runs cache-size disk walks and cookie checks off the UI thread so tab
 * switches stay instant (<5 ms).
 */
class SettingsFragment : Fragment(), MainActivity.TabFragment {

    private lateinit var cookiesStatus: TextView
    private lateinit var loginBtn: Button
    private lateinit var logoutBtn: Button
    private lateinit var cacheSizeText: TextView
    private lateinit var ytDlpVersionText: TextView
    private lateinit var ytDlpStatusText: TextView
    private lateinit var ytDlpUpdateBtn: Button
    private lateinit var ytDlpResetBtn: Button
    private lateinit var appVersionText: TextView
    private lateinit var appUpdateStatusText: TextView
    private lateinit var appUpdateBtn: Button

    @Volatile
    private var cachedSignedIn = false
    private val settingsIo = Executors.newSingleThreadExecutor()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.activity_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        cookiesStatus = view.findViewById(R.id.settingsCookiesStatus)
        loginBtn = view.findViewById(R.id.settingsLoginButton)
        logoutBtn = view.findViewById(R.id.settingsLogoutButton)
        cacheSizeText = view.findViewById(R.id.settingsCacheSize)
        ytDlpVersionText = view.findViewById(R.id.settingsYtDlpVersion)
        ytDlpStatusText = view.findViewById(R.id.settingsYtDlpStatus)
        ytDlpUpdateBtn = view.findViewById(R.id.settingsYtDlpUpdate)
        ytDlpResetBtn = view.findViewById(R.id.settingsYtDlpReset)
        appVersionText = view.findViewById(R.id.settingsAppVersion)
        appUpdateStatusText = view.findViewById(R.id.settingsAppUpdateStatus)
        appUpdateBtn = view.findViewById(R.id.settingsCheckAppUpdate)

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

        val screenOffCheck = view.findViewById<CheckBox>(R.id.settingsScreenOffAudio)
        screenOffCheck.isChecked = YouTubeStore.isScreenOffAudioOnly(ctx)
        screenOffCheck.setOnCheckedChangeListener { _, checked ->
            context?.let { YouTubeStore.setScreenOffAudioOnly(it, checked) }
        }

        val sbCheck = view.findViewById<CheckBox>(R.id.settingsSponsorBlock)
        sbCheck.isChecked = YouTubeStore.isSponsorBlockEnabled(ctx)
        sbCheck.setOnCheckedChangeListener { _, checked ->
            context?.let { YouTubeStore.setSponsorBlockEnabled(it, checked) }
        }

        view.findViewById<Button>(R.id.settingsClearCache).setOnClickListener {
            val c = context ?: return@setOnClickListener
            ThumbnailLoader.clearAll(c)
            YouTubePlaybackService.clearMediaCache(c)
            refreshCacheSizeAsync()
            Toast.makeText(c, "Cache cleared", Toast.LENGTH_SHORT).show()
        }

        // ---- Concurrent Downloads Selector ----
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
            concStatusText.text = if (selected == 0) {
                "Auto: up to $resolved parallel playlist tracks / concurrent videos"
            } else if (selected == 1) {
                "1 at a time (lowest CPU & RAM usage)"
            } else {
                "Up to $selected parallel playlist tracks / concurrent videos"
            }
        }
        concChips.forEach { (value, chip) ->
            chip.setOnClickListener {
                context?.let { DownloadPrefs.setConcurrentDownloads(it, value) }
                refreshConcurrencyChips()
            }
        }
        refreshConcurrencyChips()

        // ---- Audio Download Format (MP3 vs Fast M4A) ----
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
                "M4A skips CPU re-encoding (~10× faster playlists, original AAC quality + cover art)."
            } else {
                "MP3 works everywhere (converted via ffmpeg with embedded cover art & ID3 tags)."
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

        // ---- yt-dlp Self-Updater ----
        val nightlyCheck = view.findViewById<CheckBox>(R.id.settingsYtDlpNightly)
        nightlyCheck.isChecked = YtDlpUpdater.getChannel(ctx) == YtDlpUpdater.CHANNEL_NIGHTLY
        nightlyCheck.setOnCheckedChangeListener { _, checked ->
            context?.let {
                YtDlpUpdater.setChannel(
                    it,
                    if (checked) YtDlpUpdater.CHANNEL_NIGHTLY else YtDlpUpdater.CHANNEL_STABLE
                )
            }
        }

        ytDlpUpdateBtn.setOnClickListener {
            val appCtx = context?.applicationContext ?: return@setOnClickListener
            ytDlpUpdateBtn.isEnabled = false
            ytDlpResetBtn.isEnabled = false
            ytDlpStatusText.visibility = View.VISIBLE
            ytDlpStatusText.text = "Checking for yt-dlp update…"
            Thread {
                val res = YtDlpUpdater.checkAndUpdate(appCtx) { status ->
                    activity?.runOnUiThread {
                        if (isAdded) ytDlpStatusText.text = status
                    }
                }
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    ytDlpUpdateBtn.isEnabled = true
                    ytDlpResetBtn.isEnabled = true
                    ytDlpStatusText.text = res.message
                    refreshYtDlpUi()
                }
            }.start()
        }

        ytDlpResetBtn.setOnClickListener {
            val c = context ?: return@setOnClickListener
            YtDlpUpdater.resetToBundled(c)
            ytDlpStatusText.visibility = View.VISIBLE
            ytDlpStatusText.text = "Reset to bundled yt-dlp ✓"
            refreshYtDlpUi()
        }

        // ---- GrabAll App Update Checker ----
        appVersionText.text = "Installed: GrabAll v${AppUpdateChecker.currentVersionName(ctx)}"
        appUpdateBtn.setOnClickListener {
            val appCtx = context?.applicationContext ?: return@setOnClickListener
            appUpdateBtn.isEnabled = false
            appUpdateStatusText.visibility = View.VISIBLE
            appUpdateStatusText.text = "Checking for latest Android release…"
            Thread {
                val release = AppUpdateChecker.check(appCtx, markChecked = true)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    appUpdateBtn.isEnabled = true
                    if (release == null) {
                        appUpdateStatusText.text = "Could not check for updates — check internet connection"
                    } else if (release.isNewer) {
                        appUpdateStatusText.text = "New version available: v${release.version}"
                        showAppUpdateDialog(release)
                    } else {
                        appUpdateStatusText.text = "You're on the latest version (v${release.currentVersion}) ✓"
                    }
                }
            }.start()
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

    private fun refreshAllAsync() {
        if (!::cookiesStatus.isInitialized) return
        refreshYtDlpUi()
        refreshSessionAndCacheAsync()
    }

    private fun refreshSessionAndCacheAsync() {
        val appCtx = context?.applicationContext ?: return
        settingsIo.execute {
            val signedIn = WebViewCookieSync.hasLoggedInYouTubeSession() ||
                WebViewCookieSync.buildYouTubeAuthHeaders(appCtx) != null
            cachedSignedIn = signedIn
            val bytes = dirSize(File(appCtx.cacheDir, "yt_thumbs")) +
                dirSize(File(appCtx.cacheDir, "yt_media_cache"))
            val mb = bytes / (1024.0 * 1024.0)
            val cacheLabel = String.format("Player & thumbnail cache: %.1f MB", mb)
            activity?.runOnUiThread {
                if (!isAdded || !::cookiesStatus.isInitialized) return@runOnUiThread
                cookiesStatus.text = if (signedIn) {
                    getString(R.string.cookies_saved)
                } else {
                    getString(R.string.cookies_missing)
                }
                loginBtn.text = if (signedIn) {
                    "Switch YouTube account"
                } else {
                    getString(R.string.login_youtube)
                }
                logoutBtn.visibility = if (signedIn) View.VISIBLE else View.GONE
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
            val cacheLabel = String.format("Player & thumbnail cache: %.1f MB", mb)
            activity?.runOnUiThread {
                if (isAdded && ::cacheSizeText.isInitialized) {
                    cacheSizeText.text = cacheLabel
                }
            }
        }
    }

    private fun refreshYtDlpUi() {
        val ctx = context ?: return
        val ver = YtDlpUpdater.getCachedVersion(ctx)
        ytDlpVersionText.text = "Active: $ver"
        ytDlpResetBtn.visibility = if (YtDlpUpdater.isUsingOverride(ctx)) View.VISIBLE else View.GONE
    }

    private fun showAppUpdateDialog(release: AppUpdateChecker.AppRelease) {
        val ctx = context ?: return
        val sizeStr = if (release.sizeBytes > 0) {
            String.format(" (%.1f MB)", release.sizeBytes / (1024.0 * 1024.0))
        } else {
            ""
        }
        AlertDialog.Builder(ctx)
            .setTitle("GrabAll v${release.version} Available")
            .setMessage(
                "Installed: v${release.currentVersion}\n" +
                    "Latest Android APK: v${release.version}$sizeStr\n\n" +
                    "Tap Download APK to grab the latest release."
            )
            .setPositiveButton("Download APK") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)))
                } catch (_: Exception) {
                    Toast.makeText(ctx, "Could not open browser", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
