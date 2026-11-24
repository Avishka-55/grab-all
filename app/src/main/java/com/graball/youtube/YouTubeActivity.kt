package com.graball.youtube

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
import com.graball.R
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

    private lateinit var searchInput: EditText
    private lateinit var searchClear: TextView
    private lateinit var suggestionsBox: LinearLayout
    private lateinit var recentRow: HorizontalScrollView
    private lateinit var recentContainer: LinearLayout
    private lateinit var filterRow: HorizontalScrollView
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
    private var currentQuery: String? = null
    private var openPlaylist: PlaylistDetails? = null
    private var lastExploreItems: List<YtItem> = emptyList()
    private var requestGen = 0

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
        suggestionsBox = findViewById(R.id.ytSuggestionsBox)
        recentRow = findViewById(R.id.ytRecentRow)
        recentContainer = findViewById(R.id.ytRecentContainer)
        filterRow = findViewById(R.id.ytFilterRow)
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

        tabViews = mapOf(
            Tab.EXPLORE to findViewById(R.id.ytTabExplore),
            Tab.BOOKMARKS to findViewById(R.id.ytTabBookmarks),
            Tab.HISTORY to findViewById(R.id.ytTabHistory),
            Tab.QUEUE to findViewById(R.id.ytTabQueue)
        )
        tabViews.forEach { (tab, view) ->
            view.setOnClickListener { selectTab(tab) }
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
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        refreshRecentChips()
        updateForYouTabLabel()
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
        val hasPersonalSeeds = YouTubeStore.getHistory(this).isNotEmpty() ||
            YouTubeStore.getBookmarks(this).isNotEmpty() ||
            YouTubeStore.getRecentSearches(this).isNotEmpty()
        tabViews[Tab.EXPLORE]?.text = if (hasPersonalSeeds) "For You" else "Explore"
    }

    override fun onResume() {
        super.onResume()
        YouTubePlaybackService.addListener(playbackListener)
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

    private fun updateFilterChipStyles() {
        filterChips.forEach { (filter, chip) ->
            val selected = filter == currentFilter
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
        playlistBanner.visibility = View.GONE
        setLoading(true)
        val filter = currentFilter
        io.execute {
            val results = YouTubeInnerTube.search(query, filter)
            runOnUiThread {
                if (gen != requestGen || isFinishing) return@runOnUiThread
                setLoading(false)
                lastExploreItems = results
                showItems(results, "No results for \"$query\"")
            }
        }
    }

    private fun loadExploreForCurrentFilter() {
        if (currentFilter == YouTubeInnerTube.SearchFilter.PLAYLISTS) {
            val gen = ++requestGen
            setLoading(true)
            io.execute {
                val results = YouTubeInnerTube.explorePlaylists()
                runOnUiThread {
                    if (gen != requestGen || isFinishing) return@runOnUiThread
                    setLoading(false)
                    lastExploreItems = results
                    if (currentTab == Tab.EXPLORE && openPlaylist == null) {
                        showItems(results, "Search above to find any YouTube playlist")
                    }
                }
            }
        } else {
            loadExplore(currentFilter == YouTubeInnerTube.SearchFilter.MUSIC)
        }
    }

    private fun loadExplore(musicMode: Boolean) {
        val gen = ++requestGen
        setLoading(true)
        updateForYouTabLabel()
        io.execute {
            val results = YouTubeInnerTube.exploreForYou(applicationContext, musicMode)
            runOnUiThread {
                if (gen != requestGen || isFinishing) return@runOnUiThread
                setLoading(false)
                lastExploreItems = results
                if (currentTab == Tab.EXPLORE && openPlaylist == null) {
                    showItems(results, "Search above to find any YouTube video or playlist")
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
