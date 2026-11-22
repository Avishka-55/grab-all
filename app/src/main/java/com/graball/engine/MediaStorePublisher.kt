package com.graball.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream

/**
 * Publishes a finished download from the app's private staging dir into the
 * public Downloads folder, so the user can find it in their Files app.
 *
 * API 29+ (scoped storage, including his Android 10 device): inserts into
 * MediaStore.Downloads with RELATIVE_PATH Download/GrabAll, using the
 * IS_PENDING dance so the file only appears once fully written. Below
 * API 29, falls back to a plain copy into the public Downloads dir
 * (WRITE_EXTERNAL_STORAGE is already declared with maxSdk 28).
 *
 * Not yet wired into DownloadService: the service needs to know the final
 * output filename first (runner final-path reporting, TODO) so it can
 * publish exactly the file that just finished, rather than everything in
 * staging.
 */
object MediaStorePublisher {

    private const val TAG = "MediaStorePublisher"

    /**
     * Copy [stagedFile] to Downloads/GrabAll, or to
     * Downloads/GrabAll/[subfolder] when one is given (playlist
     * downloads land in a folder named after the playlist). Returns
     * its Uri, or null.
     */
    fun publish(
        context: Context,
        stagedFile: File,
        displayName: String,
        subfolder: String? = null
    ): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishScoped(context, stagedFile, displayName, subfolder)
            } else {
                publishLegacy(stagedFile, displayName, subfolder)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Publish failed", e)
            null
        }
    }

    /** Publish a SAF content:// document URI (e.g. WhatsApp Status on Android 11+) into Downloads/GrabAll. */
    fun publishUri(
        context: Context,
        sourceUri: Uri,
        displayName: String,
        subfolder: String? = null
    ): Uri? {
        return try {
            val tmp = File(context.cacheDir, "status_tmp_${System.currentTimeMillis()}_$displayName")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: return null
            val res = publish(context, tmp, displayName, subfolder)
            tmp.delete()
            res
        } catch (e: Exception) {
            Log.e(TAG, "Publish URI failed", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishScoped(
        context: Context,
        stagedFile: File,
        displayName: String,
        subfolder: String?
    ): Uri? {
        val resolver = context.contentResolver
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/GrabAll" +
            (subfolder?.let { "/" + sanitizeSubfolder(it) } ?: "")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeFor(displayName))
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(stagedFile).use { input ->
                input.copyTo(out)
            }
        }
        val done = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        resolver.update(uri, done, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(
        stagedFile: File,
        displayName: String,
        subfolder: String?
    ): Uri? {
        var dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "GrabAll"
        )
        if (subfolder != null) {
            dir = File(dir, sanitizeSubfolder(subfolder))
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val dest = File(dir, displayName)
        stagedFile.copyTo(dest, overwrite = true)
        return Uri.fromFile(dest)
    }

    /** Strip path separators out of a folder name, so a playlist title
     * can never escape Downloads/GrabAll. */
    fun sanitizeSubfolder(name: String): String {
        return name.replace('/', '-').replace('\\', '-').trim()
            .takeIf { it.isNotEmpty() } ?: "Playlist"
    }

    fun mimeFor(displayName: String): String {
        return when (displayName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> "video/mp4"
        }
    }
}
