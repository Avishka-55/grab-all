package com.graball.engine

import android.content.Context
import java.io.File

/**
 * Download history that survives app restarts. The progress bus keeps
 * no history, so without this the queue went blank every time the app
 * was closed — and a re-tap after reopening looked like a fresh start.
 *
 * One line per finished download in filesDir/history.txt, tab
 * separated: timestamp, status (Done/Failed), mode label, url, detail.
 * Newlines/tabs inside fields are flattened so the file stays
 * one-line-per-entry. Capped at 50 entries, oldest dropped first.
 */
object DownloadHistory {

    data class Entry(
        val timestamp: Long,
        val status: String,
        val mode: String,
        val url: String,
        val detail: String
    )

    private const val MAX_ENTRIES = 50

    private fun historyFile(context: Context): File =
        File(context.filesDir, "history.txt")

    fun record(
        context: Context,
        url: String,
        mode: String,
        status: String,
        detail: String?
    ) {
        val clean = { value: String ->
            value.replace('\n', ' ').replace('\t', ' ').take(200)
        }
        val line = listOf(
            System.currentTimeMillis().toString(),
            status,
            clean(mode),
            url,
            clean(detail ?: "")
        ).joinToString("\t")
        try {
            val file = historyFile(context)
            val lines = if (file.exists()) {
                file.readLines().toMutableList()
            } else {
                mutableListOf()
            }
            lines.add(line)
            while (lines.size > MAX_ENTRIES) {
                lines.removeAt(0)
            }
            file.writeText(lines.joinToString("\n") + "\n")
        } catch (_: Exception) {
            // History is a nicety; never break a download over it.
        }
    }

    fun load(context: Context): List<Entry> {
        val file = historyFile(context)
        if (!file.exists()) {
            return emptyList()
        }
        return try {
            file.readLines().mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size < 4) {
                    null
                } else {
                    Entry(
                        timestamp = parts[0].toLongOrNull() ?: 0L,
                        status = parts[1],
                        mode = parts[2],
                        url = parts[3],
                        detail = if (parts.size > 4) parts[4] else ""
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
