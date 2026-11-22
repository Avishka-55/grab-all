package com.graball

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * Modern folder-aware local Music Player.
 *
 * - Scans audio across BOTH internal storage and external SD card / memory card.
 * - Automatically hides voice notes, call recordings, alarms, notifications,
 *   and ringtones so you never accidentally blast a recording!
 * - Folder-first browsing: tap any folder to see & play STRICTLY that folder's
 *   songs (never jumping into other folders or recordings).
 * - "Filter Folders" dialog lets you hide/unhide any folder permanently.
 * - Includes search, Shuffle, Repeat (Off / All / One), and elapsed/total time.
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

    private enum class ViewMode { FOLDERS, FOLDER_SONGS, ALL_MUSIC }

    private lateinit var searchInput: EditText
    private lateinit var tabsRow: LinearLayout
    private lateinit var tabFolders: TextView
    private lateinit var tabAll: TextView
    private lateinit var folderHeaderBar: LinearLayout
    private lateinit var folderHeaderName: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var nowFolder: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeCur: TextView
    private lateinit var timeDur: TextView
    private lateinit var playPause: TextView
    private lateinit var shuffleBtn: TextView
    private lateinit var repeatBtn: TextView

    private var allFolders: List<MusicFolder> = emptyList()
    private var viewMode: ViewMode = ViewMode.FOLDERS
    private var openFolderPath: String? = null
    private var searchQuery: String = ""

    private var service: PlayerService? = null
    private var bound = false
    private var dragging = false
    private var lastTrack: File? = null

    private lateinit var adapter: MusicRowAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            val svc = service
            if (bound && svc != null) {
                val current = svc.currentFile()
                nowPlaying.text = current?.nameWithoutExtension
                    ?: getString(R.string.player_tap_to_play)
                val folderLbl = svc.currentFolderLabel()
                nowFolder.text = if (current != null && folderLbl.isNotBlank()) {
                    "📁 $folderLbl"
                } else {
                    "Voice notes & call recordings auto-hidden"
                }
                playPause.text = if (svc.isPlaying()) "⏸" else "▶"
                val dur = svc.duration()
                val pos = svc.position()
                if (dur > 0) {
                    seekBar.max = dur
                    if (!dragging) {
                        seekBar.progress = pos
                    }
                    timeCur.text = formatTime(pos)
                    timeDur.text = formatTime(dur)
                } else {
                    timeCur.text = "0:00"
                    timeDur.text = "0:00"
                }
                updateShuffleRepeatUi(svc)
                if (current != lastTrack) {
                    lastTrack = current
                    adapter.setActiveFile(current)
                }
            }
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
            service?.let { updateShuffleRepeatUi(it) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        searchInput = findViewById(R.id.playerSearch)
        tabsRow = findViewById(R.id.playerTabsRow)
        tabFolders = findViewById(R.id.tabMusicFolders)
        tabAll = findViewById(R.id.tabMusicAll)
        folderHeaderBar = findViewById(R.id.folderHeaderBar)
        folderHeaderName = findViewById(R.id.folderHeaderName)
        recycler = findViewById(R.id.playerRecycler)
        emptyView = findViewById(R.id.playerEmpty)
        nowPlaying = findViewById(R.id.playerNowPlaying)
        nowFolder = findViewById(R.id.playerNowFolder)
        seekBar = findViewById(R.id.playerSeek)
        timeCur = findViewById(R.id.playerTimeCur)
        timeDur = findViewById(R.id.playerTimeDur)
        playPause = findViewById(R.id.playerPlayPause)
        shuffleBtn = findViewById(R.id.playerShuffle)
        repeatBtn = findViewById(R.id.playerRepeat)

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

        // Tabs & folder controls
        tabFolders.setOnClickListener {
            viewMode = ViewMode.FOLDERS
            openFolderPath = null
            renderCurrentView()
        }
        tabAll.setOnClickListener {
            viewMode = ViewMode.ALL_MUSIC
            openFolderPath = null
            renderCurrentView()
        }
        findViewById<TextView>(R.id.btnFolderBack).setOnClickListener {
            viewMode = ViewMode.FOLDERS
            openFolderPath = null
            renderCurrentView()
        }
        findViewById<TextView>(R.id.btnFolderPlayAll).setOnClickListener {
            val folder = allFolders.firstOrNull { it.path == openFolderPath } ?: return@setOnClickListener
            if (folder.tracks.isNotEmpty()) {
                playFolderTracks(folder.tracks, 0, folder.name)
            }
        }
        findViewById<TextView>(R.id.btnManageFolders).setOnClickListener {
            showManageFoldersDialog()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                renderCurrentView()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Transport controls
        findViewById<TextView>(R.id.playerPrev).setOnClickListener { sendControl(PlayerService.ACTION_PREV) }
        findViewById<TextView>(R.id.playerNext).setOnClickListener { sendControl(PlayerService.ACTION_NEXT) }
        findViewById<TextView>(R.id.playerStop).setOnClickListener { sendControl(PlayerService.ACTION_STOP) }
        playPause.setOnClickListener {
            val svc = service
            if (bound && svc != null) {
                if (svc.isPlaying()) svc.pause() else svc.resume()
            } else {
                sendControl(PlayerService.ACTION_RESUME)
            }
        }
        shuffleBtn.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val on = svc.toggleShuffle()
            updateShuffleRepeatUi(svc)
            Toast.makeText(this, if (on) "Shuffle ON" else "Shuffle OFF", Toast.LENGTH_SHORT).show()
        }
        repeatBtn.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val mode = svc.cycleRepeatMode()
            updateShuffleRepeatUi(svc)
            val label = when (mode) {
                PlayerService.RepeatMode.OFF -> "Repeat OFF"
                PlayerService.RepeatMode.ALL -> "Repeat Folder"
                PlayerService.RepeatMode.ONE -> "Repeat One Song"
            }
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) timeCur.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(seek: SeekBar) {
                dragging = true
            }
            override fun onStopTrackingTouch(seek: SeekBar) {
                dragging = false
                service?.seekTo(seek.progress)
            }
        })

        adapter = MusicRowAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        if (ContextCompat.checkSelfPermission(this, audioReadPermission()) == PackageManager.PERMISSION_GRANTED) {
            loadLibrary()
        } else {
            permissionLauncher.launch(audioReadPermission())
        }
    }

    override fun onResume() {
        super.onResume()
        bindService(
            Intent(this, PlayerService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        handler.post(poller)
        if (ContextCompat.checkSelfPermission(this, audioReadPermission()) == PackageManager.PERMISSION_GRANTED) {
            loadLibrary()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
        if (bound) {
            unbindService(connection)
            bound = false
            service = null
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (viewMode == ViewMode.FOLDER_SONGS) {
            viewMode = ViewMode.FOLDERS
            openFolderPath = null
            renderCurrentView()
        } else {
            super.onBackPressed()
        }
    }

    private fun audioReadPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    // ---- Scanning Internal Storage + SD Card & Filtering Recordings ----

    @Suppress("DEPRECATION")
    private fun loadLibrary() {
        val internalRoot = Environment.getExternalStorageDirectory().absolutePath
        val byPath = LinkedHashMap<String, AudioTrack>()

        // 1) MediaStore scans BOTH internal storage AND external SD card automatically
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
                    // Skip tiny <12s notification/voice clips when MediaStore knows duration
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

        // 2) Direct walk of Downloads/GrabAll on EVERY volume (internal + SD card)
        //    so freshly downloaded tracks show up before MediaStore indexes them.
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

        // Group tracks by folder path
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
        // Intentionally exclude .amr / .3gp / .awb voice-memo formats
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

        // Tab styles
        val foldersSelected = viewMode == ViewMode.FOLDERS || viewMode == ViewMode.FOLDER_SONGS
        tabFolders.setBackgroundResource(if (foldersSelected) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
        tabFolders.setTextColor(ContextCompat.getColor(this, if (foldersSelected) R.color.graball_text else R.color.graball_subtext))
        tabAll.setBackgroundResource(if (viewMode == ViewMode.ALL_MUSIC) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
        tabAll.setTextColor(ContextCompat.getColor(this, if (viewMode == ViewMode.ALL_MUSIC) R.color.graball_text else R.color.graball_subtext))

        when (viewMode) {
            ViewMode.FOLDERS -> {
                tabsRow.visibility = View.VISIBLE
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
                        "No music folders visible — tap ⚙ Filter Folders above to choose folders"
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
                folderHeaderBar.visibility = View.VISIBLE
                val volTag = if (folder.isSdCard) "SD · " else ""
                folderHeaderName.text = "$volTag${folder.name} (${folder.tracks.size})"
                val tracks = if (q.isEmpty()) {
                    folder.tracks
                } else {
                    folder.tracks.filter { it.file.name.lowercase().contains(q) }
                }
                val rows = tracks.mapIndexed { idx, t -> MusicRow.TrackRow(t, tracks, idx, folder.name) }
                adapter.submitRows(rows, service?.currentFile())
                showEmptyIfNeeded(rows.isEmpty(), "No matching songs in this folder")
            }
            ViewMode.ALL_MUSIC -> {
                tabsRow.visibility = View.VISIBLE
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
                val rows = tracks.mapIndexed { idx, t -> MusicRow.TrackRow(t, tracks, idx, "All Music") }
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
        shuffleBtn.setBackgroundResource(
            if (svc.isShuffleEnabled()) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg
        )
        shuffleBtn.setTextColor(
            ContextCompat.getColor(
                this,
                if (svc.isShuffleEnabled()) R.color.graball_text else R.color.graball_subtext
            )
        )
        when (svc.getRepeatMode()) {
            PlayerService.RepeatMode.OFF -> {
                repeatBtn.text = "🔁"
                repeatBtn.setBackgroundResource(R.drawable.yt_chip_bg)
                repeatBtn.setTextColor(ContextCompat.getColor(this, R.color.graball_subtext))
            }
            PlayerService.RepeatMode.ALL -> {
                repeatBtn.text = "🔁"
                repeatBtn.setBackgroundResource(R.drawable.yt_chip_selected)
                repeatBtn.setTextColor(ContextCompat.getColor(this, R.color.graball_text))
            }
            PlayerService.RepeatMode.ONE -> {
                repeatBtn.text = "🔂"
                repeatBtn.setBackgroundResource(R.drawable.yt_chip_selected)
                repeatBtn.setTextColor(ContextCompat.getColor(this, R.color.graball_text))
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
        data class TrackRow(
            val track: AudioTrack,
            val scopeList: List<AudioTrack>,
            val indexInScope: Int,
            val scopeLabel: String
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
                    holder.action.setOnClickListener {
                        playFolderTracks(f.tracks, 0, f.name)
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
