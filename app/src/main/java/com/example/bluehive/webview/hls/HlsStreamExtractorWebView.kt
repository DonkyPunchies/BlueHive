package com.example.bluehive.webview.hls

import android.annotation.SuppressLint
import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
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
 * HlsStreamExtractorWebView — headless m3u8 extractor for HLS embed pages.
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
class HlsStreamExtractorWebView private constructor() {

    interface ExtractionListener {
        /** MAIN thread. master m3u8 + optional subtitle + headers ExoPlayer should send. */
        fun onM3u8Found(m3u8Url: String, subtitleUrl: String?, headers: Map<String, String>)
        /** MAIN thread. Failure or timeout, with a human-readable reason. */
        fun onExtractionFailed(reason: String)

        /**
         * The NETWORK refused the request — an ISP/router content filter, not a
         * dead source. Defaults to [onExtractionFailed] so existing callers keep
         * working; override to skip the retry, which cannot help here.
         */
        fun onExtractionBlocked(reason: String) = onExtractionFailed(reason)
        /** MAIN thread. Optional progress text. */
        fun onStatusUpdate(status: String) {}
    }

    companion object {
        private const val TAG = "scraper-vidapi"

        // Diagnostic switch: dumps every non-ad request the headless page makes so
        // we can see which .m3u8 we grab (master vs index) and whether a subtitle
        // request auto-fires. Works in RELEASE builds (not gated on DEBUG). Filter
        // Logcat by tag `scraper-vidapi` + text `REQ`. Flip to false when done.
        // TEMPORARY — on to capture VidFast's request chain for the server-side
        // extractor. Revert to false once captured.
        private const val VERBOSE_REQUESTS = true

        private const val TIMEOUT_MS         = 60_000L   // server auto-probe can be slow
        private const val FIRST_CLICK_DELAY  = 2_000L
        private const val CLICK_INTERVAL     = 1_500L
        private const val MAX_CLICK_ATTEMPTS = 20         // more than Miruro — probing takes time

        // ── Subtitle hunt (opt-in per call) ──────────────────────────────────
        // VidFast attaches ~180 EMPTY TextTracks to the <video> up front (english,
        // english 2, english 3, bengali, …) with cues=null, and there are no
        // <track> elements at all. Nothing is fetched until a language is
        // SELECTED — selecting is what makes the site GET its .vtt. Verified live
        // in a browser console: the .vtt appears in the network log only after the
        // click. So the only way to obtain it is to drive their menu.
        //
        // The CC button is found by its SVG icon geometry, NOT by class: the MUI
        // class names are build hashes (three different buttons shared mui-79elbk,
        // and the same button reported different classes in two page states),
        // whereas the icon path only changes if they redraw the icon.
        private const val CC_ICON_PATH_PREFIX = "M2 12C2 8.22876"
        /**
         * How long to wait for the .vtt before giving up and playing without it.
         *
         * Has to cover the injected script's own polling: the language list is NOT
         * present 900ms after the CC click (measured on the box — 'no-english-entry'),
         * so the script polls for ~7s with one menu-reopen halfway, and this budget
         * has to outlast that plus the fetch. It is a worst case, not a typical
         * cost — a successful hunt exits as soon as the .vtt is seen.
         */
        // ── Network-filter detection ─────────────────────────────────────────
        //
        // MEASURED 2026-07-31: VidFast stopped extracting entirely — the page
        // reported "100% loaded" in 1.3s, made ONE request, and never produced a
        // <video>, so every run burned the full 60s timeout twice and reported a
        // generic "source failed". It wasn't the source. Spectrum's CUJO filter
        // had started blocking the domain:
        //
        //   $ curl -I http://vidfast.vc/tv/60625/5/2
        //   HTTP/1.1 302 Found
        //   Location: https://block.charter-prod.hosted.cujo.io/warn.html?url=...
        //
        // Over HTTPS the filter can't redirect, so it kills the TLS handshake
        // instead — which is why the WebView just got nothing. Detecting this
        // turns two minutes of silence into an instant, accurate message.
        /** Hosts an ISP/router filter redirects to when it blocks a domain. */
        private val BLOCK_PAGE_HOSTS = listOf(
            "cujo.io",             // Charter / Spectrum "Security Shield" (confirmed)
            "blockpage",
            "blocked.",
            "block.",
            "warn.html",
        )
        /** Main-frame failures that mean "the connection was refused/severed". */
        private val NETWORK_BLOCK_ERROR_CODES = setOf(
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_PROXY_AUTHENTICATION,
        )

        private const val SUBTITLE_HUNT_MS = 9_000L
        private const val SUBTITLE_POLL_MS = 400L

        private const val FALLBACK_ORIGIN  = "https://cinesrc.st"  // ultimate fallback origin only
        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** Process-wide single-flight guard. Plain boolean — holds no Context. */
        @Volatile private var busy = false

        /**
         * @param huntSubtitles after the m3u8 is captured, drive the site's CC menu
         *   so it fetches its English .vtt, and return that too. Costs up to
         *   SUBTITLE_HUNT_MS extra before the player opens, so it's opt-in — only
         *   callers whose site actually works this way (VidFast) pass true.
         */
        fun extract(
            context: Context,
            url: String,
            listener: ExtractionListener,
            huntSubtitles: Boolean = false,
        ) {
            if (busy) {
                Log.w(TAG, "⚠️ extraction already running — ignoring duplicate call")
                listener.onExtractionFailed("Extraction already in progress")
                return
            }
            busy = true
            try {
                HlsStreamExtractorWebView().start(context, url, listener, huntSubtitles)
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
    // Final document URL when the m3u8 was captured - the real Referer the page used.
    @Volatile private var lastPageUrl: String? = null
    /** Set once a network filter is identified — see [reportNetworkBlock]. */
    @Volatile private var networkBlocked = false
    /** Set by the caller; see extract(huntSubtitles). */
    private var huntSubtitles = false
    /** Non-null only while the WebView is attached to a real window (hunt mode). */
    private var hostActivity: Activity? = null

    /**
     * @param context the CALLER's context. When [huntSubtitles] is on and this is an
     *   Activity, the WebView is attached to its window (invisibly) - see
     *   createWebView for why that is required.
     */
    private fun start(
        context: Context,
        url: String,
        listener: ExtractionListener,
        huntSubtitles: Boolean = false,
    ) {
        this.huntSubtitles = huntSubtitles
        this.hostActivity = context as? Activity
        val appContext = context.applicationContext
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
        // Attach to a real window when hunting subtitles.
        //
        // Normally this WebView is never added to any view hierarchy - it is driven
        // purely through loadUrl + JS. hls.js, fetch/XHR and video decoding don't
        // care, which is why plain extraction works detached. But a detached WebView
        // has a 0x0 layout viewport and gets no animation frames, and MUI opens its
        // Subtitles dialog through a transition that positions against the anchor's
        // bounding rect and completes on rAF. With zero-size rects and no frames the
        // dialog never mounts - which is exactly the '0 menu items' we measured.
        //
        // So in hunt mode we host it in the caller Activity's window at full size but
        // fully transparent: real layout, real frames, nothing visible. Removed again
        // in cleanup(). The normal (non-hunt) path is untouched and stays detached.
        val host = hostActivity.takeIf { huntSubtitles }
        val wv = WebView(host ?: appContext)
        webView = wv
        if (host != null) {
            try {
                wv.alpha = 0f
                host.addContentView(
                    wv,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                Log.d(TAG, "🧷 WebView attached to host window (invisible) for subtitle hunt")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ could not attach WebView (${e.message}) - hunt will likely fail")
            }
        }

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
            if (url != null && isBlockPageUrl(url)) {
                reportNetworkBlock(hostOf(lastPageUrl) ?: "the source", "redirected to $url")
                return
            }
            // Remember where we ended up (the embed redirects); its ORIGIN is the
            // Referer the extracted CDN validates against - see deliver().
            if (url != null) lastPageUrl = url
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame != true) return
            Log.e(TAG, "❌ MAIN-FRAME error ${error?.errorCode} ${error?.description} @ ${request.url}")
            // The page itself couldn't be reached at all. On this box that has
            // meant a filter sitting in front of the connection, not a dead site.
            if (error?.errorCode in NETWORK_BLOCK_ERROR_CODES) {
                reportNetworkBlock(
                    request.url?.host ?: "the source",
                    "main-frame ${error?.errorCode} ${error?.description}"
                )
            }
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
            // Only the PAGE's own host counts. Ad and beacon domains throw SSL
            // errors constantly during runs that go on to succeed, so treating
            // any of them as a block would abort perfectly good extractions.
            val badHost = hostOf(error?.url)
            if (badHost != null && badHost == hostOf(lastPageUrl)) {
                reportNetworkBlock(badHost, "TLS handshake refused for the page itself")
            }
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
        /** Progress/diagnostics from the subtitle hunt's injected script. */
        @Keep @JavascriptInterface
        fun onHunt(msg: String) { Log.d(TAG, "💬 hunt: $msg") }

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
        // Attach the document URL under a private pseudo-header. Additive: callers
        // that don't look for it (VidApi) are unaffected; VidFast reads it to use
        // the page-origin referer its CDN actually validates against.
        val outHeaders = headers.toMutableMap()
        lastPageUrl?.let { outHeaders["X-Page-Url"] = it }
        Log.d(TAG, "════════════ M3U8 CAPTURED ($via) ════════════")
        Log.d(TAG, "  url      : $url")
        Log.d(TAG, "  referer  : ${outHeaders["Referer"]}")
        Log.d(TAG, "  pageUrl  : ${lastPageUrl ?: "unknown"}")
        Log.d(TAG, "  subtitle : ${subtitleUrl ?: "none"}")
        Log.d(TAG, "  elapsed  : ${elapsed()}ms after $clickAttempts play attempts")
        // Hold the WebView open just long enough to make the site fetch its
        // subtitles, then finish. Skipped entirely when not requested, or when a
        // .vtt already flew past on its own.
        if (huntSubtitles && subtitleUrl == null) {
            handler.post { runSubtitleHunt { finishDelivery(url, outHeaders) } }
        } else {
            handler.post { finishDelivery(url, outHeaders) }
        }
    }

    /** Hand the stream (and subtitle, if we got one) to the caller and tear down. */
    private fun finishDelivery(url: String, outHeaders: Map<String, String>) {
        val sub = subtitleUrl
        try {
            listener?.onStatusUpdate("Stream found — starting player…")
            listener?.onM3u8Found(url, sub, outHeaders)
        } catch (e: Exception) {
            Log.e(TAG, "❌ onM3u8Found threw — cleaning up anyway", e)
        } finally {
            cleanup()
        }
    }

    /**
     * Click the site's CC button, then its first "english" entry, and wait for the
     * resulting .vtt request to be seen by the interceptor / JS hook.
     *
     * Bounded by SUBTITLE_HUNT_MS and ALWAYS calls [onDone] — a missing subtitle
     * must never cost the user their episode, so every failure path here just
     * shrugs and plays the stream.
     */
    private fun runSubtitleHunt(onDone: () -> Unit) {
        val view = webView
        if (view == null) { onDone(); return }
        Log.d(TAG, "💬 subtitle hunt: opening CC menu…")
        listener?.onStatusUpdate("Fetching subtitles…")

        val js = """
        (function(){
          try {
            // SILENCE THE PAGE FIRST. The extractor nudges the site's <video> into
            // playing to force the stream out, and that audio goes to the speakers.
            // It used to be inaudible because the WebView was destroyed the instant
            // the m3u8 was captured; holding it open for the hunt made the show
            // start playing "in the background" before the real player opened.
            Array.prototype.slice.call(document.querySelectorAll('video,audio'))
              .forEach(function(m){ try { m.muted = true; m.volume = 0; } catch(e){} });

            // FORCE A DESKTOP-WIDTH VIEWPORT.
            //
            // Measured: the attached WebView lays out at 960 CSS px and showed 14
            // control buttons, where the same page on a laptop shows 16 and puts CC
            // at a different index. VidFast is responsive, so at 960 we are getting a
            // NARROWER layout than the one whose subtitle dialog we know exists.
            // useWideViewPort is already on, but the page ships
            // <meta name="viewport" content="width=device-width">, which wins — so
            // rewrite that meta and let Blink re-lay-out. MUI reads breakpoints via
            // matchMedia, so the resize is what makes it re-render at the wide layout.
            var oldW = window.innerWidth;
            try {
              var mv = document.querySelector('meta[name=viewport]');
              if (!mv) {
                mv = document.createElement('meta');
                mv.setAttribute('name', 'viewport');
                document.head.appendChild(mv);
              }
              mv.setAttribute('content', 'width=1920, initial-scale=1');
              window.dispatchEvent(new Event('resize'));
            } catch (e) {
              VidApiBridge.onHunt('viewport override failed: ' + e.message);
            }

            var rafFired = false;
            requestAnimationFrame(function(){ rafFired = true; });
            setTimeout(function(){
              try {
                VidApiBridge.onHunt('env: ' + oldW + ' -> ' + window.innerWidth + 'x' + window.innerHeight +
                  ' raf=' + rafFired +
                  ' modals=' + document.querySelectorAll('[class*="MuiModal-root"],[role="dialog"],[role="menu"]').length +
                  ' bodyLen=' + document.body.innerHTML.length);
              } catch(e) {}
            }, 1200);

            // Give React a beat to re-render at the new breakpoint before we go
            // looking for controls — the button set itself changes with the layout.
            setTimeout(function(){ go(); }, 1000);
            function go(){
            var btns = Array.prototype.slice.call(document.querySelectorAll('button'));
            var cc = null;
            for (var i = 0; i < btns.length; i++) {
              var p = btns[i].querySelector('svg path');
              var d = p ? (p.getAttribute('d') || '') : '';
              if (d.indexOf('$CC_ICON_PATH_PREFIX') === 0) { cc = btns[i]; break; }
            }
            if (!cc) { VidApiBridge.onHunt('no-cc-button (' + btns.length + ' buttons)'); return; }
            var beforeLen = document.body.innerHTML.length;
            VidApiBridge.onHunt('cc found at index ' + btns.indexOf(cc) + ' of ' + btns.length);
            cc.click();
            setTimeout(function(){
              try {
                VidApiBridge.onHunt('after-click: bodyLen ' + beforeLen + ' -> ' +
                  document.body.innerHTML.length + ', dialogs=' +
                  document.querySelectorAll('[class*="MuiDialog-root"],[class*="MuiModal-root"]').length);
              } catch(e) {}
            }, 1200);

            // The language list is populated asynchronously — on a slow box it is
            // NOT there 900ms after the click (measured: 'no-english-entry'). Poll
            // instead of guessing, and if it never shows, report what the menu DID
            // contain so the failure is diagnosable from logcat alone.
            var items = function(){
              return Array.prototype.slice
                .call(document.querySelectorAll('[class*="MuiMenuItem-root"]'))
                .filter(function(e){ return /^english$/i.test((e.textContent || '').trim()); });
            };
            var snapshot = function(){
              var all = Array.prototype.slice
                .call(document.querySelectorAll('[class*="MuiMenuItem-root"]'));
              if (!all.length) return '0 menu items';
              return all.length + ' items: ' + all.slice(0, 12).map(function(e){
                return (e.textContent || '').trim().slice(0, 14);
              }).join(',');
            };
            var tries = 0;
            var iv = null;
            function tick(){
              tries++;
              var found = items();
              if (found.length) {
                clearInterval(iv);
                found[0].click();
                VidApiBridge.onHunt('clicked english (1 of ' + found.length + ') after ' + (tries * 400) + 'ms');
                return;
              }
              if (tries === 2)  VidApiBridge.onHunt('waiting… ' + snapshot());
              // Halfway: the menu may have closed itself — reopen once.
              if (tries === 9 && !window.__ccReopened) { window.__ccReopened = true; cc.click(); }
              if (tries >= 18) { clearInterval(iv); VidApiBridge.onHunt('gave up — ' + snapshot()); }
            }
            iv = setInterval(tick, 400);
            }
          } catch (e) {
            try { VidApiBridge.onHunt('err: ' + e.message); } catch (_) {}
          }
        })();
        """.trimIndent()
        view.evaluateJavascript(js, null)

        // Poll until the interceptor / JS hook records the .vtt, or we run out of
        // patience. subtitleUrl is written from both paths and is @Volatile.
        val deadline = System.currentTimeMillis() + SUBTITLE_HUNT_MS
        val poll = object : Runnable {
            override fun run() {
                if (subtitleUrl != null) {
                    Log.d(TAG, "💬 subtitle hunt: got it — ${truncate(subtitleUrl)}")
                    onDone(); return
                }
                if (System.currentTimeMillis() >= deadline) {
                    Log.w(TAG, "💬 subtitle hunt: nothing after ${SUBTITLE_HUNT_MS}ms — playing without")
                    onDone(); return
                }
                handler.postDelayed(this, SUBTITLE_POLL_MS)
            }
        }
        handler.postDelayed(poll, SUBTITLE_POLL_MS)
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

    // ── Network-filter detection (see BLOCK_PAGE_HOSTS) ──────────────────────

    private fun hostOf(url: String?): String? =
        url?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }

    private fun isBlockPageUrl(url: String): Boolean =
        BLOCK_PAGE_HOSTS.any { url.contains(it, ignoreCase = true) }

    /**
     * Give up NOW and say why. Retrying is pointless — a filter blocks every
     * attempt identically, so the old behaviour was two full 60s timeouts and a
     * message that blamed the source.
     */
    private fun reportNetworkBlock(host: String, how: String) {
        if (!active || delivered.get() || networkBlocked) return
        networkBlocked = true
        Log.e(TAG, "🚧 BLOCKED BY NETWORK: $host ($how)")
        handler.post {
            listener?.onStatusUpdate("Blocked by your network")
            listener?.onExtractionBlocked(
                "Your network is blocking $host.\n" +
                    "This is a filter on your internet connection (router or ISP), " +
                    "not a problem with BlueHive or the source."
            )
            cleanup()
        }
    }

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
                    // Detach before destroy() when hunt mode parented us to the host
                    // Activity — destroying a still-attached WebView leaves a dead
                    // view in that window's hierarchy.
                    (parent as? ViewGroup)?.removeView(this)
                    removeAllViews(); destroy()
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ cleanup error: ${e.message}")
            } finally {
                webView = null; listener = null; timeoutRunnable = null; clickRunnable = null
                hostActivity = null
                busy = false
                Log.d(TAG, "✅ extractor cleaned up")
            }
        }
    }
}