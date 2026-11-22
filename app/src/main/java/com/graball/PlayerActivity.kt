package com.graball

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.graball.youtube.ThumbnailLoader
import com.graball.youtube.YouTubePlaybackService
import com.graball.youtube.YouTubePlaybackState
import com.graball.youtube.YouTubePlayerActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Modern folder-aware Music Player with:
 * - Compact Mini-Player bar showing real embedded album art + live progress
 * - Expandable Full-Screen "Now Playing" screen (large cover art, ID3 tags,
 *   full transport controls, Equalizer, Add to Playlist, Up Next Queue,
 *   swipe-down to collapse)
 * - Seamless YouTube Audio Mode integration: when YouTube is playing in
 *   background audio-only mode, the mini-player & full-screen player display
 *   its YouTube thumbnail, live controls, and a 1-tap "Watch Video ↗" button
 *   to jump directly to the playing video in GrabAll's YouTube UI.
 */
class PlayerActivity : AppCompatActivity() {

    private data class AudioTrack(
        val file: File,
        val folderPath: String,
        val folderName: String,
        val isSdCard: Boolean,
        val durationMs: Long
    )

    private data class MusicFolder(
        val path: String,
        val name: String,
        val isSdCard: Boolean,
        val tracks: List<AudioTrack>,
        val isAutoExcluded: Boolean
    )

    private data class MusicPlaylist(
        val name: String,
        val paths: MutableList<String>
    )

    private enum class ViewMode { FOLDERS, FOLDER_SONGS, PLAYLISTS, PLAYLIST_SONGS, ALL_MUSIC }
    private enum class ActiveSource { NONE, LOCAL, YOUTUBE }

    // Library UI
    private lateinit var searchInput: EditText
    private lateinit var tabsRow: LinearLayout
    private lateinit var tabFolders: TextView
    private lateinit var tabPlaylists: TextView
    private lateinit var tabAll: TextView
    private lateinit var btnNewPlaylist: TextView
    private lateinit var folderHeaderBar: LinearLayout
    private lateinit var btnFolderBack: TextView
    private lateinit var folderHeaderName: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView

    // Mini-Player UI
    private lateinit var miniPlayerCard: LinearLayout
    private lateinit var miniProgress: ProgressBar
    private lateinit var miniYtBanner: LinearLayout
    private lateinit var miniBtnOpenYoutube: TextView
    private lateinit var miniPlayerRow: LinearLayout
    private lateinit var miniArtBox: FrameLayout
    private lateinit var miniArt: ImageView
    private lateinit var miniArtFallback: TextView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlayPause: TextView
    private lateinit var miniNext: TextView

    // Full-Screen Now Playing UI
    private lateinit var nowPlayingSheet: ScrollView
    private lateinit var npTopBar: LinearLayout
    private lateinit var npSourceLabel: TextView
    private lateinit var npYoutubeBar: LinearLayout
    private lateinit var npBtnOpenYoutube: TextView
    private lateinit var npArtBox: FrameLayout
    private lateinit var npArt: ImageView
    private lateinit var npArtFallback: TextView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npSeek: SeekBar
    private lateinit var npTimeCur: TextView
    private lateinit var npTimeDur: TextView
    private lateinit var npShuffle: TextView
    private lateinit var npPrev: TextView
    private lateinit var npPlayPause: TextView
    private lateinit var npNext: TextView
    private lateinit var npRepeat: TextView
    private lateinit var npBtnAddPlaylist: TextView

    private var allFolders: List<MusicFolder> = emptyList()
    private var tracksByPath: Map<String, AudioTrack> = emptyMap()
    private var playlists: MutableList<MusicPlaylist> = mutableListOf()
    private var viewMode: ViewMode = ViewMode.FOLDERS
    private var openFolderPath: String? = null
    private var openPlaylistName: String? = null
    private var searchQuery: String = ""

    private var service: PlayerService? = null
    private var bound = false
    private var draggingSeek = false
    private var isNowPlayingOpen = false
    private var lastTrack: File? = null
    private var lastArtKey: String? = null
    private var lastMetaPath: String? = null
    private var cachedLocalMeta: AlbumArtLoader.TrackMeta? = null

    private lateinit var adapter: MusicRowAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val ytStateListener: (YouTubePlaybackState) -> Unit = {
        updatePlayerUi()
    }

    private val poller = object : Runnable {
        override fun run() {
            updatePlayerUi()
            handler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                loadLibrary()
            } else {
                emptyView.text = getString(R.string.player_need_permission)
                emptyView.visibility = View.VISIBLE
            }
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PlayerService.PlayerBinder).getService()
            bound = true
            updatePlayerUi()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        // Library views
        searchInput = findViewById(R.id.playerSearch)
        tabsRow = findViewById(R.id.playerTabsRow)
        tabFolders = findViewById(R.id.tabMusicFolders)
        tabPlaylists = findViewById(R.id.tabMusicPlaylists)
        tabAll = findViewById(R.id.tabMusicAll)
        btnNewPlaylist = findViewById(R.id.btnNewPlaylist)
        folderHeaderBar = findViewById(R.id.folderHeaderBar)
        btnFolderBack = findViewById(R.id.btnFolderBack)
        folderHeaderName = findViewById(R.id.folderHeaderName)
        recycler = findViewById(R.id.playerRecycler)
        emptyView = findViewById(R.id.playerEmpty)

        // Mini-Player views
        miniPlayerCard = findViewById(R.id.miniPlayerCard)
        miniProgress = findViewById(R.id.miniProgress)
        miniYtBanner = findViewById(R.id.miniYtBanner)
        miniBtnOpenYoutube = findViewById(R.id.miniBtnOpenYoutube)
        miniPlayerRow = findViewById(R.id.miniPlayerRow)
        miniArtBox = findViewById(R.id.miniArtBox)
        miniArt = findViewById(R.id.miniArt)
        miniArtFallback = findViewById(R.id.miniArtFallback)
        miniTitle = findViewById(R.id.miniTitle)
        miniSub = findViewById(R.id.miniSub)
        miniPlayPause = findViewById(R.id.miniPlayPause)
        miniNext = findViewById(R.id.miniNext)

