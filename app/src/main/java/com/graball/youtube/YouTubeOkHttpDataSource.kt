package com.graball.youtube

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/**
 * Lightweight Media3 [DataSource] backed by OkHttp 4.12 so ExoPlayer streams
 * `googlevideo.com` media & HLS segments over the **exact same** HTTP/2, TLS,
 * IPv4-first DNS, proxy, and VPN socket-eviction stack as [YouTubeInnerTube].
 *
 * Eliminates VPN / dual-stack `HTTP 403` mismatches caused by OkHttp resolving
 * `/youtubei/v1/player` over one IP family while Android's `HttpURLConnection`
 * (`DefaultHttpDataSource`) connects to `googlevideo.com` over another IP or
 * reuses stale pre-VPN keep-alive sockets.
 */
@OptIn(UnstableApi::class)
class YouTubeOkHttpDataSource(
    private val callFactory: Call.Factory,
    private val userAgent: String,
    private val defaultRequestProperties: Map<String, String> = emptyMap()
) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(
        private val callFactory: Call.Factory,
        private val userAgent: String,
        private val defaultRequestProperties: Map<String, String> = emptyMap()
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            YouTubeOkHttpDataSource(callFactory, userAgent, defaultRequestProperties)
    }

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var responseByteStream: InputStream? = null
    private var opened = false
    private var bytesToRead = C.LENGTH_UNSET.toLong()
    private var bytesRead = 0L

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRead = 0L
        bytesToRead = 0L
        transferInitializing(dataSpec)

        val request = buildRequest(dataSpec)
        val resp: Response = try {
            callFactory.newCall(request).execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }
        response = resp
        val code = resp.code
        val headers = resp.headers.toMultimap()

        if (!resp.isSuccessful) {
            val errorBody = try {
                resp.body?.bytes() ?: ByteArray(0)
            } catch (_: IOException) {
                ByteArray(0)
            }
            closeConnectionQuietly()
            val cause = if (code == 416) {
                androidx.media3.datasource.DataSourceException(
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                )
            } else {
                null
            }
            throw HttpDataSource.InvalidResponseCodeException(
                code,
                resp.message,
                cause,
                headers,
                dataSpec,
                errorBody
            )
        }

        val body = resp.body
        if (body == null) {
            closeConnectionQuietly()
            throw HttpDataSource.HttpDataSourceException(
                "Empty HTTP response body",
                dataSpec,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }
        responseByteStream = body.byteStream()

        val contentLength = body.contentLength()
        bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (contentLength != -1L) {
            contentLength
        } else {
            C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val bytesRemaining = bytesToRead - bytesRead
            if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
            val readLen = min(length.toLong(), bytesRemaining).toInt()
            return readInternal(buffer, offset, readLen)
        }
        return readInternal(buffer, offset, length)
    }

    private fun readInternal(buffer: ByteArray, offset: Int, readLength: Int): Int {
        val stream = responseByteStream ?: return C.RESULT_END_OF_INPUT
        val read = try {
            stream.read(buffer, offset, readLength)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec ?: DataSpec(Uri.EMPTY),
                HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        }
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRead += read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? {
        val finalUrl = response?.request?.url?.toString()
        return if (!finalUrl.isNullOrBlank()) Uri.parse(finalUrl) else dataSpec?.uri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return response?.headers?.toMultimap() ?: emptyMap()
    }

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
            closeConnectionQuietly()
        } else {
            closeConnectionQuietly()
        }
        dataSpec = null
    }

    private fun buildRequest(spec: DataSpec): Request {
        val builder = Request.Builder().url(spec.uri.toString())
        for ((k, v) in defaultRequestProperties) {
            builder.header(k, v)
        }
        for ((k, v) in spec.httpRequestHeaders) {
            builder.header(k, v)
        }
        if (userAgent.isNotBlank()) {
            builder.header("User-Agent", userAgent)
        }
        if (!spec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
            builder.header("Accept-Encoding", "identity")
        }
        val pos = spec.position
        val len = spec.length
        if (pos > 0L || len != C.LENGTH_UNSET.toLong()) {
            val range = if (len != C.LENGTH_UNSET.toLong()) {
                "bytes=$pos-${pos + len - 1L}"
            } else {
                "bytes=$pos-"
            }
            builder.header("Range", range)
        }
        return builder.get().build()
    }

    private fun closeConnectionQuietly() {
        try {
            responseByteStream?.close()
        } catch (_: Exception) {
        }
        responseByteStream = null
        try {
            response?.close()
        } catch (_: Exception) {
        }
        response = null
    }
}
