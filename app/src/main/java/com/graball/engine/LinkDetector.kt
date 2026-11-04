package com.graball.engine

/**
 * What a link is, from its shape alone — no network call, so the
 * Grab panel appears instantly.
 *
 * A /watch link that also carries a playlist address is still a
 * VIDEO: it is the video the user opened, and downloading "the
 * playlist" from it would be a surprise.
 */
enum class LinkKind { VIDEO, PLAYLIST, UNKNOWN }

object LinkDetector {

    fun detect(url: String, strictYouTube: Boolean = false): LinkKind {
        val lower = url.lowercase()
        if (lower.contains("youtube.com/playlist") ||
            lower.contains("music.youtube.com/playlist")
        ) {
            return LinkKind.PLAYLIST
        }
        if (lower.contains("youtube.com/watch") ||
            lower.contains("youtube.com/shorts/") ||
            lower.contains("youtube.com/embed/") ||
            lower.contains("youtube.com/live/") ||
            lower.contains("music.youtube.com/watch") ||
            lower.contains("youtu.be/")
        ) {
            return LinkKind.VIDEO
        }
        // Main screen: any other http(s) link is still a single
        // download (yt-dlp handles many sites) — the video panel.
        // Browser (strict): anything else is "not detected".
        return if (!strictYouTube && lower.startsWith("http")) {
            LinkKind.VIDEO
        } else {
            LinkKind.UNKNOWN
        }
    }

    /** Short preview for a panel header ("youtube.com/watch?v=…"). */
    fun preview(url: String): String {
        val stripped = url.removePrefix("https://").removePrefix("http://")
        return if (stripped.length > 48) stripped.take(48) + "…" else stripped
    }
}
