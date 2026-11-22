package com.graball

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
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
 * WhatsApp Status saver:
 * - Android 5-10 (API 21-29): direct filesystem scan of WhatsApp/.Statuses
 *   across internal storage & SD card.
 * - Android 11+ (API 30+, targetSdk 34): supports Scoped Storage via 1-time
 *   SAF folder access (ACTION_OPEN_DOCUMENT_TREE persisted across restarts),
 *   saving cleanly to Downloads/GrabAll via MediaStorePublisher.publishUri().
 */
class StatusActivity : AppCompatActivity() {

    private data class StatusItem(
        val name: String,
        val lastModified: Long,
        val file: File? = null,
        val uri: Uri? = null
    ) {
        val cacheKey: String get() = file?.absolutePath ?: uri.toString()
    }

    private lateinit var grid: GridView
    private lateinit var emptyView: TextView
    private lateinit var grantBtn: Button
    private var items: List<StatusItem> = emptyList()
    private val thumbCache = LruCache<String, Bitmap>(24)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.any { it }) {
                loadStatuses()
            } else {
                emptyView.text = getString(R.string.status_need_permission)
                emptyView.visibility = View.VISIBLE
            }
        }

    private val safTreeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val treeUri = result.data?.data ?: return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val prefs = getSharedPreferences(SAF_PREFS, Context.MODE_PRIVATE)
            val saved = (prefs.getStringSet(KEY_TREE_URIS, emptySet()) ?: emptySet()).toMutableSet()
            saved.add(treeUri.toString())
            prefs.edit().putStringSet(KEY_TREE_URIS, saved).apply()
            loadStatuses()
        }

    private fun statusReadPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)
        grid = findViewById(R.id.statusGrid)
        emptyView = findViewById(R.id.statusEmpty)
        grantBtn = findViewById(R.id.statusGrantAccess)
        grid.emptyView = emptyView

        grantBtn.setOnClickListener { openWhatsAppSafPicker() }

        findViewById<LinearLayout>(R.id.navGrab).setOnClickListener {
            NavHelper.switchTab(this, MainActivity::class.java)
        }
        findViewById<LinearLayout>(R.id.navDownloads).setOnClickListener {
            NavHelper.switchTab(this, DownloadsActivity::class.java)
        }
        findViewById<LinearLayout>(R.id.navPlayer).setOnClickListener {
            NavHelper.switchTab(this, PlayerActivity::class.java)
        }
        findViewById<LinearLayout>(R.id.navSettings).setOnClickListener {
            NavHelper.switchTab(this, SettingsActivity::class.java)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            loadStatuses()
        } else {
            val perms = statusReadPermissions()
            val hasAll = perms.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            if (hasAll) {
                loadStatuses()
            } else {
                permissionLauncher.launch(perms)
            }
        }
    }

    private fun openWhatsAppSafPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val initial = Uri.parse(
                    "content://com.android.externalstorage.documents/document/" +
                        "primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp%2FMedia%2F.Statuses"
                )
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
            }
        }
        try {
            safTreeLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open folder picker", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun loadStatuses() {
        val byName = mutableMapOf<String, StatusItem>()

        // 1) Direct scan (works on Android 5-10 and occasionally legacy WhatsApp folders)
        val roots = mutableListOf<File>()
        roots.add(Environment.getExternalStorageDirectory())
        for (dir in getExternalFilesDirs(null)) {
            if (dir == null) continue
            val volumeRoot = generateSequence(dir) { it.parentFile }.elementAtOrNull(4)
            if (volumeRoot != null && volumeRoot !in roots) {
                roots.add(volumeRoot)
            }
        }
        val relativePaths = listOf(
            "WhatsApp/Media/.Statuses",
            "Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
            "WhatsApp Business/Media/.Statuses",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"
        )
        for (root in roots) {
            for (rel in relativePaths) {
                val dir = File(root, rel)
                if (!dir.isDirectory) continue
                for (file in dir.listFiles() ?: emptyArray()) {
                    if (!file.isFile || !isStatusFile(file.name)) continue
                    val prev = byName[file.name]
                    if (prev == null || file.lastModified() > prev.lastModified) {
                        byName[file.name] = StatusItem(file.name, file.lastModified(), file = file)
                    }
                }
            }
        }

        // 2) SAF persisted tree URIs (Android 11+ Scoped Storage)
        val prefs = getSharedPreferences(SAF_PREFS, Context.MODE_PRIVATE)
        val savedTrees = prefs.getStringSet(KEY_TREE_URIS, emptySet()) ?: emptySet()
        for (treeStr in savedTrees) {
            try {
                val treeUri = Uri.parse(treeStr)
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                )
                contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    while (cursor.moveToNext()) {
                        val childId = if (idCol >= 0) cursor.getString(idCol) else null
                        val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                        val mod = if (modCol >= 0) cursor.getLong(modCol) else 0L
                        if (childId.isNullOrBlank() || name.isNullOrBlank() || !isStatusFile(name)) continue
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        val prev = byName[name]
                        if (prev == null || mod > prev.lastModified) {
                            byName[name] = StatusItem(name, mod, uri = docUri)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        items = byName.values.sortedByDescending { it.lastModified }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            grantBtn.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            if (items.isEmpty()) {
                emptyView.text = if (savedTrees.isEmpty()) {
                    "Tap the button above and select \"Use this folder\" on WhatsApp's .Statuses folder (one-time Android 11+ permission)."
                } else {
                    getString(R.string.status_empty)
                }
            }
        } else {
            grantBtn.visibility = View.GONE
            if (items.isEmpty()) {
                emptyView.text = getString(R.string.status_empty)
            }
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
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): StatusItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@StatusActivity)
                .inflate(R.layout.item_status, parent, false)
            val item = items[position]
            val thumb = view.findViewById<ImageView>(R.id.statusThumb)
            val save = view.findViewById<Button>(R.id.statusSave)
            val bmp = thumbCache.get(item.cacheKey) ?: thumbnailFor(item)?.also {
                thumbCache.put(item.cacheKey, it)
            }
            if (bmp != null) {
                thumb.setImageBitmap(bmp)
            } else if (isVideo(item.name)) {
                thumb.setImageResource(android.R.drawable.ic_media_play)
            } else {
                thumb.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            val alreadySaved = File(downloadsGrabAllDir(), item.name).exists()
            save.text = if (alreadySaved) {
                getString(R.string.status_saved_btn)
            } else {
                getString(R.string.status_save)
            }
            save.isEnabled = !alreadySaved
            save.setOnClickListener {
                val uri = when {
                    item.file != null -> MediaStorePublisher.publish(this@StatusActivity, item.file, item.name)
                    item.uri != null -> MediaStorePublisher.publishUri(this@StatusActivity, item.uri, item.name)
                    else -> null
                }
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

        private fun thumbnailFor(item: StatusItem): Bitmap? {
            return try {
                if (item.file != null) {
                    thumbnailForFile(item.file)
                } else if (item.uri != null) {
                    thumbnailForUri(item.uri, item.name)
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun thumbnailForFile(file: File): Bitmap? {
            return if (isVideo(file.name)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ThumbnailUtils.createVideoThumbnail(file, Size(400, 400), null)
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(
                        file.absolutePath,
                        MediaStore.Images.Thumbnails.MINI_KIND
                    )
                }
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
        }

        private fun thumbnailForUri(uri: Uri, name: String): Bitmap? {
            return if (isVideo(name)) {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(this@StatusActivity, uri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        mmr.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 400, 400)
                    } else {
                        mmr.frameAtTime
                    }
                } finally {
                    try { mmr.release() } catch (_: Exception) { }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                while (bounds.outWidth / sample > 400 || bounds.outHeight / sample > 400) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }
        }
    }

    companion object {
        private const val SAF_PREFS = "graball_status_saf"
        private const val KEY_TREE_URIS = "tree_uris"
    }
}
