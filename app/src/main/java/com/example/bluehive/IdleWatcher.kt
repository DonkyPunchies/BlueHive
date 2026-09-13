package com.example.bluehive

import android.os.SystemClock
import android.util.Log
import com.example.bluehive.api.ApiClient
import com.example.bluehive.auth.DeviceEventStream
import com.example.bluehive.auth.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Releases this TV's streaming slot when nobody is actually using it.
 *
 * THE HOLE THIS FILLS
 * -------------------
 * The server's notion of "active" is driven by the SSE keepalive, which is driven
 * by the SOCKET, not by the user. As long as the app is foregrounded the stream
 * stays connected, last_seen_at refreshes every 30s, and the device counts against
 * the household cap indefinitely. Open BlueHive, walk away, and the slot is yours
 * for as long as the TV has power — the reaper never fires because the connection
 * is perfectly healthy.
 *
 * [InactivityWatcher] does NOT cover this despite its name: it only handles Home
 * (background) and return (foreground). An app sitting in the foreground with
 * nobody in front of it is invisible to it.
 *
 * WHY THIS RELEASES VIA /end-session AND NOT /background
 * ------------------------------------------------------
 * /background starts a 15-minute Redis grace, on the reasoning that someone who
 * pressed Home might come straight back. Stacking that on top of an idle timeout
 * means 30 + 15 = 45 minutes before a slot is reclaimable, which in a four-slot
 * household is most of a film. Someone who has already sat motionless for half an
 * hour does not need a second grace period.
 *
 * /end-session is instant, already broadcasts slot_freed so other TVs sitting on
 * the lockout screen re-enter immediately, and is semantically the honest call:
 * "release my slot". It also needs no server changes at all.
 *
 * It deliberately calls [DeviceEventStream.stop] and NOT stopAndMarkExited: the
 * exit latch blocks start() for the whole process lifetime and exists for the back
 * button. An idle release must stay resumable, because the user is expected back.
 *
 * ACCEPTED CONSEQUENCE: RELEASING MEANS YOU MIGHT NOT GET IT BACK. If the
 * household fills up while a TV is idle, the returning user lands on the lockout
 * screen. That is the cap working as intended rather than a defect — but it is a
 * decision, so [consumeIdleReleaseFlag] exists to let the lockout screen say
 * "your session timed out" instead of the generic full-slots copy.
 *
 * PLAYBACK IS NOT INPUT, AND INPUT IS NOT PRESENCE
 * ------------------------------------------------
 * A two-hour film produces no remote input at all, so an input-only timer would
 * free the slot mid-film and lock the user out of their own stream. Playback
 * therefore suppresses the plain idle timer.
 *
 * Playback is now a FULL exemption: while anything reports playing, this class
 * does nothing at all. That used to be a longer fuse plus a "still watching?"
 * confirmation, which was removed on 2026-09-11 — see the note beside the
 * thresholds for what it covered and what is now uncovered.
 *
 * The exemption is safe for ordinary media because the players end themselves:
 * both ExoPlayer activities finish() on STATE_ENDED, and the WebView clears its
 * playing flag on the page's own media-session pause. It is NOT safe for media
 * that never ends — live streams are deliberately excluded from that STATE_ENDED
 * branch — so falling asleep on Live TV holds the slot until someone presses a
 * button. That is a known, accepted gap rather than an oversight.
 *
 * TRAILERS ARE DELIBERATELY NOT REGISTERED HERE. The home-screen trailer overlay
 * and the details-screen trailer player autoplay without anyone asking, so
 * treating them as playback would mean an unattended home screen suppresses its
 * own idle timer forever — the precise bug this file exists to close. They are
 * absent from [PLAYBACK_SOURCES] by omission, and that omission is the design.
 */
object IdleWatcher {

    private const val TAG = "IdleWatcher"

