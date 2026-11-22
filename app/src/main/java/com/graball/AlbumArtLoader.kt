package com.graball

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Lightweight, zero-dependency loader for embedded audio cover art and ID3
 * metadata (title, artist, album) using Android's MediaMetadataRetriever.
 *
 * - Bounded 4 MB RAM LruCache in RGB_565 so scrolling or switching tracks is instant.
 * - Remembers files without embedded art so they are never probed twice.
 * - Single background thread keeps CPU & battery overhead near zero.
 */
object AlbumArtLoader {

    data class TrackMeta(
        val title: String,
        val artist: String,
        val album: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val artCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val noArtPaths: MutableSet<String> =
        Collections.synchronizedSet(HashSet())

    private val metaCache: LruCache<String, TrackMeta> = LruCache(160)

    fun getCachedMeta(file: File): TrackMeta? = metaCache.get(file.absolutePath)

    /** Extract ID3 metadata synchronously (fast, cached). */
    fun loadMetaSync(file: File): TrackMeta {
        val key = file.absolutePath
        metaCache.get(key)?.let { return it }
        var title = ""
        var artist = ""
        var album = ""
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(key)
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
            album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim().orEmpty()
        } catch (_: Throwable) {
        } finally {
            try { mmr.release() } catch (_: Exception) { }
        }
        val meta = TrackMeta(
            title = title.ifBlank { file.nameWithoutExtension },
            artist = artist,
            album = album
        )
        metaCache.put(key, meta)
        return meta
    }

    /** Extract embedded album art synchronously (call off main thread). */
    fun loadArtSync(file: File, targetPx: Int = 512): Bitmap? {
        val path = file.absolutePath
        val cacheKey = "$path@$targetPx"
        artCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }
        if (path in noArtPaths) return null

        val mmr = MediaMetadataRetriever()
        val bytes: ByteArray? = try {
            mmr.setDataSource(path)
            mmr.embeddedPicture
        } catch (_: Throwable) {
            null
        } finally {
            try { mmr.release() } catch (_: Exception) { }
        }

        if (bytes == null || bytes.isEmpty()) {
            noArtPaths.add(path)
            return null
        }

        val bmp = decodeSampled(bytes, targetPx) ?: return null
        artCache.put(cacheKey, bmp)
        return bmp
    }

    /** Async loader for ImageViews (mini-player, full-screen Now Playing). */
    fun loadInto(
        file: File?,
        imageView: ImageView,
        fallbackView: View? = null,
        targetPx: Int = 512
    ) {
        val tagKey = file?.let { "${it.absolutePath}@$targetPx" }
        imageView.tag = tagKey
        if (file == null || tagKey == null) {
            imageView.setImageDrawable(null)
            imageView.visibility = View.GONE
            fallbackView?.visibility = View.VISIBLE
            return
        }
        val cached = artCache.get(tagKey)
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            imageView.visibility = View.VISIBLE
            fallbackView?.visibility = View.GONE
            return
        }
        if (file.absolutePath in noArtPaths) {
            imageView.setImageDrawable(null)
            imageView.visibility = View.GONE
            fallbackView?.visibility = View.VISIBLE
            return
        }
        // Show fallback while decoding on background thread
        imageView.setImageDrawable(null)
        imageView.visibility = View.GONE
        fallbackView?.visibility = View.VISIBLE
        executor.execute {
            val bmp = loadArtSync(file, targetPx)
            mainHandler.post {
                if (imageView.tag != tagKey) return@post
                if (bmp != null && !bmp.isRecycled) {
                    imageView.setImageBitmap(bmp)
                    imageView.visibility = View.VISIBLE
                    fallbackView?.visibility = View.GONE
                } else {
                    imageView.setImageDrawable(null)
                    imageView.visibility = View.GONE
                    fallbackView?.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Async ID3 metadata lookup. */
    fun loadMetaAsync(file: File, onResult: (TrackMeta) -> Unit) {
        metaCache.get(file.absolutePath)?.let {
            onResult(it)
            return
        }
        executor.execute {
            val meta = loadMetaSync(file)
            mainHandler.post { onResult(meta) }
        }
    }

    private fun decodeSampled(bytes: ByteArray, targetPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > targetPx * 2 ||
                bounds.outHeight / sample > targetPx * 2
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Throwable) {
            null
        }
    }
}
