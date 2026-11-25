package com.graball.youtube

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.graball.BrowseActivity
import com.graball.LoginActivity
import com.graball.R
import com.graball.engine.WebViewCookieSync
import java.util.concurrent.Executors

/**
 * Lightweight, 100% ad-free NewPipe-style YouTube hub:
 * - Fast InnerTube search (All / Videos / Playlists / Music)
 * - Explore / Trending feed
 * - In-app Playlist viewer with Play All + Download Playlist
 * - Local Bookmarks, Watch History, and live Background Play Queue
 * - Docked in-app mini-player bar (works on Android 5 through Android 16)
 */
class YouTubeActivity : AppCompatActivity() {

    private enum class Tab { EXPLORE, BOOKMARKS, HISTORY, QUEUE }
    private enum class SignedInFeedMode { NONE, SUBSCRIPTIONS, WATCH_LATER }
    private enum class PagingMode { NONE, HOME, MUSIC, SUBSCRIPTIONS, WATCH_LATER, SEARCH }

    private lateinit var searchInput: EditText
    private lateinit var searchClear: TextView
    private lateinit var accountBtn: TextView
    private lateinit var suggestionsBox: LinearLayout
    private lateinit var recentRow: HorizontalScrollView
    private lateinit var recentContainer: LinearLayout
    private lateinit var filterRow: HorizontalScrollView
    private lateinit var chipSubscriptions: TextView
    private lateinit var chipWatchLater: TextView
    private lateinit var topicRow: HorizontalScrollView
    private lateinit var topicContainer: LinearLayout
    private lateinit var refreshBanner: TextView
    private lateinit var playlistBanner: LinearLayout
    private lateinit var playlistTitle: TextView
    private lateinit var playlistMeta: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var emptyView: TextView

    // Mini-player views
    private lateinit var miniBar: LinearLayout
    private lateinit var miniThumb: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlayPause: TextView

    private lateinit var tabViews: Map<Tab, TextView>
    private lateinit var filterChips: Map<YouTubeInnerTube.SearchFilter, TextView>
    private lateinit var adapter: YtItemAdapter

    private val io = Executors.newSingleThreadExecutor()
    private val suggestIo = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var suggestRunnable: Runnable? = null
    private var suppressSuggestions = false
    private var currentTab = Tab.EXPLORE
    private var currentFilter = YouTubeInnerTube.SearchFilter.ALL
    private var signedInFeedMode = SignedInFeedMode.NONE
    private var pagingMode = PagingMode.NONE
    private var currentQuery: String? = null
    private var openPlaylist: PlaylistDetails? = null
    private var lastExploreItems: MutableList<YtItem> = mutableListOf()
    private var exploreContinuation: String? = null
    private var isLoadingMoreExplore = false
    private var homeTopicChips: List<YouTubeInnerTube.FeedChip> = emptyList()
    private var selectedTopicLabel: String? = null
    private var requestGen = 0
    private var lastAccountFingerprint: String? = null

    private val playbackListener: (YouTubePlaybackState) -> Unit = { state ->
        updateMiniPlayer(state)
        adapter.setActiveVideoId(state.currentItem?.id)
        if (currentTab == Tab.QUEUE) {
            renderQueue(state)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube)

        searchInput = findViewById(R.id.ytSearchInput)
        searchClear = findViewById(R.id.ytSearchClear)
        accountBtn = findViewById(R.id.ytAccountBtn)
        suggestionsBox = findViewById(R.id.ytSuggestionsBox)
        recentRow = findViewById(R.id.ytRecentRow)
        recentContainer = findViewById(R.id.ytRecentContainer)
        filterRow = findViewById(R.id.ytFilterRow)
        chipSubscriptions = findViewById(R.id.chipSubscriptions)
        chipWatchLater = findViewById(R.id.chipWatchLater)
        topicRow = findViewById(R.id.ytTopicRow)
        topicContainer = findViewById(R.id.ytTopicContainer)
        refreshBanner = findViewById(R.id.ytRefreshBanner)
        playlistBanner = findViewById(R.id.ytPlaylistBanner)
        playlistTitle = findViewById(R.id.ytPlaylistTitle)
        playlistMeta = findViewById(R.id.ytPlaylistMeta)
        recycler = findViewById(R.id.ytList)
        loading = findViewById(R.id.ytLoading)
        emptyView = findViewById(R.id.ytEmpty)

        miniBar = findViewById(R.id.miniPlayerBar)
        miniThumb = findViewById(R.id.miniThumb)
        miniTitle = findViewById(R.id.miniTitle)
        miniSub = findViewById(R.id.miniSub)
        miniPlayPause = findViewById(R.id.miniPlayPause)

        findViewById<TextView>(R.id.ytBack).setOnClickListener {
            handleBackNavigation()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString().orEmpty()
                searchClear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                if (suppressSuggestions) return
                scheduleSuggestions(text)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerSearch()
                true
            } else {
                false
            }
        }
        searchClear.setOnClickListener {
            hideSuggestions()
            searchInput.setText("")
            currentQuery = null
            openPlaylist = null
            playlistBanner.visibility = View.GONE
            refreshRecentChips()
            if (currentTab == Tab.EXPLORE) {
                loadExploreForCurrentFilter()
            }
        }
        findViewById<TextView>(R.id.ytSearchGo).setOnClickListener {
            triggerSearch()
        }
        accountBtn.setOnClickListener { onAccountButtonTapped() }
        lastAccountFingerprint = currentAccountFingerprint()
        refreshAccountButton()
        // Silently pre-warm the ~165ms native V8 base.js solver while browsing
        // so VPN playback resolves full 144p-1080p HLS immediately on tap.
        YouTubeJsSolver.prewarmInBackground(applicationContext)

