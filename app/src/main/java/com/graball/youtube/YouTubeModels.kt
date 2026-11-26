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
    val isMuxed: Boolean,
    val contentLengthBytes: Long = 0L,
    val audioTrackId: String = "",
    val audioDisplayName: String = "",
    val isOriginalAudio: Boolean = false,
    val isDefaultAudio: Boolean = false,
    val isDubbedOrDescriptive: Boolean = false
) {
    fun estimatedSizeBytes(durationSec: Long): Long {
        if (contentLengthBytes > 0L) return contentLengthBytes
        if (bitrate > 0 && durationSec > 0L) {
            return (bitrate.toLong() * durationSec) / 8L
        }
        return 0L
    }
}

data class AudioTrackOption(
    val id: String,
    val label: String,
    val isOriginal: Boolean,
    val isDefault: Boolean
)

data class CaptionTrack(
    val langCode: String,
    val label: String,
    val url: String,
    val mimeType: String = "application/ttml+xml",
    val isAuto: Boolean = false
)

data class HlsAudioTrack(
    val groupId: String,
    val name: String,
    val language: String,
    val url: String,
    val isOriginal: Boolean = false,
    val isDefault: Boolean = false,
    val isDubbedOrDescriptive: Boolean = false
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
    val hlsAudioTracks: List<HlsAudioTrack> = emptyList(),
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

    /**
     * Pick the best audio-only stream, strictly preferring the video's
     * ORIGINAL language track over AI auto-dubs / foreign dubs / descriptive
     * tracks, and then preferring M4A/AAC highest bitrate within that track.
     */
    fun bestAudio(preferredTrackId: String? = null): StreamFormat? {
        if (audioStreams.isEmpty()) return null

        fun pickBestInPool(pool: List<StreamFormat>): StreamFormat? {
            if (pool.isEmpty()) return null
            val m4a = pool
                .filter { it.mimeType.contains("mp4", ignoreCase = true) || it.mimeType.contains("aac", ignoreCase = true) }
                .maxByOrNull { it.bitrate }
            return m4a ?: pool.maxByOrNull { it.bitrate }
        }

        if (!preferredTrackId.isNullOrBlank()) {
            val matching = audioStreams.filter { it.audioTrackId == preferredTrackId }
            if (matching.isNotEmpty()) return pickBestInPool(matching)
        }

        // 1) Creator's original audio track (never an AI dub!)
        val originals = audioStreams.filter { it.isOriginalAudio }
        if (originals.isNotEmpty()) return pickBestInPool(originals)

        // 2) YouTube default audio track (as long as it isn't flagged dubbed/descriptive)
        val defaults = audioStreams.filter { it.isDefaultAudio && !it.isDubbedOrDescriptive }
        if (defaults.isNotEmpty()) return pickBestInPool(defaults)

        // 3) Any non-dubbed, non-descriptive track
        val nonDubbed = audioStreams.filter { !it.isDubbedOrDescriptive }
        if (nonDubbed.isNotEmpty()) {
            val enNonDubbed = nonDubbed.filter {
                it.audioTrackId.startsWith("en", ignoreCase = true) ||
                    it.audioDisplayName.contains("english", ignoreCase = true)
            }
            if (enNonDubbed.isNotEmpty() && nonDubbed.any { it.audioTrackId.isNotBlank() }) {
                return pickBestInPool(enNonDubbed)
            }
            return pickBestInPool(nonDubbed)
        }

        return pickBestInPool(audioStreams)
    }

    /** Distinct audio languages available on this video (empty or size 1 for normal single-audio videos). */
    fun availableAudioTracks(): List<AudioTrackOption> {
        val grouped = LinkedHashMap<String, AudioTrackOption>()
        for (f in audioStreams) {
            val id = f.audioTrackId
            if (id.isBlank()) continue
            val label = f.audioDisplayName.ifBlank { id.substringBefore('.').uppercase() }
            val existing = grouped[id]
            if (existing == null) {
                grouped[id] = AudioTrackOption(
                    id = id,
                    label = label,
                    isOriginal = f.isOriginalAudio,
                    isDefault = f.isDefaultAudio
                )
            } else if (f.isOriginalAudio && !existing.isOriginal) {
                grouped[id] = existing.copy(isOriginal = true)
            }
        }
        return grouped.values.sortedWith(
            compareByDescending<AudioTrackOption> { it.isOriginal }
                .thenByDescending { it.isDefault }
                .thenBy { it.label.lowercase() }
        )
    }

    /** Exact or bitrate-estimated audio file size in bytes (pass [mp3BitrateKbps] for MP3 transcode). */
    fun estimatedAudioSizeBytes(mp3BitrateKbps: Int? = null): Long {
        if (mp3BitrateKbps != null && durationSec > 0L) {
            return (mp3BitrateKbps.toLong() * 1000L * durationSec) / 8L
        }
        val audio = bestAudio() ?: return 0L
        return audio.estimatedSizeBytes(durationSec)
    }

    /** Exact or bitrate-estimated merged video+audio file size in bytes for [targetHeight]. */
    fun estimatedVideoSizeBytes(targetHeight: Int): Long {
        val video = pickVideoStream(targetHeight, forDownload = true)
        val audio = bestAudio()
        if (video != null && normalizeHeight(video.height) == targetHeight && audio != null) {
            val vBytes = video.estimatedSizeBytes(durationSec)
            val aBytes = audio.estimatedSizeBytes(durationSec)
            if (vBytes > 0L) return vBytes + aBytes
        }
        val hls = pickHlsVariant(targetHeight)
        if (hls != null && normalizeHeight(hls.height) == targetHeight) {
            val bytes = hls.estimatedSizeBytes(durationSec)
            if (bytes > 0L) return bytes
        }
        val muxed = pickMuxedStream(targetHeight)
        if (muxed != null && normalizeHeight(muxed.height) == targetHeight) {
            val bytes = muxed.estimatedSizeBytes(durationSec)
            if (bytes > 0L) return bytes
        }
        return 0L
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
        val pool = muxedStreams.filter { it.isOriginalAudio }.ifEmpty {
            muxedStreams.filter { !it.isDubbedOrDescriptive }.ifEmpty { muxedStreams }
        }
        return if (targetHeight == null) {
            pool.maxByOrNull { normalizeHeight(it.height) }
        } else {
            pool.filter { normalizeHeight(it.height) in 1..targetHeight }
                .maxByOrNull { normalizeHeight(it.height) }
                ?: pool.minByOrNull { kotlin.math.abs(normalizeHeight(it.height) - targetHeight) }
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
