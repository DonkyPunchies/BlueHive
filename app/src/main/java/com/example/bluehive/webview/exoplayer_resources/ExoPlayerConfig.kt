package com.example.bluehive.webview.exoplayer_resources

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * ONE place for every ExoPlayer setting shared by the site-specific players
 * (Miruro, VidFast/VidApi via HlsPlayerActivity, and anything added later).
 *
 * WHY THIS EXISTS
 * ---------------
 * These values were duplicated verbatim in each player. They were identical when
 * this file was written — same buffers, same timeouts, same bitrate policy — which
 * is exactly the problem: nothing kept them that way. Tuning one player for a
 * low-RAM box or a flaky CDN silently left the others behind, and the divergence
 * only shows up as "why does this site stutter and that one doesn't".
 *
 * WHAT BELONGS HERE vs. IN A SITE'S PLAYER
 * ----------------------------------------
 *   HERE  — anything site-agnostic: buffer sizes, network timeouts, track
 *           selection policy, player construction, media-source wiring.
 *   THERE — request HEADERS. Those are genuinely per-site: Miruro pins
 *           Origin to miruro.tv, while the extracted-CDN players derive Referer
 *           from the embed page and add X-API-Key only when the stream is being
 *           relayed through our own backend. Forcing those into one shape would
 *           break the very thing that makes each CDN accept the request, so
 *           headers stay the caller's job and are passed in.
 *
 * All tuning knobs are consts so a change lands everywhere at once.
 */
@UnstableApi
object ExoPlayerConfig {

    // ── Buffering ────────────────────────────────────────────────────────────
    // Generous so 1080p (~5.5 Mbps) rides out CDN/proxy hiccups without
    // stalling. Raising MIN/MAX costs RAM — relevant on the 2 GB boxes — so
    // change these together and re-test there, not just on a roomy device.
    const val MIN_BUFFER_MS = 30_000
    const val MAX_BUFFER_MS = 120_000
    /** How much must buffer before playback starts. Lower = faster start, more stall risk. */
    const val BUFFER_FOR_PLAYBACK_MS = 2_500

    /**
     * How much must buffer to RESUME after a stall. Playback restarts by itself the
     * moment this much is banked — no button press, as long as the user hasn't
     * pressed pause during the stall.
     *
     * Was 10s. A measured stall (5940 kbps stream, ~0.5 Mbps effective throughput)
     * froze at the 2.9s mark and took roughly THREE MINUTES to come back, because
     * banking 10s of a 5.9 Mbps stream at that rate is a long wait — and there's
     * nothing on screen saying so, which reads as "the player is dead". 5s is still
     * 2× the start threshold, so it isn't fragile, but it recovers in seconds.
     *
     * The trade: on a genuinely slow source, resuming on a thinner cushion can mean
     * a few short stalls instead of one long one. That's the intended swap —
     * repeated brief hiccups are legible as "bad connection"; a three-minute freeze
     * is not.
     */
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

    // ── Network ──────────────────────────────────────────────────────────────
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS    = 30L

    /**
     * Jump size for the onn box's CHANNEL UP / CHANNEL DOWN keys (and the Fire TV
     * remote's ⏩ / ⏪). Shared so a skip feels the same on every site's player.
     */
    const val SEEK_INCREMENT_MS = 10_000L

    /**
     * The remote's centre/OK button, across onn, Fire TV and generic TV remotes.
     * Pressing it should act on playback IMMEDIATELY — without first pressing a
     * direction to raise the controls, which is how a bare PlayerView behaves.
     */
    fun isCenterKey(keyCode: Int): Boolean =
        keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER

