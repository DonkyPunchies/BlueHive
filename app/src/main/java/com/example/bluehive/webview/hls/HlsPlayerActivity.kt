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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
import com.example.bluehive.webview.exoplayer_resources.ExoPlayerConfig
import com.example.bluehive.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    // One watch-history row per launch, written on confirmed playback (see
    // onIsPlayingChanged). The flag survives a mid-play re-extract, so recovering
    // from a dead CDN doesn't log the same title twice.
    private var watchLogged = false

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
                com.example.bluehive.api.ApiClient.bluehiveApi.logWatchHistory(
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
                Log.d(TAG, "✅ Watch history logged on PLAYBACK: tmdb=$mediaId ($source)")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Watch-history log failed (non-fatal): ${e.message}")
            }
        }
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

        val mediaSource = buildMediaSource(url)

        // Release any previous instance before rebuilding (retry path).
        playerView?.player = null
        player?.run { stop(); clearMediaItems(); release() }

        // Buffering / bitrate / player construction all come from the shared
        // ExoPlayerConfig so every site's player behaves identically. Only this
        // file's HEADERS are site-specific (see buildMediaSource).
        val selector = trackSelector ?: ExoPlayerConfig.trackSelector(this).also { trackSelector = it }
        ExoPlayerConfig.applyBitratePolicy(selector)

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
                ExoPlayerConfig.attachStallWatchdog(this, lifecycleScope, TAG)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && !loggedDuration) {
                            loggedDuration = true
                            Log.d(TAG, "⏱ duration=${player?.duration}ms | isLive=${player?.isCurrentMediaItemLive}")
                        }
                    }

                    // ✅ Watch history is written HERE, on confirmed playback — not by
                    // the details screen when extraction succeeded. A captured stream
                    // that never actually plays (dead CDN, failed re-extract, user
                    // backing out) must not count as watched. Guarded so a pause or a
                    // mid-play re-extract can't log the same title twice.
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying && !watchLogged) {
                            watchLogged = true
                            logWatchOnPlaybackConfirmed()
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
                            if (embedUrl == null) Log.e(TAG, "no EMBED_URL passed — cannot re-extract; finishing")
                            finish()
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
                }
            }
        }
    }

    /** Video-only HLS source. Captions are rendered separately (see class doc). */
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

        return ExoPlayerConfig.hlsMediaSource(
            url,
            ExoPlayerConfig.dataSourceFactory(ua, requestHeaders)
        )
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

    /** Fire the headless extractor again for the same embed URL. Result handled above. */
    private fun relaunchExtraction() {
        val embed = embedUrl ?: run { finish(); return }
        player?.run { stop(); clearMediaItems(); release() }
        player = null
        // Which extractor to re-run is the caller's decision — this player is
        // shared by several sources and must not hardcode one of them. No class
        // name means no recovery path, so stop rather than guess.
        val extractorClass = intent.getStringExtra(HlsExtract.EXTRA_EXTRACTOR_CLASS)
        if (extractorClass.isNullOrBlank()) {
            Log.e(TAG, "no EXTRACTOR_CLASS passed — cannot re-extract; finishing")
            finish(); return
        }
        val relaunch = Intent()
            .setClassName(this, extractorClass)
            .putExtra(HlsExtract.EXTRA_URL, embed)
            // Hosts read their own key name; send both so either matches.
            .putExtra("VIDAPI_URL", embed)
            .putExtra("VIDFAST_URL", embed)
        reextractLauncher.launch(relaunch)
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
        toastJob?.cancel()
        captionTickerJob?.cancel()
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
