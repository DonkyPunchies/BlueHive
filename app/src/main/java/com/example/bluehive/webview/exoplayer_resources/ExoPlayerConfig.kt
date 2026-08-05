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
    ) {
        scope.launch {
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

    /** Applies the shared video policy to a selector that already exists. */
    fun applyVideoPolicy(selector: DefaultTrackSelector) {
        selector.setParameters(
            selector.buildUponParameters()
                .setForceHighestSupportedBitrate(FORCE_HIGHEST_BITRATE)
                .setMaxVideoSize(MAX_VIDEO_WIDTH, MAX_VIDEO_HEIGHT)
                .setTunnelingEnabled(TUNNELING_ENABLED)
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
                val quicHosts = listOf(
                    "signtime.site", "sandstorm13.site",
                    "premiumvacations.site", "diskphone12.site",
                    "moon.ironwallnet.net",
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
                val opts = """
                    {"QUIC":{
                       "origins_to_force_quic_on":"*",
                       "idle_connection_timeout_seconds":300,
                       "migrate_sessions_on_network_change_v2":true
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
                quicHosts.forEach { b.addQuicHint(it, 443, 443) }
                b.setExperimentalOptions(opts)
                b.build()
            }.onFailure {
                Log.w("scraper-vidapi", "🚀 Cronet unavailable (${it.message}) — using OkHttp")
            }.getOrNull()
            cronetEngine?.let {
                Log.d("scraper-vidapi", "🚀 Cronet ready (QUIC on) — ${it.versionString}")
            }
            return cronetEngine
        }
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
        client: OkHttpClient = okHttpClient(),
        context: Context? = null,
    ): DataSource.Factory {
        val engine = context?.let { cronet(it) }
        val http: DataSource.Factory = if (engine != null) {
            androidx.media3.datasource.cronet.CronetDataSource.Factory(
                engine, java.util.concurrent.Executors.newSingleThreadExecutor()
            )
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)
                // A QUIC path that fails must not kill playback outright: fall
                // back to the TCP stack for that request rather than erroring.
                .setFallbackFactory(
                    OkHttpDataSource.Factory(client)
                        .setUserAgent(userAgent)
                        .setDefaultRequestProperties(headers)
                )
        } else {
            OkHttpDataSource.Factory(client)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)
        }
        // Wrapped so a handshake the NETWORK refuses gets a second chance
        // through an SNI-safe path — see SniSafeDataSource. Pass-through until
        // a fetcher is installed, so this changes nothing today.
        return SniSafeDataSource.Factory(http)
    }

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