        tabViews = mapOf(
            Tab.EXPLORE to findViewById(R.id.ytTabExplore),
            Tab.BOOKMARKS to findViewById(R.id.ytTabBookmarks),
            Tab.HISTORY to findViewById(R.id.ytTabHistory),
            Tab.QUEUE to findViewById(R.id.ytTabQueue)
        )
        tabViews.forEach { (tab, view) ->
            view.setOnClickListener {
                if (tab == Tab.EXPLORE && currentTab == Tab.EXPLORE && openPlaylist == null) {
                    recycler.smoothScrollToPosition(0)
                    refreshCurrentExploreFeed()
                } else {
                    selectTab(tab)
                }
            }
        }
        findViewById<TextView>(R.id.ytTabWeb).setOnClickListener {
            startActivity(
                Intent(this, com.graball.BrowseActivity::class.java)
                    .putExtra("start_url", "https://m.youtube.com")
            )
        }

        filterChips = mapOf(
            YouTubeInnerTube.SearchFilter.ALL to findViewById(R.id.chipAll),
            YouTubeInnerTube.SearchFilter.VIDEOS to findViewById(R.id.chipVideos),
            YouTubeInnerTube.SearchFilter.PLAYLISTS to findViewById(R.id.chipPlaylists),
            YouTubeInnerTube.SearchFilter.MUSIC to findViewById(R.id.chipMusic)
        )
        filterChips.forEach { (filter, chip) ->
            chip.setOnClickListener {
                signedInFeedMode = SignedInFeedMode.NONE
                selectedTopicLabel = null
                currentFilter = filter
                updateFilterChipStyles()
                val q = currentQuery
                if (!q.isNullOrBlank()) {
                    runSearch(q)
                } else {
                    loadExploreForCurrentFilter()
                }
            }
        }
        chipSubscriptions.setOnClickListener {
            signedInFeedMode = SignedInFeedMode.SUBSCRIPTIONS
            selectedTopicLabel = null
            currentQuery = null
            updateFilterChipStyles()
            loadSignedInSpecialFeed(SignedInFeedMode.SUBSCRIPTIONS)
        }
        chipWatchLater.setOnClickListener {
            signedInFeedMode = SignedInFeedMode.WATCH_LATER
            selectedTopicLabel = null
            currentQuery = null
            updateFilterChipStyles()
            loadSignedInSpecialFeed(SignedInFeedMode.WATCH_LATER)
        }

        findViewById<TextView>(R.id.ytPlaylistPlayAll).setOnClickListener {
            val pl = openPlaylist ?: return@setOnClickListener
            if (pl.items.isNotEmpty()) {
                YouTubePlaybackService.withService(this) { svc ->
                    svc.playQueue(pl.items, startIndex = 0, audioOnly = false)
                }
                openPlayerScreen()
            }
        }
        findViewById<TextView>(R.id.ytPlaylistSelect).setOnClickListener {
            val pl = openPlaylist ?: return@setOnClickListener
            PlaylistPickerDialog.show(this, pl)
        }
        findViewById<TextView>(R.id.ytPlaylistDownload).setOnClickListener {
            val pl = openPlaylist ?: return@setOnClickListener
            val plItem = YtItem(
                id = pl.playlistId,
                title = pl.title,
                channel = pl.channel,
                isPlaylist = true,
                videoCount = "${pl.items.size} videos"
            )
            DownloadSheetHelper.show(this, plItem)
        }
        findViewById<TextView>(R.id.ytPlaylistClose).setOnClickListener {
            closePlaylistView()
        }

        // Mini-player controls
        miniBar.setOnClickListener { openPlayerScreen() }
        miniPlayPause.setOnClickListener {
            YouTubePlaybackService.instance?.togglePlayPause()
        }
        findViewById<TextView>(R.id.miniNext).setOnClickListener {
            val moved = YouTubePlaybackService.instance?.playNext() == true
            if (!moved) {
                Toast.makeText(this, "End of queue", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<TextView>(R.id.miniClose).setOnClickListener {
            YouTubePlaybackService.instance?.stopPlayback()
        }

        adapter = YtItemAdapter(
            context = this,
            onClick = { item, pos -> onItemTapped(item, pos) },
            onDownload = { item -> DownloadSheetHelper.show(this, item) },
            onQueue = { item, pos ->
                if (currentTab == Tab.QUEUE) {
                    YouTubePlaybackService.instance?.removeFromQueue(pos)
                } else {
                    YouTubePlaybackService.withService(this) { svc ->
                        svc.addToQueue(item)
                    }
                    Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show()
                }
            },
            onBookmarkChanged = { _, nowBookmarked ->
                Toast.makeText(
                    this,
                    if (nowBookmarked) "Saved to bookmarks" else "Removed from bookmarks",
                    Toast.LENGTH_SHORT
                ).show()
                if (currentTab == Tab.BOOKMARKS) {
                    renderBookmarks()
                }
            },
            isQueueMode = { currentTab == Tab.QUEUE }
        )
        val layoutManager = LinearLayoutManager(this)
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                if (currentTab != Tab.EXPLORE || openPlaylist != null) return
                if (pagingMode == PagingMode.NONE) return
                if (isLoadingMoreExplore || exploreContinuation.isNullOrBlank()) return
                val total = layoutManager.itemCount
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (total > 0 && lastVisible >= total - 6) {
                    loadMoreCurrentFeed()
                }
            }
        })
        setupPullToRefresh()

