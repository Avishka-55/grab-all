package com.graball

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Supported sites (v0.7.4). The ten famous ones, tappable — a tap
 * opens that site in the in-app browser, same as the quick-site
 * cards. Below them, the exact count: the bundled yt-dlp reports
 * 1,755 extractors (checked live with --list-extractors on
 * 2026.10.26), so the rest are "1,745 more".
 */
class SitesActivity : AppCompatActivity() {

    companion object {
        /** Extractors in the bundled yt-dlp, counted with --list-extractors. */
        const val TOTAL_EXTRACTORS = 1755
    }

    private val sites = listOf(
        "YouTube" to "https://www.youtube.com",
        "TikTok" to "https://www.tiktok.com",
        "Instagram" to "https://www.instagram.com",
        "Facebook" to "https://www.facebook.com",
        "X (Twitter)" to "https://x.com",
        "Twitch" to "https://www.twitch.tv",
        "Vimeo" to "https://vimeo.com",
        "Dailymotion" to "https://www.dailymotion.com",
        "SoundCloud" to "https://soundcloud.com",
        "Reddit" to "https://www.reddit.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sites)
        val listView = findViewById<ListView>(R.id.sitesList)
        listView.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, sites.map { it.first }
        )
        listView.setOnItemClickListener { _, _, position, _ ->
            startActivity(
                Intent(this, BrowseActivity::class.java).putExtra(
                    "start_url", sites[position].second
                )
            )
        }
        val more = TOTAL_EXTRACTORS - sites.size
        findViewById<TextView>(R.id.sitesMore).text = getString(
            R.string.sites_more, more, TOTAL_EXTRACTORS
        )
    }
}
