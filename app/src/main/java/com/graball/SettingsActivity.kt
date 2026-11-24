package com.graball

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.graball.engine.AppUpdateChecker
import com.graball.engine.DownloadPrefs
import com.graball.engine.RuntimePaths
import com.graball.engine.WebViewCookieSync
import com.graball.engine.YtDlpUpdater
import com.graball.youtube.ThumbnailLoader
import com.graball.youtube.YouTubePlaybackService
import com.graball.youtube.YouTubeStore
import java.io.File

/**
 * Settings screen: on-device YouTube sign-in, screen-off audio-only toggle,
 * SponsorBlock toggle, cache management, yt-dlp self-updater, and GrabAll app
 * update checker.
 */
class SettingsActivity : AppCompatActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        cookiesStatus = findViewById(R.id.settingsCookiesStatus)
        loginBtn = findViewById(R.id.settingsLoginButton)
        logoutBtn = findViewById(R.id.settingsLogoutButton)
        cacheSizeText = findViewById(R.id.settingsCacheSize)
        ytDlpVersionText = findViewById(R.id.settingsYtDlpVersion)
        ytDlpStatusText = findViewById(R.id.settingsYtDlpStatus)
        ytDlpUpdateBtn = findViewById(R.id.settingsYtDlpUpdate)
        ytDlpResetBtn = findViewById(R.id.settingsYtDlpReset)
        appVersionText = findViewById(R.id.settingsAppVersion)
        appUpdateStatusText = findViewById(R.id.settingsAppUpdateStatus)
        appUpdateBtn = findViewById(R.id.settingsCheckAppUpdate)

        findViewById<Button>(R.id.settingsBack).setOnClickListener {
            finish()
        }
        findViewById<LinearLayout>(R.id.navGrab).setOnClickListener {
            NavHelper.switchTab(this, MainActivity::class.java)
        }
        findViewById<LinearLayout>(R.id.navDownloads).setOnClickListener {
            NavHelper.switchTab(this, DownloadsActivity::class.java)
        }
        findViewById<LinearLayout>(R.id.navPlayer).setOnClickListener {
            NavHelper.switchTab(this, PlayerActivity::class.java)
        }
        loginBtn.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        logoutBtn.setOnClickListener {
            WebViewCookieSync.clearYouTubeSession(this)
            refreshSessionUi()
            Toast.makeText(this, "Signed out of YouTube", Toast.LENGTH_SHORT).show()
        }

        val screenOffCheck = findViewById<CheckBox>(R.id.settingsScreenOffAudio)
        screenOffCheck.isChecked = YouTubeStore.isScreenOffAudioOnly(this)
        screenOffCheck.setOnCheckedChangeListener { _, checked ->
            YouTubeStore.setScreenOffAudioOnly(this, checked)
        }

        val sbCheck = findViewById<CheckBox>(R.id.settingsSponsorBlock)
        sbCheck.isChecked = YouTubeStore.isSponsorBlockEnabled(this)
        sbCheck.setOnCheckedChangeListener { _, checked ->
            YouTubeStore.setSponsorBlockEnabled(this, checked)
        }

        findViewById<Button>(R.id.settingsClearCache).setOnClickListener {
            ThumbnailLoader.clearAll(this)
            YouTubePlaybackService.clearMediaCache(this)
            refreshCacheSize()
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
        }

        // ---- Concurrent Downloads Selector ----
        val concStatusText = findViewById<TextView>(R.id.settingsConcurrencyStatus)
        val concChips = listOf(
            0 to findViewById<TextView>(R.id.chipConcAuto),
            1 to findViewById<TextView>(R.id.chipConc1),
            2 to findViewById<TextView>(R.id.chipConc2),
            3 to findViewById<TextView>(R.id.chipConc3),
            4 to findViewById<TextView>(R.id.chipConc4),
            6 to findViewById<TextView>(R.id.chipConc6)
        )
        fun refreshConcurrencyChips() {
            val selected = DownloadPrefs.getConcurrentDownloads(this)
            val resolved = DownloadPrefs.resolvedConcurrency(this)
            concChips.forEach { (value, chip) ->
                val isSel = value == selected
                chip.setBackgroundResource(
                    if (isSel) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg
                )
                chip.setTextColor(
                    ContextCompat.getColor(
                        this,
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
                DownloadPrefs.setConcurrentDownloads(this, value)
                refreshConcurrencyChips()
            }
        }
        refreshConcurrencyChips()

        // ---- Audio Download Format (MP3 vs Fast M4A) ----
        val chipMp3 = findViewById<TextView>(R.id.chipAudioMp3)
        val chipM4a = findViewById<TextView>(R.id.chipAudioM4a)
        val audioFmtStatus = findViewById<TextView>(R.id.settingsAudioFormatStatus)
        fun refreshAudioFormatChips() {
            val isM4a = DownloadPrefs.isFastM4a(this)
            chipMp3.setBackgroundResource(if (!isM4a) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
            chipMp3.setTextColor(ContextCompat.getColor(this, if (!isM4a) R.color.graball_text else R.color.graball_subtext))
            chipM4a.setBackgroundResource(if (isM4a) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
            chipM4a.setTextColor(ContextCompat.getColor(this, if (isM4a) R.color.graball_text else R.color.graball_subtext))
            audioFmtStatus.text = if (isM4a) {
                "M4A skips CPU re-encoding (~10× faster playlists, original AAC quality + cover art)."
            } else {
                "MP3 works everywhere (converted via ffmpeg with embedded cover art & ID3 tags)."
            }
        }
        chipMp3.setOnClickListener {
            DownloadPrefs.setAudioFormat(this, DownloadPrefs.AUDIO_FORMAT_MP3)
            refreshAudioFormatChips()
        }
        chipM4a.setOnClickListener {
            DownloadPrefs.setAudioFormat(this, DownloadPrefs.AUDIO_FORMAT_M4A)
            refreshAudioFormatChips()
        }
        refreshAudioFormatChips()

        // ---- yt-dlp Self-Updater ----
        val nightlyCheck = findViewById<CheckBox>(R.id.settingsYtDlpNightly)
        nightlyCheck.isChecked = YtDlpUpdater.getChannel(this) == YtDlpUpdater.CHANNEL_NIGHTLY
        nightlyCheck.setOnCheckedChangeListener { _, checked ->
            YtDlpUpdater.setChannel(
                this,
                if (checked) YtDlpUpdater.CHANNEL_NIGHTLY else YtDlpUpdater.CHANNEL_STABLE
            )
        }

        ytDlpUpdateBtn.setOnClickListener {
            ytDlpUpdateBtn.isEnabled = false
            ytDlpResetBtn.isEnabled = false
            ytDlpStatusText.visibility = View.VISIBLE
            ytDlpStatusText.text = "Checking for yt-dlp update…"
            Thread {
                val res = YtDlpUpdater.checkAndUpdate(applicationContext) { status ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            ytDlpStatusText.text = status
                        }
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    ytDlpUpdateBtn.isEnabled = true
                    ytDlpResetBtn.isEnabled = true
                    ytDlpStatusText.text = res.message
                    refreshYtDlpUi()
                }
            }.start()
        }

        ytDlpResetBtn.setOnClickListener {
            YtDlpUpdater.resetToBundled(this)
            ytDlpStatusText.visibility = View.VISIBLE
            ytDlpStatusText.text = "Reset to bundled yt-dlp ✓"
            refreshYtDlpUi()
        }

        // ---- GrabAll App Update Checker ----
        appVersionText.text = "Installed: GrabAll v${AppUpdateChecker.currentVersionName(this)}"
        appUpdateBtn.setOnClickListener {
            appUpdateBtn.isEnabled = false
            appUpdateStatusText.visibility = View.VISIBLE
            appUpdateStatusText.text = "Checking for latest Android release…"
            Thread {
                val release = AppUpdateChecker.check(applicationContext, markChecked = true)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
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
    }

    override fun onResume() {
        super.onResume()
        refreshSessionUi()
        refreshCacheSize()
        refreshYtDlpUi()
    }

    private fun refreshSessionUi() {
        val signedIn = RuntimePaths.cookiesFile(this).exists() &&
            WebViewCookieSync.buildYouTubeAuthHeaders(this) != null
        cookiesStatus.text = if (signedIn) {
            getString(R.string.cookies_saved)
        } else {
            getString(R.string.cookies_missing)
        }
        logoutBtn.visibility = if (signedIn) View.VISIBLE else View.GONE
    }

    private fun refreshYtDlpUi() {
        val ver = YtDlpUpdater.getCachedVersion(this)
        ytDlpVersionText.text = "Active: $ver"
        ytDlpResetBtn.visibility = if (YtDlpUpdater.isUsingOverride(this)) View.VISIBLE else View.GONE
    }

    private fun showAppUpdateDialog(release: AppUpdateChecker.AppRelease) {
        val sizeStr = if (release.sizeBytes > 0) {
            String.format(" (%.1f MB)", release.sizeBytes / (1024.0 * 1024.0))
        } else {
            ""
        }
        AlertDialog.Builder(this)
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
                    Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun refreshCacheSize() {
        val bytes = dirSize(File(cacheDir, "yt_thumbs")) +
            dirSize(File(cacheDir, "yt_media_cache"))
        val mb = bytes / (1024.0 * 1024.0)
        cacheSizeText.text = String.format("Player & thumbnail cache: %.1f MB", mb)
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
