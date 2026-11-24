package com.graball.youtube

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private val isQueueMode: () -> Boolean = { false }
) : RecyclerView.Adapter<YtItemAdapter.Holder>() {

    private val items: MutableList<YtItem> = mutableListOf()
    private var activeVideoId: String? = null

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_yt_card, parent, false)
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

        val metaParts = listOf(item.channel, item.views).filter { it.isNotBlank() }
        holder.meta.text = metaParts.joinToString(" · ").ifBlank {
            if (item.isPlaylist) "YouTube Playlist" else "YouTube"
        }

        val badgeText = when {
            item.isPlaylist -> item.videoCount.ifBlank { "Playlist" }
            item.duration.isNotBlank() -> item.duration
            else -> ""
        }
        if (badgeText.isNotBlank()) {
            holder.badge.text = badgeText
            holder.badge.visibility = View.VISIBLE
        } else {
            holder.badge.visibility = View.GONE
        }

        ThumbnailLoader.load(item.thumbnailUrl, holder.thumb)

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
        val title: TextView = view.findViewById(R.id.itemTitle)
        val meta: TextView = view.findViewById(R.id.itemMeta)
        val downloadBtn: TextView = view.findViewById(R.id.itemDownload)
        val queueBtn: TextView = view.findViewById(R.id.itemQueue)
        val bookmark: TextView = view.findViewById(R.id.itemBookmark)
    }
}
