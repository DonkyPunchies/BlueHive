package com.example.bluehive.webview.vidapi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import androidx.core.net.toUri
import com.example.bluehive.BuildConfig
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VidApiStreamExtractorWebView — headless m3u8 extractor for HLS embed pages.
 *
 * Host-agnostic (currently drives the VidApi / vaplayer.ru embed; originally
 * written for cinesrc.st). Referer/Origin are derived from the captured m3u8's
 * OWN origin, so it isn't tied to any single site and adapts to rotating CDN
 * domains.
 *
 * FLOW (mirrors the Miruro extractor, minus dub/server/episode choreography):
 *   1. Load the embed URL (e.g. https://vaplayer.ru/embed/movie/{id}) headless.
 *   2. The embed auto-loads/probes its player and starts hls.js — we do NOT open
 *      any picker. We just nudge play (best-effort) and wait.
 *   3. Capture the first playlist (the .m3u8) via shouldInterceptRequest AND a
 *      fetch/XHR JS hook (belt + suspenders). Segments are disguised as
 *      .jpg/.mp4 so we ONLY trust the ".m3u8" master here.
 *   4. Opportunistically capture a subtitle URL (format=srt / .vtt) to sideload.
 *   5. Hand the m3u8 (+ subtitle + Referer headers) back, tear the WebView down.
 *
 * Every log uses tag "scraper-vidapi" — filter Logcat by `scraper-vidapi`.
 */
class VidApiStreamExtractorWebView private constructor() {

    interface ExtractionListener {
        /** MAIN thread. master m3u8 + optional subtitle + headers ExoPlayer should send. */
        fun onM3u8Found(m3u8Url: String, subtitleUrl: String?, headers: Map<String, String>)
        /** MAIN thread. Failure or timeout, with a human-readable reason. */
        fun onExtractionFailed(reason: String)
        /** MAIN thread. Optional progress text. */
        fun onStatusUpdate(status: String) {}
    }

    companion object {
        private const val TAG = "scraper-vidapi"

        // Diagnostic switch: dumps every non-ad request the headless page makes so
        // we can see which .m3u8 we grab (master vs index) and whether a subtitle
        // request auto-fires. Works in RELEASE builds (not gated on DEBUG). Filter
        // Logcat by tag `scraper-vidapi` + text `REQ`. Flip to false when done.
        private const val VERBOSE_REQUESTS = false

        private const val TIMEOUT_MS         = 60_000L   // server auto-probe can be slow
        private const val FIRST_CLICK_DELAY  = 2_000L
        private const val CLICK_INTERVAL     = 1_500L
        private const val MAX_CLICK_ATTEMPTS = 20         // more than Miruro — probing takes time

        private const val FALLBACK_ORIGIN  = "https://cinesrc.st"  // ultimate fallback origin only
        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** Process-wide single-flight guard. Plain boolean — holds no Context. */
        @Volatile private var busy = false

        fun extract(context: Context, url: String, listener: ExtractionListener) {
            if (busy) {
                Log.w(TAG, "⚠️ extraction already running — ignoring duplicate call")
                listener.onExtractionFailed("Extraction already in progress")
                return
            }
            busy = true
            try {
                VidApiStreamExtractorWebView().start(context.applicationContext, url, listener)
            } catch (e: Exception) {
                Log.e(TAG, "❌ start() threw before extraction began", e)
                busy = false
                listener.onExtractionFailed("Extraction failed to start: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val delivered = AtomicBoolean(false)
    private val playStarted = AtomicBoolean(false)
    private val cleanedUp = AtomicBoolean(false)

    private var webView: WebView? = null
    private var listener: ExtractionListener? = null
    private var timeoutRunnable: Runnable? = null
    private var clickRunnable: Runnable? = null
    private var clickAttempts = 0
    private var requestCount = 0
    private var startTimeMs = 0L
    @Volatile private var active = false
    @Volatile private var subtitleUrl: String? = null   // best-effort, captured if seen pre-delivery

    private fun start(appContext: Context, url: String, listener: ExtractionListener) {
        this.listener = listener
        active = true
        startTimeMs = System.currentTimeMillis()

        Log.d(TAG, "════════════ VIDAPI EXTRACT START ════════════")
        Log.d(TAG, "  url    : $url")
        Log.d(TAG, "  timeout: ${TIMEOUT_MS / 1000}s")

        try {
            createWebView(appContext, url)
        } catch (e: Exception) {
            Log.e(TAG, "❌ WebView creation failed", e)
            failAndCleanup("Player engine failed to start: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(appContext: Context, url: String) {
        val wv = WebView(appContext)
        webView = wv

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        // Hard state reset — the process is reused, and stale cookies/SW push the
        // 2nd+ load down a different stalling path. Start byte-identical every time.
        try {
            CookieManager.getInstance().apply { removeAllCookies(null); flush() }
            WebStorage.getInstance().deleteAllData()
            ServiceWorkerController.getInstance().setServiceWorkerClient(
                object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest) = null
                }
            )
            Log.d(TAG, "🧼 wiped cookies + web storage + neutralised service worker")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ state wipe failed (non-fatal): ${e.message}")
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = CHROME_USER_AGENT
            allowContentAccess = true
            allowFileAccess = false
            blockNetworkImage = true                       // posters only; XHR segments unaffected
            loadWithOverviewMode = true
            useWideViewPort = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        Log.d(TAG, "✅ headless WebView configured")

        wv.addJavascriptInterface(JsBridge(), "VidApiBridge")
        wv.webViewClient = ExtractorWebViewClient()
        wv.webChromeClient = ExtractorChromeClient()

        timeoutRunnable = Runnable {
            if (active && !delivered.get()) {
                val elapsed = System.currentTimeMillis() - startTimeMs
                Log.e(TAG, "⏰ timed out after ${elapsed}ms — no master.m3u8 " +
                        "($requestCount requests, $clickAttempts play attempts)")
                failAndCleanup("No playable stream found — the source failed or timed out.")
            }
        }
        handler.postDelayed(timeoutRunnable!!, TIMEOUT_MS)

        Log.d(TAG, "🚀 loading $url")
        listener?.onStatusUpdate("Loading source…")
        wv.clearCache(true)
        wv.loadUrl(url)
    }

    private inner class ExtractorWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            Log.d(TAG, "▶️ page STARTED: ${truncate(url)} [${elapsed()}ms]")
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true)
                Log.e(TAG, "❌ MAIN-FRAME error ${error?.errorCode} ${error?.description} @ ${request.url}")
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            if (request?.isForMainFrame == true)
                Log.w(TAG, "⚠️ MAIN-FRAME http ${errorResponse?.statusCode} @ ${request.url}")
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val reqUrl = request?.url?.toString() ?: return null
            requestCount++
            logRequest(reqUrl)   // diagnostic dump (VERBOSE_REQUESTS)

            // Capture subtitle sightings (best-effort) — don't deliver on these.
            if (isSubtitleUrl(reqUrl) && subtitleUrl == null) {
                subtitleUrl = reqUrl
                Log.d(TAG, "💬 subtitle seen via intercept [#$requestCount]: ${truncate(reqUrl)}")
            }

            if (isM3u8Url(reqUrl)) {
                Log.d(TAG, "🎯 m3u8 seen via network intercept [#$requestCount]: $reqUrl")
                deliver(reqUrl, buildHeaders(request), "network-intercept")
                return null   // observe only
            }

            if (isAdDomain(reqUrl)) {
                Log.d(TAG, "🛑 blocked ad/beacon: ${truncate(reqUrl)}")
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            Log.d(TAG, "✅ page loaded: ${truncate(url)} [${elapsed()}ms, $requestCount reqs]")
            if (!playStarted.compareAndSet(false, true)) {
                Log.d(TAG, "↩️ page finished again — play sequence already started, ignoring")
                return
            }
            injectNetworkHook(view)
            startPlaySequence(view)
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            Log.w(TAG, "⚠️ SSL error on ${error?.url} — cancelling")
            handler?.cancel()
        }
    }

    private inner class ExtractorChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            Log.d(TAG, "📊 progress $newProgress% [${elapsed()}ms]")
        }
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            Log.d(TAG, "🚫 blocked popup window")
            return false
        }
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            consoleMessage?.message()?.let { if (it.startsWith("scraper-vidapi:")) Log.d(TAG, "   JS → $it") }
            return true
        }
    }

    // ── Best-effort play nudge. The embed usually autoplays once it resolves
    //    (mediaPlaybackRequiresUserGesture=false). For same-origin players this
    //    also pokes the button; for a cross-origin iframe player we can't reach
    //    in, but the network intercept still catches its master.m3u8. ──────────
    private fun startPlaySequence(view: WebView?) {
        clickRunnable = object : Runnable {
            override fun run() {
                if (!active || delivered.get()) return
                if (clickAttempts >= MAX_CLICK_ATTEMPTS) {
                    Log.w(TAG, "⚠️ exhausted $MAX_CLICK_ATTEMPTS play attempts (waiting on server probe)")
                    return
                }
                clickAttempts++
                clickPlay(view, clickAttempts)
                handler.postDelayed(this, CLICK_INTERVAL)
            }
        }
        Log.d(TAG, "▶️ scheduling first play nudge in ${FIRST_CLICK_DELAY}ms")
        listener?.onStatusUpdate("Finding the best server…")
        handler.postDelayed(clickRunnable!!, FIRST_CLICK_DELAY)
    }

    private fun clickPlay(view: WebView?, attempt: Int) {
        if (view == null || delivered.get()) return
        val js = """
        (function() {
            var sels = ['button[aria-label="Play"]','.jw-icon-display','.jw-icon-playback',
                        '.vjs-big-play-button','.plyr__control--overlaid','button.vds-play-button',
                        '.vds-play-button','[data-plyr="play"]','.play-button','.btn-play'];
            for (var i=0;i<sels.length;i++){
                var b=document.querySelector(sels[i]);
                if(b){
                    ['pointerdown','pointerup','click'].forEach(function(ev){
                        try{ b.dispatchEvent(new MouseEvent(ev,{bubbles:true,cancelable:true,view:window})); }catch(e){}
                    });
                    try{ b.click(); }catch(e){}
                    console.log('scraper-vidapi: clicked '+sels[i]);
                    return 'clicked:'+sels[i];
                }
            }
            var v=document.querySelector('video');
            if(v){ try{ v.muted=true; var p=v.play(); if(p&&p.catch)p.catch(function(){}); }catch(e){}
                   console.log('scraper-vidapi: video.play()'); return 'video'; }
            console.log('scraper-vidapi: no play target yet');
            return 'notfound';
        })();
        """.trimIndent()

        view.evaluateJavascript(js) { raw ->
            val r = raw?.trim()?.removeSurrounding("\"") ?: "?"
            if (r == "notfound") Log.d(TAG, "▶️ play attempt #$attempt: nothing to click yet [${elapsed()}ms]")
            else                 Log.d(TAG, "▶️ play attempt #$attempt: $r [${elapsed()}ms]")
        }
    }

    // ── fetch/XHR hook — backup capture for manifests the native intercept misses,
    //    plus subtitle sightings. ──────────────────────────────────────────────
    private fun injectNetworkHook(view: WebView?) {
        if (view == null || delivered.get()) return
        Log.d(TAG, "🪝 injecting fetch/XHR hooks")
        val js = """
        (function() {
            if (window.__vidapiHook) return;
            window.__vidapiHook = true;
            function report(u){
                if (!u) return;
                if (u.indexOf('.m3u8') !== -1) { try { VidApiBridge.onM3u8(u); } catch(e){} }
                else if (u.indexOf('format=srt') !== -1 || u.indexOf('.srt') !== -1 || u.indexOf('.vtt') !== -1) {
                    try { VidApiBridge.onSubtitle(u); } catch(e){}
                }
            }
            var of = window.fetch;
            window.fetch = function(i, init){
                var u = (typeof i === 'string') ? i : (i && i.url) ? i.url : '';
                report(u);
                return of.apply(this, arguments).then(function(r){ report(r.url || u); return r; });
            };
            var oo = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(m, u){ this.__u = u; report(u); return oo.apply(this, arguments); };
            var os = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function(){
                var s=this;
                this.addEventListener('load', function(){ report(s.responseURL || s.__u || ''); });
                return os.apply(this, arguments);
            };
            console.log('scraper-vidapi: fetch/XHR hooks installed');
        })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private inner class JsBridge {
        @Keep @JavascriptInterface
        fun onM3u8(url: String) {
            Log.d(TAG, "🎯 m3u8 seen via JS hook: $url")
            handler.post { deliver(url, originHeaders(url), "js-hook") }
        }
        @Keep @JavascriptInterface
        fun onSubtitle(url: String) {
            if (subtitleUrl == null) {
                subtitleUrl = url
                Log.d(TAG, "💬 subtitle seen via JS hook: ${truncate(url)}")
            }
        }
    }

    private fun deliver(url: String, headers: Map<String, String>, via: String) {
        if (!isM3u8Url(url)) return
        if (!delivered.compareAndSet(false, true)) return
        Log.d(TAG, "════════════ M3U8 CAPTURED ($via) ════════════")
        Log.d(TAG, "  url      : $url")
        Log.d(TAG, "  referer  : ${headers["Referer"]}")
        Log.d(TAG, "  subtitle : ${subtitleUrl ?: "none"}")
        Log.d(TAG, "  elapsed  : ${elapsed()}ms after $clickAttempts play attempts")
        val sub = subtitleUrl
        handler.post {
            try {
                listener?.onStatusUpdate("Stream found — starting player…")
                listener?.onM3u8Found(url, sub, headers)
            } catch (e: Exception) {
                Log.e(TAG, "❌ onM3u8Found threw — cleaning up anyway", e)
            } finally {
                cleanup()
            }
        }
    }

    private fun buildHeaders(request: WebResourceRequest): Map<String, String> {
        val h = mutableMapOf<String, String>()
        request.requestHeaders?.forEach { (k, v) -> h[k] = v }
        // Fall back to the m3u8's OWN origin (not a hardcoded site) so a rotating
        // embed CDN gets a self-referer it accepts. Real request headers, when
        // WebView supplies them, still win via putIfAbsent.
        val self = originHeaders(request.url.toString())
        h.putIfAbsent("Referer", self.getValue("Referer"))
        h.putIfAbsent("Origin", self.getValue("Origin"))
        h.putIfAbsent("User-Agent", CHROME_USER_AGENT)
        return h
    }

    /** Referer/Origin/UA derived from a URL's own origin. For rotating embed CDNs
     *  a self-referer is the most widely accepted default; falls back to the
     *  FALLBACK_ORIGIN only if the URL can't be parsed. */
    private fun originHeaders(url: String): Map<String, String> {
        val uri = try { url.toUri() } catch (_: Exception) { null }
        val scheme = uri?.scheme
        val host = uri?.host
        val origin = if (scheme != null && host != null) "$scheme://$host" else FALLBACK_ORIGIN
        return mapOf(
            "Referer" to "$origin/",
            "Origin" to origin,
            "User-Agent" to CHROME_USER_AGENT
        )
    }

    private fun isM3u8Url(url: String?): Boolean {
        if (url == null) return false
        if (!url.lowercase().contains(".m3u8")) return false
        val host = try { url.toUri().host ?: "" } catch (_: Exception) { "" }
        val adHosts = listOf("doubleclick","googlesyndication","adservice","adserver",
            "popads","exoclick","trafficjunky","adexchangerapid")
        return adHosts.none { host.contains(it, ignoreCase = true) }
    }

    private fun isSubtitleUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("format=srt") || u.contains(".srt") || u.contains(".vtt") ||
                (u.contains("subs.") && u.contains(".online"))
    }

    private fun isAdDomain(url: String): Boolean {
        val host = try { url.toUri().host ?: "" } catch (_: Exception) { "" }
        val patterns = listOf("doubleclick","googlesyndication","googleadservices","analytics",
            "popads","popcash","propellerads","adsterra","exoclick","juicyads","trafficjunky",
            "adexchangerapid","usrpubtrk","/beacon","cloudflareinsights")
        return patterns.any { host.contains(it, true) || url.contains(it, true) }
    }

    // ── Diagnostic request dump ─────────────────────────────────────────────
    // Every .m3u8 is logged (master-vs-index is what we're chasing); ad/tracker
    // hosts are dropped; subtitle/API-looking requests are flagged. All lines
    // carry the "REQ" prefix so Logcat filter `scraper-vidapi` + `REQ` shows a
    // clean, numbered request list. Toggle with VERBOSE_REQUESTS.
    private fun logRequest(url: String) {
        if (!VERBOSE_REQUESTS) return
        val u = url.lowercase()
        if (u.contains(".m3u8")) { Log.d(TAG, "REQ 🎬 M3U8   #$requestCount  $url"); return }
        if (isAdDomain(url)) return                       // drop ad/tracker noise
        val kind = when {
            u.contains(".vtt") || u.contains(".srt") || u.contains("subtitle") ||
                u.contains("caption") || u.contains("/subs") || u.contains("lang=") -> "💬 SUB? "
            u.contains(".ts")  || u.contains("segment")  || u.contains(".php")       -> "📦 API? "
            else                                                                      -> "🌐 other"
        }
        Log.d(TAG, "REQ $kind  #$requestCount  $url")
    }

    private fun elapsed() = System.currentTimeMillis() - startTimeMs
    private fun truncate(url: String?, max: Int = 200): String =
        if (url == null) "null" else if (url.length > max) url.take(max) + "…" else url

    private fun failAndCleanup(reason: String) {
        handler.post {
            listener?.onStatusUpdate("Extraction failed")
            listener?.onExtractionFailed(reason)
        }
        cleanup()
    }

    private fun cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) {
            Log.d(TAG, "↩️ cleanup already ran — ignoring")
            return
        }
        Log.d(TAG, "🧹 cleanup — $requestCount reqs, $clickAttempts play attempts, " +
                "found=${delivered.get()}, ${elapsed()}ms")
        active = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        clickRunnable?.let { handler.removeCallbacks(it) }
        handler.post {
            try {
                webView?.apply {
                    stopLoading(); loadUrl("about:blank"); clearHistory(); clearCache(true)
                    removeJavascriptInterface("VidApiBridge"); webChromeClient = null
                    removeAllViews(); destroy()
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ cleanup error: ${e.message}")
            } finally {
                webView = null; listener = null; timeoutRunnable = null; clickRunnable = null
                busy = false
                Log.d(TAG, "✅ extractor cleaned up")
            }
        }
    }
}