    // ── Thresholds ────────────────────────────────────────────────────────────
    //
    // NOT DERIVED FROM ACTIVE_WINDOW_SECONDS, and worth saying why. The backend
    // deliberately converges every "device is gone" path on one 15-minute number
    // so no two mechanisms can disagree. These are a different KIND of timeout —
    // they measure the human, not the connection — so sharing that constant would
    // couple two things that should move independently. Fifteen minutes of
    // stillness is a normal pause; fifteen minutes without a keepalive is a dead
    // TV.
    //
    // Changing these is a one-line edit and a rebuild. There is no runtime knob on
    // purpose: a value that can drift per-device would make "why did my slot go"
    // unanswerable from logs alone.

    /** No input, nothing playing — detail screen, paused player, ended player. */
    private const val IDLE_RELEASE_MS = 30L * 60 * 1000

    /**
     * No input for this long, nothing playing — close the app entirely, not just
     * release the slot.
     *
     * WHY THIS EXISTS AT ALL. These boxes do not power down with the TV. Turning
     * the TV off drops HDMI, which is not an Android lifecycle event, so onStop
     * may never fire and the process simply keeps running — for days. Two things
     * rot in that state:
     *
     *   1. UPDATES. UpdateManager.checkForUpdate() is called from exactly one
     *      place, AuthActivity, on launch. A process that never dies never checks.
     *      A TV left on for a fortnight would never learn a new version exists,
     *      which quietly disables self-update for the people least likely to
     *      notice. (Foreground-return now also checks — see BlueHiveApplication —
     *      but that only helps someone who actually leaves and comes back.)
     *
     *   2. MEMORY. Coil caches, GeckoView's helper processes and this app's own
     *      bitmap history all accumulate. On a 2GB box that is the shape of thing
     *      that has OOM-killed us before.
     *
     * KEYED ON INPUT, NOT ON LIFECYCLE, and that is the point. A background-based
     * timer would miss the exact case this is for, because the app is often not
     * backgrounded when the TV goes off. "No human has pressed anything in two
     * hours" is true regardless of what Android thinks the lifecycle is.
     *
     * TWO HOURS, REDUCED FROM FOUR ON 2026-09-11, and the argument is the one
     * [IDLE_RELEASE_MS] already makes: the slot is handed back at THIRTY MINUTES.
     * Past that the process holds nothing the user or the backend cares about —
     * it is cache, and cache is precisely what the second reason above says has
     * OOM-killed us before. Keeping it for another three and a half hours bought
     * only a warm start for somebody who had already been gone half an hour.
     *
     * WHAT THE SHORTER VALUE COSTS, measured on the onn box: a cold start runs
     * roughly 15 seconds from launcher to profile picker, and it is paid only by
     * someone returning after two idle hours — who is beginning a fresh session
     * anyway. What the longer value cost was a ~170MB process sitting on a 2GB
     * box for most of an evening while somebody watched something else on the
     * same TV.
     *
     * Still comfortably clear of IDLE_RELEASE_MS, so the two never interact, and
     * still long enough never to fire on someone who stepped out for dinner.
     */
    private const val CLOSE_AFTER_IDLE_MS = 2L * 60 * 60 * 1000

    // THE "STILL WATCHING?" PROMPT WAS REMOVED 2026-09-11, and what it covered is
    // worth recording because most of it is now covered elsewhere.
    //
    // It existed for one case: falling asleep with something playing. The player
    // kept rolling, keepScreenOnWhilePlaying held the screen, and the slot was held
    // all night. Since then BOTH ExoPlayer activities close themselves on
    // STATE_ENDED, and the WebView flips setPlaying(false) on the page's own
    // media-session pause — so ordinary media that runs to its end no longer needs
    // a prompt to reclaim anything.
    //
    // WHAT IS NO LONGER COVERED, stated plainly so nobody rediscovers it as a
    // mystery: live streams are deliberately excluded from that STATE_ENDED branch
    // (a live stream reports ENDED on a transient upstream gap), so live content
    // never ends on its own. A viewer who falls asleep on Live TV now holds a slot
    // and keeps this process alive until they touch the remote, because playback
    // suppresses both the release below and the close above. The same applies if a
    // third-party embed never emits its pause event.
    //
    // Removed at the owner's direction; the design that needs it is not the design
    // being built right now. Restoring it means this block, the prompt overlay, the
    // pause hooks in setPlaying, and the playing branch of tick().

