package com.graball.youtube

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlin.math.min

/**
 * Unthrottled chunked [DataSource] for YouTube `googlevideo.com/videoplayback`
 * progressive/adaptive streams.
 *
 * YouTube aggressively throttles single open-ended (`Range: bytes=0-`) HTTPS
 * connections on `tv_samsung` / `web` streams down to ~50-150 KB/s, causing
 * constant ExoPlayer rebuffering even on fast Wi-Fi/VPN. Splitting requests
 * into bounded 2 MB chunks via GoogleVideo's native `&range=start-end` query
 * parameter bypasses the per-connection throttle so buffers fill at full wire
 * speed (~5-50 MB/s) while keeping seeking and ExoPlayer's disk cache seamless.
 */
@OptIn(UnstableApi::class)
class YouTubeChunkedDataSource(
    private val upstream: DataSource
) : DataSource {

    class Factory(
        private val upstreamFactory: DataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            YouTubeChunkedDataSource(upstreamFactory.createDataSource())
    }

    private var originalSpec: DataSpec? = null
    private var chunkedMode = false
    private var baseUrl = ""
    private var currentPos = 0L
    private var endExclusive = C.LENGTH_UNSET.toLong() // exact end if known (clen or bounded DataSpec)
    private var chunkBytesRead = 0L
    private var chunkExpectedSize = 0L
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        originalSpec = dataSpec
        chunkedMode = false
        upstreamOpen = false

        val uri = dataSpec.uri
        if (!shouldChunk(uri, dataSpec)) {
            val len = upstream.open(dataSpec)
            upstreamOpen = true
            return len
        }

        val urlStr = uri.toString()
        val clen = extractClen(urlStr)
        val start = dataSpec.position.coerceAtLeast(0L)
        val requestedEnd = if (dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.length > 0L) {
            start + dataSpec.length
        } else {
            C.LENGTH_UNSET.toLong()
        }
        val effectiveEnd = when {
            clen > 0L && requestedEnd > 0L -> min(clen, requestedEnd)
            clen > 0L -> clen
            requestedEnd > 0L -> requestedEnd
            else -> C.LENGTH_UNSET.toLong()
        }
        if (effectiveEnd != C.LENGTH_UNSET.toLong() && start >= effectiveEnd) {
            return 0L
        }

        chunkedMode = true
        baseUrl = urlStr
        currentPos = start
        endExclusive = effectiveEnd

        openNextChunk()

        return if (effectiveEnd != C.LENGTH_UNSET.toLong()) {
            (effectiveEnd - start).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!chunkedMode) {
            return upstream.read(buffer, offset, length)
        }
        if (!upstreamOpen) return C.RESULT_END_OF_INPUT

        val n = upstream.read(buffer, offset, length)
        if (n > 0) {
            currentPos += n
            chunkBytesRead += n
            return n
        }
        if (n != C.RESULT_END_OF_INPUT) return n

        // Current chunk finished — check if the stream or bounded range is done.
        if (endExclusive != C.LENGTH_UNSET.toLong() && currentPos >= endExclusive) {
            closeUpstreamQuietly()
            return C.RESULT_END_OF_INPUT
        }
        if (chunkExpectedSize > 0L && chunkBytesRead < chunkExpectedSize) {
            // Upstream delivered fewer bytes than requested -> true EOF.
            closeUpstreamQuietly()
            return C.RESULT_END_OF_INPUT
        }

        // Open the next 2 MB unthrottled chunk and continue reading seamlessly.
        openNextChunk()
        if (!upstreamOpen) return C.RESULT_END_OF_INPUT
        val next = upstream.read(buffer, offset, length)
        if (next > 0) {
            currentPos += next
            chunkBytesRead += next
        }
        return next
    }

    override fun getUri(): Uri? = originalSpec?.uri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeUpstreamQuietly()
        originalSpec = null
        chunkedMode = false
    }

    private fun openNextChunk() {
        closeUpstreamQuietly()
        if (endExclusive != C.LENGTH_UNSET.toLong() && currentPos >= endExclusive) {
            return
        }
        val chunkEnd = if (endExclusive != C.LENGTH_UNSET.toLong()) {
            min(currentPos + CHUNK_SIZE_BYTES - 1L, endExclusive - 1L)
        } else {
            currentPos + CHUNK_SIZE_BYTES - 1L
        }
        if (chunkEnd < currentPos) return

        val sep = if ('?' in baseUrl) "&" else "?"
        val rangedUri = Uri.parse("$baseUrl${sep}range=$currentPos-$chunkEnd")
        val spec = originalSpec!!.buildUpon()
            .setUri(rangedUri)
            .setPosition(0L)
            .setLength(C.LENGTH_UNSET.toLong())
            .build()

        chunkBytesRead = 0L
        chunkExpectedSize = (chunkEnd - currentPos) + 1L
        upstream.open(spec)
        upstreamOpen = true
    }

    private fun closeUpstreamQuietly() {
        if (upstreamOpen) {
            upstreamOpen = false
            try {
                upstream.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun shouldChunk(uri: Uri, spec: DataSpec): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (!host.endsWith(".googlevideo.com")) return false
        val path = uri.path ?: return false
        if (!path.contains("/videoplayback")) return false
        val url = uri.toString()
        if (url.contains("range=") ||
            url.contains(".m3u8") ||
            url.contains("source=yt_live_broadcast") ||
            url.contains("live=1")
        ) {
            return false
        }
        // Only chunk open-ended or multi-MB requests (small range probes pass straight through).
        return spec.length == C.LENGTH_UNSET.toLong() || spec.length > CHUNK_SIZE_BYTES
    }

    private fun extractClen(url: String): Long {
        val match = CLEN_REGEX.find(url) ?: return C.LENGTH_UNSET.toLong()
        return match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: C.LENGTH_UNSET.toLong()
    }

    companion object {
        private const val CHUNK_SIZE_BYTES = 2L * 1024L * 1024L // 2 MB unthrottled GoogleVideo chunks
        private val CLEN_REGEX = Regex("""[?&]clen=(\d+)""")
    }
}
