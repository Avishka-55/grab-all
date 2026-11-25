package com.graball.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.graball.engine.WebViewCookieSync
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device YouTube Proof-of-Origin (PO) Token minter backed by a persistent
 * hidden Android WebView.
 *
 * Loads a lightweight YouTube embed page, intercepts `/s/player/.../base.js`
 * to hook YouTube's built-in BotGuard PO-token generator (`pot_cms`/`pot_csms`),
 * and mints genuine PO tokens bound to a video ID or visitorData in ~0.5-2s.
 *
 * Includes a failure cooldown breaker and LRU token cache so failed attempts
 * never slow down playback or downloads.
 */
object YouTubePoTokenProvider {

    private const val TAG = "YouTubePoToken"
    private const val BUILD_TIMEOUT_SEC = 10L
    private const val MINT_TIMEOUT_SEC = 6L
    private const val MIN_VALID_POT_LEN = 40
    private const val MAX_CONSECUTIVE_FAILURES = 3
    private const val BREAKER_COOLDOWN_MS = 10L * 60L * 1000L
    private const val SESSION_TTL_MS = 4L * 60L * 60L * 1000L

    data class PoTokenResult(
        val token: String,
        val binding: String
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val buildLock = Any()
    private val tokenCache = LruCache<String, PoTokenResult>(24)

    private val potLineRegex = Regex("""^(?=.*pot_cms)(?=.*pot_csms).*$""", RegexOption.MULTILINE)

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var readyAtMs: Long = 0L

    @Volatile
    private var hookReady: Boolean = false

    @Volatile
    private var buildLatch: CountDownLatch? = null

    @Volatile
    private var consecutiveFailures: Int = 0

    @Volatile
    private var breakerUntilMs: Long = 0L

    private val mintLatches = ConcurrentHashMap<String, CountDownLatch>()
    private val mintResults = ConcurrentHashMap<String, String>()

    private val jsBridge = object {
        @JavascriptInterface
        fun onPotEvent(event: String, detail: String) {
            if (event == "ready") {
                hookReady = true
                readyAtMs = System.currentTimeMillis()
                buildLatch?.countDown()
            } else if (event == "called") {
                val len = detail.toIntOrNull() ?: 0
                if (len >= MIN_VALID_POT_LEN) {
                    hookReady = true
                    readyAtMs = System.currentTimeMillis()
                    buildLatch?.countDown()
                }
            }
        }

        @JavascriptInterface
        fun onMintResult(reqId: String, token: String) {
            mintResults[reqId] = token
            mintLatches[reqId]?.countDown()
        }
    }

    /** Resets cached generator & breaker state (e.g. after login/logout). */
    fun invalidate() {
        synchronized(buildLock) {
            tokenCache.evictAll()
            consecutiveFailures = 0
            breakerUntilMs = 0L
            hookReady = false
            readyAtMs = 0L
            destroyWebViewOnMain()
        }
    }

    /**
     * Mints or returns a cached PO Token bound to [binding] (videoId or visitorData).
     * Call off the main thread. Returns null quickly if unavailable or circuit-broken.
     */
    fun mintToken(context: Context, videoId: String, binding: String): PoTokenResult? {
        if (binding.isBlank()) return null
        tokenCache.get(binding)?.let { return it }

        val now = System.currentTimeMillis()
        if (now < breakerUntilMs) return null

        if (!ensureGeneratorReady(context.applicationContext, videoId)) {
            recordFailure()
            return null
        }

        val minted = requestMint(binding)
        if (minted != null && minted.length >= MIN_VALID_POT_LEN) {
            consecutiveFailures = 0
            val res = PoTokenResult(token = minted, binding = binding)
            tokenCache.put(binding, res)
            Log.d(TAG, "Minted PO token for binding len=${binding.length} -> potLen=${minted.length}")
            return res
        }

        recordFailure()
        return null
    }

    private fun recordFailure() {
        val next = ++consecutiveFailures
        if (next >= MAX_CONSECUTIVE_FAILURES) {
            breakerUntilMs = System.currentTimeMillis() + BREAKER_COOLDOWN_MS
            Log.w(TAG, "PO token breaker tripped for 10m after $next consecutive failures")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureGeneratorReady(appContext: Context, seedVideoId: String): Boolean {
        val now = System.currentTimeMillis()
        if (hookReady && webView != null && (now - readyAtMs) < SESSION_TTL_MS) {
            return true
        }

        synchronized(buildLock) {
            val now2 = System.currentTimeMillis()
            if (hookReady && webView != null && (now2 - readyAtMs) < SESSION_TTL_MS) {
                return true
            }
            hookReady = false
            val latch = CountDownLatch(1)
            buildLatch = latch

            mainHandler.post {
                try {
                    destroyWebViewNow()
                    val wv = WebView(appContext).apply {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = false
                        settings.blockNetworkImage = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            settings.safeBrowsingEnabled = false
                        }
                        settings.userAgentString = WebViewCookieSync.cleanBrowserUserAgent(settings.userAgentString)
                        addJavascriptInterface(jsBridge, "GrabAllPotBridge")
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                val urlStr = request.url.toString()
                                if (urlStr.contains("/s/player/") && urlStr.contains("/base.js")) {
                                    return fetchAndPatchBaseJs(urlStr)
                                }
                                // Block media/images/ads so embed loads fast & silent
                                val host = request.url.host?.lowercase().orEmpty()
                                if (host.endsWith(".googlevideo.com") ||
                                    host == "i.ytimg.com" ||
                                    host.endsWith(".ggpht.com") ||
                                    host.contains("doubleclick.net") ||
                                    host.contains("googlesyndication.com")
                                ) {
                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                    }
                    webView = wv
                    wv.loadUrl("https://www.youtube.com/embed/$seedVideoId")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed creating PO token WebView: ${t.message}")
                    latch.countDown()
                }
            }

            val ok = try {
                latch.await(BUILD_TIMEOUT_SEC, TimeUnit.SECONDS) && hookReady
            } catch (_: InterruptedException) {
                false
            } finally {
                buildLatch = null
            }

            if (!ok) {
                Log.w(TAG, "PO token generator timed out or failed to hook base.js")
                destroyWebViewOnMain()
            }
            return ok
        }
    }

    private fun requestMint(binding: String): String? {
        val wv = webView ?: return null
        val reqId = UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        mintLatches[reqId] = latch

        val js = """
            (function() {
              var id = ${JSONObject.quote(reqId)};
              var b = ${JSONObject.quote(binding)};
              try {
                if (typeof window.__graballMintPot === 'function') {
                  var res = window.__graballMintPot(b);
                  if (res && typeof res.then === 'function') {
                    res.then(function(tok) {
                      GrabAllPotBridge.onMintResult(id, String(tok || ''));
                    }).catch(function() {
                      GrabAllPotBridge.onMintResult(id, '');
                    });
                  } else {
                    GrabAllPotBridge.onMintResult(id, String(res || ''));
                  }
                } else {
                  GrabAllPotBridge.onMintResult(id, '');
                }
              } catch (e) {
                GrabAllPotBridge.onMintResult(id, '');
              }
            })();
        """.trimIndent()

        mainHandler.post {
            try {
                wv.evaluateJavascript(js, null)
            } catch (_: Throwable) {
                latch.countDown()
            }
        }

        return try {
            val done = latch.await(MINT_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (done) mintResults.remove(reqId)?.takeIf { it.isNotBlank() } else null
        } catch (_: InterruptedException) {
            null
        } finally {
            mintLatches.remove(reqId)
            mintResults.remove(reqId)
        }
    }

    private fun fetchAndPatchBaseJs(url: String): WebResourceResponse? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", YouTubeSessionConfig.DESKTOP_WEB_UA)
                .build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            } ?: return null

            val patched = patchPotGeneratorInBaseJs(body) ?: return null
            WebResourceResponse(
                "application/javascript",
                "utf-8",
                200,
                "OK",
                mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Cache-Control" to "no-store"
                ),
                ByteArrayInputStream(patched.toByteArray(Charsets.UTF_8))
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed fetching/patching base.js for PO token: ${e.message}")
            null
        }
    }

    /**
     * Locates YouTube's internal PO-token minting assignment in `base.js`
     * (the function body referencing both `pot_cms` and `pot_csms`), captures
     * its first context argument on invocation, and exposes `window.__graballMintPot(binding)`.
     */
    private fun patchPotGeneratorInBaseJs(js: String): String? {
        val match = potLineRegex.find(js) ?: return null
        val start = match.range.first
        var depth = 0
        var seenBrace = false
        var end = -1
        for (i in start until js.length) {
            when (js[i]) {
                '{' -> {
                    depth++
                    seenBrace = true
                }
                '}' -> {
                    depth--
                    if (seenBrace && depth == 0) {
                        end = i + 1
                        break
                    }
                }
            }
        }
        if (!seenBrace || end <= start) return null

        val fnBlock = js.substring(start, end)
        val openBrace = fnBlock.indexOf('{')
        val assignEq = fnBlock.indexOf('=')
        if (openBrace <= 0 || assignEq <= 0 || assignEq > openBrace) return null

        val fnName = fnBlock.substring(0, assignEq).trim()
        val withCtxCapture = fnBlock.substring(0, openBrace + 1) +
            "window.__graballPotCtx=arguments[0];" +
            fnBlock.substring(openBrace + 1)

        val hookCode = buildString {
            append(withCtxCapture)
            append(";window.__graballOrigPot=").append(fnName).append(";")
            append(fnName).append("=function(a,b){var p=window.__graballOrigPot(a,b);try{GrabAllPotBridge.onPotEvent('called',String(p?p.length:0));}catch(e){}return p;};")
            append("window.__graballMintPot=function(b){return window.__graballOrigPot(window.__graballPotCtx,b);};")
            append("try{GrabAllPotBridge.onPotEvent('ready','');}catch(e){};")
        }

        return js.substring(0, start) + hookCode + js.substring(end)
    }

    private fun destroyWebViewOnMain() {
        mainHandler.post { destroyWebViewNow() }
    }

    private fun destroyWebViewNow() {
        val old = webView
        webView = null
        if (old != null) {
            try {
                old.stopLoading()
                old.loadUrl("about:blank")
                old.destroy()
            } catch (_: Throwable) {
            }
        }
    }
}
