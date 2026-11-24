package com.graball.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.graball.engine.WebViewCookieSync
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Fast, ad-free logged-in `m.youtube.com` stream extractor backed by a
 * pre-warmed hidden Android WebView sharing the user's `CookieManager`.
 *
 * Speed optimizations for VPN playback (~1-2s tap-to-play):
 * - [warmUp] pre-creates the WebView & Chromium renderer process as soon as
 *   YouTubeActivity opens (eliminating ~800ms cold WebView boot on first tap).
 * - Blocks all non-essential subresources (thumbnails, avatars, CSS, fonts,
 *   analytics, `/next` recommendations, and ads) in `shouldInterceptRequest`
 *   so only HTML + player JS + `/player` travel over the VPN.
 * - Hooks `HTMLMediaElement.prototype.src` + starts a fast 100ms watcher in
 *   `onPageStarted` (not waiting for `onPageFinished`) so the decrypted
 *   `googlevideo.com/videoplayback` URL is handed to ExoPlayer the instant
 *   YouTube's JS computes it.
 */
object YouTubeWebStreamExtractor {

    private const val TAG = "YouTubeWebExtractor"
    private const val EXTRACT_TIMEOUT_SEC = 12L
    private const val IDLE_UNLOAD_DELAY_MS = 20_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val extractLock = Any()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var activeUserAgent: String = ""

    @Volatile
    private var currentSession: ExtractSession? = null

    private val idleUnloadRunnable = Runnable {
        if (currentSession == null) {
            try {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
            } catch (_: Exception) {
            }
        }
    }

    private class ExtractSession(
        val videoId: String,
        val latch: CountDownLatch = CountDownLatch(1)
    ) {
        @Volatile var title: String = ""
        @Volatile var channel: String = ""
        @Volatile var durationSec: Long = 0L
        @Volatile var viewCount: String = ""
        @Volatile var description: String = ""
        @Volatile var thumbnailUrl: String = ""
        @Volatile var adShowing: Boolean = false
        @Volatile var hlsUrl: String? = null
        @Volatile var muxedUrl: String? = null
        @Volatile var videoUrl: String? = null
        @Volatile var videoMime: String = "video/mp4"
        @Volatile var videoHeight: Int = 0
        @Volatile var audioUrl: String? = null
        @Volatile var audioMime: String = "audio/mp4"
        @Volatile var streamingDataJson: String? = null
        @Volatile var finished: Boolean = false

        fun tryComplete() {
            if (finished) return
            val ready = !hlsUrl.isNullOrBlank() ||
                !muxedUrl.isNullOrBlank() ||
                (!videoUrl.isNullOrBlank() && !audioUrl.isNullOrBlank())
            if (ready) {
                finished = true
                latch.countDown()
            }
        }
    }

