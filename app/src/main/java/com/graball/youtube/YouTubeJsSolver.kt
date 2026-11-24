package com.graball.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.graball.engine.RuntimePaths
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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
 * Why this is ~30x-100x faster than spawning yt-dlp + QuickJS on VPN:
 * - Android System WebView runs Google's V8 JIT engine on a background renderer
 *   thread (never blocking the Android UI thread).
 * - Fetches the ~1 KB `iframe_api` to find YouTube's active `player_id` and
 *   downloads `base.js` + `signatureTimestamp` ONCE per player version.
 * - Uses the bundled `yt-dlp-ejs` (`lib.min.js` + `core.min.js`) to preprocess
 *   `base.js` ONCE (~1-2s in V8 JIT), caches the preprocessed player to disk
 *   (`filesDir/yt-v8-cache/`), and keeps `window.__ytSolver = { n, sig }` live
 *   in V8 memory across videos.
 * - Every subsequent video resolves all `sig` and `n` challenges in < 10 ms
 *   with zero CPython startup, zero QuickJS subprocesses, and zero 1 MB watch
 *   webpage downloads.
 */
object YouTubeJsSolver {

    private const val TAG = "YouTubeJsSolver"
    private const val PLAYER_REFRESH_INTERVAL_MS = 30L * 60L * 1000L
    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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

    // Temporary bridge payloads cleared immediately after WebView reads them.
    @Volatile
    private var pendingLibJs: String = ""

    @Volatile
    private var pendingCoreJs: String = ""

    @Volatile
    private var pendingPlayerJs: String = ""

    @Volatile
    private var pendingCachedPreprocessed: String = ""

    @Volatile
    private var initLatch: CountDownLatch? = null

    @Volatile
    private var initOk: Boolean = false

    @Volatile
    private var initPreprocessedOutput: String = ""

    @Volatile
    private var initError: String = ""

    private val solvePayloads = ConcurrentHashMap<String, String>()
    private val solveLatches = ConcurrentHashMap<String, CountDownLatch>()
    private val solveResults = ConcurrentHashMap<String, String>()

