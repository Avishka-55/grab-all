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
    val videoCount: String = "",
    val isLive: Boolean = false,
    val channelId: String = "",
    val channelAvatarUrl: String = ""
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

data class CaptionTrack(
    val langCode: String,
    val label: String,
    val vttUrl: String,
    val isAuto: Boolean = false
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
    val hlsVariants: List<StreamFormat> = emptyList(),
    val videoStreams: List<StreamFormat>,
    val audioStreams: List<StreamFormat>,
    val muxedStreams: List<StreamFormat>,
    val userAgent: String,
    val captionTracks: List<CaptionTrack> = emptyList()
) {
    /** Distinct playable video heights sorted descending (e.g. 2160, 1440, 1080, 720, 480, 360). */
    fun availableHeights(): List<Int> {
        val heights = (
            videoStreams.map { it.height } +
                hlsVariants.map { it.height } +
                muxedStreams.map { it.height }
            )
            .filter { it > 0 }
            .map { normalizeHeight(it) }
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
     * Pick a video-only stream matching [targetHeight] (or highest <= targetHeight).
     * Resolves height FIRST, then prefers H.264 (avc1) -> VP9 -> other within that
     * height so 144p-1080p use universal H.264 while 1440p & 2160p cleanly pick VP9
     * instead of getting silently downgraded to 1080p.
     */
    fun pickVideoStream(targetHeight: Int?, forDownload: Boolean = false): StreamFormat? {
        return pickFromCandidates(
            streams = videoStreams,
            targetHeight = targetHeight,
            autoMaxHeight = if (forDownload) Int.MAX_VALUE else 720
        )
    }

    /**
     * Pick an exact per-resolution HLS variant playlist (e.g. `web_safari` muxed
     * 144p-1080p `.m3u8`). Auto mode defaults to crisp 720p (never 360p).
     */
    fun pickHlsVariant(targetHeight: Int?): StreamFormat? {
        return pickFromCandidates(
            streams = hlsVariants,
            targetHeight = targetHeight,
            autoMaxHeight = 720
        )
    }

    /** Fallback muxed stream (video + audio in one file) when adaptive merge isn't available. */
    fun pickMuxedStream(targetHeight: Int?): StreamFormat? {
        if (muxedStreams.isEmpty()) return null
        return if (targetHeight == null) {
            muxedStreams.maxByOrNull { normalizeHeight(it.height) }
        } else {
            muxedStreams.filter { normalizeHeight(it.height) in 1..targetHeight }
                .maxByOrNull { normalizeHeight(it.height) }
                ?: muxedStreams.minByOrNull { kotlin.math.abs(normalizeHeight(it.height) - targetHeight) }
        }
    }

    private fun pickFromCandidates(
        streams: List<StreamFormat>,
        targetHeight: Int?,
        autoMaxHeight: Int
    ): StreamFormat? {
        if (streams.isEmpty()) return null
        val byHeight = streams
            .filter { it.height > 0 }
            .groupBy { normalizeHeight(it.height) }
        if (byHeight.isEmpty()) return streams.maxByOrNull { it.bitrate }

        val chosenHeight = if (targetHeight == null) {
            byHeight.keys.filter { it <= autoMaxHeight }.maxOrNull()
                ?: byHeight.keys.minOrNull()!!
        } else {
            byHeight.keys.filter { it <= targetHeight }.maxOrNull()
                ?: byHeight.keys.minByOrNull { kotlin.math.abs(it - targetHeight) }!!
        }
        val group = byHeight[chosenHeight].orEmpty()
        return group.filter { it.mimeType.contains("avc1", ignoreCase = true) }
            .maxByOrNull { it.bitrate }
            ?: group.filter {
                it.mimeType.contains("vp9", ignoreCase = true) ||
                    it.mimeType.contains("vp09", ignoreCase = true)
            }.maxByOrNull { it.bitrate }
            ?: group.maxByOrNull { it.bitrate }
    }

    companion object {
        private val STANDARD_HEIGHTS = intArrayOf(144, 240, 360, 480, 720, 1080, 1440, 2160)

        fun normalizeHeight(rawHeight: Int): Int {
            for (std in STANDARD_HEIGHTS) {
                if (kotlin.math.abs(rawHeight - std) <= 12) return std
            }
            return rawHeight
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
