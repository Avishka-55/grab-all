package com.graball

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.graball.engine.DownloadPrefs
import com.graball.engine.DownloadService
import com.graball.engine.DownloadProgressBus
import com.graball.engine.LinkDetector
import com.graball.engine.LinkKind
import com.graball.engine.RuntimePaths
import com.graball.youtube.YouTubeActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var qualitySpinner: Spinner
    private lateinit var audioOnlyCheck: CheckBox
    private lateinit var playlistSpinner: Spinner
    private lateinit var linkInput: EditText
    private lateinit var grabPanel: LinearLayout
    private lateinit var grabType: TextView
    private lateinit var playlistRow: LinearLayout
    /** Top tabs (Search is this screen); kept to re-select Search on return. */
    private lateinit var mainTabs: List<TextView>
    /** What the open Grab panel holds; video panels force single. */
    private var grabbedKind: LinkKind = LinkKind.VIDEO

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Android 13+ (API 33+) hides foreground download/music
        // notifications unless POST_NOTIFICATIONS is granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        qualitySpinner = findViewById(R.id.qualitySpinner)
        qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Best", "1080p", "720p", "480p", "360p")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        audioOnlyCheck = findViewById(R.id.audioOnlyCheck)
        playlistSpinner = findViewById(R.id.playlistSpinner)
        playlistSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Single video", "First 20", "First 30", "First 50", "All videos")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val linkInput = findViewById<EditText>(R.id.linkInput)
        this.linkInput = linkInput
        grabPanel = findViewById(R.id.grabPanel)
        grabType = findViewById(R.id.grabType)
        playlistRow = findViewById(R.id.playlistRow)
        // Option 4 chrome: tabs, site cards, bottom nav. Search is
        // this screen; YouTube opens the in-app browser; More opens
        // the supported-sites list.
        mainTabs = listOf(
            findViewById(R.id.tabSearch),
            findViewById(R.id.tabYouTube),
            findViewById(R.id.tabMusic),
            findViewById(R.id.tabMore)
        )
        selectTab(mainTabs[0])
        mainTabs[1].setOnClickListener {
            selectTab(mainTabs[1])
            startActivity(Intent(this, YouTubeActivity::class.java))
        }
        mainTabs[2].setOnClickListener {
            selectTab(mainTabs[2])
            startActivity(
                Intent(this, YouTubeActivity::class.java).putExtra("start_mode", "music")
            )
        }
        mainTabs[3].setOnClickListener {
            selectTab(mainTabs[3])
            startActivity(Intent(this, SitesActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.siteYoutube).setOnClickListener {
            startActivity(Intent(this, YouTubeActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.siteTiktok).setOnClickListener {
            startActivity(
                Intent(this, BrowseActivity::class.java).putExtra(
                    "start_url", "https://www.tiktok.com"
                )
            )
        }
        findViewById<LinearLayout>(R.id.siteInstagram).setOnClickListener {
            startActivity(
                Intent(this, BrowseActivity::class.java).putExtra(
                    "start_url", "https://www.instagram.com"
                )
            )
        }
        findViewById<LinearLayout>(R.id.siteFacebook).setOnClickListener {
            startActivity(
                Intent(this, BrowseActivity::class.java).putExtra(
                    "start_url", "https://www.facebook.com"
                )
            )
        }
        findViewById<LinearLayout>(R.id.siteWhatsapp).setOnClickListener {
            startActivity(Intent(this, StatusActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.siteMore).setOnClickListener {
            startActivity(Intent(this, SitesActivity::class.java))
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
        findViewById<LinearLayout>(R.id.navSettings).setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        findViewById<Button>(R.id.grabButton).setOnClickListener {
            var url = linkInput.text.toString().trim()
            if (url.isEmpty()) {
                // Empty box + a link on the clipboard: Grab pulls it
                // in first, so the button is one tap, not two.
                val clip = (getSystemService(Context.CLIPBOARD_SERVICE)
                    as? ClipboardManager)?.primaryClip
                    ?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
                val found = Regex("https?://\\S+").find(clip)?.value
                    ?.trimEnd('.', ',', ';', '!', '?', ')', ']')
                if (found != null) {
                    url = found
                    linkInput.setText(url)
                }
            }
            if (url.isEmpty()) {
                Toast.makeText(this, "Paste a video link first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val kind = LinkDetector.detect(url)
            if (kind == LinkKind.UNKNOWN) {
                Toast.makeText(this, "That doesn't look like a link", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showGrabPanel(url, kind)
        }
        findViewById<Button>(R.id.downloadButton).setOnClickListener {
            val url = linkInput.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Paste a video link first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // The panel decides the mode: a video panel is always
            // single, whatever the (hidden) playlist spinner holds.
            val playlistMode = if (grabbedKind == LinkKind.PLAYLIST) {
                when (playlistSpinner.selectedItemPosition) {
                    1 -> "20"
                    2 -> "30"
                    3 -> "50"
                    4 -> "all"
                    else -> "single"
                }
            } else {
                "single"
            }
            if (playlistMode == "single" && url.contains("playlist?list=")) {
                Toast.makeText(
                    this,
                    "That's a playlist — pick how many videos above",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            // A STABLE staging subdir per URL (not per tap): a re-tap
            // after a network failure must land in the same dir, so
            // yt-dlp finds the kept .part file and resumes.
            val staging = RuntimePaths.stagingDirFor(this, url)
            if (!staging.exists()) {
                staging.mkdirs()
            }
            // Playlist downloads share one folder, so index the file
            // names — same-title videos would otherwise overwrite.
            // v0.7.5: a playlist lands in its own folder named after
            // it (Downloads/GrabAll/<Playlist Title>), via the
            // %(playlist_title)s template dir; singles stay flat.
            val fileName = if (playlistMode == "single") {
                "%(title)s.%(ext)s"
            } else {
                "%(playlist_title)s/%(playlist_index)s - %(title)s.%(ext)s"
            }
            val template = File(staging, fileName).absolutePath
            val maxHeight = when (qualitySpinner.selectedItemPosition) {
                1 -> 1080
                2 -> 720
                3 -> 480
                4 -> 360
                else -> null
            }
            DownloadProgressBus.modes[url] = buildString {
                if (audioOnlyCheck.isChecked) {
                    append("Audio only")
                } else {
                    append(
                        when (qualitySpinner.selectedItemPosition) {
                            1 -> "1080p"
                            2 -> "720p"
                            3 -> "480p"
                            4 -> "360p"
                            else -> "Best"
                        }
                    )
                }
                when (playlistMode) {
                    "20" -> append(" · First 20")
                    "30" -> append(" · First 30")
                    "50" -> append(" · First 50")
                    "all" -> append(" · All videos")
                }
            }
            // Remember these choices for the next panel — but a
            // video download must not overwrite the playlist choice
            // (see DownloadPrefs).
            val saved = DownloadPrefs.load(this)
            DownloadPrefs.save(
                this,
                DownloadPrefs.Choices(
                    qualitySpinner.selectedItemPosition,
                    audioOnlyCheck.isChecked,
                    if (grabbedKind == LinkKind.PLAYLIST) {
                        playlistSpinner.selectedItemPosition
                    } else {
                        saved.playlistPos
                    }
                )
            )
            DownloadService.startDownload(
                this, url, template,
                audioOnlyCheck.isChecked, maxHeight, playlistMode
            )
            // No row here anymore: the row is born on the Downloads
            // screen, from the first progress update for this URL.
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        }
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Show the Grab panel for a link: the header says what was
     * detected (video or playlist, plus a short preview), the
     * playlist count only appears for playlists, and the spinners
     * open on the user's last-used choices.
     */
    private fun showGrabPanel(url: String, kind: LinkKind) {
        grabbedKind = kind
        grabType.text = (if (kind == LinkKind.PLAYLIST) "Playlist" else "Video") +
            " · " + LinkDetector.preview(url)
        playlistRow.visibility = if (kind == LinkKind.PLAYLIST) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        val prefs = DownloadPrefs.load(this)
        qualitySpinner.setSelection(prefs.qualityPos)
        audioOnlyCheck.isChecked = prefs.audioOnly
        if (kind == LinkKind.PLAYLIST) {
            playlistSpinner.setSelection(prefs.playlistPos)
        }
        grabPanel.visibility = android.view.View.VISIBLE
    }

    /**
     * Shared links land here from the YouTube app, Chrome, etc.
     * (ACTION_SEND, text/plain). Prefill the link field and open
     * the Grab panel straight away — the user still picks options
     * and taps Download, so a share can never start a download by
     * surprise.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) {
            return
        }
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(shared)?.value
            ?.trimEnd('.', ',', ';', '!', '?', ')', ']') ?: return
        findViewById<EditText>(R.id.linkInput).setText(url)
        val kind = LinkDetector.detect(url)
        if (kind != LinkKind.UNKNOWN) {
            showGrabPanel(url, kind)
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the browser: this screen is Search again, so the
        // tab highlight returns with it.
        if (::mainTabs.isInitialized) {
            selectTab(mainTabs[0])
        }
    }

    /** Add a queue row for [url], or refresh it if one already exists. */
    /** Highlight one top tab; the rest go dim. */
    private fun selectTab(selected: TextView) {
        mainTabs.forEach {
            it.setBackgroundResource(android.R.color.transparent)
            it.setTextColor(ContextCompat.getColor(this, R.color.graball_tab_text))
            it.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        selected.setBackgroundResource(R.drawable.tab_selected)
        selected.setTextColor(Color.BLACK)
        selected.setTypeface(null, android.graphics.Typeface.BOLD)
    }

}
