package com.graball.youtube

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Privacy-preserving SponsorBlock client (k-anonymity SHA-256 4-char prefix).
 * Fetches sponsor, self-promo, intro, outro, and interaction reminder segments
 * asynchronously with a short timeout; fails silently when offline or 404.
 */
object SponsorBlockClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    fun fetchSegments(videoId: String): List<SponsorSegment> {
        if (videoId.length != 11) return emptyList()
        return try {
            val prefix = sha256Prefix4(videoId)
            val url = "https://sponsor.ajay.app/api/skipSegments/$prefix" +
                "?service=YouTube&categories=%5B%22sponsor%22,%22selfpromo%22,%22intro%22,%22outro%22,%22interaction%22%5D"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "GrabAll-Android")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val arr = JSONArray(body)
                val out = mutableListOf<SponsorSegment>()
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONObject(i) ?: continue
                    if (entry.optString("videoID") != videoId) continue
                    val segs = entry.optJSONArray("segments") ?: continue
                    for (j in 0 until segs.length()) {
                        val s = segs.optJSONObject(j) ?: continue
                        val range = s.optJSONArray("segment") ?: continue
                        if (range.length() < 2) continue
                        val startMs = (range.optDouble(0, 0.0) * 1000.0).toLong()
                        val endMs = (range.optDouble(1, 0.0) * 1000.0).toLong()
                        val cat = s.optString("category", "sponsor")
                        if (endMs > startMs + 500L) {
                            out.add(SponsorSegment(startMs, endMs, cat))
                        }
                    }
                }
                out.sortedBy { it.startMs }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun sha256Prefix4(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return "%02x%02x".format(digest[0], digest[1])
    }
}
