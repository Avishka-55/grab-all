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
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.graball.engine.MediaStorePublisher
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Smooth 60fps WhatsApp Status Viewer & Saver:
 * - Separate Images / Videos tabs with live item counts
 * - 100% async background thumbnail decoding into a 12 MB RGB_565 LruCache
 *   (zero UI-thread disk/video decoding -> zero scroll lag!)
 * - Tap any image to enlarge full-screen; tap any video to play in-app via
 *   Media3 ExoPlayer with audio & seek bar (no download needed first)
 * - 1-tap Save to Downloads/GrabAll and Share right from grid or full-screen preview
 * - Supports Android 5-10 direct scan & Android 11+ persisted SAF tree URIs
 */
class StatusActivity : AppCompatActivity() {

    private data class StatusItem(
        val name: String,
        val lastModified: Long,
        val isVideo: Boolean,
        val file: File? = null,
        val uri: Uri? = null
    ) {
        val cacheKey: String get() = file?.absolutePath ?: uri.toString()
    }

    private enum class Tab { IMAGES, VIDEOS }

    private lateinit var recycler: RecyclerView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var grantBtn: Button
    private lateinit var tabImages: TextView
    private lateinit var tabVideos: TextView

    // Full-screen preview overlay
    private lateinit var previewOverlay: FrameLayout
    private lateinit var previewImage: ImageView
    private lateinit var previewVideo: PlayerView
    private lateinit var previewCounter: TextView
    private lateinit var previewSave: TextView
    private lateinit var previewShare: TextView
    private lateinit var previewPrev: TextView
    private lateinit var previewNext: TextView

    private var allImages: List<StatusItem> = emptyList()
    private var allVideos: List<StatusItem> = emptyList()
    private var activeTab: Tab = Tab.IMAGES
    private var hasSelectedInitialTab = false
    private val savedNames = Collections.synchronizedSet(mutableSetOf<String>())

    private var previewIndex = -1
    private var previewPlayer: ExoPlayer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val thumbExecutor = Executors.newFixedThreadPool(3)
    private lateinit var adapter: StatusAdapter

