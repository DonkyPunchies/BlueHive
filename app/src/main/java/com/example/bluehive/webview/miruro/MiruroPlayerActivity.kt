package com.example.bluehive.webview.miruro

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import com.example.bluehive.R
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.example.bluehive.utilities.VideoQualityOverlay
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.example.bluehive.BuildConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@UnstableApi
class MiruroPlayerActivity : ComponentActivity() {

    private val sCRAPE = "scraper"

    // Channel up/down jump size for ExoPlayer seek (in ms).
    private val SEEK_INCREMENT_MS = 10_000L

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    // Per center-dial press: did we route the DOWN to the PlayerView controller
    // (so we let the UP through too) or handle it ourselves as a play/pause toggle
    // (so we swallow the UP)? Decided on ACTION_DOWN and reused on ACTION_UP, so a
    // visibility change between the two (e.g. controls auto-showing on pause) can't
    // split a single press across both paths.
    private var centerWentToController = false

    // ── Video-quality picker ────────────────────────────────────────────────────
    private val showQualityOverlay = mutableStateOf(false)   // toggled by the control-bar button
    private var selectedQualityIndex = 0                     // 0 = Auto
    private var qualityVideoGroup: Tracks.Group? = null
    private var qualityTrackIndices: List<Int> = emptyList() // parallel to labels[1..], sorted hi→lo

    // Fire TV remotes have a dedicated ▶❚❚ transport key; the onn box remote
    // does not. We only hijack the media play/pause keys on Fire TV so onn
    // behaviour is untouched. Detected via the Amazon fire_tv system feature,
    // with manufacturer as a fallback for odd builds.
    private val isFireTv: Boolean by lazy {
        packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
                Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("M3U8_URL")
        if (url.isNullOrBlank()) {
            Log.e(sCRAPE, "MiruroPlayerActivity launched without M3U8_URL — finishing")
            finish()
            return
        }
        val referer = intent.getStringExtra("REFERER") ?: "https://www.miruro.tv/"
        val ua = intent.getStringExtra("UA")
            ?: ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        Log.d(sCRAPE, "▶️ playing extracted m3u8: $url (referer=$referer)")

        val okHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val requestHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to referer,
            "Origin" to "https://www.miruro.tv",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "X-API-Key" to BuildConfig.API_KEY
        )

        val httpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(ua)
            .setDefaultRequestProperties(requestHeaders)