    /**
     * Toggle playback. Flips `playWhenReady` rather than checking `isPlaying`,
     * because `isPlaying` is briefly false mid-buffer — reading it there makes the
     * button feel like it "only registers sometimes". If playback already ended,
     * restart from the top instead of doing nothing.
     */
    fun togglePlayPause(player: ExoPlayer) {
        when {
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(0); player.play() }
            player.playWhenReady -> player.pause()
            else -> player.play()
        }
    }

    /**
     * Seek relative to the current position, clamped so a skip can't run past the
     * end or before zero. Live/unknown-duration streams only get the lower clamp.
     * Returns the position actually seeked to, for logging.
     */
    fun seekBy(player: ExoPlayer, deltaMs: Long): Long {
        val duration = player.duration
        val current  = player.currentPosition
        val target = if (duration > 0) {
            (current + deltaMs).coerceIn(0L, duration)
        } else {
            (current + deltaMs).coerceAtLeast(0L)
        }
        player.seekTo(target)
        return target
    }

    /**
     * Desktop-Chrome UA. Extracted CDNs frequently gate on a browser-looking UA,
     * and it must MATCH the one the extractor used when the token was minted —
     * some CDNs bind the token to it. Callers normally pass the extractor's UA
     * through; this is the fallback when none was captured.
     */
    const val CHROME_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Pin to the highest rung instead of ramping adaptively.
     *
     * HLS master playlists default to ADAPTIVE selection, so ExoPlayer starts low
     * and climbs — on a TV that reads as "it started blurry". These are
     * short-lived extracted streams on (usually) wired/strong Wi-Fi, so going
     * straight to the top rung is the better trade. No-op for single-quality
     * playlists. Set false if you ever want adaptive behaviour back.
     */
    const val FORCE_HIGHEST_BITRATE = true

    // ── Time bar focus tint ──────────────────────────────────────────────────
    // The scrubber is D-pad focusable, but ExoPlayer gives it no focus styling —
    // so on a TV there's nothing telling you it's selected and about to respond to
    // left/right. These turn it blue while focused. Tune freely.
    /** Played portion + scrubber knob while the bar HAS focus. */
    const val TIME_BAR_FOCUSED_COLOR   = 0xFF4DA3FF.toInt()
    /** Played portion + scrubber knob when it does NOT (ExoPlayer's white). */
    const val TIME_BAR_UNFOCUSED_COLOR = 0xFFFFFFFF.toInt()

    /**
     * Tint the scrubber blue while it holds D-pad focus.
     *
     * Call once after the PlayerView exists. Posted to the view's queue because
     * the time bar is inflated by PlayerControlView, so it does not exist yet at
     * construction time — looking it up synchronously returns null. Safe to call
     * when the controller is hidden; no-ops if the bar can't be found.
     */
    fun attachTimeBarFocusTint(playerView: androidx.media3.ui.PlayerView) {
        playerView.post {
            val bar = playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(
                androidx.media3.ui.R.id.exo_progress
            ) ?: return@post

            fun tint(focused: Boolean) {
                val c = if (focused) TIME_BAR_FOCUSED_COLOR else TIME_BAR_UNFOCUSED_COLOR
                bar.setPlayedColor(c)
                bar.setScrubberColor(c)
            }

            tint(bar.hasFocus())
            bar.onFocusChangeListener =
                android.view.View.OnFocusChangeListener { _, hasFocus -> tint(hasFocus) }
        }
    }

    // ── Controller (on-screen control bar) ───────────────────────────────────

    /**
     * How long the controls stay up before fading out. media3's default is 5s.
     *
     * Note this is the PLAYING timeout. media3 deliberately keeps the controls up
     * indefinitely while paused / idle / ended (shouldShowControllerIndefinitely),
     * which is the behaviour you want — a paused player shouldn't hide its own
     * scrubber. So "3 seconds" means 3 seconds of playback, not of wall clock.
     */
    const val CONTROLLER_SHOW_TIMEOUT_MS = 3_000

    /** The four D-pad directions — the keys that should raise the controls. */
    fun isDirectionKey(keyCode: Int): Boolean =
        keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT

    /**
     * Is the control bar on screen?
     *
     * NOT just `isControllerFullyVisible`: that one is false for the whole duration
     * of the fade-IN animation, so a second press arriving a beat after the first
     * would be judged "controls hidden" and get eaten. Falling back to the
     * controller view's own visibility closes that window.
     */
    fun isControllerShowing(playerView: androidx.media3.ui.PlayerView?): Boolean {
        val pv = playerView ?: return false
        if (pv.isControllerFullyVisible) return true
        val ctrl = pv.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_controller)
        return ctrl != null && ctrl.visibility == android.view.View.VISIBLE
    }

    /**
     * ONE D-pad press raises the controls — not two.
     *
     * PlayerView does show the controls on the first direction key IT sees, but on
     * a TV that key usually never reaches it: the players host the PlayerView in a
     * Compose `AndroidView`, so the first press is spent by the Compose focus
     * system moving focus into the interop view, and only the second press gets
     * through. Intercepting in the Activity's dispatchKeyEvent — ahead of the whole
     * view hierarchy — skips that entirely.
     *
     * Only the ACTION_DOWN is consumed. The paired ACTION_UP is harmless because
     * both ViewRootImpl and Compose only perform focus movement on ACTION_DOWN,
     * and buttons fire on centre, not on directions.
     *
     * @return true if the key was consumed to raise the controls. When the controls
     *         are ALREADY up this returns false, so directions pass through and
     *         navigate between the controls as usual.
     */
    fun raiseControllerOnDirectionKey(
        playerView: androidx.media3.ui.PlayerView?,
        event: android.view.KeyEvent,
    ): Boolean {
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return false
        if (!isDirectionKey(event.keyCode)) return false
        val pv = playerView ?: return false
        if (isControllerShowing(pv)) return false
        pv.showController()   // applies CONTROLLER_SHOW_TIMEOUT_MS and focuses play/pause
        return true
    }

    /**
     * Everything a site's player wants from its PlayerView: the shared show
     * timeout, the focus tint, and the buffering spinner. Call once, in the
     * PlayerView factory.
     */
    fun applyControllerPolicy(playerView: androidx.media3.ui.PlayerView) {
        playerView.setControllerShowTimeoutMs(CONTROLLER_SHOW_TIMEOUT_MS)
        attachTimeBarFocusTint(playerView)

        // ── Show something while starved ────────────────────────────────────
        // PlayerView ships a spinner (R.id.exo_buffering) but defaults to
        // SHOW_BUFFERING_NEVER, so a stall rendered as a frozen frame with no
        // explanation. That's how a recoverable rebuffer read as "the player is
        // dead" — and pressing keys at that point PAUSES it, turning a stall that
        // would have healed itself into one that needs a button press.
        //
        // WHEN_PLAYING, not ALWAYS: the spinner appears only while the user
        // actually wants playback, so a deliberate pause stays clean.
        //
        // Note it appears the instant buffering starts, with no grace delay —
        // media3 exposes no threshold. A brief network hiccup therefore flashes
        // the spinner. That's honest feedback ("the connection stuttered") and
        // beats a bespoke delayed overlay; swap in a custom one if it reads as
        // noisy.
        playerView.setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
    }

    /**
     * Stop the TV going to sleep mid-episode.
     *
     * Nothing else does this: our players never set the flag, and media3's
     * PlayerView never touches keepScreenOn either (verified — zero references in
     * 1.9.0). Video playback on its own does NOT suppress the system screensaver;
     * only a window flag does. So a 45-minute episode watched without touching the
     * remote was relying on nothing at all.
     *
     * Gated on playWhenReady rather than isPlaying ON PURPOSE: isPlaying goes false
     * during a rebuffer, and a long stall is precisely when you must NOT let the
     * screen drop — the user is sitting there waiting for it to recover. The screen
     * stays awake whenever playback is *intended*, and is released on a real pause,
     * on the end of the episode, and on teardown.
     *
     * Set on the Activity window, not the view, so it survives the player being
     * rebuilt by a mid-play re-extract.
     */
    fun keepScreenOnWhilePlaying(activity: android.app.Activity, player: ExoPlayer) {
        fun sync() {
            val wanted = player.playWhenReady &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED
            if (wanted) {
                activity.window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            } else {
                activity.window.clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }
        }
        sync()
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = sync()
            override fun onPlaybackStateChanged(playbackState: Int) = sync()
        })
    }

    // ── Stall watchdog ───────────────────────────────────────────────────────

    /** No position movement for this long, with the loader idle, means wedged. */
    const val STALL_WEDGE_MS = 10_000L
    /** Backstop: fire even if the loader claims to be busy. */
    const val STALL_HARD_MS = 30_000L
    /** Seeks that failed to unwedge before we re-prepare the whole source. */
    const val STALL_SEEKS_BEFORE_REPREPARE = 2

    /**
     * Recover from a playback deadlock the player itself never reports.
     *
     * MEASURED FAILURE (2026-07-30, VidFast 1080p on the Amlogic box): playback
     * froze for ~10 minutes with playWhenReady still true. The vendor log showed
     *
     *     W/C2BqBuffer: last successful dequeue was 235810016 us ago
     *     VDA: INs=5637/5645, OUTs=5637/5637 {IN=8/8, OUT=12/12[kAtClient(12)]}
     *
     * i.e. the video codec could not get an output buffer from the display's
     * BufferQueue for 235 SECONDS because all 12 were held by the client and
     * never returned. Everything else followed from that: the codec couldn't
     * decode, its input backed up to 8/8, the loader stopped because the buffer
     * was full, and the audio clock stopped with it. Network, CDN, wifi and the
     * TS container were all verified clean — this is the vendor display stack,
     * not the stream. It eventually freed itself when the audio HAL came out of
     * standby, which is why it looked like an endless buffer rather than a crash.
     *
     * ExoPlayer raises no error for this, so nothing recovered. seekTo() does:
     * it flushes the codec, and MediaCodec.flush() RETURNS EVERY CLIENT-HELD
     * OUTPUT BUFFER — precisely the resource that was stuck. A ten-minute
     * deadlock becomes a sub-second blip.
     *
     * This is a recovery, not a cure. The vendor stack will still hiccup; we
     * just stop paying minutes for it.
     *
     * [isLoading] is the discriminator that keeps this from firing on an honest
     * rebuffer: a real one is actively fetching, a wedge is not.
     */
    fun attachStallWatchdog(
        player: ExoPlayer,
        scope: kotlinx.coroutines.CoroutineScope,
        tag: String,
    ) {
        scope.launch {
            var lastPos = -1L
            var stalledMs = 0L
            var seeks = 0
            // delay() is cancellable, so cancelling the scope ends this loop —
            // no isActive check needed.
            while (true) {
                delay(1_000)

                val shouldBeMoving = player.playWhenReady &&
                    player.playbackState != Player.STATE_IDLE &&
                    player.playbackState != Player.STATE_ENDED
                if (!shouldBeMoving) { lastPos = -1L; stalledMs = 0L; seeks = 0; continue }

                val pos = player.currentPosition
                if (pos != lastPos) { lastPos = pos; stalledMs = 0L; seeks = 0; continue }

                stalledMs += 1_000
                val wedged = (!player.isLoading && stalledMs >= STALL_WEDGE_MS) ||
                    stalledMs >= STALL_HARD_MS
                if (!wedged) continue

                if (seeks < STALL_SEEKS_BEFORE_REPREPARE) {
                    seeks++
                    Log.w(
                        tag,
                        "🩺 wedged at ${pos}ms for ${stalledMs}ms " +
                            "(state=${stateName(player.playbackState)}, loading=${player.isLoading}) " +
                            "— flushing codec via seek, attempt $seeks"
                    )
                    // Same position: a codec flush, not a user-visible jump.
                    player.seekTo(pos)
                } else {
                    Log.e(tag, "🩺 still wedged after $seeks seeks — re-preparing the source")
                    player.prepare()
                    seeks = 0
                }
                stalledMs = 0L
            }
        }
    }

    // ── Playback diagnostics ─────────────────────────────────────────────────

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE      -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY     -> "READY"
        Player.STATE_ENDED     -> "ENDED"
        else                   -> "?$state"
    }

    /**
     * Log the things that actually explain a stall.
     *
     * Written after a freeze had to be diagnosed from Amlogic's own VDA and
     * C2BqBuffer logs, because the app recorded nothing between "playing" and
     * "still playing ten minutes later". These are the events that would have
     * named it in one line.
     */
    fun attachPlaybackLog(player: ExoPlayer, tag: String) {
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onPlaybackStateChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                state: Int,
            ) {
                Log.d(tag, "📺 state → ${stateName(state)} @ ${eventTime.currentPlaybackPositionMs}ms")
            }

            override fun onIsLoadingChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                isLoading: Boolean,
            ) {
                Log.d(tag, "📥 loading=$isLoading @ ${eventTime.currentPlaybackPositionMs}ms")
            }

            override fun onDroppedVideoFrames(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                Log.w(tag, "🎞 dropped $droppedFrames frames in ${elapsedMs}ms")
            }

            override fun onLoadError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean,
            ) {
                Log.w(
                    tag,
                    "⚠️ load error (canceled=$wasCanceled) ${error.javaClass.simpleName}: ${error.message} " +
                        "— ${loadEventInfo.uri.toString().takeLast(70)}"
                )
            }

            override fun onBandwidthEstimate(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                // Only when it drops near the stream's own bitrate — otherwise
                // this fires constantly and buries everything else.
                if (bitrateEstimate in 1 until 2_500_000) {
                    Log.w(tag, "📉 bandwidth estimate ${bitrateEstimate / 1000} kbps")
                }
            }

            override fun onAudioUnderrun(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                // The audio clock starving is what froze the whole pipeline in the
                // 2026-07-30 wedge — see attachStallWatchdog.
                Log.w(
                    tag,
                    "🔇 audio underrun: buffer=${bufferSizeMs}ms, " +
                        "${elapsedSinceLastFeedMs}ms since last feed"
                )
            }
        })
    }

    // ── "What am I actually playing?" ────────────────────────────────────────

    /**
     * Log the video rung the player ACTUALLY selected, plus every rung the stream
     * offered. One line, once, a beat into playback:
     *
     *   🎬 video: 1920x1080 @ 4500kbps (avc1.640028) — 3 rungs offered: 1080p, 720p, 480p
     *
     * WHY THIS EXISTS
     * ---------------
     * "Are we getting the best quality?" was previously answerable only by pulling
     * a live CDN token out of logcat and fetching the master playlist by hand
     * before it expired — and even that only shows what was OFFERED, never what
     * ExoPlayer CHOSE. Those are different facts, and the difference is exactly
     * where a silent downgrade hides: 480p upscaled on a TV across the room reads
     * as "a bit soft", not as broken.
     *
     * The rung count also settles a real open question for free. The extractor
     * takes the first non-ad .m3u8 it sees and never checks whether it's a master
     * or a single-rendition playlist. "1 rung offered" in this line means we got a
     * lone variant and there was nothing to pick — which is the evidence that
     * would justify teaching the extractor to prefer masters. Until it shows up,
     * that fix is speculative.
     *
     * Purely diagnostic — reads state, changes nothing. Deduped, because
     * onTracksChanged fires on every period/selection change; a repeat line
     * therefore means the rung genuinely moved (a real downshift worth seeing).
     */
    fun attachVideoTrackLog(player: ExoPlayer, tag: String) {
        var lastLine = ""
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                var chosen: androidx.media3.common.Format? = null
                val rungs = mutableListOf<Int>()

                tracks.groups.forEach { group ->
                    if (group.type != androidx.media3.common.C.TRACK_TYPE_VIDEO) return@forEach
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        if (format.height != androidx.media3.common.Format.NO_VALUE) {
                            rungs += format.height
                        }
                        if (group.isTrackSelected(i)) chosen = format
                    }
                }
                if (chosen == null && rungs.isEmpty()) return   // audio-only event

                // Copied out because Kotlin won't smart-cast a captured var.
                val sel = chosen
                val noValue = androidx.media3.common.Format.NO_VALUE
                val res = if (sel != null && sel.width != noValue && sel.height != noValue) {
                    "${sel.width}x${sel.height}"
                } else "unknown"
                val kbps = sel?.bitrate?.takeIf { it != noValue }?.let { " @ ${it / 1000}kbps" } ?: ""
                val codec = sel?.codecs?.let { " ($it)" } ?: ""
                val offered = rungs.sortedDescending().joinToString(", ") { "${it}p" }

                val line = "🎬 video: $res$kbps$codec — " +
                        "${rungs.size} rung(s) offered: ${offered.ifEmpty { "none reported" }}"
                if (line != lastLine) {
                    lastLine = line
                    android.util.Log.d(tag, line)
                }
            }
        })
    }

    // ── Factories ────────────────────────────────────────────────────────────

    /** Shared buffering policy. */
    fun loadControl(): LoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            // Prefer keeping playback going over strictly honouring size limits —
            // the right call for a video player on a memory-tight device.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

    /** Track selector with the shared bitrate policy applied. */
    fun trackSelector(context: Context): DefaultTrackSelector =
        DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters().setForceHighestSupportedBitrate(FORCE_HIGHEST_BITRATE)
            )
        }

    /** Applies the shared bitrate policy to a selector that already exists. */
    fun applyBitratePolicy(selector: DefaultTrackSelector) {
        selector.setParameters(
            selector.buildUponParameters().setForceHighestSupportedBitrate(FORCE_HIGHEST_BITRATE)
        )
    }

    // ── DNS-over-HTTPS ───────────────────────────────────────────────────────
    //
    // Most consumer ISP/router filtering is done at the DNS layer — it's the
    // cheapest place to do it — and DoH walks straight past that: the lookup
    // travels inside HTTPS to a resolver of our choosing, so the network never
    // sees which name was asked for.
    //
    // It does NOT defeat every filter. The Charter/CUJO block measured on
    // 2026-07-31 reads the TLS ClientHello's SNI, not DNS (verified: 1.1.1.1
    // and 8.8.8.8 both returned the real Cloudflare addresses while the
    // connection was still refused). Beating that needs Encrypted Client Hello,
    // which Android's TLS stack does not expose — a separate, larger job.
    // This is the cheap tier that covers the common case.
    private const val DOH_URL = "https://cloudflare-dns.com/dns-query"

    /**
     * Resolve over HTTPS, fall back to the system resolver.
     *
     * The fallback is not optional. Some networks block DoH outright, and a
     * bare DnsOverHttps throws UnknownHostException when it can't reach its
     * resolver — which would turn "this user has a filtered DNS" into "nothing
     * plays at all for anyone whose network dislikes DoH". Strictly better than
     * the status quo, never worse.
     */
    private class DohWithSystemFallback(private val doh: Dns) : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            try {
                doh.lookup(hostname).ifEmpty { Dns.SYSTEM.lookup(hostname) }
            } catch (e: Exception) {
                Log.w("scraper-vidapi", "DoH lookup failed for $hostname (${e.message}) — system DNS")
                Dns.SYSTEM.lookup(hostname)
            }
    }

    /** Built once — the resolver keeps its own connection pool and cache. */
    private val dohResolver: Dns by lazy {
        // bootstrapDnsHosts pins the resolver's OWN addresses. Without it the
        // system resolver has to look up cloudflare-dns.com first, which is
        // exactly the lookup a DNS filter would intercept — self-defeating.
        val doh = DnsOverHttps.Builder()
            .client(OkHttpClient.Builder().build())
            .url(DOH_URL.toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
            )
            .includeIPv6(false)     // the TV boxes are v4-only in practice
            .build()
        DohWithSystemFallback(doh)
    }

    /** HTTP client for stream fetches. Redirects followed — CDNs bounce a lot. */
    fun okHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .dns(dohResolver)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    /**
     * Data-source factory for extracted streams.
     *
     * @param userAgent send the UA the extractor used — some CDNs bind tokens to it.
     * @param headers   the site's own Referer/Origin/etc. See the class note on why
     *                  these are NOT centralised.
     */
    fun dataSourceFactory(
        userAgent: String,
        headers: Map<String, String>,
        client: OkHttpClient = okHttpClient(),
    ): DataSource.Factory =
        // Wrapped so a handshake the NETWORK refuses gets a second chance
        // through an SNI-safe path — see SniSafeDataSource. Pass-through until
        // a fetcher is installed, so this changes nothing today.
        SniSafeDataSource.Factory(
            OkHttpDataSource.Factory(client)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)
        )

    /** HLS media source from an m3u8 URL. */
    fun hlsMediaSource(url: String, factory: DataSource.Factory): HlsMediaSource =
        HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url))

    /**
     * Build a player with the shared buffering + track-selection policy.
     * Pass an existing [selector] to keep a user's quality choice across a retry.
     */
    fun buildPlayer(
        context: Context,
        selector: DefaultTrackSelector = trackSelector(context),
    ): ExoPlayer =
        ExoPlayer.Builder(context)
            .setTrackSelector(selector)
            .setLoadControl(loadControl())
            .build()

    // ── Header helpers (shared shape, per-site values) ────────────────────────

    /**
     * The header set extracted CDNs generally expect. [referer] and [origin] are
     * the parts that vary by site — get them wrong and the CDN 403s even though
     * the URL is valid, which is the single most common extracted-playback failure.
     *
     * @param extra site-specific additions, e.g. X-API-Key when the stream is
     *              relayed through our own backend.
     */
    fun streamHeaders(
        userAgent: String,
        referer: String,
        origin: String,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("Referer", referer)
        put("Origin", origin)
        put("Accept", "*/*")
        put("Accept-Language", "en-US,en;q=0.9")
        putAll(extra)
    }

    /** `https://host` for a URL — the Origin that pairs with a page/CDN Referer. */
    fun originOf(url: String, fallback: String): String = try {
        val u = android.net.Uri.parse(url)
        if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else fallback
    } catch (_: Exception) {
        fallback
    }
}
