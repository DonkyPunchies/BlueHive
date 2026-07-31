package com.example.bluehive.webview.vidfast

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.example.bluehive.webview.hls.HlsStreamExtractorWebView

/**
 * Transparent, content-less host for VidFast m3u8 extraction.
 *
 * Runs in its OWN process (see android:process in the manifest) and kills itself
 * when done. That isolation is the whole point of this feature: the alternative is
 * keeping GeckoView — a full browser engine — resident for the entire film, which
 * is what OOM-killed the 2 GB box. Here the WebView lives ~35s and its memory goes
 * away with the process.
 *
 * WHY IT REUSES HlsStreamExtractorWebView
 * ------------------------------------------
 * VidFast and VidApi are both hls.js embeds, and an on-device probe confirmed the
 * VidApi extractor captures VidFast's manifest UNCHANGED (caught via both the JS
 * hook and the network intercept, no DRM, no license traffic). Cloning ~500 lines
 * of interception logic would mean two copies of the same tricky code to fix every
 * time a site shifts. If VidFast ever needs genuinely different handling, split it
 * then — and the natural move is promoting the shared engine to its own package
 * rather than duplicating it.
 *
 * PROGRESS REPORTING
 * ------------------
 * The extractor runs in a separate process, so the details screen can't observe it
 * directly. Each real milestone is broadcast (same UID, so this stays in-app) and
 * the screen maps it onto the loading overlay. These are the extractor's ACTUAL
 * states, not a timer pretending to be progress.
 */
class VidFastExtractorActivity : ComponentActivity() {

