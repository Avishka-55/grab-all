package com.graball.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.graball.engine.RuntimePaths
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Persistent, in-memory YouTube JS (`sig` + `n`) solver powered by Android's
 * built-in Chromium WebView (V8 JIT engine).
 *
 * Key design details for Android WebView reliability:
 * - Tries player JS variants in priority order (`tv-player-ias.js` ->
 *   `player_es6` -> `player_ias`) because YouTube A/B players like `b485e332`
 *   crash `yt-dlp-ejs` on `player_ias` while `tv-player-ias` and `player_es6`
 *   succeed cleanly.
 * - Serves multi-megabyte scripts (`base.js`, `lib.min.js`, `core.min.js`,
 *   cached `preprocessed.js`) via `shouldInterceptRequest` (`fetch()`) instead
 *   of `@JavascriptInterface` return values, and streams `preprocessed_player`
 *   back in 256 KB chunks — completely avoiding Android WebView IPC/Binder
 *   transaction size limits.
 * - Compiles ONCE (~1-2s cold in V8 JIT, ~150ms warm from disk cache) and
 *   solves all `sig` + `n` challenges in **< 10 ms** per video in RAM.
 */
object YouTubeJsSolver {

    private const val TAG = "YouTubeJsSolver"
    // Use real youtube.com origin so Android WebView never fails synthetic .local
    // host resolution before invoking shouldInterceptRequest!
    private const val VIRTUAL_HOST = "www.youtube.com"
    private const val VIRTUAL_PATH_PREFIX = "/__graball_solver__"
    private const val VIRTUAL_ORIGIN = "https://$VIRTUAL_HOST$VIRTUAL_PATH_PREFIX/"
    private const val PLAYER_REFRESH_INTERVAL_MS = 30L * 60L * 1000L
    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val PLAYER_VARIANTS = listOf(
        "tv-player-ias.vflset/tv-player-ias.js",
        "player_es6.vflset/en_US/base.js",
        "player_ias.vflset/en_US/base.js"
    )

    data class ReadyInfo(
        val playerId: String,
        val signatureTimestamp: Int
    )