        refreshRecentChips()
        updateForYouTabLabel()
        updateFilterChipStyles()
        val startPlaylistId = intent.getStringExtra("start_playlist_id")?.trim()
        val startQuery = intent.getStringExtra("start_query")?.trim()
        val startMode = intent.getStringExtra("start_mode")
        if (!startPlaylistId.isNullOrBlank()) {
            openPlaylistItems(
                YtItem(id = startPlaylistId, title = "Playlist", channel = "", isPlaylist = true)
            )
        } else if (!startQuery.isNullOrBlank()) {
            suppressSuggestions = true
            searchInput.setText(startQuery)
            searchInput.setSelection(startQuery.length)
            suppressSuggestions = false
            YouTubeStore.recordSearch(this, startQuery)
            refreshRecentChips()
            currentQuery = startQuery
            runSearch(startQuery)
        } else if (startMode == "music") {
            currentFilter = YouTubeInnerTube.SearchFilter.MUSIC
            updateFilterChipStyles()
            loadExplore(musicMode = true)
        } else {
            loadExplore(musicMode = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val plId = intent.getStringExtra("start_playlist_id")?.trim()
        if (!plId.isNullOrBlank()) {
            if (currentTab != Tab.EXPLORE) selectTab(Tab.EXPLORE)
            openPlaylistItems(YtItem(id = plId, title = "Playlist", channel = "", isPlaylist = true))
            return
        }
        val q = intent.getStringExtra("start_query")?.trim()
        if (!q.isNullOrBlank()) {
            suppressSuggestions = true
            searchInput.setText(q)
            searchInput.setSelection(q.length)
            suppressSuggestions = false
            YouTubeStore.recordSearch(this, q)
            refreshRecentChips()
            currentQuery = q
            if (currentTab != Tab.EXPLORE) selectTab(Tab.EXPLORE)
            runSearch(q)
        }
    }

    private fun updateForYouTabLabel() {
        val hasPersonalSeeds = isSignedInToYouTube() ||
            YouTubeStore.getHistory(this).isNotEmpty() ||
            YouTubeStore.getBookmarks(this).isNotEmpty() ||
            YouTubeStore.getRecentSearches(this).isNotEmpty()
        tabViews[Tab.EXPLORE]?.text = if (hasPersonalSeeds) "For You" else "Explore"
    }

    private fun currentAccountFingerprint(): String {
        val signedIn = isSignedInToYouTube()
        val token = if (signedIn) WebViewCookieSync.getLoginInfoToken().orEmpty() else ""
        return "$signedIn:$token"
    }

    override fun onResume() {
        super.onResume()
        refreshAccountButton()
        val fp = currentAccountFingerprint()
        if (lastAccountFingerprint != null && fp != lastAccountFingerprint) {
            lastAccountFingerprint = fp
            signedInFeedMode = SignedInFeedMode.NONE
            homeTopicChips = emptyList()
            selectedTopicLabel = null
            updateForYouTabLabel()
            updateFilterChipStyles()
            updateTopicChipsVisibility()
            if (currentTab == Tab.EXPLORE && currentQuery.isNullOrBlank() && openPlaylist == null) {
                loadExploreForCurrentFilter()
            }
        } else {
            lastAccountFingerprint = fp
            updateFilterChipStyles()
        }
        YouTubePlaybackService.addListener(playbackListener)
    }

    private fun isSignedInToYouTube(): Boolean {
        return WebViewCookieSync.hasLoggedInYouTubeSession() ||
            WebViewCookieSync.buildYouTubeAuthHeaders(this) != null
    }

    private fun refreshAccountButton() {
        val signedIn = isSignedInToYouTube()
        accountBtn.text = if (signedIn) "✓" else "👤"
        accountBtn.setBackgroundResource(
            if (signedIn) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg
        )
    }

    private fun onAccountButtonTapped() {
        if (!isSignedInToYouTube()) {
            startActivity(LoginActivity.createSignInIntent(this))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Signed in to YouTube ✓")
            .setItems(
                arrayOf(
                    "Open YouTube Web (Ad-Free)",
                    "Switch account",
                    "Sign out"
                )
            ) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, BrowseActivity::class.java)
                            .putExtra("start_url", "https://m.youtube.com")
                    )
                    1 -> startActivity(LoginActivity.createSwitchAccountIntent(this))
                    2 -> startActivity(LoginActivity.createLogoutIntent(this))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onPause() {
        YouTubePlaybackService.removeListener(playbackListener)
        super.onPause()
    }

    override fun onDestroy() {
        suggestRunnable?.let { mainHandler.removeCallbacks(it) }
        io.shutdownNow()
        suggestIo.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!handleBackNavigation()) {
            super.onBackPressed()
        }
    }

