package com.graball.engine

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * First-launch extractor for GrabAll's embedded Python/yt-dlp runtime.
 *
 * Under targetSdk 34+ (Android 10-16 W^X SELinux), all native ELF binaries and
 * Python C extensions (.so) ship in jniLibs/<abi>/lib*.so so Android extracts
 * ONLY the matching ABI into nativeLibraryDir with executable permissions.
 *
 * This extractor unzips only the pure-Python assets (python-shared.zip,
 * per-arch _sysconfigdata, and ytdlp.zip) and wires symlinks inside
 * filesDir/runtime/ pointing to nativeLibraryDir/lib*.so so Python's
 * lib-dynload and yt-dlp's --ffmpeg-location see their exact expected filenames.
 * Falls back cleanly to legacy asset zips if jniLibs aren't present.
 */
object RuntimeExtractor {

    private const val TAG = "RuntimeExtractor"
    private const val MARKER = ".version"
    private const val DYNLOAD_PREFIX = "libpydyn_"

    var lastError: String? = null
        private set

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

    private data class Component(
        val assetZip: String,
        val destDirName: String,
        val executables: List<String>
    )

    private val DEVICE_ABI: String? = run {
        val abis = android.os.Build.SUPPORTED_ABIS
        when {
            abis.any { it == "arm64-v8a" } -> "arm64-v8a"
            abis.any { it == "armeabi-v7a" } -> "armeabi-v7a"
            else -> null
        }
    }

    private fun assetExists(context: Context, path: String): Boolean {
        return try {
            context.assets.open(path).use { true }
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun extractIfNeeded(
        context: Context,
        version: String,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        val abi = DEVICE_ABI
        if (abi == null) {
            val reported = android.os.Build.SUPPORTED_ABIS.joinToString()
            val err = "Unsupported CPU architecture ($reported). GrabAll requires an ARM device."
            lastError = err
            Log.e(TAG, err)
            return false
        }
        val nativeDir = RuntimePaths.nativeLibDir(context)
        val hasJniRuntime = File(nativeDir, "libpython_exec.so").exists()
        val runtimeDir = RuntimePaths.runtimeDir(context)
        val marker = File(runtimeDir, MARKER)
        val modeTag = if (hasJniRuntime) "jni" else "zip"
        // Include nativeDir path in marker too: Android changes the /data/app/~~xxx
        // path on every APK update, so symlinks to nativeLibraryDir must refresh!
        val expectedMarker = "$version-$abi-$modeTag-${nativeDir.absolutePath.hashCode()}"
        if (marker.exists() && marker.readText().trim() == expectedMarker) {
            return true
        }
        lastError = null
        val is64Bit = abi == "arm64-v8a"
        val components = if (hasJniRuntime) {
            val list = mutableListOf(
                Component("runtime/python-shared.zip", "python", emptyList()),
                Component("runtime/ytdlp.zip", "ytdlp", emptyList())
            )
            val sysconfigZip = if (is64Bit) "runtime/python-sysconfig-64.zip" else "runtime/python-sysconfig-32.zip"
            if (assetExists(context, sysconfigZip)) {
                list.add(Component(sysconfigZip, "python", emptyList()))
            }
            list
        } else {
            val pythonZip = if (is64Bit) "runtime/python64.zip" else "runtime/python32.zip"
            val qjsZip = if (is64Bit) "runtime/qjs64.zip" else "runtime/qjs32.zip"
            val ffmpegZip = if (is64Bit) "runtime/ffmpeg64.zip" else "runtime/ffmpeg32.zip"
            listOf(
                Component("runtime/python-shared.zip", "python", emptyList()),
                Component(pythonZip, "python", listOf("bin/python")),
                Component("runtime/ytdlp.zip", "ytdlp", emptyList()),
                Component(qjsZip, "qjs", listOf("bin/qjs")),
                Component(ffmpegZip, "ffmpeg", listOf("bin/ffmpeg", "bin/ffprobe"))
            )
        }

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
            if (hasJniRuntime) {
                linkJniBinaries(nativeDir, runtimeDir)
            }
            marker.writeText(expectedMarker)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Runtime extraction failed", e)
            lastError = e.message ?: e.toString()
            return false
        }
    }

    /**
     * Create symlinks inside filesDir/runtime/ pointing at nativeLibraryDir/lib*.so
     * so Python's lib-dynload modules and yt-dlp's ffmpeg/ffprobe lookup work
     * seamlessly under targetSdk 34+ SELinux rules.
     */
    private fun linkJniBinaries(nativeDir: File, runtimeDir: File) {
        val pyBinDir = File(runtimeDir, "python/bin").apply { mkdirs() }
        val pyLibDir = File(runtimeDir, "python/lib").apply { mkdirs() }
        val dynloadDir = File(runtimeDir, "python/lib/python3.13/lib-dynload").apply { mkdirs() }
        val ffmpegBinDir = File(runtimeDir, "ffmpeg/bin").apply { mkdirs() }
        val qjsBinDir = File(runtimeDir, "qjs/bin").apply { mkdirs() }

        symlinkIfTargetExists(File(nativeDir, "libpython_exec.so"), File(pyBinDir, "python"))
        symlinkIfTargetExists(File(nativeDir, "libpython3.13.so"), File(pyLibDir, "libpython3.13.so"))
        symlinkIfTargetExists(File(nativeDir, "libffmpeg.so"), File(ffmpegBinDir, "ffmpeg"))
        symlinkIfTargetExists(File(nativeDir, "libffprobe.so"), File(ffmpegBinDir, "ffprobe"))
        symlinkIfTargetExists(File(nativeDir, "libqjs.so"), File(qjsBinDir, "qjs"))

        // Link all Python C extension modules: libpydyn_<orig>.so -> lib-dynload/<orig>.so
        nativeDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(DYNLOAD_PREFIX) && file.name.endsWith(".so")) {
                val origName = file.name.removePrefix(DYNLOAD_PREFIX)
                symlinkIfTargetExists(file, File(dynloadDir, origName))
            }
        }
    }

    private fun symlinkIfTargetExists(target: File, link: File) {
        if (!target.exists()) return
        if (link.exists()) link.delete()
        link.parentFile?.mkdirs()
        Os.symlink(target.absolutePath, link.absolutePath)
    }

    private fun unzip(input: InputStream, destDir: File) {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            val destRoot = destDir.canonicalPath + File.separator
            var entry: ZipEntry? = zip.nextEntry
            while (true) {
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