    /**
     * Tick cadence. Pure arithmetic on a plugged-in TV; no network, no wakelock.
     */
    private const val TICK_MS = 15L * 1000

    // ── Playback sources ──────────────────────────────────────────────────────
    //
    // One id per surface that plays REAL content. There are three, and missing any
    // one of them means that player's viewers get a prompt (or a release) during a
    // film they are actually watching.
    const val SOURCE_HLS     = "hls"        // HlsPlayerActivity      — films / TV
    const val SOURCE_MIRURO  = "miruro"     // MiruroPlayerActivity   — anime
    const val SOURCE_WEBVIEW = "webview"    // MainWebViewer          — GeckoView

    private val PLAYBACK_SOURCES = setOf(SOURCE_HLS, SOURCE_MIRURO, SOURCE_WEBVIEW)

    // ── State ─────────────────────────────────────────────────────────────────

    /**
     * elapsedRealtime, NOT currentTimeMillis: monotonic, so an NTP correction or a
     * user changing the TV clock cannot make the timer expire early or — worse —
     * park it in the future and suppress every check until reboot. Same reasoning
     * as HostEntitlementRelay.lastCheckAt.
     */
    @Volatile private var lastInputAt = SystemClock.elapsedRealtime()

    /**
     * Which surfaces currently report playing. A SET rather than a boolean because
     * two can overlap during a hand-off (details screen -> player), and because a
     * set is self-healing: an activity that dies without its onIsPlayingChanged
     * ever firing false is still cleaned up by its onDestroy calling
     * [setPlaying] with false for its own id, and can never clear another's.
     */
    private val playingSources = mutableSetOf<String>()

    /** Set while the app is foregrounded. Background is InactivityWatcher's job. */
    @Volatile private var foreground = false

    /** True between an idle release and the next interaction that re-acquires. */
    @Volatile private var releasedForIdle = false

    /**
     * Latched once the idle close has been requested.
     *
     * The close is asynchronous — it ends the session, then finishes the task —
     * so the ticker can run several more times before the process actually goes.
     * Without this latch it would post every 15 seconds in that window.
     * Cleared by noteInteraction() so a close that somehow fails to land does not
     * leave the app permanently unable to ask again.
     */
    @Volatile private var closeRequested = false

    /**
     * Latched for the lockout screen. Distinct from [releasedForIdle], which is
     * cleared the instant the user touches the remote — long before the 429 that
     * needs to explain itself has come back.
     */
    @Volatile private var lastReleaseWasIdle = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Somebody touched the remote.
     *
     * Called from the window-callback hook in BlueHiveApplication, which wraps
     * EVERY activity's key and touch dispatch — so this fires for D-pad presses on
     * Compose screens, on the player, and inside GeckoView without any activity
     * needing to know this class exists. Overriding onUserInteraction() per
     * activity would have meant editing two dozen files and silently missing the
     * next one added.
     *
     * Cheap enough to call on every keystroke: two volatile writes and, only in
     * the rare woken-from-idle case, a stream start.
     */
    fun noteInteraction() {
        lastInputAt = SystemClock.elapsedRealtime()
        // Someone is here. Re-arm the close so a failed teardown cannot leave the
        // app unable to ever ask again.
        closeRequested = false

        if (releasedForIdle) {
            releasedForIdle = false
            Log.i(TAG, "user returned after idle release — re-acquiring slot")
            // start(), not startAfterUserReturn(): we never set the exit latch, so
            // there is nothing to clear, and clearing it here would resurrect a
            // back-button exit that happened to be followed by a stray key event.
            //
            // A 429 (household filled up while we were idle) surfaces through
            // LockoutBus from inside the stream's own connect loop, which is why
            // nothing is awaited here.
            DeviceEventStream.start()
        }
    }

