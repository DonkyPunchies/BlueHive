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
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.example.bluehive.utilities.VideoQualityOverlay
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.RelayGrantRequest
import com.example.bluehive.webview.exoplayer_resources.ExoPlayerConfig
import com.example.bluehive.api.WatchHistoryRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    // Channel up/down jump size for ExoPlayer seek (in ms). Shared with every
    // other site's player via ExoPlayerConfig so a skip feels identical.
    private val SEEK_INCREMENT_MS = ExoPlayerConfig.SEEK_INCREMENT_MS

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    // Watch history is written from HERE, on confirmed playback, rather than by the
    // details screen when it launched us — see onIsPlayingChanged below. One row per
    // launch; the flag stops pause/resume re-logging.
    private var watchLogged = false

    // ── Direct-first playback, HLS relay as fallback ────────────────────────────
    // We play the CDN URL directly and only route through /api/hls/proxy if that
    // fails. The relay exists because some consumer ISPs (Spectrum Security Shield
    // and friends) DNS-hijack the CDN host — but that only affects SOME networks,
    // and relaying pushes every video byte through the backend (~1 GB per episode,
    // in AND out). Trying direct first means unfiltered networks cost the server
    // nothing, while filtered ones still work via the fallback.
    private var fallbackUrl: String? = null
    private var usingFallback = false

    /**
     * Media3 groups IO/network failures in the 2000-2999 error range
     * (ERROR_CODE_IO_*). We fall back ONLY for those — a decoder or renderer
     * failure (4000s) would fail identically through the relay, so retrying it
     * would just waste bandwidth and delay the error the user needs to see.
     */
    private fun isNetworkFailure(error: PlaybackException): Boolean =
        error.errorCode in 2000..2999

    /** POST the watch-history row. Fire-and-forget: never block or fail playback. */
    private fun logWatchOnPlaybackConfirmed() {
        val profileId = intent.getIntExtra("PROFILE_ID", -1)
        val mediaId   = intent.getStringExtra("MEDIA_ID")?.toIntOrNull() ?: 0
        if (profileId == -1 || mediaId == 0) {
            Log.w(sCRAPE, "▶️ playback confirmed but PROFILE_ID/MEDIA_ID missing — not logging")
            return
        }
        val mediaType = intent.getStringExtra("MEDIA_TYPE") ?: "tv"
        val season    = intent.getIntExtra("SEASON_NUMBER", 0).takeIf { it > 0 }
        val episode   = intent.getIntExtra("EPISODE_NUMBER", 0).takeIf { it > 0 }
        val title     = intent.getStringExtra("MEDIA_TITLE")
        val epName    = intent.getStringExtra("EPISODE_NAME")
        val source    = intent.getStringExtra("SOURCE_NAME") ?: "Miruro"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ApiClient.bluehiveApi.logWatchHistory(
                    WatchHistoryRequest(
                        profile_id     = profileId,
                        media_tmdb_id  = mediaId,
                        media_type     = mediaType,
                        source_name    = source,
                        media_title    = title,
                        season_number  = season,
                        episode_number = episode,
                        episode_name   = epName,
                    )
                )
                Log.d(sCRAPE, "✅ Watch history logged on PLAYBACK: tmdb=$mediaId S$season E$episode ($source)")
            } catch (e: Exception) {
                Log.w(sCRAPE, "⚠️ Watch-history log failed (non-fatal): ${e.message}")
            }
        }
    }

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

        // Relay URL to switch to if the direct CDN attempt fails. Absent = the
        // caller wants the given URL played as-is with no fallback.
        fallbackUrl = intent.getStringExtra("M3U8_FALLBACK_URL")?.takeIf { it.isNotBlank() }

        Log.d(sCRAPE, "▶️ playing extracted m3u8: $url (referer=$referer) " +
                "fallback=${if (fallbackUrl != null) "available" else "none"}")

        val requestHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to referer,
            "Origin" to "https://www.miruro.tv",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "X-API-Key" to BuildConfig.API_KEY
        )

        // Buffering / bitrate / networking come from the shared ExoPlayerConfig so
        // every site's player behaves identically. Only requestHeaders above are
        // Miruro-specific (its CDN pins Origin to miruro.tv).
        val httpFactory = ExoPlayerConfig.dataSourceFactory(ua, requestHeaders, context = this)

        fun buildMediaSource(playUrl: String): HlsMediaSource =
            ExoPlayerConfig.hlsMediaSource(playUrl, httpFactory)

        // Always grab the HIGHEST quality Miruro lists. HLS master playlists (e.g. the
        // wixmp 1080p/720p/480p one) default to ADAPTIVE selection — ExoPlayer starts
        // lower and ramps with bandwidth. Pin it to the top bitrate the device can
        // decode instead. Single-quality media playlists have nothing to choose, so
        // they're unaffected. This is playback-side only — zero impact on extraction.
        val trackSelector = ExoPlayerConfig.trackSelector(this)
        // Generous buffers so 1080p (~5.5 Mbps) relayed through the HLS proxy rides out
        // proxy/CDN hiccups without stalling. Mirrors VidApiPlayerActivity:
        // min 30s, max 120s, start after 2.5s, resume 10s after a rebuffer.
        player = ExoPlayerConfig.buildPlayer(this, trackSelector)
            .apply {
            // Diagnostic only: logs the rung actually selected + all rungs offered.
            ExoPlayerConfig.attachVideoTrackLog(this, sCRAPE)
            // Hold the display awake while playback is intended (incl. stalls).
            ExoPlayerConfig.keepScreenOnWhilePlaying(this@MiruroPlayerActivity, this)
            // Same diagnostics + deadlock recovery as HlsPlayerActivity: the
            // wedge is in the box's display stack, so it is not site-specific.
            ExoPlayerConfig.attachPlaybackLog(this, sCRAPE)
            ExoPlayerConfig.attachStallWatchdog(this, lifecycleScope, sCRAPE)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(sCRAPE, "❌ ExoPlayer failed for url=$url", error)
                    Log.e(sCRAPE, "❌ Cause: ${error.cause}")

                    // ── Network blockage → switch to the HLS relay, once ────────
                    // The direct CDN is unreachable on this network (ISP DNS
                    // hijack, blocked IP, dead host). Re-prepare against the relay
                    // and resume where we were, so a mid-play CDN failure doesn't
                    // restart the episode.
                    val fb = fallbackUrl
                    if (!usingFallback && fb != null && isNetworkFailure(error)) {
                        usingFallback = true
                        val resumeAt = currentPosition.coerceAtLeast(0L)
                        Log.w(sCRAPE,
                            "🔀 direct CDN unreachable (${error.errorCodeName}) — " +
                            "falling back to HLS relay, resuming at ${resumeAt}ms")
                        // Tag the relay with WHAT drove us here. It rides the
                        // master-playlist request only — _proxify propagates just
                        // the sid to segments — which is exactly where register()
                        // reads it, so it costs one query param, once.
                        val relayUrl = fb + "&err=" + java.net.URLEncoder.encode(
                            error.errorCodeName, "UTF-8")
                        // The relay refuses an unauthorised URL, so mint a grant
                        // first — see RelayGrantRequest. Async, hence the switch
                        // to the player happens in the coroutine below.
                        lifecycleScope.launch {
                            val granted = runCatching {
                                ApiClient.bluehiveApi.mintRelayGrant(
                                    RelayGrantRequest(relayUrl))
                            }.getOrNull()?.takeIf { it.isSuccessful }?.body()?.relay_url
                            if (granted.isNullOrBlank()) {
                                Log.e(sCRAPE, "🛑 relay grant refused — cannot recover")
                                return@launch
                            }
                            player?.apply {
                                setMediaSource(buildMediaSource(granted))
                                prepare()
                                if (resumeAt > 0) seekTo(resumeAt)
                                playWhenReady = true
                            }
                        }
                    } else if (usingFallback) {
                        Log.e(sCRAPE, "❌ relay failed too — nothing left to try")
                    }
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

            // ✅ THE watched signal for anime. Fires only once video is genuinely
            // rolling — not when the user picked DuB/SuB, not when a server was
            // chosen, not merely when this activity launched. A failed extract, a
            // dead server, or a stream that never starts therefore never marks the
            // episode watched. Guarded by `watchLogged` so a pause/resume can't
            // log the same episode twice.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !watchLogged) {
                    watchLogged = true
                    logWatchOnPlaybackConfirmed()
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
                            // 3s fade-out + blue focused scrubber (shared).
                            ExoPlayerConfig.applyControllerPolicy(this)
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
                // The dedicated ⏪ / ⏩ transport keys (Fire TV remote, and any other
                // remote that sends them) do the same ±10s jump as the onn box's
                // channel keys, straight on ExoPlayer with no on-screen scrubber.
                // seekBy already clamps to [0, dur].
                //
                // NOT gated on isFireTv, unlike play/pause below: seeking is the
                // only sane meaning for these keys on ANY remote, and gating it
                // meant a Firestick whose feature-flag/manufacturer check missed
                // would silently do nothing. Matches HlsPlayerActivity.
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekBy(SEEK_INCREMENT_MS); return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
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

        // Swallow the matching ACTION_UP so PlayerView never sees these keys and
        // the scrubber can't flash. Seek keys are ungated (see the DOWN handler);
        // play/pause stays behind isFireTv to keep onn behaviour untouched.
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
                    event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ||
                    event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                    event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ||
                    (isFireTv && (
                            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE)))
        ) {
            return true
        }

        // One direction press is enough to raise the controls (see the helper —
        // the first press was being spent on Compose focus interop).
        if (ExoPlayerConfig.raiseControllerOnDirectionKey(playerView, event)) return true

        return super.dispatchKeyEvent(event)
    }

    // Toggle ExoPlayer playback for the Fire TV transport key. Flipping
    // playWhenReady is the robust toggle (works mid-buffer, where isPlaying is
    // briefly false); if playback already ended, restart from the top instead
    // of doing nothing.
    private fun togglePlayPause() {
        val p = player ?: return
        ExoPlayerConfig.togglePlayPause(p)
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