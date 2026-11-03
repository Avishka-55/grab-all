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
    private val rowStatus = mutableMapOf<String, TextView>()

    private val progressListener: (DownloadProgressBus.State) -> Unit = { state ->
        updateRow(state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        queueContainer = findViewById(R.id.queueContainer)
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
        row.addView(title)
        row.addView(status)
        queueContainer.addView(row)
        rowStatus[url] = status
    }

    private fun updateRow(state: DownloadProgressBus.State) {
        val statusView = rowStatus[state.id] ?: run {
            addRow(state.id, "")
            rowStatus[state.id]!!
        }
        statusView.text = when (state.status) {
            DownloadProgressBus.Status.DOWNLOADING -> {
                val pct = state.percent?.toInt()
                if (pct != null) getString(R.string.queue_downloading, pct)
                else getString(R.string.queue_downloading_unknown)
            }
            DownloadProgressBus.Status.COMPLETE -> getString(R.string.queue_done)
            DownloadProgressBus.Status.FAILED -> getString(R.string.queue_failed)
        }
    }
}
