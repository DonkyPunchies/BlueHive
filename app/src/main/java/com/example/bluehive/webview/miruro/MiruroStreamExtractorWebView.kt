package com.example.bluehive.webview.miruro

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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MiruroStreamExtractorWebView — headless WebView m3u8 extractor for miruro.tv
 *
 * FLOW:
 *   1. Load the Miruro watch URL in a WebView that is never attached to the UI
 *      (renders nothing — effectively headless).
 *   2. When the page settles, click the Vidstack play button
 *      (button.vds-play-button.vds-button). Nothing hits the network until
 *      playback starts, so the click is what makes hls.js fetch the manifest.
 *   3. Catch the master playlist (…/pl.m3u8) via shouldInterceptRequest AND a
 *      fetch/XHR JS hook (belt + suspenders).
 *   4. Hand the m3u8 + the headers ExoPlayer needs (Referer = miruro.tv) back
 *      to the caller, then tear the WebView down.
 *
 * Design notes:
 *   - This is a per-extraction INSTANCE, not a singleton, so the WebView lives
 *     in an instance field and is collected once cleanup() runs — no static
 *     Context/WebView leak.
 *   - A process-wide [busy] flag in the companion enforces one-at-a-time
 *     extraction without retaining any Context.
 *
 * All logs use the "scraper" tag — filter Logcat by `scraper` to follow along.
 */
class MiruroStreamExtractorWebView private constructor() {

    interface ExtractionListener {
        /** MAIN thread. The master m3u8 + headers ExoPlayer should send. */
        fun onM3u8Found(m3u8Url: String, headers: Map<String, String>)

        /** MAIN thread. Failure or timeout. */
        fun onExtractionFailed(reason: String)

        /** MAIN thread. Optional progress text. */
        fun onStatusUpdate(status: String) {}
    }

    /** A single provider/server entry read from the open "Provider" dropdown. */
    data class ServerInfo(
        val name: String,
        val tags: List<String>,
        val isDefault: Boolean
    )

    /** Listener for ENUMERATE mode — returns the server list, never plays. */
    interface EnumerationListener {
        /** MAIN thread. The servers available for the selected audio track. */
        fun onServersFound(servers: List<ServerInfo>)

        /** MAIN thread. Failure, timeout, or REASON_NO_DUB. */
        fun onEnumerationFailed(reason: String)

        /** MAIN thread. Optional progress text. */
        fun onStatusUpdate(status: String) {}
    }

    /** What this run is for. */
    private enum class Mode { EXTRACT, ENUMERATE }





    companion object {
        private const val sCRAPE = "scraper"

        // ── Tunables ─────────────────────────────────────────────────────
        private const val TIMEOUT_MS = 60_000L
        private const val FIRST_CLICK_DELAY = 2_500L   // let the player mount + sources resolve
        private const val CLICK_INTERVAL = 1_500L      // re-click cadence
        private const val MAX_CLICK_ATTEMPTS = 12

        // ── Dub-selection choreography ───────────────────────────────────
        private const val DROPDOWN_OPEN_DELAY     = 600L   // after opening the menu, before reading options
        private const val DUB_SWITCH_DELAY        = 900L   // after picking dub, before pressing play
        private const val DUB_RETRY_INTERVAL      = 800L   // retry cadence while the dropdown/menu renders
        private const val MAX_DUB_TRIGGER_ATTEMPTS = 8     // wait for the Language button to hydrate
        private const val MAX_DUB_OPTION_ATTEMPTS  = 4     // wait for the open menu to populate

        // Dub confirmation — verify the switch actually took before playing.
        private const val DUB_CONFIRM_INTERVAL  = 400L     // poll cadence for the Language label
        private const val MAX_DUB_CONFIRM_READS = 4        // tolerate a slow-to-update label
        private const val MAX_DUB_RECLICKS      = 2        // re-select dub if it still reads sub
        const val REASON_NO_DUB = "NO_DUB"                 // sentinel passed to onExtractionFailed

        // ── Provider/server choreography (ENUMERATE + named-server EXTRACT) ─
        private const val PROVIDER_RETRY_INTERVAL       = 800L  // retry cadence while the Provider menu renders
        private const val MAX_PROVIDER_TRIGGER_ATTEMPTS = 8     // wait for the Provider button to hydrate
        private const val MAX_PROVIDER_OPTION_ATTEMPTS  = 4     // wait for the open menu to populate
        private const val SERVER_SWITCH_DELAY           = 900L  // after picking a server, before pressing play

        // ── Episode choreography (TV only — click the right EP before anything) ─
        private const val EPISODE_RETRY_INTERVAL = 800L   // retry cadence while the episode grid hydrates
        private const val MAX_EPISODE_ATTEMPTS   = 10     // episode grid can be slow to render
        private const val EPISODE_SWITCH_DELAY   = 1_200L // after clicking the episode, let it load

        private const val MIRURO_ORIGIN = "https://www.miruro.tv"
        private const val MIRURO_REFERER = "https://www.miruro.tv/"
        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** Process-wide single-flight guard. A plain boolean — holds no Context. */
        @Volatile
        private var busy = false

        /**
         * Entry point. Starts a one-shot extraction. Safe to call from the main
         * thread (e.g. a Compose onClick). Does not retain the instance statically.
         */
        fun extract(
            context: Context,
            url: String,
            sourceName: String = "Miruro",
            dub: Boolean = false,
            serverName: String? = null,        // non-null → select this provider before playing
            episodeNumber: Int? = null,        // non-null → TV: click this episode first
            listener: ExtractionListener
        ) {
            if (busy) {
                Log.w(sCRAPE, "⚠️ extraction already running — ignoring duplicate call")
                listener.onExtractionFailed("Extraction already in progress")
                return
            }
            busy = true
            try {
                MiruroStreamExtractorWebView().start(
                    appContext      = context.applicationContext,
                    url             = url,
                    sourceName      = sourceName,
                    dub             = dub,
                    mode            = Mode.EXTRACT,
                    serverName      = serverName,
                    episodeNumber   = episodeNumber,
                    extractListener = listener,
                    enumListener    = null
                )
            } catch (e: Exception) {
                // start() catches its own errors, but if construction or anything
                // before its internal try fails, never leave the guard stuck true.
                Log.e(sCRAPE, "❌ start() threw before extraction began", e)
                busy = false
                listener.onExtractionFailed("Extraction failed to start: ${e.message}")
            }
        }

        /**
         * ENUMERATE: load the page, select the audio track, open the "Provider"
         * dropdown, and read the server list. Never presses play — nothing hits
         * the network, so this is side-effect free.
         */
        fun enumerate(
            context: Context,
            url: String,
            dub: Boolean = false,
            episodeNumber: Int? = null,        // non-null → TV: click this episode first
            listener: EnumerationListener
        ) {
            if (busy) {
                Log.w(sCRAPE, "⚠️ extraction already running — ignoring enumerate call")
                listener.onEnumerationFailed("Extraction already in progress")
                return
            }
            busy = true
            try {
                MiruroStreamExtractorWebView().start(
                    appContext      = context.applicationContext,
                    url             = url,
                    sourceName      = "Miruro",
                    dub             = dub,
                    mode            = Mode.ENUMERATE,
                    serverName      = null,
                    episodeNumber   = episodeNumber,
                    extractListener = null,
                    enumListener    = listener
                )
            } catch (e: Exception) {
                Log.e(sCRAPE, "❌ enumerate start() threw before it began", e)
                busy = false
                listener.onEnumerationFailed("Check failed to start: ${e.message}")
            }
        }
    }

