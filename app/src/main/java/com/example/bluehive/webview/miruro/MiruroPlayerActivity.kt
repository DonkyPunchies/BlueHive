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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.widget.Toast
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.example.bluehive.auth.SessionManager
import com.example.bluehive.BuildConfig
import com.example.bluehive.IdleWatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.example.bluehive.api.extractStreamAwait

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

    // ── Second-stage recovery: re-extract when the relay fails too ──────────
    //
    // THE FIRST STAGE (direct -> relay, below) CANNOT FIX AN EXPIRED TOKEN.
    // Miruro signs its segment URLs with a token that dies after roughly nine
    // minutes. MEASURED 2026-08-27: Mushoku Tensei s1e6 played 121 segments
    // through the relay and then every request became a 403, because the URL
    // itself had expired — not the network, not the host, the URL. Relaying a
    // dead URL harder cannot help; the only fix is a NEW url, which means
    // extracting again.
    //
    // Until this existed the branch simply logged "nothing left to try" and the
    // picture froze on the last decoded frame.
    private var extractRetries = 0

    // ── Cooldown, not expiry ────────────────────────────────────────────────
    //
    // A 403 FROM THIS CDN IS A PENALTY BOX, NOT A DEAD URL, and getting that
    // backwards is what made the first version of this file worse than the
    // freeze it replaced. MEASURED 2026-08-27:
    //
    //   - Two extractions with refresh=1, both reporting cached=false, returned
    //     the IDENTICAL url. Miruro's path token is deterministic per
    //     episode+audio+server, so re-extracting cannot mint a different one and
    //     is structurally incapable of clearing a 403.
    //   - The exact url that 403'd this device answered 200 from the SAME
    //     household IP twenty minutes later. Nothing had expired; the client had
    //     been let back in.
    //   - Providers bee, pewe and hop all resolve to s1.watami.win. Switching
    //     server changes the url but not the host the limit is counted against,
    //     so it buys nothing here either.
    //
    // The 429s that preceded the 403s said all of this out loud. So the answer to
    // a refusal is to STOP ASKING for a while — the opposite of what re-extraction
    // does, which is to restart playback and fire a fresh burst of segment
    // requests at a CDN that just asked for quiet.
    // The last url EXTRACTION produced — not what the player currently holds.
    // Those differ whenever the relay is in use (the player has an
    // api.<domain>/api/hls/proxy url wrapping this one), and comparing against
    // the player's would make the same-url guard below a silent no-op in exactly
    // the case that needs it most.
    private var lastExtractedM3u8: String? = null
    private var cooldownRetries = 0

    // ONE COOLDOWN AT A TIME. MEASURED 2026-08-28 on Mushoku Tensei: cooldowns 3
    // and 4 fired 9.5s and 41s after the previous one instead of the 60s and 120s
    // they had asked for, so a 225-second budget was spent in 104 seconds and the
    // player gave up while the CDN was still only a few minutes from letting us
    // back in. The url it abandoned answered 200 shortly afterwards.
    //
    // The cause is that a multi-audio manifest gives ExoPlayer TWO loaders — video
    // and the English audio rendition — and each reports its own failure. Without
    // this guard the audio track's error spends the video track's pending
    // cooldown, and the budget drains at the rate errors arrive rather than the
    // rate we chose to retry.
    private var cooldownPending = false
    private val MAX_COOLDOWN_RETRIES = 4
    // 30s, 60s, 120s, 240s — 7.5 minutes of patience in total. The previous
    // 15/30/60/120 schedule was still short: the refusal that ended the 2026-08-28
    // session had lifted well before I checked the url again, but not within the
    // 3m45s that schedule allowed even when honoured.
    private val COOLDOWN_BASE_MS = 30_000L

    // THE BUDGET RESETS AFTER SUSTAINED PLAYBACK, and that is the difference
    // between this and the movie player's version. HlsPlayerActivity spends a
    // LIFETIME budget of four, which suits "this CDN is broken, try a few
    // servers". It does not suit a token that expires on a schedule: a
    // 24-minute episode needs ~2 refreshes and a feature-length film ~9, so a
    // lifetime cap would still freeze exactly the content this is meant to fix.
    //
    // Elapsed time since the last recovery is the signal. If the stream played
    // for two clear minutes, whatever just happened is a NEW problem rather
    // than a retry loop, and it gets a fresh budget. A genuine loop retries
    // within seconds and never earns the reset.
    private var lastRecoveryAtMs = 0L
    private val MAX_EXTRACT_RETRIES = 4
    private val RETRY_BUDGET_RESET_MS = 120_000L

    /** Say something before the screen goes away — a silent finish reads as a crash. */
    private fun abandonSourceWithNotice() {
        Toast.makeText(this, "Couldn't reach this episode's server. Try another source.",
                       Toast.LENGTH_LONG).show()
        lifecycleScope.launch { delay(2_500); finish() }
    }

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

        // ── COLLAPSE THE DETAILS BACK-STACK ─────────────────────────────────
        //
        // Browsing recommendation -> recommendation pushes each page onto
        // BlueHiveApplication.detailsHistory (capped at 8, oldest evicted). Once
        // something actually PLAYS, that trail is spent: the user's next BACK
        // should return them to wherever they entered from — Home, History,
        // Favorites — not walk them back through eight pages they already left.
        //
        // Clearing is the whole mechanism: with the stack empty,
        // popPreviousDetails() returns null and the details screen's back handler
        // falls through to its sourceScreen branch, which goes to the entry point.
        //
        // WHY IT HAD STOPPED WORKING. clearDetailsHistory() was only ever called
        // from handleStreamingSourceSelected — the GeckoView path. Once playback
        // moved to backend extraction + ExoPlayer, that function stopped being
        // reached for almost every play, so the stack was never collapsed and BACK
        // walked the whole trail again.
        //
        // Placed here, gated by the same guard as the history row, so "logged to
        // watch history" and "back-stack collapsed" are always the same event —
        // which is the rule that makes the behaviour predictable.
        runCatching {
            (application as? com.example.bluehive.BlueHiveApplication)?.clearDetailsHistory()
        }


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
        lastExtractedM3u8 = url

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
        // See HlsPlayerActivity: the hint must exist before the engine is built.
        ExoPlayerConfig.ensureQuicHint(this, url)
        val httpFactory = ExoPlayerConfig.dataSourceFactory(ua, requestHeaders, context = this)

        fun buildMediaSource(playUrl: String): HlsMediaSource =
            ExoPlayerConfig.hlsMediaSource(playUrl, httpFactory)

        // ── Re-extraction inputs ────────────────────────────────────────────────
        // AUDIO is the one that must not be missing. Sub and dub are different
        // streams behind one page, and the backend keys its cache on the track —
        // so re-extracting without naming it hands a dub viewer Japanese audio and
        // calls it a success. That is a worse outcome than the freeze this whole
        // path exists to remove, because nothing about it looks wrong.
        val reAudio   = intent.getStringExtra("AUDIO")
        val reServer  = intent.getStringExtra("SERVER")
        val reTmdb    = intent.getStringExtra("MEDIA_ID")?.toIntOrNull()
        val reSeason  = intent.getIntExtra("SEASON_NUMBER", 0).takeIf { it > 0 }
        val reEpisode = intent.getIntExtra("EPISODE_NUMBER", 0).takeIf { it > 0 }
        val reTitle   = intent.getStringExtra("MEDIA_TITLE")
        val reProfile = intent.getIntExtra("PROFILE_ID", -1).takeIf { it > 0 }

        /**
         * Fetch a FRESH manifest and resume where we were.
         *
         * The last resort, reached only once relaying has also failed. Logged at
         * Log.e throughout on purpose: release builds strip v/d/i/w, and a recovery
         * path that goes quiet in exactly the builds users run is a path nobody can
         * diagnose.
         */
        fun reExtractAndResume(why: String) {
            if (reTmdb == null) {
                Log.e(sCRAPE, "[recovery] no MEDIA_ID — cannot re-extract ($why)")
                abandonSourceWithNotice(); return
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastRecoveryAtMs != 0L && now - lastRecoveryAtMs > RETRY_BUDGET_RESET_MS) {
                Log.e(sCRAPE, "[recovery] ${(now - lastRecoveryAtMs) / 1000}s of playback since " +
                        "the last recovery — this is a new fault, resetting the budget")
                extractRetries = 0
            }
            if (extractRetries >= MAX_EXTRACT_RETRIES) {
                Log.e(sCRAPE, "[recovery] $MAX_EXTRACT_RETRIES re-extractions without lasting " +
                        "playback — giving up on this source")
                abandonSourceWithNotice(); return
            }
            extractRetries++
            lastRecoveryAtMs = now
            val resumeAt = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            Log.e(sCRAPE, "[recovery] re-extracting ($why) " +
                    "$extractRetries/$MAX_EXTRACT_RETRIES, resuming at ${resumeAt}ms")

            lifecycleScope.launch {
                val body = runCatching {
                    ApiClient.bluehiveApi.extractStreamAwait(
                        type    = if (reSeason != null) "tv" else "movie",
                        tmdbId  = reTmdb,
                        season  = reSeason,
                        episode = reEpisode,
                        source  = "miruro",
                        // refresh=1 IS THE POINT. The cached entry is the very URL
                        // that just died; serving it back would burn a retry to
                        // reproduce the same 403.
                        refresh = 1,
                        mtype   = if (reSeason != null) "anime_tv" else "anime_movie",
                        title   = reTitle,
                        profile = reProfile,
                        device  = SessionManager.get().deviceFingerprint,
                        audio   = reAudio,
                        server  = reServer,
                    )
                }.getOrNull()?.takeIf { it.isSuccessful }?.body()

                if (body == null || body.m3u8.isBlank()) {
                    Log.e(sCRAPE, "[recovery] re-extraction produced nothing — giving up")
                    abandonSourceWithNotice(); return@launch
                }
                // THE URL DID NOT CHANGE, SO NEITHER WILL THE OUTCOME. Miruro mints
                // deterministically, so a re-extraction can hand back the very url
                // that just failed. Restarting into it costs a seek, a fresh burst
                // of segment requests and one of the four retries, to reproduce the
                // same error — so treat it as the refusal it is and cool down.
                if (lastExtractedM3u8 == body.m3u8) {
                    Log.e(sCRAPE, "[recovery] re-extraction returned the SAME url — " +
                            "nothing to restart into, cooling down instead")
                    extractRetries--   // refund: this attempt bought nothing
                    if (cooldownRetries < MAX_COOLDOWN_RETRIES) {
                        cooldownRetries++
                        val waitMs = COOLDOWN_BASE_MS shl (cooldownRetries - 1)
                        delay(waitMs)
                        runCatching { player?.prepare() }
                    } else {
                        abandonSourceWithNotice()
                    }
                    return@launch
                }
                if (reAudio != null && body.audio != null && !body.audio.equals(reAudio, true)) {
                    Log.e(sCRAPE, "[recovery] asked for '$reAudio' but the extractor " +
                            "returned '${body.audio}' — audio will not match the label")
                }
                // BACK TO STAGE ONE. The fresh URL is a direct CDN link, so the
                // relay fallback becomes available again; leaving usingFallback set
                // would skip it and relay a stream that may not need relaying at
                // all — every byte of it through the VPS for nothing.
                usingFallback = false
                fallbackUrl = body.proxy_url?.takeIf { it.isNotBlank() }
                lastExtractedM3u8 = body.m3u8
                Log.e(sCRAPE, "[recovery] fresh manifest, resuming at ${resumeAt}ms")
                player?.apply {
                    setMediaSource(buildMediaSource(body.m3u8))
                    prepare()
                    if (resumeAt > 0) seekTo(resumeAt)
                    playWhenReady = true
                }
            }
        }

        // Always grab the HIGHEST quality Miruro lists. HLS master playlists (e.g. the
        // wixmp 1080p/720p/480p one) default to ADAPTIVE selection — ExoPlayer starts
        // lower and ramps with bandwidth. Pin it to the top bitrate the device can
        // decode instead. Single-quality media playlists have nothing to choose, so
        // they're unaffected. This is playback-side only — zero impact on extraction.
        val trackSelector = ExoPlayerConfig.trackSelector(this)

        // ── Pick the audio the viewer actually asked for ─────────────────────────
        //
        // THE MANIFEST CONTAINS BOTH TRACKS AND MARKS THE WRONG ONE DEFAULT.
        // MEASURED 2026-08-27 on Mushoku Tensei s1e6, the master playlist returned
        // for a DUB request:
        //
        //   #EXT-X-MEDIA:TYPE=AUDIO,NAME="Japanese",DEFAULT=YES,LANGUAGE="jpn"
        //   #EXT-X-MEDIA:TYPE=AUDIO,NAME="English",           LANGUAGE="eng"
        //
        // Extraction was correct — the dub url differs from the sub url and the
        // English rendition is right there — but with no language preference set,
        // ExoPlayer honours DEFAULT=YES and plays Japanese. The dub was being
        // fetched and then ignored, which is why this looked like an extraction
        // fault and is not one.
        //
        // setPreferredAudioLanguage is a PREFERENCE, not a filter: a single-track
        // manifest (which is what this source used to return) still plays normally
        // rather than failing to find a match.
        if (intent.getStringExtra("AUDIO")?.equals("dub", ignoreCase = true) == true) {
            trackSelector.setParameters(
                trackSelector.buildUponParameters().setPreferredAudioLanguage("en")
            )
            Log.e(sCRAPE, "🔊 dub requested — preferring English audio rendition")
        }
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
                            // Captured rather than discarded so a THROWN call is
                            // distinguishable from a refusal. Both used to look
                            // identical in the log, which is why "relay grant
                            // refused" never said whether the relay had said no or
                            // the request never arrived.
                            val grantAttempt = runCatching {
                                ApiClient.bluehiveApi.mintRelayGrant(
                                    RelayGrantRequest(relayUrl))
                            }
                            val granted = grantAttempt.getOrNull()
                                ?.takeIf { it.isSuccessful }?.body()?.relay_url
                            if (granted.isNullOrBlank()) {
                                // RE-EXTRACT, DO NOT PARK.
                                //
                                // This used to log one line and return, which left the
                                // player in STATE_IDLE forever: usingFallback was
                                // already true, so no later error could route to stage
                                // two — and no later error ever arrived, because
                                // attachStallWatchdog deliberately skips IDLE. The
                                // result was a black screen that never recovered and
                                // never explained itself, with BACK the only way out.
                                //
                                // HlsPlayerActivity hits the identical condition and
                                // calls relaunchExtraction(); every other terminal
                                // branch in this file ends at abandonSourceWithNotice().
                                // This was the one dead end.
                                //
                                // reExtractAndResume owns the budget: it falls through
                                // to abandonSourceWithNotice() when MEDIA_ID is missing
                                // or the retries are spent, and refuses to restart into
                                // an unchanged URL. So the worst case is now a message
                                // the user can act on instead of silence.
                                val why = grantAttempt.exceptionOrNull()
                                    ?.let { "threw ${it.javaClass.simpleName}: ${it.message}" }
                                    ?: grantAttempt.getOrNull()?.let { "http ${it.code()}" }
                                    ?: "no response"
                                Log.e(sCRAPE, "🛑 relay grant refused ($why) — re-extracting")
                                reExtractAndResume("relay-grant-refused")
                                return@launch
                            }
                            player?.apply {
                                setMediaSource(buildMediaSource(granted))
                                prepare()
                                if (resumeAt > 0) seekTo(resumeAt)
                                playWhenReady = true
                            }
                        }
                    } else {
                        // ── Stage two ────────────────────────────────────────────
                        //
                        // A PLAIN `else`, NOT `else if (usingFallback)`. Two ways in:
                        // the relay was tried and failed, OR there was never a relay
                        // URL to try (fb == null, a caller that wanted this URL played
                        // as-is). The second case used to match no branch at all and
                        // froze in silence — the same symptom, one condition earlier.
                        //
                        // THE SPLIT THAT MATTERS: did the server REFUSE us, or did we
                        // fail to reach it? They look alike to the player and want
                        // opposite treatment.
                        //
                        //   401/403/429  the host answered, and said no. The url is
                        //                fine — this client is in a penalty box. Wait.
                        //   transport    connection reset, DNS, TLS, timeout. Nothing
                        //                answered, so the url may genuinely be dead and
                        //                a fresh one is worth fetching.
                        //
                        // Getting this backwards is what made the previous version worse
                        // than the freeze: it read a 403 as an expired token and
                        // re-extracted, four times in 73 seconds, each restarting
                        // playback and firing another burst at a throttled CDN.
                        val httpStatus = generateSequence(error.cause) { it.cause }
                            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                            .firstOrNull()?.responseCode

                        if (httpStatus == 429 || httpStatus == 403 || httpStatus == 401) {
                            // Already waiting — this is the other loader reporting the
                            // same refusal, not a new one. Spending a retry on it is
                            // what drained the budget in a third of the intended time.
                            if (cooldownPending) {
                                Log.e(sCRAPE, "[recovery] HTTP $httpStatus while a cooldown " +
                                        "is already pending — ignoring (not spending a retry)")
                                return
                            }
                            // ESCALATE TO RE-EXTRACTION ONCE WAITING HAS CLEARLY
                            // FAILED. MEASURED 2026-08-28, mid-freeze, sampling 40
                            // distinct segments of the manifest the player was stuck
                            // on: 13 answered 200 and 27 answered 403, and an
                            // immediate retry of eight failures recovered NONE of
                            // them. That manifest was not throttled or expired — two
                            // thirds of it was simply gone, permanently.
                            //
                            // A fresh extraction at the same moment returned a
                            // different host (watami rather than piltover) serving
                            // 6 of 8 sampled segments. So the remedy for a rotten
                            // manifest is a different manifest, and no amount of
                            // patience substitutes for it.
                            //
                            // WHY COOLDOWN STILL COMES FIRST: a genuine rate-limit
                            // does exist and does clear on its own, and re-extracting
                            // into one costs a browser run to be refused again. Two
                            // waits separate "asked to slow down" from "this copy is
                            // broken"; after that, waiting longer is just a slower
                            // way to fail. reExtractAndResume refuses to restart into
                            // an unchanged url, so the escalation cannot loop.
                            if (cooldownRetries >= 2 && reTmdb != null) {
                                Log.e(sCRAPE, "[recovery] $cooldownRetries cooldowns did not " +
                                        "clear HTTP $httpStatus — this manifest looks broken, " +
                                        "re-extracting for a different one")
                                cooldownRetries = 0
                                cooldownPending = false
                                reExtractAndResume("stale-manifest-$httpStatus")
                            } else if (cooldownRetries < MAX_COOLDOWN_RETRIES) {
                                cooldownRetries++
                                val waitMs = COOLDOWN_BASE_MS shl (cooldownRetries - 1)
                                Log.e(sCRAPE, "[recovery] HTTP $httpStatus — cooling down " +
                                        "${waitMs / 1000}s ($cooldownRetries/$MAX_COOLDOWN_RETRIES), " +
                                        "same url, NOT re-extracting")
                                Toast.makeText(this@MiruroPlayerActivity,
                                    "Server is throttling — retrying in ${waitMs / 1000}s…",
                                    Toast.LENGTH_SHORT).show()
                                cooldownPending = true
                                lifecycleScope.launch {
                                    delay(waitMs)
                                    // prepare() replays the SAME source. The url was
                                    // never the problem, so there is nothing to rebuild.
                                    runCatching { player?.prepare() }
                                    // Cleared only AFTER prepare, so errors arriving
                                    // during the wait cannot spend another retry.
                                    cooldownPending = false
                                }
                            } else {
                                Log.e(sCRAPE, "[recovery] still $httpStatus after " +
                                        "$MAX_COOLDOWN_RETRIES cooldowns — giving up on this source")
                                abandonSourceWithNotice()
                            }
                        } else if (isNetworkFailure(error)) {
                            // Nothing answered. A fresh url can plausibly help here, and
                            // reExtractAndResume refuses to restart into an unchanged one.
                            reExtractAndResume(error.errorCodeName)
                        } else {
                            // 4000s are decode/renderer faults. A fresh URL would
                            // decode exactly as badly, so re-extracting would spend a
                            // browser run to reproduce the same failure.
                            Log.e(sCRAPE, "[recovery] ${error.errorCodeName} is not a " +
                                    "network fault — re-extraction cannot help")
                            abandonSourceWithNotice()
                        }
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

                // Episode over — close rather than sit on a finished player.
                // See the equivalent branch in HlsPlayerActivity for the full
                // reasoning; the short version is that an unattended anime player
                // at STATE_ENDED held a household streaming slot indefinitely and
                // kept the TV's screen awake doing it.
                if (state == Player.STATE_ENDED && player?.isCurrentMediaItemLive != true) {
                    Log.i(sCRAPE, "🏁 episode ended — closing player")
                    IdleWatcher.setPlaying(IdleWatcher.SOURCE_MIRURO, false)
                    finish()
                }
            }

            // ✅ THE watched signal for anime. Fires only once video is genuinely
            // rolling — not when the user picked DuB/SuB, not when a server was
            // chosen, not merely when this activity launched. A failed extract, a
            // dead server, or a stream that never starts therefore never marks the
            // episode watched. Guarded by `watchLogged` so a pause/resume can't
            // log the same episode twice.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Suppress the plain idle release while frames are flowing, and arm
                // the longer still-watching check instead. The pause hook has to
                // hop to the main thread — ExoPlayer is single-threaded and
                // IdleWatcher ticks on IO.
                IdleWatcher.setPlaying(IdleWatcher.SOURCE_MIRURO, isPlaying)

                // FRAMES ARE FLOWING, SO THE PENALTY BOX IS BEHIND US. Without this
                // the budget is a per-launch total, and a two-hour film that gets
                // throttled four separate times would be abandoned on the fourth
                // even though every previous cooldown did exactly its job.
                if (isPlaying && cooldownRetries > 0) {
                    Log.e(sCRAPE, "[recovery] playback resumed — clearing the cooldown budget")
                    cooldownRetries = 0
                    cooldownPending = false
                }

                if (isPlaying && !watchLogged) {
                    watchLogged = true
                    logWatchOnPlaybackConfirmed()
                }
            }

            // LOGGING ONLY, AND SECOND. Recovery lives in the listener added
            // earlier (see reExtractAndResume) — Player.Listener callbacks run in
            // registration order, so that one has already decided what to do by the
            // time this line prints. Kept because the message is the human-readable
            // one, but do not add behaviour here: two listeners both acting on the
            // same error is how you get a re-extraction racing a relay switch.
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

                    // "Still watching?" — LAST, so it draws above the quality
                    // overlay. Takes no focus and consumes no input; any remote
                    // press dismisses it through the window hook in
                    // BlueHiveApplication.
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
        // Applied HERE because the player is already released, so no request
        // is in flight and the engine can be shut down properly. Rebuilding
        // mid-playback leaked engines until Cronet stopped building at all.
        ExoPlayerConfig.applyPendingQuicHints()
        Log.d(sCRAPE, "🧹 MiruroPlayerActivity onDestroy — releasing player")
        // Unconditionally and first: onIsPlayingChanged is not guaranteed to fire
        // false on the way out, and a source stuck in the playing set suppresses
        // the idle timer for the rest of the process lifetime.
        IdleWatcher.setPlaying(IdleWatcher.SOURCE_MIRURO, false)
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