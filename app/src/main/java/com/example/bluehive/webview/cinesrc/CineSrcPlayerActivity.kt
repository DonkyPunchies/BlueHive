package com.example.bluehive.webview.cinesrc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import com.example.bluehive.BuildConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Plays a cinesrc master.m3u8 in ExoPlayer.
 * On a fatal CDN error (e.g. a missing segment → HTTP 404), re-runs extraction for
 * the same title to get a fresh server/UUID, up to MAX_EXTRACT_RETRIES times.
 */
@UnstableApi
class CineSrcPlayerActivity : ComponentActivity() {

    private val TAG = "scraper-cine"

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    // --- failover state ---
    private var loggedDuration = false
    private var extractRetries = 0

    // --- stream params (kept so we can rebuild the player on retry) ---
    private var embedUrl: String? = null
    private var referer: String = "https://cinesrc.st/"
    private var ua: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private lateinit var reextractLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("M3U8_URL")
        if (url.isNullOrBlank()) {
            Log.e(TAG, "CineSrcPlayerActivity launched without M3U8_URL — finishing")
            finish(); return
        }

        // embedUrl is what makes failover possible. Pass it from the launcher (see note below).
        embedUrl = intent.getStringExtra("EMBED_URL")
        referer  = intent.getStringExtra("REFERER") ?: referer
        ua       = intent.getStringExtra("UA") ?: ua
        val subUrl = intent.getStringExtra("SUBTITLE_URL")?.takeIf { it.isNotBlank() }

        // Register the re-extraction result handler once.
        reextractLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            val newUrl = data?.getStringExtra(CineSrcExtractorActivity.RESULT_M3U8)
            if (result.resultCode == RESULT_OK && !newUrl.isNullOrBlank()) {
                referer = data.getStringExtra(CineSrcExtractorActivity.RESULT_REFERER) ?: referer
                ua      = data.getStringExtra(CineSrcExtractorActivity.RESULT_UA) ?: ua
                val newSub = data.getStringExtra(CineSrcExtractorActivity.RESULT_SUBTITLE)
                    ?.takeIf { it.isNotBlank() }
                Log.d(TAG, "✅ re-extraction succeeded — restarting playback on fresh node")
                loggedDuration = false
                startPlayback(newUrl, newSub)
            } else {
                val reason = data?.getStringExtra(CineSrcExtractorActivity.RESULT_REASON) ?: "unknown"
                Log.e(TAG, "❌ re-extraction failed ($reason) — finishing")
                finish()
            }
        }

        startPlayback(url, subUrl)
    }

    /** Builds (or rebuilds) the ExoPlayer for a given m3u8. Safe to call on retry. */
    private fun startPlayback(url: String, subUrl: String?) {
        Log.d(TAG, "▶️ playing cinesrc m3u8: $url (referer=$referer, sub=${subUrl ?: "none"})")

        // Subtitle sideloading stays OFF (…/search?id= is a JSON endpoint, not a .srt,
        // and media3 1.9.0 disables legacy SubRip decoding).
        if (subUrl != null) Log.d(TAG, "💬 subtitle URL captured but NOT sideloaded (testing): $subUrl")

        val okHttpClient = OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val requestHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to referer,
            "Origin" to "https://cinesrc.st",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "X-API-Key" to BuildConfig.API_KEY
        )

        val httpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(ua)
            .setDefaultRequestProperties(requestHeaders)

        val mediaSource: MediaSource =
            HlsMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(url))

        // Release any previous instance before rebuilding (retry path).
        playerView?.player = null
        player?.run { stop(); clearMediaItems(); release() }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 30_000,
                /* maxBufferMs */ 120_000,                  // hoard up to 2 min when bandwidth is good (default 50s)
                /* bufferForPlaybackMs */ 2_500,            // initial buffer before first frame
                /* bufferForPlaybackAfterRebufferMs */ 10_000 // refill a real cushion after a stall (default 5s)
            )
            .setPrioritizeTimeOverSizeThresholds(true)      // buffer by time, not a byte budget
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build().apply {
                addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && !loggedDuration) {
                        loggedDuration = true
                        Log.d(TAG, "⏱ duration=${player?.duration}ms | isLive=${player?.isCurrentMediaItemLive}")
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "❌ ExoPlayer error: ${error.errorCodeName} | ${error.message} | cause=${error.cause?.message}")

                    val isCdnFailure =
                        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                                error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE

                    if (isCdnFailure && embedUrl != null && extractRetries < MAX_EXTRACT_RETRIES) {
                        extractRetries++
                        Log.w(TAG, "↻ CDN failure — re-extracting for a fresh server (retry $extractRetries/$MAX_EXTRACT_RETRIES)")
                        relaunchExtraction()
                    } else {
                        if (embedUrl == null)
                            Log.e(TAG, "no EMBED_URL passed — cannot re-extract; finishing")
                        finish()
                    }
                }
            })
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }

        // Re-attach to the existing PlayerView if we're on a retry.
        playerView?.player = player

        if (playerView == null) {
            setContent {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            playerView = this
                            this.player = this@CineSrcPlayerActivity.player
                            useController = true
                        }
                    }
                )
            }
        }
    }

    /** Fire the headless extractor again for the same embed URL. Result handled above. */
    private fun relaunchExtraction() {
        val embed = embedUrl ?: run { finish(); return }
        player?.run { stop(); clearMediaItems(); release() }
        player = null
        val intent = Intent(this, CineSrcExtractorActivity::class.java)
            .putExtra(CineSrcExtractorActivity.EXTRA_URL, embed)
        reextractLauncher.launch(intent)
    }

    override fun onPause()  { super.onPause();  player?.pause() }
    override fun onResume() { super.onResume(); player?.play() }
    override fun onDestroy() {
        Log.d(TAG, "🧹 onDestroy — releasing player")
        playerView?.player = null
        playerView = null
        player?.run { playWhenReady = false; stop(); clearMediaItems(); release() }
        player = null
        super.onDestroy()
    }

    companion object {
        private const val MAX_EXTRACT_RETRIES = 2
    }
}