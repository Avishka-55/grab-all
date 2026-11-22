package com.graball.youtube

/**
 * Lightweight data models for the native NewPipe-style YouTube player.
 * Kept immutable and flat so RecyclerView lists and background services
 * stay low-allocation and low-RAM on older 32-bit devices.
 */
data class YtItem(
    val id: String,
    val title: String,
    val channel: String,
    val duration: String = "",
    val views: String = "",
    val thumbnailUrl: String = "",
    val isPlaylist: Boolean = false,
    val videoCount: String = ""
) {
    val watchUrl: String
        get() = if (isPlaylist) {
            "https://www.youtube.com/playlist?list=$id"
        } else {
            "https://www.youtube.com/watch?v=$id"
        }
}

data class StreamFormat(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val qualityLabel: String,
    val height: Int,
    val bitrate: Int,
    val isAudioOnly: Boolean,
    val isVideoOnly: Boolean,
    val isMuxed: Boolean
)

data class VideoStreamInfo(
    val videoId: String,
    val title: String,
    val channel: String,
    val durationSec: Long,
    val viewCount: String,
    val description: String,
    val thumbnailUrl: String,
    val hlsUrl: String?,
    val videoStreams: List<StreamFormat>,
    val audioStreams: List<StreamFormat>,
    val muxedStreams: List<StreamFormat>,
    val userAgent: String
) {
    /** Distinct playable video heights sorted descending (e.g. 1080, 720, 480, 360). */
    fun availableHeights(): List<Int> {
        val heights = (videoStreams.map { it.height } + muxedStreams.map { it.height })
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
        return heights
    }

    /** Pick the best audio-only stream (prefer M4A/AAC for universal hardware decode). */
    fun bestAudio(): StreamFormat? {
        val m4a = audioStreams
            .filter { it.mimeType.contains("mp4", ignoreCase = true) || it.mimeType.contains("aac", ignoreCase = true) }
            .maxByOrNull { it.bitrate }
        return m4a ?: audioStreams.maxByOrNull { it.bitrate }
    }

    /**
     * Pick a video-only stream closest to [targetHeight] (or <= targetHeight),
     * preferring H.264 (avc1) for low CPU / battery hardware decoding on all devices.
     */
    fun pickVideoStream(targetHeight: Int?): StreamFormat? {
        if (videoStreams.isEmpty()) return null
        val h264 = videoStreams.filter {
            it.mimeType.contains("avc1", ignoreCase = true) ||
                it.mimeType.contains("mp4", ignoreCase = true)
        }.ifEmpty { videoStreams }
        return if (targetHeight == null) {
            // Auto: cap at 720p on low-RAM devices, 1080p otherwise
            h264.filter { it.height in 1..1080 }.maxByOrNull { it.height * 10_000L + it.bitrate }
                ?: h264.maxByOrNull { it.height }
        } else {
            h264.filter { it.height in 1..targetHeight }.maxByOrNull { it.height * 10_000L + it.bitrate }
                ?: h264.minByOrNull { kotlin.math.abs(it.height - targetHeight) }
        }
    }

    /** Fallback muxed stream (video + audio in one file) when adaptive merge isn't available. */
    fun pickMuxedStream(targetHeight: Int?): StreamFormat? {
        if (muxedStreams.isEmpty()) return null
        return if (targetHeight == null) {
            muxedStreams.maxByOrNull { it.height }
        } else {
            muxedStreams.filter { it.height in 1..targetHeight }.maxByOrNull { it.height }
                ?: muxedStreams.minByOrNull { kotlin.math.abs(it.height - targetHeight) }
        }
    }
}

data class SponsorSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String
)

data class PlaylistDetails(
    val playlistId: String,
    val title: String,
    val channel: String,
    val items: List<YtItem>
)

data class WatchHistoryEntry(
    val item: YtItem,
    val positionMs: Long,
    val watchedAt: Long
)
