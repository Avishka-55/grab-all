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
                nowPlaying.text = current?.name ?: getString(R.string.player_empty)
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
        // v0.7.1: scan the whole phone for music via MediaStore, not
        // just Downloads/GrabAll — his mp3/m4a files live in other
        // folders too, and v0.7.0's single-folder listing showed
        // nothing for them. Fall back to the direct folder listing
        // if the MediaStore query comes up empty.
        val found = mutableListOf<File>()
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DISPLAY_NAME
            )
            val selection = MediaStore.Audio.Media.IS_MUSIC + "!=0"
            val sort = MediaStore.Audio.Media.DATE_MODIFIED + " DESC"
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null, sort
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
        files = if (found.isNotEmpty()) {
            found.distinctBy { it.absolutePath }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "GrabAll"
            )
            dir.listFiles()
                ?.filter { it.isFile && isAudio(it.name) }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }
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
        val svc = service
        if (bound && svc != null) {
            svc.playList(files, position)
        } else {
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
