package com.example.bluehive.webview.miruro

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

class MiruroExtractorActivity : ComponentActivity() {

    companion object {
        private const val TAG = "scraper"

        // Inputs
        const val EXTRA_URL         = "MIRURO_URL"
        const val EXTRA_DUB         = "MIRURO_DUB"
        const val EXTRA_MODE        = "MIRURO_MODE"
        const val EXTRA_SERVER_NAME = "MIRURO_SERVER"
        const val EXTRA_EPISODE     = "MIRURO_EPISODE"   // TV only; <= 0 means none

        // Values for EXTRA_MODE
        const val MODE_EXTRACT   = "EXTRACT"
        const val MODE_ENUMERATE = "ENUMERATE"

        // EXTRACT result (RESULT_OK)
        const val RESULT_M3U8    = "RESULT_M3U8"
        const val RESULT_REFERER = "RESULT_REFERER"
        const val RESULT_UA      = "RESULT_UA"

        // ENUMERATE result (RESULT_OK) — parallel arrays + default index
        const val RESULT_SERVER_NAMES         = "RESULT_SERVER_NAMES"
        const val RESULT_SERVER_TAGS          = "RESULT_SERVER_TAGS"
        const val RESULT_SERVER_DEFAULT_INDEX = "RESULT_SERVER_DEFAULT_INDEX"

        // Shared failure flags (RESULT_CANCELED)
        const val RESULT_NO_DUB = "RESULT_NO_DUB"   // requested audio track not available
        const val RESULT_FAILED = "RESULT_FAILED"   // real error (not a user cancel)
    }

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NO setContent. Transparent, renders nothing — the overlay lives in the
        // main process (MoviesDetailsScreenCompose) so this process gives its
        // whole main thread to the WebView.

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Log.e(TAG, "MiruroExtractorActivity: missing URL")
            finishAndKill(RESULT_CANCELED, Intent().putExtra(RESULT_FAILED, true))
            return
        }

        val dub        = intent.getBooleanExtra(EXTRA_DUB, false)
        val mode       = intent.getStringExtra(EXTRA_MODE) ?: MODE_EXTRACT
        val serverName = intent.getStringExtra(EXTRA_SERVER_NAME)
        val episode    = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it > 0 }

        Log.d(
            TAG,
            "🧩 extractor up (pid=${Process.myPid()}) mode=$mode dub=$dub " +
                    "server=${serverName ?: "default"} episode=${episode ?: "n/a"} url=$url"
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "↩️ user cancelled")
                // Plain cancel — no RESULT_FAILED, so the UI stays silent.
                finishAndKill(RESULT_CANCELED, null)
            }
        })

        if (mode == MODE_ENUMERATE) {
            startEnumerate(url, dub, episode)
        } else {
            startExtract(url, dub, serverName, episode)
        }
    }

    private fun startExtract(url: String, dub: Boolean, serverName: String?, episodeNumber: Int?) {
        MiruroStreamExtractorWebView.extract(
            context       = this,
            url           = url,
            sourceName    = "Miruro",
            dub           = dub,
            serverName    = serverName,
            episodeNumber = episodeNumber,
            listener      = object : MiruroStreamExtractorWebView.ExtractionListener {
                override fun onStatusUpdate(status: String) {
                    Log.d(TAG, "status: $status")
                }

                override fun onM3u8Found(m3u8Url: String, headers: Map<String, String>) {
                    val data = Intent()
                        .putExtra(RESULT_M3U8,    m3u8Url)
                        .putExtra(RESULT_REFERER, headers["Referer"])
                        .putExtra(RESULT_UA,      headers["User-Agent"])
                    finishAndKill(RESULT_OK, data)
                }

                override fun onExtractionFailed(reason: String) {
                    Log.e(TAG, "extraction failed: $reason")
                    if (reason == MiruroStreamExtractorWebView.REASON_NO_DUB) {
                        finishAndKill(RESULT_CANCELED, Intent().putExtra(RESULT_NO_DUB, true))
                    } else {
                        finishAndKill(RESULT_CANCELED, Intent().putExtra(RESULT_FAILED, true))
                    }
                }
            }
        )
    }

    private fun startEnumerate(url: String, dub: Boolean, episodeNumber: Int?) {
        MiruroStreamExtractorWebView.enumerate(
            context       = this,
            url           = url,
            dub           = dub,
            episodeNumber = episodeNumber,
            listener      = object : MiruroStreamExtractorWebView.EnumerationListener {
                override fun onStatusUpdate(status: String) {
                    Log.d(TAG, "status: $status")
                }

                override fun onServersFound(servers: List<MiruroStreamExtractorWebView.ServerInfo>) {
                    val names = ArrayList<String>(servers.size)
                    val tags  = ArrayList<String>(servers.size)
                    var defaultIndex = -1
                    servers.forEachIndexed { i, s ->
                        names.add(s.name)
                        tags.add(s.tags.joinToString(","))   // tags packed CSV, unpacked in the screen
                        if (s.isDefault && defaultIndex == -1) defaultIndex = i
                    }
                    val data = Intent()
                        .putStringArrayListExtra(RESULT_SERVER_NAMES, names)
                        .putStringArrayListExtra(RESULT_SERVER_TAGS, tags)
                        .putExtra(RESULT_SERVER_DEFAULT_INDEX, if (defaultIndex == -1) 0 else defaultIndex)
                    finishAndKill(RESULT_OK, data)
                }

                override fun onEnumerationFailed(reason: String) {
                    Log.e(TAG, "enumerate failed: $reason")
                    if (reason == MiruroStreamExtractorWebView.REASON_NO_DUB) {
                        finishAndKill(RESULT_CANCELED, Intent().putExtra(RESULT_NO_DUB, true))
                    } else {
                        finishAndKill(RESULT_CANCELED, Intent().putExtra(RESULT_FAILED, true))
                    }
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
            Log.d(TAG, "💀 killing :extractor process (pid=${Process.myPid()})")
            Process.killProcess(Process.myPid())
        }, 300)
    }
}