package com.example.bluehive.webview.hls

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.annotation.SuppressLint
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.RelayGrantRequest
import com.example.bluehive.auth.SessionManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.net.URLEncoder
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.example.bluehive.BuildConfig
import com.example.bluehive.IdleWatcher
import com.example.bluehive.webview.exoplayer_resources.ExoPlayerConfig
import com.example.bluehive.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import kotlin.math.abs
import java.util.concurrent.TimeUnit
import com.example.bluehive.api.extractStreamAwait

/**
 * Plays an extracted HLS (m3u8) stream in ExoPlayer. Source-agnostic —
 * VidApi, VidFast and anything else that extracts to a manifest use this.
 *
 * Captions (DEV STATE): vaplayer ships none, so the CC button fetches an English
 * subtitle ON DEMAND from OpenSubtitles (see [HlsSubtitles]), keyed by tmdb id.
 *
 * We render captions OURSELVES — parse the .vtt into timed cues and drive a
 * [SubtitleView] from a position ticker — instead of sideloading them into
 * ExoPlayer. That buys a true render-time timing offset: shifting is instant,
 * needs no reload/rebuffer, and stays correct across pause / seek / fast-forward /
 * rewind because the active cue is recomputed from the playback clock every tick.
 * The offset is adjustable from real buttons in the control bar and is remembered
 * per title (SharedPreferences), so a title is synced once and stays synced.
 *
 * On a fatal CDN error, re-runs extraction for a fresh token/node (MAX_EXTRACT_RETRIES).
 */
@UnstableApi
class HlsPlayerActivity : ComponentActivity() {

    private val TAG = "scraper-vidapi"

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var trackSelector: DefaultTrackSelector? = null
    /** Cues on screen right now. Compose state, so the live SubtitleView renders
     *  them — see the AndroidView in setContent. */
    private var activeCues by mutableStateOf<List<Cue>>(emptyList())

    // --- failover state ---
    // Per centre press: did the DOWN go to PlayerView's controller (so let the UP
    // through too) or did we handle it as a play/pause toggle (so swallow the UP)?
    // Decided on DOWN and reused on UP, so the controls appearing between the two
    // can't split one press across both paths.
    private var centerWentToController = false

    private var loggedDuration = false
    private var extractRetries = 0
    /** Counts 429 back-offs. Separate from extractRetries: a rate limit is not a
     *  failed extraction and must not consume the re-extraction budget. */
    private var rateLimitRetries = 0

    // Backend relay for this stream, from /api/extract's proxy_url. Null when the
    // caller didn't pass one, in which case behaviour is exactly as before.
    private var relayUrl: String? = null
    private var usingRelay = false

    /**
     * elapsedRealtime at the start of the DIRECT attempt, 0 before one begins.
     *
     * Reported to the relay as direct_ms. Without it relay_sessions records when
     * the RELAY started and nothing about the attempt it replaced, so a host that
     * failed instantly and one that limped for thirty seconds before giving up are
     * the same row — and they are not the same problem.
     */
    private var directStartedAtMs = 0L

    /**
     * Forces the container interpretation after one has been proven wrong.
     *
     * Null means "believe the backend flag, then the url heuristic", which is the
     * normal path. Set only by the container-parse retry below.
     */
    private var forcedUseHls: Boolean? = null

    /** Which interpretation the current media source was actually built with. */
    private var lastUsedHls: Boolean = true

    /** One container-parse retry per playback. See the handler for why exactly one. */
    private var containerRetried = false

    /**
     * The FIRST url this playback tried, kept after a relay overwrites currentM3u8.
     *
     * Both reports describe the direct attempt, not the relay: the host worth
     * recording is the CDN we tried to reach, and once switchToRelay runs
     * currentM3u8 points at our own backend, whose transport is always going to
     * look healthy.
     */
    private var directUrl: String = ""

    /**
     * Guards the one playback_start report per playback.
     *
     * NOT the existing loggedDuration flag, which switchToRelay deliberately
     * resets so the relayed stream logs its own duration. Reusing it would post a
     * second report for the same playback every time one relayed, and the rate
     * this row exists to compute would count those plays twice.
     */
    private var reportedPlaybackStart = false

    // One watch-history row per launch, written on confirmed playback (see
    // onIsPlayingChanged). The flag survives a mid-play re-extract, so recovering
    // from a dead CDN doesn't log the same title twice.
    private var watchLogged = false

    /**
     * Stream URLs already known to be dead this session.
     *
     * The retry budget exists because the segment CDN is drawn from a POOL — a
     * re-extraction usually yields a different host, so a failure is normally one
     * bad draw rather than a dead title. That reasoning collapses when the source
     * keeps handing back the SAME url: on 2026-08-17 VidNest deleted a film's file
     * and a full 21.5s browser re-extraction returned the identical dead link, so
     * the app burned every retry plus a relay attempt re-fetching one 404 for
     * ~90 seconds before saying anything.
     *
     * Recording what has already failed makes the two cases distinguishable: a new
     * URL is still worth a try, a repeat never is.
     */
    private val failedStreamUrls = mutableSetOf<String>()

    /**
     * Give up on this source and say so, rather than closing silently.
     *
     * Lifted out of onPlayerError so the fast-fail paths below end exactly the way
     * the retry-exhausted path always has — one message, one behaviour.
     */
    private fun abandonSourceWithNotice() {
        showToast("Couldn't reach this episode's server. Try another source.")
        lifecycleScope.launch { delay(2_500); finish() }
    }

    /** Row id from logWatchHistory — the handle progress/stop are addressed to. */
    private var watchId: Int? = null
    private var progressJob: kotlinx.coroutines.Job? = null
    private var recoveryWatchdogJob: kotlinx.coroutines.Job? = null
    private var progressiveRefreshJob: kotlinx.coroutines.Job? = null
    // The stall watchdog polls ONE player instance. startPlayback rebuilds the
    // player on every recovery, so the previous loop is cancelled before the
    // player it holds is released. Before this was tracked, each rebuild left
    // another 1 Hz loop polling a dead player until the Activity died.
    private var stallWatchdogJob: kotlinx.coroutines.Job? = null

    /**
     * Always-visible recovery breadcrumb.
     *
     * Log.e ON PURPOSE, for a path that is not an error. proguard-rules.pro strips
     * v/d/i/w from release builds and keeps only e/wtf, so anything logged at w or
     * below is invisible in exactly the situation that needs explaining. The
     * recovery path was logged entirely at w/d, which is why a stream that died at
     * 59:02 and then silently failed to restart left no trace at all.
     *
     * These lines are rare — a handful per failure, none during healthy playback —
     * so the cost of keeping them is a few strings in the APK.
     */
    private fun recoveryLog(msg: String) = Log.e(TAG, "[recovery] $msg")

    /**
     * True when the current URL is a single file rather than a playlist.
     *
     * Same test buildMediaSource uses to pick the media source, lifted out so the
     * refresh scheduler cannot disagree with the player about what it is playing.
     */
    private fun looksProgressive(url: String): Boolean = runCatching {
        val path = android.net.Uri.parse(url).path.orEmpty().lowercase()
        val lower = url.lowercase()
        !path.endsWith(".m3u8") && !path.endsWith(".mpd") &&
            !lower.contains(".m3u8") && !lower.contains(".mpd")
    }.getOrDefault(false)

    /** POST the watch-history row. Fire-and-forget: never block or fail playback. */
    private fun logWatchOnPlaybackConfirmed() {
        val profileId = intent.getIntExtra("PROFILE_ID", -1)
        val mediaId   = intent.getIntExtra("TMDB_ID", 0)
        if (profileId == -1 || mediaId == 0) {
            Log.w(TAG, "▶️ playback confirmed but PROFILE_ID/TMDB_ID missing — not logging")
            return
        }
        val mediaType = intent.getStringExtra("MEDIA_TYPE") ?: "movie"
        val season    = intent.getIntExtra("SEASON_NUMBER", 0).takeIf { it > 0 }
        val episode   = intent.getIntExtra("EPISODE_NUMBER", 0).takeIf { it > 0 }
        val title     = intent.getStringExtra("MEDIA_TITLE")
        val epName    = intent.getStringExtra("EPISODE_NAME")
        val source    = intent.getStringExtra("SOURCE_NAME") ?: "HLS"

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


        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val row = com.example.bluehive.api.ApiClient.bluehiveApi.logWatchHistory(
                    com.example.bluehive.api.WatchHistoryRequest(
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
                // The RETURNED ID WAS PREVIOUSLY DISCARDED, and that was the whole
                // reason no position was ever recorded: without it the client had
                // no handle to report progress against or to close the session
                // with, so the backend was left to guess and filled
                // progress_seconds with the row's wall-clock age instead.
                watchId = row.id
                startProgressHeartbeat()
                Log.d(TAG, "✅ Watch history logged on PLAYBACK: tmdb=$mediaId ($source) id=${row.id}")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Watch-history log failed (non-fatal): ${e.message}")
            }
        }
    }