        fun buildMediaSource(playUrl: String): HlsMediaSource {
            return HlsMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(playUrl))
        }

        // Always grab the HIGHEST quality Miruro lists. HLS master playlists (e.g. the
        // wixmp 1080p/720p/480p one) default to ADAPTIVE selection — ExoPlayer starts
        // lower and ramps with bandwidth. Pin it to the top bitrate the device can
        // decode instead. Single-quality media playlists have nothing to choose, so
        // they're unaffected. This is playback-side only — zero impact on extraction.
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setForceHighestSupportedBitrate(true))
        }
        // Generous buffers so 1080p (~5.5 Mbps) relayed through the HLS proxy rides out
        // proxy/CDN hiccups without stalling. Mirrors VidApiPlayerActivity:
        // min 30s, max 120s, start after 2.5s, resume 10s after a rebuffer.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 10_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(sCRAPE, "❌ ExoPlayer failed for url=$url", error)
                    Log.e(sCRAPE, "❌ Cause: ${error.cause}")
                }
            })
            setMediaSource(buildMediaSource(url))
            prepare()
            playWhenReady = true
        }


        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    Log.d(sCRAPE, "⏱ duration=${player?.duration}ms | isLive=${player?.isCurrentMediaItemLive}")
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(sCRAPE, "❌ ExoPlayer error: ${error.message} | cause: ${error.cause?.message}")
            }
        })


        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        (LayoutInflater.from(ctx)
                            .inflate(R.layout.miruro_player_view, null) as PlayerView).apply {
                            playerView = this
                            this.player = this@MiruroPlayerActivity.player
                            // Quality button in the custom control bar opens the picker.
                            findViewById<View?>(R.id.quality_button)?.setOnClickListener {
                                showQualityOverlay.value = true
                            }
                        }
                    }
                )
                if (showQualityOverlay.value) {
                    val labels = remember(showQualityOverlay.value) { buildQualityLabels() }
                    VideoQualityOverlay(
                        options       = labels,
                        selectedIndex = selectedQualityIndex,
                        onSelect      = { idx ->
                            applyQuality(idx)
                            showQualityOverlay.value = false
                        },
                    )
                }
            }
        }
    }

    // Enumerate the stream's video qualities → ["Auto", "1080p", "720p", …], sorted
    // high→low, caching the group + track indices so applyQuality() can map a pick
    // back to a track. Called each time the picker opens (tracks can change).
    private fun buildQualityLabels(): List<String> {
        qualityVideoGroup = null
        qualityTrackIndices = emptyList()
        val p = player ?: return listOf("Auto")
        val group = p.currentTracks.groups.firstOrNull {
            it.type == C.TRACK_TYPE_VIDEO && it.length > 0
        } ?: return listOf("Auto")
        // (trackIndex, height, bitrate) sorted high→low, one row per resolution
        // (duplicate heights collapse to the highest-bitrate variant).
        val seenHeights = HashSet<Int>()
        val tracks = (0 until group.length)
            .map { idx ->
                val f = group.getTrackFormat(idx)
                Triple(idx, f.height, f.bitrate)
            }
            .sortedWith(compareByDescending<Triple<Int, Int, Int>> { it.second }.thenByDescending { it.third })
            .filter { seenHeights.add(it.second) }
        qualityVideoGroup = group
        qualityTrackIndices = tracks.map { it.first }
        val labels = mutableListOf("Auto")
        tracks.forEach { t ->
            labels.add(
                when {
                    t.second > 0 -> "${t.second}p"
                    t.third > 0  -> "${t.third / 1000} kbps"
                    else         -> "Track ${labels.size}"
                }
            )
        }
        return labels
    }

    // Apply the chosen quality. Index 0 (Auto) clears the override so the
    // force-highest default resumes; otherwise pin the mapped track.
    private fun applyQuality(index: Int) {
        val p = player ?: return
        selectedQualityIndex = index
        val builder = p.trackSelectionParameters.buildUpon()
        val group = qualityVideoGroup
        if (index <= 0 || group == null) {
            builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            qualityTrackIndices.getOrNull(index - 1)?.let { trackIdx ->
                builder.setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIdx))
                )
            }
        }
        p.trackSelectionParameters = builder.build()
    }

    // Map the onn box CHANNEL UP / CHANNEL DOWN keys to skip forward / back.
    // We act on ACTION_DOWN and also swallow ACTION_UP for these two keys so
    // the system never gets a chance to do anything else with them.
    // @SuppressLint("RestrictedApi") silences the androidx false-positive on the
    // super.dispatchKeyEvent() call — overriding this on a ComponentActivity is
    // legitimate and works at runtime.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Diagnostic: log EVERY key on the way down so we can see exactly what
        // the onn remote actually sends (this is the right way to confirm the
        // keycode — the HDMI/CEC logcat lines only cover volume).
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d(
                sCRAPE,
                "🎛 key down: code=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)})"
            )
        }
        // Quality overlay open: BACK closes it; every other key goes to the Compose
        // overlay so its focusable rows handle up / down / OK.
        if (showQualityOverlay.value) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) showQualityOverlay.value = false
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (player != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    seekBy(SEEK_INCREMENT_MS)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    seekBy(-SEEK_INCREMENT_MS)
                    return true
                }
                // Fire TV only: the remote's dedicated transport key toggles
                // playback directly. We intercept here in the Activity's
                // dispatchKeyEvent — before the view hierarchy — so PlayerView
                // never sees it and the on-screen controls stay out of the way.
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (isFireTv) {
                    togglePlayPause(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> if (isFireTv) {
                    player?.play(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> if (isFireTv) {
                    player?.pause(); return true
                }
                // Fire TV only: the dedicated ⏪ / ⏩ transport keys do the same
                // ±10s jump as the onn box's channel keys, straight on ExoPlayer
                // with no on-screen scrubber. seekBy already clamps to [0, dur].
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (isFireTv) {
                    seekBy(SEEK_INCREMENT_MS); return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> if (isFireTv) {
                    seekBy(-SEEK_INCREMENT_MS); return true
                }
                // Both remotes: the center dial is context-sensitive.
                //  • Controls hidden  → toggle play/pause directly (the quick action,
                //    every press — this is what fixed the "registers once" bug).
                //  • Controls showing → hand the key to PlayerView so the focused
                //    control fires (rewind-5 / play-pause / forward-15 / prev / next),
                //    so center can select options too, not just play/pause.
                // D-pad directions (never intercepted) are what raise the controls.
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (playerView?.isControllerFullyVisible == true) {
                        centerWentToController = true
                        return super.dispatchKeyEvent(event)   // activate focused control
                    } else {
                        centerWentToController = false
                        togglePlayPause()
                        return true
                    }
                }
            }
        }
        // Center dial UP: mirror the DOWN decision. If the DOWN went to the
        // controller, let the UP through so the focused button gets a full press;
        // if we handled it as a toggle, swallow the UP to match.
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            return if (centerWentToController) super.dispatchKeyEvent(event) else true
        }

        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
                    event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ||
                    (isFireTv && (
                            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND)))
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Toggle ExoPlayer playback for the Fire TV transport key. Flipping
    // playWhenReady is the robust toggle (works mid-buffer, where isPlaying is
    // briefly false); if playback already ended, restart from the top instead
    // of doing nothing.
    private fun togglePlayPause() {
        val p = player ?: return
        when {
            p.playbackState == Player.STATE_ENDED -> { p.seekTo(0); p.play() }
            p.playWhenReady -> p.pause()
            else -> p.play()
        }
        Log.d(sCRAPE, "⏯ transport key → playWhenReady=${player?.playWhenReady}")
    }

    // Seek relative to the current position, clamped to [0, duration].
    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration
        val current = p.currentPosition
        val target = if (duration > 0) {
            (current + deltaMs).coerceIn(0L, duration)
        } else {
            (current + deltaMs).coerceAtLeast(0L)
        }
        p.seekTo(target)
        Log.d(sCRAPE, "⏩ channel-key seek: ${current}ms → ${target}ms (Δ${deltaMs}ms)")
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroy() {
        Log.d(sCRAPE, "🧹 MiruroPlayerActivity onDestroy — releasing player")
        releasePlayer()
        super.onDestroy()
    }

    private fun releasePlayer() {
        playerView?.player = null
        playerView = null
        player?.run {
            playWhenReady = false
            stop()
            clearMediaItems()
            release()
        }
        player = null
    }
}