        // Full-Screen Now Playing views
        nowPlayingSheet = findViewById(R.id.nowPlayingSheet)
        npTopBar = findViewById(R.id.npTopBar)
        npSourceLabel = findViewById(R.id.npSourceLabel)
        npYoutubeBar = findViewById(R.id.npYoutubeBar)
        npBtnOpenYoutube = findViewById(R.id.npBtnOpenYoutube)
        npArtBox = findViewById(R.id.npArtBox)
        npArt = findViewById(R.id.npArt)
        npArtFallback = findViewById(R.id.npArtFallback)
        npTitle = findViewById(R.id.npTitle)
        npArtist = findViewById(R.id.npArtist)
        npSeek = findViewById(R.id.npSeek)
        npTimeCur = findViewById(R.id.npTimeCur)
        npTimeDur = findViewById(R.id.npTimeDur)
        npShuffle = findViewById(R.id.npShuffle)
        npPrev = findViewById(R.id.npPrev)
        npPlayPause = findViewById(R.id.npPlayPause)
        npNext = findViewById(R.id.npNext)
        npRepeat = findViewById(R.id.npRepeat)
        npBtnAddPlaylist = findViewById(R.id.npBtnAddPlaylist)

        // Hardware-rounded clipping for both album art cards (API 21+)
        miniArtBox.outlineProvider = ViewOutlineProvider.BACKGROUND
        miniArtBox.clipToOutline = true
        npArtBox.outlineProvider = ViewOutlineProvider.BACKGROUND
        npArtBox.clipToOutline = true