    private val jsBridge = object {
        @JavascriptInterface
        fun onMetadata(jsonStr: String) {
            val s = currentSession ?: return
            try {
                val obj = JSONObject(jsonStr)
                val vid = obj.optString("videoId")
                if (vid.isNotBlank() && vid != s.videoId) return
                val t = obj.optString("title")
                if (t.isNotBlank()) s.title = t
                val ch = obj.optString("author")
                if (ch.isNotBlank()) s.channel = ch
                val dur = obj.optLong("lengthSeconds", 0L)
                if (dur > 0L) s.durationSec = dur
                val vc = obj.optString("viewCount")
                if (vc.isNotBlank()) s.viewCount = vc
                val desc = obj.optString("shortDescription")
                if (desc.isNotBlank()) s.description = desc
                val thumb = obj.optString("thumbnail")
                if (thumb.startsWith("http")) s.thumbnailUrl = thumb
                val hls = obj.optString("hlsUrl")
                if (hls.startsWith("http")) {
                    s.hlsUrl = hls
                    s.tryComplete()
                }
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun onAdState(isAd: Boolean) {
            currentSession?.adShowing = isAd
        }

        @JavascriptInterface
        fun onStreamingData(jsonStr: String) {
            val s = currentSession ?: return
            if (jsonStr.length > 50) {
                s.streamingDataJson = jsonStr
            }
        }

        @JavascriptInterface
        fun onVideoSrc(url: String) {
            val s = currentSession ?: return
            if (s.adShowing) return
            handleCapturedMediaUrl(s, url, fromVideoElement = true)
        }
    }

    /**
     * Pre-boots the hidden WebView & Chromium renderer process in the background
     * so the first video tap skips ~600-1200ms of cold WebView creation.
     */
    fun warmUp(context: Context) {
        if (webView != null) return
        val appContext = context.applicationContext
        mainHandler.post {
            try {
                ensureWebViewOnMain(appContext)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Extracts playable stream URLs for [videoId] using the hidden logged-in
     * `m.youtube.com` WebView. Call off the main thread.
     */
    fun extract(context: Context, videoId: String): YouTubeInnerTube.ResolveResult {
        synchronized(extractLock) {
            val session = ExtractSession(videoId)
            currentSession = session
            mainHandler.removeCallbacks(idleUnloadRunnable)

            val started = startLoadOnMain(context.applicationContext, videoId)
            if (!started) {
                currentSession = null
                return YouTubeInnerTube.ResolveResult(null, "Could not start WebView extractor")
            }

            try {
                session.latch.await(EXTRACT_TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            } finally {
                // Immediately silence & halt network activity, then unload to
                // about:blank after a brief idle window so back-to-back taps stay hot.
                haltHiddenMedia()
                if (currentSession === session) {
                    currentSession = null
                }
                mainHandler.postDelayed(idleUnloadRunnable, IDLE_UNLOAD_DELAY_MS)
            }

            val videoStreams = mutableListOf<StreamFormat>()
            val audioStreams = mutableListOf<StreamFormat>()
            val muxedStreams = mutableListOf<StreamFormat>()

            session.videoUrl?.let { vUrl ->
                videoStreams.add(
                    StreamFormat(
                        itag = extractItag(vUrl),
                        url = vUrl,
                        mimeType = session.videoMime,
                        qualityLabel = if (session.videoHeight > 0) "${session.videoHeight}p" else "HD",
                        height = session.videoHeight.takeIf { it > 0 } ?: 720,
                        bitrate = 1_500_000,
                        isAudioOnly = false,
                        isVideoOnly = true,
                        isMuxed = false
                    )
                )
            }
            session.audioUrl?.let { aUrl ->
                audioStreams.add(
                    StreamFormat(
                        itag = extractItag(aUrl),
                        url = aUrl,
                        mimeType = session.audioMime,
                        qualityLabel = "audio",
                        height = 0,
                        bitrate = 128_000,
                        isAudioOnly = true,
                        isVideoOnly = false,
                        isMuxed = false
                    )
                )
            }
            session.muxedUrl?.let { mUrl ->
                muxedStreams.add(
                    StreamFormat(
                        itag = extractItag(mUrl).takeIf { it > 0 } ?: 18,
                        url = mUrl,
                        mimeType = "video/mp4",
                        qualityLabel = "360p",
                        height = 360,
                        bitrate = 600_000,
                        isAudioOnly = false,
                        isVideoOnly = false,
                        isMuxed = true
                    )
                )
            }

            val hls = session.hlsUrl
            val rawStreamingData = session.streamingDataJson
            var pendingRawStreamingData: String? = rawStreamingData

            // If V8 solver is already warm in memory, decrypt ALL adaptiveFormats
            // (144p-1080p+ video + M4A/Opus audio) right now in <10ms!
            if (!rawStreamingData.isNullOrBlank() && YouTubeJsSolver.isWarmInMemory()) {
                val decrypted = YouTubeInnerTube.decryptRawStreamingData(rawStreamingData)
                if (decrypted != null) {
                    if (decrypted.videoStreams.isNotEmpty()) {
                        videoStreams.clear()
                        videoStreams.addAll(decrypted.videoStreams)
                    }
                    if (decrypted.audioStreams.isNotEmpty()) {
                        audioStreams.clear()
                        audioStreams.addAll(decrypted.audioStreams)
                    }
                    if (decrypted.muxedStreams.isNotEmpty() && muxedStreams.isEmpty()) {
                        muxedStreams.addAll(decrypted.muxedStreams)
                    }
                    if (videoStreams.isNotEmpty() && audioStreams.isNotEmpty()) {
                        pendingRawStreamingData = null
                    }
                }
            }

            if (hls == null && muxedStreams.isEmpty() && (videoStreams.isEmpty() || audioStreams.isEmpty())) {
                Log.w(TAG, "Logged-in WebView extractor captured no playable URL for $videoId")
                return YouTubeInnerTube.ResolveResult(null, "WebView stream capture timed out")
            }

            val info = VideoStreamInfo(
                videoId = videoId,
                title = session.title.ifBlank { "YouTube Video" },
                channel = session.channel,
                durationSec = session.durationSec,
                viewCount = session.viewCount,
                description = session.description,
                thumbnailUrl = session.thumbnailUrl.ifBlank {
                    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                },
                hlsUrl = hls,
                videoStreams = videoStreams,
                audioStreams = audioStreams,
                muxedStreams = muxedStreams,
                userAgent = activeUserAgent.ifBlank {
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                },
                captionTracks = emptyList(),
                pendingStreamingDataJson = pendingRawStreamingData
            )
            Log.d(
                TAG,
                "Captured live logged-in WebView stream for $videoId " +
                    "(hls=${hls != null}, muxed=${muxedStreams.size}, v=${videoStreams.size}, a=${audioStreams.size}, pendingHd=${pendingRawStreamingData != null})"
            )
            return YouTubeInnerTube.ResolveResult(info)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebViewOnMain(appContext: Context): WebView {
        webView?.let { return it }
        val wv = WebView(appContext).apply {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)
            val cleanUa = WebViewCookieSync.cleanBrowserUserAgent(settings.userAgentString)
            activeUserAgent = cleanUa
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = cleanUa
            addJavascriptInterface(jsBridge, "GrabAllWebExt")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val uri = request.url
                    if (isBlockedNonEssentialRequest(uri)) {
                        return emptyResponse()
                    }
                    val urlStr = uri.toString()
                    val session = currentSession
                    if (session != null && isGoogleVideoMedia(uri, urlStr)) {
                        if (!session.finished && !session.adShowing) {
                            handleCapturedMediaUrl(session, urlStr, fromVideoElement = false)
                        }
                        // Never let the hidden WebView actually download video bytes!
                        return WebResourceResponse(
                            "application/octet-stream",
                            null,
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (url.startsWith("https://m.youtube.com/watch")) {
                        injectEarlyHookAndWatcher(view)
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (url.startsWith("https://m.youtube.com/watch")) {
                        injectEarlyHookAndWatcher(view)
                    }
                }
            }
        }
        webView = wv
        return wv
    }

    private fun startLoadOnMain(appContext: Context, videoId: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        mainHandler.post {
            try {
                val wv = ensureWebViewOnMain(appContext)
                wv.onResume()
                wv.resumeTimers()
                wv.stopLoading()
                wv.loadUrl("https://m.youtube.com/watch?v=$videoId")
                ok = true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed starting hidden m.youtube.com WebView", e)
            } finally {
                latch.countDown()
            }
        }
        return try {
            latch.await(6, TimeUnit.SECONDS) && ok
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun haltHiddenMedia() {
        mainHandler.post {
            try {
                val wv = webView ?: return@post
                wv.evaluateJavascript(
                    "(function(){try{if(window.__graballExtTimer){clearInterval(window.__graballExtTimer);window.__graballExtTimer=0;}var v=document.querySelector('video');if(v){v.pause();v.removeAttribute('src');v.load();}}catch(e){}})();",
                    null
                )
                wv.stopLoading()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Injected right at `onPageStarted` (and idempotently re-ensured on
     * `onPageFinished`):
     * 1. Disables MSE (`MediaSource`/`ManagedMediaSource`) so YouTube serves a
     *    clean progressive MP4 `<video>.src` instead of SABR/UMP.
     * 2. Hooks `HTMLMediaElement.prototype.src` setter so we capture the URL
     *    the exact microsecond YouTube's JS assigns it.
     * 3. Polls every 100ms for `ytInitialPlayerResponse` metadata + `<video>.src`
     *    and auto-triggers play without waiting for `onPageFinished`.
     */
    private fun injectEarlyHookAndWatcher(view: WebView) {
        val js = """
            (function() {
              try {
                Object.defineProperty(window, 'MediaSource', { get: function() { return undefined; }, configurable: true });
                Object.defineProperty(window, 'ManagedMediaSource', { get: function() { return undefined; }, configurable: true });
              } catch (e) {}

              if (!window.__graballSrcHooked) {
                window.__graballSrcHooked = true;
                try {
                  var desc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                  if (desc && desc.set && desc.get) {
                    Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                      configurable: true,
                      get: function() { return desc.get.call(this); },
                      set: function(val) {
                        try {
                          if (typeof val === 'string' && val.indexOf('googlevideo.com/videoplayback') !== -1) {
                            GrabAllWebExt.onVideoSrc(val);
                          }
                        } catch (e) {}
                        return desc.set.call(this, val);
                      }
                    });
                  }
                } catch (e) {}
              }

              if (window.__graballExtRunning) return;
              window.__graballExtRunning = true;
              var reportedMeta = false;

              function reportMeta() {
                if (reportedMeta) return;
                try {
                  var pr = window.ytInitialPlayerResponse;
                  if (pr && pr.videoDetails) {
                    if (pr.adPlacements) pr.adPlacements = [];
                    if (pr.playerAds) pr.playerAds = [];
                    var d = pr.videoDetails;
                    var thumbs = d.thumbnail && d.thumbnail.thumbnails;
                    var thumb = (thumbs && thumbs.length) ? thumbs[thumbs.length - 1].url : "";
                    var hls = (pr.streamingData && pr.streamingData.hlsManifestUrl) ? pr.streamingData.hlsManifestUrl : "";
                    reportedMeta = true;
                    if (pr.streamingData && (pr.streamingData.adaptiveFormats || pr.streamingData.formats)) {
                      try {
                        GrabAllWebExt.onStreamingData(JSON.stringify(pr.streamingData));
                      } catch (e2) {}
                    }
                    GrabAllWebExt.onMetadata(JSON.stringify({
                      videoId: d.videoId || "",
                      title: d.title || "",
                      author: d.author || "",
                      lengthSeconds: parseInt(d.lengthSeconds || "0", 10) || 0,
                      viewCount: d.viewCount || "",
                      shortDescription: d.shortDescription || "",
                      thumbnail: thumb,
                      hlsUrl: hls
                    }));
                  }
                } catch (e) {}
              }

              function tick() {
                try {
                  reportMeta();
                  var player = document.querySelector('.html5-video-player');
                  var isAd = !!(player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')));
                  GrabAllWebExt.onAdState(isAd);
                  var v = document.querySelector('video');
                  if (v) {
                    v.muted = true;
                    v.volume = 0;
                    if (isAd) {
                      var skip = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern');
                      if (skip) skip.click();
                      if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration;
                    } else {
                      var src = v.currentSrc || v.src || "";
                      if (src && src.indexOf('googlevideo.com/videoplayback') !== -1) {
                        GrabAllWebExt.onVideoSrc(src);
                        v.pause();
                        if (window.__graballExtTimer) {
                          clearInterval(window.__graballExtTimer);
                          window.__graballExtTimer = 0;
                        }
                        return;
                      }
                      if (v.paused) {
                        var playBtn = document.querySelector('.ytp-large-play-button, button[aria-label="Play"]');
                        if (playBtn) playBtn.click();
                        var p = v.play();
                        if (p && p.catch) p.catch(function(){});
                      }
                    }
                  }
                } catch (e) {}
              }

              tick();
              window.__graballExtTimer = setInterval(tick, 100);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun handleCapturedMediaUrl(
        session: ExtractSession,
        rawUrl: String,
        fromVideoElement: Boolean
    ) {
        if (session.finished) return
        if (!rawUrl.startsWith("http")) return
        // Reject UMP / SABR binary container URLs (ExoPlayer needs plain MP4/WebM/HLS)
        if (rawUrl.contains("ump=1") || rawUrl.contains("srfvp=1")) return
        // Reject obvious ad stream markers
        if (rawUrl.contains("ctier=L") || rawUrl.contains("&oad=") || rawUrl.contains("?oad=")) return

        // Duration sanity check: if we know the video duration (> 35s) and the
        // stream's `dur=` is wildly shorter (e.g. a 15s pre-roll ad), skip it!
        val urlDur = extractDur(rawUrl)
        if (session.durationSec > 35L && urlDur > 0.0 && urlDur < session.durationSec - 15.0) {
            return
        }

        val cleanUrl = stripStreamingRangeParams(rawUrl)
        val mime = extractMime(cleanUrl).lowercase()
        val itag = extractItag(cleanUrl)

        when {
            cleanUrl.contains(".m3u8") || cleanUrl.contains("/hls_variant/") -> {
                session.hlsUrl = cleanUrl
            }
            fromVideoElement || itag == 18 || itag == 22 || itag == 37 || itag == 38 -> {
                session.muxedUrl = cleanUrl
            }
            mime.startsWith("audio/") || itag in setOf(139, 140, 141, 249, 250, 251) -> {
                session.audioUrl = cleanUrl
                session.audioMime = mime.ifBlank { "audio/mp4" }
            }
            mime.startsWith("video/") -> {
                session.videoUrl = cleanUrl
                session.videoMime = mime
                session.videoHeight = guessHeightFromItag(itag)
            }
        }
        session.tryComplete()
    }

    private fun isGoogleVideoMedia(uri: Uri, urlStr: String): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (!host.endsWith(".googlevideo.com")) return false
        val path = uri.path ?: return false
        return path.contains("/videoplayback") || path.contains("/hls_variant/") || urlStr.contains(".m3u8")
    }

    /**
     * Blocks ads, telemetry, images, avatars, stylesheets, fonts, and
     * secondary recommendations so the hidden WebView only fetches the watch
     * HTML, YouTube player JS, and stream metadata over VPN.
     */
    private fun isBlockedNonEssentialRequest(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (host.endsWith(".googlevideo.com")) return false

        if (host.contains("doubleclick.net") ||
            host.contains("googlesyndication.com") ||
            host.contains("googleadservices.com") ||
            host.startsWith("adservice.google.") ||
            host == "ads.youtube.com" ||
            host == "s0.2mdn.net" ||
            host == "i.ytimg.com" ||
            host == "i9.ytimg.com" ||
            host.endsWith(".ggpht.com") ||
            host.endsWith(".googleusercontent.com") ||
            host == "fonts.googleapis.com" ||
            host == "fonts.gstatic.com" ||
            host == "play.google.com"
        ) {
            return true
        }

        val path = uri.path?.lowercase().orEmpty()
        if (path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".png") ||
            path.endsWith(".webp") ||
            path.endsWith(".gif") ||
            path.endsWith(".ico") ||
            path.endsWith(".svg") ||
            path.endsWith(".woff") ||
            path.endsWith(".woff2") ||
            path.endsWith(".ttf") ||
            path.endsWith(".css")
        ) {
            return true
        }

        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            if (path.contains("/pagead/") ||
                path.contains("/api/stats/") ||
                path.contains("/ptracking") ||
                path.contains("/get_midroll_info") ||
                path.contains("/generate_204") ||
                path.contains("/youtubei/v1/log_event") ||
                path.contains("/youtubei/v1/next") ||
                path.contains("/youtubei/v1/browse") ||
                path.contains("/youtubei/v1/guide") ||
                path.contains("/youtubei/v1/account/")
            ) {
                return true
            }
        }
        return false
    }

    private fun emptyResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        ByteArrayInputStream(ByteArray(0))
    )

    private fun stripStreamingRangeParams(url: String): String {
        return url
            .replace(Regex("""([?&])range=[^&]*&?"""), "$1")
            .replace(Regex("""([?&])rn=[^&]*&?"""), "$1")
            .replace(Regex("""([?&])rbuf=[^&]*&?"""), "$1")
            .replace("?&", "?")
            .trimEnd('&', '?')
    }

    private fun extractItag(url: String): Int =
        Regex("""[?&]itag=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun extractMime(url: String): String {
        val raw = Regex("""[?&]mime=([^&]+)""").find(url)?.groupValues?.get(1) ?: return ""
        return try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
    }

    private fun extractDur(url: String): Double =
        Regex("""[?&]dur=([0-9.]+)""").find(url)?.groupValues?.get(1)?.toDoubleOrNull() ?: -1.0

    private fun guessHeightFromItag(itag: Int): Int = when (itag) {
        160, 278, 394 -> 144
        133, 242, 395 -> 240
        134, 243, 396, 18 -> 360
        135, 244, 397 -> 480
        136, 247, 298, 398, 22 -> 720
        137, 248, 299, 399 -> 1080
        else -> 720
    }
}
