package com.graball.youtube

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.graball.LoginActivity
import com.graball.R
import com.graball.engine.WebViewCookieSync
import java.util.concurrent.Executors

/**
 * Native YouTube Channel Page: shows channel avatar, subscriber count,
 * live Subscribe button, and infinite-scroll Videos & Playlists tabs.
 */
class YouTubeChannelActivity : AppCompatActivity() {

    private lateinit var topTitle: TextView
    private lateinit var avatarView: ImageView
    private lateinit var channelName: TextView
    private lateinit var channelMeta: TextView
    private lateinit var subscribeBtn: TextView
    private lateinit var tabVideos: TextView
    private lateinit var tabPlaylists: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var adapter: YtItemAdapter

    private val io = Executors.newSingleThreadExecutor()
    private var channelId: String = ""
    private var activeTab = YouTubeInnerTube.ChannelTab.VIDEOS
    private var isSubscribed = false
    private var continuationToken: String? = null
    private var isLoadingMore = false
    private var loadedItems = mutableListOf<YtItem>()
    private var requestGen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_channel)

        channelId = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty().trim()
        val initialName = intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty().ifBlank { "Channel" }
        val initialAvatar = intent.getStringExtra(EXTRA_CHANNEL_AVATAR).orEmpty()
        isSubscribed = intent.getBooleanExtra(EXTRA_IS_SUBSCRIBED, false)

        if (channelId.isBlank()) {
            finish()
            return
        }

        topTitle = findViewById(R.id.channelTopTitle)
        avatarView = findViewById(R.id.channelAvatar)
        channelName = findViewById(R.id.channelName)
        channelMeta = findViewById(R.id.channelMeta)
        subscribeBtn = findViewById(R.id.channelSubscribeBtn)
        tabVideos = findViewById(R.id.channelTabVideos)
        tabPlaylists = findViewById(R.id.channelTabPlaylists)
        recycler = findViewById(R.id.channelList)
        loading = findViewById(R.id.channelLoading)
        emptyView = findViewById(R.id.channelEmpty)

        avatarView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        avatarView.clipToOutline = true

        topTitle.text = initialName
        channelName.text = initialName
        if (initialAvatar.isNotBlank()) {
            ThumbnailLoader.load(initialAvatar, avatarView, targetWidthPx = 160)
        }
        updateSubscribeButton()

        findViewById<TextView>(R.id.channelBack).setOnClickListener { finish() }
        subscribeBtn.setOnClickListener { toggleSubscribe() }
        tabVideos.setOnClickListener { selectTab(YouTubeInnerTube.ChannelTab.VIDEOS) }
        tabPlaylists.setOnClickListener { selectTab(YouTubeInnerTube.ChannelTab.PLAYLISTS) }

        adapter = YtItemAdapter(
            context = this,
            onClick = { item, _ ->
                if (item.isPlaylist) {
                    startActivity(
                        Intent(this, YouTubeActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra("start_playlist_id", item.id)
                        }
                    )
                } else {
                    YouTubePlaybackService.withService(this) { svc ->
                        svc.playItem(item, audioOnly = false)
                    }
                    startActivity(
                        Intent(this, YouTubePlayerActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                    )
                }
            },
            onDownload = { item -> DownloadSheetHelper.show(this, item) },
            onQueue = { item, _ ->
                YouTubePlaybackService.withService(this) { svc -> svc.addToQueue(item) }
                Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show()
            }
        )
        val lm = LinearLayoutManager(this)
        recycler.layoutManager = lm
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingMore || continuationToken.isNullOrBlank()) return
                val total = lm.itemCount
                if (total > 0 && lm.findLastVisibleItemPosition() >= total - 6) {
                    loadMore()
                }
            }
        })

        selectTab(YouTubeInnerTube.ChannelTab.VIDEOS)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun selectTab(tab: YouTubeInnerTube.ChannelTab) {
        activeTab = tab
        val isVideos = tab == YouTubeInnerTube.ChannelTab.VIDEOS
        tabVideos.setBackgroundResource(if (isVideos) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
        tabVideos.setTextColor(ContextCompat.getColor(this, if (isVideos) R.color.graball_text else R.color.graball_subtext))
        tabPlaylists.setBackgroundResource(if (!isVideos) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg)
        tabPlaylists.setTextColor(ContextCompat.getColor(this, if (!isVideos) R.color.graball_text else R.color.graball_subtext))
        adapter.setLargeCardMode(isVideos)
        loadTab(tab)
    }

    private fun loadTab(tab: YouTubeInnerTube.ChannelTab) {
        val gen = ++requestGen
        continuationToken = null
        isLoadingMore = false
        loading.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        io.execute {
            val page = YouTubeInnerTube.fetchChannelTab(applicationContext, channelId, tab = tab)
            runOnUiThread {
                if (gen != requestGen || isFinishing) return@runOnUiThread
                loading.visibility = View.GONE
                if (page == null) {
                    emptyView.text = "Couldn't load channel"
                    emptyView.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                if (page.title.isNotBlank() && page.title != "Channel") {
                    topTitle.text = page.title
                    channelName.text = page.title
                }
                val meta = listOf(page.handle, page.subscriberCount).filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotBlank()) channelMeta.text = meta
                if (page.avatarUrl.isNotBlank()) {
                    ThumbnailLoader.load(page.avatarUrl, avatarView, targetWidthPx = 160)
                }
                isSubscribed = page.isSubscribed
                updateSubscribeButton()

                loadedItems = page.items.toMutableList()
                continuationToken = page.continuationToken
                adapter.submitList(loadedItems, YouTubePlaybackService.currentState.currentItem?.id)
                if (loadedItems.isEmpty()) {
                    emptyView.text = if (tab == YouTubeInnerTube.ChannelTab.VIDEOS) {
                        "No videos found on this channel"
                    } else {
                        "No playlists found on this channel"
                    }
                    emptyView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun loadMore() {
        val tok = continuationToken ?: return
        val tab = activeTab
        val gen = requestGen
        isLoadingMore = true
        io.execute {
            val page = YouTubeInnerTube.fetchChannelTab(
                context = applicationContext,
                channelId = channelId,
                tab = tab,
                continuationToken = tok
            )
            runOnUiThread {
                if (gen != requestGen || isFinishing) {
                    isLoadingMore = false
                    return@runOnUiThread
                }
                isLoadingMore = false
                if (page == null) {
                    continuationToken = null
                    return@runOnUiThread
                }
                val existing = loadedItems.map { it.id }.toHashSet()
                val fresh = page.items.filter { existing.add(it.id) }
                continuationToken = if (fresh.isNotEmpty() && page.continuationToken != tok) {
                    page.continuationToken
                } else {
                    null
                }
                if (fresh.isNotEmpty()) {
                    loadedItems.addAll(fresh)
                    adapter.appendItems(fresh)
                }
            }
        }
    }

    private fun updateSubscribeButton() {
        subscribeBtn.text = if (isSubscribed) "✓ Subscribed" else "🔔 Subscribe"
        subscribeBtn.setBackgroundResource(
            if (isSubscribed) R.drawable.yt_chip_bg else R.drawable.yt_chip_selected
        )
    }

    private fun toggleSubscribe() {
        if (WebViewCookieSync.buildYouTubeAuthHeaders(this) == null) {
            AlertDialog.Builder(this)
                .setTitle("Sign in to YouTube")
                .setMessage("Sign in with your Google account inside GrabAll to subscribe to channels.")
                .setPositiveButton("Log in") { _, _ -> startActivity(Intent(this, LoginActivity::class.java)) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val prev = isSubscribed
        val next = !prev
        isSubscribed = next
        updateSubscribeButton()
        io.execute {
            val ok = YouTubeInnerTube.setSubscribe(applicationContext, channelId, next)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ok) {
                    Toast.makeText(this, if (next) "Subscribed" else "Unsubscribed", Toast.LENGTH_SHORT).show()
                } else {
                    isSubscribed = prev
                    updateSubscribeButton()
                    Toast.makeText(this, "Couldn't update subscription", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "channel_id"
        private const val EXTRA_CHANNEL_NAME = "channel_name"
        private const val EXTRA_CHANNEL_AVATAR = "channel_avatar"
        private const val EXTRA_IS_SUBSCRIBED = "is_subscribed"

        fun createIntent(
            context: Context,
            channelId: String,
            channelName: String = "",
            channelAvatar: String = "",
            isSubscribed: Boolean = false
        ): Intent = Intent(context, YouTubeChannelActivity::class.java).apply {
            putExtra(EXTRA_CHANNEL_ID, channelId)
            putExtra(EXTRA_CHANNEL_NAME, channelName)
            putExtra(EXTRA_CHANNEL_AVATAR, channelAvatar)
            putExtra(EXTRA_IS_SUBSCRIBED, isSubscribed)
        }
    }
}