    // 12 MB RGB_565 thumbnail cache (~100-150 thumbnails -> instant scroll back/forth)
    private val thumbCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val failedThumbKeys = Collections.synchronizedSet(mutableSetOf<String>())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.any { it }) {
                loadStatusesAsync()
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
            loadStatusesAsync()
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

        recycler = findViewById(R.id.statusRecycler)
        loadingSpinner = findViewById(R.id.statusLoading)
        emptyView = findViewById(R.id.statusEmpty)
        grantBtn = findViewById(R.id.statusGrantAccess)
        tabImages = findViewById(R.id.tabStatusImages)
        tabVideos = findViewById(R.id.tabStatusVideos)

        previewOverlay = findViewById(R.id.statusPreviewOverlay)
        previewImage = findViewById(R.id.previewImage)
        previewVideo = findViewById(R.id.previewVideo)
        previewCounter = findViewById(R.id.previewCounter)
        previewSave = findViewById(R.id.previewSave)
        previewShare = findViewById(R.id.previewShare)
        previewPrev = findViewById(R.id.previewPrev)
        previewNext = findViewById(R.id.previewNext)

        adapter = StatusAdapter()
        recycler.layoutManager = GridLayoutManager(this, 2)
        recycler.setHasFixedSize(true)
        recycler.setItemViewCacheSize(24)
        recycler.adapter = adapter

        grantBtn.setOnClickListener { openWhatsAppSafPicker() }
        findViewById<TextView>(R.id.statusRefresh).setOnClickListener { loadStatusesAsync() }

        tabImages.setOnClickListener {
            if (activeTab != Tab.IMAGES) {
                activeTab = Tab.IMAGES
                renderActiveTab()
            }
        }
        tabVideos.setOnClickListener {
            if (activeTab != Tab.VIDEOS) {
                activeTab = Tab.VIDEOS
                renderActiveTab()
            }
        }

        findViewById<TextView>(R.id.previewClose).setOnClickListener { closePreview() }
        previewPrev.setOnClickListener {
            if (previewIndex > 0) showPreviewAt(previewIndex - 1)
        }
        previewNext.setOnClickListener {
            val list = currentTabItems()
            if (previewIndex + 1 < list.size) showPreviewAt(previewIndex + 1)
        }
        previewSave.setOnClickListener {
            val item = currentTabItems().getOrNull(previewIndex) ?: return@setOnClickListener
            saveStatusAsync(item) { ok ->
                if (ok) updatePreviewSaveButton(item)
            }
        }
        previewShare.setOnClickListener {
            val item = currentTabItems().getOrNull(previewIndex) ?: return@setOnClickListener
            shareStatusItem(item)
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
        findViewById<LinearLayout>(R.id.navSettings).setOnClickListener {
            NavHelper.switchTab(this, SettingsActivity::class.java)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            loadStatusesAsync()
        } else {
            val perms = statusReadPermissions()
            val hasAll = perms.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            if (hasAll) {
                loadStatusesAsync()
            } else {
                permissionLauncher.launch(perms)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        previewPlayer?.pause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (previewOverlay.visibility == View.VISIBLE) {
            closePreview()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        releasePreviewPlayer()
        thumbExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        super.onDestroy()
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
    private fun loadStatusesAsync() {
        loadingSpinner.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        ioExecutor.execute {
            val byName = mutableMapOf<String, StatusItem>()

            // Pre-scan WhatsApp Status folder (+ legacy GrabAll root) off the main thread
            // so Saved badges need 0 UI disk I/O and older saves still show as Saved.
            val existingSaved = mutableSetOf<String>()
            val grabRoot = downloadsGrabAllDir()
            File(grabRoot, STATUS_SUBFOLDER).listFiles()?.forEach { f ->
                if (f.isFile) existingSaved.add(f.name)
            }
            grabRoot.listFiles()?.forEach { f ->
                if (f.isFile) existingSaved.add(f.name)
            }

            // 1) Direct filesystem scan (Android 5-10 & legacy WhatsApp folders)
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
                            byName[file.name] = StatusItem(
                                name = file.name,
                                lastModified = file.lastModified(),
                                isVideo = isVideo(file.name),
                                file = file
                            )
                        }
                    }
                }
            }

            // 2) Persisted SAF tree URIs (Android 11+ Scoped Storage)
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
                                byName[name] = StatusItem(
                                    name = name,
                                    lastModified = mod,
                                    isVideo = isVideo(name),
                                    uri = docUri
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            val sortedAll = byName.values.sortedByDescending { it.lastModified }
            val images = sortedAll.filter { !it.isVideo }
            val videos = sortedAll.filter { it.isVideo }
            val noSafYet = savedTrees.isEmpty()

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                loadingSpinner.visibility = View.GONE
                savedNames.clear()
                savedNames.addAll(existingSaved)
                allImages = images
                allVideos = videos

                if (!hasSelectedInitialTab) {
                    hasSelectedInitialTab = true
                    if (allImages.isEmpty() && allVideos.isNotEmpty()) {
                        activeTab = Tab.VIDEOS
                    }
                }

                val totalEmpty = allImages.isEmpty() && allVideos.isEmpty()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    grantBtn.visibility = if (totalEmpty) View.VISIBLE else View.GONE
                    if (totalEmpty) {
                        emptyView.text = if (noSafYet) {
                            "Tap the button above and select \"Use this folder\" on WhatsApp's .Statuses folder (one-time Android 11+ permission)."
                        } else {
                            getString(R.string.status_empty)
                        }
                    }
                } else {
                    grantBtn.visibility = View.GONE
                    if (totalEmpty) {
                        emptyView.text = getString(R.string.status_empty)
                    }
                }
                renderActiveTab()
            }
        }
    }

    private fun currentTabItems(): List<StatusItem> =
        if (activeTab == Tab.IMAGES) allImages else allVideos

    private fun renderActiveTab() {
        tabImages.text = "🖼 Images (${allImages.size})"
        tabVideos.text = "🎬 Videos (${allVideos.size})"

        val imgSelected = activeTab == Tab.IMAGES
        tabImages.setBackgroundResource(if (imgSelected) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
        tabImages.setTextColor(
            ContextCompat.getColor(this, if (imgSelected) R.color.graball_text else R.color.graball_subtext)
        )
        tabVideos.setBackgroundResource(if (!imgSelected) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
        tabVideos.setTextColor(
            ContextCompat.getColor(this, if (!imgSelected) R.color.graball_text else R.color.graball_subtext)
        )

        val list = currentTabItems()
        adapter.submitList(list)
        if (list.isEmpty()) {
            if (allImages.isNotEmpty() || allVideos.isNotEmpty()) {
                emptyView.text = if (activeTab == Tab.IMAGES) {
                    "No image statuses right now"
                } else {
                    "No video statuses right now"
                }
            }
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
        }
    }

    // ---- Full-Screen In-App Preview (Images & Videos, No Download Needed) ----

    private fun openPreview(index: Int) {
        val list = currentTabItems()
        if (index !in list.indices) return
        previewOverlay.visibility = View.VISIBLE
        showPreviewAt(index)
    }

    private fun showPreviewAt(index: Int) {
        val list = currentTabItems()
        val item = list.getOrNull(index) ?: return
        previewIndex = index
        previewCounter.text = "${index + 1} / ${list.size}"
        previewPrev.visibility = if (index > 0) View.VISIBLE else View.GONE
        previewNext.visibility = if (index + 1 < list.size) View.VISIBLE else View.GONE
        updatePreviewSaveButton(item)

        if (item.isVideo) {
            previewImage.visibility = View.GONE
            previewImage.setImageDrawable(null)
            previewVideo.visibility = View.VISIBLE
            playPreviewVideo(item)
        } else {
            releasePreviewPlayer()
            previewVideo.visibility = View.GONE
            previewImage.visibility = View.VISIBLE
            // Show cached thumb immediately while full-res decodes off-thread
            thumbCache.get(item.cacheKey)?.let { previewImage.setImageBitmap(it) }
            val targetKey = item.cacheKey
            thumbExecutor.execute {
                val full = decodeFullImage(item)
                if (full != null) {
                    mainHandler.post {
                        if (!isFinishing && previewOverlay.visibility == View.VISIBLE &&
                            currentTabItems().getOrNull(previewIndex)?.cacheKey == targetKey
                        ) {
                            previewImage.setImageBitmap(full)
                        }
                    }
                }
            }
        }
    }

    private fun playPreviewVideo(item: StatusItem) {
        val mediaUri = item.uri ?: item.file?.let { Uri.fromFile(it) } ?: return
        val player = previewPlayer ?: ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            repeatMode = Player.REPEAT_MODE_ONE
            previewPlayer = this
        }
        previewVideo.player = player
        player.setMediaItem(MediaItem.fromUri(mediaUri))
        player.prepare()
        player.playWhenReady = true
    }

    private fun releasePreviewPlayer() {
        previewVideo.player = null
        previewPlayer?.release()
        previewPlayer = null
    }

    private fun closePreview() {
        releasePreviewPlayer()
        previewImage.setImageDrawable(null)
        previewOverlay.visibility = View.GONE
        previewIndex = -1
    }

    private fun updatePreviewSaveButton(item: StatusItem) {
        val saved = savedNames.contains(item.name)
        previewSave.text = if (saved) "✓ Saved" else "⬇ Save"
        previewSave.isEnabled = !saved
        previewSave.alpha = if (saved) 0.65f else 1.0f
    }

    private fun saveStatusAsync(item: StatusItem, onDone: (Boolean) -> Unit = {}) {
        if (savedNames.contains(item.name)) {
            onDone(true)
            return
        }
        ioExecutor.execute {
            val uri = when {
                item.file != null -> MediaStorePublisher.publish(
                    this, item.file, item.name, subfolder = STATUS_SUBFOLDER
                )
                item.uri != null -> MediaStorePublisher.publishUri(
                    this, item.uri, item.name, subfolder = STATUS_SUBFOLDER
                )
                else -> null
            }
            val ok = uri != null
            if (ok) savedNames.add(item.name)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                Toast.makeText(
                    this,
                    getString(if (ok) R.string.status_saved else R.string.status_save_failed),
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) adapter.notifyItemSaved(item.name)
                onDone(ok)
            }
        }
    }

    private fun shareStatusItem(item: StatusItem) {
        try {
            val shareUri = when {
                item.uri != null -> item.uri
                item.file != null -> FileProvider.getUriForFile(
                    this, "com.graball.fileprovider", item.file
                )
                else -> return
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = MediaStorePublisher.mimeFor(item.name)
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share status"))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not share this status", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Async Image & Video Thumbnail Decoders ----

    private fun decodeThumbnail(item: StatusItem): Bitmap? {
        return try {
            val raw = if (item.file != null) {
                decodeFileThumb(item.file, item.isVideo)
            } else if (item.uri != null) {
                decodeUriThumb(item.uri, item.isVideo)
            } else {
                null
            }
            raw
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeFileThumb(file: File, video: Boolean): Bitmap? {
        return if (video) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createVideoThumbnail(file, Size(280, 280), null)
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
            while (bounds.outWidth / sample > 300 || bounds.outHeight / sample > 300) {
                sample *= 2
            }
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }.let { BitmapFactory.decodeFile(file.absolutePath, it) }
        }
    }

    private fun decodeUriThumb(uri: Uri, video: Boolean): Bitmap? {
        return if (video) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(this, uri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    mmr.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 280, 280)
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
            while (bounds.outWidth / sample > 300 || bounds.outHeight / sample > 300) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    }

    private fun decodeFullImage(item: StatusItem): Bitmap? {
        val maxDim = resources.displayMetrics.heightPixels.coerceIn(1080, 2048)
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (item.file != null) {
                BitmapFactory.decodeFile(item.file.absolutePath, bounds)
            } else if (item.uri != null) {
                contentResolver.openInputStream(item.uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            } else {
                return null
            }
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            if (item.file != null) {
                BitmapFactory.decodeFile(item.file.absolutePath, opts)
            } else {
                contentResolver.openInputStream(item.uri!!)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }
        } catch (_: Throwable) {
            null
        }
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
    private fun downloadsGrabAllDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "GrabAll"
    )

    // ---- Smooth Zero-Lag RecyclerView Adapter ----

    private inner class StatusAdapter : RecyclerView.Adapter<StatusAdapter.Holder>() {

        private var data: List<StatusItem> = emptyList()

        fun submitList(newItems: List<StatusItem>) {
            data = newItems
            notifyDataSetChanged()
        }

        fun notifyItemSaved(name: String) {
            val idx = data.indexOfFirst { it.name == name }
            if (idx >= 0) notifyItemChanged(idx)
        }

        override fun getItemCount(): Int = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_status, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = data[position]
            val key = item.cacheKey
            holder.boundKey = key
            holder.videoBadge.visibility = if (item.isVideo) View.VISIBLE else View.GONE

            val cached = thumbCache.get(key)
            if (cached != null) {
                holder.thumb.setImageBitmap(cached)
            } else {
                holder.thumb.setImageDrawable(null)
                if (!failedThumbKeys.contains(key)) {
                    thumbExecutor.execute {
                        if (holder.boundKey != key) return@execute
                        val bmp = decodeThumbnail(item)
                        if (bmp != null) {
                            thumbCache.put(key, bmp)
                            mainHandler.post {
                                if (!isFinishing && holder.boundKey == key) {
                                    holder.thumb.setImageBitmap(bmp)
                                }
                            }
                        } else {
                            failedThumbKeys.add(key)
                        }
                    }
                }
            }

            val saved = savedNames.contains(item.name)
            holder.save.text = if (saved) "✓ Saved" else "⬇ Save"
            holder.save.setBackgroundResource(
                if (saved) R.drawable.yt_chip_bg else R.drawable.yt_chip_selected
            )
            holder.save.alpha = if (saved) 0.65f else 1.0f
            holder.save.isEnabled = !saved

            holder.thumbBox.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos in data.indices) {
                    openPreview(pos)
                }
            }
            holder.save.setOnClickListener {
                saveStatusAsync(item)
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val thumbBox: FrameLayout = view.findViewById(R.id.statusThumbBox)
            val thumb: ImageView = view.findViewById(R.id.statusThumb)
            val videoBadge: TextView = view.findViewById(R.id.statusVideoBadge)
            val save: TextView = view.findViewById(R.id.statusSave)
            @Volatile
            var boundKey: String? = null

            init {
                thumbBox.outlineProvider = ViewOutlineProvider.BACKGROUND
                thumbBox.clipToOutline = true
            }
        }
    }

    companion object {
        private const val SAF_PREFS = "graball_status_saf"
        private const val KEY_TREE_URIS = "tree_uris"
        private const val STATUS_SUBFOLDER = "WhatsApp Status"
    }
}