    companion object {
        private const val TAG = "scraper-vidfast"

        const val EXTRA_URL = "VIDFAST_URL"

        const val RESULT_M3U8     = "RESULT_M3U8"
        const val RESULT_REFERER  = "RESULT_REFERER"
        const val RESULT_UA       = "RESULT_UA"
        const val RESULT_SUBTITLE = "RESULT_SUBTITLE"
        // The embed page's final URL (e.g. https://vidfast.vc/...). Its origin is
        // the Referer VidFast's CDN validates against — see the player launch.
        const val RESULT_PAGE_URL = "RESULT_PAGE_URL"

        const val RESULT_FAILED = "RESULT_FAILED"   // real error (not a user cancel)
        const val RESULT_REASON = "RESULT_REASON"   // human-readable failure reason
        // The failure was a network filter refusing the connection. Retrying is
        // pointless — every attempt is blocked identically — so the details
        // screen skips the retry and shows the reason instead.
        const val RESULT_BLOCKED = "RESULT_BLOCKED"

        // ── Live progress, extractor process → details screen ────────────────
        const val ACTION_PROGRESS = "com.example.bluehive.VIDFAST_EXTRACT_PROGRESS"
        const val EXTRA_STATUS    = "STATUS"
        const val EXTRA_STAGE     = "STAGE"

        /**
         * Map a real extractor status onto the overlay's 1..5 stage scale.
         * Deliberately derived from what the extractor actually reports, so the
         * dots can't advance while nothing is happening.
         */
        fun stageFor(status: String): Int = when {
            status.startsWith("Loading")  -> 1
            status.startsWith("Finding")  -> 2
            status.startsWith("Stream")   -> 4
            status.startsWith("Extract")  -> 5   // failed — overlay is torn down anyway
            else                          -> 3
        }

        fun broadcastProgress(ctx: Context, status: String) {
            ctx.sendBroadcast(
                Intent(ACTION_PROGRESS)
                    .setPackage(ctx.packageName)     // in-app only
                    .putExtra(EXTRA_STATUS, status)
                    .putExtra(EXTRA_STAGE, stageFor(status))
            )
        }
    }

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NO setContent — the loading overlay lives in TVShowsDetailsScreenCompose.

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Log.e(TAG, "VidFastExtractorActivity: missing URL")
            finishAndKill(RESULT_CANCELED, Intent()
                .putExtra(RESULT_FAILED, true)
                .putExtra(RESULT_REASON, "No embed URL was provided."))
            return
        }

        Log.d(TAG, "🧩 vidfast extractor up (pid=${Process.myPid()}) url=$url")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "↩️ user cancelled")
                finishAndKill(RESULT_CANCELED, null)  // plain cancel — no fallback, no error
            }
        })

        HlsStreamExtractorWebView.extract(
            context = this,
            url = url,
            // OFF. The hunt code in HlsStreamExtractorWebView still works and can be
            // re-armed by flipping this to true, but it never once produced a .vtt on
            // device, and it costs up to SUBTITLE_HUNT_MS (9s) on EVERY play. English
            // captions come from OpenSubtitles instead (see HlsSubtitles) and are good.
            //
            // Idea: VidFast only fetches its own .vtt when a language is picked in its
            // CC menu, so drive that menu in the extractor before tearing the WebView
            // down. The site's caption is per-release and therefore always in sync,
            // which OpenSubtitles is not.
            //
            // Every run ends the same way: the CC button IS found by its icon path and
            // clicked, the body grows slightly (so the click lands), and then zero
            // MuiMenuItems ever appear. Ruled out on device, in this order:
            //
            //   1. "The WebView is detached, so it has no layout and no rAF, and MUI
            //      can't animate a dialog open."  → attached it to this Activity's
            //      window (invisible). env: 960x540 raf=true. Still 0 menu items.
            //   2. "The menu is just slow on a 2 GB box."  → polled 400ms × 18 with a
            //      reopen at try 9, ~7s. Still 0 menu items.
            //   3. "960 CSS px puts VidFast on a narrower responsive layout than the
            //      laptop, which shows 16 controls to our 14."  → rewrote the viewport
            //      meta to width=1920 and let it re-lay-out. env: 960 -> 1920x1080,
            //      and STILL 14 buttons, dialogs=0, 0 menu items.
            //
            // (3) is the informative one: the control set does not change with width,
            // so the laptop/TV difference isn't the viewport. The remaining suspect is
            // capability media queries — Android WebView reports pointer:coarse /
            // hover:none, so VidFast likely renders a touch control set whose subtitle
            // picker isn't a MUI Menu at all. Next attempt should start by dumping the
            // 65 chars the body gains after the click, and by checking what the site
            // renders under an emulated fine pointer — not by guessing again.
            huntSubtitles = false,
            listener = object : HlsStreamExtractorWebView.ExtractionListener {
                override fun onStatusUpdate(status: String) {
                    Log.d(TAG, "status: $status")
                    broadcastProgress(this@VidFastExtractorActivity, status)
                }

                override fun onM3u8Found(m3u8Url: String, subtitleUrl: String?, headers: Map<String, String>) {
                    Log.d(TAG, "✅ m3u8 captured: $m3u8Url")
                    val data = Intent()
                        .putExtra(RESULT_M3U8, m3u8Url)
                        .putExtra(RESULT_REFERER, headers["Referer"])
                        .putExtra(RESULT_UA, headers["User-Agent"])
                        .putExtra(RESULT_SUBTITLE, subtitleUrl)
                        .putExtra(RESULT_PAGE_URL, headers["X-Page-Url"])
                    finishAndKill(RESULT_OK, data)
                }

                override fun onExtractionFailed(reason: String) {
                    Log.e(TAG, "extraction failed: $reason")
                    finishAndKill(RESULT_CANCELED, Intent()
                        .putExtra(RESULT_FAILED, true)
                        .putExtra(RESULT_REASON, reason))
                }

                override fun onExtractionBlocked(reason: String) {
                    Log.e(TAG, "extraction blocked by network: $reason")
                    finishAndKill(RESULT_CANCELED, Intent()
                        .putExtra(RESULT_FAILED, true)
                        .putExtra(RESULT_BLOCKED, true)
                        .putExtra(RESULT_REASON, reason))
                }
            }
        )
    }

    private fun finishAndKill(resultCode: Int, data: Intent?) {
        if (done) return
        done = true
        setResult(resultCode, data)
        finish()
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "💀 killing :vidfastextractor process (pid=${Process.myPid()})")
            Process.killProcess(Process.myPid())
        }, 300)
    }
}