    private val jsBridge = object {
        @JavascriptInterface
        fun getLibJs(): String = pendingLibJs

        @JavascriptInterface
        fun getCoreJs(): String = pendingCoreJs

        @JavascriptInterface
        fun getPlayerJs(): String = pendingPlayerJs

        @JavascriptInterface
        fun getCachedPreprocessed(): String = pendingCachedPreprocessed

        @JavascriptInterface
        fun onInitDone(ok: Boolean, payloadOrError: String) {
            initOk = ok
            if (ok) {
                initPreprocessedOutput = payloadOrError
                initError = ""
            } else {
                initPreprocessedOutput = ""
                initError = payloadOrError
            }
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

            val playerId = fetchActivePlayerId() ?: return again
            // If playerId didn't change and solver is already live in memory, just refresh timestamp!
            if (!forceRefresh && again != null && again.playerId == playerId && webView != null) {
                lastCheckedAtMs = System.currentTimeMillis()
                return again
            }

            val cacheDir = File(context.filesDir, "yt-v8-cache").apply { mkdirs() }
            val preprocessedFile = File(cacheDir, "player_$playerId.js")
            val stsFile = File(cacheDir, "player_$playerId.sts")

            val cachedSts = stsFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            val cachedPreprocessed = preprocessedFile.takeIf { it.isFile && it.length() > 10_000L }
                ?.let { runCatching { it.readText() }.getOrNull() }

            if (!ensureWebViewCreated(context.applicationContext)) {
                return null
            }

            // Fast warm-restart path: preprocessed player already cached on disk (~150ms in V8!)
            if (cachedSts != null && !cachedPreprocessed.isNullOrBlank()) {
                val ok = loadCachedPreprocessedIntoV8(cachedPreprocessed)
                if (ok) {
                    val info = ReadyInfo(playerId, cachedSts)
                    readyInfo = info
                    lastCheckedAtMs = System.currentTimeMillis()
                    Log.d(TAG, "Warm V8 solver loaded from disk cache (pid=$playerId, sts=$cachedSts)")
                    return info
                }
                runCatching { preprocessedFile.delete() }
            }

            // Cold path (once per YouTube player version): fetch base.js & preprocess in V8 (~1-2s!)
            val baseJs = fetchBaseJs(playerId) ?: return null
            val sts = Regex("""signatureTimestamp:(\d+)""").find(baseJs)?.groupValues?.get(1)?.toIntOrNull()
                ?: return null
            val (libJs, coreJs) = loadEjsScripts(context) ?: return null

            val preprocessed = preprocessAndLoadInV8(libJs, coreJs, baseJs) ?: return null
            runCatching {
                preprocessedFile.writeText(preprocessed)
                stsFile.writeText(sts.toString())
                pruneCacheDir(cacheDir)
            }

            val info = ReadyInfo(playerId, sts)
            readyInfo = info
            lastCheckedAtMs = System.currentTimeMillis()
            Log.d(TAG, "Cold V8 solver compiled & cached (pid=$playerId, sts=$sts)")
            return info
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
            Log.w(TAG, "V8 solve timed out or returned empty")
            return null
        }

        return try {
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) {
                Log.w(TAG, "V8 solve error: ${root.optString("error")}")
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
            Log.w(TAG, "Failed parsing V8 solve output: ${e.message}")
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
                    override fun onPageFinished(view: WebView, url: String) {
                        if (!created) {
                            created = true
                            latch.countDown()
                        }
                    }
                }
                webView = wv
                wv.loadDataWithBaseURL(
                    "https://www.youtube.com/",
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

    private fun loadCachedPreprocessedIntoV8(preprocessed: String): Boolean {
        val wv = webView ?: return false
        pendingCachedPreprocessed = preprocessed
        val latch = CountDownLatch(1)
        initLatch = latch
        initOk = false
        initError = ""

        val js = """
            setTimeout(function() {
              try {
                var pp = GrabAllBridge.getCachedPreprocessed();
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
            }, 0);
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
            pendingCachedPreprocessed = ""
            initLatch = null
        }
        if (!done || !initOk) {
            Log.w(TAG, "Warm V8 load failed: $initError")
            return false
        }
        return true
    }

    private fun preprocessAndLoadInV8(
        libJs: String,
        coreJs: String,
        baseJs: String
    ): String? {
        val wv = webView ?: return null
        pendingLibJs = libJs
        pendingCoreJs = coreJs
        pendingPlayerJs = baseJs
        val latch = CountDownLatch(1)
        initLatch = latch
        initOk = false
        initPreprocessedOutput = ""
        initError = ""

        val js = """
            setTimeout(function() {
              try {
                if (!window.__ejsLoaded) {
                  (0, eval)(GrabAllBridge.getLibJs());
                  Object.assign(globalThis, lib);
                  (0, eval)(GrabAllBridge.getCoreJs());
                  window.__ejsLoaded = true;
                }
                var res = jsc({
                  type: "player",
                  player: GrabAllBridge.getPlayerJs(),
                  requests: [],
                  output_preprocessed: true
                });
                if (!res || res.type === "error" || !res.preprocessed_player) {
                  GrabAllBridge.onInitDone(false, res ? String(res.error || "empty") : "null_res");
                  return;
                }
                var s = { n: null, sig: null };
                Function("_result", res.preprocessed_player)(s);
                if (!s.sig || !s.n) {
                  GrabAllBridge.onInitDone(false, "missing_sig_or_n");
                  return;
                }
                window.__ytSolver = s;
                GrabAllBridge.onInitDone(true, res.preprocessed_player);
              } catch (e) {
                GrabAllBridge.onInitDone(false, String(e));
              }
            }, 0);
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
            pendingLibJs = ""
            pendingCoreJs = ""
            pendingPlayerJs = ""
            initLatch = null
        }

        if (!done || !initOk || initPreprocessedOutput.isBlank()) {
            Log.w(TAG, "Cold V8 preprocess failed: $initError")
            return null
        }
        return initPreprocessedOutput.also { initPreprocessedOutput = "" }
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

    private fun fetchBaseJs(playerId: String): String? {
        val url = "https://www.youtube.com/s/player/$playerId/player_ias.vflset/en_US/base.js"
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
            Log.w(TAG, "Failed fetching base.js ($playerId): ${e.message}")
            null
        }
    }

    /**
     * Loads `lib.min.js` and `core.min.js` from extracted runtime files if
     * available, otherwise directly from the bundled `runtime/ytdlp.zip` asset
     * (so V8 works even before Python runtime extraction has ever run!).
     */
    private fun loadEjsScripts(context: Context): Pair<String, String>? {
        val extractedDir = File(RuntimePaths.ytDlpBundleDir(context), "yt_dlp_ejs/yt/solver")
        val libFile = File(extractedDir, "lib.min.js")
        val coreFile = File(extractedDir, "core.min.js")
        if (libFile.isFile && coreFile.isFile) {
            val lib = runCatching { libFile.readText() }.getOrNull()
            val core = runCatching { coreFile.readText() }.getOrNull()
            if (!lib.isNullOrBlank() && !core.isNullOrBlank()) {
                return lib to core
            }
        }

        var lib: String? = null
        var core: String? = null
        try {
            ZipInputStream(context.assets.open("runtime/ytdlp.zip").buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "yt_dlp_ejs/yt/solver/lib.min.js" -> lib = zis.readBytes().toString(Charsets.UTF_8)
                        "yt_dlp_ejs/yt/solver/core.min.js" -> core = zis.readBytes().toString(Charsets.UTF_8)
                    }
                    if (lib != null && core != null) break
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading ejs scripts from assets: ${e.message}")
        }
        return if (!lib.isNullOrBlank() && !core.isNullOrBlank()) lib!! to core!! else null
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
