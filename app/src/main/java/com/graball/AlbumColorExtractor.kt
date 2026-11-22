package com.graball

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

/**
 * Ultra-fast (~0.5ms), zero-dependency dominant color extractor for album covers
 * and YouTube thumbnails. Samples a 24x24 scaled copy and derives an eye-friendly
 * darkened backdrop color + soft pastel wave/accent tint so white controls and
 * lyrics always maintain crisp readability (even on bright yellow or white covers).
 */
object AlbumColorExtractor {

    data class ThemeColors(
        val backdrop: Int,
        val lyricsBackdrop: Int,
        val waveAccent: Int
    )

    val DEFAULT = ThemeColors(
        backdrop = Color.parseColor("#1A172B"),
        lyricsBackdrop = Color.parseColor("#131120"),
        waveAccent = Color.parseColor("#FF7A18")
    )

    fun extract(bitmap: Bitmap?): ThemeColors {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return DEFAULT
        }
        return try {
            val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
            val pixels = IntArray(24 * 24)
            small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
            if (small !== bitmap) small.recycle()

            // 18 hue buckets (20 deg each) weighted by saturation & mid-brightness
            val bucketWeight = FloatArray(18)
            val bucketR = FloatArray(18)
            val bucketG = FloatArray(18)
            val bucketB = FloatArray(18)
            var fallbackR = 0f
            var fallbackG = 0f
            var fallbackB = 0f
            var fallbackWeight = 0f
            val hsv = FloatArray(3)

            for (px in pixels) {
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                Color.RGBToHSV(r, g, b, hsv)
                val sat = hsv[1]
                val v = hsv[2]
                // Skip near-black letterbox bars and blown-out pure white borders
                if (v < 0.08f) continue
                fallbackR += r
                fallbackG += g
                fallbackB += b
                fallbackWeight += 1f
                if (sat < 0.14f || v > 0.97f && sat < 0.25f) continue

                val bucket = ((hsv[0] / 20f).toInt()).coerceIn(0, 17)
                // Favor vibrant mid-to-bright colors
                val w = (0.35f + sat * 1.4f) * (0.4f + v * 0.6f)
                bucketWeight[bucket] += w
                bucketR[bucket] += r * w
                bucketG[bucket] += g * w
                bucketB[bucket] += b * w
            }

            var bestIdx = -1
            var bestW = 0f
            for (i in 0 until 18) {
                if (bucketWeight[i] > bestW) {
                    bestW = bucketWeight[i]
                    bestIdx = i
                }
            }

            val baseR: Int
            val baseG: Int
            val baseB: Int
            if (bestIdx >= 0 && bestW > 0.5f) {
                baseR = (bucketR[bestIdx] / bestW).toInt().coerceIn(0, 255)
                baseG = (bucketG[bestIdx] / bestW).toInt().coerceIn(0, 255)
                baseB = (bucketB[bestIdx] / bestW).toInt().coerceIn(0, 255)
            } else if (fallbackWeight > 0f) {
                baseR = (fallbackR / fallbackWeight).toInt().coerceIn(0, 255)
                baseG = (fallbackG / fallbackWeight).toInt().coerceIn(0, 255)
                baseB = (fallbackB / fallbackWeight).toInt().coerceIn(0, 255)
            } else {
                return DEFAULT
            }

            Color.RGBToHSV(baseR, baseG, baseB, hsv)
            val hue = hsv[0]
            val sat = hsv[1]

            // Rich darkened backdrop (like Vivo/iMusic: bright yellow -> warm olive-gold,
            // bright red -> deep burgundy, bright cyan -> rich deep teal) for crisp white text.
            // Perceived luminance of yellow/green hues (~45..165 deg) is higher, so lower V slightly.
            val isBrightHue = hue in 40f..170f
            val maxV = if (isBrightHue) 0.44f else 0.40f
            val minV = 0.22f
            val bgHsv = floatArrayOf(
                hue,
                sat.coerceIn(0.28f, 0.72f),
                max(minV, hsv[2] * 0.52f).coerceAtMost(maxV)
            )
            val backdrop = Color.HSVToColor(bgHsv)

            // Deeper variant for Full-Screen Live Lyrics readability
            val lyricsHsv = floatArrayOf(
                hue,
                sat.coerceIn(0.25f, 0.65f),
                (bgHsv[2] * 0.68f).coerceIn(0.14f, 0.30f)
            )
            val lyricsBackdrop = Color.HSVToColor(lyricsHsv)

            // Soft pastel wave/accent tint harmonizing with cover color
            val accentHsv = floatArrayOf(
                hue,
                sat.coerceIn(0.18f, 0.42f),
                0.92f
            )
            val waveAccent = Color.HSVToColor(accentHsv)

            ThemeColors(backdrop, lyricsBackdrop, waveAccent)
        } catch (_: Throwable) {
            DEFAULT
        }
    }
}
