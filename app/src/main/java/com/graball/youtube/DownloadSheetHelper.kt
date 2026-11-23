package com.graball.youtube

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.graball.engine.DownloadPrefs
import com.graball.engine.DownloadProgressBus
import com.graball.engine.DownloadService
import com.graball.engine.RuntimePaths
import java.io.File

/**
 * Reusable 1-tap Download dialog for any YouTube video or playlist in the
 * native player / search / playlist / related screens.
 *
 * Wired straight into GrabAll's existing DownloadService, DownloadPrefs,
 * and DownloadProgressBus so downloads appear in the Downloads tab and
 * publish to Downloads/GrabAll seamlessly.
 */
object DownloadSheetHelper {

    fun show(context: Context, item: YtItem) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val prefs = DownloadPrefs.load(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val subtitle = TextView(context).apply {
            text = (if (item.isPlaylist) "Playlist" else "Video") + " · " + item.title
            textSize = 13f
            setTextColor(Color.LTGRAY)
            maxLines = 2
        }
        container.addView(subtitle)

        val qualityLabel = TextView(context).apply {
            text = "Quality"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(12), 0, dp(4))
        }
        container.addView(qualityLabel)

        val qualitySpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                listOf("Best", "1080p", "720p", "480p", "360p")
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(prefs.qualityPos.coerceIn(0, 4))
        }
        container.addView(qualitySpinner)

        val audioCheck = CheckBox(context).apply {
            text = "Audio only (MP3)"
            isChecked = prefs.audioOnly
            setPadding(0, dp(8), 0, dp(4))
        }
        container.addView(audioCheck)

        var playlistSpinner: Spinner? = null
        if (item.isPlaylist) {
            val plLabel = TextView(context).apply {
                text = "Playlist range"
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(0, dp(8), 0, dp(4))
            }
            container.addView(plLabel)
            playlistSpinner = Spinner(context).apply {
                adapter = ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_item,
                    listOf("First 20", "First 30", "First 50", "All videos")
                ).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                val plPos = (prefs.playlistPos - 1).coerceIn(0, 3)
                setSelection(plPos)
            }
            container.addView(playlistSpinner)
        }

        AlertDialog.Builder(context)
            .setTitle(if (item.isPlaylist) "Download Playlist" else "Download Video")
            .setView(container)
            .setPositiveButton("Download") { _, _ ->
                val url = item.watchUrl
                val qPos = qualitySpinner.selectedItemPosition
                val audioOnly = audioCheck.isChecked
                val playlistMode = if (item.isPlaylist) {
                    when (playlistSpinner?.selectedItemPosition) {
                        0 -> "20"
                        1 -> "30"
                        2 -> "50"
                        else -> "all"
                    }
                } else {
                    "single"
                }

                val staging = RuntimePaths.stagingDirFor(context, url)
                if (!staging.exists()) staging.mkdirs()

                val fileName = if (playlistMode == "single") {
                    "%(title)s.%(ext)s"
                } else {
                    "%(playlist_title)s/%(playlist_index)s - %(title)s.%(ext)s"
                }
                val template = File(staging, fileName).absolutePath
                val maxHeight = when (qPos) {
                    1 -> 1080
                    2 -> 720
                    3 -> 480
                    4 -> 360
                    else -> null
                }

                DownloadProgressBus.modes[url] = buildString {
                    if (audioOnly) {
                        append("Audio only")
                    } else {
                        append(
                            when (qPos) {
                                1 -> "1080p"
                                2 -> "720p"
                                3 -> "480p"
                                4 -> "360p"
                                else -> "Best"
                            }
                        )
                    }
                    when (playlistMode) {
                        "20" -> append(" · First 20")
                        "30" -> append(" · First 30")
                        "50" -> append(" · First 50")
                        "all" -> append(" · All videos")
                    }
                }

                DownloadPrefs.save(
                    context,
                    DownloadPrefs.Choices(
                        qualityPos = qPos,
                        audioOnly = audioOnly,
                        playlistPos = if (item.isPlaylist) {
                            (playlistSpinner?.selectedItemPosition ?: 0) + 1
                        } else {
                            prefs.playlistPos
                        }
                    )
                )

                if (item.title.isNotBlank()) {
                    DownloadProgressBus.titles[url] = item.title
                }
                DownloadService.startDownload(
                    context, url, template, audioOnly, maxHeight, playlistMode
                )
                Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
