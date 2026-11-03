package com.graball

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.graball.engine.DownloadProgressBus
import com.graball.engine.DownloadService
import com.graball.engine.RuntimePaths
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var queueContainer: LinearLayout
    private lateinit var cookiesStatus: TextView
    private val rowStatus = mutableMapOf<String, TextView>()
    private val rowDetail = mutableMapOf<String, TextView>()

    private val progressListener: (DownloadProgressBus.State) -> Unit = { state ->
        updateRow(state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        queueContainer = findViewById(R.id.queueContainer)
        cookiesStatus = findViewById(R.id.cookiesStatus)
        val linkInput = findViewById<EditText>(R.id.linkInput)
        findViewById<Button>(R.id.downloadButton).setOnClickListener {
            val url = linkInput.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Paste a video link first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val staging = RuntimePaths.stagingDir(this)
            if (!staging.exists()) {
                staging.mkdirs()
            }
            // yt-dlp resolves the title/extension at download time.
            val template = File(staging, "%(title)s.%(ext)s").absolutePath
            DownloadService.startDownload(this, url, template)
            addRow(url, getString(R.string.queue_queued))
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.loginButton).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Visible proof of whether the cookies import worked, so a failed
        // download never leaves the user guessing about the session.
        cookiesStatus.text = if (RuntimePaths.cookiesFile(this).exists()) {
            getString(R.string.cookies_saved)
        } else {
            getString(R.string.cookies_missing)
        }
        DownloadProgressBus.subscribe(progressListener)
    }

    override fun onPause() {
        DownloadProgressBus.unsubscribe(progressListener)
        super.onPause()
    }

    /** Add a queue row for [url], or refresh it if one already exists. */
    private fun addRow(url: String, statusText: String) {
        if (rowStatus.containsKey(url)) {
            rowStatus[url]?.text = statusText
            rowDetail[url]?.visibility = android.view.View.GONE
            return
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }
        val title = TextView(this).apply {
            text = url
            textSize = 14f
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
        }
        val status = TextView(this).apply {
            text = statusText
            textSize = 13f
        }
        val detail = TextView(this).apply {
            textSize = 12f
            visibility = android.view.View.GONE
        }
        row.addView(title)
        row.addView(status)
        row.addView(detail)
        queueContainer.addView(row)
        rowStatus[url] = status
        rowDetail[url] = detail
    }

    private fun updateRow(state: DownloadProgressBus.State) {
        val statusView = rowStatus[state.id] ?: run {
            addRow(state.id, "")
            rowStatus[state.id]!!
        }
        val detailView = rowDetail[state.id]
        statusView.text = when (state.status) {
            DownloadProgressBus.Status.DOWNLOADING -> {
                val pct = state.percent?.toInt()
                if (pct != null) getString(R.string.queue_downloading, pct)
                else getString(R.string.queue_downloading_unknown)
            }
            DownloadProgressBus.Status.COMPLETE -> getString(R.string.queue_done)
            DownloadProgressBus.Status.FAILED -> getString(R.string.queue_failed)
        }
        if (state.status == DownloadProgressBus.Status.FAILED &&
            !state.detail.isNullOrBlank() && detailView != null
        ) {
            detailView.text = state.detail
            detailView.visibility = android.view.View.VISIBLE
        } else {
            detailView?.visibility = android.view.View.GONE
        }
    }
}
