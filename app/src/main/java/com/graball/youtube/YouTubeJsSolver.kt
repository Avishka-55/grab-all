package com.graball.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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

/**
 * Lightweight, ~165ms YouTube JS (`sig` + `n`) solver powered by Android's
 * built-in Chromium WebView (V8 JIT engine).
 *
 * Instead of running a heavy 2.5 MB JS AST parser on mobile, this fetches
 * YouTube's active `player_ias.vflset/en_US/base.js` once (cached on disk by
 * player ID), exports YouTube's own internal URL-helper & signature functions
 * via a single patch before `})(_yt_player);`, and executes `base.js` natively
 * via a `<script src>` tag in one hidden WebView:
 * - Native V8 compile: ~150-300 ms (zero AST parsing, zero OOM)
 * - Solve `sig` + `n`: < 5 ms in RAM, with an idempotent O(1) cache for HLS `/n/`
 */
object YouTubeJsSolver {

    private const val TAG = "YouTubeJsSolver"
    private const val VIRTUAL_HOST = "www.youtube.com"
    private const val VIRTUAL_PATH = "/__graball_solver__/player.js"
    private const val PLAYER_REFRESH_INTERVAL_MS = 30L * 60L * 1000L
    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val PLAYER_VARIANT = "player_ias.vflset/en_US/base.js"
    private const val PLAYER_FOOTER = "})(_yt_player);"

    // Matches YouTube's modern URL-helper signature wrapper in player_ias/base.js:
    //   $o=function(a,b,c){ ... new g.Xo(a,true) ... .set("alr","yes") ... return ... }
    // Group 1 = sig function name, Group 2 = URL helper class name (e.g. g.Xo)
    private val URL_HELPER_SIG_REGEX = Regex(
        """([a-zA-Z0-9_${'$'}]{2,})=function\s*\([a-zA-Z0-9_${'$'}]+,[a-zA-Z0-9_${'$'}]+,[a-zA-Z0-9_${'$'}]+\)\{(?:[^}{]{0,600}\bnew\s+(g\.[a-zA-Z0-9_${'$'}]+)\s*\([a-zA-Z0-9_${'$'}]+,(?:!0|true)\)[^}{]{0,400}\.set\(["']alr["'],["']yes["']\)[^}{]{0,200}\breturn\b)"""
    )
    private val STS_REGEX = Regex("""signatureTimestamp[=:](\d+)""")
    private val HLS_PATH_N_REGEX = Regex("""(/n/)([^/?#]+)""")
    private val QUERY_N_REGEX = Regex("""([?&]n=)([^&]+)""")

    data class ReadyInfo(
        val playerId: String,
        val signatureTimestamp: Int
    )

    data class SolveResult(
        val sigs: Map<String, String>,
        val ns: Map<String, String>
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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

    @Volatile
    private var servedPatchedPlayerBytes: ByteArray = ByteArray(0)

    @Volatile
    private var initLatch: CountDownLatch? = null

    @Volatile
    private var initOk: Boolean = false

    @Volatile
    private var initError: String = ""

    private val solvePayloads = ConcurrentHashMap<String, String>()
    private val solveLatches = ConcurrentHashMap<String, CountDownLatch>()
    private val solveResults = ConcurrentHashMap<String, String>()

    // Idempotent O(1) cache of solved `n` values so HLS master/variant/segment
    // URLs resolve instantly and are never double-solved.
    private val rawToSolvedN = ConcurrentHashMap<String, String>()
    private val solvedNSet = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    @Volatile
    private var prewarming: Boolean = false

    private val jsBridge = object {
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

    /** Returns true if a warm native V8 solver is already compiled in RAM. */
    fun isWarmInMemory(): Boolean = readyInfo != null && webView != null

    /** Silently pre-warms the native V8 solver on a background thread. */
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
            start()
        }
    }

    /** Forces a fresh player lookup on the next call (e.g. if YouTube rotated `sts`). */
    fun invalidate() {
        synchronized(initLock) {
            readyInfo = null
            lastCheckedAtMs = 0L
            rawToSolvedN.clear()
            solvedNSet.clear()
        }
    }