    // ── Per-extraction state (instance fields, never static) ────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val delivered = AtomicBoolean(false)
    private val playStarted = AtomicBoolean(false) // prevents duplicate onPageFinished from starting clicks twice
    private val cleanedUp = AtomicBoolean(false)   // prevents double cleanup/race cleanup

    private var webView: WebView? = null
    private var listener: ExtractionListener? = null
    private var timeoutRunnable: Runnable? = null
    private var clickRunnable: Runnable? = null
    private var clickAttempts = 0
    private var dubReclicks = 0               // times we re-selected dub after it read sub
    // Some sources fetch the dub manifest the instant the language switch lands —
    // before any play click — and never re-fetch it. Rather than drop it, hold the
    // latest one and flush it through the moment dub is confirmed.
    private var pendingDubUrl: String? = null
    private var pendingDubHeaders: Map<String, String>? = null
    private var requestCount = 0
    private var startTimeMs = 0L

    @Volatile private var dub = false         // true → select the Dub track before playing
    @Volatile private var dubReady = false    // true once dub is selected; gates manifest capture
    @Volatile private var serverReady = true  // false while a chosen server is pending; gates capture
    @Volatile private var episodeReady = true // false while a TV episode is pending; gates capture

    private var mode = Mode.EXTRACT           // EXTRACT (play + capture) or ENUMERATE (read servers)
    private var serverName: String? = null    // non-null → select this provider before playing
    private var episodeNumber: Int? = null    // non-null → TV: click this episode before anything
    private var enumListener: EnumerationListener? = null

    @Volatile
    private var active = false

    // ════════════════════════════════════════════════════════════════════
    private fun start(
        appContext: Context,
        url: String,
        sourceName: String,
        dub: Boolean,
        mode: Mode,
        serverName: String?,
        episodeNumber: Int?,
        extractListener: ExtractionListener?,
        enumListener: EnumerationListener?
    ) {
        this.listener = extractListener
        this.enumListener = enumListener
        this.dub = dub
        this.mode = mode
        this.serverName = serverName
        this.episodeNumber = episodeNumber
        // If a specific server is requested, hold manifest capture shut until we
        // actually switch to it — otherwise the DEFAULT stream the player loads
        // first gets grabbed before the switch (the bug). No server requested →
        // gate starts open, so default / sub / dub behaviour is unchanged.
        this.serverReady = serverName.isNullOrBlank()
        // TV anime URLs carry ?ep=N — the AniList episode the backend already
        // remapped from the TMDB season/episode. Movie URLs have no ?ep, so this
        // is null and the episode gate stays open → movie behaviour is unchanged.
        this.episodeNumber = parseEpParam(url)
        this.episodeReady = (episodeNumber == null)
        active = true
        startTimeMs = System.currentTimeMillis()

        Log.d(sCRAPE, "════════════ MIRURO ${mode.name} START ════════════")
        Log.d(sCRAPE, "  source : $sourceName")
        Log.d(sCRAPE, "  url    : $url")
        Log.d(sCRAPE, "  track  : ${if (dub) "DUB" else "SUB"}")
        Log.d(sCRAPE, "  server : ${serverName ?: "default"}")
        Log.d(sCRAPE, "  episode: ${episodeNumber ?: "n/a (movie)"}")
        Log.d(sCRAPE, "  timeout: ${TIMEOUT_MS / 1000}s")

        try {
            createWebView(appContext, url)
        } catch (e: Exception) {
            Log.e(sCRAPE, "❌ WebView creation failed", e)
            failAndCleanup("WebView creation failed: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(appContext: Context, url: String) {
        val wv = WebView(appContext)
        webView = wv

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // ── HARD STATE RESET ────────────────────────────────────────────────
        // The app process is reused between extractions, so WebView's app-global
        // profile (cookies, localStorage, cache, service worker) survives the
        // previous run and pushes the 2nd+ load down a different, stalling path.
        // Wipe it so every extraction starts byte-identical to the very first.
        try {
            CookieManager.getInstance().apply { removeAllCookies(null); flush() }
            WebStorage.getInstance().deleteAllData()
            ServiceWorkerController.getInstance().setServiceWorkerClient(
                object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest) = null
                }
            )
            Log.d(sCRAPE, "🧼 wiped cookies + web storage + neutralised service worker")
        } catch (e: Exception) {
            Log.w(sCRAPE, "⚠️ state wipe failed (non-fatal): ${e.message}")
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false          // let play() run without a tap
            userAgentString = CHROME_USER_AGENT
            allowContentAccess = true
            allowFileAccess = false
            blockNetworkImage = true                          // we don't need posters
            loadWithOverviewMode = true
            useWideViewPort = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        Log.d(sCRAPE, "✅ headless WebView configured (JS on, images off, desktop UA)")

        wv.addJavascriptInterface(JsBridge(), "ScraperBridge")
        wv.webViewClient = ExtractorWebViewClient()
        wv.webChromeClient = ExtractorChromeClient()

        timeoutRunnable = Runnable {
            if (active && !delivered.get()) {
                val elapsed = System.currentTimeMillis() - startTimeMs
                Log.e(sCRAPE, "⏰ timed out after ${elapsed}ms — no m3u8 " +
                        "($requestCount requests, $clickAttempts play attempts)")
                failAndCleanup(
                    "Timed out — no m3u8 found ($requestCount requests intercepted)"
                )
            }
        }
        handler.postDelayed(timeoutRunnable!!, TIMEOUT_MS)

        Log.d(sCRAPE, "🚀 loading $url")
        listener?.onStatusUpdate("Loading Miruro…")
        wv.clearCache(true)
        wv.loadUrl(url)
    }

    // ── WebViewClient: request interception + page lifecycle ────────────────
    private inner class ExtractorWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            val elapsed = System.currentTimeMillis() - startTimeMs
            Log.d(sCRAPE, "▶️ page STARTED: ${truncate(url)} [${elapsed}ms]")
        }

