package com.graball.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lightweight native WebView BotGuard PO Token (`&pot=...`) provider.
 *
 * Lazily initialized ONLY when a GVS PO-token-enforcing client (`WEB_CREATOR`,
 * `WEB_REMIX`, `WEB_SAFARI`) is needed (e.g., on cloud VPNs where `VISIONOS`
 * is bot-blocked and `TV_SAMSUNG` receives poisoned HTTP-500 honeypot itags),
 * so off-VPN playback incurs zero WebView overhead.
 *
 * Uses YouTube's standard Waa/BotGuard protocol:
 * 1. `POST /api/jnn/v1/Create` -> BotGuard VM JS + program
 * 2. Execute VM inside an offline hidden WebView (`blockNetworkLoads = true`)
 * 3. `POST /api/jnn/v1/GenerateIT` -> session integrity token (valid ~6-12h)
 * 4. Mint GVS PO tokens in RAM (< 20 ms) bound to `dataSyncId` / `visitorData` / `videoId`.
 */
object YouTubePoTokenProvider {

    private const val TAG = "YouTubePoTokenProvider"
    private const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
    private const val GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
    private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
    private const val JNN_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
    private const val MIN_TTL_SEC = 600L
    private const val MAX_TTL_SEC = 43_200L
    private const val SAFETY_MARGIN_SEC = 300L
    private const val MAX_CONSECUTIVE_FAILURES = 3
    private const val FAILURE_COOLDOWN_MS = 3L * 60L * 1000L

    private val PROTO_JSON_MEDIA = "application/json+protobuf; charset=utf-8".toMediaType()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initLock = Any()
    private val mintLock = Any()

    private data class CachedToken(
        val token: String,
        val expiresAtElapsedMs: Long
    )

    private data class ChallengeData(
        val interpreterJs: String,
        val program: String,
        val globalName: String
    )

    private data class JsCallResult(
        val ok: Boolean,
        val value: String,
        val error: String
    )

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var integrityExpiresAtElapsedMs: Long = 0L

    @Volatile
    private var consecutiveFailures: Int = 0

    @Volatile
    private var cooldownUntilElapsedMs: Long = 0L

    @Volatile
    var lastError: String = ""
        private set

    private val tokenCache = ConcurrentHashMap<String, CachedToken>()
    private val challengePayloads = ConcurrentHashMap<String, String>()
    private val callLatches = ConcurrentHashMap<String, CountDownLatch>()
    private val callResults = ConcurrentHashMap<String, JsCallResult>()

    @Volatile
    private var pageReadyLatch: CountDownLatch? = null

    private val jsBridge = object {
        @JavascriptInterface
        fun onPageReady() {
            pageReadyLatch?.countDown()
        }

        @JavascriptInterface
        fun getChallengeJson(reqId: String): String =
            challengePayloads.remove(reqId) ?: "{}"

        @JavascriptInterface
        fun onBgInit(reqId: String, ok: Boolean, bgResponse: String, error: String) {
            callResults[reqId] = JsCallResult(ok, bgResponse, error)
            callLatches[reqId]?.countDown()
        }

        @JavascriptInterface
        fun onMintDone(reqId: String, ok: Boolean, potB64: String, error: String) {
            callResults[reqId] = JsCallResult(ok, potB64, error)
            callLatches[reqId]?.countDown()
        }
    }

    fun isReadyInMemory(): Boolean =
        webView != null && SystemClock.elapsedRealtime() < integrityExpiresAtElapsedMs

    /** Clears cached tokens & tears down the hidden WebView (e.g. on VPN switch or 403 retry). */
    fun invalidate() {
        tokenCache.clear()
        consecutiveFailures = 0
        cooldownUntilElapsedMs = 0L
        synchronized(initLock) {
            integrityExpiresAtElapsedMs = 0L
            val existing = webView
            webView = null
            if (existing != null) {
                mainHandler.post { destroyWebViewSafely(existing) }
            }
        }
    }