    /**
     * Solves a single YouTube `n` challenge using the warm V8 solver with an
     * idempotent O(1) cache (already-solved tokens are returned untouched).
     */
    fun solveSingleN(rawN: String): String? {
        if (rawN.isBlank()) return null
        if (rawN in solvedNSet) return rawN
        rawToSolvedN[rawN]?.let { return it }
        if (!isWarmInMemory()) return null
        val solved = solve(emptyList(), listOf(rawN))?.ns?.get(rawN)?.takeIf { it.isNotBlank() }
            ?: return null
        if (rawToSolvedN.size > 256) {
            rawToSolvedN.clear()
            solvedNSet.clear()
        }
        rawToSolvedN[rawN] = solved
        solvedNSet.add(solved)
        return solved
    }

    /**
     * Rewrites any unsolved YouTube `n` challenge in [url] — supporting both
     * HLS path-style `/n/<token>/` and progressive query-style `?n=<token>` / `&n=<token>`.
     * Safe to call repeatedly on the same URL (idempotent).
     */
    fun solveUrlNParam(url: String): String {
        if (!isWarmInMemory()) return url
        var out = url
        val pathMatch = HLS_PATH_N_REGEX.find(out)
        if (pathMatch != null) {
            val rawEncoded = pathMatch.groupValues[2]
            val rawDecoded = urlDecode(rawEncoded)
            val solved = solveSingleN(rawDecoded)
            if (!solved.isNullOrBlank() && solved != rawDecoded) {
                out = out.replaceRange(pathMatch.range, pathMatch.groupValues[1] + urlEncode(solved))
            }
        }
        val queryMatch = QUERY_N_REGEX.find(out)
        if (queryMatch != null) {
            val rawEncoded = queryMatch.groupValues[2]
            val rawDecoded = urlDecode(rawEncoded)
            val solved = solveSingleN(rawDecoded)
            if (!solved.isNullOrBlank() && solved != rawDecoded) {
                out = out.replaceRange(queryMatch.range, queryMatch.groupValues[1] + urlEncode(solved))
            }
        }
        return out
    }

    /**
     * Ensures the persistent hidden V8 WebView has YouTube's active `base.js`
     * solver compiled natively in RAM (~150-300ms) and returns its `sts`.
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
            val patchedFile = File(cacheDir, "native_$playerId.js")
            val stsFile = File(cacheDir, "native_$playerId.sts")

            var sts = stsFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            var patchedBytes = patchedFile.takeIf { it.isFile && it.length() > 100_000L }
                ?.let { runCatching { it.readBytes() }.getOrNull() }

            if (sts == null || patchedBytes == null || patchedBytes.isEmpty()) {
                val baseJs = fetchPlayerJs(playerId) ?: run {
                    lastError = "Failed downloading player_ias base.js ($playerId)"
                    return null
                }
                sts = STS_REGEX.find(baseJs)?.groupValues?.get(1)?.toIntOrNull() ?: run {
                    lastError = "signatureTimestamp not found in base.js"
                    return null
                }
                val patched = patchBaseJsWithExports(baseJs) ?: run {
                    lastError = "URL-helper sig/n pattern not found in base.js ($playerId)"
                    Log.w(TAG, lastError)
                    return null
                }
                patchedBytes = patched.toByteArray(Charsets.UTF_8)
                runCatching {
                    patchedFile.writeBytes(patchedBytes)
                    stsFile.writeText(sts.toString())
                    pruneCacheDir(cacheDir)
                }
            }

            val ok = loadPatchedPlayerIntoV8(context.applicationContext, patchedBytes)
            if (!ok) {
                runCatching { patchedFile.delete() }
                lastError = initError.ifBlank { "Native V8 base.js init failed" }
                Log.w(TAG, lastError)
                return null
            }

            val info = ReadyInfo(playerId, sts)
            readyInfo = info
            lastCheckedAtMs = System.currentTimeMillis()
            lastError = ""
            rawToSolvedN.clear()
            solvedNSet.clear()
            Log.d(TAG, "Native V8 solver ready (pid=$playerId, sts=$sts)")
            return info
        }
    }

    /**
     * Solves batches of YouTube `sig` and `n` challenges using the warm V8
     * solver in memory (< 5 ms). Call off the main thread after [ensureReady].
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
            latch.await(8, TimeUnit.SECONDS)
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

    /**
     * Patches `player_ias/base.js` right before its closing `})(_yt_player);`
     * to export YouTube's own signature & `n` URL-helper functions directly onto
     * `window.__ytSolver` — zero AST parsing needed.
     */
    private fun patchBaseJsWithExports(baseJs: String): String? {
        val footerIdx = baseJs.lastIndexOf(PLAYER_FOOTER)
        if (footerIdx < 0) return null
        val match = URL_HELPER_SIG_REGEX.find(baseJs) ?: return null
        val sigFn = match.groupValues[1]
        val urlHelper = match.groupValues[2]
        if (sigFn.isBlank() || urlHelper.isBlank()) return null

        val exportBlock = buildString {
            append("\nwindow.__ytSolver={\n")
            append("  sig:function(x){var h=").append(sigFn)
            append("('https://www.youtube.com/videoplayback','signature',String(x));return h&&h.get?h.get('signature'):'';},\n")
            append("  n:function(x){var h=new ").append(urlHelper)
            append("('https://www.youtube.com/videoplayback?n='+encodeURIComponent(String(x)),true);return h&&h.get?h.get('n'):'';}\n")
            append("};\n")
        }
        return baseJs.substring(0, footerIdx) + exportBlock + baseJs.substring(footerIdx)
    }

