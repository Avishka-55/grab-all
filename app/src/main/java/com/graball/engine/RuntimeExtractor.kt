package com.graball.engine

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * First-launch extractor for GrabAll's embedded native runtime.
 *
 * Each native component ships as a zip in assets/runtime/ (produced by the
 * native-asset packaging step, TODO) and is unzipped into filesDir/runtime/
 * on first launch. Extraction is version-gated: a marker file records the
 * bundled version, so reinstalls and updates re-extract exactly once, and
 * normal launches skip the work entirely.
 *
 * Zip layout contract: entries inside each zip are relative to that
 * component's directory. For example, runtime/qjs.zip must contain
 * bin/qjs (not qjs/bin/qjs), because it is unzipped into runtime/qjs/.
 * RuntimePaths' paths assume this layout.
 *
 * Asset access is string-based, so this compiles before the asset zips
 * exist; extraction fails loudly at runtime (returns false, logs) until
 * the packaging step lands them.
 *
 * Runs synchronously — always call off the main thread.
 */
object RuntimeExtractor {

    private const val TAG = "RuntimeExtractor"
    private const val MARKER = ".version"

    data class Component(
        /** Asset path of the component zip, e.g. "runtime/qjs.zip". */
        val assetZip: String,
        /** Directory under runtimeDir() to unzip into. */
        val destDirName: String,
        /** Paths relative to destDirName that must be executable. */
        val executables: List<String>
    )

    val COMPONENTS = listOf(
        Component("runtime/python.zip", "python", listOf("bin/python")),
        Component("runtime/ytdlp.zip", "ytdlp", emptyList()),
        Component("runtime/qjs.zip", "qjs", listOf("bin/qjs")),
        Component("runtime/ffmpeg.zip", "ffmpeg", listOf("bin/ffmpeg"))
    )

    /**
     * Extract all components if the bundled [version] differs from the
     * marker file. [onProgress] receives 0..1 as each component finishes.
     * Returns true when the runtime is ready (already extracted or freshly
     * extracted), false on any failure.
     */
    fun extractIfNeeded(
        context: Context,
        version: String,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        val runtimeDir = RuntimePaths.runtimeDir(context)
        val marker = File(runtimeDir, MARKER)
        if (marker.exists() && marker.readText().trim() == version) {
            return true
        }
        try {
            if (runtimeDir.exists()) {
                runtimeDir.deleteRecursively()
            }
            runtimeDir.mkdirs()
            COMPONENTS.forEachIndexed { index, component ->
                val dest = File(runtimeDir, component.destDirName)
                dest.mkdirs()
                context.assets.open(component.assetZip).use { input ->
                    unzip(input, dest)
                }
                component.executables.forEach { relativePath ->
                    val binary = File(dest, relativePath)
                    if (binary.exists()) {
                        binary.setExecutable(true)
                    } else {
                        Log.w(TAG, "Expected executable missing: " + binary.absolutePath)
                    }
                }
                onProgress?.invoke((index + 1).toFloat() / COMPONENTS.size)
            }
            marker.writeText(version)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Runtime extraction failed", e)
            return false
        }
    }

    /** Unzip [input] into [destDir], rejecting entries that escape it. */
    private fun unzip(input: InputStream, destDir: File) {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            val destRoot = destDir.canonicalPath + File.separator
            var entry: ZipEntry? = zip.nextEntry
            while (true) {
                // Copy to a val: the loop var can't be smart-cast inside
                // the require lambda below (Kotlin forbids smart-casting a
                // var captured by a closure), so all uses go through this.
                val current = entry ?: break
                val out = File(destDir, current.name)
                require(out.canonicalPath.startsWith(destRoot)) {
                    "Bad zip entry: " + current.name
                }
                if (current.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { output ->
                        zip.copyTo(output)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
