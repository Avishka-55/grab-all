package com.graball.youtube

import android.annotation.SuppressLint
import android.content.Context
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
 * Real logged-in `m.youtube.com` stream extractor using a hidden Android
 * WebView that shares the user's authenticated Google/YouTube `CookieManager`.
 *
 * Why this is bulletproof on VPN:
 * - Uses YouTube's OWN official mobile webpage & JS engine (real login cookies,
 *   real PO token, real `sig` + `n` decryption — zero `yt-dlp-ejs`, zero Python,
 *   zero QuickJS, zero manual `SAPISIDHASH` computation).
 * - Blocks YouTube ad endpoints in `shouldInterceptRequest` and disables MSE
 *   (`window.MediaSource = undefined`) so YouTube's player bypasses SABR/UMP
 *   and sets a clean, directly playable `googlevideo.com/videoplayback` MP4 URL
 *   on `<video>.src` (and/or issues clean non-UMP adaptive video+audio GETs).
 * - Immediately unloads the hidden WebView (`about:blank`) the moment the real
 *   stream URL is captured (~1.5-2.5s) and hands the 100% ad-free stream to
 *   GrabAll's native ExoPlayer (with SponsorBlock, PiP, Screen-Off Audio, and
 *   unthrottled 2 MB chunked playback) and FastYouTubeDownloader.
 */
object YouTubeWebStreamExtractor {

    private const val TAG = "YouTubeWebExtractor"
    private const val EXTRACT_TIMEOUT_SEC = 14L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val extractLock = Any()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var activeUserAgent: String = ""

    @Volatile
    private var currentSession: ExtractSession? = null

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
        fun onVideoSrc(url: String) {
            val s = currentSession ?: return
            if (s.adShowing) return
            handleCapturedMediaUrl(s, url, fromVideoElement = true)
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

            val started = startLoadOnMain(context.applicationContext, videoId)
            if (!started) {
                currentSession = null
                return YouTubeInnerTube.ResolveResult(null, "Could not start WebView extractor")
            }

            try {
                session.latch.await(EXTRACT_TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            } finally {
                // Stop loading / media immediately so hidden WebView uses zero bandwidth & CPU
                stopHiddenWebView()
                if (currentSession === session) {
                    currentSession = null
                }
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
                captionTracks = emptyList()
            )
            Log.d(
                TAG,
                "Captured live logged-in WebView stream for $videoId " +
                    "(hls=${hls != null}, muxed=${muxedStreams.size}, v=${videoStreams.size}, a=${audioStreams.size})"
            )
            return YouTubeInnerTube.ResolveResult(info)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startLoadOnMain(appContext: Context, videoId: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        mainHandler.post {
            try {
                var wv = webView
                if (wv == null) {
                    wv = WebView(appContext).apply {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        val cleanUa = WebViewCookieSync.cleanBrowserUserAgent(settings.userAgentString)
                        activeUserAgent = cleanUa
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = cleanUa
                        addJavascriptInterface(jsBridge, "GrabAllWebExt")
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                val uri = request.url
                                if (isAdOrTelemetryRequest(uri)) {
                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                                val urlStr = uri.toString()
                                val session = currentSession
                                if (session != null && !session.finished && isGoogleVideoMedia(uri, urlStr)) {
                                    // Don't intercept if page reports an ad is currently showing
                                    if (!session.adShowing) {
                                        handleCapturedMediaUrl(session, urlStr, fromVideoElement = false)
                                    }
                                    // Return empty response so hidden WebView doesn't waste data playing it!
                                    if (session.finished) {
                                        return WebResourceResponse(
                                            "application/octet-stream",
                                            null,
                                            ByteArrayInputStream(ByteArray(0))
                                        )
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                injectBootstrapHook(view)
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                injectExtractorWatcher(view)
                            }
                        }
                    }
                    webView = wv
                }
                wv.onResume()
                wv.resumeTimers()
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

    private fun stopHiddenWebView() {
        mainHandler.post {
            try {
                val wv = webView ?: return@post
                wv.stopLoading()
                wv.loadUrl("about:blank")
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Disables MSE (`window.MediaSource = undefined`) early so m.youtube.com
     * falls back from SABR/UMP to a direct progressive MP4 `video.src` URL
     * decrypted by YouTube's own official player JS with the user's real login!
     */
    private fun injectBootstrapHook(view: WebView) {
        val js = """
            (function() {
              try {
                Object.defineProperty(window, 'MediaSource', { get: function() { return undefined; }, configurable: true });
                Object.defineProperty(window, 'ManagedMediaSource', { get: function() { return undefined; }, configurable: true });
              } catch (e) {}
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    /**
     * Reads `ytInitialPlayerResponse` metadata, strips ad slots, mutes video,
     * auto-starts playback, and forwards `<video>.src` to Kotlin the instant
     * YouTube's JS assigns the decrypted `googlevideo.com` URL.
     */
    private fun injectExtractorWatcher(view: WebView) {
        val js = """
            (function() {
              if (window.__graballExtRunning) return;
              window.__graballExtRunning = true;
              function reportMeta() {
                try {
                  var pr = window.ytInitialPlayerResponse;
                  if (pr && pr.videoDetails) {
                    if (pr.adPlacements) pr.adPlacements = [];
                    if (pr.playerAds) pr.playerAds = [];
                    var d = pr.videoDetails;
                    var thumbs = d.thumbnail && d.thumbnail.thumbnails;
                    var thumb = (thumbs && thumbs.length) ? thumbs[thumbs.length - 1].url : "";
                    var hls = (pr.streamingData && pr.streamingData.hlsManifestUrl) ? pr.streamingData.hlsManifestUrl : "";
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
              setInterval(tick, 250);
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

        // Duration sanity check: if we know the video duration (> 30s) and the
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

    private fun isAdOrTelemetryRequest(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (host.contains("doubleclick.net") ||
            host.contains("googlesyndication.com") ||
            host.contains("googleadservices.com") ||
            host.startsWith("adservice.google.") ||
            host == "ads.youtube.com" ||
            host == "s0.2mdn.net"
        ) {
            return true
        }
        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            val path = uri.path?.lowercase().orEmpty()
            if (path.contains("/pagead/") ||
                path.contains("/api/stats/ads") ||
                path.contains("/ptracking") ||
                path.contains("/get_midroll_info")
            ) {
                return true
            }
        }
        return false
    }

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