    /**
     * A playback surface started or stopped playing.
     *
     * @param source one of [SOURCE_HLS] / [SOURCE_MIRURO] / [SOURCE_WEBVIEW].
     * @param pause  how to stop this surface if the prompt times out. Stored on
     *               the transition into playing and dropped on the way out, so a
     *               dead activity's lambda is never held.
     */
    fun setPlaying(source: String, isPlaying: Boolean) {
        if (source !in PLAYBACK_SOURCES) {
            // Loud rather than silent. A typo'd id would read as "this player never
            // plays", which manifests as the prompt appearing over a film — a
            // symptom nobody would trace back to a string literal.
            Log.w(TAG, "setPlaying for unknown source '$source' — ignored")
            return
        }
        synchronized(playingSources) {
            if (isPlaying) playingSources.add(source) else playingSources.remove(source)
        }
        Log.d(TAG, "playback $source=$isPlaying (playing=${snapshotPlaying()})")
    }

    /**
     * Is any player running right now?
     *
     * Public because the update gate needs it: an update must never take the
     * screen away from someone mid-film. Reading the same set the idle timer
     * uses means there is one answer to "is playback happening", not two that
     * can disagree.
     */
    fun isPlaying(): Boolean = snapshotPlaying().isNotEmpty()

    /** Anything playing right now? */
    private fun snapshotPlaying(): Set<String> =
        synchronized(playingSources) { playingSources.toSet() }

