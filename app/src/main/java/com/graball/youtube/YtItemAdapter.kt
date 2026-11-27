package com.graball.youtube

import android.content.Context
import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.graball.R

/**
 * Lightweight RecyclerView adapter shared across Search, Explore, Playlists,
 * Bookmarks, History, Queue, and Related Videos.
 */
class YtItemAdapter(
    private val context: Context,
    private val onClick: (YtItem, Int) -> Unit,
    private val onDownload: (YtItem) -> Unit,
    private val onQueue: (YtItem, Int) -> Unit,
    private val onBookmarkChanged: ((YtItem, Boolean) -> Unit)? = null,
    private val isQueueMode: () -> Boolean = { false },
    private val onChannelClick: ((YtItem) -> Unit)? = null
) : RecyclerView.Adapter<YtItemAdapter.Holder>() {

    private val items: MutableList<YtItem> = mutableListOf()
    private var activeVideoId: String? = null
    private var largeCardMode: Boolean = false

    fun setLargeCardMode(enabled: Boolean) {
        if (largeCardMode != enabled) {
            largeCardMode = enabled
            notifyDataSetChanged()
        }
    }

    fun submitList(newItems: List<YtItem>, activeId: String? = activeVideoId) {
        items.clear()
        items.addAll(newItems)
        activeVideoId = activeId
        notifyDataSetChanged()
    }

    fun appendItems(moreItems: List<YtItem>) {
        if (moreItems.isEmpty()) return
        val start = items.size
        items.addAll(moreItems)
        notifyItemRangeInserted(start, moreItems.size)
    }

    fun setActiveVideoId(id: String?) {
        if (activeVideoId != id) {
            activeVideoId = id
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (largeCardMode && !items[position].isPlaylist) VIEW_TYPE_LARGE else VIEW_TYPE_COMPACT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layoutRes = if (viewType == VIEW_TYPE_LARGE) {
            R.layout.item_yt_card_large
        } else {
            R.layout.item_yt_card
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(layoutRes, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        val isCurrent = !item.isPlaylist && item.id == activeVideoId
        holder.title.setTextColor(
            ContextCompat.getColor(
                context,
                if (isCurrent) R.color.graball_orange else R.color.graball_text
            )
        )

        val metaParts = listOf(item.channel, item.views, item.publishedTime).filter { it.isNotBlank() }
        holder.meta.text = metaParts.joinToString(" · ").ifBlank {
            if (item.isPlaylist) "YouTube Playlist" else "YouTube"
        }

        val isLiveItem = !item.isPlaylist && (item.isLive || item.duration.equals("LIVE", ignoreCase = true))
        val badgeText = when {
            item.isPlaylist -> item.videoCount.ifBlank { "Playlist" }
            isLiveItem -> "● LIVE"
            item.duration.isNotBlank() -> item.duration
            else -> ""
        }
        if (badgeText.isNotBlank()) {
            holder.badge.text = badgeText
            holder.badge.setBackgroundResource(
                if (isLiveItem) R.drawable.yt_live_badge_bg else R.drawable.yt_badge_bg
            )
            holder.badge.visibility = View.VISIBLE
        } else {
            holder.badge.visibility = View.GONE
        }

        val targetThumbWidth = if (largeCardMode && !item.isPlaylist) 720 else 360
        ThumbnailLoader.load(item.thumbnailUrl, holder.thumb, targetWidthPx = targetThumbWidth)

        val canOpenChannel = onChannelClick != null && !item.isPlaylist && item.channelId.isNotBlank()
        if (canOpenChannel && holder.channelClickArea != null && holder.channelAvatar != null) {
            holder.channelClickArea.visibility = View.VISIBLE
            holder.channelInitial?.text = item.channel.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "▶"
            if (item.channelAvatarUrl.isNotBlank()) {
                holder.channelAvatar.visibility = View.VISIBLE
                ThumbnailLoader.load(item.channelAvatarUrl, holder.channelAvatar, targetWidthPx = 96)
            } else {
                holder.channelAvatar.setImageDrawable(null)
                holder.channelAvatar.visibility = View.GONE
            }
            holder.channelClickArea.setOnClickListener { onChannelClick?.invoke(item) }
        } else {
            holder.channelClickArea?.visibility = View.GONE
            holder.channelClickArea?.setOnClickListener(null)
        }

        val bookmarked = YouTubeStore.isBookmarked(context, item.id)
        holder.bookmark.text = if (bookmarked) "★" else "☆"
        holder.bookmark.setTextColor(
            ContextCompat.getColor(
                context,
                if (bookmarked) R.color.graball_orange else R.color.graball_subtext
            )
        )

        val queueMode = isQueueMode()
        holder.queueBtn.visibility = if (item.isPlaylist && !queueMode) View.GONE else View.VISIBLE
        holder.queueBtn.text = if (queueMode) "Remove" else "+ Queue"

        holder.itemView.setOnClickListener { onClick(item, position) }
        holder.downloadBtn.setOnClickListener { onDownload(item) }
        holder.queueBtn.setOnClickListener { onQueue(item, position) }
        holder.bookmark.setOnClickListener {
            val now = YouTubeStore.toggleBookmark(context, item)
            holder.bookmark.text = if (now) "★" else "☆"
            holder.bookmark.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (now) R.color.graball_orange else R.color.graball_subtext
                )
            )
            onBookmarkChanged?.invoke(item, now)
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.itemThumb)
        val badge: TextView = view.findViewById(R.id.itemBadge)
        val channelClickArea: View? = view.findViewById(R.id.itemChannelClickArea)
        val channelInitial: TextView? = view.findViewById(R.id.itemChannelInitial)
        val channelAvatar: ImageView? = view.findViewById(R.id.itemChannelAvatar)
        val title: TextView = view.findViewById(R.id.itemTitle)
        val meta: TextView = view.findViewById(R.id.itemMeta)
        val downloadBtn: TextView = view.findViewById(R.id.itemDownload)
        val queueBtn: TextView = view.findViewById(R.id.itemQueue)
        val bookmark: TextView = view.findViewById(R.id.itemBookmark)

        init {
            val circleOutline = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
            channelInitial?.outlineProvider = circleOutline
            channelInitial?.clipToOutline = true
            channelAvatar?.outlineProvider = circleOutline
            channelAvatar?.clipToOutline = true
        }
    }

    companion object {
        private const val VIEW_TYPE_COMPACT = 0
        private const val VIEW_TYPE_LARGE = 1
    }
}