    /**
     * Report the playback position every PROGRESS_HEARTBEAT_MS while playing.
     *
     * Heartbeats rather than a single write at teardown, because teardown is the
     * one moment least likely to happen: the process can be killed, the TV can
     * lose power, the player can be released by a recovery path that never comes
     * back. Anything written only at the end is lost precisely when it matters.
     *
     * currentPosition must be read on the main thread — ExoPlayer asserts its
     * access thread — while the POST must not be, hence the hop.
     */
    private fun startProgressHeartbeat() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(PROGRESS_HEARTBEAT_MS)
                val id = watchId ?: continue
                val positionSec = runCatching {
                    if (player?.isPlaying == true) player?.currentPosition else null
                }.getOrNull()?.takeIf { it > 0 }?.let { (it / 1000).toInt() } ?: continue
                runCatching {
                    withContext(Dispatchers.IO) {
                        com.example.bluehive.api.ApiClient.bluehiveApi.updateWatchProgress(
                            id, com.example.bluehive.api.WatchProgressRequest(positionSec)
                        )
                    }
                }
            }
        }
    }

    /**
     * Close the watch session with a final position.
     *
     * Runs on a detached scope, NOT lifecycleScope: this fires from onDestroy, and
     * a lifecycleScope job is already cancelled by then, so the call would never
     * leave the device.
     */
    private fun stopWatchSession() {
        val id = watchId ?: return
        watchId = null
        val positionSec = runCatching { player?.currentPosition }
            .getOrNull()?.takeIf { it > 0 }?.let { (it / 1000).toInt() } ?: 0
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                com.example.bluehive.api.ApiClient.bluehiveApi.stopWatchHistory(
                    id, com.example.bluehive.api.WatchProgressRequest(positionSec)
                )
            }
        }
    }

    /**
     * Watch a recovery attempt and report it if playback never resumes.
     *
     * Cancelled by onIsPlayingChanged the moment frames flow, so a recovery that
     * works costs one timer and reports nothing.
     */
    private fun armRecoveryWatchdog(what: String) {
        recoveryWatchdogJob?.cancel()
        recoveryWatchdogJob = lifecycleScope.launch {
            delay(RECOVERY_WATCHDOG_MS)
            if (player?.isPlaying == true) return@launch
            recoveryLog("STALLED after $what — no playback ${RECOVERY_WATCHDOG_MS}ms later")
            com.example.bluehive.diagnostics.CrashReporter.reportPlaybackError(
                context = this@HlsPlayerActivity,
                errorCodeName = "RECOVERY_STALLED",
                message = "recovery produced no playback",
                cause = what,
                extraMeta = mapOf(
                    "stage"           to what,
                    "position_ms"     to runCatching { player?.currentPosition }.getOrNull(),
                    "player_null"     to (player == null),
                    "using_relay"     to usingRelay,
                    "extract_retries" to extractRetries,
                    "embed_url"       to embedUrl,
                    "current_url"     to currentM3u8.take(200),
                ),
            )
        }
    }

    /**
     * Re-extract a progressive stream before its URL expires.
     *
     * Only armed for single-file sources — an HLS playlist refreshes itself, so
     * scheduling this for one would burn an extraction for nothing.
     */
    private fun scheduleProgressiveRefresh(url: String) {
        progressiveRefreshJob?.cancel()
        if (!looksProgressive(url)) return
        progressiveRefreshJob = lifecycleScope.launch {
            delay(PROGRESSIVE_REFRESH_MS)
            refreshProgressiveInPlace()
        }
    }

    /**
     * Swap a progressive stream onto a fresh URL WITHOUT tearing down the working
     * one first.
     *
     * REWRITTEN before shipping. The first version just called
     * relaunchExtraction(), and that was a bad trade: relaunchExtraction releases
     * the player and only then goes looking for a replacement, which routes a
     * healthy stream straight through startPlayback() — the very call that
     * silently produced nothing on 2026-08-17 and left a live Activity with a dead
     * player. That would have moved the failure from ~59 minutes to 50 and applied
     * it to EVERY long film rather than only ones outliving their URL. A refresh
     * that can break playback which was working is worse than no refresh.
     *
     * So the order is inverted. The current stream keeps playing untouched while a
     * replacement is fetched AND probed; only once there is a URL known to answer
     * do we spend the position and rebuild. Every failure path leaves playback
     * exactly as it was and tries again later, so the worst case is the old
     * behaviour — a 404 at expiry, handled by the existing recovery — and never a
     * stream killed by its own maintenance.
     *
     * Deliberately does NOT spend extractRetries: that budget belongs to real
     * failures, and a scheduled refresh must not eat the attempts a genuine CDN
     * error will need later.
     */
    private suspend fun refreshProgressiveInPlace() {
        if (player == null) return
        val tmdb = intent.getIntExtra("TMDB_ID", 0)
        if (tmdb <= 0) return

        val type    = intent.getStringExtra("MEDIA_TYPE") ?: "movie"
        val season  = intent.getIntExtra("SEASON_NUMBER", 0).takeIf { it > 0 }
        val episode = intent.getIntExtra("EPISODE_NUMBER", 0).takeIf { it > 0 }
        val source  = intent.getStringExtra("EXTRACT_SOURCE") ?: "vidfast"

        recoveryLog("pre-emptive refresh: fetching a replacement URL (player untouched)")

        val body = runCatching {
            ApiClient.bluehiveApi.extractStreamAwait(
                type = type, tmdbId = tmdb, season = season, episode = episode,
                source = source, refresh = 1, mtype = type,
                title = intent.getStringExtra("MEDIA_TITLE"),
                profile = intent.getIntExtra("PROFILE_ID", -1).takeIf { it > 0 },
                device = SessionManager.get().deviceFingerprint,
            )
        }.getOrNull()?.takeIf { it.isSuccessful }?.body()

        if (body == null || body.m3u8.isBlank()) {
            recoveryLog("pre-emptive refresh: no manifest — KEEPING current stream, retrying later")
            rescheduleProgressiveRetry(); return
        }

        // PROBE BEFORE COMMITTING. A 200 from our backend means extraction ran, not
        // that the URL it handed back actually serves bytes. Without this the swap
        // could replace a playing stream with a dead one — precisely the risk this
        // rewrite exists to remove. One ranged request is far cheaper than a second
        // ExoPlayer instance on a memory-tight TV box.
        if (!probeStreamReachable(
                body.m3u8,
                probeUa = body.user_agent?.takeIf { it.isNotBlank() } ?: ua,
                probeReferer = body.referer?.takeIf { it.isNotBlank() } ?: referer,
            )
        ) {
            recoveryLog("pre-emptive refresh: replacement URL did not answer — KEEPING current stream")
            rescheduleProgressiveRetry(); return
        }

        // Committed. Everything from here mirrors a normal recovery swap.
        captureResumeState()
        body.referer?.takeIf { it.isNotBlank() }?.let { referer = it }
        body.user_agent?.takeIf { it.isNotBlank() }?.let { ua = it }
        body.proxy_url?.takeIf { it.isNotBlank() }?.let { relayUrl = it }
        usingRelay = false
        loggedDuration = false

        recoveryLog("pre-emptive refresh: replacement verified — swapping at ${resumePositionMs}ms")
        startPlayback(body.m3u8)
        armRecoveryWatchdog("progressive-refresh")
    }

    /**
     * A failed refresh is not fatal — the current URL is still serving. Try again
     * before the real expiry rather than waiting for the 404.
     */
    private fun rescheduleProgressiveRetry() {
        progressiveRefreshJob?.cancel()
        progressiveRefreshJob = lifecycleScope.launch {
            delay(PROGRESSIVE_REFRESH_RETRY_MS)
            refreshProgressiveInPlace()
        }
    }

    /**
     * Cheap liveness check: does this URL return bytes?
     *
     * A ranged GET rather than HEAD — several of these hosts answer HEAD with 405
     * or a misleading 200 while the real GET 404s, which would defeat the point.
     * Carries the same Referer/UA the player will use, because some of them gate on
     * exactly that.
     *
     * THROUGH THE SAME TRANSPORT AS PLAYBACK, which is the whole point. This used
     * to be a java.net.HttpURLConnection: system DNS, plaintext SNI, TCP only. On
     * an SNI-filtered line that is exactly the request the filter severs, so a
     * fresh URL that Cronet with QUIC and ECH could play was judged dead, added
     * to failedStreamUrls, charged against extractRetries and ultimately ended in
     * abandonSourceWithNotice. It also cost up to 20 s of its own timeouts. Now
     * the probe opens a two-byte ranged request on the data source playback will
     * use, so its verdict and the player's outcome cannot disagree.
     *
     * [probeUa] / [probeReferer] default to the current stream's values; pass the
     * candidate's own when the backend supplied new ones, since those are what
     * the player will send once the URL is committed.
     */
    private suspend fun probeStreamReachable(
        url: String,
        probeUa: String = ua,
        probeReferer: String = referer,
    ): Boolean = withContext(Dispatchers.IO) {
        val source = streamDataSourceFactory(url, probeUa, probeReferer).createDataSource()
        try {
            // position 0, length 2 -> "Range: bytes=0-1". open() returns once the
            // response headers are in and throws on anything outside 2xx, which
            // is the same yes/no the old responseCode check gave.
            source.open(DataSpec.Builder().setUri(url).setPosition(0).setLength(2).build())
            true
        } catch (e: Exception) {
            Log.d(TAG, "probe: candidate did not answer (${e.javaClass.simpleName}: ${e.message})")
            false
        } finally {
            runCatching { source.close() }
        }
    }

    // --- stream params (kept so we can rebuild on retry) ---
    private var embedUrl: String? = null
    private var currentM3u8: String = ""
    private var resumePositionMs: Long = 0

    /**
     * Should the rebuilt player start playing? Travels with [resumePositionMs].
     *
     * Defaults TRUE so the first build, which has no previous player to ask,
     * behaves exactly as before. Every rebuild path overwrites it through
     * [captureResumeState], and startPlayback resets it to true once consumed —
     * so a stale false can never leak into a later, unrelated build.
     */
    private var resumePlayWhenReady: Boolean = true

    /**
     * Snapshot what a rebuild has to carry across: where we were, and whether we
     * were watching.
     *
     * ONE HELPER BECAUSE THERE ARE FOUR CALLERS and there was nearly a fifth.
     * The position was already being copied at all four — the progressive
     * refresh, the container retry, the relay switch and re-extraction — and the
     * play/pause state at none of them, which is precisely the kind of omission
     * that happens when the same three lines are written out four times.
     */
    private fun captureResumeState() {
        player?.let {
            if (it.currentPosition > 0) resumePositionMs = it.currentPosition
            resumePlayWhenReady = it.playWhenReady
        }
    }
    // EMPTY BY DEFAULT, so "no Referer" is expressible.
    //
    // This was "https://cinesrc.st/". Combined with the `?:` reads below, a null
    // REFERER extra silently became that hardcoded value — so when the backend
    // deliberately suppresses the header for a source whose CDN 403s it, the
    // client sent cinesrc.st anyway. MEASURED 2026-08-30: the backend logged
    // "referer suppressed for moviesapi" and returned null, and NetLog shows the
    // very next segment going out with `referer: https://cinesrc.st/` and coming
    // back 403. The suppression could not have worked while this default existed.
    private var referer: String = ""
    private var ua: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // --- captions (dev state, self-rendered) ---
    private var tmdbId: Int = 0
    // Subtitle the EXTRACTOR pulled off the streaming site (SUBTITLE_URL extra).
    // Preferred over OpenSubtitles: it's this exact release's caption track, so
    // it's already in sync and needs no imdb lookup.
    private var streamSubtitleUrl: String? = null
    private var parsedCues: List<TimedCue> = emptyList()
    private var captionsLoaded = false
    private var captionsOn = false
    private var lastCueKey: String = "\u0000"
    private var toastText by mutableStateOf<String?>(null)
    private var toastJob: Job? = null
    private var captionTickerJob: Job? = null

    // --- English-subtitle picker (CC button) ---
    // OpenSubtitles lists several English subtitles per episode, each timed to a
    // different rip, and only one matches what we're streaming. The picker shows
    // them all so a drifting one can be swapped out mid-episode.
    private var pickerOpen by mutableStateOf(false)
    private var pickerLoading by mutableStateOf(false)
    private var pickerOptions by mutableStateOf<List<HlsSubtitles.SubtitleChoice>>(emptyList())
    private var pickerError by mutableStateOf<String?>(null)
    /** EnglishOption.index currently rendering, or -1 when captions are off. */
    private var activeSubtitleIndex by mutableStateOf(-1)
    /** True only if WE paused to show the picker, so closing it can't resume a
     *  video the user had deliberately paused before pressing CC. */
    private var pausedForPicker = false

    /**
     * Was playback running when this activity last went to the background?
     *
     * THE SAME RULE AS pausedForPicker, applied to the lifecycle. onResume used
     * to call play() unconditionally, so anything that backgrounded the activity
     * and came back resumed a film the user had deliberately paused. The one
     * trigger that is certain to do that on a television is the daydream: this
     * box has screensaver_enabled=1 and screen_off_timeout=600000, so pausing
     * and sitting still for ten minutes backgrounds the app, and the key press
     * that dismisses the screensaver brings it back — playing.
     *
     * playWhenReady, NOT isPlaying. isPlaying is briefly false mid-buffer (see
     * ExoPlayerConfig.togglePlayPause, which flips playWhenReady for exactly
     * this reason), so pausing during a stall would record "was not playing" and
     * the film would stay paused on return — trading one wrong behaviour for
     * another. playWhenReady is the user's intent rather than the decoder's
     * moment-to-moment state.
     */
    private var wasPlayingBeforePause = false

    // Subtitle timing offset in ms (+ = show later, − = show earlier), persisted per title.
    private var subtitleOffsetMs: Long = 0
    private var subOffsetLabel: TextView? = null
    private var ccButton: ImageButton? = null
    private val subPrefs by lazy { getSharedPreferences("vidapi_sub_offsets", MODE_PRIVATE) }

    /**
     * Subtitle RATE correction — the one fix the offset buttons cannot perform.
     *
     * An offset is a constant shift, and it handles the common case: the release a
     * subtitle was timed to had a different amount of distributor logo before the
     * first frame. It cannot touch the other case. A subtitle authored against a
     * 25fps PAL transfer, rendered over a 23.976fps source, is wrong by a RATIO
     * rather than a constant — 4.27%, which is ~3m50s by the end of a feature.
     * Sync the opening and the ending breaks; sync the ending and the opening
     * does. No number of nudges fixes it, because the error GROWS.
     *
     * So cue times are now scaled as well as shifted: videoTime = cue*rate + offset.
     * Because we render cues ourselves off the playback clock (see the class
     * header) this costs one division per tick and applies instantly, mid-film,
     * with no reload — the same property that made the offset buttons possible.
     *
     * NOBODY OPERATES THIS. There is no drift button, and the words "framerate"
     * and "drift" appear nowhere in the UI. The rate is MEASURED from the ◀ / ▶
     * nudges the viewer was already going to make — see [recordSyncPoint].
     *
     * That deletion is the whole design. The button this replaces made the
     * viewer diagnose their own problem first ("is this off by a constant, or by
     * a ratio?") before it could tell them which control to reach for. That is a
     * distinction for someone who knows what telecine is, and asking for it is
     * what made the feature unusable rather than the number of buttons.
     *
     * 1.0 means no correction, and must keep meaning exactly that — see
     * renderActiveCues.
     */
    private var subtitleRate: Double = 1.0

    /**
     * One "this looks right HERE" observation: the playback position, and the
     * offset the viewer settled on at it.
     *
     * Settled is the load-bearing word. Reaching +2.00s takes eight presses, and
     * the seven offsets on the way there are not answers to anything — they are
     * someone still turning the dial. Only the value they stop at is data, which
     * is why these are recorded on a debounce rather than per press.
     */
    private data class SyncPoint(val positionMs: Long, val offsetMs: Long)

    private var lastSyncPoint: SyncPoint? = null
    private var syncSettleJob: Job? = null

    /** How long the nudging must stop before an offset counts as settled. */
    private val SYNC_SETTLE_MS = 3_000L

    /**
     * How far apart two observations must be before their slope means anything.
     *
     * FIFTEEN minutes, and the number is measured rather than picked. Each
     * observation carries about ±250ms of error — the nudge step is 250ms and
     * nobody's eye is better than that anyway — so the error in the SLOPE is
     * roughly 500ms divided by the span. A short arm turns that into a large
     * rate error applied across the whole film:
     *
     *     span    worst residual, simulated, on a 36s-per-2h drift
     *      5 min   5.6s   ← worse than some of what it was fixing
     *     10 min   3.0s
     *     15 min   2.0s   ← the knee
     *     30 min   1.1s
     *
     * Five minutes was the first guess and the simulation threw it out.
     */
    private val SYNC_MIN_SPAN_MS = 15 * 60_000L

    /** Sanity band for a measured rate. Outside it, the pair was not drift. */
    private val SYNC_RATE_MIN = 0.90
    private val SYNC_RATE_MAX = 1.10

    /**
     * The two settled offsets must differ by at least this much.
     *
     * This is the noise floor, stated in the only units that matter. Two
     * observations 250ms apart are indistinguishable from the same observation
     * made twice, and dividing that by the span yields a confident-looking rate
     * built out of rounding. Below 1.5s we are not measuring drift, so we leave
     * the film alone — at which point the drift is under ~12s across a feature,
     * which the ◀ / ▶ buttons already handle.
     */
    private val SYNC_MIN_OFFSET_DELTA_MS = 1_500L

    private lateinit var reextractLauncher: ActivityResultLauncher<Intent>

    private data class TimedCue(val startMs: Long, val endMs: Long, val cue: Cue)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("M3U8_URL")
        if (url.isNullOrBlank()) {
            Log.e(TAG, "HlsPlayerActivity launched without M3U8_URL — finishing")
            finish(); return
        }

        embedUrl = intent.getStringExtra("EMBED_URL")
        relayUrl = intent.getStringExtra("M3U8_FALLBACK_URL")
        referer  = intent.getStringExtra("REFERER") ?: referer
        ua       = intent.getStringExtra("UA") ?: ua
        tmdbId   = intent.getIntExtra("TMDB_ID", 0)
        streamSubtitleUrl = intent.getStringExtra("SUBTITLE_URL")
        if (!streamSubtitleUrl.isNullOrBlank())
            Log.d(TAG, "subs: stream subtitle available → ${streamSubtitleUrl!!.take(90)}")

        // Restore the saved timing for this title (sync once, remembered). The
        // rate index is clamped: a value stored by a build with a different rate
        // table would otherwise be an out-of-bounds read on every caption tick.
        subtitleOffsetMs = if (tmdbId > 0) subPrefs.getLong(tmdbId.toString(), 0L) else 0L
        // Clamped on the way in: a rate outside the band would make every cue
        // lookup wrong for the whole film, and prefs outlive the code that wrote
        // them. The key is deliberately NOT the "_rate" one an earlier build used
        // for a preset INDEX — getFloat on an int-typed key throws.
        subtitleRate = if (tmdbId > 0)
            subPrefs.getFloat("${tmdbId}_ratef", 1f).toDouble()
                .coerceIn(SYNC_RATE_MIN, SYNC_RATE_MAX) else 1.0
        if (subtitleOffsetMs != 0L || subtitleRate != 1.0)
            Log.d(TAG, "subs: restored saved timing ${subtitleOffsetMs}ms " +
                    "rate=${"%.5f".format(subtitleRate)} for tmdb=$tmdbId")

        reextractLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            val newUrl = data?.getStringExtra(HlsExtract.RESULT_M3U8)
            if (result.resultCode == RESULT_OK && !newUrl.isNullOrBlank()) {
                referer = data.getStringExtra(HlsExtract.RESULT_REFERER) ?: referer
                ua      = data.getStringExtra(HlsExtract.RESULT_UA) ?: ua
                Log.d(TAG, "✅ re-extraction succeeded — restarting playback on fresh node")
                loggedDuration = false
                startPlayback(newUrl)
            } else {
                val reason = data?.getStringExtra(HlsExtract.RESULT_REASON) ?: "unknown"
                Log.e(TAG, "❌ re-extraction failed ($reason) — finishing")
                finish()
            }
        }

        startPlayback(url)
    }

    /** Builds (or rebuilds) the ExoPlayer for a given m3u8. Safe to call on retry. */
    private fun startPlayback(url: String) {
        currentM3u8 = url
        // Start of the DIRECT attempt, for the relay report's direct_ms. Only the
        // direct path arms it: re-arming on the relay's own startPlayback would
        // measure the relay against itself and report ~0ms every time. Left alone
        // once set, so a re-extraction that is still direct keeps the original
        // clock — the number worth having is "how long before bytes arrived", not
        // "how long since the most recent attempt".
        if (!usingRelay && directStartedAtMs == 0L) {
            directStartedAtMs = android.os.SystemClock.elapsedRealtime()
            directUrl = url
        }
        Log.d(TAG, "▶️ playing vidapi m3u8: $url (referer=$referer)")
        // Re-armed on EVERY startPlayback, including recovery rebuilds: a fresh
        // URL restarts the expiry clock, so the old timer would fire early and the
        // new one would never be set at all.
        scheduleProgressiveRefresh(url)

        val mediaSource = buildMediaSource(url)

        // Release any previous instance before rebuilding (retry path). The
        // watchdog goes first: it holds the OLD player and runs on this same
        // thread, so cancelling here guarantees it never touches a released one.
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null
        playerView?.player = null
        player?.run { stop(); clearMediaItems(); release() }

        // Buffering / bitrate / player construction all come from the shared
        // ExoPlayerConfig so every site's player behaves identically. Only this
        // file's HEADERS are site-specific (see buildMediaSource).
        // A TrackSelector belongs to exactly ONE player. Media3's TrackSelector.init()
        // fails a checkState if the same instance is handed to a second one, and
        // this function REBUILDS the player on every retry path — mid-play
        // re-extraction and the relay fallback both land here. Caching the selector
        // across those rebuilds crashed the app outright:
        //
        //   FATAL java.lang.IllegalStateException
        //     at TrackSelector.init(TrackSelector.java:145)
        //     at ExoPlayer$Builder.build → startPlayback → reextractViaBackend
        //
        // It only surfaced once a re-extraction actually SUCCEEDED and got as far
        // as rebuilding; every earlier failure died before this line.
        val selector = ExoPlayerConfig.trackSelector(this).also { trackSelector = it }
        ExoPlayerConfig.applyVideoPolicy(selector)

        player = ExoPlayerConfig.buildPlayer(this, selector)
            .apply {
                // Diagnostic only: logs the rung actually selected + all rungs offered.
                ExoPlayerConfig.attachVideoTrackLog(this, TAG)
                // Hold the display awake while playback is intended (incl. stalls).
                // Attached here, not at the PlayerView, so a mid-play re-extract
                // that rebuilds the player re-arms it.
                ExoPlayerConfig.keepScreenOnWhilePlaying(this@HlsPlayerActivity, this)
                // State/loading/underrun/dropped-frame trail. A freeze used to be
                // invisible here — it had to be reconstructed from the vendor's
                // codec logs — so these are the lines that name one directly.
                ExoPlayerConfig.attachPlaybackLog(this, TAG)
                // Break the vendor display-stack deadlock that froze playback for
                // ~10 minutes with the player still reporting "playing".
                // onManifestDead: a wedge that survives seeks AND re-prepares is
                // a dead url, not a stuck codec — an expired token presents this
                // way because it hangs instead of erroring, so onPlayerError
                // never fires and nothing below would otherwise recover.
                stallWatchdogJob = ExoPlayerConfig.attachStallWatchdog(this, lifecycleScope, TAG) {
                    // A wedge on a blocked CDN looks like this rather than an
                    // error — the request hangs instead of failing — so the relay
                    // belongs here too.
                    if (switchToRelay("STALL_WEDGED")) return@attachStallWatchdog
                    if (extractRetries < MAX_EXTRACT_RETRIES) {
                        extractRetries++
                        Log.w(TAG, "↻ wedged on a dead manifest — re-extracting " +
                                "($extractRetries/$MAX_EXTRACT_RETRIES)")
                        relaunchExtraction()
                    } else {
                        Log.e(TAG, "🛑 wedged and out of re-extraction attempts — finishing")
                        finish()
                    }
                }
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && !loggedDuration) {
                            loggedDuration = true
                            Log.d(TAG, "⏱ duration=${player?.duration}ms | isLive=${player?.isCurrentMediaItemLive}")
                        }

                        // ── THE DENOMINATOR ──────────────────────────────────
                        //
                        // One row per playback that reached frames. See
                        // CrashReporter.reportPlaybackStart for why this exists
                        // at all; the short version is that a table of failures
                        // cannot produce a rate.
                        //
                        // MEASURES THE DIRECT ATTEMPT, not whatever is playing
                        // now. After a relay, currentM3u8 points at our own
                        // backend and its transport always looks healthy, so
                        // reporting that would make every relayed play look like
                        // a clean h2 200 and defeat the whole point.
                        if (state == Player.STATE_READY && !reportedPlaybackStart) {
                            reportedPlaybackStart = true
                            val probed = directUrl.ifBlank { currentM3u8 }
                            val transport = ExoPlayerConfig.transportFor(probed)
                            val startupMs =
                                if (directStartedAtMs > 0L)
                                    (android.os.SystemClock.elapsedRealtime() - directStartedAtMs).toInt()
                                else -1
                            Log.e(TAG, "📈 playback start: source=${intent.getStringExtra("SOURCE_NAME")} " +
                                    "relayed=$usingRelay http=${transport?.httpStatus ?: -1} " +
                                    "proto=${transport?.protocol ?: "(unknown)"} startupMs=$startupMs")
                            com.example.bluehive.diagnostics.CrashReporter.reportPlaybackStart(
                                context = this@HlsPlayerActivity,
                                extraMeta = mapOf(
                                    "source_name" to intent.getStringExtra("SOURCE_NAME"),
                                    "relayed"     to usingRelay,
                                    // The server derives origin_host from this with
                                    // the same unwrap the relay path uses, then drops
                                    // it before the row is written. A signed CDN url
                                    // is not something to keep in telemetry.
                                    "stream_url"  to probed,
                                    "http_status" to (transport?.httpStatus ?: -1),
                                    "negotiated_protocol" to (transport?.protocol ?: "(unknown)"),
                                    "host_was_new" to ExoPlayerConfig.wasNewlyLearned(probed),
                                    // Recorded on the SUCCESS path too, so a
                                    // network where ECH quietly stopped arming is
                                    // visible before it costs anyone a relay.
                                    "ech_state"    to ExoPlayerConfig.echState(),
                                    "host_publishes_ech" to ExoPlayerConfig.hostPublishesEch(probed),
                                    "startup_ms"  to startupMs,
                                    "media_tmdb_id"  to intent.getIntExtra("TMDB_ID", -1).takeIf { it > 0 },
                                    "media_type"     to intent.getStringExtra("MEDIA_TYPE"),
                                    "season_number"  to intent.getIntExtra("SEASON_NUMBER", -1).takeIf { it > 0 },
                                    "episode_number" to intent.getIntExtra("EPISODE_NUMBER", -1).takeIf { it > 0 },
                                    "media_title"    to intent.getStringExtra("MEDIA_TITLE"),
                                ),
                            )
                        }

                        // ── STATE_ENDED WAS NOT HANDLED AT ALL ────────────────
                        //
                        // The credits rolled and this activity simply stayed up on a
                        // finished player, forever. On a TV that is usually somebody
                        // asleep in front of it: the screen stays on
                        // (keepScreenOnWhilePlaying), the session stays connected,
                        // and the household streaming slot stays occupied until
                        // morning.
                        //
                        // finish() rather than leaving it to IdleWatcher's 30-minute
                        // sweep, because a player at STATE_ENDED is not ambiguous.
                        // Nobody is mid-anything. Waiting half an hour to reclaim a
                        // slot from a stream that is definitively over is pure
                        // waste, and returning to the details screen is what the
                        // viewer would have done themselves.
                        //
                        // isCurrentMediaItemLive is excluded: a live stream can
                        // report ENDED on a transient upstream gap, and closing the
                        // player under someone watching live would be a far worse
                        // bug than the one this fixes.
                        if (state == Player.STATE_ENDED && player?.isCurrentMediaItemLive != true) {
                            Log.i(TAG, "🏁 playback ended — closing player")
                            IdleWatcher.setPlaying(IdleWatcher.SOURCE_HLS, false)
                            finish()
                        }
                    }

                    // ✅ Watch history is written HERE, on confirmed playback — not by
                    // the details screen when extraction succeeded. A captured stream
                    // that never actually plays (dead CDN, failed re-extract, user
                    // backing out) must not count as watched. Guarded so a pause or a
                    // mid-play re-extract can't log the same title twice.
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // Tell the idle timer whether anyone could plausibly be
                        // watching. While this is true the plain 30-minute release
                        // is suppressed and the longer still-watching check applies
                        // instead — a feature film produces no remote input at all,
                        // so an input-only timer would close it mid-scene.
                        //
                        // The pause hook is how IdleWatcher stops playback if the
                        // still-watching prompt goes unanswered. It must run on the
                        // main thread: ExoPlayer is single-threaded and throws if
                        // touched from the watcher's IO tick.
                        IdleWatcher.setPlaying(IdleWatcher.SOURCE_HLS, isPlaying)

                        if (isPlaying) {
                            // Frames are flowing, so whatever recovery was in
                            // flight succeeded — disarm before it files a report.
                            recoveryWatchdogJob?.cancel()
                            // Same reasoning for the rate-limit budget: the 429 we
                            // backed off from has cleared, so a NEW one later in the
                            // episode deserves a full set of retries rather than
                            // inheriting a spent counter and abandoning a stream
                            // that is demonstrably working.
                            rateLimitRetries = 0
                            recoveryWatchdogJob = null
                        }
                        if (isPlaying && !watchLogged) {
                            watchLogged = true
                            logWatchOnPlaybackConfirmed()
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "❌ ExoPlayer error: ${error.errorCodeName} | ${error.message} | cause=${error.cause?.message}")

                        // Report BEFORE any recovery attempt. A retry that
                        // succeeds still hides the fact that something failed —
                        // and a premature end that silently re-extracts looks to
                        // the viewer like a stutter while leaving no trace at all.
                        // This is fire-and-forget; nothing below waits on it.
                        com.example.bluehive.diagnostics.CrashReporter.reportPlaybackError(
                            context = this@HlsPlayerActivity,
                            errorCodeName = error.errorCodeName,
                            message = error.message,
                            cause = error.cause?.message,
                            extraMeta = mapOf(
                                "error_code"      to error.errorCode,
                                // player is nullable and may already be torn down
                                // by the time an error surfaces — position is the
                                // single most useful field here (it says whether
                                // the stream died at the start or 40 minutes in),
                                // so read it defensively rather than skip it.
                                "position_ms"     to runCatching { player?.currentPosition }.getOrNull(),
                                "duration_ms"     to runCatching { player?.duration }.getOrNull(),
                                "extract_retries" to extractRetries,
                                "embed_url"       to embedUrl,
                            ),
                        )

                        // Remember what just died so a re-extraction handing back
                        // the same link is recognisable. Relay URLs carry the real
                        // stream in ?url=, so unwrap it — otherwise the direct and
                        // relayed forms of one dead URL look like two different
                        // ones and the repeat check never fires.
                        runCatching {
                            val direct = if (usingRelay) {
                                android.net.Uri.parse(currentM3u8).getQueryParameter("url")
                            } else currentM3u8
                            direct?.takeIf { it.isNotBlank() }?.let { failedStreamUrls += it }
                        }

                        // ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ADDED 2026-08-16.
                        //
                        // Its absence was why a stalled stream simply ENDED. Every
                        // recovery path below — relay switch, re-extraction, even
                        // the "try another source" toast — is gated on this flag, so
                        // a timeout fell through to finish() and the video closed
                        // mid-episode with no attempt to save it and nothing on
                        // screen to explain why.
                        //
                        // Measured that day: American Dad died at 11:35 of 21:39
                        // with error 2002. The cause was upstream — VidNest proxies
                        // segments through rotating Cloudflare Workers, and the
                        // same 3 KB playlist fetched five times took
                        // 0.4s / 5.5s / 0.2s / 18.4s / 0.6s. A multi-megabyte
                        // segment through an 18-second stall exceeds the 30s read
                        // timeout, and a throttled worker keeps doing it.
                        //
                        // A timeout is a CDN failure in every way that matters here:
                        // the bytes are not arriving from that host, and the fixes
                        // are the same ones — relay through our own server, or
                        // re-extract for a different upstream.
                        // ── 429 IS NOT A DEAD CDN ────────────────────────────
                        // MEASURED 2026-08-18. Evil Dead Burn failed six times in
                        // under a minute, every report carrying
                        // cause="Response code: 429". The CDN was not blocked, not
                        // expired and not gone — it was telling this TV to slow
                        // down, and the handler below answered by re-extracting,
                        // which produced a fresh manifest pointing at the SAME host
                        // and fired yet another request at it. Every retry made the
                        // rate limit worse. It only recovered because the user
                        // waited long enough between attempts for the window to
                        // roll.
                        //
                        // So 429 gets the opposite treatment from every other 2004:
                        // keep the URL, wait, and try it again. Re-extraction cannot
                        // help a rate limit and relaying would move our own server
                        // into the penalty box instead.
                        val httpStatus = generateSequence(error.cause) { it.cause }
                            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                            .firstOrNull()?.responseCode

                        if (httpStatus == 429) {
                            if (rateLimitRetries < MAX_RATE_LIMIT_RETRIES) {
                                rateLimitRetries++
                                // Exponential, because a fixed short delay is just a
                                // slower version of the same mistake: 4s, 8s, 16s.
                                val waitMs = RATE_LIMIT_BASE_DELAY_MS shl (rateLimitRetries - 1)
                                Log.w(TAG, "⏳ HTTP 429 — backing off ${waitMs}ms " +
                                        "(retry $rateLimitRetries/$MAX_RATE_LIMIT_RETRIES), NOT re-extracting")
                                showToast("This server is busy — retrying in ${waitMs / 1000}s…")
                                lifecycleScope.launch {
                                    delay(waitMs)
                                    // Same source, same URL. prepare() re-runs the
                                    // load without minting anything new.
                                    runCatching { player?.prepare() }
                                }
                                return
                            }
                            Log.e(TAG, "🛑 still 429 after $MAX_RATE_LIMIT_RETRIES back-offs — giving up on this source")
                            abandonSourceWithNotice()
                            return
                        }

                        // ── THE LABEL WAS WRONG, NOT THE STREAM ─────────────
                        //
                        // A container error means the bytes arrived and no parser
                        // recognised them. That is overwhelmingly one thing: a
                        // playlist handed to the file parsers, or the reverse. The
                        // fetch worked, so no amount of relaying or re-extracting
                        // helps — and none of them even ran, because this code is
                        // not a CDN failure and fell straight through to
                        // abandonSourceWithNotice.
                        //
                        // MEASURED 2026-09-13 on VidNest: the stream was HLS with
                        // its manifest named master.txt and its segments named
                        // .woff2, so the extractor found no ".m3u8", called it
                        // progressive, and ExoPlayer died on NoDeclaredBrand with
                        // a perfectly good stream on the other end.
                        //
                        // Retrying with the OTHER interpretation needs no knowledge
                        // of how any site names its files: the parsers rejecting it
                        // is itself the evidence. Exactly once, because if both
                        // readings fail the stream really is unplayable and a third
                        // attempt just delays the honest answer.
                        if (error.errorCode ==
                                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
                            !containerRetried
                        ) {
                            containerRetried = true
                            forcedUseHls = !lastUsedHls
                            // Log.e: R8 strips w from release builds, and this
                            // line is the only thing that explains why the source
                            // type changed between the two lines around it.
                            Log.e(TAG, "↻ container unrecognised as " +
                                    "${if (lastUsedHls) "hls" else "progressive"} — " +
                                    "retrying once as " +
                                    "${if (forcedUseHls == true) "hls" else "progressive"}")
                            captureResumeState()
                            startPlayback(currentM3u8)
                            return
                        }

                        val isCdnFailure =
                            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                                    error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
                        // Relay first — see switchToRelay for why re-extraction
                        // cannot fix a blocked CDN.
                        if (isCdnFailure && switchToRelay(error.errorCodeName, httpStatus)) return
                        if (isCdnFailure && embedUrl != null && extractRetries < MAX_EXTRACT_RETRIES) {
                            extractRetries++
                            Log.w(TAG, "↻ CDN failure — re-extracting for a fresh server (retry $extractRetries/$MAX_EXTRACT_RETRIES)")
                            relaunchExtraction()
                        } else {
                            if (embedUrl == null) Log.e(TAG, "no EMBED_URL passed — cannot re-extract; finishing")
                            // Say something before the screen goes away. Four
                            // retries with a backoff is ~10s of black screen, and
                            // closing silently reads as a crash rather than "this
                            // episode's CDN is unreachable from here".
                            if (isCdnFailure) {
                                abandonSourceWithNotice()
                            } else {
                                finish()
                            }
                        }
                    }
                })
                setMediaSource(mediaSource)
                prepare()
                if (resumePositionMs > 0) seekTo(resumePositionMs)
                resumePositionMs = 0
                // PAUSED STAYS PAUSED ACROSS A REBUILD.
                //
                // This was an unconditional `true`, which is right for the first
                // build and wrong for every later one. startPlayback is also the
                // tail of four rebuild paths — the 50-minute progressive refresh,
                // the container retry, the relay switch and re-extraction — and
                // each of those carefully carries the POSITION across
                // (resumePositionMs) while dropping whether the user was watching.
                //
                // The refresh is the one that bites, because it is the only one
                // that fires with no user action and no error: arm it at 09:09,
                // pause the film at 09:15, and at 09:59 it swaps the URL and
                // starts playing to an empty room.
                playWhenReady = resumePlayWhenReady
                resumePlayWhenReady = true
            }

        playerView?.player = player

        if (playerView == null) {
            setContent {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                playerView = this
                                this.player = this@HlsPlayerActivity.player
                                useController = true
                                // 3s fade-out + blue scrubber while it holds D-pad
                                // focus. Shared so every site's player matches.
                                ExoPlayerConfig.applyControllerPolicy(this)
                                // Inject our subtitle CC + timing buttons into the
                                // control bar each time it appears (guarded to once).
                                setControllerVisibilityListener(
                                    PlayerView.ControllerVisibilityListener { vis ->
                                        if (vis == View.VISIBLE) {
                                            injectSubtitleControls(this)
                                            // Hide media3's own CC button — we use our own.
                                            findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)
                                                ?.visibility = View.GONE
                                        }
                                    }
                                )
                            }
                        }
                    )

                    // Self-rendered captions overlay (fed by the position ticker).
                    //
                    // Cues are pushed through Compose state rather than through a
                    // cached SubtitleView reference. The ticker used to hold the
                    // View the factory happened to build and call setCues on it
                    // directly, which silently stopped working the moment Compose
                    // handed us a different instance — the on-screen text froze,
                    // and "turn captions off" couldn't clear it because it was
                    // clearing a view nobody was looking at. update runs against
                    // whatever view is live now, so that can't happen.
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            SubtitleView(ctx).apply {
                                setUserDefaultStyle()
                                setUserDefaultTextSize()
                                setBottomPaddingFraction(0.10f)   // sit above the control bar
                            }
                        },
                        update = { it.setCues(activeCues) }
                    )

                    // English-subtitle picker (CC). Drawn above the player and the
                    // caption layer so it owns D-pad focus while it's up.
                    if (pickerOpen) {
                        SubtitlePickerOverlay(
                            loading     = pickerLoading,
                            options     = pickerOptions,
                            activeIndex = activeSubtitleIndex,
                            durationMs  = player?.duration?.takeIf { it > 0 } ?: 0L,
                            error       = pickerError,
                            onPick      = { applyChoice(it) },
                            onTurnOff   = { turnCaptionsOff() },
                            onDismiss   = { closeSubtitlePicker() },
                        )
                    }

                    // Transient toast, lower-right corner (non-interactive overlay).
                    toastText?.let { msg ->
                        Text(
                            text = msg,
                            color = Color.White,
                            fontSize = 15.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                // Clears the control bar. At bottom = 28.dp a toast
                                // raised BY a control-bar button lands on top of the
                                // buttons that raised it — the timing read-out sits
                                // exactly there, which is the one thing you want to
                                // read afterwards. The width cap stops a long
                                // message spanning the screen instead of wrapping.
                                .padding(end = 28.dp, bottom = 96.dp)
                                .widthIn(max = 560.dp)
                                .background(Color(0xE6000000), RoundedCornerShape(10.dp))
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                        )
                    }

                    // "Still watching?" — LAST, so it draws above the captions, the
                    // toast and the subtitle picker. It takes no focus and consumes
                    // no input (any key dismisses it through the window hook), so
                    // sitting on top costs the picker nothing if both are somehow up.
                }
            }
        }
    }

    /**
     * The data-source factory playback uses for [url]: the same headers, the same
     * QUIC hint, the same Cronet-or-OkHttp choice. Shared by [buildMediaSource]
     * and [probeStreamReachable] so that a URL is judged by the transport that
     * will actually play it.
     */
    private fun streamDataSourceFactory(
        url: String,
        streamUa: String,
        streamReferer: String,
    ): androidx.media3.datasource.DataSource.Factory {
        val isBackendRelay = try {
            val backendHost = android.net.Uri.parse(BuildConfig.API_BASE_URL).host
            val streamHost  = android.net.Uri.parse(url).host
            backendHost != null && backendHost.equals(streamHost, ignoreCase = true)
        } catch (_: Exception) { false }

        // Referer only when the backend supplies one; Origin never. See
        // ExoPlayerConfig.streamHeaders for the measured per-level table and for
        // why a blanket removal (build 114) was wrong.
        val requestHeaders = mutableMapOf(
            "User-Agent" to streamUa,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        if (streamReferer.isNotBlank()) requestHeaders["Referer"] = streamReferer
        if (isBackendRelay) requestHeaders["X-API-Key"] = BuildConfig.API_KEY

        // BEFORE the factory, because addQuicHint is build-time state. A filtered
        // CDN never advertises alt-svc (its TCP handshake dies first), so QUIC has
        // to be tried on the very first request or not at all.
        ExoPlayerConfig.ensureQuicHint(this, url)
        return ExoPlayerConfig.dataSourceFactory(streamUa, requestHeaders, context = this)
    }

    /**
     * Video-only source. Captions are rendered separately (see class doc).
     *
     * HLS unless the URL is demonstrably a whole file. VidNest returns
     * hlmv.tripplestream.online/videos/<id> — no .m3u8, no extension — and
     * HlsMediaSource cannot parse that, so the source TYPE has to follow the
     * stream shape rather than being fixed.
     */
    private fun buildMediaSource(url: String): MediaSource {
        val originHeader = try {
            val u = android.net.Uri.parse(referer)
            if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else "https://cinesrc.st"
        } catch (_: Exception) { "https://cinesrc.st" }

        val factory = streamDataSourceFactory(url, ua, referer)

        // Decided from the URL, NOT from the backend's is_hls flag. The player is
        // handed a URL from several places — the relay, a mid-play re-extraction,
        // an on-device extractor — and only some of them carry that flag. The URL
        // is the one thing always present, so it is the only signal that can't go
        // stale mid-session.
        //
        // Relay URLs always read as HLS here: the real stream rides in the ?url=
        // query parameter, so the path is /api/hls/proxy and never matches. That
        // is correct for segmented streams but WRONG for a relayed progressive
        // file — see the note in the release write-up; not reachable today because
        // the relay is only minted on a failure path.
        val looksProgressive = runCatching {
            val path = android.net.Uri.parse(url).path.orEmpty().lowercase()
            !path.endsWith(".m3u8") && !path.endsWith(".mpd") &&
                !url.lowercase().contains(".m3u8") && !url.lowercase().contains(".mpd")
        }.getOrDefault(false)

        // THE BACKEND'S FLAG WINS WHEN WE HAVE ONE. The URL heuristic above stays
        // as the fallback — it is still the only signal present on every path —
        // but it cannot see past an extensionless URL, and some sources have one.
        //
        // MEASURED 2026-08-30: VidLove serves HLS from
        // https://a2.shows.st/api?d=... with no ".m3u8" anywhere. The heuristic
        // called it progressive, the MP4 extractors were fed a playlist, and it
        // died on UnrecognizedInputFormatException / NoDeclaredBrand — a decode
        // error that says nothing about the real cause.
        //
        // Absent extra -> unchanged behaviour, which is why this is safe for the
        // on-device and re-extraction paths that never set it.
        val hlsHint: Boolean? =
            if (intent.hasExtra("IS_HLS")) intent.getBooleanExtra("IS_HLS", true) else null
        val useHls = forcedUseHls ?: (hlsHint ?: !looksProgressive)
        lastUsedHls = useHls

        Log.e(TAG, "🎞 source=${if (useHls) "hls" else "progressive"} " +
                "(${if (hlsHint != null) "backend is_hls=$hlsHint" else "inferred from url"}) " +
                "url=${url.take(80)}")

        return if (useHls) ExoPlayerConfig.hlsMediaSource(url, factory)
               else ExoPlayerConfig.progressiveMediaSource(url, factory)
    }

    // ── Captions: CC button + self-rendering ─────────────────────────────────
    /** Sentinel for "nothing rendered yet" — no real cue key can be a NUL. */
    private val noCueKey = 0.toChar().toString()

    /**
     * CC now opens the picker rather than silently loading the first English
     * subtitle it finds. There are usually several, only one is timed to the
     * release we're streaming, and until now the other four were fetched past,
     * discarded and never offered — leaving a drifting subtitle with no recourse
     * but the offset buttons, which cannot fix a framerate mismatch.
     */
    private fun onCcClicked() {
        if (pickerOpen) closeSubtitlePicker() else openSubtitlePicker()
    }

    /** Cache key shared by every subtitle for this exact episode. */
    private fun subtitleCacheKey(): String {
        val season  = intent.getIntExtra("SEASON_NUMBER", 0)
        val episode = intent.getIntExtra("EPISODE_NUMBER", 0)
        return if (season > 0) "${tmdbId}_s${season}e${episode}" else "$tmdbId"
    }

    private fun openSubtitlePicker() {
        if (tmdbId <= 0 && streamSubtitleUrl.isNullOrBlank()) {
            Log.e(TAG, "subs: no tmdbId passed — cannot fetch captions")
            showToast("Sorry, no subtitle for this media is available.")
            return
        }
        pickerOpen = true
        pickerError = null
        playerView?.hideController()

        // Pause while they choose — comparing candidates takes a few seconds and
        // the episode shouldn't run on underneath. Only resume what WE paused, so
        // closing the panel can't restart a video they had deliberately paused.
        player?.let { p ->
            if (p.playWhenReady) { p.playWhenReady = false; pausedForPicker = true }
        }

        // Already loaded once this session — reopen instantly, no re-download.
        if (pickerOptions.isNotEmpty()) return

        pickerLoading = true
        Log.d(TAG, "subs: ▶️ CC pressed → listing English subtitles for tmdb=$tmdbId")
        lifecycleScope.launch {
            val choices = withContext(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(25_000) {
                        val key = subtitleCacheKey()
                        val fromStream = streamSubtitleUrl

                        // NOTHING IS DOWNLOADED HERE ANY MORE except the stream's
                        // own caption. This used to fan out and fetch every
                        // candidate at once — the comment claimed "~40 KB each",
                        // and measured on the onn box it peaked at 806MB and got
                        // the app killed by the low-memory killer mid-film.
                        //
                        // The backend lists them with their cue counts and timings
                        // already computed, so the picker renders from a few
                        // hundred bytes of JSON. The chosen file is fetched in
                        // applyChoice, one download instead of eight.
                        val listed = if (tmdbId > 0) HlsSubtitles.listChoices(
                            tmdbId    = tmdbId,
                            mediaType = intent.getStringExtra("MEDIA_TYPE") ?: "movie",
                            season    = intent.getIntExtra("SEASON_NUMBER", 0),
                            episode   = intent.getIntExtra("EPISODE_NUMBER", 0),
                        ) else emptyList()

                        // The stream caption still has to be fetched to be offered
                        // at all — it is the only way to know whether it has cues.
                        // One file, and it is the option most likely to be picked.
                        // The SOURCE is part of the cache key. Every site serves
                        // its own encode with its own caption, so one name per
                        // title handed the second site the first site's file.
                        val stream = if (!fromStream.isNullOrBlank())
                            HlsSubtitles.streamChoice(
                                context   = this@HlsPlayerActivity,
                                url       = fromStream,
                                key       = key,
                                sourceTag = intent.getStringExtra("EXTRACT_SOURCE") ?: "vidfast",
                            )
                        else null

                        listOfNotNull(stream) + listed
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "subs: listing threw — ${e.message}", e); null
                }
            }
            pickerLoading = false
            pickerOptions = choices.orEmpty()
            if (pickerOptions.isEmpty()) {
                pickerError = "Couldn't reach the subtitle service. Try again in a moment."
                Log.e(TAG, "subs: FAILED for tmdb=$tmdbId — nothing usable (see prior subs: logs)")
            } else {
                Log.d(TAG, "subs: picker ready — ${pickerOptions.size} option(s)")
                // Deliberately does NOT auto-apply the first option. Loading one
                // on open makes the panel a confirmation of a choice already made
                // rather than a choice — and the first entry is exactly the one
                // that might be drifting, which is why the picker exists.
            }
        }
    }

    private fun closeSubtitlePicker() {
        pickerOpen = false
        if (pausedForPicker) { player?.playWhenReady = true; pausedForPicker = false }
    }

    /** Render [choice] from now on. Only ever called from the picker. */
    private fun applyChoice(choice: HlsSubtitles.SubtitleChoice) {
        lifecycleScope.launch {
            val cues = withContext(Dispatchers.IO) {
                try {
                    // THE DOWNLOAD HAPPENS HERE NOW, for this one option only.
                    // Listing an option costs a row; fetching it costs a request,
                    // and only the option actually chosen ever pays that. The
                    // stream caption returns its existing path immediately.
                    val path = HlsSubtitles.ensureVtt(
                        context   = this@HlsPlayerActivity,
                        choice    = choice,
                        tmdbId    = tmdbId,
                        mediaType = intent.getStringExtra("MEDIA_TYPE") ?: "movie",
                        season    = intent.getIntExtra("SEASON_NUMBER", 0),
                        episode   = intent.getIntExtra("EPISODE_NUMBER", 0),
                        key       = subtitleCacheKey(),
                    )
                    if (path != null && File(path).exists()) parseVtt(path) else null
                } catch (e: Exception) {
                    Log.e(TAG, "subs: parse threw — ${e.message}", e); null
                }
            }
            if (cues.isNullOrEmpty()) {
                showToast("That subtitle couldn't be read — try another.")
                return@launch
            }
            parsedCues = cues
            captionsLoaded = true
            captionsOn = true
            activeSubtitleIndex = choice.option.index
            // A different file has different timing, so an anchor taken against
            // the old one would pair with a point from the new one and measure
            // a drift that belongs to neither.
            lastSyncPoint = null
            syncSettleJob?.cancel()
            lastCueKey = noCueKey
            updateCcButtonState()
            startCaptionTicker()
            Log.d(TAG, "subs: captions loaded — ${choice.label} (${cues.size} cues), offset=${subtitleOffsetMs}ms")
            closeSubtitlePicker()
            showToast(
                if (subtitleOffsetMs != 0L || subtitleRate != 1.0)
                    "${choice.label} on (timing saved from last time)."
                else
                    "${choice.label} on. Out of sync? Nudge it with ◀ / ▶."
            )
        }
    }

    private fun turnCaptionsOff() {
        captionsOn = false
        captionsLoaded = false
        activeSubtitleIndex = -1
        // Stop the ticker FIRST, then clear — otherwise the next tick (up to
        // 80ms away) can win the race and put the last cue straight back.
        captionTickerJob?.cancel()
        captionTickerJob = null
        parsedCues = emptyList()
        activeCues = emptyList()
        lastCueKey = noCueKey
        updateCcButtonState()
        closeSubtitlePicker()
        Log.d(TAG, "captions OFF")
    }

    /** Recompute the active cue(s) from the playback clock (+ offset) ~12×/sec. */
    private fun startCaptionTicker() {
        captionTickerJob?.cancel()
        captionTickerJob = lifecycleScope.launch {
            while (true) {
                renderActiveCues()
                delay(80)
            }
        }
    }

    private fun renderActiveCues() {
        val p = player
        val active: List<Cue> =
            if (!captionsOn || p == null || parsedCues.isEmpty()) emptyList()
            else {
                // Map the playback clock into CUE space: videoTime = cue*rate +
                // offset, inverted. +offset ⇒ cue shows later; rate > 1 stretches
                // the subtitle to fit a longer video, fixing one that runs ahead.
                //
                // At rate 1.0 this is exactly the old expression: a Long position
                // converts to Double losslessly below 2^53 ms, and dividing by 1.0
                // is the identity, so the uncorrected path did not change.
                val t = ((p.currentPosition - subtitleOffsetMs) / subtitleRate).toLong()
                parsedCues.filter { t >= it.startMs && t <= it.endMs }.map { it.cue }
            }
        val key = active.joinToString("") { it.text ?: "" }
        if (key != lastCueKey) { activeCues = active; lastCueKey = key }
    }

    // ── Subtitle timing controls (buttons in the bottom control bar) ──────────
    private fun injectSubtitleControls(pv: PlayerView) {
        if (subOffsetLabel != null) return                 // inject once
        val bar = pv.findViewById<LinearLayout>(androidx.media3.ui.R.id.exo_basic_controls) ?: return
        val inflater = LayoutInflater.from(pv.context)

        fun syncButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton =
            (inflater.inflate(R.layout.vidapi_sub_sync_button, bar, false) as ImageButton).apply {
                setImageResource(iconRes)
                contentDescription = desc
                setOnClickListener { onClick() }
            }

        val cc = syncButton(R.drawable.ic_sub_cc, "Toggle captions") { onCcClicked() }
        val earlier = syncButton(R.drawable.ic_sub_earlier, "Subtitles earlier") { nudgeOffset(-250) }
        val label = (inflater.inflate(R.layout.vidapi_sub_offset_label, bar, false) as TextView).apply {
            text = fmtTiming()
        }
        val later = syncButton(R.drawable.ic_sub_later, "Subtitles later") { nudgeOffset(+250) }
        val reset = syncButton(R.drawable.ic_sub_reset, "Reset subtitle timing") { resetOffset() }

        ccButton = cc
        subOffsetLabel = label
        updateCcButtonState()

        // Four controls, and every one of them does something the viewer can
        // state without jargon: show captions, earlier, later, start over. Rate
        // correction is deliberately absent — it is measured, not operated.
        listOf(cc, earlier, label, later, reset)
            .forEachIndexed { i, v -> bar.addView(v, i) }
        Log.d(TAG, "subs: injected caption + timing controls into control bar")
    }

    private fun nudgeOffset(deltaMs: Int) {
        subtitleOffsetMs = (subtitleOffsetMs + deltaMs).coerceIn(-60_000L, 60_000L)
        subOffsetLabel?.text = fmtTiming()
        persistTiming()
        // Nudging the timing implies wanting captions visible — but only while
        // one is still selected. After an explicit turn-off there is nothing to
        // re-enable, and resurrecting it there would undo the user's choice.
        if (captionsLoaded && !captionsOn && activeSubtitleIndex >= 0) {
            captionsOn = true; updateCcButtonState()
        }
        renderActiveCues()                                 // instant, no reload
        armSyncSettle()                                    // learn from this nudge
        Log.d(TAG, "subs: timing offset → ${subtitleOffsetMs}ms")
    }

    /**
     * Wait for the nudging to stop, then record where they stopped.
     *
     * Restarted on every press, so a run of presses collapses into the single
     * value they landed on. Cancelled outright when captions are off, because an
     * offset nobody can see is not an observation of anything.
     */
    private fun armSyncSettle() {
        syncSettleJob?.cancel()
        if (!captionsOn || !captionsLoaded) return
        syncSettleJob = lifecycleScope.launch {
            delay(SYNC_SETTLE_MS)
            player?.currentPosition?.let { recordSyncPoint(it, subtitleOffsetMs) }
        }
    }

    /**
     * The whole drift feature, with no UI attached.
     *
     * A viewer fixing a subtitle tells us, without meaning to, exactly what is
     * wrong with it. Each time they settle on an offset they are asserting "at
     * THIS moment, the right shift is THIS much". One such assertion is a
     * constant offset and we already honour it. Two of them, far enough apart,
     * are a LINE — and the slope of that line is the timing error we could not
     * otherwise see.
     *
     *     needed offset at p   o(p) = p·(1 − 1/rate) + delta/rate
     *     slope                m    = (o₂ − o₁) / (p₂ − p₁) = 1 − 1/rate
     *     so                   rate = 1 / (1 − m)
     *     and                  delta = rate·(o₁ − p₁·m)
     *
     * Both fall out exactly; nothing is guessed and no preset is involved. Note
     * it does NOT need them to have fully corrected the drift either time — only
     * to have stopped somewhere they were happy with, twice.
     */
    private fun recordSyncPoint(positionMs: Long, offsetMs: Long) {
        val point = SyncPoint(positionMs, offsetMs)
        val prev = lastSyncPoint
        val span = positionMs - (prev?.positionMs ?: 0L)

        // Too close together, or they seeked backwards: this is the new anchor
        // rather than the far end of a measurement.
        if (prev == null || span < SYNC_MIN_SPAN_MS) {
            lastSyncPoint = point
            Log.d(TAG, "subs: sync anchor at ${positionMs}ms offset=${offsetMs}ms")
            return
        }

        // Under the noise floor: they settled on effectively the same answer
        // twice, which means the subtitle is NOT drifting. Leave it alone.
        if (abs(offsetMs - prev.offsetMs) < SYNC_MIN_OFFSET_DELTA_MS) {
            lastSyncPoint = point
            return
        }

        val m = (offsetMs - prev.offsetMs).toDouble() / span.toDouble()
        val rate = 1.0 / (1.0 - m)
        if (rate.isNaN() || rate !in SYNC_RATE_MIN..SYNC_RATE_MAX) {
            // Not a rate error — more likely they simply wanted it shifted twice.
            // Keep the newer point; the next one may still form a sane pair.
            lastSyncPoint = point
            Log.d(TAG, "subs: sync pair implied rate ${"%.4f".format(rate)} — out of band, ignored")
            return
        }

        subtitleRate = rate
        subtitleOffsetMs = (rate * (prev.offsetMs - prev.positionMs * m))
            .toLong().coerceIn(-60_000L, 60_000L)
        lastSyncPoint = null                               // measured; start over
        subOffsetLabel?.text = fmtTiming()
        persistTiming()
        renderActiveCues()

        // The one sentence the viewer ever sees about any of this. It names no
        // mechanism — but it DOES name the way out, because something that
        // changes your subtitles on its own has to be undoable by someone who
        // never asked for it and does not know what it did.
        showToast("This subtitle drifts — corrected for the rest of the film. " +
                "Reset undoes it.")
        Log.d(TAG, "subs: measured drift from 2 sync points — " +
                "rate=${"%.5f".format(rate)} offset=${subtitleOffsetMs}ms " +
                "(p1=${prev.positionMs} o1=${prev.offsetMs}, p2=$positionMs o2=$offsetMs)")
    }

    /** Start over — including anything measured, and the measurement in progress. */
    private fun resetOffset() {
        subtitleOffsetMs = 0
        subtitleRate = 1.0
        lastSyncPoint = null
        syncSettleJob?.cancel()
        subOffsetLabel?.text = fmtTiming()
        persistTiming()
        renderActiveCues()
        Log.d(TAG, "subs: timing reset — offset 0, rate 1.0, sync points cleared")
    }

    /** Offset and rate travel together: a title is synced once, both parts of it. */
    private fun persistTiming() {
        if (tmdbId <= 0) return
        subPrefs.edit()
            .putLong(tmdbId.toString(), subtitleOffsetMs)   // key unchanged: saved offsets survive
            .putFloat("${tmdbId}_ratef", subtitleRate.toFloat())
            .remove("${tmdbId}_rate")                      // the old preset index, now meaningless
            .apply()
    }

    private fun updateCcButtonState() { ccButton?.alpha = if (captionsOn) 1f else 0.45f }

    private fun fmtOffset(ms: Long): String = String.format(Locale.US, "%+.2fs", ms / 1000.0)

    /**
     * The control-bar read-out. Just the offset — the rate is deliberately NOT
     * shown. "×1.043" is a number nobody can act on, and putting it here would
     * re-introduce the mechanism this design exists to hide.
     */
    private fun fmtTiming(): String = fmtOffset(subtitleOffsetMs)

    /** Parse our WEBVTT (always HH:MM:SS.mmm from HlsSubtitles) into timed cues. */
    private fun parseVtt(path: String): List<TimedCue> {
        val text = File(path).readText(Charsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n")
        val tsRe = Regex("(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})")
        val out = ArrayList<TimedCue>()
        for (block in text.split("\n\n")) {
            val lines = block.trim().split("\n")
            if (lines.isEmpty()) continue
            var idx = -1
            var m: MatchResult? = null
            for ((i, l) in lines.withIndex()) {
                val mm = tsRe.find(l)
                if (mm != null) { idx = i; m = mm; break }
            }
            val g = m?.groupValues ?: continue
            val start = ((g[1].toLong() * 60 + g[2].toLong()) * 60 + g[3].toLong()) * 1000 + g[4].toLong()
            val end   = ((g[5].toLong() * 60 + g[6].toLong()) * 60 + g[7].toLong()) * 1000 + g[8].toLong()
            val cueText = lines.drop(idx + 1).joinToString("\n").trim()
            if (cueText.isEmpty()) continue
            out.add(TimedCue(start, end, Cue.Builder().setText(cueText).build()))
        }
        return out
    }

    private fun showToast(msg: String) {
        toastText = msg
        toastJob?.cancel()
        toastJob = lifecycleScope.launch { delay(5_000); toastText = null }
    }

    /**
     * Fall back to the backend relay, once.
     *
     * WHY THIS COMES BEFORE RE-EXTRACTION: re-extracting cannot fix a blocked CDN.
     * The source pins one host per episode and hands back the SAME one, so on a
     * filtered line the retry loop just fails four times and shows "Couldn't reach
     * this episode's server" — measured, not assumed. The relay is the only path
     * that recovers it, because the bytes then come from us and never touch the
     * blocked host.
     *
     * Direct is still tried FIRST and the relay only after a real failure, so an
     * unfiltered network never costs us the transfer. `usingRelay` makes it
     * strictly once: if the relay itself dies, we fall through to the old
     * re-extraction path rather than looping.
     */
    private fun switchToRelay(why: String, httpStatus: Int? = null): Boolean {
        val relay = relayUrl
        if (relay == null || usingRelay) return false
        // READ THE DIRECT ATTEMPT'S FACTS BEFORE usingRelay FLIPS. Everything
        // below describes the attempt that just failed, and currentM3u8 is still
        // the direct URL at this point — one line later it is the relay's.
        val diedOn = currentM3u8
        val transport = ExoPlayerConfig.transportFor(diedOn)
        val wasNew = ExoPlayerConfig.wasNewlyLearned(diedOn)
        // TWO LEVELS, BECAUSE ECH FAILS AT TWO LEVELS. echState says whether the
        // device is negotiating ECH at all; hostEch says whether this CDN even
        // published a config to negotiate with. "ECH broke" and "this host was
        // never eligible" are different faults and only the pair separates them.
        val echState = ExoPlayerConfig.echState()
        val hostEch = when (ExoPlayerConfig.hostPublishesEch(diedOn)) {
            true  -> "yes"
            false -> "no"
            null  -> "unknown"
        }
        val directMs =
            if (directStartedAtMs > 0L)
                (android.os.SystemClock.elapsedRealtime() - directStartedAtMs).toInt()
            else -1

        usingRelay = true
        // Remember the position BEFORE the teardown so recovery resumes.
        captureResumeState()
        // ── WHAT THE RELAY ROW GETS TOLD ─────────────────────────────────────
        //
        // err alone could not answer the only question that matters when a relay
        // shows up: was this the ISP filter, or did the CDN simply say no?
        // ERROR_CODE_IO_BAD_HTTP_STATUS covers 403, 404, 410 and every 5xx, which
        // are four different faults with four different fixes.
        //
        //   http  — the actual status. -1 when the failure was not an HTTP one.
        //   proto — h3 means QUIC carried it and the filter was not involved;
        //           (none) with http=-1 is the filter's own signature.
        //   ms    — how long direct was attempted before giving up.
        //   new   — whether this host was first seen by this playback's warm, so
        //           "the defence lost" is distinguishable from "there was none".
        //
        // Query params on a URL the grant endpoint preserves verbatim, so no new
        // transport, endpoint or schema round trip is involved.
        val tagged = buildString {
            append(relay)
            append("&err=").append(URLEncoder.encode(why, "UTF-8"))
            append("&http=").append(httpStatus ?: transport?.httpStatus ?: -1)
            append("&proto=").append(
                URLEncoder.encode(transport?.protocol ?: "(unknown)", "UTF-8")
            )
            append("&ms=").append(directMs)
            append("&new=").append(if (wasNew) 1 else 0)
            append("&ech=").append(URLEncoder.encode(echState, "UTF-8"))
            append("&hostech=").append(hostEch)
        }
        Log.e(TAG, "🔀 relay report: err=$why http=${httpStatus ?: transport?.httpStatus ?: -1} " +
                "proto=${transport?.protocol ?: "(unknown)"} directMs=$directMs newHost=$wasNew " +
                "ech=$echState hostEch=$hostEch")
        Log.w(TAG, "🔀 CDN unreachable ($why) — relaying via backend, " +
                "resuming at ${resumePositionMs}ms")
        loggedDuration = false
        // Posted, not inline: this fires from inside the player's own error
        // callback, and startPlayback releases that player.
        lifecycleScope.launch {
            // The relay refuses an unauthorised URL — see RelayGrantRequest. One
            // round trip, only ever on the failure path.
            val granted = runCatching {
                ApiClient.bluehiveApi.mintRelayGrant(RelayGrantRequest(tagged))
            }.getOrNull()?.takeIf { it.isSuccessful }?.body()?.relay_url

            if (granted.isNullOrBlank()) {
                // Over quota, or no valid session. Don't burn the one relay
                // attempt on a URL that will 401 — fall back to the old path.
                Log.e(TAG, "🛑 relay grant refused — falling back to re-extraction")
                relaunchExtraction()
                return@launch
            }
            recoveryLog("relay granted — restarting at ${resumePositionMs}ms")
            startPlayback(granted)
            armRecoveryWatchdog("relay-switch")
        }
        return true
    }

    /** Fire the headless extractor again for the same embed URL. Result handled above. */
    private fun relaunchExtraction() {
        val embed = embedUrl ?: run { finish(); return }
        // Remember where we were BEFORE tearing the player down, so a recovery
        // resumes the episode instead of restarting it.
        captureResumeState()
        player?.run { stop(); clearMediaItems(); release() }
        player = null
        // Which extractor to re-run is the caller's decision — this player is
        // shared by several sources and must not hardcode one of them.
        val extractorClass = intent.getStringExtra(HlsExtract.EXTRA_EXTRACTOR_CLASS)
        if (extractorClass.isNullOrBlank()) {
            // No local extractor: VidFast now extracts server-side, so ask the
            // backend for a fresh manifest instead of giving up. This is the path
            // that was missing when a token expired mid-episode — the watchdog
            // re-prepared a dead url forever because nothing could replace it.
            reextractViaBackend(); return
        }
        val relaunch = Intent()
            .setClassName(this, extractorClass)
            .putExtra(HlsExtract.EXTRA_URL, embed)
            // Hosts read their own key name; send both so either matches.
            .putExtra("VIDAPI_URL", embed)
            .putExtra("VIDFAST_URL", embed)
        reextractLauncher.launch(relaunch)
    }

    /**
     * Ask the backend for a FRESH manifest and resume where we left off.
     *
     * WHY THIS EXISTS: playback tokens expire. MEASURED 2026-08-03, House of the
     * Dragon at ~31 min — segments stopped arriving with no error at all (the
     * request just hangs), so onPlayerError never fired and the stall watchdog
     * re-prepared the same dead url indefinitely. refresh=1 bypasses the server
     * cache, which matters because the cached copy is the thing that just died.
     */
    private fun reextractViaBackend() {
        val tmdb = intent.getIntExtra("TMDB_ID", 0)
        if (tmdb <= 0) {
            Log.e(TAG, "no TMDB_ID — cannot re-extract via backend; finishing")
            finish(); return
        }
        val type    = intent.getStringExtra("MEDIA_TYPE") ?: "movie"
        val season  = intent.getIntExtra("SEASON_NUMBER", 0).takeIf { it > 0 }
        val episode = intent.getIntExtra("EPISODE_NUMBER", 0).takeIf { it > 0 }
        // Re-ask the SAME source that produced this stream. Defaulting to vidfast
        // would silently move a VidEasy viewer onto VidFast's extraction.
        val source  = intent.getStringExtra("EXTRACT_SOURCE") ?: "vidfast"

        Log.w(TAG, "↻ asking backend for a fresh manifest " +
                "(tmdb=$tmdb $type s=$season e=$episode, resume=${resumePositionMs}ms)")

        lifecycleScope.launch {
            delay(REEXTRACT_BACKOFF_MS)
            val body = runCatching {
                // refresh=1 forces a real browser run rather than a cache hit —
                // essential here, because the cached entry is the one that just
                // handed us a blocked CDN. A fresh run re-draws from the pool.
                ApiClient.bluehiveApi.extractStreamAwait(
                    type = type, tmdbId = tmdb, season = season, episode = episode,
                    source = source, refresh = 1,
                    // A re-extract is the LIKELIEST relay of all — it only happens
                    // after a CDN already failed — so it must carry identity too.
                    mtype = type, title = intent.getStringExtra("MEDIA_TITLE"),
                    profile = intent.getIntExtra("PROFILE_ID", -1).takeIf { it > 0 },
                    device = SessionManager.get().deviceFingerprint,
                )
            }.getOrNull()?.takeIf { it.isSuccessful }?.body()

            if (body == null || body.m3u8.isBlank()) {
                Log.e(TAG, "❌ backend re-extraction failed — finishing")
                finish(); return@launch
            }

            // SAME URL BACK = the source has no working link, not a bad draw.
            // Retrying re-runs a ~20s browser extraction to be handed the identical
            // 404, so every remaining attempt is guaranteed to fail. Stop here and
            // tell the user something true instead of churning.
            if (body.m3u8 in failedStreamUrls) {
                recoveryLog("re-extraction returned an already-dead URL — source has no working copy")
                abandonSourceWithNotice(); return@launch
            }

            // NEW url, but verify it answers BEFORE rebuilding the player around it.
            // A 200 from our backend means extraction ran, not that the link serves
            // bytes. Probing costs one ranged request; handing a dead URL to
            // ExoPlayer costs a full connect/read timeout and another error cycle.
            if (!probeStreamReachable(
                    body.m3u8,
                    probeUa = body.user_agent?.takeIf { it.isNotBlank() } ?: ua,
                    probeReferer = body.referer?.takeIf { it.isNotBlank() } ?: referer,
                )
            ) {
                failedStreamUrls += body.m3u8
                recoveryLog("re-extracted URL does not answer — not handing it to the player")
                if (extractRetries < MAX_EXTRACT_RETRIES) {
                    // Still a pool to draw from: go straight to the next attempt
                    // rather than waiting for ExoPlayer to time out on a known-dead
                    // link. This is what keeps the pool-redraw behaviour intact.
                    extractRetries++
                    reextractViaBackend()
                } else {
                    abandonSourceWithNotice()
                }
                return@launch
            }
            body.referer?.takeIf { it.isNotBlank() }?.let { referer = it }
            body.user_agent?.takeIf { it.isNotBlank() }?.let { ua = it }

            // RE-ARM THE RELAY. A fresh manifest is a fresh playback: it comes with
            // its own proxy_url, and the previous one is stale. Without this the
            // relay is spent after a single use and every subsequent retry runs
            // direct-only — observed as four consecutive re-extractions landing on
            // the same blocked CDN with no fallback left, then giving up.
            // MAX_EXTRACT_RETRIES still bounds the loop, so this cannot ping-pong.
            body.proxy_url?.takeIf { it.isNotBlank() }?.let { relayUrl = it }
            usingRelay = false

            recoveryLog("fresh manifest received — restarting at ${resumePositionMs}ms")
            loggedDuration = false
            startPlayback(body.m3u8)
            // The exact gap that hid the 2026-08-17 failure: the backend answered
            // 200, this line was reached, and playback never began. Nothing
            // reported it because success here was assumed rather than confirmed.
            armRecoveryWatchdog("backend-re-extract")
        }
    }

    // ── onn box CHANNEL UP / DOWN → skip forward / back ──────────────────────
    // Handled in dispatchKeyEvent, i.e. BEFORE the view hierarchy, so PlayerView's
    // controller never sees these keys and no scrubber pops up — the jump is
    // silent and instant. ACTION_UP is swallowed too, so the system can't do
    // anything else with them afterwards.
    //
    // Fire TV's dedicated transport keys are mapped to the same jump, since that
    // remote has no channel rocker. Matches MiruroPlayerActivity's behaviour;
    // the increment and clamping live in ExoPlayerConfig so a skip feels the same
    // on every site's player.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The subtitle picker is a modal: while it's up every key belongs to it.
        // Without this the activity would keep stealing centre (play/pause) and
        // the channel keys (seek) before Compose ever saw them, so rows could be
        // moved between but never actually selected.
        if (pickerOpen) return super.dispatchKeyEvent(event)

        val p = player
        if (p != null) {
            val isSeekForward =
                event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
                    event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            val isSeekBack =
                event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ||
                    event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND

            if (isSeekForward || isSeekBack) {
                // Act once on the way DOWN; swallow the matching UP.
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val delta = if (isSeekForward) ExoPlayerConfig.SEEK_INCREMENT_MS
                                else -ExoPlayerConfig.SEEK_INCREMENT_MS
                    val from = p.currentPosition
                    val to   = ExoPlayerConfig.seekBy(p, delta)
                    Log.d(TAG, "⏩ channel-key seek: ${from}ms -> ${to}ms (delta ${delta}ms)")
                }
                return true
            }

            // ── Centre / OK → play-pause on the FIRST press ──────────────────
            // A bare PlayerView treats the first centre press as "raise the
            // controls", so pausing needed a direction press first, then OK. We
            // intercept ahead of the view hierarchy and toggle immediately.
            //
            // Still context-sensitive: once the controls ARE showing, centre goes
            // to the focused control instead, so CC / subtitle-offset / quality
            // buttons remain reachable. Directions (never intercepted) are what
            // raise the controls.
            if (ExoPlayerConfig.isCenterKey(event.keyCode)) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (playerView?.isControllerFullyVisible == true) {
                        centerWentToController = true
                        return super.dispatchKeyEvent(event)   // activate focused control
                    }
                    centerWentToController = false
                    ExoPlayerConfig.togglePlayPause(p)
                    Log.d(TAG, "⏯ centre key → playWhenReady=${p.playWhenReady}")
                    return true
                }
                // Mirror the DOWN decision on the UP so a focused button gets a
                // full press, or the toggle stays a single clean event.
                return if (centerWentToController) super.dispatchKeyEvent(event) else true
            }
        }

        // One direction press is enough to raise the controls (see the helper —
        // the first press was being spent on Compose focus interop).
        if (ExoPlayerConfig.raiseControllerOnDirectionKey(playerView, event)) return true

        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        // Recorded BEFORE pausing, or we would always read false.
        wasPlayingBeforePause = player?.playWhenReady == true
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        // Only resume what WE paused. A film the user stopped stays stopped.
        if (wasPlayingBeforePause) player?.play()
    }
    override fun onDestroy() {
        // Applied HERE because the player is already released, so no request
        // is in flight and the engine can be shut down properly. Rebuilding
        // mid-playback leaked engines until Cronet stopped building at all.
        ExoPlayerConfig.applyPendingQuicHints()
        Log.d(TAG, "🧹 onDestroy — releasing player")
        // Unconditionally, and before anything can throw. onIsPlayingChanged is not
        // guaranteed to fire false on the way out — a process kill, a crash during
        // teardown, or release() while paused all skip it — and a source left
        // stuck in the playing set would suppress the idle timer for the rest of
        // the process lifetime. This is the only clearing path that always runs.
        IdleWatcher.setPlaying(IdleWatcher.SOURCE_HLS, false)
        // BEFORE the player is released — stopWatchSession reads currentPosition,
        // and a released player reports nothing, which would close every session
        // at position 0 and undo the whole point of tracking it.
        stopWatchSession()
        progressJob?.cancel()
        recoveryWatchdogJob?.cancel()
        progressiveRefreshJob?.cancel()
        stallWatchdogJob?.cancel()
        toastJob?.cancel()
        captionTickerJob?.cancel()
        playerView?.player = null
        playerView = null
        player?.run { playWhenReady = false; stop(); clearMediaItems(); release() }
        player = null
        super.onDestroy()
    }

    companion object {
        /**
         * How many times to re-draw a segment CDN before giving up.
         *
         * WHY 4, NOT 2: VidFast hands each extraction a segment host from a pool.
         * MEASURED 2026-08-03 on this line — `moon.ironwallnet.net` (playlists),
         * `premiumvacations.site`, `checknews02.site`, `sandstorm13.site` all
         * serve fine, while `signtime.site` is severed by the ISP filter. So a
         * failure is usually ONE bad draw out of ~5, not a dead episode: the same
         * episode failed, then played after a fresh extraction.
         *
         * At 2 retries a run of bad luck still ends in a visible failure. At 4 the
         * odds of drawing the blocked host every single time get small, and the
         * cost of an extra attempt is one ~2s extraction (deduplicated server-side).
         */
        private const val MAX_EXTRACT_RETRIES = 4
        /** How many times a 429 is waited out before the source is abandoned. */
        private const val MAX_RATE_LIMIT_RETRIES = 3
        /** First back-off; doubles each attempt (4s, 8s, 16s = 28s total).
         *  Long enough for a per-minute window to roll, short enough that a
         *  viewer does not assume the app has died. */
        private const val RATE_LIMIT_BASE_DELAY_MS = 4_000L

        /**
         * Pause before re-extracting. The point is to draw a DIFFERENT host, and
         * hammering the backend instantly tends to return the same assignment;
         * a beat also keeps a genuinely dead episode from burning all four
         * attempts inside a second and looking like a crash.
         */
        private const val REEXTRACT_BACKOFF_MS = 700L

        /**
         * How often to report the playback position to the backend.
         *
         * This is the resume point's resolution AND its worst-case loss: if the
         * app is killed, the last heartbeat is what survives, so 20s means at most
         * 20s of re-watching. Cheap — one tiny POST per interval per playing
         * device, and only while something is actually playing.
         */
        private const val PROGRESS_HEARTBEAT_MS = 20_000L

        /**
         * How long a recovery attempt gets to actually produce playback before it
         * is reported as stalled.
         *
         * ADDED 2026-08-17 after a failure that was invisible end to end: a movie's
         * URL 404'd at 59:02, the relay swap failed (it proxies the same dead URL),
         * re-extraction ran, the backend answered 200 with a fresh URL — and then
         * nothing. No decoder, no error, no toast, no finish(). The activity sat
         * there with a released player, which is exactly what "the video just
         * ended but the player stayed open" looks like.
         *
         * Nothing in the logs explained it, because the whole recovery path logs at
         * w/d level and R8 strips those from release builds. This watchdog is the
         * backstop for that entire class: if a recovery ran and playback is not
         * going N seconds later, say so out loud and file a report.
         *
         * 25s > CONNECT(15s) + a slow manifest fetch, so a merely-slow recovery is
         * not reported as a stall.
         */
        private const val RECOVERY_WATCHDOG_MS = 25_000L

        /**
         * When to pre-emptively re-extract a PROGRESSIVE stream.
         *
         * Progressive sources hand back one URL for the whole file, and VidNest's
         * expire after roughly an hour. A 97-minute film therefore cannot finish:
         * measured 2026-08-17, "Unthinkable" died at 59:02 with a 404 on
         * hlmv.tripplestream.online. Nothing was wrong with the player or the
         * network — the URL simply outlived less than the movie.
         *
         * HLS does not need this: the playlist is re-fetched continuously, so a
         * refreshed token arrives on its own. Only the one-URL-per-film case is
         * exposed, which is why this is gated on looksProgressive().
         *
         * 50 minutes buys a ~10 minute margin under the observed hour, and is late
         * enough that anything under ~50 minutes never pays for an extra extraction.
         */
        private const val PROGRESSIVE_REFRESH_MS = 50L * 60L * 1000L

        /**
         * Retry gap after a refresh attempt that declined to swap.
         *
         * Short enough to get two or three more tries inside the ~10 minute margin
         * before the URL actually expires, long enough not to hammer extraction
         * while a stream is playing perfectly well.
         */
        private const val PROGRESSIVE_REFRESH_RETRY_MS = 3L * 60L * 1000L
    }
}
