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
 * native-asset packaging step) and is unzipped into filesDir/runtime/
 * on first launch. The native sets come in a 32-bit and a 64-bit variant
 * (python32/python64, qjs32/qjs64, ffmpeg32/ffmpeg64) plus two shared
 * zips (python-shared, ytdlp); componentsForAbi() picks the set matching
 * the device. Extraction is version-gated: a marker file records the
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

    /** Reason the last extraction failed; null when it never ran or worked. */
    var lastError: String? = null
        private set

    /**
     * Make [file] executable, verifying the result. File.setExecutable
     * can silently fail on some devices (v0.2.0 left a non-executable
     * python binary behind), so fall back to chmod 755 and check
     * canExecute() afterwards. Returns true when the file is executable.
     */
    fun ensureExecutable(file: File): Boolean {
        if (file.canExecute()) return true
        try {
            file.setExecutable(true, false)
        } catch (_: Exception) {
        }
        if (file.canExecute()) return true
        try {
            val proc = Runtime.getRuntime()
                .exec(arrayOf("chmod", "755", file.absolutePath))
            proc.waitFor()
        } catch (_: Exception) {
        }
        return file.canExecute()
    }

    data class Component(
        /** Asset path of the component zip, e.g. "runtime/qjs.zip". */
        val assetZip: String,
        /** Directory under runtimeDir() to unzip into. */
        val destDirName: String,
        /** Paths relative to destDirName that must be executable. */
        val executables: List<String>
    )

    /**
     * Device ABI supported by the bundled runtimes, or null on non-ARM
     * devices (e.g. x86/x86_64 emulators without ARM translation).
     * Prefers 64-bit ARM (arm64-v8a) whenever available, falling back
     * to 32-bit ARM (armeabi-v7a) on 32-bit devices.
     */
    private val DEVICE_ABI: String? = run {
        val abis = android.os.Build.SUPPORTED_ABIS
        when {
            abis.any { it == "arm64-v8a" } -> "arm64-v8a"
            abis.any { it == "armeabi-v7a" } -> "armeabi-v7a"
            else -> null
        }
    }

    /**
     * Components for [abi]. The shared stdlib zip and yt-dlp are
     * arch-independent; the native sets (python, qjs, ffmpeg) come in
     * a 32-bit and a 64-bit variant. All variants unzip into the same
     * directories, so nothing downstream of extraction cares which ABI
     * was picked. The shared zip must extract before the arch python
     * zip (both land in "python").
     */
    private fun componentsForAbi(abi: String): List<Component> {
        val is64Bit = abi == "arm64-v8a"
        val pythonZip =
            if (is64Bit) "runtime/python64.zip" else "runtime/python32.zip"
        val qjsZip =
            if (is64Bit) "runtime/qjs64.zip" else "runtime/qjs32.zip"
        val ffmpegZip =
            if (is64Bit) "runtime/ffmpeg64.zip" else "runtime/ffmpeg32.zip"
        return listOf(
            Component("runtime/python-shared.zip", "python", emptyList()),
            Component(pythonZip, "python", listOf("bin/python")),
            Component("runtime/ytdlp.zip", "ytdlp", emptyList()),
            Component(qjsZip, "qjs", listOf("bin/qjs")),
            Component(ffmpegZip, "ffmpeg", listOf("bin/ffmpeg", "bin/ffprobe"))
        )
    }

    /**
     * Extract all components if the bundled [version] (plus device ABI)
     * differs from the marker file. [onProgress] receives 0..1 as each
     * component finishes. Returns true when the runtime is ready (already
     * extracted or freshly extracted), false on any failure.
     *
     * Synchronized so two downloads started back-to-back on first launch
     * cannot race and delete runtimeDir out from under each other.
     */
    @Synchronized
    fun extractIfNeeded(
        context: Context,
        version: String,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        val abi = DEVICE_ABI
        if (abi == null) {
            val reported = android.os.Build.SUPPORTED_ABIS.joinToString()
            lastError = "Unsupported CPU architecture ($reported). GrabAll requires an ARM device."
            Log.e(TAG, lastError!!)
            return false
        }
        val runtimeDir = RuntimePaths.runtimeDir(context)
        val marker = File(runtimeDir, MARKER)
        // Include ABI in the marker so a backup restored from a 32-bit
        // phone onto a 64-bit phone always re-extracts the right binaries.
        val expectedMarker = "$version-$abi"
        if (marker.exists() && marker.readText().trim() == expectedMarker) {
            return true
        }
        lastError = null
        val components = componentsForAbi(abi)
        try {
            if (runtimeDir.exists()) {
                runtimeDir.deleteRecursively()
            }
            runtimeDir.mkdirs()
            components.forEachIndexed { index, component ->
                val dest = File(runtimeDir, component.destDirName)
                dest.mkdirs()
                context.assets.open(component.assetZip).use { input ->
                    unzip(input, dest)
                }
                component.executables.forEach { relativePath ->
                    val binary = File(dest, relativePath)
                    if (binary.exists()) {
                        if (!ensureExecutable(binary)) {
                            Log.w(TAG, "Could not make executable: " + binary.absolutePath)
                        }
                    } else if (relativePath != "bin/ffprobe") {
                        Log.w(TAG, "Expected executable missing: " + binary.absolutePath)
                    }
                }
                onProgress?.invoke((index + 1).toFloat() / components.size)
            }
            marker.writeText(expectedMarker)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Runtime extraction failed", e)
            lastError = e.message ?: e.toString()
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
