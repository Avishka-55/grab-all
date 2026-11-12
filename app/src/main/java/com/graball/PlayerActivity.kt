package com.graball

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

/**
 * GrabAll's own music player (v0.7.0). Lists the audio files in
 * Downloads/GrabAll; tapping one plays it through PlayerService, which
 * keeps playing in the background with notification controls.
 * Local files only — no YouTube streaming.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPause: Button
    private var files: List<File> = emptyList()
    private var service: PlayerService? = null
    private var bound = false
    private var dragging = false
    private var lastTrack: File? = null

    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            val svc = service
            if (bound && svc != null) {
                val current = svc.currentFile()
                // Idle line is "tap a song", NOT the empty-list text:
                // the old shared string told him "No audio yet" even
                // with a full list on screen, until he tapped one.
                nowPlaying.text = current?.name ?: getString(R.string.player_tap_to_play)
                playPause.text = if (svc.isPlaying()) "⏸" else "▶"
                val dur = svc.duration()
                if (dur > 0) {
                    seekBar.max = dur
                    if (!dragging) {
                        seekBar.progress = svc.position()
                    }
                }
                if (current != lastTrack) {
                    lastTrack = current
                    (listView.adapter as? PlayerAdapter)?.notifyDataSetChanged()
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                loadFiles()
            } else {
                emptyView.text = getString(R.string.player_need_permission)
                emptyView.visibility = View.VISIBLE
            }
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PlayerService.PlayerBinder).getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        listView = findViewById(R.id.playerList)
        emptyView = findViewById(R.id.playerEmpty)
        nowPlaying = findViewById(R.id.playerNowPlaying)
        seekBar = findViewById(R.id.playerSeek)
        playPause = findViewById(R.id.playerPlayPause)
        listView.emptyView = emptyView
        findViewById<LinearLayout>(R.id.navGrab).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }
        findViewById<LinearLayout>(R.id.navDownloads).setOnClickListener {
            startActivity(
                Intent(this, DownloadsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }
        findViewById<LinearLayout>(R.id.navSettings).setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }
        findViewById<Button>(R.id.playerPrev).setOnClickListener { sendControl(PlayerService.ACTION_PREV) }
        findViewById<Button>(R.id.playerNext).setOnClickListener { sendControl(PlayerService.ACTION_NEXT) }
        findViewById<Button>(R.id.playerStop).setOnClickListener { sendControl(PlayerService.ACTION_STOP) }
        playPause.setOnClickListener {
            val svc = service
            if (bound && svc != null) {
                if (svc.isPlaying()) svc.pause() else svc.resume()
            } else {
                sendControl(PlayerService.ACTION_RESUME)
            }
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seek: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(seek: SeekBar) {
                dragging = false
                service?.seekTo(seek.progress)
            }
        })
        listView.setOnItemClickListener { _, _, position, _ ->
            playAt(position)
        }
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            loadFiles()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    override fun onResume() {
        super.onResume()
        bindService(
            Intent(this, PlayerService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        handler.post(poller)
        // Re-scan on every return: a song downloaded since this
        // screen was last open must show up without restarting the
        // app — the old onCreate-only scan left a stale empty list.
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            loadFiles()
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
    private fun loadFiles() {
        // v0.7.4: scan the whole phone for music via MediaStore AND
        // the Downloads/GrabAll folder, merged — not either/or.
        // Two fixes in one: the old IS_MUSIC!=0 filter drops audio
        // files the media scanner never flagged as music (very
        // common for files in Downloads), and the old MediaStore-
        // only-or-folder-only choice could come up empty while the
        // other source had the files.
        val found = mutableListOf<File>()
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DISPLAY_NAME
            )
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                MediaStore.Audio.Media.DATE_MODIFIED + " DESC"
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                    if (!path.isNullOrEmpty()) {
                        val f = File(path)
                        if (f.isFile && isAudio(f.name)) {
                            found.add(f)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "GrabAll"
        )
        dir.listFiles()
            ?.filter { it.isFile && isAudio(it.name) }
            ?.let { found.addAll(it) }
        files = found.distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
        if (files.isEmpty()) {
            emptyView.text = getString(R.string.player_empty)
        }
        listView.adapter = PlayerAdapter()
    }

    private fun isAudio(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp3", "m4a", "opus", "ogg", "wav", "flac")
    }

    private fun playAt(position: Int) {
        // Always START the service, never just bind to it: a
        // bound-only service dies the moment this activity unbinds
        // (onPause, i.e. every window switch), which is exactly why
        // music kept stopping when he changed screens. A started
        // service keeps playing until Stop, whatever the UI does.
        startService(
            Intent(this, PlayerService::class.java).apply {
                action = PlayerService.ACTION_PLAY
                putStringArrayListExtra(
                    PlayerService.EXTRA_PATHS,
                    ArrayList(files.map { it.absolutePath })
                )
                putExtra(PlayerService.EXTRA_INDEX, position)
            }
        )
    }

    private fun sendControl(action: String) {
        // Works whether bound or not: the service is a started service,
        // so an action intent reaches it even with no activity bound.
        startService(Intent(this, PlayerService::class.java).setAction(action))
    }

    private inner class PlayerAdapter : BaseAdapter() {
        override fun getCount(): Int = files.size
        override fun getItem(position: Int): File = files[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@PlayerActivity)
                .inflate(R.layout.item_player, parent, false)
            val file = files[position]
            val name = view.findViewById<TextView>(R.id.playerRowName)
            name.text = file.name
            name.setTextColor(
                if (file == service?.currentFile()) {
                    ContextCompat.getColor(this@PlayerActivity, R.color.graball_orange)
                } else {
                    ContextCompat.getColor(this@PlayerActivity, R.color.graball_text)
                }
            )
            return view
        }
    }
}
