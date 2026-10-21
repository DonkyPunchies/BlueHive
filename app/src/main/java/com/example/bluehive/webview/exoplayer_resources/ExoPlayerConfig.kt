package com.example.bluehive.webview.exoplayer_resources

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import com.example.bluehive.BlueHiveApplication

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
    /**
     * @param tintTimeBar whether to drive the time bar's colours from focus.
     *   FALSE for a player whose control layout STATES its colours: this helper
     *   calls setPlayedColor immediately on attach, so the unfocused white would
     *   silently paint over an app:played_color declared in XML -- which is
     *   exactly what turned the HLS player's blue progress line white.
     */
    fun applyControllerPolicy(
        playerView: androidx.media3.ui.PlayerView,
        tintTimeBar: Boolean = true,
    ) {
        playerView.setControllerShowTimeoutMs(CONTROLLER_SHOW_TIMEOUT_MS)
        if (tintTimeBar) attachTimeBarFocusTint(playerView)

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

        // ── Pause leaves focus on PLAY, not on skip-previous ────────────────
        //
        // Press pause on the remote and media3 raises the control bar. Nothing
        // held focus, so Android hands it to the first focusable child of
        // exo_center_controls — which is exo_prev. The button under the cursor
        // after pausing was therefore "skip to previous episode", one click away
        // from losing your place entirely, when the only thing anyone wants
        // there is to resume.
        //
        // HOOKED ON THE PLAYER, NOT ON CONTROLLER VISIBILITY, and that is not a
        // stylistic choice: PlayerView.setControllerVisibilityListener is a
        // single setter, and HlsPlayerActivity installs its own AFTER calling
        // this to inject the subtitle buttons. A listener set here would be
        // silently replaced, and only on that screen — the bug would look fixed
        // in Miruro and untouched in HLS.
        //
        // playWhenReady, not isPlaying: isPlaying also drops on every rebuffer,
        // which would yank focus mid-stall while the viewer is aiming at
        // something else. This fires on a deliberate pause only.
        //
        // posted, because the controller is still being laid out when the
        // callback arrives; requestFocus on a view that is not yet shown is a
        // no-op that returns false. Guarded on isShown so a pause with the bar
        // hidden does not steal focus from an overlay.
        playerView.player?.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) return
                playerView.post {
                    playerView
                        .findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)
                        ?.takeIf { it.isShown && it.isFocusable }
                        ?.requestFocus()
                }
            }
        })
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
     * Re-prepares that failed before we conclude the MANIFEST is dead, not the
     * codec.
     *
     * MEASURED 2026-08-03 (House of the Dragon, ~31 min in): the playback token
     * expired mid-episode. Segments stopped arriving with NO error — the request
     * simply hangs — so onPlayerError never fires and the loop below ran
     * seek/seek/re-prepare forever against a manifest that could never work
     * again. The watchdog was doing its job perfectly on the wrong problem.
     *
     * Re-preparing re-reads the SAME url, so if the url itself is dead this is
     * unwinnable locally. After this many rounds, hand off to the host to fetch
     * a fresh manifest.
     */
    const val STALL_REPREPARES_BEFORE_GIVEUP = 2

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
     *
     * @return the polling Job. The loop holds THIS [player] instance, so a caller
     *   that rebuilds its player must cancel the returned Job before releasing
     *   the old one. Without that, every recovery rebuild in HlsPlayerActivity
     *   added another 1 Hz loop polling a released player until the Activity
     *   died.
     */
    fun attachStallWatchdog(
        player: ExoPlayer,
        scope: kotlinx.coroutines.CoroutineScope,
        tag: String,
        /**
         * Called once when seeks AND re-prepares have both failed — i.e. the url
         * itself is the problem. Null (the default) keeps the original
         * retry-forever behaviour for callers with no way to get a fresh one.
         */
        onManifestDead: (() -> Unit)? = null,
    ): kotlinx.coroutines.Job {
        return scope.launch {
            var lastPos = -1L
            var stalledMs = 0L
            var seeks = 0
            var reprepares = 0
            var gaveUp = false
            // delay() is cancellable, so cancelling the scope ends this loop —
            // no isActive check needed.
            while (true) {
                delay(1_000)

                val shouldBeMoving = player.playWhenReady &&
                    player.playbackState != Player.STATE_IDLE &&
                    player.playbackState != Player.STATE_ENDED
                if (!shouldBeMoving) { lastPos = -1L; stalledMs = 0L; seeks = 0; continue }

                val pos = player.currentPosition
                if (pos != lastPos) {
                    // Moving again — everything recovered, so forget the history.
                    lastPos = pos; stalledMs = 0L; seeks = 0; reprepares = 0; gaveUp = false
                    continue
                }

                stalledMs += 1_000
                val wedged = (!player.isLoading && stalledMs >= STALL_WEDGE_MS) ||
                    stalledMs >= STALL_HARD_MS
                if (!wedged) continue

                if (gaveUp) { stalledMs = 0L; continue }   // handoff already made

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
                } else if (reprepares < STALL_REPREPARES_BEFORE_GIVEUP) {
                    reprepares++
                    Log.e(tag, "🩺 still wedged after $seeks seeks — re-preparing the source " +
                        "($reprepares/$STALL_REPREPARES_BEFORE_GIVEUP)")
                    player.prepare()
                    seeks = 0
                } else if (onManifestDead != null) {
                    // Seeks flush the codec, prepare() re-reads the same url. Both
                    // exhausted means the url is dead — a expired token looks
                    // exactly like this, since it hangs rather than erroring.
                    gaveUp = true
                    Log.e(tag, "🩺 wedged through $STALL_SEEKS_BEFORE_REPREPARE seeks and " +
                        "$reprepares re-prepares — manifest looks dead, asking for a fresh one")
                    onManifestDead()
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

            /**
             * Hosts already reported. An episode is ~630 segments, so without this
             * every successful fetch would log a line; with it, each CDN is named
             * ONCE and a second entry appearing mid-episode means the stream moved
             * to a different host — which matters, because only some of them are
             * reachable on a filtered line.
             *
             * WHY LOG SUCCESS AT ALL: until now only FAILURES named their host, so
             * there was no record of which CDNs actually work. That asymmetry is
             * what made "is signtime.site blocked, or was it never involved?"
             * unanswerable without guessing. MEASURED 2026-08-03: the same master
             * manifest failed on signtime.site and later played fine — the
             * assignment rotates, and only per-play logging can show how often.
             */
            private val seenHosts = mutableSetOf<String>()

            override fun onLoadCompleted(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            ) {
                val host = loadEventInfo.uri.host ?: return
                if (!seenHosts.add(host)) return          // already named this one

                val kind = when (mediaLoadData.dataType) {
                    androidx.media3.common.C.DATA_TYPE_MANIFEST -> "playlist"
                    androidx.media3.common.C.DATA_TYPE_MEDIA    -> "segment"
                    else -> "type${mediaLoadData.dataType}"
                }
                // Last path element identifies the rung (seg-1-s720p-…) or the
                // variant bucket (index-s1080p-…) without printing the token.
                val leaf = loadEventInfo.uri.lastPathSegment ?: ""
                val ms   = loadEventInfo.loadDurationMs.coerceAtLeast(1)
                val kb   = loadEventInfo.bytesLoaded / 1024
                val mbps = (loadEventInfo.bytesLoaded * 8.0) / ms / 1000.0

                Log.i(
                    tag,
                    "🌐 host=$host ($kind) ${kb}KB in ${ms}ms " +
                        "(${"%.1f".format(mbps)} Mbps) — $leaf" +
                        if (seenHosts.size > 1) "  ⇠ HOST #${seenHosts.size} this play" else ""
                )
            }

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

            /**
             * Rung switches. `🎬 video:` only reports the FIRST format, so an
             * adaptive drop mid-episode (bad wifi, a throttling CDN) was
             * invisible — the stream just got blurry with nothing in the log.
             * Also confirms MAX_VIDEO_WIDTH/HEIGHT is actually holding: nothing
             * above 1080p should ever appear here.
             */
            override fun onDownstreamFormatChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            ) {
                val f = mediaLoadData.trackFormat ?: return
                if (f.height <= 0) return                 // audio/subtitle track
                Log.i(
                    tag,
                    "🔀 rung → ${f.width}x${f.height} @ ${f.bitrate / 1000}kbps " +
                        "(${f.codecs}) @ ${eventTime.currentPlaybackPositionMs}ms"
                )
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
                // HOST FIRST. takeLast(70) alone prints only the token tail, which
                // is identical across every rung and every CDN — so a whole night
                // was spent probing a host that had been INFERRED from a playlist
                // fetched elsewhere, never observed from the failing request. The
                // host is the one field that identifies what actually broke.
                val u = loadEventInfo.uri
                Log.w(
                    tag,
                    "⚠️ load error host=${u.host} (canceled=$wasCanceled) " +
                        "${error.javaClass.simpleName}: ${error.message} " +
                        "— …${u.toString().takeLast(45)}"
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

    // ── Video ceiling ────────────────────────────────────────────────────────
    //
    // MEASURED 2026-08-03 on the Amlogic box:
    //   wm size        -> Physical 3840x2160, Override 1920x1080
    //   avc decoder    -> size max 4096x4096   (4K decode IS supported)
    //
    // So the hardware can decode 4K, but Android's surface is 1080p — a 2160p
    // rung is decoded at full size and then downscaled to 1080p for output. That
    // is 4x the decode work and ~3x the bitrate (16 Mbps vs 5.5) for pixels that
    // never reach the screen, on a box with 1.9 GB of RAM.
    //
    // It also feeds the deadlock this file's stall watchdog exists for: that bug
    // is display buffers never being returned, and 4K buffers are ~4x larger and
    // scarcer. Capping is mitigation, NOT a cure — the same wedge was recorded at
    // 1080p in July — but it costs nothing visible.
    //
    // Raise this if the surface override ever goes 4K, or on a stronger device.
    const val MAX_VIDEO_WIDTH  = 1920
    const val MAX_VIDEO_HEIGHT = 1080

    // Tunneled playback: OFF. Tried 2026-08-03 and REVERTED the same day.
    //
    // The idea was sound — tunneling keeps decode→display inside the vendor
    // pipeline so the framework never holds the output buffers the stall
    // watchdog's deadlock exhausts (OUT=11/12 [kAtClient]) — and this decoder
    // does advertise `tunneled-playback` in media_codecs_amlogic_video.xml.
    //
    // But it never demonstrably fixed anything, and it BROKE startup:
    //
    //   ERROR_CODE_FAILED_RUNTIME_CHECK
    //   Caused by: java.lang.IllegalStateException
    //       at MediaCodecVideoRenderer.onEnabled(MediaCodecVideoRenderer.java:867)
    //
    // The video renderer throws while being ENABLED — before any byte is
    // fetched — when tunneling is requested and no valid tunneling audio session
    // is available. It fired 4x in one session, BUFFERING→IDLE each time, and
    // cleared on an app restart, i.e. it depends on audio-session timing. An
    // unverified mitigation that intermittently prevents playback outright is a
    // net loss, so it stays off unless someone can show it curing the deadlock.
    const val TUNNELING_ENABLED = false

    /** Track selector with the shared bitrate, size and tunneling policy applied. */
    fun trackSelector(context: Context): DefaultTrackSelector =
        DefaultTrackSelector(context).apply { applyVideoPolicy(this) }

    /**
     * Never let a player choose a text track. THE SAME RULE as the one applied in
     * [applyVideoPolicy], for the players that do not take a selector from here.
     *
     * A bare ExoPlayer.Builder(context) builds its own DefaultTrackSelector, which
     * carries the same preferred-text-language default and would render an
     * embedded track just as happily — see applyVideoPolicy for the advertising
     * track this stopped. The trailer and loading-screen players play our own clip
     * and TMDB trailers, so nothing they touch carries one today. That is luck
     * about the CONTENT rather than a property of the code, and the invariant is
     * cheap enough to hold everywhere instead.
     *
     * Call it on any player not built through [buildPlayer].
     */
    fun disableTextTracks(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /** Applies the shared video policy to a selector that already exists. */
    fun applyVideoPolicy(selector: DefaultTrackSelector) {
        selector.setParameters(
            selector.buildUponParameters()
                .setForceHighestSupportedBitrate(FORCE_HIGHEST_BITRATE)
                .setMaxVideoSize(MAX_VIDEO_WIDTH, MAX_VIDEO_HEIGHT)
                .setTunnelingEnabled(TUNNELING_ENABLED)
                // ── AUDIO LANGUAGE, STATED RATHER THAN ASSUMED ──────────────
                //
                // Nothing set this before, and DefaultTrackSelector's context
                // constructor fills in a preferred TEXT language but not a
                // preferred AUDIO one. With no preference the scorer falls back
                // to the manifest's own DEFAULT flag, so whatever the packager
                // marked wins.
                //
                // MEASURED 2026-09-13 on VidNest (Gundam Hathaway, tmdb 910850):
                //
                //   NAME="हिन्दी"  LANGUAGE="hi"  AUTOSELECT=YES  DEFAULT=YES
                //   NAME="English" LANGUAGE="en"  AUTOSELECT=NO   DEFAULT=NO
                //   NAME="日本語"   LANGUAGE="ja"  AUTOSELECT=NO   DEFAULT=NO
                //
                // Hindi is the declared default, so it played Hindi on a box
                // whose entire UI is English. The tracks ARE tagged with proper
                // language codes, which is what makes a stated preference work:
                // an explicit language match outscores the DEFAULT flag.
                //
                // Device locale first so this stays right if the box is ever set
                // to another language, English second as the fallback the
                // catalogue is actually in. A stream with one audio track is
                // unaffected — there is nothing to choose between.
                .setPreferredAudioLanguages(
                    java.util.Locale.getDefault().language, "en"
                )
                // ── NO TEXT TRACK IS EVER SELECTED BY THE PLAYER ────────────
                //
                // We render captions ourselves — parsed into timed cues and
                // driven from the playback clock into our own SubtitleView (see
                // HlsPlayerActivity's header). PlayerView ALSO ships a built-in
                // SubtitleView, which draws whatever text track the selector
                // picked, and nothing connected that to our CC button. Two
                // renderers, one switch.
                //
                // DefaultTrackSelector(context) — the constructor two lines up
                // from here — fills in a preferred TEXT language from the device
                // locale. So any English text track in the container got selected
                // and drawn whether captions were on or not.
                //
                // MEASURED 2026-09-29, Terminator 2 (tmdb 280) on VidNest. The
                // source is a progressive .mkv, and its header reads:
                //
                //   S_TEXT/UTF8                 ← an embedded SRT track
                //   mkvmerge v56.1.0            ← what muxed it in
                //   "Visit 480ptvseries.com For More Movie & TV Series"
                //
                // An advertising track the release group buried in the file. It
                // sat on the picture at all times, below our own caption line
                // because PlayerView's view has no bottom-padding fraction, and
                // it could not be turned off: the control bar hides media3's CC
                // button (we use our own), so the one switch for it was gone.
                //
                // Costs us nothing. The subtitles we do offer come from the
                // extractor's subtitle_url and from the backend — never from
                // track selection — and MiruroPlayerActivity has no captions at
                // all, so it only benefits from the same junk being suppressed.
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
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
    //
    // ONE INSTANCE, BUILT ON FIRST USE. This used to construct a new client on
    // every call, and dataSourceFactory evaluated it as a default argument on
    // every playback start and every recovery rebuild, including the Cronet path
    // that never used the result. Each instance owns a connection pool and a
    // dispatcher thread pool, so a troubled film leaked a handful of both.
    // OkHttpClient is immutable and built to be shared; a caller that needs
    // different timeouts can newBuilder() this one and share its pools.
    private val sharedOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(dohResolver)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    fun okHttpClient(): OkHttpClient = sharedOkHttpClient

    /**
     * Callback executor for every CronetDataSource, ONE for the process, which is
     * how Media3's own demo app wires it. The callbacks only signal the data
     * source's condition variable and hand over a buffer, so a single thread
     * serves any number of concurrent sources.
     *
     * This used to be Executors.newSingleThreadExecutor() per dataSourceFactory
     * call and was never shut down: its core thread is non-daemon with no idle
     * timeout, so every playback start and every recovery rebuild left one more
     * idle thread alive for the rest of the process.
     */
    private val cronetCallbackExecutor: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "cronet-datasource-callback")
        }
    }

    /**
     * Data-source factory for extracted streams.
     *
     * @param userAgent send the UA the extractor used — some CDNs bind tokens to it.
     * @param headers   the site's own Referer/Origin/etc. See the class note on why
     *                  these are NOT centralised.
     */
    // ── HTTP/3 transport ─────────────────────────────────────────────────────
    //
    // WHY: CUJO reads the hostname out of the TLS ClientHello on TCP and severs
    // the handshake. QUIC runs over UDP/443, which consumer filters
    // overwhelmingly do not inspect at all — which is why GeckoView plays these
    // exact CDNs on the same box, in the same second, that OkHttp cannot reach.
    // MEASURED 2026-08-03: `load error host=signtime.site ... Unable to parse TLS
    // packet header` in ExoPlayer, while the browser streams fine, and the CDNs
    // advertise `alt-svc: h3=":443"`.
    //
    // OkHttp has no HTTP/3 support, so no amount of tuning it helps. Cronet is
    // Chrome's stack and speaks QUIC natively — the same road the browser takes.
    //
    // Built once and cached; null means "unavailable, use OkHttp". A failure
    // here must never break playback, so every path degrades to the old one.
    @Volatile private var cronetTried = false
    @Volatile private var cronetEngine: org.chromium.net.CronetEngine? = null

    private fun cronet(context: Context): org.chromium.net.CronetEngine? {
        if (cronetTried) return cronetEngine
        synchronized(this) {
            if (cronetTried) return cronetEngine
            cronetTried = true
            cronetEngine = runCatching {
                // PROVEN 2026-08-03 from Firefox on this exact connection:
                //   GET https://signtime.site/…/seg-22-s2160p-v1-a1.jpg
                //   Status 200 · Version HTTP/3 · 12.57 MB
                // while curl to the same host on the same wire gets HTTP 000.
                // The filter inspects TCP/443 and reads the SNI; QUIC rides
                // UDP/443 and is not inspected. So the fix is to actually USE h3.
                //
                // Why the first attempt failed: Cronet normally discovers h3 from
                // an `alt-svc` header on a successful TCP response — and that TCP
                // request is the one being blocked. It never learns, falls back to
                // TCP, and dies with ERR_SSL_PROTOCOL_ERROR. addQuicHint() alone
                // did not override that, so force it explicitly:
                //
                //   host_whitelist  — QUIC is permitted for these origins
                //   origins_to_force_quic_on — skip the TCP probe entirely and
                //                    open QUIC on the FIRST request, which is the
                //                    only way to reach a host whose TCP is severed
                // MEASURED 2026-08-29, and this list is why the filter still wins.
                // addQuicHint is what makes Cronet open QUIC on the FIRST request,
                // without waiting to learn alt-svc from an HTTP/2 response — which
                // is the only way to reach a host whose TCP handshake is severed
                // before any response can arrive.
                //
                // Proof from one session's transport log:
                //   ultralamp.top      -> protocol=h3  http=200   played direct
                //   moon.peakstorm.top -> protocol=none http=-1   severed, relayed
                // ultralamp was never filtered, so TCP connected, alt-svc advertised
                // h3, and it upgraded on its own. moon.peakstorm.top never gets that
                // chance, and it is not in this list, so it only ever tried TCP.
                //
                // NOTE origins_to_force_quic_on:"*" DOES NOT substitute for this.
                // The same session measured h2 on crypto.cloudflare.com and on our
                // own API, so the wildcard force is not taking effect — the hint
                // list is what actually decides.
                //
                // THE HOSTS ROTATE, which is the real defect: ironwallnet was this
                // CDN's name when the list was written and it is peakstorm today.
                // A hardcoded list is stale the moment the CDN moves. This is a
                // measurement to confirm the mechanism, not the fix — see the
                // backend-supplied host list that should replace it.
                val quicHosts = listOf(
                    "signtime.site", "sandstorm13.site",
                    "premiumvacations.site", "diskphone12.site",
                    "moon.ironwallnet.net",
                    // current, from relay_sessions + extractor logs 2026-08-29
                    "moon.peakstorm.top", "s1.piltover.li", "s1.watami.win",
                    "proudotter.top", "tinytower.top", "grandpearl.top",
                    "ultralamp.top", "tiktoks.animanga.fun",
                )
                // "*" — force QUIC on EVERY origin, not a host list.
                //
                // The per-host form ("signtime.site:443,…") produced no change:
                // Cronet still reported net::ERR_SSL_PROTOCOL_ERROR, which is a
                // TCP/TLS failure, meaning it never went over UDP. Either the list
                // format was silently rejected or it was overridden. "*" is the
                // documented catch-all and removes that variable entirely.
                //
                // Safe here because this engine ONLY serves extracted video
                // streams — the CDNs all advertise `alt-svc: h3=":443"`, and
                // CronetDataSource still has an OkHttp fallback factory beneath it
                // for anything that genuinely cannot do QUIC.
                // ── AsyncDNS: the switch that arms ECH ───────────────────────
                //
                // ECH IS ALREADY IN THIS APK AND HAS NEVER ONCE BEEN USED.
                // Verified 2026-08-28 by reading the shipped binary:
                // lib/armeabi-v7a/libcronet.119.0.6045.31.so contains
                // encrypted_client_hello.cc, the EncryptedClientHello and
                // EncryptedClientHelloQuic feature strings, the ech_config_list
                // SvcParam parser, and ten UseDnsHttpsSvcb* symbols.
                //
                // WE ALREADY PROVED THE MECHANISM WORKS ON THIS NETWORK — in the
                // other engine. gecko_config.yaml turns on network.trr.mode 2 plus
                // network.dns.echconfig.enabled for exactly this filter, and the
                // note there records the measurement: signtime.site returned 256
                // bytes (handshake severed) while every other CDN returned ~2800.
                // GeckoView can do it because it carries its own TLS stack (NSS)
                // rather than Android's Conscrypt. Cronet carries BoringSSL for the
                // same reason and is no more dependent on the OS — so the argument
                // that justified ECH in the webviewer applies here unchanged.
                //
                // WHY IT LIES DORMANT: the blocker is DNS, not TLS. Chromium only
                // attempts ECH when it holds an HTTPS (type 65) record carrying an
                // `ech=` parameter, and it only queries type 65 through its OWN
                // async resolver. Cronet defaults to Android's getaddrinfo, which
                // cannot return type 65 at all — so the record never arrives, ECH
                // is never attempted, and the hostname goes out in cleartext for
                // the filter to read. Enabling AsyncDNS is what closes that gap.
                // It is the Cronet spelling of what network.trr.mode already does
                // for Gecko.
                //
                // FALLS BACK ON ITS OWN. Unlike Gecko's TRR mode 3 — which took the
                // webviewer down earlier in this project by having no fallback —
                // Chromium's resolver keeps the system resolver beneath it. Worst
                // case here is "no ECH", never "no DNS".
                // ── Why the broken-QUIC backoff is shortened, not disabled ───
                //
                // Chromium marks a host QUIC-BROKEN after a failed QUIC attempt and
                // refuses QUIC for a cooldown that GROWS with each failure. That is
                // correct on an ordinary network, where TCP is a fine fallback and
                // there is no point hammering a dead path.
                //
                // IT IS BACKWARDS HERE. On an SNI-filtering line the fallback is the
                // thing that does not work: TCP is exactly what the ISP severs. So a
                // single QUIC hiccup disables the ONLY working transport, and every
                // retry re-arms the timer and makes it worse.
                //
                // MEASURED 2026-08-29, repeatedly replaying one title:
                //   16:31  moon.peakstorm.top -> protocol=(none) http=-1   relayed
                //   16:31  api.<domain>       -> h3 http=429   (our own relay,
                //                                RELAY BUSY at 2 concurrent)
                //   16:40  moon.peakstorm.top -> protocol=h3 http=200      direct
                // Ten minutes untouched was the entire fix. Retrying was what kept
                // it broken, from both ends at once.
                //
                // NOT SET TO ZERO, DELIBERATELY. Somewhere with UDP genuinely
                // blocked — hotel wifi, a corporate guest network — the backoff is
                // what stops every single request paying for a doomed QUIC attempt
                // before falling back. One second is long enough to avoid that
                // thrash and short enough that a transient failure costs nothing.
                //
                // retry_without_alt_svc_on_quic_errors is left at its default (true)
                // so a network with no QUIC at all still degrades to TCP rather than
                // failing outright.
                // SUPERSEDED 2026-08-29 — READ THIS BEFORE TRUSTING THE
                // IPv6 STORY BELOW. The identical signature (quic_error 9,
                // "Largest acked is 0.", injected packet_number 2147483648)
                // now reproduces over IPv4 with this flag confirmed live in
                // cronetExperimentalParams. The original dataset had no
                // IPv4-CDN case, so address family was perfectly confounded
                // with "is a filtered CDN host" — the flag was never the fix,
                // it just happened to correlate. The real mechanism is an
                // on-path device forging replies to any handshake carrying a
                // blocked hostname in cleartext; see the ECH block above.
                // Keeping the flag: it is harmless and removes a variable.
                // ── disable_ipv6_on_wifi: THE actual fix ─────────────────────
                //
                // PROVEN BY CRONET'S OWN NETLOG, 2026-08-29. Every QUIC session to
                // a filtered CDN died the same way, and every one was IPv6:
                //
                //   host                     family  closed-with
                //   api.<domain>             IPv4    ok
                //   moon.peakstorm.top       IPv6    quic_error 9
                //   moon.peakstorm.top       IPv6    quic_error 9   (x4)
                //
                // Error 9 is QUIC_INVALID_ACK_DATA, "Largest acked is 0", with
                // from_peer:false — the CLIENT tore the connection down, because it
                // received a packet acknowledging something never sent. The bogus
                // packet header carried packet_number 2147483648 (0x80000000). That
                // is a middlebox injecting into the QUIC handshake, not a clean
                // block, and it happens on Charter's IPv6 path.
                //
                // The one host that always worked, api.<domain>, is not virtuous —
                // IONOS simply issued the VPS no IPv6 address, so it could only ever
                // use v4. Cloudflare CDNs publish AAAA, Cronet prefers IPv6, and
                // every one of those connections walked into the mangler. QUIC then
                // failed, TCP fell back into the SNI filter, and playback relayed.
                //
                // That is why hinting looked broken: 42 hints applied,
                // ALT_SVC_FOUND is_broken:false, a QUIC_SESSION created — the hint
                // worked perfectly every time. The transport underneath it did not.
                //
                // SCOPED TO WI-FI, which is what this box uses (wlan0) and all the
                // evidence covers. A wired client keeps IPv6.
                //
                // ALSO FIXING A KEY NAME: the backoff option is
                // initial_delay_for_broken_alternative_service_SECONDS. Build 86 set
                // it without the suffix, so Cronet ignored it and the setting has
                // done nothing since — visible in the option-name table inside
                // libcronet itself.
                // ── The 5 ms race that was costing us ECH ───────────────────
                //
                // MEASURED 2026-08-29 from NetLog (fail.json, resolver job sid 91):
                //
                //   18286873  DNS_TRANSACTION finalroom.top query_type=1   (A)
                //   18286874  DNS_TRANSACTION finalroom.top query_type=65  (HTTPS)
                //   18286894  A answered, +21ms
                //   18286899  HOST_RESOLVER_MANAGER_DNS_TASK_TIMEOUT       +5ms
                //   18286899  type-65 CANCELLED, net_error -3
                //   18286899  endpoint_metadatas: []
                //   18286907  first QUIC INITIAL sent, SNI IN THE CLEAR
                //
                // Chromium waits clamp(percent x address-resolve-time, min, max)
                // for the supplemental HTTPS record after the address answer
                // lands. The default floor is 5 ms, and 20% of a 21 ms lookup
                // rounds under it — so the record is discarded 8 ms before the
                // ClientHello goes out, every time.
                //
                // CHARTER SERVES THE RECORD. Probed directly 2026-08-29 against
                // 192.168.1.1, 1.1.1.1 and 8.8.8.8 — all three return rcode=0
                // ancount=1 rdlen=136 with ech= present and alpn=h3. The record
                // was never unavailable; we were hanging up before it arrived.
                // A working host proves the timing is the only variable:
                // orbitpanda.top's two transactions took 95 ms and 424 ms, no
                // timeout fired, ECH engaged, and that connection was never
                // touched by the filter.
                //
                // WHY IT MATTERS, proven by controlled experiment 2026-08-29 —
                // one IP (172.67.205.53:443), only the SNI string varied:
                //   SNI finalroom.top       -> no certificate, blocked
                //   SNI moon.peakstorm.top  -> no certificate, blocked
                //   SNI example.com         -> CN=example.com, 200
                //   SNI cloudflare-ech.com  -> CN=cloudflare-ech.com, 200
                // The filter keys on the hostname string. cloudflare-ech.com is
                // exactly the public_name ECH puts in the OUTER ClientHello, and
                // it passes — so concealing the inner name defeats the match.
                //
                // 500 ms floor / 2 s ceiling: orbitpanda's answer arrived 424 ms
                // after its sibling, so anything under ~500 ms would still lose
                // the race. Cold first resolution per host only; cached after.
                val opts = """
                    {"AsyncDNS":{"enable":true},
                     "UseDnsHttpsSvcb":{
                       // "enable" IS NOT OPTIONAL — build 98 omitted it and the
                       // feature switched OFF, which is the opposite of the
                       // intent and measurably worse than the default.
                       //   build 97 (no group declared):
                       //       HOST_RESOLVER_MANAGER_JOB dns_query_types ["A","HTTPS"]
                       //   build 98 (group declared, no "enable"):
                       //       HOST_RESOLVER_MANAGER_JOB dns_query_types ["A"]
                       // Declaring the group replaces the feature's defaults, so
                       // without the switch Cronet configures it and leaves it
                       // disabled — the HTTPS query is never sent, ECH can never
                       // arm, and moon.peakstorm.top (which had been surviving on
                       // a cached ECH config) started failing too. Same silent-
                       // ignore class as the initial_delay_for_broken_alternative
                       // _service_seconds typo: Cronet validates nothing.
                       "enable":true,
                       "use_alpn":true,
                       // 1500, not 500. MEASURED 2026-08-30, 24 type-65 lookups
                       // against Charter's resolver for the CDN hosts we actually
                       // use:  median 262ms, p95 530ms, max 587ms. A 500ms floor
                       // therefore CANCELS roughly the slowest 5-10% of lookups —
                       // and a cancelled lookup caches the host with no ECH config,
                       // which is precisely the failure it was meant to prevent.
                       //
                       // Raising it is nearly free, because the wait ends when an
                       // answer ARRIVES, not when the timer expires. A host with no
                       // HTTPS record still answers, just with ancount=0: verified
                       // for api.daigurrenblue.com, which has no record and replies
                       // promptly. Only a query that gets no response at all pays
                       // the full floor, and that is the case worth being patient
                       // for anyway.
                       "insecure_extra_time_min":1500,
                       "insecure_extra_time_percent":100,
                       "insecure_extra_time_max":2000
                     },
                     "disable_ipv6_on_wifi":true,
                     "QUIC":{
                       // origins_to_force_quic_on REMOVED 2026-08-31.
                       //
                       // It forced a QUIC attempt at EVERY origin, including hosts
                       // that advertise alpn=h2 and nothing else. Combined with the
                       // 1s broken-alternative-service delay below and backoff
                       // disabled, Cronet marked QUIC broken for such a host and
                       // then un-marked it a second later — retrying a doomed
                       // handshake on essentially every new connection, forever.
                       //
                       // MEASURED 2026-08-31 on My Hero Academia via Miruro, whose
                       // CDNs (s1.piltover.li, s1.watami.win) publish an HTTPS
                       // record with alpn=h2 and no h3: segment reads died with
                       // SocketTimeoutException inside TsExtractor while the same
                       // segments fetched at 3-9 MB/s over curl, the box pinged the
                       // host at 17ms with 0% loss, and its Wi-Fi linked at
                       // 866Mbps. curl and ping never attempt QUIC, which is
                       // exactly why every external test came back clean.
                       //
                       // It was also not load-bearing: when the hint list was
                       // emptied for the 2026-08-29 SVCB experiment, NO QUIC
                       // happened anywhere despite this being set. addQuicHint is
                       // what actually opens QUIC first for the CDNs we care about,
                       // and hosts that genuinely speak h3 still get it via their
                       // own alt-svc. This only ever forced failures on hosts that
                       // cannot.
                       "idle_connection_timeout_seconds":300,
                       "migrate_sessions_on_network_change_v2":true,
                       "initial_delay_for_broken_alternative_service_seconds":1,
                       "exponential_backoff_on_initial_delay":false
                    }}
                """.trimIndent()

                // Pick the APP-PACKAGED (embedded) provider explicitly.
                //
                // MEASURED: three separate QUIC configurations all failed with
                // net::ERR_SSL_PROTOCOL_ERROR, and every stack frame came from
                //   com.google.android.gms.dynamite_cronetdynamite
                // i.e. Google Play Services Cronet, NOT the cronet-embedded build
                // in this APK. Play Services restricts what a caller may
                // configure, so setExperimentalOptions was very likely dropped —
                // which matches QUIC never being attempted at all.
                //
                // The embedded provider is ours: same process, same version we
                // pinned, and it honours experimental options. Falls back to the
                // default builder if for any reason it isn't present.
                val providers = org.chromium.net.CronetProvider.getAllProviders(
                    context.applicationContext
                ).filter { it.isEnabled }
                Log.d("scraper-vidapi", "🚀 cronet providers: " +
                        providers.joinToString { "${it.name}@${it.version}" })

                val embedded = providers.firstOrNull {
                    it.name == org.chromium.net.CronetProvider.PROVIDER_NAME_APP_PACKAGED
                }
                val b = (embedded?.createBuilder()
                    ?: org.chromium.net.ExperimentalCronetEngine
                        .Builder(context.applicationContext))
                        as org.chromium.net.ExperimentalCronetEngine.Builder
                Log.d("scraper-vidapi", "🚀 using provider: " +
                        (embedded?.name ?: "default (Play Services)"))
                b.enableQuic(true)
                b.enableHttp2(true)
                b.enableBrotli(true)
                // MEASURED, NOT ASSUMED. Eight builds were shipped on the belief
                // that these hints reach the engine; that was never once verified.
                // A host can be in the static list, in prefs, and still not get
                // QUIC — and without this line there is no way to tell whether the
                // hint was missing, ignored, or applied-but-refused by the network.
                // HINTS STAY ON. The 2026-08-29 experiment emptied them at the
                // same moment it enabled SVCB, so its failure could not
                // distinguish "SVCB does not work" from "nothing was left to
                // fall back on". ECH and QUIC hints solve different halves:
                // the hint decides QUIC is tried, ECH decides the name is
                // concealed once it is. Both are needed.
                val hints = (quicHosts + learnedQuicHosts(context)).distinct()
                hints.forEach { b.addQuicHint(it, 443, 443) }
                Log.e("scraper-vidapi",
                    "🔬 QUIC HINTS applied=${hints.size} " +
                    "(static=${quicHosts.size} learned=${learnedQuicHosts(context).size}) " +
                    "peakstorm=${hints.any { it.contains("peakstorm") }}")
                b.setExperimentalOptions(opts)
                b.build()
            }.onFailure {
                // Log.e, NOT Log.w: release builds strip w, and this is the single
                // most consequential thing that can go wrong in this file — no
                // Cronet means no QUIC means every filtered CDN fails.
                Log.e("scraper-vidapi",
                    "🚨 Cronet unavailable (${it.message}) — falling back to OkHttp, " +
                    "which has NO QUIC. Filtered hosts will fail from here.")
            }.getOrNull()
            cronetEngine?.let {
                cronetEverBuilt = true
                Log.d("scraper-vidapi", "🚀 Cronet ready (QUIC on) — ${it.versionString}")
                attachTransportLog(it)
                startNetLog(it, context)
            }
            return cronetEngine
        }
    }

    // ── Transport instrumentation ────────────────────────────────────────────
    //
    // WHY THIS EXISTS: three days were spent arguing about whether ECH was
    // active, on evidence that could not distinguish it from HTTP/3 evading a
    // TCP-only filter. Enabling AsyncDNS turned BOTH on at once — the same DNS
    // record carries `ech=` and `alpn=h3` — so every subsequent success or
    // failure had two candidate explanations and no way to separate them.
    // Guessing produced four builds on three wrong theories. These two numbers
    // end that.
    //
    // Logged at Log.e ON PURPOSE. Release builds strip v/d/i/w, and a diagnostic
    // that goes silent in exactly the builds users run is not a diagnostic.

    /**
     * Record Cronet's own account of every connection attempt.
     *
     * WHY: measurement showed 42 QUIC hints applied, moon.peakstorm.top among
     * them, and Cronet still opening no QUIC connection to it — while an
     * UNHINTED host negotiated h3 through alt-svc in the same session. Hinting
     * is therefore not the variable, and no amount of reasoning from outside the
     * stack has explained which one is. NetLog is Chromium's own record of what
     * it decided and why: whether a QUIC job was created, whether it was raced
     * against TCP, whether the alternative service was marked broken, and what
     * killed it.
     *
     * WRITTEN TO getExternalFilesDir SO IT CAN ACTUALLY BE READ. The internal
     * data dir needs run-as, which fails on a release build because it is not
     * debuggable — so an internal path would produce a file nobody can retrieve.
     *
     * STOPPED ON A TIMER, because a NetLog is only valid once stopped: the JSON
     * is left unterminated while recording. 90s covers a playback start and the
     * first failures without producing an unmanageable file.
     */
    @Volatile private var netLogRunning = false

    /** Restart capture at each playback, so the window always covers the attempt. */
    @Synchronized
    fun restartNetLog(context: Context) {
        if (!NETLOG_ENABLED) return
        val e = cronetEngine ?: return
        if (netLogRunning) runCatching { e.stopNetLog() }
        netLogRunning = false
        startNetLog(e, context)
    }

    private fun startNetLog(engine: org.chromium.net.CronetEngine, context: Context) {
        if (!NETLOG_ENABLED) return
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "cronet-netlog.json")
            // ROTATE, DO NOT OVERWRITE. This capture is restarted from
            // ensureQuicHint, which runs precisely BECAUSE a new host was met —
            // so writing to a fixed path means a failure destroys its own
            // evidence. MEASURED 2026-08-29: finalroom.top refused four
            // handshakes and relayed, then the restart it triggered overwrote
            // the only record of it; the pulled file held zero live events for
            // that host and the cause was unrecoverable.
            //
            // One generation is enough for the pattern that keeps happening —
            // failing playback, then a working one — which leaves the failure in
            // .prev and the success in the live file. The rename is safe here
            // because restartNetLog has already called stopNetLog, and an
            // unstopped NetLog is invalid JSON anyway.
            runCatching {
                if (f.exists()) {
                    val prev = java.io.File(dir, "cronet-netlog.prev.json")
                    if (prev.exists()) prev.delete()
                    f.renameTo(prev)
                }
            }
            engine.startNetLogToFile(f.absolutePath, false)
            netLogRunning = true
            Log.e("scraper-vidapi", "🔬 NETLOG recording -> ${f.absolutePath}")
            // 60s from the START OF A PLAYBACK, not from engine build. A capture
            // anchored to engine build missed the 20:28 failure entirely because
            // the engine had been up nine minutes by then — the window has to
            // follow the thing being measured.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                kotlinx.coroutines.delay(60_000)
                runCatching { engine.stopNetLog() }
                netLogRunning = false
                Log.e("scraper-vidapi", "🔬 NETLOG stopped (${f.length()} bytes) — ready to pull")
            }
        }.onFailure { Log.e("scraper-vidapi", "🔬 NETLOG unavailable: ${it.message}") }
    }

    /**
     * Warm this stream's hosts and WAIT for it, capped.
     *
     * WHY WAITING IS NECESSARY. Fire-and-forget lost a real race, twice.
     * MEASURED 2026-08-30 on "Obsession": the warm was dispatched at
     * 14:51:38.200 and the first request to polarcandy.top went out at
     * 14:51:38.738 — a 538ms head start against an ECH lookup that runs
     * median 262ms / p95 530ms / max 587ms on this line. It lost, the hostname
     * went out in cleartext, and the ISP severed it. moon.peakstorm.top, already
     * warm from an earlier play, reached h3 200 in the same instant.
     *
     * SELF-TUNING, so the cost is paid only when there is something to buy.
     * This awaits the HEAD requests themselves, not a fixed delay: a host whose
     * DNS and ECH config are already cached answers in tens of milliseconds over
     * an existing QUIC session, so the wait is negligible. Only a genuinely cold
     * host costs its lookup time. A fixed sleep would tax every playback for the
     * benefit of the rare one.
     *
     * CAPPED because the alternative to waiting is a relay, not a hang. If the
     * lookup has not landed by the cap we proceed anyway and behave exactly as
     * before — this can delay playback slightly, never prevent it.
     */
    suspend fun warmHostsAwait(hosts: List<String>, capMs: Long = 1200) {
        if (hosts.isEmpty()) return
        val ctx = BlueHiveApplication.appContext ?: return
        val all = hosts.map { it.lowercase() }.filter { it.isNotBlank() }.distinct()
        if (all.isEmpty()) return

        val known = learnedQuicHosts(ctx)
        val fresh = all.filter { it !in known }
        if (fresh.isNotEmpty()) mergeQuicHints(ctx, fresh)
        // Snapshot for the relay report. Replaced rather than added to, so the
        // answer is always "was this host new for THIS play" — the previous
        // playback's newcomers are not this one's excuse.
        freshAtLastWarm.clear()
        freshAtLastWarm.addAll(fresh)
        // Off the critical path: dispatched, never awaited. See the function.
        dispatchEchConfigProbe(all)

        val engine = runCatching { cronet(ctx) }.getOrNull() ?: return
        val latch = java.util.concurrent.CountDownLatch(all.size)
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val started = System.currentTimeMillis()
        // PER-HOST OUTCOMES, because "all settled" was actively misleading: the
        // latch counts down on failure too, so a host whose warm was SEVERED
        // reported exactly like one that succeeded. MEASURED 2026-08-30 —
        // moon.peakstorm.top logged "2 host(s) in 681ms, all settled" and was then
        // severed four times. If a warm cannot reach a host, that is the single
        // most useful fact available and it was being swallowed.
        val ok  = java.util.Collections.synchronizedSet(HashSet<String>())
        val bad = java.util.Collections.synchronizedMap(HashMap<String, String>())

        for (h in all) {
            runCatching {
                val cb = object : org.chromium.net.UrlRequest.Callback() {
                    override fun onRedirectReceived(
                        r: org.chromium.net.UrlRequest,
                        i: org.chromium.net.UrlResponseInfo, u: String
                    ) { r.cancel() }
                    override fun onResponseStarted(
                        r: org.chromium.net.UrlRequest, i: org.chromium.net.UrlResponseInfo
                    ) { r.cancel() }
                    override fun onReadCompleted(
                        r: org.chromium.net.UrlRequest,
                        i: org.chromium.net.UrlResponseInfo, b: java.nio.ByteBuffer
                    ) { r.cancel() }
                    override fun onSucceeded(
                        r: org.chromium.net.UrlRequest, i: org.chromium.net.UrlResponseInfo
                    ) { ok.add(h); latch.countDown() }
                    override fun onFailed(
                        r: org.chromium.net.UrlRequest,
                        i: org.chromium.net.UrlResponseInfo?, e: org.chromium.net.CronetException
                    ) { bad[h] = e.message ?: "failed"; latch.countDown() }
                    // Cancelled is the NORMAL path here — we cancel at headers,
                    // because the response body is irrelevant. Counting down only
                    // on success would wait out the full cap every single time.
                    override fun onCanceled(
                        r: org.chromium.net.UrlRequest, i: org.chromium.net.UrlResponseInfo?
                    ) { ok.add(h); latch.countDown() }   // we cancel at headers: reaching them IS success
                }
                engine.newUrlRequestBuilder("https://$h/", cb, exec)
                    .setHttpMethod("HEAD").build().start()
            }.onFailure { latch.countDown() }
        }

        val settled = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            latch.await(capMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val waited = System.currentTimeMillis() - started
        // "settled" MEANS THE REQUESTS FINISHED, NOT THAT THEY SUCCEEDED — the
        // latch counts down on failure and cancellation too, which it must, or a
        // severed host would wait out the whole cap. Reporting the elapsed time
        // is what makes this readable: a warm that returns in ~150ms did no DNS
        // at all and served a cached entry, while a real lookup on this line
        // costs 262-587ms. MEASURED 15:13:32 — 907ms with one new host, which
        // resolved and reached h3 200, while the cached one returned instantly
        // and was severed.
        Log.e("scraper-vidapi",
            "🔥 warmed ${ok.size}/${all.size} host(s) in ${waited}ms " +
            "(${fresh.size} new${if (settled) "" else ", cap hit"}" +
            "${if (waited < 120) ", NO DNS — served from cache" else ""})" +
            " ok=${ok.sorted().joinToString()}" +
            "${if (bad.isEmpty()) "" else " UNREACHABLE=" + bad.entries.joinToString { "${it.key} (${it.value})" }}")
        runCatching { exec.shutdown() }
    }

    /** Has Cronet EVER built here? Separates our bug from a device limitation. */
    @Volatile private var cronetEverBuilt = false

    /** Last bulk prewarm, so an engine rebuild cannot trigger one per playback. */
    @Volatile private var lastBulkPrewarmMs = 0L
    private const val BULK_PREWARM_MIN_INTERVAL_MS = 5 * 60_000L

    @Volatile private var prewarmed = false

    /**
     * Resolve the known CDN hosts BEFORE anything needs them, so ECH is already
     * cached when playback starts.
     *
     * WHY THIS EXISTS. ECH conceals the hostname, and the filter matches on the
     * hostname, so ECH is the whole defence. But ECH needs the HTTPS/SVCB DNS
     * record, and Chromium only waits `insecure_extra_time` for it AFTER the
     * address answer lands. In the playback path that wait sits ~34ms before the
     * ClientHello goes out, and if the record loses the race the resolver caches
     * an entry with NO ech_config_list. Every request against that cached entry
     * then goes out in cleartext until it expires.
     *
     * MEASURED 2026-08-30 10:58: moon.peakstorm.top produced four handshake
     * refusals in a capture with no fresh DNS and zero ECH events — served from
     * exactly such an entry. The same host had worked earlier the same day.
     *
     * Doing it at startup removes the race instead of trying to win it: nothing
     * is waiting on these, so the full allowance is available, and by the time a
     * playback resolves the host the entry already carries its ECH config.
     *
     * ONE CHEAP REQUEST PER HOST, capped and serialised in small batches. Cronet
     * exposes no resolve-only API, so a request is the only way to drive the
     * resolver — but the response is irrelevant. A 403 or 404 warms the DNS entry
     * exactly as well as a 200, so failures are ignored by design.
     */
    fun prewarmCdnDns(context: Context) {
        if (prewarmed) return
        prewarmed = true
        val engine = runCatching { cronet(context) }.getOrNull() ?: return
        // The LEARNED list only. The static list is a compiled-in fallback whose
        // hosts rarely rotate; the backend-published set is the one that churns
        // (moon.peakstorm.top, finalroom.top, orbitpanda.top, impactdrivenbrands
        // .site ...) and therefore the one whose ECH configs are worth fetching
        // ahead of time. quicHosts is also a local inside the engine builder and
        // deliberately not hoisted for this.
        val hosts = learnedQuicHosts(context).toList()
        if (hosts.isEmpty()) return

        warm(context, hosts)
    }

    /** Drive the resolver for [hosts]. Responses are irrelevant and discarded. */
    private fun warm(context: Context, hosts: List<String>) {
        val engine = runCatching { cronet(context) }.getOrNull() ?: return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
            var done = 0
            // Small batches with a pause between them. These are third-party CDNs
            // and 48 simultaneous connections from one address at app start looks
            // like scanning; spread out it looks like what it is.
            for (chunk in hosts.chunked(6)) {
                for (h in chunk) {
                    runCatching {
                        val cb = object : org.chromium.net.UrlRequest.Callback() {
                            override fun onRedirectReceived(
                                r: org.chromium.net.UrlRequest,
                                i: org.chromium.net.UrlResponseInfo, u: String
                            ) { r.cancel() }
                            override fun onResponseStarted(
                                r: org.chromium.net.UrlRequest,
                                i: org.chromium.net.UrlResponseInfo
                            ) { r.cancel() }   // headers are enough; never read a body
                            override fun onReadCompleted(
                                r: org.chromium.net.UrlRequest,
                                i: org.chromium.net.UrlResponseInfo,
                                b: java.nio.ByteBuffer
                            ) { r.cancel() }
                            override fun onSucceeded(
                                r: org.chromium.net.UrlRequest,
                                i: org.chromium.net.UrlResponseInfo
                            ) {}
                            override fun onFailed(
                                r: org.chromium.net.UrlRequest,
                                i: org.chromium.net.UrlResponseInfo?,
                                e: org.chromium.net.CronetException
                            ) {}
                            override fun onCanceled(
                                r: org.chromium.net.UrlRequest,
                                i: org.chromium.net.UrlResponseInfo?
                            ) {}
                        }
                        engine.newUrlRequestBuilder("https://$h/", cb, exec)
                            .setHttpMethod("HEAD")
                            .build().start()
                        done++
                    }
                }
                kotlinx.coroutines.delay(400)
            }
            // Log.e because release builds strip everything below it, and a warm
            // that silently stopped running would be invisible exactly where it
            // matters.
            Log.e("scraper-vidapi",
                "🔥 DNS/ECH prewarm dispatched for $done CDN host(s) — " +
                "playback should now find ECH already cached")
            kotlinx.coroutines.delay(15_000)
            exec.shutdown()
        }
    }

    /**
     * When the ECH probe last ran, so it can be re-armed per playback.
     *
     * WAS ONCE PER PROCESS, AT ENGINE BUILD, AND THAT MADE IT LIE. MEASURED
     * 2026-09-12: the probe fired at 13:28:10 and reported plaintext over h2,
     * nine seconds BEFORE the DNS/ECH prewarm was dispatched — so it measured a
     * completely cold engine, which is the one moment guaranteed not to
     * represent playback. Two relays at 13:35 then reported that same seven
     * minute old verdict. A field that reads "plaintext" on every device forever
     * is worse than no field, because it looks like a finding.
     *
     * Now re-armed at the start of each playback, after the warm, so the verdict
     * describes the conditions the stream is actually about to meet.
     */
    @Volatile private var lastEchProbeMs = 0L

    /**
     * Floor between probes. Playbacks are minutes apart, so this changes nothing
     * for real viewing and only stops a burst when somebody flips between
     * sources on one title.
     */
    private const val ECH_PROBE_MIN_INTERVAL_MS = 30_000L

    /**
     * What the probe concluded, KEPT rather than only printed.
     *
     * The verdict existed already and went straight into a log line. That made
     * the one thing the whole transport defence rests on the one thing no relay
     * row could report: a host that connected over QUIC with its name in the
     * clear looked identical to one that was properly concealed, because h3
     * describes the road and says nothing about the envelope.
     *
     * "encrypted" | "plaintext" | "inconclusive", or null until the probe runs.
     */
    @Volatile private var echVerdict: String? = null

    /**
     * DEVICE LEVEL, NOT HOST LEVEL. This says whether ECH negotiates at all on
     * this network, which is the failure mode that takes everything down at
     * once. Whether a PARTICULAR CDN was concealed additionally needs that host
     * to publish an ECH config — see [hostPublishesEch].
     */
    fun echState(): String = echVerdict ?: "unknown"

    /**
     * Does this host publish an ECH config? null until looked up.
     *
     * Cached across playbacks on purpose: an ECH config is a DNS record, not a
     * per-connection fact, and it changes on the order of days.
     */
    private val hostEchConfig =
        java.util.Collections.synchronizedMap(HashMap<String, Boolean>())

    /** true = publishes ECH, false = does not, null = never looked up. */
    fun hostPublishesEch(url: String): Boolean? = runCatching {
        java.net.URI(url).host?.lowercase()?.let { hostEchConfig[it] }
    }.getOrNull()

    /**
     * Ask DoH whether each host publishes an ECH config. Fire and forget.
     *
     * WHY TYPE 65 AND NOT THE EXISTING RESOLVER. dohResolver answers A/AAAA
     * only; ECH configs live in the HTTPS record, which needs the JSON query
     * form. Chromium asks for exactly this record itself and will not attempt
     * ECH without it, so its presence is the difference between "ECH failed for
     * this host" and "this host was never eligible" — two findings that look
     * identical in a relay row and have completely different fixes.
     *
     * NOT AWAITED, AND DELIBERATELY OFF THE FAILURE PATH. Dispatched during the
     * warm so the answer is already cached seconds before any failure could ask
     * for it. Doing it inside switchToRelay would add a DNS round trip to the
     * one path where the viewer is already staring at a black screen.
     */
    private fun dispatchEchConfigProbe(hosts: List<String>) {
        val todo = hosts.filter { it.isNotBlank() && it !in hostEchConfig }
        if (todo.isEmpty()) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val client = okHttpClient()
            for (h in todo) {
                runCatching {
                    val req = okhttp3.Request.Builder()
                        .url("https://cloudflare-dns.com/dns-query?name=$h&type=HTTPS")
                        .header("Accept", "application/dns-json")
                        .build()
                    client.newCall(req).execute().use { r ->
                        val txt = r.body?.string().orEmpty()
                        // Cloudflare renders the HTTPS record's SvcParams as a
                        // string, so the config shows up as an `ech="..."` pair.
                        // Absent means the host published no ECH config at all.
                        hostEchConfig[h] = txt.contains("ech=")
                    }
                }.onFailure {
                    // Left ABSENT, which reads as unknown. Recording false here
                    // would turn "the lookup failed" into "this host has no ECH",
                    // and that is the wrong answer to act on.
                    hostEchConfig.remove(h)
                    Log.w("scraper-vidapi", "ech-config lookup failed for $h (${it.message})")
                }
            }
        }
    }

    private fun probeEch(engine: org.chromium.net.CronetEngine) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastEchProbeMs != 0L && now - lastEchProbeMs < ECH_PROBE_MIN_INTERVAL_MS) return
        lastEchProbeMs = now
        // CLOUDFLARE'S OWN SELF-TEST, deliberately not a streaming host. It
        // answers `sni=encrypted` when the ClientHello concealed the hostname and
        // `sni=plaintext` when it did not — a direct read of the one thing under
        // investigation, costing one small request and touching no CDN we stream
        // from (which matters while the hosting account is under review).
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val body = StringBuilder()
        val cb = object : org.chromium.net.UrlRequest.Callback() {
            override fun onRedirectReceived(
                r: org.chromium.net.UrlRequest, i: org.chromium.net.UrlResponseInfo, u: String
            ) { r.followRedirect() }

            override fun onResponseStarted(
                r: org.chromium.net.UrlRequest, i: org.chromium.net.UrlResponseInfo
            ) { r.read(java.nio.ByteBuffer.allocateDirect(16384)) }

            override fun onReadCompleted(
                r: org.chromium.net.UrlRequest,
                i: org.chromium.net.UrlResponseInfo,
                bb: java.nio.ByteBuffer
            ) {
                bb.flip()
                val arr = ByteArray(bb.remaining()); bb.get(arr)
                body.append(String(arr))
                bb.clear(); r.read(bb)
            }

            override fun onSucceeded(
                r: org.chromium.net.UrlRequest, i: org.chromium.net.UrlResponseInfo
            ) {
                val sni = body.lines().firstOrNull { it.startsWith("sni=") } ?: "sni=(absent)"
                echVerdict = if (sni.contains("encrypted")) "encrypted" else "plaintext"
                Log.e("scraper-vidapi",
                    "🔬 ECH PROBE: $sni | protocol=${i.negotiatedProtocol} | " +
                    "verdict=" + if (sni.contains("encrypted"))
                        "ECH IS ACTIVE — the hostname is concealed"
                    else
                        "ECH IS NOT ACTIVE — the hostname is going out in the clear")
                exec.shutdown()
            }

            override fun onFailed(
                r: org.chromium.net.UrlRequest,
                i: org.chromium.net.UrlResponseInfo?,
                e: org.chromium.net.CronetException
            ) {
                echVerdict = "inconclusive"
                Log.e("scraper-vidapi", "🔬 ECH PROBE failed (inconclusive): ${e.message}")
                exec.shutdown()
            }

            // Nothing cancels this request today. Covered anyway, so the executor
            // is released on every terminal callback Cronet defines.
            override fun onCanceled(
                r: org.chromium.net.UrlRequest, i: org.chromium.net.UrlResponseInfo?
            ) {
                exec.shutdown()
            }
        }
        runCatching {
            engine.newUrlRequestBuilder(
                "https://crypto.cloudflare.com/cdn-cgi/trace", cb, exec
            ).build().start()
        }.onFailure {
            echVerdict = "inconclusive"
            Log.e("scraper-vidapi", "🔬 ECH PROBE could not start: ${it.message}")
            // No callback will ever run, so nothing else can release it. The
            // worker thread is only created on the first task, so this closes a
            // code path rather than a thread leak, but it is the only path that
            // lacked it.
            exec.shutdown()
        }
    }

    /** First few requests per host: which protocol actually carried them. */
    /** What a host's last request actually negotiated, and what it answered. */
    data class HostTransport(val protocol: String, val httpStatus: Int)

    /**
     * Last transport outcome per host, for THIS playback.
     *
     * WAS A SET USED ONLY TO DEDUPE LOG LINES. The two facts that decide whether
     * a relay was the ISP filter or the CDN itself — the negotiated protocol and
     * the HTTP status — were formatted into a string and dropped on the floor,
     * so relay_sessions recorded ERROR_CODE_IO_BAD_HTTP_STATUS with no way to
     * tell a 404 from a 403, and no way to tell a severed handshake
     * (protocol=(none) http=-1) from a CDN that answered.
     *
     * Holding the values costs one small map and makes both reportable. The map
     * keeps the LATEST outcome per host while the log still prints once, which
     * is what [switchToRelay] needs: by the time it runs, the entry for the
     * failing host is the failure.
     */
    private val hostTransport =
        java.util.Collections.synchronizedMap(HashMap<String, HostTransport>())

    /** The last transport outcome for [url]'s host this playback, if any. */
    fun transportFor(url: String): HostTransport? = runCatching {
        java.net.URI(url).host?.lowercase()?.let { hostTransport[it] }
    }.getOrNull()

    /**
     * Hosts this playback's warm had never seen before.
     *
     * A host learned for the first time is one that could not have been
     * QUIC-hinted at engine build, so its first connection is the one least
     * likely to survive a filtered line. Recording it separates "the defence was
     * armed and still lost" from "this host was brand new", which read
     * identically in relay_sessions.
     */
    private val freshAtLastWarm = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Was [url]'s host newly learned by the most recent warm? */
    fun wasNewlyLearned(url: String): Boolean = runCatching {
        java.net.URI(url).host?.lowercase()?.let { it in freshAtLastWarm }
    }.getOrDefault(false) ?: false

    /**
     * Executor for the request-finished listener below, ONE for the process.
     *
     * This used to be a fresh newSingleThreadExecutor() per engine build with no
     * shutdown anywhere. An engine is rebuilt at every player teardown that
     * learned a new QUIC host (applyPendingQuicHints), so each rebuild left one
     * more idle thread behind for the life of the process. The listener only
     * records a host's protocol and status, so one thread serves every engine
     * and simply outlives them.
     */
    private val transportLogExecutor: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "cronet-transport-log")
        }
    }

    private fun attachTransportLog(engine: org.chromium.net.CronetEngine) {
        val ex = engine as? org.chromium.net.ExperimentalCronetEngine ?: return
        runCatching {
            ex.addRequestFinishedListener(
                object : org.chromium.net.RequestFinishedInfo.Listener(transportLogExecutor) {
                    override fun onRequestFinished(info: org.chromium.net.RequestFinishedInfo) {
                        val host = runCatching {
                            java.net.URI(info.url).host ?: return
                        }.getOrNull() ?: return
                        val proto = info.responseInfo?.negotiatedProtocol ?: "(none)"
                        val code = info.responseInfo?.httpStatusCode ?: -1
                        // RECORD EVERY REQUEST, LOG ONLY THE FIRST. An episode is
                        // hundreds of requests and the protocol does not change
                        // between them; logging each would bury the answer. But
                        // the VALUES have to keep updating, because the request
                        // that matters to a relay report is the one that failed,
                        // and that is rarely the first one this host served.
                        val first = hostTransport.put(
                            host.lowercase(), HostTransport(proto, code)
                        ) == null
                        if (!first) return
                        Log.e("scraper-vidapi",
                            "🔬 TRANSPORT $host -> protocol=$proto http=$code " +
                            "(h3 = QUIC/UDP, h2 or http/1.1 = TCP)")
                    }
                }
            )
        }.onFailure {
            Log.e("scraper-vidapi", "🔬 transport listener unavailable: ${it.message}")
        }
    }

    // ── QUIC hints, learned instead of hardcoded ─────────────────────────────
    //
    // THE STATIC LIST CANNOT WORK, and one session's transport log shows exactly
    // why. vidfast does not have a CDN hostname — it has a rotating pool:
    //
    //   moon.peakstorm.top  -> h3   200   (hinted in build 82, so QUIC first)
    //   polarsable.top      -> h3   200   (not filtered, upgraded via alt-svc)
    //   boldapple.top       -> none -1    (not hinted, filtered, severed)
    //
    // Whether an episode plays is therefore decided by which hostname the
    // extractor happened to draw — a coin flip, which is precisely how this
    // looked like an intermittent, unexplainable fault for three days.
    //
    // WHY A HINT IS NEEDED AT ALL. Cronet normally discovers HTTP/3 from an
    // alt-svc header on an HTTP/2 response. A filtered host never sends one,
    // because its TCP handshake is severed before any response exists. Only
    // addQuicHint opens QUIC on the FIRST request, and that is build-time state.
    //
    // So: remember every CDN host we are asked to play, and rebuild the engine
    // when we meet a new one. The set persists, so a host costs one rebuild ever,
    // and the common case reuses the existing engine untouched.
    private const val QUIC_PREFS = "bh_quic_hints"
    /** Diagnostic only. Off by default — a NetLog records URLs and timings. */
    // OFF as of 2026-08-30, and it had stopped being merely expensive.
    //
    // MEASURED on the "Everest" failure: 48,229,470 bytes written in ~60s of
    // playback, with 50,518,626 more retained in the rotation — 98MB on a box
    // with 1.0GB free of 4.7GB (77% used). Those writes are sustained and
    // concurrent with video buffering, so on a low-end TV box the diagnostic is
    // a plausible contributor to the very stalls it was capturing. A measuring
    // instrument that loads the thing it measures has to come off before the
    // measurement means anything.
    //
    // It earned its keep first: rotation to .prev caught the finalroom.top
    // failure that overwrote itself, and these captures are what proved the
    // injected-packet, ECH and 300s-TTL findings. All of that is now settled.
    //
    // Flip to true to capture again; everything else is intact and per-playback
    // rotation still works.
    // BACK ON 2026-08-30, narrowly. Turning it off was premature: the I/O
    // suspicion that justified it was refuted by the very capture it was
    // suspected of spoiling, and an open question remains that only NetLog can
    // answer — whether moon.peakstorm.top is being served from a HostCache entry
    // that carries no ECH config, which would make the warm a no-op for exactly
    // the hosts that need it.
    //
    // The event cap that made these captures useless for LATE failures is
    // harmless here: it fills after ~23s because QUIC packet logging burns
    // ~12,700 events/sec, and the DNS phase this question needs is in the first
    // seconds. A short capture is sufficient and, at ~50MB, still costs real
    // disk — so this comes off again the moment the question is settled.
    // OFF. It has now answered every question it was turned on for, and each of
    // those answers is recorded in the comments around this file:
    //   - the injected QUIC INITIAL (packet_number 0x80000000, sub-RTT arrival)
    //   - ECH confirmed active, and the 300s HTTPS-record TTL behind the
    //     five-minute pattern of a host working and then not
    //   - the silent OkHttp fallback, proved by a capture with ZERO
    //     SSL_HANDSHAKE_ERROR events during four logged handshake refusals
    //
    // It is not free: ~50MB per playback, two generations retained, on a box with
    // ~1GB spare — and it records URLs, which has no place in a build customers
    // run. Flip to true to capture again; rotation to .prev still works, and the
    // 300,000-event cap means a capture covers roughly the first 23 seconds, so
    // it is only useful for questions about the START of a playback.
    private const val NETLOG_ENABLED = false

    /**
     * EXPERIMENT — can DNS replace the whole QUIC-hint apparatus?
     *
     * Cronet can learn "this host speaks QUIC" straight from the HTTPS (SVCB) DNS
     * record; that is the dns_alpn_h3 job visible in NetLog. If it does, a brand
     * new CDN gets QUIC on its first request with no hint, no list, no backend
     * endpoint and no engine rebuild — and every one of those pieces becomes dead
     * weight, including the two that caused outages today.
     *
     * It is not working right now: NetLog shows endpoint_metadatas:[] even though
     * 30 of 32 CDNs publish alpn=h3. UseDnsHttpsSvcb/use_alpn is the documented
     * switch and has never been tried here.
     *
     * TRUE ALSO EMPTIES THE HINT LIST, deliberately. Leaving hints on would make
     * the result unreadable — QUIC would work either way and prove nothing. The
     * only honest test is to remove the crutch and see whether DNS carries it.
     *
     * REVERT: set this to false, rebuild, install. Nothing else changes — the
     * list, endpoint and merge logic are all still present and simply resume.
     */
    // RESULT, 2026-08-29, and the reason it failed was NOT what this file said.
    //
    // The experiment reported FAILED: hints emptied + use_alpn on, three films,
    // protocol=(none) every time. I concluded Cronet ignores the DNS record and
    // that ECH was gated behind secure DNS "which Cronet exposes no way to
    // satisfy". BOTH CONCLUSIONS WERE WRONG, and NetLog says so:
    //
    //   - ECH HAS ALREADY WORKED ON THIS DEVICE over ordinary insecure AsyncDNS
    //     against Charter's own resolver (secure:false in the resolver dump).
    //     orbitpanda.top resolved its HTTPS record, sent a real inner
    //     ClientHello (SSL_ENCRYPTED_CLIENT_HELLO, outer SNI cloudflare-ech.com)
    //     and moved 32,854 QUIC packets untouched. There is no secure-DNS gate.
    //   - The record is not missing, it is CANCELLED — Chromium's 5 ms floor for
    //     the supplemental type-65 query expires before the answer arrives. See
    //     the timeline in the options block above.
    //   - The experiment ALSO emptied the hint list, so its negative result
    //     could not separate "SVCB does nothing" from "nothing was left to fall
    //     back on". Two changes, one observation: unreadable either way.
    //
    // The flag is deleted rather than kept, because the thing it tested is now
    // shipping unconditionally with the timing it actually needed.
    @Volatile private var pendingHintRebuild = false

    @Synchronized
    fun ensureQuicHint(context: Context, url: String) {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return
        if (host.isBlank()) return

        // NEVER HINT OUR OWN RELAY, and never rebuild the engine because of it.
        // MEASURED 2026-08-29: a relay fallback swaps the player's url to
        // api.<domain>, which looked like a brand-new CDN to this function — so
        // it tore down and rebuilt the transport IN THE MIDDLE of a recovery,
        // exactly when playback was least able to absorb it. Our own origin is
        // never ISP-filtered and never needs a QUIC hint to be reachable.
        val ownHost = runCatching {
            java.net.URI(com.example.bluehive.BuildConfig.API_BASE_URL).host
        }.getOrNull()
        if (ownHost != null && host.equals(ownHost, ignoreCase = true)) return

        restartNetLog(context)

        // PER-PLAYBACK, NOT PER-PROCESS. hostTransport suppresses duplicate
        // transport lines, which is right within one playback and wrong across
        // several: the second play of a host that has since REGRESSED to TCP
        // would print nothing, and silence reads as success. Cleared here
        // because this runs once, at the start of each playback.
        hostTransport.clear()

        // AFTER THE WARM, WHICH IS THE WHOLE POINT. warmHostsAwait has already
        // run by the time a playback reaches here, so the probe now measures an
        // engine that holds the ECH configs it is supposed to use. Dispatched,
        // never awaited: it is one small request and the answer is wanted three
        // seconds from now at the earliest, not before the first byte.
        runCatching { cronet(context) }.getOrNull()?.let { probeEch(it) }
        val prefs = context.getSharedPreferences(QUIC_PREFS, Context.MODE_PRIVATE)
        val known = prefs.getStringSet("hosts", emptySet()) ?: emptySet()
        if (host in known) return
        prefs.edit().putStringSet("hosts", known + host).apply()
        // REMEMBERED, NOT REBUILT. The engine is deliberately left alone.
        //
        // MEASURED 2026-08-29 and this cost a working setup: dropping the engine
        // reference per new host leaks it — shutdown() cannot be called safely
        // while requests may be in flight — and after a few rebuilds Cronet
        // failed to construct at all. cronet() then returned null,
        // dataSourceFactory silently fell back to OkHttp, and OkHttp has no QUIC,
        // so every stream went back over filtered TCP. The tell was the exception
        // TYPE flipping to javax.net.ssl.SSLException, which Cronet never throws.
        //
        // Worse, that failure logged at Log.w — stripped from release builds — so
        // the transport degraded invisibly and looked like the ISP getting
        // stricter rather than the app quietly losing its network stack.
        //
        // So the hint lands on the NEXT app start. The cost is that the very
        // first playback from an unseen CDN host may fall back to the relay once;
        // every play after that uses QUIC. That is a far better trade than
        // risking the transport for everything.
        pendingHintRebuild = true
        Log.e("scraper-vidapi",
            "🔀 learned new CDN host $host — QUIC hint applies when the player " +
            "closes (see applyPendingQuicHints)")
    }

    /**
     * Rebuild the transport if new CDN hosts were learned — called when a player
     * is torn down, which is the only moment this is safe.
     *
     * WHY NOT IMMEDIATELY. addQuicHint is build-time state, so a newly learned
     * host cannot take effect without a new engine. Doing that mid-playback cost
     * a working setup on 2026-08-29: the reference was dropped while requests
     * were still in flight (shutdown() throws in that state), the engines leaked,
     * and after a few rebuilds Cronet stopped constructing entirely. cronet()
     * then returned null and dataSourceFactory fell back to OkHttp, which has no
     * QUIC — so every filtered CDN failed, and the giveaway was the exception
     * type flipping to javax.net.ssl.SSLException.
     *
     * WHY NOT ONLY AT APP START. That was the safe overcorrection, and it made
     * the behaviour surprising: a viewer whose stream relayed would back out,
     * replay, and get relayed again, because ExoPlayerConfig is an object and the
     * engine outlives the Activity. "Close it and try again" is what anyone would
     * expect to work, so that is where the rebuild belongs.
     *
     * onDestroy is safe because the player has already been released, so no
     * request is in flight and shutdown() can be called properly rather than the
     * reference being abandoned.
     */
    @Synchronized
    fun applyPendingQuicHints() {
        if (!pendingHintRebuild) return
        pendingHintRebuild = false
        val old = cronetEngine ?: return
        // SHUT DOWN FIRST, AND ONLY DROP THE REFERENCE IF IT SUCCEEDS.
        //
        // The earlier version dropped it either way, on the reasoning that a
        // leaked engine beats an unapplied hint. That was wrong and it cost a
        // working session on 2026-08-29:
        //
        //   19:47:12  moon.peakstorm.top -> h3 200          (working)
        //   19:47:17  engine still busy — dropped without shutdown
        //   19:47:35  TLS handshake refused for moon.peakstorm.top
        //   19:47:38  relay granted
        //
        // Cronet is a process-wide singleton shared across activities, so a
        // player reaching onDestroy does NOT mean nothing is using it —
        // shutdown() throws IllegalStateException while any request lives. Drop
        // it then and two engines exist at once; do that a few times and Cronet
        // stops constructing altogether, cronet() returns null, and every stream
        // silently falls to OkHttp, which has no QUIC.
        //
        // So a busy engine is simply KEPT. pendingHintRebuild stays true and the
        // next teardown tries again; one of them eventually lands while the
        // engine is genuinely idle. The cost of waiting is at most one more
        // relayed playback. The cost of leaking is the transport itself.
        val shutdownOk = runCatching { old.shutdown() }.isSuccess
        if (!shutdownOk) {
            pendingHintRebuild = true      // try again at the next teardown
            Log.e("scraper-vidapi",
                "🔀 engine still busy — KEEPING it (dropping would leak and has " +
                "previously broken QUIC entirely); hints retry on next close")
            return
        }
        cronetEngine = null
        // RELEASE THE ONE-SHOT LATCH, or this is not a rebuild — it is a
        // permanent teardown.
        //
        // cronet() opens with `if (cronetTried) return cronetEngine`, and
        // cronetTried was set true by the FIRST construction and never reset.
        // Nulling the engine without clearing it therefore made every later
        // cronet() call return null forever: the prewarm below got null and
        // silently did nothing, dataSourceFactory got null and fell through to
        // OkHttp, and the app lost QUIC and ECH for the rest of the process.
        //
        // MEASURED 2026-08-31: merges at 15:16 and 15:28 each armed a rebuild;
        // by 15:32 the app was logging "CRONET LOST after having worked" and
        // extraction was timing out. The three retries added in build 112 could
        // not help — all three hit the same latch. Only a force-stop recovered
        // it, because a new process starts with cronetTried = false.
        //
        // This one line is what makes "rebuild" mean rebuild.
        cronetTried = false
        Log.e("scraper-vidapi", "🔀 transport rebuilt with newly learned QUIC hints")

        // A REBUILT ENGINE STARTS WITH AN EMPTY HOST CACHE, AND ECH LIVES ONLY
        // THERE. Confirmed from the NetLog's own config:
        // quicInfo.max_server_configs_stored_in_properties = 0 — Chromium keeps
        // no persistent ECH store, so every config the prewarm fetched dies with
        // the old engine. The next request then resolves cold, inside the
        // playback critical path, and can lose the race for a fresh config —
        // which puts the hostname back on the wire in cleartext.
        //
        // MEASURED 2026-08-30 on "Everest": moon.peakstorm.top served h3 200 at
        // 13:55:43, the transport was rebuilt at 13:58:59, and at 13:59:10 the
        // same host produced four TLS handshake refusals ~600ms after its warm
        // was dispatched — an ECH lookup here needs up to 587ms, so it lost.
        //
        // Re-arm the whole prewarm, not just the current stream's hosts: the
        // cache that was just discarded held all of them.
        // THROTTLED, not unconditional. A rebuild does lose every cached ECH
        // config, so re-warming is right in principle — but this fires ~123 HEAD
        // requests, and rebuilds are armed by ANY newly learned host, which now
        // means roughly one per playback. MEASURED 2026-08-31: merges at 15:16
        // and 15:28 each armed one, and shortly after the engine stopped
        // constructing altogether ("CRONET LOST after having worked"), dropping
        // the app to OkHttp with no QUIC and no ECH until it was force-stopped.
        //
        // The stream's OWN hosts are warmed per-play by warmHostsAwait, which is
        // the warm that actually decides whether the next playback is protected.
        // This bulk pass is a background nicety and can wait.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBulkPrewarmMs >= BULK_PREWARM_MIN_INTERVAL_MS) {
            lastBulkPrewarmMs = now
            prewarmed = false
            BlueHiveApplication.appContext?.let { prewarmCdnDns(it) }
        } else {
            Log.e("scraper-vidapi",
                "🔀 transport rebuilt — skipping bulk prewarm " +
                "(${(now - lastBulkPrewarmMs) / 1000}s since the last one); " +
                "this stream's hosts are warmed per-play")
        }
    }

    /**
     * Merge a backend-supplied host list into what this device already knows.
     *
     * MERGED, NEVER REPLACED. A device may have discovered a host the backend has
     * not recorded yet — a rotation it met first, or an extraction that happened
     * on another client — and overwriting would throw that away and make it pay
     * discovery a second time.
     *
     * Does not rebuild the transport itself: this runs at app start, before any
     * engine exists, so the hints are simply present when one is first built. The
     * flag is set anyway so that if an engine somehow already exists, the next
     * player teardown picks the new hosts up.
     */
    @Synchronized
    fun mergeQuicHints(
        context: Context,
        hosts: Collection<String>,
        withdraw: Collection<String> = emptyList(),
    ) {
        if (hosts.isEmpty() && withdraw.isEmpty()) return
        val prefs = context.getSharedPreferences(QUIC_PREFS, Context.MODE_PRIVATE)
        val known = prefs.getStringSet("hosts", emptySet()) ?: emptySet()
        val clean = hosts.mapNotNull { it.trim().lowercase().ifBlank { null } }.toSet()

        // WITHDRAWAL, because merging alone cannot fix a bad entry. Merge is
        // additive on purpose — a device may know a host the backend has not met
        // — but that also means a host once learned is kept forever, including
        // one that CANNOT speak h3 and must never be hinted.
        //
        // MEASURED 2026-08-31: s1.piltover.li and s1.watami.win (Miruro) sat in
        // this set from earlier merges. Every connection to them opened with a
        // doomed QUIC handshake, and with the 1s broken-service delay Cronet
        // retried it endlessly — segment reads timing out inside TsExtractor
        // while curl pulled the same bytes at 3-9 MB/s. Filtering them out of the
        // backend's list fixed nothing on a device that had already stored them.
        //
        // Only the backend withdraws, and only on positive evidence (a DNS answer
        // with no h3). Absence from `hosts` is NOT a withdrawal.
        val drop = withdraw.mapNotNull { it.trim().lowercase().ifBlank { null } }.toSet()
        if (drop.isNotEmpty()) {
            // Remembered, not just subtracted once. Any later path may re-add the
            // host; learnedQuicHosts() filters against this on every read.
            val blocked = prefs.getStringSet(NO_QUIC_KEY, emptySet()) ?: emptySet()
            if (!blocked.containsAll(drop)) {
                prefs.edit().putStringSet(NO_QUIC_KEY, blocked + drop).apply()
            }
        }
        val merged = (known + clean) - drop
        if (merged == known) return
        prefs.edit().putStringSet("hosts", merged).apply()
        pendingHintRebuild = true
        val added = (clean - known).size
        val removed = (known intersect drop).size
        Log.e("scraper-vidapi",
            "🔀 CDN hints: +$added, -$removed (${merged.size} known)" +
            if (removed > 0) " — withdrew h2-only: ${(known intersect drop).joinToString()}" else "")
    }

    /** Hosts the backend has PROVEN cannot speak h3. Never hinted, never warmed. */
    private const val NO_QUIC_KEY = "h2_only"


    /**
     * Hosts learned so far, fed into the builder alongside the static seeds.
     *
     * FILTERED ON READ, which is the whole guarantee. THREE separate paths add
     * hosts — the backend merge, ensureQuicHint on a newly met host, and
     * warmHosts on an extraction's reported list — and subtracting only in the
     * first was not enough. MEASURED 2026-08-31: the backend withdrew
     * s1.watami.win at 14:57, and the next anime play put it straight back
     * ("warmed 1/1 host(s) (1 new) ok=s1.watami.win") because warmHosts merges
     * without a withdrawal list.
     *
     * Filtering here means it does not matter which path re-adds it: a host on
     * the no-QUIC list can never reach addQuicHint. Storage may still carry the
     * name; nothing reads it back.
     */
    private fun learnedQuicHosts(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(QUIC_PREFS, Context.MODE_PRIVATE)
        val known = prefs.getStringSet("hosts", emptySet()) ?: emptySet()
        val blocked = prefs.getStringSet(NO_QUIC_KEY, emptySet()) ?: emptySet()
        return if (blocked.isEmpty()) known else known - blocked
    }

    /**
     * Data source for extracted streams. Cronet (HTTP/3) when available, OkHttp
     * otherwise — see [cronet] for why the transport is the whole point.
     *
     * [context] is optional so existing callers keep working; without it there is
     * no way to build a Cronet engine and this behaves exactly as before.
     */
    fun dataSourceFactory(
        userAgent: String,
        headers: Map<String, String>,
        // Null means the shared client, resolved ONLY if the OkHttp branch is
        // taken. A default of okHttpClient() here was evaluated on every call,
        // Cronet or not.
        client: OkHttpClient? = null,
        context: Context? = null,
    ): DataSource.Factory {
        // ── OKHTTP CANNOT PLAY ON THIS LINE, SO NEVER REACH FOR IT QUIETLY ──
        //
        // MEASURED 2026-08-30 on "Bird Box". The manifest loaded over h3 at
        // 15:22:56 with ECH; 43 seconds later the SAME host was severed four
        // times, and the NetLog covering that exact window contains ZERO
        // SSL_HANDSHAKE_ERROR events (31,377 of a 300,000 cap, so nothing was
        // truncated). The stack trace says why:
        //
        //     androidx.media3.datasource.okhttp.OkHttpDataSource.open(...)
        //     javax.net.ssl.SSLException: Unable to parse TLS packet header
        //
        // Those requests never went through Cronet at all. OkHttp has no QUIC and
        // no ECH, so it puts the hostname on the wire in cleartext and the ISP
        // filter severs it EVERY time. That is the whole of the "intermittency":
        // on Cronet it works, on OkHttp it cannot.
        //
        // So a null engine is not a benign degradation here, it is a guaranteed
        // relay. Retry once — engine construction has failed transiently before,
        // notably after the leak that made cronet() return null for the rest of
        // the process — and if it still fails, say so at Log.e, which is the one
        // level release builds keep. Silence is what let this hide all day behind
        // four different theories.
        var engine = context?.let { cronet(it) }
        if (engine == null && context != null) {
            // TWO DIFFERENT SITUATIONS, and only one is survivable.
            //
            // Cronet has built before in this process -> a null engine is OUR bug
            // (a teardown racing a rebuild, or the leak that once made cronet()
            // return null for the rest of the process). Handing back OkHttp there
            // is not graceful degradation, it is a guaranteed relay, because
            // OkHttp cannot conceal a hostname. So retry rather than accept it.
            //
            // Cronet has NEVER built -> this device lacks a usable provider.
            // OkHttp is then the only thing that plays at all, and a customer with
            // extra relays beats a customer with a black screen.
            repeat(3) { if (engine == null) engine = cronet(context) }
            Log.e("scraper-vidapi", when {
                engine != null ->
                    "⚠️ Cronet was null — rebuilt on retry; QUIC/ECH intact"
                cronetEverBuilt ->
                    "⚠️ CRONET LOST after having worked — this is a bug, not a device " +
                    "limit. Using OkHttp, which has no QUIC and no ECH: every filtered " +
                    "host WILL be severed and relayed until the engine returns."
                else ->
                    "⚠️ CRONET NEVER AVAILABLE on this device — using OkHttp. Expect " +
                    "relays on any ISP-filtered host; there is no client-side escape."
            })
        }
        val http: DataSource.Factory = if (engine != null) {
            androidx.media3.datasource.cronet.CronetDataSource.Factory(
                engine, cronetCallbackExecutor
            )
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)
                // NO setFallbackFactory HERE, DELIBERATELY — it was dead code.
                // Media3's own source settles it: Factory(CronetEngine, Executor)
                // does checkNotNull(cronetEngine), and createDataSource() consults
                // a fallback ONLY when cronetEngine == null. With a live engine
                // that branch is unreachable, so the call we used to make here had
                // never once run.
                //
                // It still cost hours on 2026-08-30: it made the OkHttp requests in
                // the logs look like a designed per-request retry, when the real
                // OkHttp path is the `else` below — taken when cronet() returns
                // null. Removing it so the next reader is not misled the same way.
        } else {
            OkHttpDataSource.Factory(client ?: okHttpClient())
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)
        }
        // Wrapped so a handshake the NETWORK refuses gets a second chance
        // through an SNI-safe path — see SniSafeDataSource. Pass-through until
        // a fetcher is installed, so this changes nothing today.
        return SniSafeDataSource.Factory(http)
    }

    /** HLS media source from an m3u8 URL. */
    /**
     * Load-error policy that REFUSES TO FALL BACK ONTO A DIFFERENT RENDITION when
     * the server refuses a request.
     *
     * WHY, MEASURED 2026-08-28 on Mushoku Tensei s1e6 while the freeze was live.
     * Sampling four segments from each rung of the same master playlist:
     *
     *   640x360    800 kbps   200 403 403 403
     *   1280x720  2800 kbps   200 403 200 200
     *   1920x1080 5500 kbps   200 200 200 200
     *
     * The CDN serves the top rung correctly and the lower ones barely at all.
     * Playback therefore starts fine on 1080p — FORCE_HIGHEST_BITRATE picks it —
     * and dies later for a reason that looks like nothing to do with quality.
     *
     * THE TRAP IS THAT FORCING THE BITRATE DOES NOT PREVENT THIS. That is a
     * PREFERENCE, applied when choosing among healthy tracks. The fallback that
     * actually bites is error-driven: one transient failure on a 1080p segment
     * makes the default policy blacklist that variant and switch to 720p, which
     * is broken, which blacklists in turn and drops to 360p, which is worse. A
     * single hiccup cascades into every rung exhausted and a fatal error, and the
     * user sees a freeze minutes after the hiccup that caused it.
     *
     * Returning null from getFallbackSelectionFor says "do not exclude this
     * track, do not switch" — so a refusal is retried on the rung that works
     * instead of being escaped onto rungs that do not. Retry timing is left to
     * the default policy, and MiruroPlayerActivity's cooldown still owns what
     * happens once the retries are exhausted.
     *
     * NOT APPLIED TO progressiveMediaSource: a single-file source has no
     * renditions to fall back between, so there is nothing for this to change.
     */
    private val noRenditionFallback = object : DefaultLoadErrorHandlingPolicy() {
        override fun getFallbackSelectionFor(
            fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
            loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
        ): LoadErrorHandlingPolicy.FallbackSelection? {
            val cause = loadErrorInfo.exception
            if (cause is HttpDataSource.InvalidResponseCodeException &&
                (cause.responseCode == 403 || cause.responseCode == 429 ||
                 cause.responseCode == 401)) {
                return null
            }
            return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
        }
    }

    fun hlsMediaSource(url: String, factory: DataSource.Factory): HlsMediaSource =
        HlsMediaSource.Factory(factory)
            .setLoadErrorHandlingPolicy(noRenditionFallback)
            .createMediaSource(MediaItem.fromUri(url))

    /**
     * Whole-file (non-segmented) source, for sources that hand back a video file
     * instead of a playlist.
     *
     * MEASURED 2026-08-07 on vidnest.fun: the video arrives as
     * hlmv.tripplestream.online/videos/<id> — one progressive file, no .m3u8, no
     * extension at all. Feeding that to HlsMediaSource fails at the parse: it
     * expects to read a #EXTM3U playlist and gets raw container bytes, so the
     * load errors before a frame is decoded.
     *
     * Seeking here rides on HTTP range requests rather than segment boundaries,
     * which is why it must be a different source type and not just a different
     * URL. The DataSource.Factory is shared, so the site-specific Referer/Origin
     * headers behave identically on both paths.
     */
    fun progressiveMediaSource(url: String, factory: DataSource.Factory): ProgressiveMediaSource =
        ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url))

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
            // THE CONTROL BAR'S SEEK BUTTONS READ THESE, not SEEK_INCREMENT_MS.
            // media3 wires exo_rew and exo_ffwd straight to player.seekBack()
            // and seekForward(), which use the PLAYER's increments -- and
            // ExoPlayer defaults to 5s back and 15s forward, which is where the
            // mismatched "5" and "15" on the stock bar came from.
            // SEEK_INCREMENT_MS was only ever honoured by the channel-key
            // handler in dispatchKeyEvent, so the two disagreed depending on
            // which control you used.
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()

    // ── Header helpers (shared shape, per-site values) ────────────────────────


    /** `https://host` for a URL — the Origin that pairs with a page/CDN Referer. */
}
