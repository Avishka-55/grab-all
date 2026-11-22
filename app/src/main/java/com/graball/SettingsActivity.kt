package com.graball

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.graball.engine.CookieImporter
import com.graball.engine.RuntimePaths
import com.graball.youtube.ThumbnailLoader
import com.graball.youtube.YouTubePlaybackService
import com.graball.youtube.YouTubeStore
import java.io.File

/**
 * Settings screen: YouTube session cookies import, screen-off audio-only toggle,
 * SponsorBlock toggle, and player/thumbnail cache management.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var cookiesStatus: TextView
    private lateinit var cacheSizeText: TextView

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                val result = CookieImporter.importCookies(this, uri)
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                if (result.ok) {
                    cookiesStatus.text = getString(R.string.cookies_saved)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        cookiesStatus = findViewById(R.id.settingsCookiesStatus)
        cacheSizeText = findViewById(R.id.settingsCacheSize)

        findViewById<Button>(R.id.settingsBack).setOnClickListener {
            finish()
        }
        findViewById<LinearLayout>(R.id.navGrab).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        findViewById<LinearLayout>(R.id.navDownloads).setOnClickListener {
            startActivity(
                Intent(this, DownloadsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        findViewById<LinearLayout>(R.id.navPlayer).setOnClickListener {
            startActivity(
                Intent(this, PlayerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        findViewById<Button>(R.id.settingsImportButton).setOnClickListener {
            importLauncher.launch("*/*")
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
    }

    override fun onResume() {
        super.onResume()
        cookiesStatus.text = if (RuntimePaths.cookiesFile(this).exists()) {
            getString(R.string.cookies_saved)
        } else {
            getString(R.string.cookies_missing)
        }
        refreshCacheSize()
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
