package com.example.bluehive.webview.vidapi

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * Transparent, content-less host for the VidApi (embed) extraction. Runs in its
 * own process (see manifest android:process) so the WebView gets a whole main
 * thread, and is killed afterward to reclaim its memory — same pattern as
 * MiruroExtractorActivity.
 */
class VidApiExtractorActivity : ComponentActivity() {

    companion object {
        private const val TAG = "scraper-vidapi"

        const val EXTRA_URL = "VIDAPI_URL"

        const val RESULT_M3U8     = "RESULT_M3U8"
        const val RESULT_REFERER  = "RESULT_REFERER"
        const val RESULT_UA       = "RESULT_UA"
        const val RESULT_SUBTITLE = "RESULT_SUBTITLE"

        const val RESULT_FAILED = "RESULT_FAILED"   // real error (not a user cancel)
        const val RESULT_REASON = "RESULT_REASON"   // human-readable failure reason
    }

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NO setContent — the loading overlay lives in MoviesDetailsScreenCompose.

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Log.e(TAG, "VidApiExtractorActivity: missing URL")
            finishAndKill(RESULT_CANCELED, Intent()
                .putExtra(RESULT_FAILED, true)
                .putExtra(RESULT_REASON, "No embed URL was provided."))
            return
        }

        Log.d(TAG, "🧩 vidapi extractor up (pid=${Process.myPid()}) url=$url")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "↩️ user cancelled")
                finishAndKill(RESULT_CANCELED, null)  // plain cancel — UI stays silent
            }
        })

        VidApiStreamExtractorWebView.extract(
            context = this,
            url = url,
            listener = object : VidApiStreamExtractorWebView.ExtractionListener {
                override fun onStatusUpdate(status: String) { Log.d(TAG, "status: $status") }

                override fun onM3u8Found(m3u8Url: String, subtitleUrl: String?, headers: Map<String, String>) {
                    val data = Intent()
                        .putExtra(RESULT_M3U8, m3u8Url)
                        .putExtra(RESULT_REFERER, headers["Referer"])
                        .putExtra(RESULT_UA, headers["User-Agent"])
                        .putExtra(RESULT_SUBTITLE, subtitleUrl)
                    finishAndKill(RESULT_OK, data)
                }

                override fun onExtractionFailed(reason: String) {
                    Log.e(TAG, "extraction failed: $reason")
                    finishAndKill(RESULT_CANCELED, Intent()
                        .putExtra(RESULT_FAILED, true)
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
            Log.d(TAG, "💀 killing :vidapiextractor process (pid=${Process.myPid()})")
            Process.killProcess(Process.myPid())
        }, 300)
    }
}