    /**
     * Called from ProcessLifecycleOwner. While backgrounded the slot is governed by
     * InactivityWatcher's Redis grace, so ticking here as well could release a slot
     * the grace was already counting down — two mechanisms, one slot, no
     * coordination. Foreground is the only state this class has an opinion about.
     */
    fun setForeground(isForeground: Boolean) {
        foreground = isForeground
        if (isForeground) {
            // Returning from background is interaction by definition, and without
            // this the first tick after a long background would see a stale
            // lastInputAt and release immediately.
            lastInputAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Consume the "the last release was an idle timeout" latch.
     *
     * Read once by LockoutActivity so it can explain itself. Consuming rather than
     * peeking, so a later unrelated workspace_full does not inherit the copy from
     * an idle release that happened an hour ago.
     */
    fun consumeIdleReleaseFlag(): Boolean {
        val was = lastReleaseWasIdle
        lastReleaseWasIdle = false
        return was
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            Log.i(TAG, "idle watcher started (release=${IDLE_RELEASE_MS / 60000}m, " +
                    "close=${CLOSE_AFTER_IDLE_MS / 60000}m)")
            while (isActive) {
                delay(TICK_MS)
                runCatching { tick() }
                    .onFailure { Log.w(TAG, "tick failed: ${it.message}") }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    // ── The decision ──────────────────────────────────────────────────────────

    private suspend fun tick() {
        // Every one of these is a reason this class has no opinion, not a reason to
        // release. Releasing on "I could not tell" is how an idle timer locks out a
        // paying user who was sitting right there.
        //
        // THE FOREGROUND GATE USED TO BE HERE AND IT MADE THE 4-HOUR CLOSE DEAD
        // CODE. Reported from the field 2026-09-11: a TV left on overnight still
        // had BlueHive running after six hours. Confirmed on the box — the process
        // had been up 5h26m with the close never firing.
        //
        // The cause is that a TV backgrounds this app long before four hours are
        // up. This one has screensaver_enabled=1 and screen_off_timeout=600000, so
        // the daydream takes the foreground after TEN MINUTES, BlueHive is stopped,
        // ProcessLifecycleOwner.onStop calls setForeground(false), and this
        // returned on line one for the rest of the night. The timer could only ever
        // have fired on a lit screen with the screensaver disabled.
        //
        // So the gate now sits BELOW the close, guarding only the slot work that
        // genuinely is foreground-only. The close is the opposite case: an app that
        // is backgrounded AND idle is exactly the process worth reclaiming, and the
        // slot has already been dealt with by InactivityWatcher's server-side grace.
        if (!SessionManager.get().isAuthenticated) return
        if (DeviceEventStream.isIntentionallyExited) return

        val idleMs  = SystemClock.elapsedRealtime() - lastInputAt
        val playing = snapshotPlaying()

        // ── Close the whole app ────────────────────────────────────────────────
        //
        // CHECKED BEFORE THE releasedForIdle SHORT-CIRCUIT, and that ordering is
        // the whole reason this works. The slot is released at 30 minutes, which
        // sets releasedForIdle and makes every later tick return immediately —
        // so a close placed after that guard could never be reached, no matter
        // how long the box sat idle. Written that way first; it was dead code.
        //
        // Independent of the release, not a follow-on from it: release() frees
        // the SLOT, this frees the PROCESS. A release that failed (offline, say)
        // must not also prevent the close.
        //
        // Still gated on nothing playing. By four hours the still-watching prompt
        // will long since have paused and released, so this should be unreachable
        // during playback — but the cost of being wrong is killing someone's film,
        // and the check is free.
        if (playing.isEmpty() && idleMs >= CLOSE_AFTER_IDLE_MS && !closeRequested) {
            closeRequested = true
            // Log.e, not Log.i: R8 strips v/d/i/w from release builds, so on the
            // builds users actually run this line did not exist — which is part of
            // why a close that never fired went unnoticed for so long. The one
            // event worth being able to find in a bug report is this one.
            Log.e(TAG, "idle ${idleMs / 60000}m, nothing playing, foreground=$foreground — closing the app")
            IdleCloseBus.post()
            return
        }

        // ── Everything past here is SLOT work, and the slot is only ours while
        // foregrounded. Backgrounding hands it to InactivityWatcher's server-side
        // 15-minute grace, so releasing again from here would be redundant at best
        // and would fight that mechanism at worst.
        if (!foreground) return

        if (releasedForIdle) return

        if (playing.isEmpty()) {
            // Nothing playing: a detail screen, a paused film, or a player sitting
            // on a finished one. Thirty minutes of stillness in any of those is a
            // room with nobody in it.
            if (idleMs >= IDLE_RELEASE_MS) {
                Log.i(TAG, "idle ${idleMs / 60000}m with nothing playing — releasing slot")
                release()
            }
            return
        }

        // Something is playing, so nothing here applies. Playback is the one state
        // this class will not act against: the release above would free a slot out
        // from under a running stream, and the close would kill someone's film.
        //
        // Nothing reclaims a session that plays forever any more — see the removal
        // note beside the thresholds. In practice both ExoPlayer surfaces close
        // themselves when the media ends, so this only leaves live content and a
        // web embed that never reports a pause.
    }

    /**
     * Hand the slot back.
     *
     * Bounded by a timeout because this runs on a tick, not on a user action —
     * a request wedged on a dead network must not keep the watcher from ticking
     * again. If the POST is lost entirely the stale-session reaper still flips the
     * device inactive ~15 minutes after the stream stops, so the worst case is the
     * old behaviour rather than a stuck slot.
     */
    private suspend fun release() {
        releasedForIdle    = true
        lastReleaseWasIdle = true

        // STOP THE STREAM FIRST, AND THE ORDER IS THE WHOLE POINT.
        //
        // MEASURED ON DEVICE 2026-08-24, with these two calls the other way round:
        //
        //   16:05:26.969  IdleWatcher: releasing slot
        //   16:05:27.012  DeviceEventStream: SSE stream ended cleanly — reconnecting
        //   16:05:27.014  DeviceEventStream: Stopping SSE event stream
        //
        // /end-session pushes force_close to THIS device's own Redis channel. The
        // stream reads it, returns STREAM_ENDED_CLEANLY, and its connect loop treats
        // that as "the server closed us, reconnect immediately" — resetting the
        // backoff and re-dialling, which re-acquires the very slot just released.
        // stop() landed 2ms later and won by luck; on a slower tick it would not,
        // and the feature would silently do nothing while logging a happy release.
        //
        // Stopping first removes the loop that could race. The POST below is an
        // ordinary HTTP call on a different client, unaffected by the stream being
        // gone.
        //
        // stop(), NOT stopAndMarkExited(): the exit latch is permanent for the
        // process and would block the re-acquire in noteInteraction().
        DeviceEventStream.stop()

        runCatching { withTimeout(2_000) { ApiClient.bluehiveApi.endSession() } }
            .onFailure { Log.w(TAG, "end-session ping failed (reaper is the backstop): ${it.message}") }
    }
}