    private fun handleBackNavigation(): Boolean {
        if (openPlaylist != null) {
            closePlaylistView()
            return true
        }
        if (currentTab != Tab.EXPLORE) {
            selectTab(Tab.EXPLORE)
            return true
        }
        if (!currentQuery.isNullOrBlank()) {
            searchClear.performClick()
            return true
        }
        // If YouTubeActivity became the task root (e.g. after returning from
        // background/notification), bring MainActivity back instead of exiting!
        if (isTaskRoot) {
            startActivity(
                Intent(this, com.graball.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }
        finish()
        return true
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        openPlaylist = null
        playlistBanner.visibility = View.GONE
        tabViews.forEach { (t, view) ->
            val selected = t == tab
            view.setBackgroundResource(
                if (selected) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg
            )
            view.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.graball_text else R.color.graball_subtext
                )
            )
        }
        filterRow.visibility = if (tab == Tab.EXPLORE) View.VISIBLE else View.GONE
        updateTopicChipsVisibility()
        syncCardStyleForCurrentView()
        when (tab) {
            Tab.EXPLORE -> {
                if (lastExploreItems.isNotEmpty()) {
                    showItems(lastExploreItems, "No videos found")
                } else {
                    loadExplore(currentFilter == YouTubeInnerTube.SearchFilter.MUSIC)
                }
            }
            Tab.BOOKMARKS -> renderBookmarks()
            Tab.HISTORY -> renderHistory()
            Tab.QUEUE -> renderQueue(YouTubePlaybackService.currentState)
        }
    }

    /**
     * Full-width 16:9 YouTube-style cards on Home / Topic / Subscriptions / Music feeds;
     * compact side-by-side rows for Search, Playlists, Watch Later, Saved, History & Queue.
     */
    private fun syncCardStyleForCurrentView() {
        val useLarge = currentTab == Tab.EXPLORE &&
            openPlaylist == null &&
            currentQuery.isNullOrBlank() &&
            signedInFeedMode != SignedInFeedMode.WATCH_LATER &&
            currentFilter != YouTubeInnerTube.SearchFilter.PLAYLISTS
        adapter.setLargeCardMode(useLarge)
    }