    data class SolveResult(
        val sigs: Map<String, String>,
        val ns: Map<String, String>
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val initLock = Any()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var readyInfo: ReadyInfo? = null

    @Volatile
    private var lastCheckedAtMs: Long = 0L

    @Volatile
    var lastError: String = ""
        private set

    // Served to the hidden WebView via shouldInterceptRequest (zero IPC size limit).
    @Volatile
    private var servedLibBytes: ByteArray = ByteArray(0)

    @Volatile
    private var servedCoreBytes: ByteArray = ByteArray(0)

    @Volatile
    private var servedPlayerBytes: ByteArray = ByteArray(0)

    @Volatile
    private var servedCachedPreprocessedBytes: ByteArray = ByteArray(0)

    @Volatile
    private var initLatch: CountDownLatch? = null

    @Volatile
    private var initOk: Boolean = false

    @Volatile
    private var initError: String = ""

    private val preprocessedBuf = StringBuilder()

    private val solvePayloads = ConcurrentHashMap<String, String>()
    private val solveLatches = ConcurrentHashMap<String, CountDownLatch>()
    private val solveResults = ConcurrentHashMap<String, String>()

    private val jsBridge = object {
        @JavascriptInterface
        fun beginPreprocessed() {
            synchronized(preprocessedBuf) {
                preprocessedBuf.setLength(0)
            }
        }

        @JavascriptInterface
        fun appendPreprocessedChunk(chunk: String) {
            synchronized(preprocessedBuf) {
                preprocessedBuf.append(chunk)
            }
        }

        @JavascriptInterface
        fun onInitDone(ok: Boolean, error: String) {
            initOk = ok
            initError = if (ok) "" else error
            initLatch?.countDown()
        }

        @JavascriptInterface
        fun getSolvePayload(reqId: String): String = solvePayloads.remove(reqId) ?: "{}"

        @JavascriptInterface
        fun onSolveDone(reqId: String, resultJson: String) {
            solveResults[reqId] = resultJson
            solveLatches[reqId]?.countDown()
        }
    }

    /** Returns true if a warm V8 solver is already compiled in RAM. */
    fun isWarmInMemory(): Boolean = readyInfo != null && webView != null

    /** Silently pre-warms the V8 solver on a background thread without blocking playback. */
    @Volatile
    private var prewarming: Boolean = false

    fun prewarmInBackground(context: Context) {
        if (isWarmInMemory() || prewarming) return
        prewarming = true
        val appCtx = context.applicationContext
        Thread {
            try {
                ensureReady(appCtx)
            } catch (_: Throwable) {
            } finally {
                prewarming = false
            }
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Forces a fresh player lookup on the next call (e.g. if YouTube rotated `sts`). */
    fun invalidate() {
        synchronized(initLock) {
            readyInfo = null
            lastCheckedAtMs = 0L
        }
    }

    /**
     * Ensures the persistent hidden V8 WebView has YouTube's active player
     * solver compiled in memory and returns its `signatureTimestamp`.
     * Call off the main thread.
     */
    fun ensureReady(context: Context, forceRefresh: Boolean = false): ReadyInfo? {
        val now = System.currentTimeMillis()
        val existing = readyInfo
        if (!forceRefresh && existing != null && webView != null &&
            (now - lastCheckedAtMs) < PLAYER_REFRESH_INTERVAL_MS
        ) {
            return existing
        }

        synchronized(initLock) {
            val again = readyInfo
            if (!forceRefresh && again != null && webView != null &&
                (System.currentTimeMillis() - lastCheckedAtMs) < PLAYER_REFRESH_INTERVAL_MS
            ) {
                return again
            }

            val playerId = fetchActivePlayerId()
            if (playerId == null) {
                lastError = "iframe_api lookup failed"
                return again
            }
            if (!forceRefresh && again != null && again.playerId == playerId && webView != null) {
                lastCheckedAtMs = System.currentTimeMillis()
                return again
            }

            val cacheDir = File(context.filesDir, "yt-v8-cache").apply { mkdirs() }
            val preprocessedFile = File(cacheDir, "player_$playerId.js")
            val stsFile = File(cacheDir, "player_$playerId.sts")

            val cachedSts = stsFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            val cachedPreprocessed = preprocessedFile.takeIf { it.isFile && it.length() > 10_000L }
                ?.let { runCatching { it.readBytes() }.getOrNull() }

            if (!ensureWebViewCreated(context.applicationContext)) {
                lastError = "Hidden V8 WebView creation failed"
                return null
            }

            // Fast warm-restart path: preprocessed player already cached on disk (~150ms in V8!)
            if (cachedSts != null && cachedPreprocessed != null && cachedPreprocessed.isNotEmpty()) {
                val ok = loadCachedPreprocessedIntoV8(cachedPreprocessed)
                if (ok) {
                    val info = ReadyInfo(playerId, cachedSts)
                    readyInfo = info
                    lastCheckedAtMs = System.currentTimeMillis()
                    lastError = ""
                    Log.d(TAG, "Warm V8 solver loaded from disk cache (pid=$playerId, sts=$cachedSts)")
                    return info
                }
                runCatching { preprocessedFile.delete() }
            }

            val (libBytes, coreBytes) = loadEjsScripts(context) ?: run {
                lastError = "Failed loading bundled yt-dlp-ejs scripts"
                return null
            }

            // Cold path: try each YouTube player JS variant until one preprocesses cleanly in V8.
            for (variant in PLAYER_VARIANTS) {
                val baseJs = fetchPlayerVariant(playerId, variant) ?: continue
                val sts = Regex("""signatureTimestamp:(\d+)""").find(baseJs)
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: continue

                val preprocessed = preprocessAndLoadInV8(
                    libBytes = libBytes,
                    coreBytes = coreBytes,
                    playerBytes = baseJs.toByteArray(Charsets.UTF_8)
                )
                if (preprocessed != null) {
                    runCatching {
                        preprocessedFile.writeText(preprocessed)
                        stsFile.writeText(sts.toString())
                        pruneCacheDir(cacheDir)
                    }
                    val info = ReadyInfo(playerId, sts)
                    readyInfo = info
                    lastCheckedAtMs = System.currentTimeMillis()
                    lastError = ""
                    Log.d(TAG, "Cold V8 solver compiled & cached (pid=$playerId, variant=$variant, sts=$sts)")
                    return info
                }
                Log.w(TAG, "Variant $variant failed in V8 ($initError), trying next variant")
            }

            lastError = initError.ifBlank { "All YouTube player variants failed in V8" }
            return null
        }
    }

    /**
     * Solves batches of YouTube `sig` and `n` challenges using the warm V8
     * solver in memory (< 10 ms). Call off the main thread after [ensureReady].
     */
    fun solve(
        sigs: Collection<String>,
        ns: Collection<String>
    ): SolveResult? {
        val wv = webView ?: return null
        if (readyInfo == null) return null
        val uniqueSigs = sigs.filter { it.isNotBlank() }.distinct()
        val uniqueNs = ns.filter { it.isNotBlank() }.distinct()
        if (uniqueSigs.isEmpty() && uniqueNs.isEmpty()) {
            return SolveResult(emptyMap(), emptyMap())
        }

        val reqId = UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("sigs", JSONArray(uniqueSigs))
            put("ns", JSONArray(uniqueNs))
        }.toString()

        val latch = CountDownLatch(1)
        solvePayloads[reqId] = payload
        solveLatches[reqId] = latch

        val js = """
            (function() {
              var id = ${JSONObject.quote(reqId)};
              try {
                var s = window.__ytSolver;
                if (!s || !s.sig || !s.n) {
                  GrabAllBridge.onSolveDone(id, JSON.stringify({ ok: false, error: "solver_not_ready" }));
                  return;
                }
                var p = JSON.parse(GrabAllBridge.getSolvePayload(id));
                var sigOut = {};
                for (var i = 0; i < p.sigs.length; i++) {
                  var k = p.sigs[i];
                  sigOut[k] = s.sig(k);
                }
                var nOut = {};
                for (var j = 0; j < p.ns.length; j++) {
                  var nk = p.ns[j];
                  nOut[nk] = s.n(nk);
                }
                GrabAllBridge.onSolveDone(id, JSON.stringify({ ok: true, sigs: sigOut, ns: nOut }));
              } catch (e) {
                GrabAllBridge.onSolveDone(id, JSON.stringify({ ok: false, error: String(e) }));
              }
            })();
        """.trimIndent()

        mainHandler.post {
            try {
                wv.evaluateJavascript(js, null)
            } catch (e: Exception) {
                solveResults[reqId] = JSONObject().put("ok", false).put("error", e.message).toString()
                latch.countDown()
            }
        }

        val finished = try {
            latch.await(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        } finally {
            solvePayloads.remove(reqId)
            solveLatches.remove(reqId)
        }
        val raw = solveResults.remove(reqId)
        if (!finished || raw.isNullOrBlank()) {
            lastError = "V8 solve timed out"
            Log.w(TAG, lastError)
            return null
        }

        return try {
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) {
                lastError = "V8 solve error: ${root.optString("error")}"
                Log.w(TAG, lastError)
                return null
            }
            val sigMap = HashMap<String, String>()
            val sigObj = root.optJSONObject("sigs")
            if (sigObj != null) {
                val keys = sigObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = sigObj.optString(k)
                    if (v.isNotBlank()) sigMap[k] = v
                }
            }
            val nMap = HashMap<String, String>()
            val nObj = root.optJSONObject("ns")
            if (nObj != null) {
                val keys = nObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = nObj.optString(k)
                    if (v.isNotBlank()) nMap[k] = v
                }
            }
            SolveResult(sigMap, nMap)
        } catch (e: Exception) {
            lastError = "V8 solve parse error: ${e.message}"
            Log.w(TAG, lastError)
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebViewCreated(appContext: Context): Boolean {
        if (webView != null) return true
        val latch = CountDownLatch(1)
        var created = false
        mainHandler.post {
            try {
                if (webView != null) {
                    created = true
                    latch.countDown()
                    return@post
                }
                val wv = WebView(appContext)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = false
                wv.addJavascriptInterface(jsBridge, "GrabAllBridge")
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val uri = request.url
                        val path = uri.path.orEmpty()
                        if (uri.host == VIRTUAL_HOST && path.startsWith(VIRTUAL_PATH_PREFIX)) {
                            val bytes = when (path.removePrefix(VIRTUAL_PATH_PREFIX)) {
                                "/lib.js" -> servedLibBytes
                                "/core.js" -> servedCoreBytes
                                "/base.js" -> servedPlayerBytes
                                "/cached.js" -> servedCachedPreprocessedBytes
                                else -> null
                            }
                            if (bytes != null) {
                                return WebResourceResponse(
                                    "application/javascript",
                                    "utf-8",
                                    200,
                                    "OK",
                                    mapOf(
                                        "Access-Control-Allow-Origin" to "*",
                                        "Cache-Control" to "no-store"
                                    ),
                                    ByteArrayInputStream(bytes)
                                )
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        if (!created) {
                            created = true
                            latch.countDown()
                        }
                    }
                }
                webView = wv
                wv.loadDataWithBaseURL(
                    VIRTUAL_ORIGIN,
                    "<!doctype html><html><body></body></html>",
                    "text/html",
                    "utf-8",
                    null
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Failed creating hidden V8 WebView", e)
                latch.countDown()
            }
        }
        return try {
            latch.await(8, TimeUnit.SECONDS) && created && webView != null
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun loadCachedPreprocessedIntoV8(preprocessedBytes: ByteArray): Boolean {
        val wv = webView ?: return false
        servedCachedPreprocessedBytes = preprocessedBytes
        val latch = CountDownLatch(1)
        initLatch = latch
        initOk = false
        initError = ""

        val js = """
            (async function() {
              try {
                var resp = await fetch("${VIRTUAL_ORIGIN}cached.js");
                var pp = await resp.text();
                var s = { n: null, sig: null };
                Function("_result", pp)(s);
                if (!s.sig || !s.n) {
                  GrabAllBridge.onInitDone(false, "missing_fns");
                  return;
                }
                window.__ytSolver = s;
                GrabAllBridge.onInitDone(true, "");
              } catch (e) {
                GrabAllBridge.onInitDone(false, String(e));
              }
            })();
        """.trimIndent()

        mainHandler.post {
            try {
                wv.evaluateJavascript(js, null)
            } catch (e: Exception) {
                initError = e.message ?: "eval_failed"
                latch.countDown()
            }
        }

        val done = try {
            latch.await(15, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        } finally {
            servedCachedPreprocessedBytes = ByteArray(0)
            initLatch = null
        }
        if (!done || !initOk) {
            Log.w(TAG, "Warm V8 load failed: $initError")
            return false
        }
        return true
    }

    private fun preprocessAndLoadInV8(
        libBytes: ByteArray,
        coreBytes: ByteArray,
        playerBytes: ByteArray
    ): String? {
        val wv = webView ?: return null
        servedLibBytes = libBytes
        servedCoreBytes = coreBytes
        servedPlayerBytes = playerBytes
        synchronized(preprocessedBuf) {
            preprocessedBuf.setLength(0)
        }
        val latch = CountDownLatch(1)
        initLatch = latch
        initOk = false
        initError = ""

        val js = """
            (async function() {
              try {
                if (!window.__ejsLoaded) {
                  var libText = await (await fetch("${VIRTUAL_ORIGIN}lib.js")).text();
                  (0, eval)(libText);
                  Object.assign(globalThis, lib);
                  var coreText = await (await fetch("${VIRTUAL_ORIGIN}core.js")).text();
                  (0, eval)(coreText);
                  window.__ejsLoaded = true;
                }
                var playerText = await (await fetch("${VIRTUAL_ORIGIN}base.js")).text();
                var res = jsc({
                  type: "player",
                  player: playerText,
                  requests: [],
                  output_preprocessed: true
                });
                if (!res || res.type === "error" || !res.preprocessed_player) {
                  GrabAllBridge.onInitDone(false, res ? String(res.error || "empty") : "null_res");
                  return;
                }
                var pp = res.preprocessed_player;
                var s = { n: null, sig: null };
                Function("_result", pp)(s);
                if (!s.sig || !s.n) {
                  GrabAllBridge.onInitDone(false, "missing_sig_or_n");
                  return;
                }
                window.__ytSolver = s;
                GrabAllBridge.beginPreprocessed();
                var chunkSize = 262144;
                for (var i = 0; i < pp.length; i += chunkSize) {
                  GrabAllBridge.appendPreprocessedChunk(pp.slice(i, i + chunkSize));
                }
                GrabAllBridge.onInitDone(true, "");
              } catch (e) {
                GrabAllBridge.onInitDone(false, String(e));
              }
            })();
        """.trimIndent()

        mainHandler.post {
            try {
                wv.evaluateJavascript(js, null)
            } catch (e: Exception) {
                initError = e.message ?: "eval_failed"
                latch.countDown()
            }
        }

        val done = try {
            latch.await(30, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        } finally {
            servedLibBytes = ByteArray(0)
            servedCoreBytes = ByteArray(0)
            servedPlayerBytes = ByteArray(0)
            initLatch = null
        }

        if (!done || !initOk) {
            Log.w(TAG, "Cold V8 preprocess failed: $initError")
            synchronized(preprocessedBuf) { preprocessedBuf.setLength(0) }
            return null
        }
        return synchronized(preprocessedBuf) {
            val out = preprocessedBuf.toString()
            preprocessedBuf.setLength(0)
            out.takeIf { it.isNotBlank() }
        }
    }

    private fun fetchActivePlayerId(): String? {
        return try {
            val req = Request.Builder()
                .url("https://www.youtube.com/iframe_api")
                .header("User-Agent", WEB_UA)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                Regex("""player\\?/([0-9a-f]{8})\\?/""").find(body)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed fetching iframe_api: ${e.message}")
            null
        }
    }

    private fun fetchPlayerVariant(playerId: String, variant: String): String? {
        val url = "https://www.youtube.com/s/player/$playerId/$variant"
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", WEB_UA)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()?.takeIf { it.length > 100_000 }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed fetching $variant ($playerId): ${e.message}")
            null
        }
    }

    private fun loadEjsScripts(context: Context): Pair<ByteArray, ByteArray>? {
        val extractedDir = File(RuntimePaths.ytDlpBundleDir(context), "yt_dlp_ejs/yt/solver")
        val libFile = File(extractedDir, "lib.min.js")
        val coreFile = File(extractedDir, "core.min.js")
        if (libFile.isFile && coreFile.isFile) {
            val lib = runCatching { libFile.readBytes() }.getOrNull()
            val core = runCatching { coreFile.readBytes() }.getOrNull()
            if (lib != null && lib.isNotEmpty() && core != null && core.isNotEmpty()) {
                return lib to core
            }
        }

        var lib: ByteArray? = null
        var core: ByteArray? = null
        try {
            ZipInputStream(context.assets.open("runtime/ytdlp.zip").buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "yt_dlp_ejs/yt/solver/lib.min.js" -> lib = zis.readBytes()
                        "yt_dlp_ejs/yt/solver/core.min.js" -> core = zis.readBytes()
                    }
                    if (lib != null && core != null) break
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading ejs scripts from assets: ${e.message}")
        }
        val finalLib = lib
        val finalCore = core
        return if (finalLib != null && finalLib.isNotEmpty() && finalCore != null && finalCore.isNotEmpty()) {
            finalLib to finalCore
        } else {
            null
        }
    }

    private fun pruneCacheDir(dir: File) {
        val players = dir.listFiles { f -> f.isFile && f.name.endsWith(".js") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (players.size > 2) {
            players.drop(2).forEach { jsFile ->
                runCatching { jsFile.delete() }
                runCatching { File(dir, jsFile.name.removeSuffix(".js") + ".sts").delete() }
            }
        }
    }
}
