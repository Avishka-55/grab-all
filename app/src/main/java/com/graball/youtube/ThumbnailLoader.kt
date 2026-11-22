package com.graball.youtube

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ultra-lightweight thumbnail loader for low-RAM devices.
 *
 * Caps in-memory cache at <= 8 MB, decodes opaque video thumbnails with
 * RGB_565 (50% less RAM than ARGB_8888) and downsamples to ~360px wide
 * so scrolling lists stay smooth even on old 1-2 GB RAM 32-bit phones.
 */
object ThumbnailLoader {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache: LruCache<String, Bitmap> = run {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        val cacheKb = minOf(8 * 1024, maxOf(2 * 1024, maxKb / 16))
        object : LruCache<String, Bitmap>(cacheKb) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                value.byteCount / 1024
        }
    }

    fun load(url: String?, imageView: ImageView, targetWidthPx: Int = 360) {
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
            val bmp = fetchAndDecode(url, targetWidthPx) ?: return@execute
            cache.put(url, bmp)
            mainHandler.post {
                if (imageView.tag == url) {
                    imageView.setImageBitmap(bmp)
                }
            }
        }
    }

    fun clearMemory() {
        cache.evictAll()
    }

    private fun fetchAndDecode(url: String, targetW: Int): Bitmap? {
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
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
            }
        } catch (_: Throwable) {
            null
        }
    }
}