    /**
     * Mints (or returns a cached) GVS PO Token bound to [identifier]
     * (typically `dataSyncId` when signed in, `visitorData` when guest, or `videoId`).
     * Must be called off the main thread.
     */
    fun mintPoToken(
        context: Context,
        identifier: String,
        forceRefresh: Boolean = false
    ): String? {
        val cleanId = identifier.trim()
        if (cleanId.isEmpty()) return null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "mintPoToken called on main thread; skipping")
            return null
        }

        val now = SystemClock.elapsedRealtime()
        if (!forceRefresh) {
            val cached = tokenCache[cleanId]
            if (cached != null && now < cached.expiresAtElapsedMs) {
                return cached.token
            }
        } else {
            tokenCache.remove(cleanId)
        }

        if (!forceRefresh && now < cooldownUntilElapsedMs) {
            return null
        }

        if (!ensureIntegrityReady(context.applicationContext, forceRefresh)) {
            return null
        }

        val minted = synchronized(mintLock) {
            val recheck = tokenCache[cleanId]
            if (!forceRefresh && recheck != null && SystemClock.elapsedRealtime() < recheck.expiresAtElapsedMs) {
                return@synchronized recheck.token
            }
            val wv = webView ?: return@synchronized null
            mintInsideWebView(wv, cleanId)
        }

        if (minted != null && minted.length > 16) {
            consecutiveFailures = 0
            cooldownUntilElapsedMs = 0L
            val expiry = integrityExpiresAtElapsedMs.coerceAtLeast(SystemClock.elapsedRealtime() + 60_000L)
            if (tokenCache.size > 48) {
                tokenCache.clear()
            }
            tokenCache[cleanId] = CachedToken(minted, expiry)
            return minted
        }

        recordFailure(lastError.ifBlank { "mint_failed" })
        return null
    }

    private fun ensureIntegrityReady(appContext: Context, forceRefresh: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!forceRefresh && webView != null && now < integrityExpiresAtElapsedMs) {
            return true
        }

        synchronized(initLock) {
            val now2 = SystemClock.elapsedRealtime()
            if (!forceRefresh && webView != null && now2 < integrityExpiresAtElapsedMs) {
                return true
            }

            // Tear down any previous WebView so the BotGuard VM starts clean.
            webView?.let { old ->
                webView = null
                mainHandler.post { destroyWebViewSafely(old) }
            }
            integrityExpiresAtElapsedMs = 0L

            val challenge = fetchChallenge() ?: run {
                recordFailure(lastError.ifBlank { "create_challenge_failed" })
                return false
            }

            val wv = createBootstrappedWebView(appContext) ?: run {
                recordFailure(lastError.ifBlank { "webview_bootstrap_failed" })
                return false
            }

            val bgResp = runBotGuardInit(wv, challenge) ?: run {
                mainHandler.post { destroyWebViewSafely(wv) }
                recordFailure(lastError.ifBlank { "botguard_init_failed" })
                return false
            }

            val integrity = fetchIntegrityToken(bgResp) ?: run {
                mainHandler.post { destroyWebViewSafely(wv) }
                recordFailure(lastError.ifBlank { "generate_it_failed" })
                return false
            }

            val (integrityStdB64, ttlSec) = integrity
            val setOk = setIntegrityInWebView(wv, integrityStdB64)
            if (!setOk) {
                mainHandler.post { destroyWebViewSafely(wv) }
                recordFailure("set_integrity_failed")
                return false
            }

            val effectiveTtlSec = (ttlSec - SAFETY_MARGIN_SEC).coerceIn(MIN_TTL_SEC, MAX_TTL_SEC)
            webView = wv
            integrityExpiresAtElapsedMs = SystemClock.elapsedRealtime() + effectiveTtlSec * 1000L
            consecutiveFailures = 0
            cooldownUntilElapsedMs = 0L
            lastError = ""
            Log.d(TAG, "BotGuard integrity ready (ttl=${effectiveTtlSec}s)")
            return true
        }
    }

    private fun recordFailure(reason: String) {
        lastError = reason
        val count = ++consecutiveFailures
        if (count >= MAX_CONSECUTIVE_FAILURES) {
            cooldownUntilElapsedMs = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
        }
        Log.w(TAG, "PO token failure #$count: $reason")
    }

    private fun postJnn(url: String, jsonBody: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", YouTubeSessionConfig.DESKTOP_WEB_UA)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json+protobuf")
                .header("x-goog-api-key", JNN_API_KEY)
                .header("x-user-agent", "grpc-web-javascript/0.1")
                .post(jsonBody.toRequestBody(PROTO_JSON_MEDIA))
                .build()
            YouTubeSessionConfig.anonymousOkHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    lastError = "jnn_http_${resp.code}"
                    return null
                }
                resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            lastError = "jnn_net_${e.javaClass.simpleName}"
            null
        }
    }

    private fun fetchChallenge(): ChallengeData? {
        val raw = postJnn(CREATE_URL, "[\"$REQUEST_KEY\"]") ?: return null
        return try {
            val top = JSONArray(raw)
            val inner: JSONArray = if (top.length() > 1 && top.opt(1) is String) {
                val encoded = top.getString(1)
                val decoded = decodeUrlSafeBase64(encoded) ?: run {
                    lastError = "challenge_b64_decode_failed"
                    return null
                }
                val shifted = ByteArray(decoded.size) { idx -> (decoded[idx] + 97).toByte() }
                JSONArray(String(shifted, Charsets.UTF_8))
            } else {
                top.optJSONArray(0) ?: return null
            }

            var interpreterJs = findFirstNonBlankString(inner.optJSONArray(1))
            if (interpreterJs.isNullOrBlank()) {
                val scriptUrl = findFirstNonBlankString(inner.optJSONArray(2))
                if (!scriptUrl.isNullOrBlank() && scriptUrl.startsWith("http")) {
                    interpreterJs = fetchExternalScript(scriptUrl)
                }
            }
            val program = inner.optString(4)
            val globalName = inner.optString(5)
            if (interpreterJs.isNullOrBlank() || program.isBlank() || globalName.isBlank()) {
                lastError = "challenge_fields_incomplete"
                return null
            }
            ChallengeData(interpreterJs, program, globalName)
        } catch (e: Exception) {
            lastError = "challenge_parse_${e.javaClass.simpleName}"
            null
        }
    }

    private fun fetchExternalScript(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", YouTubeSessionConfig.DESKTOP_WEB_UA)
                .get()
                .build()
            YouTubeSessionConfig.anonymousOkHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchIntegrityToken(bgResponse: String): Pair<String, Long>? {
        val body = JSONArray().apply {
            put(REQUEST_KEY)
            put(bgResponse)
        }.toString()
        val raw = postJnn(GENERATE_IT_URL, body) ?: return null
        return try {
            val arr = JSONArray(raw)
            val tokenRaw = arr.optString(0)
            val ttlSec = arr.optLong(1, 21_600L)
            if (tokenRaw.isBlank()) {
                lastError = "integrity_token_empty"
                return null
            }
            val bytes = decodeUrlSafeBase64(tokenRaw) ?: run {
                lastError = "integrity_b64_decode_failed"
                return null
            }
            val stdB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            Pair(stdB64, ttlSec)
        } catch (e: Exception) {
            lastError = "integrity_parse_${e.javaClass.simpleName}"
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBootstrappedWebView(appContext: Context): WebView? {
        val readyLatch = CountDownLatch(1)
        pageReadyLatch = readyLatch
        var created: WebView? = null
        var createErr: String? = null

        mainHandler.post {
            try {
                val wv = WebView(appContext).apply {
                    settings.javaScriptEnabled = true
                    settings.blockNetworkLoads = true
                    settings.userAgentString = YouTubeSessionConfig.DESKTOP_WEB_UA
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        settings.safeBrowsingEnabled = false
                    }
                    addJavascriptInterface(jsBridge, "GrabAllPotBridge")
                }
                created = wv
                wv.loadDataWithBaseURL(
                    "https://www.youtube.com/",
                    BOOTSTRAP_HTML,
                    "text/html",
                    "utf-8",
                    null
                )
            } catch (t: Throwable) {
                createErr = t.message ?: t.javaClass.simpleName
                readyLatch.countDown()
            }
        }

        val ok = try {
            readyLatch.await(6, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        } finally {
            pageReadyLatch = null
        }

        val wv = created
        if (!ok || wv == null || createErr != null) {
            lastError = createErr ?: "webview_ready_timeout"
            if (wv != null) {
                mainHandler.post { destroyWebViewSafely(wv) }
            }
            return null
        }
        return wv
    }

    private fun runBotGuardInit(wv: WebView, challenge: ChallengeData): String? {
        val reqId = UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("interpreterJs", challenge.interpreterJs)
            put("program", challenge.program)
            put("globalName", challenge.globalName)
        }.toString()

        val latch = CountDownLatch(1)
        challengePayloads[reqId] = payload
        callLatches[reqId] = latch

        mainHandler.post {
            try {
                wv.evaluateJavascript("__initBg(${JSONObject.quote(reqId)});", null)
            } catch (e: Exception) {
                callResults[reqId] = JsCallResult(false, "", e.message ?: "eval_err")
                latch.countDown()
            }
        }

        val finished = try {
            latch.await(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        } finally {
            challengePayloads.remove(reqId)
            callLatches.remove(reqId)
        }

        val res = callResults.remove(reqId)
        if (!finished || res == null || !res.ok || res.value.isBlank()) {
            lastError = res?.error?.ifBlank { "bg_init_empty" } ?: "bg_init_timeout"
            return null
        }
        return res.value
    }

    private fun setIntegrityInWebView(wv: WebView, integrityStdB64: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        mainHandler.post {
            try {
                wv.evaluateJavascript("__setIntegrity(${JSONObject.quote(integrityStdB64)});") {
                    ok = true
                    latch.countDown()
                }
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        return try {
            latch.await(3, TimeUnit.SECONDS) && ok
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun mintInsideWebView(wv: WebView, identifier: String): String? {
        val reqId = UUID.randomUUID().toString()
        val idB64 = Base64.encodeToString(identifier.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val latch = CountDownLatch(1)
        callLatches[reqId] = latch

        mainHandler.post {
            try {
                wv.evaluateJavascript(
                    "__mintPot(${JSONObject.quote(reqId)}, ${JSONObject.quote(idB64)});",
                    null
                )
            } catch (e: Exception) {
                callResults[reqId] = JsCallResult(false, "", e.message ?: "mint_eval_err")
                latch.countDown()
            }
        }

        val finished = try {
            latch.await(6, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        } finally {
            callLatches.remove(reqId)
        }

        val res = callResults.remove(reqId)
        if (!finished || res == null || !res.ok || res.value.isBlank()) {
            lastError = res?.error?.ifBlank { "mint_empty" } ?: "mint_timeout"
            return null
        }
        return res.value
            .replace('+', '-')
            .replace('/', '_')
    }

    private fun destroyWebViewSafely(wv: WebView) {
        try {
            wv.removeJavascriptInterface("GrabAllPotBridge")
            wv.stopLoading()
            wv.clearHistory()
            wv.loadUrl("about:blank")
            wv.onPause()
            wv.removeAllViews()
            wv.destroy()
        } catch (_: Throwable) {
        }
    }

    private fun findFirstNonBlankString(arr: JSONArray?): String? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v is String && v.isNotBlank()) return v
        }
        return null
    }

    private fun decodeUrlSafeBase64(input: String): ByteArray? {
        return try {
            val cleaned = input.trim()
                .replace('-', '+')
                .replace('_', '/')
                .replace('.', '=')
            val padded = when (cleaned.length % 4) {
                2 -> "$cleaned=="
                3 -> "$cleaned="
                else -> cleaned
            }
            Base64.decode(padded, Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }

    private val BOOTSTRAP_HTML = """
        <!doctype html>
        <html lang="en">
        <head>
        <script>
        window.__bgState = {
          globalName: null,
          program: null,
          signalOut: null,
          signalUsed: false,
          integrityU8: null
        };
        function __b64ToU8(b64) {
          var bin = atob(b64);
          var out = new Uint8Array(bin.length);
          for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
          return out;
        }
        function __u8ToB64(u8) {
          var s = '';
          for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
          return btoa(s);
        }
        function __runVmSnapshot(onSuccess, onError) {
          try {
            var st = window.__bgState;
            var vm = window[st.globalName];
            if (!vm || typeof vm.a !== 'function') {
              onError('vm_missing_' + st.globalName);
              return;
            }
            var asyncSnap = null;
            vm.a(
              st.program,
              function(fn) { asyncSnap = fn; },
              true,
              undefined,
              function() {},
              [[], []]
            );
            var tries = 0;
            var timer = setInterval(function() {
              if (typeof asyncSnap === 'function') {
                clearInterval(timer);
                try {
                  var signalOut = [];
                  asyncSnap(function(bgResp) {
                    onSuccess(signalOut, String(bgResp || ''));
                  }, [undefined, undefined, signalOut, undefined]);
                } catch (e2) {
                  onError('snap_err:' + String(e2));
                }
              } else if (++tries >= 1600) {
                clearInterval(timer);
                onError('async_snap_timeout');
              }
            }, 5);
          } catch (e) {
            onError(String(e));
          }
        }
        function __initBg(reqId) {
          try {
            var cfg = JSON.parse(GrabAllPotBridge.getChallengeJson(reqId));
            if (!cfg || !cfg.interpreterJs || !cfg.program || !cfg.globalName) {
              GrabAllPotBridge.onBgInit(reqId, false, '', 'missing_challenge_fields');
              return;
            }
            (new Function(cfg.interpreterJs))();
            window.__bgState.globalName = cfg.globalName;
            window.__bgState.program = cfg.program;
            window.__bgState.signalOut = null;
            window.__bgState.signalUsed = false;
            __runVmSnapshot(
              function(signalOut, bgResp) {
                window.__bgState.signalOut = signalOut;
                window.__bgState.signalUsed = false;
                GrabAllPotBridge.onBgInit(reqId, true, bgResp, '');
              },
              function(err) {
                GrabAllPotBridge.onBgInit(reqId, false, '', err);
              }
            );
          } catch (e) {
            GrabAllPotBridge.onBgInit(reqId, false, '', String(e));
          }
        }
        function __setIntegrity(b64) {
          window.__bgState.integrityU8 = __b64ToU8(b64);
        }
        function __finishMint(reqId, signalOut, idB64) {
          try {
            var st = window.__bgState;
            var factory = signalOut && signalOut[0];
            if (typeof factory !== 'function') {
              GrabAllPotBridge.onMintDone(reqId, false, '', 'signal_factory_missing');
              return;
            }
            var minter = factory(st.integrityU8);
            if (typeof minter !== 'function') {
              GrabAllPotBridge.onMintDone(reqId, false, '', 'minter_not_function');
              return;
            }
            var potU8 = minter(__b64ToU8(idB64));
            if (!potU8 || !(potU8 instanceof Uint8Array) || potU8.length === 0) {
              GrabAllPotBridge.onMintDone(reqId, false, '', 'empty_pot_bytes');
              return;
            }
            GrabAllPotBridge.onMintDone(reqId, true, __u8ToB64(potU8), '');
          } catch (e) {
            GrabAllPotBridge.onMintDone(reqId, false, '', 'mint_err:' + String(e));
          }
        }
        function __mintPot(reqId, idB64) {
          try {
            var st = window.__bgState;
            if (!st || !st.integrityU8 || !st.program) {
              GrabAllPotBridge.onMintDone(reqId, false, '', 'bg_not_ready');
              return;
            }
            if (st.signalOut && !st.signalUsed) {
              st.signalUsed = true;
              __finishMint(reqId, st.signalOut, idB64);
              return;
            }
            __runVmSnapshot(
              function(freshSignalOut) {
                __finishMint(reqId, freshSignalOut, idB64);
              },
              function(err) {
                GrabAllPotBridge.onMintDone(reqId, false, '', 'refresh_' + err);
              }
            );
          } catch (e) {
            GrabAllPotBridge.onMintDone(reqId, false, '', String(e));
          }
        }
        GrabAllPotBridge.onPageReady();
        </script>
        </head>
        <body></body>
        </html>
    """.trimIndent()
}
