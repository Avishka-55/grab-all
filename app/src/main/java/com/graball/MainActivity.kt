package com.graball

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.graball.engine.DownloadService
import com.graball.engine.RuntimePaths
import java.io.File

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.loginButton).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }
}