        // Bottom nav
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
        findViewById<LinearLayout>(R.id.navSettings).setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // Tabs & library header controls
        tabFolders.setOnClickListener {
            viewMode = ViewMode.FOLDERS
            openFolderPath = null
            openPlaylistName = null
            renderCurrentView()
        }
        tabPlaylists.setOnClickListener {
            viewMode = ViewMode.PLAYLISTS
            openFolderPath = null
            openPlaylistName = null
            renderCurrentView()
        }
        tabAll.setOnClickListener {
            viewMode = ViewMode.ALL_MUSIC
            openFolderPath = null
            openPlaylistName = null
            renderCurrentView()
        }
        btnNewPlaylist.setOnClickListener { showCreatePlaylistDialog(initialTrack = null) }
        btnFolderBack.setOnClickListener {
            if (viewMode == ViewMode.PLAYLIST_SONGS) {
                viewMode = ViewMode.PLAYLISTS
                openPlaylistName = null
            } else {
                viewMode = ViewMode.FOLDERS
                openFolderPath = null
            }
            renderCurrentView()
        }
        findViewById<TextView>(R.id.btnFolderPlayAll).setOnClickListener {
            if (viewMode == ViewMode.PLAYLIST_SONGS) {
                val pl = playlists.firstOrNull { it.name == openPlaylistName } ?: return@setOnClickListener
                val tracks = resolvePlaylistTracks(pl)
                if (tracks.isNotEmpty()) playFolderTracks(tracks, 0, pl.name)
            } else {
                val folder = allFolders.firstOrNull { it.path == openFolderPath } ?: return@setOnClickListener
                if (folder.tracks.isNotEmpty()) playFolderTracks(folder.tracks, 0, folder.name)
            }
        }
        findViewById<TextView>(R.id.btnEqualizer).setOnClickListener { openSystemEqualizer() }
        findViewById<TextView>(R.id.btnManageFolders).setOnClickListener { showManageFoldersDialog() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                renderCurrentView()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Mini-player interactions
        miniPlayerRow.setOnClickListener { openNowPlayingSheet() }
        miniPlayPause.setOnClickListener { handlePlayPause() }
        miniNext.setOnClickListener { handleNext() }
        miniBtnOpenYoutube.setOnClickListener { openCurrentYouTubeVideo() }

        // Full-Screen Now Playing interactions
        findViewById<TextView>(R.id.npCollapse).setOnClickListener { closeNowPlayingSheet() }
        findViewById<TextView>(R.id.npBtnQueue).setOnClickListener { showQueueDialog() }
        npBtnOpenYoutube.setOnClickListener { openCurrentYouTubeVideo() }
        npPrev.setOnClickListener { handlePrev() }
        npPlayPause.setOnClickListener { handlePlayPause() }
        npNext.setOnClickListener { handleNext() }
        npShuffle.setOnClickListener {
            if (resolveActiveSource() != ActiveSource.LOCAL) return@setOnClickListener
            val svc = service ?: return@setOnClickListener
            val on = svc.toggleShuffle()
            updateShuffleRepeatUi(svc)
            Toast.makeText(this, if (on) "Shuffle ON" else "Shuffle OFF", Toast.LENGTH_SHORT).show()
        }
        npRepeat.setOnClickListener {
            if (resolveActiveSource() != ActiveSource.LOCAL) return@setOnClickListener
            val svc = service ?: return@setOnClickListener
            val mode = svc.cycleRepeatMode()
            updateShuffleRepeatUi(svc)
            val label = when (mode) {
                PlayerService.RepeatMode.OFF -> "Repeat OFF"
                PlayerService.RepeatMode.ALL -> "Repeat All"
                PlayerService.RepeatMode.ONE -> "Repeat One Song"
            }
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.npBtnEq).setOnClickListener { openSystemEqualizer() }
        npBtnAddPlaylist.setOnClickListener {
            val file = service?.currentFile() ?: return@setOnClickListener
            val track = tracksByPath[file.absolutePath] ?: AudioTrack(
                file = file,
                folderPath = file.parentFile?.absolutePath ?: "",
                folderName = file.parentFile?.name ?: "Storage",
                isSdCard = false,
                durationMs = service?.duration()?.toLong() ?: 0L
            )
            showAddToPlaylistDialog(track)
        }
        findViewById<TextView>(R.id.npBtnStop).setOnClickListener {
            when (resolveActiveSource()) {
                ActiveSource.YOUTUBE -> YouTubePlaybackService.instance?.stopPlayback()
                ActiveSource.LOCAL -> sendControl(PlayerService.ACTION_STOP)
                ActiveSource.NONE -> Unit
            }
            closeNowPlayingSheet()
            updatePlayerUi()
        }

        npSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) npTimeCur.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(seek: SeekBar) {
                draggingSeek = true
            }
            override fun onStopTrackingTouch(seek: SeekBar) {
                draggingSeek = false
                when (resolveActiveSource()) {
                    ActiveSource.YOUTUBE -> YouTubePlaybackService.instance?.seekTo(seek.progress.toLong())
                    ActiveSource.LOCAL -> service?.seekTo(seek.progress)
                    ActiveSource.NONE -> Unit
                }
            }
        })

        // Swipe down on artwork or top bar to collapse Now Playing cleanly
        setupSwipeDownToCollapse(npTopBar)
        setupSwipeDownToCollapse(npArtBox)

        adapter = MusicRowAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        playlists = loadPlaylists()

        if (ContextCompat.checkSelfPermission(this, audioReadPermission()) == PackageManager.PERMISSION_GRANTED) {
            loadLibrary()
        } else {
            permissionLauncher.launch(audioReadPermission())
        }

        if (intent?.getBooleanExtra("open_now_playing", false) == true) {
            openNowPlayingSheet(animate = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_now_playing", false)) {
            openNowPlayingSheet(animate = true)
        }
    }

    override fun onResume() {
        super.onResume()
        bindService(
            Intent(this, PlayerService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        YouTubePlaybackService.addListener(ytStateListener)
        handler.post(poller)
        if (ContextCompat.checkSelfPermission(this, audioReadPermission()) == PackageManager.PERMISSION_GRANTED) {
            loadLibrary()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
        YouTubePlaybackService.removeListener(ytStateListener)
        if (bound) {
            unbindService(connection)
            bound = false
            service = null
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            isNowPlayingOpen -> closeNowPlayingSheet()
            viewMode == ViewMode.FOLDER_SONGS -> {
                viewMode = ViewMode.FOLDERS
                openFolderPath = null
                renderCurrentView()
            }
            viewMode == ViewMode.PLAYLIST_SONGS -> {
                viewMode = ViewMode.PLAYLISTS
                openPlaylistName = null
                renderCurrentView()
            }
            else -> super.onBackPressed()
        }
    }

    // ---- Active Source Coordination (Local Music vs YouTube Audio) ----

    private fun resolveActiveSource(): ActiveSource {
        val localFile = if (bound) service?.currentFile() else null
        val localPlaying = if (bound) service?.isPlaying() == true else false
        val ytState = YouTubePlaybackService.currentState
        val ytItem = ytState.currentItem
        val ytAudioActive = ytItem != null && (ytState.isAudioOnly || ytState.isPlaying)

        return when {
            localFile != null && localPlaying -> ActiveSource.LOCAL
            ytAudioActive -> ActiveSource.YOUTUBE
            localFile != null -> ActiveSource.LOCAL
            ytItem != null -> ActiveSource.YOUTUBE
            else -> ActiveSource.NONE
        }
    }

    private fun updatePlayerUi() {
        val source = resolveActiveSource()
        when (source) {
            ActiveSource.LOCAL -> bindLocalPlayerUi()
            ActiveSource.YOUTUBE -> bindYouTubePlayerUi()
            ActiveSource.NONE -> bindIdlePlayerUi()
        }
    }

    private fun bindLocalPlayerUi() {
        val svc = service ?: return
        val current = svc.currentFile() ?: return

        if (current.absolutePath != lastMetaPath) {
            lastMetaPath = current.absolutePath
            cachedLocalMeta = svc.currentTrackMeta() ?: AlbumArtLoader.getCachedMeta(current)
            AlbumArtLoader.loadMetaAsync(current) { meta ->
                if (lastMetaPath == current.absolutePath) {
                    cachedLocalMeta = meta
                }
            }
        }
        val meta = svc.currentTrackMeta() ?: cachedLocalMeta
        val title = meta?.title?.takeIf { it.isNotBlank() } ?: current.nameWithoutExtension
        val folderLbl = svc.currentFolderLabel()
        val sub = buildString {
            if (!meta?.artist.isNullOrBlank()) {
                append(meta!!.artist)
                if (folderLbl.isNotBlank()) append(" · ").append(folderLbl)
            } else if (folderLbl.isNotBlank()) {
                append("📁 ").append(folderLbl)
            } else {
                append("Local Music")
            }
        }

        // Mini-player
        miniYtBanner.visibility = View.GONE
        miniTitle.text = title
        miniSub.text = sub
        val playing = svc.isPlaying()
        miniPlayPause.text = if (playing) "⏸" else "▶"

        // Full-Screen Now Playing
        npSourceLabel.text = if (folderLbl.isNotBlank()) {
            "PLAYING FROM · ${folderLbl.uppercase()}"
        } else {
            "LOCAL MUSIC"
        }
        npYoutubeBar.visibility = View.GONE
        npTitle.text = title
        npArtist.text = sub
        npPlayPause.text = if (playing) "⏸" else "▶"
        npShuffle.visibility = View.VISIBLE
        npRepeat.visibility = View.VISIBLE
        npBtnAddPlaylist.visibility = View.VISIBLE
        updateShuffleRepeatUi(svc)

        // Progress & SeekBar
        val dur = svc.duration()
        val pos = svc.position()
        updateProgressBars(pos, dur)

        // Album Art (only triggers when active track changes)
        val artKey = "local:${current.absolutePath}"
        if (artKey != lastArtKey) {
            lastArtKey = artKey
            AlbumArtLoader.loadInto(current, miniArt, miniArtFallback, targetPx = 160)
            AlbumArtLoader.loadInto(current, npArt, npArtFallback, targetPx = 640)
        }

        if (current != lastTrack) {
            lastTrack = current
            adapter.setActiveFile(current)
        }
    }

    private fun bindYouTubePlayerUi() {
        val ytState = YouTubePlaybackService.currentState
        val ytSvc = YouTubePlaybackService.instance
        val item = ytState.currentItem ?: return
        val info = ytState.streamInfo

        val title = info?.title?.takeIf { it.isNotBlank() } ?: item.title
        val channel = info?.channel?.takeIf { it.isNotBlank() }
            ?: item.channel.ifBlank { "YouTube Audio" }
        val playing = ytState.isPlaying

        // Mini-player with small YouTube Audio indicator + direct "Watch Video ↗" button
        miniYtBanner.visibility = View.VISIBLE
        miniTitle.text = title
        miniSub.text = channel
        miniPlayPause.text = if (playing) "⏸" else "▶"

        // Full-Screen Now Playing with YouTube Audio field
        npSourceLabel.text = "🎧 YOUTUBE AUDIO MODE"
        npYoutubeBar.visibility = View.VISIBLE
        npTitle.text = title
        npArtist.text = channel
        npPlayPause.text = if (playing) "⏸" else "▶"
        npShuffle.visibility = View.INVISIBLE
        npRepeat.visibility = View.INVISIBLE
        npBtnAddPlaylist.visibility = View.GONE

        // Live ExoPlayer progress
        val dur = ytSvc?.durationMs()?.toInt() ?: 0
        val pos = ytSvc?.positionMs()?.toInt() ?: 0
        updateProgressBars(pos, dur)

        // YouTube Thumbnail as Album Art (reuses existing disk-cached ThumbnailLoader)
        val thumbUrl = info?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: item.thumbnailUrl
        val artKey = "yt:$thumbUrl"
        if (artKey != lastArtKey) {
            lastArtKey = artKey
            if (thumbUrl.isNotBlank()) {
                miniArt.visibility = View.VISIBLE
                miniArtFallback.visibility = View.GONE
                npArt.visibility = View.VISIBLE
                npArtFallback.visibility = View.GONE
                ThumbnailLoader.load(thumbUrl, miniArt, targetWidthPx = 200)
                ThumbnailLoader.load(thumbUrl, npArt, targetWidthPx = 640)
            } else {
                clearArtwork()
            }
        }

        if (lastTrack != null) {
            lastTrack = null
            adapter.setActiveFile(null)
        }
    }

    private fun bindIdlePlayerUi() {
        miniYtBanner.visibility = View.GONE
        miniTitle.text = getString(R.string.player_tap_to_play)
        miniSub.text = "Voice notes & call recordings auto-hidden"
        miniPlayPause.text = "▶"
        miniProgress.progress = 0

        npYoutubeBar.visibility = View.GONE
        npSourceLabel.text = "NOW PLAYING"
        npTitle.text = getString(R.string.player_tap_to_play)
        npArtist.text = "Pick a song from Folders or Playlists"
        npPlayPause.text = "▶"
        npShuffle.visibility = View.VISIBLE
        npRepeat.visibility = View.VISIBLE
        npBtnAddPlaylist.visibility = View.GONE
        updateProgressBars(0, 0)

        if (lastArtKey != null) {
            lastArtKey = null
            clearArtwork()
        }
    }

    private fun clearArtwork() {
        miniArt.setImageDrawable(null)
        miniArt.visibility = View.GONE
        miniArtFallback.visibility = View.VISIBLE
        npArt.setImageDrawable(null)
        npArt.visibility = View.GONE
        npArtFallback.visibility = View.VISIBLE
    }

    private fun updateProgressBars(posMs: Int, durMs: Int) {
        if (durMs > 0) {
            npSeek.max = durMs
            if (!draggingSeek) {
                npSeek.progress = posMs.coerceIn(0, durMs)
            }
            npTimeCur.text = formatTime(posMs)
            npTimeDur.text = formatTime(durMs)
            miniProgress.max = durMs
            miniProgress.progress = posMs.coerceIn(0, durMs)
        } else {
            npSeek.max = 100
            if (!draggingSeek) npSeek.progress = 0
            npTimeCur.text = "0:00"
            npTimeDur.text = "0:00"
            miniProgress.progress = 0
        }
    }

    // ---- Transport Actions ----

    private fun handlePlayPause() {
        when (resolveActiveSource()) {
            ActiveSource.YOUTUBE -> YouTubePlaybackService.instance?.togglePlayPause()
            ActiveSource.LOCAL -> {
                val svc = service
                if (bound && svc != null) {
                    if (svc.isPlaying()) svc.pause() else svc.resume()
                } else {
                    sendControl(PlayerService.ACTION_RESUME)
                }
            }
            ActiveSource.NONE -> {
                Toast.makeText(this, "Tap any song or folder to start playing", Toast.LENGTH_SHORT).show()
            }
        }
        updatePlayerUi()
    }

    private fun handleNext() {
        when (resolveActiveSource()) {
            ActiveSource.YOUTUBE -> YouTubePlaybackService.instance?.playNext()
            ActiveSource.LOCAL -> sendControl(PlayerService.ACTION_NEXT)
            ActiveSource.NONE -> Unit
        }
    }

    private fun handlePrev() {
        when (resolveActiveSource()) {
            ActiveSource.YOUTUBE -> YouTubePlaybackService.instance?.playPrevious()
            ActiveSource.LOCAL -> sendControl(PlayerService.ACTION_PREV)
            ActiveSource.NONE -> Unit
        }
    }

    private fun openCurrentYouTubeVideo() {
        val intent = Intent(this, YouTubePlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("restore_video", true)
        }
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    // ---- Full-Screen Now Playing Sheet Open / Close / Swipe ----

    private fun openNowPlayingSheet(animate: Boolean = true) {
        if (resolveActiveSource() == ActiveSource.NONE) {
            Toast.makeText(this, "Tap a song first to open Now Playing", Toast.LENGTH_SHORT).show()
            return
        }
        if (isNowPlayingOpen) return
        isNowPlayingOpen = true
        nowPlayingSheet.visibility = View.VISIBLE
        if (animate) {
            nowPlayingSheet.translationY = resources.displayMetrics.heightPixels.toFloat() * 0.25f
            nowPlayingSheet.alpha = 0f
            nowPlayingSheet.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(220L)
                .start()
        } else {
            nowPlayingSheet.translationY = 0f
            nowPlayingSheet.alpha = 1f
        }
        updatePlayerUi()
    }

    private fun closeNowPlayingSheet() {
        if (!isNowPlayingOpen) return
        isNowPlayingOpen = false
        nowPlayingSheet.animate()
            .translationY(resources.displayMetrics.heightPixels.toFloat() * 0.22f)
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                nowPlayingSheet.visibility = View.GONE
                nowPlayingSheet.translationY = 0f
                nowPlayingSheet.alpha = 1f
            }
            .start()
    }

    private fun setupSwipeDownToCollapse(target: View) {
        var downY = 0f
        val threshold = 72f * resources.displayMetrics.density
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val dy = event.rawY - downY
                    if (dy > threshold) {
                        closeNowPlayingSheet()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    // ---- Up Next Queue Dialog (Local + YouTube) ----

    private fun showQueueDialog() {
        when (resolveActiveSource()) {
            ActiveSource.LOCAL -> {
                val svc = service ?: return
                val list = svc.currentPlaylist()
                if (list.isEmpty()) {
                    Toast.makeText(this, "Queue is empty", Toast.LENGTH_SHORT).show()
                    return
                }
                val curIdx = svc.currentIndex()
                val labels = list.mapIndexed { idx, file ->
                    val prefix = if (idx == curIdx) "▶ " else "${idx + 1}. "
                    "$prefix${file.nameWithoutExtension}"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Up Next (${list.size})")
                    .setItems(labels) { _, which ->
                        svc.playAtIndex(which)
                        updatePlayerUi()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
            ActiveSource.YOUTUBE -> {
                val ytState = YouTubePlaybackService.currentState
                val q = ytState.queue
                if (q.isEmpty()) {
                    Toast.makeText(this, "YouTube queue is empty", Toast.LENGTH_SHORT).show()
                    return
                }
                val curIdx = ytState.queueIndex
                val labels = q.mapIndexed { idx, item ->
                    val prefix = if (idx == curIdx) "▶ " else "${idx + 1}. "
                    "$prefix${item.title}"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("YouTube Queue (${q.size})")
                    .setItems(labels) { _, which ->
                        YouTubePlaybackService.instance?.playQueue(q, which, audioOnly = ytState.isAudioOnly)
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
            ActiveSource.NONE -> {
                Toast.makeText(this, "Nothing in queue", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun audioReadPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    // ---- Equalizer (works for BOTH local music & YouTube audio!) ----

    private fun openSystemEqualizer() {
        val session = when (resolveActiveSource()) {
            ActiveSource.YOUTUBE -> YouTubePlaybackService.instance?.audioSessionId() ?: 0
            else -> service?.audioSessionId() ?: 0
        }
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            if (session != 0) {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
            }
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No system equalizer app found on this device", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open equalizer", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Custom Playlists Storage ----

    private fun playlistsFile(): File = File(filesDir, "music_playlists.json")

    private fun loadPlaylists(): MutableList<MusicPlaylist> {
        val f = playlistsFile()
        if (!f.isFile) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val result = mutableListOf<MusicPlaylist>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isBlank()) continue
                val pArr = obj.optJSONArray("paths") ?: JSONArray()
                val paths = mutableListOf<String>()
                for (j in 0 until pArr.length()) {
                    val p = pArr.optString(j)
                    if (p.isNotBlank()) paths.add(p)
                }
                result.add(MusicPlaylist(name, paths))
            }
            result
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun savePlaylists() {
        try {
            val arr = JSONArray()
            for (pl in playlists) {
                val obj = JSONObject()
                obj.put("name", pl.name)
                val pArr = JSONArray()
                for (p in pl.paths) pArr.put(p)
                obj.put("paths", pArr)
                arr.put(obj)
            }
            playlistsFile().writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun resolvePlaylistTracks(pl: MusicPlaylist): List<AudioTrack> {
        val internalRoot = Environment.getExternalStorageDirectory().absolutePath
        return pl.paths.mapNotNull { path ->
            tracksByPath[path] ?: File(path).takeIf { it.isFile }?.let { f ->
                val parent = f.parentFile
                AudioTrack(
                    file = f,
                    folderPath = parent?.absolutePath ?: "",
                    folderName = parent?.name ?: "Storage",
                    isSdCard = !f.absolutePath.startsWith(internalRoot),
                    durationMs = 0L
                )
            }
        }
    }

    private fun showCreatePlaylistDialog(initialTrack: AudioTrack?) {
        val input = EditText(this).apply {
            hint = "Playlist name (e.g. Gym, Roadtrip)"
            setSingleLine(true)
            setPadding(48, 32, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                if (playlists.any { it.name.equals(name, ignoreCase = true) }) {
                    Toast.makeText(this, "A playlist named \"$name\" already exists", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val paths = mutableListOf<String>()
                if (initialTrack != null) paths.add(initialTrack.file.absolutePath)
                playlists.add(MusicPlaylist(name, paths))
                savePlaylists()
                Toast.makeText(
                    this,
                    if (initialTrack != null) "Added to \"$name\"" else "Created \"$name\"",
                    Toast.LENGTH_SHORT
                ).show()
                renderCurrentView()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddToPlaylistDialog(track: AudioTrack) {
        val items = playlists.map { "${it.name} (${it.paths.size})" } + "+ Create New Playlist…"
        AlertDialog.Builder(this)
            .setTitle("Add to Playlist")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == playlists.size) {
                    showCreatePlaylistDialog(initialTrack = track)
                } else {
                    val pl = playlists[which]
                    val path = track.file.absolutePath
                    if (path !in pl.paths) {
                        pl.paths.add(path)
                        savePlaylists()
                        Toast.makeText(this, "Added to \"${pl.name}\"", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Already in \"${pl.name}\"", Toast.LENGTH_SHORT).show()
                    }
                    renderCurrentView()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPlaylistMenu(pl: MusicPlaylist) {
        val options = arrayOf("Play Playlist", "Rename Playlist", "Delete Playlist")
        AlertDialog.Builder(this)
            .setTitle(pl.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val tracks = resolvePlaylistTracks(pl)
                        if (tracks.isNotEmpty()) playFolderTracks(tracks, 0, pl.name)
                        else Toast.makeText(this, "Playlist is empty", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val input = EditText(this).apply {
                            setText(pl.name)
                            setSingleLine(true)
                            setPadding(48, 32, 48, 24)
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Rename Playlist")
                            .setView(input)
                            .setPositiveButton("Save") { _, _ ->
                                val newName = input.text.toString().trim()
                                if (newName.isBlank()) return@setPositiveButton
                                val idx = playlists.indexOf(pl)
                                if (idx >= 0) {
                                    playlists[idx] = MusicPlaylist(newName, pl.paths)
                                    if (openPlaylistName == pl.name) openPlaylistName = newName
                                    savePlaylists()
                                    renderCurrentView()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    2 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Delete \"${pl.name}\"?")
                            .setMessage("Your audio files will NOT be deleted — only this playlist.")
                            .setPositiveButton("Delete") { _, _ ->
                                playlists.remove(pl)
                                if (openPlaylistName == pl.name) {
                                    openPlaylistName = null
                                    viewMode = ViewMode.PLAYLISTS
                                }
                                savePlaylists()
                                renderCurrentView()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .show()
    }

    // ---- Long-Press Menus for Songs & Folders ----

    private fun showTrackMenu(track: AudioTrack, inPlaylistName: String?) {
        val items = mutableListOf("Add to Playlist")
        if (inPlaylistName != null) items.add("Remove from \"$inPlaylistName\"")
        items.add("Share")
        items.add("Delete File")

        AlertDialog.Builder(this)
            .setTitle(track.file.nameWithoutExtension)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Add to Playlist" -> showAddToPlaylistDialog(track)
                    "Remove from \"$inPlaylistName\"" -> {
                        val pl = playlists.firstOrNull { it.name == inPlaylistName }
                        if (pl != null) {
                            pl.paths.remove(track.file.absolutePath)
                            savePlaylists()
                            Toast.makeText(this, "Removed from \"${pl.name}\"", Toast.LENGTH_SHORT).show()
                            renderCurrentView()
                        }
                    }
                    "Share" -> shareTrack(track)
                    "Delete File" -> confirmDeleteTrack(track)
                }
            }
            .show()
    }

    private fun showFolderMenu(folder: MusicFolder) {
        val items = arrayOf("Play Folder", "Hide Folder")
        AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> playFolderTracks(folder.tracks, 0, folder.name)
                    1 -> {
                        val prefs = getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE)
                        val hidden = (prefs.getStringSet(KEY_HIDDEN_FOLDERS, emptySet()) ?: emptySet()).toMutableSet()
                        val included = (prefs.getStringSet(KEY_INCLUDED_FOLDERS, emptySet()) ?: emptySet()).toMutableSet()
                        hidden.add(folder.path)
                        included.remove(folder.path)
                        prefs.edit()
                            .putStringSet(KEY_HIDDEN_FOLDERS, hidden)
                            .putStringSet(KEY_INCLUDED_FOLDERS, included)
                            .apply()
                        Toast.makeText(this, "Hidden \"${folder.name}\" (restore via ⚙ Filter)", Toast.LENGTH_SHORT).show()
                        renderCurrentView()
                    }
                }
            }
            .show()
    }

    private fun shareTrack(track: AudioTrack) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", track.file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share song"))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not share this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteTrack(track: AudioTrack) {
        AlertDialog.Builder(this)
            .setTitle("Delete Song?")
            .setMessage("Permanently delete \"${track.file.name}\" from storage?")
            .setPositiveButton("Delete") { _, _ ->
                var deleted = track.file.delete()
                if (!deleted) {
                    try {
                        val rows = contentResolver.delete(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            "${MediaStore.Audio.Media.DATA} = ?",
                            arrayOf(track.file.absolutePath)
                        )
                        deleted = rows > 0 || !track.file.exists()
                    } catch (_: Exception) {
                    }
                }
                if (deleted) {
                    for (pl in playlists) pl.paths.remove(track.file.absolutePath)
                    savePlaylists()
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    loadLibrary()
                } else {
                    Toast.makeText(
                        this,
                        "Android blocked deleting this file (delete it from Files app)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Scanning Internal Storage + SD Card & Filtering Recordings ----

    @Suppress("DEPRECATION")
    private fun loadLibrary() {
        val internalRoot = Environment.getExternalStorageDirectory().absolutePath
        val byPath = LinkedHashMap<String, AudioTrack>()

        try {
            val projection = arrayOf(
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION
            )
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                MediaStore.Audio.Media.DATE_MODIFIED + " DESC"
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val durMs = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    if (path.isNullOrBlank()) continue
                    val file = File(path)
                    if (!file.isFile || !isMusicFile(file.name)) continue
                    if (durMs in 1L..11_999L) continue
                    val parent = file.parentFile ?: continue
                    val isSd = !file.absolutePath.startsWith(internalRoot)
                    byPath[file.absolutePath] = AudioTrack(
                        file = file,
                        folderPath = parent.absolutePath,
                        folderName = parent.name.ifBlank { "Storage" },
                        isSdCard = isSd,
                        durationMs = durMs
                    )
                }
            }
        } catch (_: Exception) {
        }

        val volumeRoots = mutableSetOf(Environment.getExternalStorageDirectory())
        for (dir in getExternalFilesDirs(null)) {
            if (dir == null) continue
            val root = generateSequence(dir) { it.parentFile }.elementAtOrNull(4)
            if (root != null) volumeRoots.add(root)
        }
        for (vol in volumeRoots) {
            val grabDir = File(File(vol, Environment.DIRECTORY_DOWNLOADS), "GrabAll")
            if (!grabDir.isDirectory) continue
            grabDir.walkTopDown()
                .filter { it.isFile && isMusicFile(it.name) }
                .forEach { file ->
                    if (!byPath.containsKey(file.absolutePath)) {
                        val parent = file.parentFile ?: grabDir
                        val isSd = !file.absolutePath.startsWith(internalRoot)
                        byPath[file.absolutePath] = AudioTrack(
                            file = file,
                            folderPath = parent.absolutePath,
                            folderName = parent.name.ifBlank { "GrabAll" },
                            isSdCard = isSd,
                            durationMs = 0L
                        )
                    }
                }
        }

        tracksByPath = byPath

        val grouped = byPath.values.groupBy { it.folderPath }
        allFolders = grouped.map { (path, tracks) ->
            val first = tracks.first()
            MusicFolder(
                path = path,
                name = first.folderName,
                isSdCard = first.isSdCard,
                tracks = tracks.sortedByDescending { it.file.lastModified() },
                isAutoExcluded = isRecordingOrSystemFolder(path)
            )
        }.sortedWith(
            compareByDescending<MusicFolder> { it.path.contains("/GrabAll", ignoreCase = true) }
                .thenByDescending { it.tracks.size }
                .thenBy { it.name.lowercase() }
        )

        renderCurrentView()
    }

    private fun isMusicFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp3", "m4a", "opus", "ogg", "wav", "flac", "aac")
    }

    private fun isRecordingOrSystemFolder(folderPath: String): Boolean {
        val lower = folderPath.lowercase()
        if (lower.contains("/graball")) return false
        return RECORDING_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    // ---- Folder Inclusion / Exclusion Preferences ----

    private fun isFolderVisible(folder: MusicFolder): Boolean {
        val prefs = getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE)
        val forcedIncluded = prefs.getStringSet(KEY_INCLUDED_FOLDERS, emptySet()) ?: emptySet()
        val forcedHidden = prefs.getStringSet(KEY_HIDDEN_FOLDERS, emptySet()) ?: emptySet()
        if (folder.path in forcedHidden) return false
        if (folder.path in forcedIncluded) return true
        return !folder.isAutoExcluded
    }

    private fun showManageFoldersDialog() {
        if (allFolders.isEmpty()) {
            Toast.makeText(this, "No audio folders found yet", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = allFolders.map { f ->
            val tag = buildString {
                if (f.isSdCard) append("SD · ")
                append("${f.tracks.size} files")
                if (f.isAutoExcluded) append(" · Recording/System")
            }
            "${f.name}\n($tag)"
        }.toTypedArray()
        val checked = BooleanArray(allFolders.size) { i -> isFolderVisible(allFolders[i]) }

        AlertDialog.Builder(this)
            .setTitle("Choose Music Folders")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                val included = mutableSetOf<String>()
                val hidden = mutableSetOf<String>()
                allFolders.forEachIndexed { idx, f ->
                    if (checked[idx]) {
                        if (f.isAutoExcluded) included.add(f.path)
                    } else {
                        hidden.add(f.path)
                    }
                }
                getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE).edit()
                    .putStringSet(KEY_INCLUDED_FOLDERS, included)
                    .putStringSet(KEY_HIDDEN_FOLDERS, hidden)
                    .apply()
                renderCurrentView()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Rendering ----

    private fun renderCurrentView() {
        val visibleFolders = allFolders.filter { isFolderVisible(it) }
        val q = searchQuery.lowercase()

        val foldersSelected = viewMode == ViewMode.FOLDERS || viewMode == ViewMode.FOLDER_SONGS
        val playlistsSelected = viewMode == ViewMode.PLAYLISTS || viewMode == ViewMode.PLAYLIST_SONGS
        val allSelected = viewMode == ViewMode.ALL_MUSIC

        tabFolders.setBackgroundResource(if (foldersSelected) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
        tabFolders.setTextColor(ContextCompat.getColor(this, if (foldersSelected) R.color.graball_text else R.color.graball_subtext))
        tabPlaylists.setBackgroundResource(if (playlistsSelected) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
        tabPlaylists.setTextColor(ContextCompat.getColor(this, if (playlistsSelected) R.color.graball_text else R.color.graball_subtext))
        tabAll.setBackgroundResource(if (allSelected) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
        tabAll.setTextColor(ContextCompat.getColor(this, if (allSelected) R.color.graball_text else R.color.graball_subtext))

        when (viewMode) {
            ViewMode.FOLDERS -> {
                tabsRow.visibility = View.VISIBLE
                btnNewPlaylist.visibility = View.GONE
                folderHeaderBar.visibility = View.GONE
                val filtered = if (q.isEmpty()) {
                    visibleFolders
                } else {
                    visibleFolders.filter {
                        it.name.lowercase().contains(q) ||
                            it.tracks.any { t -> t.file.name.lowercase().contains(q) }
                    }
                }
                val rows = filtered.map { MusicRow.FolderRow(it) }
                adapter.submitRows(rows, service?.currentFile())
                showEmptyIfNeeded(
                    rows.isEmpty(),
                    if (allFolders.isNotEmpty()) {
                        "No music folders visible — tap ⚙ Filter above to choose folders"
                    } else {
                        getString(R.string.player_empty)
                    }
                )
            }
            ViewMode.FOLDER_SONGS -> {
                val folder = allFolders.firstOrNull { it.path == openFolderPath }
                if (folder == null) {
                    viewMode = ViewMode.FOLDERS
                    renderCurrentView()
                    return
                }
                tabsRow.visibility = View.GONE
                btnNewPlaylist.visibility = View.GONE
                folderHeaderBar.visibility = View.VISIBLE
                btnFolderBack.text = "← Folders"
                val volTag = if (folder.isSdCard) "SD · " else ""
                folderHeaderName.text = "$volTag${folder.name} (${folder.tracks.size})"
                val tracks = if (q.isEmpty()) {
                    folder.tracks
                } else {
                    folder.tracks.filter { it.file.name.lowercase().contains(q) }
                }
                val rows = tracks.mapIndexed { idx, t ->
                    MusicRow.TrackRow(t, tracks, idx, folder.name, inPlaylistName = null)
                }
                adapter.submitRows(rows, service?.currentFile())
                showEmptyIfNeeded(rows.isEmpty(), "No matching songs in this folder")
            }
            ViewMode.PLAYLISTS -> {
                tabsRow.visibility = View.VISIBLE
                btnNewPlaylist.visibility = View.VISIBLE
                folderHeaderBar.visibility = View.GONE
                val filtered = if (q.isEmpty()) {
                    playlists
                } else {
                    playlists.filter { it.name.lowercase().contains(q) }
                }
                val rows = filtered.map { pl ->
                    MusicRow.PlaylistRow(pl, resolvePlaylistTracks(pl).size)
                }
                adapter.submitRows(rows, service?.currentFile())
                showEmptyIfNeeded(
                    rows.isEmpty(),
                    "No playlists yet — tap + New Playlist or long-press any song to add it"
                )
            }
            ViewMode.PLAYLIST_SONGS -> {
                val pl = playlists.firstOrNull { it.name == openPlaylistName }
                if (pl == null) {
                    viewMode = ViewMode.PLAYLISTS
                    renderCurrentView()
                    return
                }
                tabsRow.visibility = View.GONE
                btnNewPlaylist.visibility = View.GONE
                folderHeaderBar.visibility = View.VISIBLE
                btnFolderBack.text = "← Playlists"
                val allTracks = resolvePlaylistTracks(pl)
                folderHeaderName.text = "📋 ${pl.name} (${allTracks.size})"
                val tracks = if (q.isEmpty()) {
                    allTracks
                } else {
                    allTracks.filter { it.file.name.lowercase().contains(q) }
                }
                val rows = tracks.mapIndexed { idx, t ->
                    MusicRow.TrackRow(t, tracks, idx, pl.name, inPlaylistName = pl.name)
                }
                adapter.submitRows(rows, service?.currentFile())
                showEmptyIfNeeded(
                    rows.isEmpty(),
                    "Playlist is empty — long-press any song in Folders or All Music to add it"
                )
            }
            ViewMode.ALL_MUSIC -> {
                tabsRow.visibility = View.VISIBLE
                btnNewPlaylist.visibility = View.GONE
                folderHeaderBar.visibility = View.GONE
                val allTracks = visibleFolders.flatMap { it.tracks }
                    .sortedByDescending { it.file.lastModified() }
                val tracks = if (q.isEmpty()) {
                    allTracks
                } else {
                    allTracks.filter {
                        it.file.name.lowercase().contains(q) ||
                            it.folderName.lowercase().contains(q)
                    }
                }
                val rows = tracks.mapIndexed { idx, t ->
                    MusicRow.TrackRow(t, tracks, idx, "All Music", inPlaylistName = null)
                }
                adapter.submitRows(rows, service?.currentFile())
                showEmptyIfNeeded(rows.isEmpty(), getString(R.string.player_empty))
            }
        }
    }

    private fun showEmptyIfNeeded(isEmpty: Boolean, message: String) {
        if (isEmpty) {
            emptyView.text = message
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
        }
    }

    private fun playFolderTracks(tracks: List<AudioTrack>, startIndex: Int, folderLabel: String) {
        if (tracks.isEmpty()) return
        YouTubePlaybackService.instance?.pauseIfPlaying()
        startService(
            Intent(this, PlayerService::class.java).apply {
                action = PlayerService.ACTION_PLAY
                putStringArrayListExtra(
                    PlayerService.EXTRA_PATHS,
                    ArrayList(tracks.map { it.file.absolutePath })
                )
                putExtra(PlayerService.EXTRA_INDEX, startIndex)
                putExtra(PlayerService.EXTRA_FOLDER_LABEL, folderLabel)
            }
        )
    }

    private fun sendControl(action: String) {
        startService(Intent(this, PlayerService::class.java).setAction(action))
    }

    private fun updateShuffleRepeatUi(svc: PlayerService) {
        npShuffle.setBackgroundResource(
            if (svc.isShuffleEnabled()) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg
        )
        npShuffle.setTextColor(
            ContextCompat.getColor(
                this,
                if (svc.isShuffleEnabled()) R.color.graball_text else R.color.graball_subtext
            )
        )
        when (svc.getRepeatMode()) {
            PlayerService.RepeatMode.OFF -> {
                npRepeat.text = "🔁"
                npRepeat.setBackgroundResource(R.drawable.yt_chip_bg)
                npRepeat.setTextColor(ContextCompat.getColor(this, R.color.graball_subtext))
            }
            PlayerService.RepeatMode.ALL -> {
                npRepeat.text = "🔁"
                npRepeat.setBackgroundResource(R.drawable.yt_chip_selected)
                npRepeat.setTextColor(ContextCompat.getColor(this, R.color.graball_text))
            }
            PlayerService.RepeatMode.ONE -> {
                npRepeat.text = "🔂"
                npRepeat.setBackgroundResource(R.drawable.yt_chip_selected)
                npRepeat.setTextColor(ContextCompat.getColor(this, R.color.graball_text))
            }
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    // ---- Adapter ----

    private sealed class MusicRow {
        data class FolderRow(val folder: MusicFolder) : MusicRow()
        data class PlaylistRow(val playlist: MusicPlaylist, val trackCount: Int) : MusicRow()
        data class TrackRow(
            val track: AudioTrack,
            val scopeList: List<AudioTrack>,
            val indexInScope: Int,
            val scopeLabel: String,
            val inPlaylistName: String?
        ) : MusicRow()
    }

    private inner class MusicRowAdapter : RecyclerView.Adapter<MusicRowAdapter.Holder>() {
        private var rows: List<MusicRow> = emptyList()
        private var activeFile: File? = null

        fun submitRows(newRows: List<MusicRow>, current: File?) {
            rows = newRows
            activeFile = current
            notifyDataSetChanged()
        }

        fun setActiveFile(current: File?) {
            activeFile = current
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_music_row, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val row = rows[position]) {
                is MusicRow.FolderRow -> {
                    val f = row.folder
                    holder.icon.text = if (f.isSdCard) "💾" else "📁"
                    holder.title.text = f.name
                    holder.title.setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.graball_text))
                    val storageLbl = if (f.isSdCard) "SD Card" else "Internal"
                    holder.sub.text = "$storageLbl · ${f.tracks.size} songs"
                    holder.action.text = "▶ Play"
                    holder.action.visibility = View.VISIBLE
                    holder.itemView.setOnClickListener {
                        openFolderPath = f.path
                        viewMode = ViewMode.FOLDER_SONGS
                        renderCurrentView()
                    }
                    holder.itemView.setOnLongClickListener {
                        showFolderMenu(f)
                        true
                    }
                    holder.action.setOnClickListener {
                        playFolderTracks(f.tracks, 0, f.name)
                    }
                }
                is MusicRow.PlaylistRow -> {
                    val pl = row.playlist
                    holder.icon.text = "📋"
                    holder.title.text = pl.name
                    holder.title.setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.graball_text))
                    holder.sub.text = "${row.trackCount} songs · Long-press to manage"
                    holder.action.text = "▶ Play"
                    holder.action.visibility = View.VISIBLE
                    holder.itemView.setOnClickListener {
                        openPlaylistName = pl.name
                        viewMode = ViewMode.PLAYLIST_SONGS
                        renderCurrentView()
                    }
                    holder.itemView.setOnLongClickListener {
                        showPlaylistMenu(pl)
                        true
                    }
                    holder.action.setOnClickListener {
                        val tracks = resolvePlaylistTracks(pl)
                        if (tracks.isNotEmpty()) playFolderTracks(tracks, 0, pl.name)
                        else Toast.makeText(this@PlayerActivity, "Playlist is empty", Toast.LENGTH_SHORT).show()
                    }
                }
                is MusicRow.TrackRow -> {
                    val t = row.track
                    val isPlaying = t.file == activeFile
                    holder.icon.text = if (isPlaying) "♫" else "🎵"
                    holder.title.text = t.file.nameWithoutExtension
                    holder.title.setTextColor(
                        ContextCompat.getColor(
                            this@PlayerActivity,
                            if (isPlaying) R.color.graball_orange else R.color.graball_text
                        )
                    )
                    val durStr = if (t.durationMs > 0) " · ${formatTime(t.durationMs.toInt())}" else ""
                    val volStr = if (t.isSdCard) "SD · " else ""
                    holder.sub.text = "$volStr${t.folderName}$durStr"
                    holder.action.text = if (isPlaying) "Playing" else "▶"
                    holder.action.visibility = View.VISIBLE
                    holder.itemView.setOnClickListener {
                        playFolderTracks(row.scopeList, row.indexInScope, row.scopeLabel)
                    }
                    holder.itemView.setOnLongClickListener {
                        showTrackMenu(t, row.inPlaylistName)
                        true
                    }
                    holder.action.setOnClickListener {
                        playFolderTracks(row.scopeList, row.indexInScope, row.scopeLabel)
                    }
                }
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: TextView = view.findViewById(R.id.musicRowIcon)
            val title: TextView = view.findViewById(R.id.musicRowTitle)
            val sub: TextView = view.findViewById(R.id.musicRowSub)
            val action: TextView = view.findViewById(R.id.musicRowAction)
        }
    }

    companion object {
        private const val MUSIC_PREFS = "graball_music_folders"
        private const val KEY_INCLUDED_FOLDERS = "included_folders"
        private const val KEY_HIDDEN_FOLDERS = "hidden_folders"

        private val RECORDING_KEYWORDS = listOf(
            "whatsapp voice notes",
            "whatsapp audio",
            "call recording",
            "call_rec",
            "callrec",
            "/recordings",
            "/recording",
            "voice recorder",
            "voicerecorder",
            "soundrecorder",
            "sound_recorder",
            "/recorder",
            "/alarms",
            "/notifications",
            "/ringtones",
            "/.statuses",
            "telegram audio",
            "miui/sound_recorder"
        )
    }
}
