package com.graball

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Bundle
import android.os.Environment
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.graball.engine.MediaStorePublisher
import java.io.File

/**
 * WhatsApp Status saver, SnapTube-style (v0.6.2).
 *
 * No network, no login, no yt-dlp: WhatsApp itself saves recent
 * statuses onto the phone (its hidden .Statuses folder — synced in
 * the background, so statuses appear here even if never opened).
 * This screen just lists that folder in a grid and copies a tapped
 * file into Downloads/GrabAll via MediaStorePublisher.
 *
 * On his Android 10 phone with targetSdk 28, legacy storage applies,
 * so the folder reads directly with READ_EXTERNAL_STORAGE. Statuses
 * expire after 24h — a file saved here is gone from WhatsApp then,
 * but the copy in Downloads stays.
 */
class StatusActivity : AppCompatActivity() {

    private lateinit var grid: GridView
    private lateinit var emptyView: TextView
    private var files: List<File> = emptyList()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                loadStatuses()
            } else {
                emptyView.text = getString(R.string.status_need_permission)
                emptyView.visibility = View.VISIBLE
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)
        grid = findViewById(R.id.statusGrid)
        emptyView = findViewById(R.id.statusEmpty)
        grid.emptyView = emptyView
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
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            loadStatuses()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    @Suppress("DEPRECATION")
    private fun loadStatuses() {
        val ext = Environment.getExternalStorageDirectory()
        val candidates = listOf(
            File(ext, "WhatsApp/Media/.Statuses"),
            File(ext, "Android/media/com.whatsapp/WhatsApp/Media/.Statuses")
        )
        val found = candidates.firstOrNull { it.isDirectory }?.listFiles()
            ?.filter { it.isFile && isStatusFile(it.name) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        files = found
        if (files.isEmpty()) {
            emptyView.text = getString(R.string.status_empty)
        }
        grid.adapter = StatusAdapter()
    }

    private fun isStatusFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp", "gif", "mp4", "mov", "mkv", "3gp")
    }

    private fun isVideo(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp4", "mov", "mkv", "3gp")
    }

    @Suppress("DEPRECATION")
    private fun downloadsGrabAllDir(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "GrabAll"
        )
    }

    private inner class StatusAdapter : BaseAdapter() {
        override fun getCount(): Int = files.size
        override fun getItem(position: Int): File = files[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@StatusActivity)
                .inflate(R.layout.item_status, parent, false)
            val file = files[position]
            val thumb = view.findViewById<ImageView>(R.id.statusThumb)
            val save = view.findViewById<Button>(R.id.statusSave)
            val bmp = thumbnailFor(file)
            if (bmp != null) {
                thumb.setImageBitmap(bmp)
            } else if (isVideo(file.name)) {
                thumb.setImageResource(android.R.drawable.ic_media_play)
            } else {
                thumb.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            val alreadySaved = File(downloadsGrabAllDir(), file.name).exists()
            save.text = if (alreadySaved) {
                getString(R.string.status_saved_btn)
            } else {
                getString(R.string.status_save)
            }
            save.isEnabled = !alreadySaved
            save.setOnClickListener {
                val uri = MediaStorePublisher.publish(this@StatusActivity, file, file.name)
                if (uri != null) {
                    Toast.makeText(
                        this@StatusActivity, getString(R.string.status_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                    save.text = getString(R.string.status_saved_btn)
                    save.isEnabled = false
                } else {
                    Toast.makeText(
                        this@StatusActivity, getString(R.string.status_save_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            return view
        }

        private fun thumbnailFor(file: File): Bitmap? {
            return try {
                if (isVideo(file.name)) {
                    ThumbnailUtils.createVideoThumbnail(file, Size(400, 400), null)
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 400 || bounds.outHeight / sample > 400) {
                        sample *= 2
                    }
                    BitmapFactory.Options().apply { inSampleSize = sample }
                        .let { BitmapFactory.decodeFile(file.absolutePath, it) }
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
