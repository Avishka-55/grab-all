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

    /** Copy [stagedFile] to Downloads/GrabAll. Returns its Uri, or null. */
    fun publish(context: Context, stagedFile: File, displayName: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishScoped(context, stagedFile, displayName)
            } else {
                publishLegacy(stagedFile, displayName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Publish failed", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishScoped(
        context: Context,
        stagedFile: File,
        displayName: String
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeFor(displayName))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GrabAll")
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
    private fun publishLegacy(stagedFile: File, displayName: String): Uri? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "GrabAll"
        )
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val dest = File(dir, displayName)
        stagedFile.copyTo(dest, overwrite = true)
        return Uri.fromFile(dest)
    }

    private fun mimeFor(displayName: String): String {
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
