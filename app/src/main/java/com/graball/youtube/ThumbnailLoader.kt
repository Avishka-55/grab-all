package com.graball.youtube

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ultra-lightweight 2-tier thumbnail loader (RAM + bounded disk cache).
 *
 * - RAM cache: <= 8 MB LruCache decoded in RGB_565 (50% less RAM than ARGB_8888)
 * - Disk cache: <= 16 MB in cacheDir/yt_thumbs so thumbnails appear instantly
 *   across app restarts without re-downloading over mobile data.
 */
object ThumbnailLoader {

    private const val MAX_DISK_BYTES = 16L * 1024L * 1024L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var diskDir: File? = null

    private val cache: LruCache<String, Bitmap> = run {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        val cacheKb = minOf(8 * 1024, maxOf(2 * 1024, maxKb / 16))
        object : LruCache<String, Bitmap>(cacheKb) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                value.byteCount / 1024
        }
    }

    fun init(context: Context) {
        if (diskDir == null) {
            diskDir = File(context.applicationContext.cacheDir, "yt_thumbs").apply {
                if (!exists()) mkdirs()
            }
        }
    }

    fun load(url: String?, imageView: ImageView, targetWidthPx: Int = 360) {
        init(imageView.context)
        imageView.tag = url
        if (url.isNullOrBlank()) {
            imageView.setImageDrawable(null)
            return
        }
        val cached = cache.get(url)
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            return
        }
        imageView.setImageDrawable(null)
        executor.execute {
            val bmp = loadSync(url, targetWidthPx) ?: return@execute
            mainHandler.post {
                if (imageView.tag == url) {
                    imageView.setImageBitmap(bmp)
                }
            }
        }
    }

    /** Synchronous fetch/cache read for notifications and background artwork. */
    fun loadSync(url: String?, targetWidthPx: Int = 360): Bitmap? {
        if (url.isNullOrBlank()) return null
        val mem = cache.get(url)
        if (mem != null && !mem.isRecycled) return mem

        val file = diskFileFor(url)
        if (file != null && file.isFile && file.length() > 0) {
            val decoded = decodeBytes(file.readBytes(), targetWidthPx)
            if (decoded != null) {
                file.setLastModified(System.currentTimeMillis())
                cache.put(url, decoded)
                return decoded
            }
        }

        val bytes = fetchBytes(url) ?: return null
        if (file != null) {
            try {
                file.writeBytes(bytes)
                pruneDiskIfNeeded()
            } catch (_: Exception) {
            }
        }
        val bmp = decodeBytes(bytes, targetWidthPx) ?: return null
        cache.put(url, bmp)
        return bmp
    }

    fun clearAll(context: Context) {
        cache.evictAll()
        try {
            File(context.cacheDir, "yt_thumbs").deleteRecursively()
        } catch (_: Exception) {
        }
    }

    private fun diskFileFor(url: String): File? {
        val dir = diskDir ?: return null
        if (!dir.exists()) dir.mkdirs()
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 20)
        return File(dir, "$digest.img")
    }

    private fun pruneDiskIfNeeded() {
        val dir = diskDir ?: return
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        val sorted = files.sortedBy { it.lastModified() }
        for (f in sorted) {
            val len = f.length()
            if (f.delete()) {
                total -= len
            }
            if (total <= MAX_DISK_BYTES * 3 / 4) break
        }
    }

    private fun fetchBytes(url: String): ByteArray? {
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeBytes(bytes: ByteArray, targetW: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > targetW * 2) {
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