    /**
     * Loads the patched `base.js` natively via a `<script src>` tag intercepted
     * by `shouldInterceptRequest` (zero `fetch()`, zero CORS issues, zero IPC
     * size limits) and verifies the `n` solver with a quick self-test.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun loadPatchedPlayerIntoV8(appContext: Context, patchedBytes: ByteArray): Boolean {
        servedPatchedPlayerBytes = patchedBytes
        val latch = CountDownLatch(1)
        initLatch = latch
        initOk = false
        initError = ""

        val html = """
            <!doctype html>
            <html>
            <head>
              <script>window._yt_player = {};</script>
              <script src="https://$VIRTUAL_HOST$VIRTUAL_PATH"></script>
              <script>
                (function() {
                  try {
                    var s = window.__ytSolver;
                    if (!s || typeof s.sig !== 'function' || typeof s.n !== 'function') {
                      GrabAllBridge.onInitDone(false, 'missing_solver_exports');
                      return;
                    }
                    var probe = s.n('abcdef123456');
                    if (!probe || probe === 'abcdef123456') {
                      GrabAllBridge.onInitDone(false, 'n_probe_unchanged');
                      return;
                    }
                    GrabAllBridge.onInitDone(true, '');
                  } catch (e) {
                    GrabAllBridge.onInitDone(false, String(e));
                  }
                })();
              </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        mainHandler.post {
            try {
                val wv = webView ?: WebView(appContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.blockNetworkImage = true
                    settings.loadsImagesAutomatically = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        settings.safeBrowsingEnabled = false
                    }
                    addJavascriptInterface(jsBridge, "GrabAllBridge")
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val uri = request.url
                            if (uri.host == VIRTUAL_HOST && uri.path == VIRTUAL_PATH) {
                                return WebResourceResponse(
                                    "application/javascript",
                                    "utf-8",
                                    200,
                                    "OK",
                                    mapOf(
                                        "Access-Control-Allow-Origin" to "*",
                                        "Cache-Control" to "no-store"
                                    ),
                                    ByteArrayInputStream(servedPatchedPlayerBytes)
                                )
                            }
                            // Block all external subresources so base.js runs purely offline in RAM.
                            return WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }
                    webView = this
                }
                wv.loadDataWithBaseURL(
                    "https://$VIRTUAL_HOST/",
                    html,
                    "text/html",
                    "utf-8",
                    null
                )
            } catch (t: Throwable) {
                initError = t.message ?: "webview_init_failed"
                Log.e(TAG, "Failed initializing native V8 WebView", t)
                latch.countDown()
            }
        }

        val done = try {
            latch.await(12, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        } finally {
            servedPatchedPlayerBytes = ByteArray(0)
            initLatch = null
        }
        return done && initOk
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

    private fun fetchPlayerJs(playerId: String): String? {
        val url = "https://www.youtube.com/s/player/$playerId/$PLAYER_VARIANT"
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
            Log.w(TAG, "Failed fetching $PLAYER_VARIANT ($playerId): ${e.message}")
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

    private fun urlDecode(s: String): String =
        try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun urlEncode(s: String): String =
        try { java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20") } catch (_: Exception) { s }
}