        override fun onReceivedError(
            view: WebView?, request: WebResourceRequest?, error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                Log.e(sCRAPE, "❌ MAIN-FRAME error ${error?.errorCode} ${error?.description} @ ${request.url}")
            }
        }

        override fun onReceivedHttpError(
            view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
        ) {
            if (request?.isForMainFrame == true) {
                Log.w(sCRAPE, "⚠️ MAIN-FRAME http ${errorResponse?.statusCode} @ ${request.url}")
            }
        }

        override fun shouldInterceptRequest(
            view: WebView?, request: WebResourceRequest?
        ): WebResourceResponse? {
            val reqUrl = request?.url?.toString() ?: return null
            requestCount++

            if (isM3u8Url(reqUrl)) {
                Log.d(sCRAPE, "🎯 m3u8 seen via network intercept [#$requestCount]: $reqUrl")
                deliver(reqUrl, buildHeaders(request), "network-intercept")
                return null    // let it proceed; we only observe
            }

            if (isAdDomain(reqUrl)) {
                return WebResourceResponse(
                    "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                )
            }
            return null
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val elapsed = System.currentTimeMillis() - startTimeMs
            Log.d(sCRAPE, "✅ page loaded: ${truncate(url)} [${elapsed}ms, $requestCount reqs]")

            if (!playStarted.compareAndSet(false, true)) {
                Log.d(sCRAPE, "↩️ page finished again — play sequence already started, ignoring")
                return
            }

            // ENUMERATE never plays, so the manifest hooks aren't needed there.
            if (mode == Mode.EXTRACT) injectNetworkHook(view)

            // TV: the watch URL loads the show with an episode grid — click the
            // right episode BEFORE touching audio / servers / play. Movies pass
            // episodeNumber == null and skip straight to the existing path.
            val ep = episodeNumber
            if (ep != null) {
                listener?.onStatusUpdate("Selecting episode $ep…")
                enumListener?.onStatusUpdate("Selecting episode $ep…")
                selectEpisode(view, ep, 1)
            } else {
                proceedAfterEpisode(view)
            }
        }

        override fun onReceivedSslError(
            view: WebView?, handler: SslErrorHandler?, error: SslError?
        ) {
            Log.w(sCRAPE, "⚠️ SSL error on ${error?.url} — cancelling (SSL errors not permitted)")
            handler?.cancel()
        }
    }

    private inner class ExtractorChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            val elapsed = System.currentTimeMillis() - startTimeMs
            Log.d(sCRAPE, "📊 progress $newProgress% [${elapsed}ms]")
        }

        override fun onCreateWindow(
            view: WebView?, isDialog: Boolean,
            isUserGesture: Boolean, resultMsg: Message?
        ): Boolean {
            Log.d(sCRAPE, "🚫 blocked popup window")
            return false
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            // Surface only our own console.log lines, not the site's noise.
            consoleMessage?.message()?.let {
                if (it.startsWith("scraper:")) Log.d(sCRAPE, "   JS → $it")
            }
            return true
        }
    }



    // After the episode is in place (or immediately, for movies), kick off the
    // existing audio → server → play choreography.
    private fun proceedAfterEpisode(view: WebView?) {
        if (view == null || !active) return
        if (dub) {
            listener?.onStatusUpdate("Selecting dub…")
            openLanguageDropdown(view, 1)
        } else {
            listener?.onStatusUpdate("Preparing…")
            afterAudioReady(view)
        }
    }

    // ── TV: find the "EP N" button in the episode grid and click it ─────────
    // The episode buttons carry aria-label / title like "EP 1: Spring of the
    // DEAD"; we match the number exactly (so EP 1 never matches EP 12).
    private fun selectEpisode(view: WebView?, epNum: Int, attempt: Int) {
        if (view == null || !active) return
        if (attempt > MAX_EPISODE_ATTEMPTS) {
            Log.w(sCRAPE, "📺 episode $epNum button never appeared")
            failAndCleanup("Episode $epNum not available")
            return
        }
        val js = """
        (function(epNum) {
            var scope = document.querySelector('#episodes-list-container') || document;
            var btns = Array.prototype.slice.call(scope.querySelectorAll('button[aria-label], button[title]'));
            var match = null;
            for (var i = 0; i < btns.length; i++) {
                var b = btns[i];
                var label = b.getAttribute('aria-label') || b.getAttribute('title') || '';
                var m = label.match(/EP\s*(\d+)/i);
                if (m && parseInt(m[1], 10) === epNum) { match = b; break; }
            }
            if (!match) return 'not-found';
            try { match.scrollIntoView({block:'center'}); } catch(e){}
            ['pointerdown','pointerup','click'].forEach(function(ev){
                try { match.dispatchEvent(new MouseEvent(ev,{bubbles:true,cancelable:true,view:window})); } catch(e){}
            });
            try { match.click(); } catch(e){}
            return 'clicked';
        })($epNum);
        """.trimIndent()

        view.evaluateJavascript(js) { raw ->
            val result = raw?.trim()?.removeSurrounding("\"") ?: "?"
            Log.d(sCRAPE, "📺 select episode $epNum (attempt $attempt): $result")
            if (result == "clicked") {
                // Correct episode is now loading — open the episode gate. Capture
                // is still further gated by dub / server where applicable.
                episodeReady = true
                handler.postDelayed({
                    if (!active) return@postDelayed
                    proceedAfterEpisode(view)
                }, EPISODE_SWITCH_DELAY)
            } else {
                handler.postDelayed({ selectEpisode(view, epNum, attempt + 1) }, EPISODE_RETRY_INTERVAL)
            }
        }
    }



    // ── DUB: open the Language dropdown, choose "Dub", then play ────────────
    // aria/text selectors only — no hashed CSS-module class names — so this
    // survives Miruro re-deploys. The dropdown hydrates after onPageFinished on
    // this SPA, so both steps retry until the element appears. If the menu
    // populates but has no Dub entry, we report REASON_NO_DUB.
    private fun openLanguageDropdown(view: WebView?, attempt: Int) {
        if (view == null || !active || delivered.get()) return
        if (attempt > MAX_DUB_TRIGGER_ATTEMPTS) {
            Log.w(sCRAPE, "🎌 Language button never appeared — no dub available")
            failAndCleanup(REASON_NO_DUB)
            return
        }
        val openJs = """
        (function() {
            var t = document.querySelector('button[aria-label="Language"]');
            if (!t) return 'no-trigger';
            try { t.click(); } catch(e){}
            return 'opened';
        })();
        """.trimIndent()

        view.evaluateJavascript(openJs) { raw ->
            val result = raw?.trim()?.removeSurrounding("\"") ?: "?"
            if (result != "opened") {
                Log.d(sCRAPE, "🎌 language trigger not ready (attempt $attempt) — retrying")
                handler.postDelayed({ openLanguageDropdown(view, attempt + 1) }, DUB_RETRY_INTERVAL)
                return@evaluateJavascript
            }
            Log.d(sCRAPE, "🎌 language dropdown opened (attempt $attempt)")
            handler.postDelayed({ clickDubOption(view, 1) }, DROPDOWN_OPEN_DELAY)
        }
    }

    private fun clickDubOption(view: WebView?, attempt: Int) {
        if (view == null || !active || delivered.get()) return
        val clickJs = """
        (function() {
            var opts = Array.prototype.slice.call(document.querySelectorAll('[role="option"]'));
            if (opts.length === 0) return 'no-options';
            var dub = opts.filter(function(o){
                return /\bdub\b/i.test((o.textContent || '').trim());
            })[0];
            if (!dub) return 'no-dub';
            ['pointerdown','pointerup','click'].forEach(function(t){
                try { dub.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window})); } catch(e){}
            });
            try { dub.click(); } catch(e){}
            return 'dub-clicked';
        })();
        """.trimIndent()

        view.evaluateJavascript(clickJs) { raw ->
            val result = raw?.trim()?.removeSurrounding("\"") ?: "?"
            Log.d(sCRAPE, "🎌 dub option (attempt $attempt): $result")
            when (result) {
                "dub-clicked" -> {
                    // dubReady is NOT set here. Two things can otherwise hand back a
                    // sub stream mislabelled as dub: selecting Dub briefly re-touches
                    // the current SUB source, and the switch itself can fail to take.
                    // So we wait, then CONFIRM the active track is really Dub before
                    // playing — and the capture gate only opens at the play click.
                    listener?.onStatusUpdate("Dub selected — confirming…")
                    handler.postDelayed({
                        if (!active) return@postDelayed
                        confirmDubActive(view, 1)
                    }, DUB_SWITCH_DELAY)
                }
                "no-options" -> {
                    if (attempt >= MAX_DUB_OPTION_ATTEMPTS) {
                        Log.w(sCRAPE, "🎌 menu never populated — no dub available")
                        failAndCleanup(REASON_NO_DUB)
                    } else {
                        handler.postDelayed({ clickDubOption(view, attempt + 1) }, DUB_RETRY_INTERVAL)
                    }
                }
                else -> {   // "no-dub": options present, none is Dub → genuinely sub-only
                    Log.w(sCRAPE, "🎌 no Dub entry in menu — no dub available")
                    failAndCleanup(REASON_NO_DUB)
                }
            }
        }
    }




    // ── DUB: confirm the active audio track is really Dub before we play ────
    // Reads the Language trigger's current label. Clearly "Dub" → go. Still
    // "Sub" → the switch didn't take, so re-select Dub (capped). Neither word
    // (label not exposed on this build) → don't break the common case: poll
    // briefly, then proceed anyway. The play-time capture gate already drops
    // stray pre-play sub manifests, so proceeding stays safe in that case.
    private fun confirmDubActive(view: WebView?, readAttempt: Int) {
        if (view == null || !active || delivered.get()) return
        val js = """
        (function() {
            var t = document.querySelector('button[aria-label="Language"]');
            if (!t) return 'no-trigger';
            var label = (t.textContent || '').trim().toLowerCase();
            if (/\bdub\b/.test(label)) return 'dub';
            if (/\bsub\b/.test(label)) return 'sub';
            return 'unknown';
        })();
        """.trimIndent()

        view.evaluateJavascript(js) { raw ->
            val result = raw?.trim()?.removeSurrounding("\"") ?: "?"
            Log.d(sCRAPE, "🎌 confirm dub (read $readAttempt, reclicks $dubReclicks): $result")
            when (result) {
                "dub" -> {
                    Log.d(sCRAPE, "🎌 dub confirmed active — opening gate")
                    // Dub is verified, so open the capture gate now and flush any
                    // manifest the player grabbed during the switch.
                    openDubGateAndProceed(view, deliverBuffered = true)
                }
                "sub" -> {
                    if (dubReclicks < MAX_DUB_RECLICKS) {
                        dubReclicks++
                        Log.w(sCRAPE, "🎌 track still SUB — re-selecting dub (reclick $dubReclicks)")
                        // Discard whatever the player buffered while still on sub so a
                        // stale sub manifest can't be flushed when dub finally confirms.
                        pendingDubUrl = null
                        pendingDubHeaders = null
                        openLanguageDropdown(view, 1)   // re-opens menu, re-clicks Dub, re-confirms
                    } else {
                        // Won't switch after retries — report no-dub rather than
                        // silently hand back a sub stream the user didn't ask for.
                        Log.w(sCRAPE, "🎌 dub never took after $MAX_DUB_RECLICKS reclicks — no-dub")
                        failAndCleanup(REASON_NO_DUB)
                    }
                }
                else -> {
                    // 'unknown' / 'no-trigger' — label not populated/exposed yet.
                    if (readAttempt < MAX_DUB_CONFIRM_READS) {
                        handler.postDelayed(
                            { confirmDubActive(view, readAttempt + 1) },
                            DUB_CONFIRM_INTERVAL
                        )
                    } else {
                        // Can't read the label — proceed best-effort. Open the gate so
                        // a play-time manifest is still caught, but DON'T flush the
                        // buffer: unconfirmed, it might be the sub stream.
                        Log.w(sCRAPE, "🎌 dub state inconclusive — proceeding anyway")
                        openDubGateAndProceed(view, deliverBuffered = false)
                    }
                }
            }
        }
    }


    // ── Open the dub capture gate, then deliver the manifest the player already
    //    fetched during the language switch, or proceed to play ───────────────
    // deliverBuffered = true when dub is confirmed: if the source fetched its dub
    // manifest during the switch (and won't again on the play click), that buffered
    // manifest is what we want, so hand it over now. Only for the default server —
    // with a named server still pending, the buffered one is the default-server
    // stream and must be ignored in favour of the upcoming server switch.
    private fun openDubGateAndProceed(view: WebView?, deliverBuffered: Boolean) {
        if (view == null || !active || delivered.get()) return
        dubReady = true

        val bufferedUrl = pendingDubUrl
        if (deliverBuffered && bufferedUrl != null && serverName.isNullOrBlank()) {
            Log.d(sCRAPE, "🎌 dub gate open — delivering manifest fetched during the switch")
            deliver(
                bufferedUrl,
                pendingDubHeaders ?: mapOf(
                    "Referer" to MIRURO_REFERER,
                    "Origin" to MIRURO_ORIGIN,
                    "User-Agent" to CHROME_USER_AGENT
                ),
                "buffered-dub"
            )
            return
        }

        Log.d(sCRAPE, "🎌 dub gate open — proceeding to play")
        afterAudioReady(view)
    }

    // ── After the audio track is ready, decide what to do next ──────────────
    private fun afterAudioReady(view: WebView?) {
        if (view == null || !active) return
        when (mode) {
            Mode.ENUMERATE -> openProviderDropdown(view, 1)
            Mode.EXTRACT -> {
                val sn = serverName
                if (sn.isNullOrBlank()) {
                    startPlaySequence(view)            // default server — unchanged path
                } else {
                    openProviderForSelect(view, sn, 1) // named server, then play
                }
            }
        }
    }

    // ── ENUMERATE: open the Provider dropdown, then read every option ───────
    private fun openProviderDropdown(view: WebView?, attempt: Int) {
        if (view == null || !active) return
        if (attempt > MAX_PROVIDER_TRIGGER_ATTEMPTS) {
            Log.w(sCRAPE, "🗂 Provider button never appeared")
            failAndCleanup("No server list available")
            return
        }
        val openJs = """
        (function() {
            var t = document.querySelector('button[aria-label="Provider"]');
            if (!t) return 'no-trigger';
            try { t.click(); } catch(e){}
            return 'opened';
        })();
        """.trimIndent()

        view.evaluateJavascript(openJs) { raw ->
            val result = raw?.trim()?.removeSurrounding("\"") ?: "?"
            if (result != "opened") {
                Log.d(sCRAPE, "🗂 provider trigger not ready (attempt $attempt) — retrying")
                handler.postDelayed({ openProviderDropdown(view, attempt + 1) }, PROVIDER_RETRY_INTERVAL)
                return@evaluateJavascript
            }
            Log.d(sCRAPE, "🗂 provider dropdown opened (attempt $attempt)")
            handler.postDelayed({ readProviderOptions(view, 1) }, DROPDOWN_OPEN_DELAY)
        }
    }

    private fun readProviderOptions(view: WebView?, attempt: Int) {
        if (view == null || !active) return
        // Scope strictly to the Provider trigger's own listbox so we never read
        // the Language menu by accident. Name lives in the "itemName" span; tags
        // are the children of the "tagRow" span. aria/role + semantic class
        // fragments only — no hashed module class names.
        val readJs = """
        (function() {
            var t = document.querySelector('button[aria-label="Provider"]');
            if (!t) return JSON.stringify({status:'no-trigger'});
            var container = t.parentElement;
            var menu = null;
            while (container && !menu) {
                menu = container.querySelector('[role="listbox"]');
                if (!menu) container = container.parentElement;
            }
            if (!menu) return JSON.stringify({status:'no-menu'});
            var opts = Array.prototype.slice.call(menu.querySelectorAll('[role="option"]'));
            if (opts.length === 0) return JSON.stringify({status:'no-options'});
            var servers = [];
            for (var i = 0; i < opts.length; i++) {
                var o = opts[i];
                var nameEl = o.querySelector('[class*="itemName"]') || o.querySelector('[class*="itemLead"]');
                var name = nameEl ? (nameEl.textContent || '').trim() : (o.textContent || '').trim();
                if (!name) continue;
                var tags = [];
                var tagRow = o.querySelector('[class*="tagRow"]');
                if (tagRow) {
                    var kids = tagRow.children;
                    for (var k = 0; k < kids.length; k++) {
                        var tt = (kids[k].textContent || '').trim();
                        if (tt) tags.push(tt);
                    }
                }
                var isDefault = o.getAttribute('aria-selected') === 'true';
                servers.push({ name: name, tags: tags, isDefault: isDefault });
            }
            return JSON.stringify({status:'ok', servers: servers});
        })();
        """.trimIndent()

        view.evaluateJavascript(readJs) { raw ->
            val json = decodeJsString(raw)
            val parsed = try { JSONObject(json) } catch (e: Exception) { null }
            val status = parsed?.optString("status") ?: "?"
            when (status) {
                "ok" -> {
                    val arr: JSONArray? = parsed?.optJSONArray("servers")
                    val list = ArrayList<ServerInfo>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val name = obj.optString("name").trim()
                            if (name.isEmpty()) continue
                            val tagsArr = obj.optJSONArray("tags")
                            val tags = ArrayList<String>()
                            if (tagsArr != null) {
                                for (j in 0 until tagsArr.length()) {
                                    val tg = tagsArr.optString(j).trim()
                                    if (tg.isNotEmpty()) tags.add(tg)
                                }
                            }
                            list.add(ServerInfo(name, tags, obj.optBoolean("isDefault", false)))
                        }
                    }
                    if (list.isEmpty()) {
                        failAndCleanup("No servers found")
                    } else if (delivered.compareAndSet(false, true)) {
                        Log.d(sCRAPE, "🗂 found ${list.size} servers: ${list.joinToString { it.name }}")
                        handler.post {
                            try {
                                enumListener?.onServersFound(list)
                            } catch (e: Exception) {
                                Log.e(sCRAPE, "❌ onServersFound threw — cleaning up anyway", e)
                            } finally {
                                cleanup()
                            }
                        }
                    }
                }
                "no-options", "no-menu", "no-trigger" -> {
                    if (attempt >= MAX_PROVIDER_OPTION_ATTEMPTS) {
                        failAndCleanup("Server list never populated ($status)")
                    } else {
                        handler.postDelayed({ readProviderOptions(view, attempt + 1) }, PROVIDER_RETRY_INTERVAL)
                    }
                }
                else -> failAndCleanup("Server list read error ($status)")
            }
        }
    }

    // ── EXTRACT (named server): open Provider, click the match, then play ───
    private fun openProviderForSelect(view: WebView?, server: String, attempt: Int) {
        if (view == null || !active || delivered.get()) return
        if (attempt > MAX_PROVIDER_TRIGGER_ATTEMPTS) {
            Log.w(sCRAPE, "🗂 Provider button never appeared for select")
            failAndCleanup("Couldn't open server list")
            return
        }
        val openJs = """
        (function() {
            var t = document.querySelector('button[aria-label="Provider"]');
            if (!t) return 'no-trigger';
            try { t.click(); } catch(e){}
            return 'opened';
        })();
        """.trimIndent()

        view.evaluateJavascript(openJs) { raw ->
            val result = raw?.trim()?.removeSurrounding("\"") ?: "?"
            if (result != "opened") {
                handler.postDelayed({ openProviderForSelect(view, server, attempt + 1) }, PROVIDER_RETRY_INTERVAL)
                return@evaluateJavascript
            }
            handler.postDelayed({ clickServerOption(view, server, 1) }, DROPDOWN_OPEN_DELAY)
        }
    }

    private fun clickServerOption(view: WebView?, server: String, attempt: Int) {
        if (view == null || !active || delivered.get()) return
        val nameLiteral = JSONObject.quote(server)   // safely escaped JS string literal
        val clickJs = """
        (function() {
            var want = $nameLiteral;
            var t = document.querySelector('button[aria-label="Provider"]');
            if (!t) return 'no-trigger';
            var container = t.parentElement;
            var menu = null;
            while (container && !menu) {
                menu = container.querySelector('[role="listbox"]');
                if (!menu) container = container.parentElement;
            }
            if (!menu) return 'no-menu';
            var opts = Array.prototype.slice.call(menu.querySelectorAll('[role="option"]'));
            var match = null;
            for (var i = 0; i < opts.length; i++) {
                var o = opts[i];
                var nameEl = o.querySelector('[class*="itemName"]') || o.querySelector('[class*="itemLead"]');
                var nm = nameEl ? (nameEl.textContent || '').trim() : (o.textContent || '').trim();
                if (nm === want) { match = o; break; }
            }
            if (!match) return 'not-found';
            ['pointerdown','pointerup','click'].forEach(function(ev){
                try { match.dispatchEvent(new MouseEvent(ev,{bubbles:true,cancelable:true,view:window})); } catch(e){}
            });
            try { match.click(); } catch(e){}
            return 'selected';
        })();
        """.trimIndent()

        view.evaluateJavascript(clickJs) { raw ->
            val result = raw?.trim()?.removeSurrounding("\"") ?: "?"
            Log.d(sCRAPE, "🗂 select server '$server' (attempt $attempt): $result")
            when (result) {
                "selected" -> {
                    // Open the capture gate the moment the chosen server is active.
                    // From here on, the next manifest the player fetches — whether
                    // it loads on the switch itself or when we press play — is the
                    // one we actually want.
                    serverReady = true
                    listener?.onStatusUpdate("Server selected — starting player…")
                    handler.postDelayed({
                        if (!active || delivered.get()) return@postDelayed
                        startPlaySequence(view)
                    }, SERVER_SWITCH_DELAY)
                }
                "not-found" -> {
                    Log.w(sCRAPE, "🗂 server '$server' not found in list")
                    failAndCleanup("Server not available")
                }
                else -> {
                    if (attempt >= MAX_PROVIDER_OPTION_ATTEMPTS) {
                        failAndCleanup("Couldn't select server ($result)")
                    } else {
                        handler.postDelayed({ clickServerOption(view, server, attempt + 1) }, PROVIDER_RETRY_INTERVAL)
                    }
                }
            }
        }
    }



    // ── Click the Vidstack play button, retrying until the m3u8 lands ───────
    private fun startPlaySequence(view: WebView?) {
        clickRunnable = object : Runnable {
            override fun run() {
                if (!active || delivered.get()) return
                if (clickAttempts >= MAX_CLICK_ATTEMPTS) {
                    Log.w(sCRAPE, "⚠️ exhausted $MAX_CLICK_ATTEMPTS play-button attempts")
                    return
                }
                // The dub capture gate is opened in openDubGateAndProceed() the moment
                // dub is confirmed — not here — so a manifest this source fetches during
                // the switch (before the first play click) is buffered and flushed
                // rather than dropped.
                clickAttempts++
                clickPlayButton(view, clickAttempts)
                handler.postDelayed(this, CLICK_INTERVAL)
            }
        }
        Log.d(sCRAPE, "▶️ scheduling first play-button click in ${FIRST_CLICK_DELAY}ms")
        listener?.onStatusUpdate("Pressing play…")
        handler.postDelayed(clickRunnable!!, FIRST_CLICK_DELAY)
    }

    private fun clickPlayButton(view: WebView?, attempt: Int) {
        if (view == null || delivered.get()) return
        val js = """
        (function() {
            var btn = document.querySelector('button.vds-play-button.vds-button')
                   || document.querySelector('button.vds-play-button')
                   || document.querySelector('.vds-play-button')
                   || document.querySelector('button[aria-label="Play"]')
                   || document.querySelector('[data-media-tooltip="play"]');
            if (btn) {
                ['pointerdown','pointerup','click'].forEach(function(t){
                    try { btn.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window})); } catch(e){}
                });
                try { btn.click(); } catch(e){}
                console.log('scraper: clicked vds-play-button (' + (btn.className||'') + ')');
                return 'clicked';
            }
            var mp = document.querySelector('media-player');
            if (mp && typeof mp.play === 'function') {
                try { mp.play(); console.log('scraper: called media-player.play()'); return 'media-player'; } catch(e){}
            }
            var v = document.querySelector('video');
            if (v) {
                try { v.muted = true; v.click(); var p = v.play(); if (p && p.catch) p.catch(function(){}); } catch(e){}
                console.log('scraper: clicked + played <video>');
                return 'video';
            }
            console.log('scraper: play button not present yet');
            return 'notfound';
        })();
        """.trimIndent()

        view.evaluateJavascript(js) { raw ->
            val result = raw?.trim()?.removeSurrounding("\"") ?: "?"
            val elapsed = System.currentTimeMillis() - startTimeMs
            if (result == "notfound") {
                Log.d(sCRAPE, "▶️ play attempt #$attempt: button not ready yet [${elapsed}ms]")
            } else {
                Log.d(sCRAPE, "▶️ play attempt #$attempt: PRESSED ($result) [${elapsed}ms]")
            }
        }
    }

    // ── fetch/XHR hook: backup capture path for manifests the native
    //    intercept might miss ─────────────────────────────────────────────
    private fun injectNetworkHook(view: WebView?) {
        if (view == null || delivered.get()) return
        Log.d(sCRAPE, "🪝 injecting fetch/XHR m3u8 hooks")
        val js = """
        (function() {
            if (window.__miruroHook) return;
            window.__miruroHook = true;
            function report(u){ if (u && u.indexOf('.m3u8') !== -1) {
                try { ScraperBridge.onM3u8Intercepted(u); } catch(e){}
            }}
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
                var s = this;
                this.addEventListener('load', function(){ report(s.responseURL || s.__u || ''); });
                return os.apply(this, arguments);
            };
            console.log('scraper: fetch/XHR hooks installed');
        })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private inner class JsBridge {
        // Called from injected JS; @Keep stops R8 stripping it and silences the
        // "never used" inspection (Lint can't see the JS call site).
        @Keep
        @JavascriptInterface
        fun onM3u8Intercepted(url: String) {
            Log.d(sCRAPE, "🎯 m3u8 seen via JS hook: $url")
            handler.post {
                deliver(
                    url,
                    mapOf(
                        "Referer" to MIRURO_REFERER,
                        "Origin" to MIRURO_ORIGIN,
                        "User-Agent" to CHROME_USER_AGENT
                    ),
                    "js-hook"
                )
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    private fun deliver(url: String, headers: Map<String, String>, via: String) {
        // ENUMERATE never presses play, so it must never deliver a manifest.
        if (mode != Mode.EXTRACT) return
        // TV: drop anything captured before the correct episode is selected —
        // the show page can auto-preview episode 1.
        if (!episodeReady) {
            Log.d(sCRAPE, "⏭️ ignoring pre-episode m3u8 ($via): $url")
            return
        }
        // In dub mode, a manifest seen before the gate opens isn't necessarily
        // junk: some sources fetch the dub manifest the instant you pick dub —
        // during the switch, before any play click — and never re-fetch it. So
        // hold the latest one instead of dropping it; openDubGateAndProceed()
        // flushes it through the moment dub is confirmed.
        if (dub && !dubReady) {
            pendingDubUrl = url
            pendingDubHeaders = headers
            Log.d(sCRAPE, "🕓 buffering pre-gate dub m3u8 ($via): $url")
            return
        }
        // When a specific server was requested, drop everything the player loads
        // before we've switched to it — that early manifest is the DEFAULT
        // stream, which is exactly the bug being fixed here.
        if (!serverReady) {
            Log.d(sCRAPE, "⏭️ ignoring pre-server (default) m3u8 ($via): $url")
            return
        }
        // Exactly-once, even though shouldInterceptRequest runs off the main thread.
        if (!delivered.compareAndSet(false, true)) return
        val elapsed = System.currentTimeMillis() - startTimeMs
        Log.d(sCRAPE, "════════════ M3U8 CAPTURED ($via) ════════════")
        Log.d(sCRAPE, "  url     : $url")
        Log.d(sCRAPE, "  referer : ${headers["Referer"]}")
        Log.d(sCRAPE, "  elapsed : ${elapsed}ms after $clickAttempts play attempts")
        handler.post {
            try {
                listener?.onStatusUpdate("Stream found — starting player…")
                listener?.onM3u8Found(url, headers)
            } catch (e: Exception) {
                // A throw here (e.g. startActivity failing) must NOT skip cleanup,
                // or busy stays true and every future extraction is blocked.
                Log.e(sCRAPE, "❌ onM3u8Found threw — cleaning up anyway", e)
            } finally {
                cleanup()
            }
        }
    }

    private fun buildHeaders(request: WebResourceRequest): Map<String, String> {
        val h = mutableMapOf<String, String>()
        request.requestHeaders?.forEach { (k, v) -> h[k] = v }
        h.putIfAbsent("Referer", MIRURO_REFERER)
        h.putIfAbsent("Origin", MIRURO_ORIGIN)
        h.putIfAbsent("User-Agent", CHROME_USER_AGENT)
        return h
    }

    private fun isM3u8Url(url: String?): Boolean {
        if (url == null) return false
        if (!url.lowercase().contains(".m3u8")) return false
        val host = try { url.toUri().host ?: "" } catch (_: Exception) { "" }
        val adHosts = listOf(
            "doubleclick", "googlesyndication", "adservice",
            "adserver", "popads", "exoclick", "trafficjunky"
        )
        return adHosts.none { host.contains(it, ignoreCase = true) }
    }

    private fun isAdDomain(url: String): Boolean {
        val host = try { url.toUri().host ?: "" } catch (_: Exception) { "" }
        val patterns = listOf(
            "doubleclick", "googlesyndication", "googleadservices",
            "analytics", "popads", "popcash", "propellerads", "adsterra",
            "exoclick", "juicyads", "trafficjunky", "/beacon", "cloudflareinsights"
        )
        return patterns.any { host.contains(it, true) || url.contains(it, true) }
    }

    private fun truncate(url: String?, max: Int = 200): String =
        if (url == null) "null" else if (url.length > max) url.take(max) + "…" else url


    /** TV anime watch URLs look like …/watch/{id}?ep=N, where N is the AniList
     *  episode the backend resolved to. Returns N, or null for movies (no ?ep). */
    private fun parseEpParam(url: String): Int? = try {
        url.toUri().getQueryParameter("ep")?.trim()?.toIntOrNull()
    } catch (_: Exception) { null }

    /**
     * evaluateJavascript returns a JSON-encoded string literal (the JS already
     * called JSON.stringify, so WebView wraps it again). Unwrap it once so we
     * get the real JSON text back.
     */
    private fun decodeJsString(raw: String?): String {
        if (raw == null) return ""
        return try {
            JSONObject("{\"v\":$raw}").getString("v")
        } catch (e: Exception) {
            raw.trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
        }
    }




    // ════════════════════════════════════════════════════════════════════
    private fun failAndCleanup(reason: String) {
        handler.post {
            when (mode) {
                Mode.EXTRACT -> {
                    listener?.onStatusUpdate("Extraction failed")
                    listener?.onExtractionFailed(reason)
                }
                Mode.ENUMERATE -> {
                    enumListener?.onStatusUpdate("Check failed")
                    enumListener?.onEnumerationFailed(reason)
                }
            }
        }
        cleanup()
    }

    private fun cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) {
            Log.d(sCRAPE, "↩️ cleanup already ran — ignoring duplicate cleanup")
            return
        }

        val elapsed = System.currentTimeMillis() - startTimeMs
        Log.d(
            sCRAPE,
            "🧹 cleanup — $requestCount reqs, $clickAttempts play attempts, " +
                    "found=${delivered.get()}, ${elapsed}ms"
        )

        active = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        clickRunnable?.let { handler.removeCallbacks(it) }

        handler.post {
            try {
                webView?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    clearHistory()
                    clearCache(true)          // ← drop this extraction's HTTP cache
                    removeJavascriptInterface("ScraperBridge")
                    webChromeClient = null
                    removeAllViews()
                    destroy()
                }
            } catch (e: Exception) {
                Log.w(sCRAPE, "⚠️ cleanup error: ${e.message}")
            } finally {
                // ALWAYS release the single-flight guard, even if WebView teardown
                // throws — otherwise busy stays true and the next extraction is
                // rejected with "Extraction already in progress".
                webView = null
                listener = null
                timeoutRunnable = null
                clickRunnable = null
                busy = false
                Log.d(sCRAPE, "✅ extractor cleaned up")
            }
        }
    }
}