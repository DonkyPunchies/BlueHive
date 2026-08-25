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
import com.example.bluehive.StillWatchingOverlay
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
import java.util.concurrent.TimeUnit

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
            ApiClient.bluehiveApi.extractStream(
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
        if (!probeStreamReachable(body.m3u8)) {
            recoveryLog("pre-emptive refresh: replacement URL did not answer — KEEPING current stream")
            rescheduleProgressiveRetry(); return
        }

        // Committed. Everything from here mirrors a normal recovery swap.
        player?.let { if (it.currentPosition > 0) resumePositionMs = it.currentPosition }
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
     */
    private suspend fun probeStreamReachable(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-1")
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Referer", referer)
                connectTimeout = 10_000
                readTimeout    = 10_000
                instanceFollowRedirects = true
            }
            val code = c.responseCode
            c.disconnect()
            code in 200..299
        }.getOrDefault(false)
    }

    // --- stream params (kept so we can rebuild on retry) ---
    private var embedUrl: String? = null
    private var currentM3u8: String = ""
    private var resumePositionMs: Long = 0
    private var referer: String = "https://cinesrc.st/"
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

    // Subtitle timing offset in ms (+ = show later, − = show earlier), persisted per title.
    private var subtitleOffsetMs: Long = 0
    private var subOffsetLabel: TextView? = null
    private var ccButton: ImageButton? = null
    private val subPrefs by lazy { getSharedPreferences("vidapi_sub_offsets", MODE_PRIVATE) }

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

        // Restore the saved timing offset for this title (sync once, remembered).
        subtitleOffsetMs = if (tmdbId > 0) subPrefs.getLong(tmdbId.toString(), 0L) else 0L
        if (subtitleOffsetMs != 0L)
            Log.d(TAG, "subs: restored saved timing offset ${subtitleOffsetMs}ms for tmdb=$tmdbId")

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
        Log.d(TAG, "▶️ playing vidapi m3u8: $url (referer=$referer)")
        // Re-armed on EVERY startPlayback, including recovery rebuilds: a fresh
        // URL restarts the expiry clock, so the old timer would fire early and the
        // new one would never be set at all.
        scheduleProgressiveRefresh(url)

        val mediaSource = buildMediaSource(url)

        // Release any previous instance before rebuilding (retry path).
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
                ExoPlayerConfig.attachStallWatchdog(this, lifecycleScope, TAG) {
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
                        IdleWatcher.setPlaying(IdleWatcher.SOURCE_HLS, isPlaying) {
                            runOnUiThread { player?.pause() }
                        }

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

                        val isCdnFailure =
                            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                                    error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
                        // Relay first — see switchToRelay for why re-extraction
                        // cannot fix a blocked CDN.
                        if (isCdnFailure && switchToRelay(error.errorCodeName)) return
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
                playWhenReady = true
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
                                .padding(end = 28.dp, bottom = 28.dp)
                                .background(Color(0xE6000000), RoundedCornerShape(10.dp))
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                        )
                    }

                    // "Still watching?" — LAST, so it draws above the captions, the
                    // toast and the subtitle picker. It takes no focus and consumes
                    // no input (any key dismisses it through the window hook), so
                    // sitting on top costs the picker nothing if both are somehow up.
                    StillWatchingOverlay()
                }
            }
        }
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

        val isBackendRelay = try {
            val backendHost = android.net.Uri.parse(BuildConfig.API_BASE_URL).host
            val streamHost  = android.net.Uri.parse(url).host
            backendHost != null && backendHost.equals(streamHost, ignoreCase = true)
        } catch (_: Exception) { false }

        val requestHeaders = mutableMapOf(
            "User-Agent" to ua,
            "Referer" to referer,
            "Origin" to originHeader,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        if (isBackendRelay) requestHeaders["X-API-Key"] = BuildConfig.API_KEY

        val factory = ExoPlayerConfig.dataSourceFactory(ua, requestHeaders, context = this)

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

        Log.d(TAG, "🎞 source=${if (looksProgressive) "progressive" else "hls"} url=${url.take(90)}")

        return if (looksProgressive) ExoPlayerConfig.progressiveMediaSource(url, factory)
               else ExoPlayerConfig.hlsMediaSource(url, factory)
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
                        val options = if (tmdbId > 0) HlsSubtitles.listEnglishOptions(
                            tmdbId    = tmdbId,
                            mediaType = intent.getStringExtra("MEDIA_TYPE") ?: "movie",
                            season    = intent.getIntExtra("SEASON_NUMBER", 0),
                            episode   = intent.getIntExtra("EPISODE_NUMBER", 0),
                        ) else emptyList()

                        // Fetch every candidate at once. They're ~40 KB each, and
                        // downloading five serially would make the panel crawl.
                        coroutineScope {
                            val stream = if (!fromStream.isNullOrBlank())
                                async { HlsSubtitles.streamChoice(this@HlsPlayerActivity, fromStream, key) }
                            else null
                            val rest = options.map { opt ->
                                async { HlsSubtitles.downloadChoice(this@HlsPlayerActivity, opt, key) }
                            }
                            listOfNotNull(stream?.await()) + rest.awaitAll().filterNotNull()
                        }
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
                    if (File(choice.path).exists()) parseVtt(choice.path) else null
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
            lastCueKey = noCueKey
            updateCcButtonState()
            startCaptionTicker()
            Log.d(TAG, "subs: captions loaded — ${choice.label} (${cues.size} cues), offset=${subtitleOffsetMs}ms")
            closeSubtitlePicker()
            showToast(
                if (subtitleOffsetMs == 0L)
                    "${choice.label} on. Still off? Nudge it with the ◀ / ▶ buttons."
                else
                    "${choice.label} on (saved timing ${fmtOffset(subtitleOffsetMs)})."
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
                val t = p.currentPosition - subtitleOffsetMs   // +offset ⇒ cue shows later
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
            text = fmtOffset(subtitleOffsetMs)
        }
        val later = syncButton(R.drawable.ic_sub_later, "Subtitles later") { nudgeOffset(+250) }
        val reset = syncButton(R.drawable.ic_sub_reset, "Reset subtitle timing") { resetOffset() }

        ccButton = cc
        subOffsetLabel = label
        updateCcButtonState()

        // Prepend the cluster to the right-hand controls, in order.
        listOf(cc, earlier, label, later, reset).forEachIndexed { i, v -> bar.addView(v, i) }
        Log.d(TAG, "subs: injected caption + timing controls into control bar")
    }

    private fun nudgeOffset(deltaMs: Int) {
        subtitleOffsetMs = (subtitleOffsetMs + deltaMs).coerceIn(-60_000L, 60_000L)
        subOffsetLabel?.text = fmtOffset(subtitleOffsetMs)
        persistOffset()
        // Nudging the timing implies wanting captions visible — but only while
        // one is still selected. After an explicit turn-off there is nothing to
        // re-enable, and resurrecting it there would undo the user's choice.
        if (captionsLoaded && !captionsOn && activeSubtitleIndex >= 0) {
            captionsOn = true; updateCcButtonState()
        }
        renderActiveCues()                                 // instant, no reload
        Log.d(TAG, "subs: timing offset → ${subtitleOffsetMs}ms")
    }

    private fun resetOffset() {
        subtitleOffsetMs = 0
        subOffsetLabel?.text = fmtOffset(0)
        persistOffset()
        renderActiveCues()
        Log.d(TAG, "subs: timing offset reset to 0")
    }

    private fun persistOffset() {
        if (tmdbId > 0) subPrefs.edit().putLong(tmdbId.toString(), subtitleOffsetMs).apply()
    }

    private fun updateCcButtonState() { ccButton?.alpha = if (captionsOn) 1f else 0.45f }

    private fun fmtOffset(ms: Long): String = String.format(Locale.US, "%+.2fs", ms / 1000.0)

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
    private fun switchToRelay(why: String): Boolean {
        val relay = relayUrl
        if (relay == null || usingRelay) return false
        usingRelay = true
        // Remember the position BEFORE the teardown so recovery resumes.
        player?.let { if (it.currentPosition > 0) resumePositionMs = it.currentPosition }
        // Tags the relay session with what drove us here; lands in
        // bluehive.relay_sessions.error_code via the master-playlist request.
        val tagged = relay + "&err=" + URLEncoder.encode(why, "UTF-8")
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
        player?.let { if (it.currentPosition > 0) resumePositionMs = it.currentPosition }
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
                ApiClient.bluehiveApi.extractStream(
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
            if (!probeStreamReachable(body.m3u8)) {
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

    override fun onPause()  { super.onPause();  player?.pause() }
    override fun onResume() { super.onResume(); player?.play() }
    override fun onDestroy() {
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