    private fun setupPullToRefresh() {
        val thresholdPx = 68f * resources.displayMetrics.density
        var pullStartY = -1f
        var pullReady = false
        recycler.setOnTouchListener { _, event ->
            val canRefresh = currentTab == Tab.EXPLORE && openPlaylist == null
            if (!canRefresh) {
                pullStartY = -1f
                pullReady = false
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!recycler.canScrollVertically(-1)) {
                        pullStartY = event.y
                    } else {
                        pullStartY = -1f
                    }
                    pullReady = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pullStartY < 0f && !recycler.canScrollVertically(-1)) {
                        pullStartY = event.y
                    }
                    if (pullStartY >= 0f && !recycler.canScrollVertically(-1)) {
                        val dy = event.y - pullStartY
                        if (dy > thresholdPx) {
                            pullReady = true
                            refreshBanner.text = "↑ Release to refresh feed"
                            refreshBanner.visibility = View.VISIBLE
                        } else if (dy > thresholdPx * 0.35f) {
                            pullReady = false
                            refreshBanner.text = "↓ Pull to refresh"
                            refreshBanner.visibility = View.VISIBLE
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val trigger = pullReady && event.actionMasked == MotionEvent.ACTION_UP
                    pullStartY = -1f
                    pullReady = false
                    if (trigger) {
                        refreshCurrentExploreFeed()
                    } else if (refreshBanner.text.toString().contains("Pull") ||
                        refreshBanner.text.toString().contains("Release")
                    ) {
                        refreshBanner.visibility = View.GONE
                    }
                }
            }
            false
        }
    }

    private fun refreshCurrentExploreFeed() {
        val q = currentQuery
        if (!q.isNullOrBlank()) {
            runSearch(q)
            return
        }
        if (signedInFeedMode != SignedInFeedMode.NONE) {
            loadSignedInSpecialFeed(signedInFeedMode)
            return
        }
        val activeChip = homeTopicChips.firstOrNull {
            it.label == selectedTopicLabel && !it.continuationToken.isNullOrBlank()
        }
        if (currentFilter == YouTubeInnerTube.SearchFilter.ALL && activeChip != null) {
            loadHomeTopicChip(activeChip)
        } else {
            loadExploreForCurrentFilter(forceNetworkRefresh = true)
        }
    }

    private fun updateFilterChipStyles() {
        val showSignedInFeeds = isSignedInToYouTube() && currentQuery.isNullOrBlank()
        chipSubscriptions.visibility = if (showSignedInFeeds) View.VISIBLE else View.GONE
        chipWatchLater.visibility = if (showSignedInFeeds) View.VISIBLE else View.GONE
        if (!showSignedInFeeds && signedInFeedMode != SignedInFeedMode.NONE) {
            signedInFeedMode = SignedInFeedMode.NONE
        }
        filterChips.forEach { (filter, chip) ->
            val selected = signedInFeedMode == SignedInFeedMode.NONE && filter == currentFilter
            chip.setBackgroundResource(
                if (selected) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg
            )
            chip.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.graball_text else R.color.graball_subtext
                )
            )
        }
        listOf(
            chipSubscriptions to (signedInFeedMode == SignedInFeedMode.SUBSCRIPTIONS),
            chipWatchLater to (signedInFeedMode == SignedInFeedMode.WATCH_LATER)
        ).forEach { (chip, selected) ->
            chip.setBackgroundResource(
                if (selected) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg
            )
            chip.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.graball_text else R.color.graball_subtext
                )
            )
        }
        updateTopicChipsVisibility()
    }

    private fun updateTopicChipsVisibility() {
        val show = currentTab == Tab.EXPLORE &&
            openPlaylist == null &&
            currentQuery.isNullOrBlank() &&
            signedInFeedMode == SignedInFeedMode.NONE &&
            currentFilter == YouTubeInnerTube.SearchFilter.ALL &&
            isSignedInToYouTube() &&
            homeTopicChips.isNotEmpty()
        topicRow.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun renderHomeTopicChips() {
        topicContainer.removeAllViews()
        if (homeTopicChips.isEmpty()) {
            topicRow.visibility = View.GONE
            return
        }
        val activeLabel = selectedTopicLabel
            ?: homeTopicChips.firstOrNull { it.selected }?.label
            ?: homeTopicChips.firstOrNull()?.label
        val dp = resources.displayMetrics.density
        homeTopicChips.forEach { chipData ->
            val isSelected = chipData.label == activeLabel
            val chipView = TextView(this).apply {
                text = chipData.label
                textSize = 11.5f
                setTextColor(
                    ContextCompat.getColor(
                        this@YouTubeActivity,
                        if (isSelected) R.color.graball_text else R.color.graball_subtext
                    )
                )
                setBackgroundResource(
                    if (isSelected) R.drawable.yt_chip_selected else R.drawable.yt_chip_bg
                )
                setPadding((11 * dp).toInt(), (5 * dp).toInt(), (11 * dp).toInt(), (5 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = (6 * dp).toInt() }
                setOnClickListener {
                    if (chipData.label == selectedTopicLabel) return@setOnClickListener
                    if (chipData.continuationToken.isNullOrBlank()) {
                        selectedTopicLabel = chipData.label
                        renderHomeTopicChips()
                        loadExplore(musicMode = false)
                    } else {
                        selectedTopicLabel = chipData.label
                        renderHomeTopicChips()
                        loadHomeTopicChip(chipData)
                    }
                }
            }
            topicContainer.addView(chipView)
        }
        updateTopicChipsVisibility()
    }

    private fun triggerSearch() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) return
        hideSuggestions()
        hideKeyboard()
        // If the user pasted a YouTube video or playlist link directly into
        // the search box, open it immediately instead of running a text search.
        extractPlaylistId(q)?.let { plId ->
            if (!q.contains("watch?v=")) {
                openPlaylistItems(YtItem(id = plId, title = "Playlist", channel = "", isPlaylist = true))
                return
            }
        }
        extractVideoId(q)?.let { vid ->
            val item = YtItem(
                id = vid,
                title = "YouTube Video",
                channel = "",
                thumbnailUrl = "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
            )
            YouTubePlaybackService.withService(this) { svc ->
                svc.playItem(item, audioOnly = false)
            }
            openPlayerScreen()
            return
        }
        YouTubeStore.recordSearch(this, q)
        refreshRecentChips()
        currentQuery = q
        if (currentTab != Tab.EXPLORE) {
            selectTab(Tab.EXPLORE)
        }
        runSearch(q)
    }

    // ---- Debounced Search Autocomplete & Recent Search Chips ----

    private fun scheduleSuggestions(raw: String) {
        suggestRunnable?.let { mainHandler.removeCallbacks(it) }
        val q = raw.trim()
        if (q.length < 2 || q.startsWith("http", ignoreCase = true)) {
            hideSuggestions()
            return
        }
        val task = Runnable {
            suggestIo.execute {
                val list = YouTubeInnerTube.fetchSearchSuggestions(q)
                runOnUiThread {
                    if (isFinishing || searchInput.text.toString().trim() != q) return@runOnUiThread
                    renderSuggestions(list)
                }
            }
        }
        suggestRunnable = task
        mainHandler.postDelayed(task, 250L)
    }

    private fun renderSuggestions(suggestions: List<String>) {
        suggestionsBox.removeAllViews()
        if (suggestions.isEmpty()) {
            suggestionsBox.visibility = View.GONE
            return
        }
        val dp = resources.displayMetrics.density
        suggestions.forEach { text ->
            val row = TextView(this).apply {
                this.text = "🔍  $text"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@YouTubeActivity, R.color.graball_text))
                setPadding((8 * dp).toInt(), (9 * dp).toInt(), (8 * dp).toInt(), (9 * dp).toInt())
                setOnClickListener {
                    suppressSuggestions = true
                    searchInput.setText(text)
                    searchInput.setSelection(text.length)
                    suppressSuggestions = false
                    triggerSearch()
                }
            }
            suggestionsBox.addView(row)
        }
        suggestionsBox.visibility = View.VISIBLE
    }

    private fun hideSuggestions() {
        suggestRunnable?.let { mainHandler.removeCallbacks(it) }
        suggestionsBox.visibility = View.GONE
    }

    private fun refreshRecentChips() {
        val recents = YouTubeStore.getRecentSearches(this).take(8)
        recentContainer.removeAllViews()
        if (recents.isEmpty()) {
            recentRow.visibility = View.GONE
            return
        }
        val dp = resources.displayMetrics.density
        recents.forEach { q ->
            val chip = TextView(this).apply {
                text = "↺ $q"
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@YouTubeActivity, R.color.graball_subtext))
                setBackgroundResource(R.drawable.yt_chip_bg)
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = (6 * dp).toInt() }
                setOnClickListener {
                    suppressSuggestions = true
                    searchInput.setText(q)
                    searchInput.setSelection(q.length)
                    suppressSuggestions = false
                    triggerSearch()
                }
            }
            recentContainer.addView(chip)
        }
        recentRow.visibility = View.VISIBLE
    }

    private fun extractVideoId(input: String): String? {
        val regex = Regex("""(?:youtube\.com/(?:watch\?.*v=|shorts/|embed/|live/)|youtu\.be/)([A-Za-z0-9_-]{11})""")
        return regex.find(input)?.groupValues?.getOrNull(1)
    }

    private fun extractPlaylistId(input: String): String? {
        if (!input.contains("youtube.com") && !input.contains("youtu.be")) return null
        val regex = Regex("""[?&]list=([A-Za-z0-9_-]+)""")
        return regex.find(input)?.groupValues?.getOrNull(1)
    }

    private fun runSearch(query: String) {
        val gen = ++requestGen
        openPlaylist = null
        signedInFeedMode = SignedInFeedMode.NONE
        pagingMode = PagingMode.SEARCH
        exploreContinuation = null
        isLoadingMoreExplore = false
        playlistBanner.visibility = View.GONE
        refreshBanner.visibility = View.GONE
        updateFilterChipStyles()
        syncCardStyleForCurrentView()
        setLoading(true)
        val filter = currentFilter
        io.execute {
            val page = YouTubeInnerTube.searchPaged(query, filter, continuationToken = null)
            runOnUiThread {
                if (gen != requestGen || isFinishing) return@runOnUiThread
                setLoading(false)
                lastExploreItems = page.items.toMutableList()
                exploreContinuation = page.continuationToken
                showItems(page.items, "No results for \"$query\"")
            }
        }
    }

    private fun loadExploreForCurrentFilter(forceNetworkRefresh: Boolean = false) {
        if (signedInFeedMode != SignedInFeedMode.NONE) {
            loadSignedInSpecialFeed(signedInFeedMode)
            return
        }
        if (currentFilter == YouTubeInnerTube.SearchFilter.PLAYLISTS) {
            val gen = ++requestGen
            pagingMode = PagingMode.NONE
            exploreContinuation = null
            isLoadingMoreExplore = false
            refreshBanner.visibility = View.GONE
            updateTopicChipsVisibility()
            syncCardStyleForCurrentView()
            setLoading(true)
            io.execute {
                val results = YouTubeInnerTube.explorePlaylists()
                runOnUiThread {
                    if (gen != requestGen || isFinishing) return@runOnUiThread
                    setLoading(false)
                    lastExploreItems = results.toMutableList()
                    if (currentTab == Tab.EXPLORE && openPlaylist == null) {
                        showItems(results, "Search above to find any YouTube playlist")
                    }
                }
            }
        } else {
            loadExplore(
                musicMode = (currentFilter == YouTubeInnerTube.SearchFilter.MUSIC),
                forceNetworkRefresh = forceNetworkRefresh
            )
        }
    }

    private fun loadExplore(musicMode: Boolean, forceNetworkRefresh: Boolean = false) {
        val gen = ++requestGen
        pagingMode = when {
            musicMode -> PagingMode.MUSIC
            isSignedInToYouTube() -> PagingMode.HOME
            else -> PagingMode.NONE
        }
        exploreContinuation = null
        isLoadingMoreExplore = false
        updateForYouTabLabel()
        updateFilterChipStyles()
        syncCardStyleForCurrentView()

        val accountKey = currentAccountFingerprint()
        // Instant 0ms disk-cached Home feed on open, then quietly refresh in background!
        var showedCache = false
        if (!musicMode && !forceNetworkRefresh) {
            val cached = YouTubeStore.loadHomeFeedCache(applicationContext, accountKey)
            if (cached != null && cached.items.isNotEmpty()) {
                showedCache = true
                lastExploreItems = cached.items.toMutableList()
                if (cached.chips.isNotEmpty()) {
                    homeTopicChips = cached.chips
                    if (selectedTopicLabel == null) {
                        selectedTopicLabel = cached.chips.firstOrNull { it.selected }?.label
                            ?: cached.chips.firstOrNull()?.label
                    }
                    renderHomeTopicChips()
                }
                if (currentTab == Tab.EXPLORE && openPlaylist == null) {
                    showItems(lastExploreItems, "Search above to find any YouTube video or playlist")
                }
            }
        }

        if (showedCache) {
            setLoading(false)
            refreshBanner.text = "Refreshing recommendations…"
            refreshBanner.visibility = View.VISIBLE
        } else {
            refreshBanner.visibility = if (forceNetworkRefresh) {
                refreshBanner.text = "Refreshing recommendations…"
                View.VISIBLE
            } else {
                View.GONE
            }
            setLoading(!forceNetworkRefresh || lastExploreItems.isEmpty())
        }

        val knownMusicToken = homeTopicChips.firstOrNull {
            it.label.equals("Music", ignoreCase = true)
        }?.continuationToken

        io.execute {
            val page = if (musicMode) {
                YouTubeInnerTube.fetchPersonalizedMusicPage(
                    context = applicationContext,
                    continuationToken = null,
                    knownMusicChipToken = knownMusicToken
                )
            } else {
                YouTubeInnerTube.exploreForYouPaged(applicationContext, musicMode = false)
            }
            if (!musicMode && page.items.isNotEmpty()) {
                YouTubeStore.saveHomeFeedCache(
                    context = applicationContext,
                    accountKey = accountKey,
                    items = page.items,
                    chips = page.chips
                )
            }
            runOnUiThread {
                if (gen != requestGen || isFinishing) return@runOnUiThread
                setLoading(false)
                refreshBanner.visibility = View.GONE
                if (page.items.isNotEmpty() || !showedCache) {
                    lastExploreItems = page.items.toMutableList()
                    exploreContinuation = page.continuationToken
                    if (!musicMode && page.chips.isNotEmpty()) {
                        homeTopicChips = page.chips
                        if (selectedTopicLabel == null) {
                            selectedTopicLabel = page.chips.firstOrNull { it.selected }?.label
                                ?: page.chips.firstOrNull()?.label
                        }
                        renderHomeTopicChips()
                    } else {
                        updateTopicChipsVisibility()
                    }
                    if (currentTab == Tab.EXPLORE && openPlaylist == null) {
                        showItems(lastExploreItems, "Search above to find any YouTube video or playlist")
                    }
                } else {
                    exploreContinuation = page.continuationToken
                }
            }
        }
    }

    private fun loadHomeTopicChip(chip: YouTubeInnerTube.FeedChip) {
        val token = chip.continuationToken ?: return
        val gen = ++requestGen
        pagingMode = PagingMode.HOME
        exploreContinuation = null
        isLoadingMoreExplore = false
        refreshBanner.visibility = View.GONE
        syncCardStyleForCurrentView()
        setLoading(true)
        io.execute {
            val page = YouTubeInnerTube.fetchSignedInHomePage(applicationContext, continuationToken = token)
            runOnUiThread {
                if (gen != requestGen || isFinishing) return@runOnUiThread
                setLoading(false)
                lastExploreItems = page.items.toMutableList()
                exploreContinuation = page.continuationToken
                if (currentTab == Tab.EXPLORE && openPlaylist == null) {
                    showItems(lastExploreItems, "No videos in ${chip.label}")
                }
            }
        }
    }

    private fun loadSignedInSpecialFeed(mode: SignedInFeedMode) {
        val gen = ++requestGen
        openPlaylist = null
        playlistBanner.visibility = View.GONE
        refreshBanner.visibility = View.GONE
        pagingMode = when (mode) {
            SignedInFeedMode.SUBSCRIPTIONS -> PagingMode.SUBSCRIPTIONS
            SignedInFeedMode.WATCH_LATER -> PagingMode.WATCH_LATER
            SignedInFeedMode.NONE -> PagingMode.NONE
        }
        exploreContinuation = null
        isLoadingMoreExplore = false
        syncCardStyleForCurrentView()
        setLoading(true)
        io.execute {
            val page = when (mode) {
                SignedInFeedMode.SUBSCRIPTIONS -> YouTubeInnerTube.fetchSubscriptionsPage(applicationContext)
                SignedInFeedMode.WATCH_LATER -> YouTubeInnerTube.fetchWatchLaterPage(applicationContext)
                SignedInFeedMode.NONE -> YouTubeInnerTube.FeedPage(emptyList(), null)
            }
            runOnUiThread {
                if (gen != requestGen || isFinishing) return@runOnUiThread
                setLoading(false)
                lastExploreItems = page.items.toMutableList()
                exploreContinuation = page.continuationToken
                val emptyMsg = when (mode) {
                    SignedInFeedMode.SUBSCRIPTIONS -> "No subscription videos found"
                    SignedInFeedMode.WATCH_LATER -> "Your Watch Later list is empty"
                    SignedInFeedMode.NONE -> "No videos found"
                }
                if (currentTab == Tab.EXPLORE && openPlaylist == null) {
                    showItems(lastExploreItems, emptyMsg)
                }
            }
        }
    }

    private fun loadMoreCurrentFeed() {
        val token = exploreContinuation ?: return
        val mode = pagingMode
        if (isLoadingMoreExplore || mode == PagingMode.NONE) return
        val gen = requestGen
        val activeQuery = currentQuery
        val activeFilter = currentFilter
        isLoadingMoreExplore = true
        io.execute {
            val page = when (mode) {
                PagingMode.HOME -> YouTubeInnerTube.fetchSignedInHomePage(applicationContext, continuationToken = token)
                PagingMode.MUSIC -> YouTubeInnerTube.fetchPersonalizedMusicPage(applicationContext, continuationToken = token)
                PagingMode.SUBSCRIPTIONS -> YouTubeInnerTube.fetchSubscriptionsPage(applicationContext, continuationToken = token)
                PagingMode.WATCH_LATER -> YouTubeInnerTube.fetchWatchLaterPage(applicationContext, continuationToken = token)
                PagingMode.SEARCH -> if (!activeQuery.isNullOrBlank()) {
                    YouTubeInnerTube.searchPaged(activeQuery, activeFilter, continuationToken = token)
                } else {
                    YouTubeInnerTube.FeedPage(emptyList(), null)
                }
                PagingMode.NONE -> YouTubeInnerTube.FeedPage(emptyList(), null)
            }
            runOnUiThread {
                if (gen != requestGen || isFinishing) {
                    isLoadingMoreExplore = false
                    return@runOnUiThread
                }
                isLoadingMoreExplore = false
                val existingIds = lastExploreItems.map { it.id }.toHashSet()
                val fresh = page.items.filter { existingIds.add(it.id) }
                exploreContinuation = if (page.continuationToken != token && fresh.isNotEmpty()) {
                    page.continuationToken
                } else {
                    null
                }
                if (fresh.isNotEmpty()) {
                    lastExploreItems.addAll(fresh)
                    if (currentTab == Tab.EXPLORE && openPlaylist == null) {
                        adapter.appendItems(fresh)
                    }
                }
            }
        }
    }

    private fun openPlaylistItems(playlistItem: YtItem) {
        val gen = ++requestGen
        setLoading(true)
        io.execute {
            val details = YouTubeInnerTube.fetchPlaylist(playlistItem.id)
            runOnUiThread {
                if (gen != requestGen || isFinishing) return@runOnUiThread
                setLoading(false)
                if (details == null || details.items.isEmpty()) {
                    Toast.makeText(this, "Couldn't load playlist", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                openPlaylist = details
                filterRow.visibility = View.GONE
                topicRow.visibility = View.GONE
                refreshBanner.visibility = View.GONE
                syncCardStyleForCurrentView()
                playlistBanner.visibility = View.VISIBLE
                playlistTitle.text = details.title
                playlistMeta.text = listOf(details.channel, "${details.items.size} videos")
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                showItems(details.items, "Playlist is empty")
            }
        }
    }

    private fun closePlaylistView() {
        openPlaylist = null
        playlistBanner.visibility = View.GONE
        if (currentTab == Tab.EXPLORE) {
            filterRow.visibility = View.VISIBLE
            updateTopicChipsVisibility()
            syncCardStyleForCurrentView()
            showItems(lastExploreItems, "Search YouTube to find videos or playlists")
        } else {
            selectTab(currentTab)
        }
    }

    private fun onItemTapped(item: YtItem, pos: Int) {
        if (item.isPlaylist) {
            openPlaylistItems(item)
            return
        }
        val pl = openPlaylist
        if (pl != null) {
            YouTubePlaybackService.withService(this) { svc ->
                svc.playQueue(pl.items, startIndex = pos, audioOnly = false)
            }
        } else if (currentTab == Tab.QUEUE) {
            val q = YouTubePlaybackService.currentState.queue
            if (pos in q.indices) {
                YouTubePlaybackService.withService(this) { svc ->
                    svc.playQueue(q, startIndex = pos, audioOnly = YouTubePlaybackService.currentState.isAudioOnly)
                }
            }
        } else {
            YouTubePlaybackService.withService(this) { svc ->
                svc.playItem(item, audioOnly = false)
            }
        }
        openPlayerScreen()
    }

    private fun openPlayerScreen() {
        startActivity(
            Intent(this, YouTubePlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_up, R.anim.fade_out)
    }

    private fun renderBookmarks() {
        setLoading(false)
        showItems(YouTubeStore.getBookmarks(this), "No saved videos or playlists yet — tap ☆ on any item")
    }

    private fun renderHistory() {
        setLoading(false)
        val items = YouTubeStore.getHistory(this).map { it.item }
        showItems(items, "Watch history is empty")
    }

    private fun renderQueue(state: YouTubePlaybackState) {
        setLoading(false)
        showItems(state.queue, "Queue is empty — tap + Queue on any video")
    }

    private fun showItems(items: List<YtItem>, emptyMsg: String) {
        adapter.submitList(items, YouTubePlaybackService.currentState.currentItem?.id)
        if (items.isEmpty()) {
            emptyView.text = emptyMsg
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
        }
    }

    private fun setLoading(busy: Boolean) {
        loading.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) emptyView.visibility = View.GONE
    }

    private fun updateMiniPlayer(state: YouTubePlaybackState) {
        val current = state.currentItem
        if (current == null) {
            miniBar.visibility = View.GONE
            return
        }
        miniBar.visibility = View.VISIBLE
        miniTitle.text = current.title
        miniSub.text = when {
            state.isLoading -> "Loading stream…"
            state.isAudioOnly -> "🎧 BG Audio · ${current.channel}"
            state.isPlaying -> "Playing · ${current.channel}"
            else -> "Paused · ${current.channel}"
        }
        miniPlayPause.text = if (state.isPlaying) "⏸" else "▶"
        ThumbnailLoader.load(current.thumbnailUrl, miniThumb, targetWidthPx = 160)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }
}
