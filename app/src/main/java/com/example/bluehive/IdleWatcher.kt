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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * But suppressing it entirely reopens the hole in the exact case that prompted
 * this file: falling asleep during playback. The player keeps rolling, the screen
 * stays on (keepScreenOnWhilePlaying), and the slot is held all night. So playing
 * gets a much longer fuse and a confirmation prompt rather than an exemption —
 * the "still watching?" pattern, which an engaged viewer never sees because
 * pausing, seeking or skipping an intro all count as input.
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
     * No input while something IS playing, before we ask whether anyone is there.
     *
     * Long on purpose. This is the only timer that can interrupt someone who is
     * genuinely watching, so it is set well past the runtime of a feature film's
     * longest uninterrupted stretch. The cost of being wrong here is a prompt over
     * somebody's film; the cost of being wrong in the other direction is one
     * wasted slot until morning.
     */
    private const val STILL_WATCHING_MS = 90L * 60 * 1000

    /** How long the "still watching?" prompt waits before giving up. */
    private const val PROMPT_GRACE_MS = 60L * 1000

    /**
     * Tick cadence. Bounds the resolution of PROMPT_GRACE_MS, so it has to be well
     * under it — a 60s tick against a 60s grace would let the prompt sit for up to
     * two minutes. Pure arithmetic on a plugged-in TV; no network, no wakelock.
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

    /** How to pause each playing surface when the prompt times out. */
    private val pauseHooks = mutableMapOf<String, () -> Unit>()

    /** Set while the app is foregrounded. Background is InactivityWatcher's job. */
    @Volatile private var foreground = false

    /** True between an idle release and the next interaction that re-acquires. */
    @Volatile private var releasedForIdle = false

    /**
     * Latched for the lockout screen. Distinct from [releasedForIdle], which is
     * cleared the instant the user touches the remote — long before the 429 that
     * needs to explain itself has come back.
     */
    @Volatile private var lastReleaseWasIdle = false

    /** elapsedRealtime the prompt went up, or 0 when it is down. */
    @Volatile private var promptShownAt = 0L

    private val _stillWatching = MutableStateFlow(false)

    /** Collected by [StillWatchingOverlay]; true while the prompt should be up. */
    val stillWatching: StateFlow<Boolean> = _stillWatching.asStateFlow()

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

        if (promptShownAt != 0L) {
            promptShownAt = 0L
            _stillWatching.value = false
            Log.d(TAG, "still-watching prompt dismissed by input")
        }

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
    fun setPlaying(source: String, isPlaying: Boolean, pause: (() -> Unit)? = null) {
        if (source !in PLAYBACK_SOURCES) {
            // Loud rather than silent. A typo'd id would read as "this player never
            // plays", which manifests as the prompt appearing over a film — a
            // symptom nobody would trace back to a string literal.
            Log.w(TAG, "setPlaying for unknown source '$source' — ignored")
            return
        }
        synchronized(playingSources) {
            if (isPlaying) {
                playingSources.add(source)
                if (pause != null) pauseHooks[source] = pause
            } else {
                playingSources.remove(source)
                pauseHooks.remove(source)
            }
        }
        Log.d(TAG, "playback $source=$isPlaying (playing=${snapshotPlaying()})")
    }

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
            Log.i(TAG, "idle watcher started (idle=${IDLE_RELEASE_MS / 60000}m, " +
                    "playing=${STILL_WATCHING_MS / 60000}m, prompt=${PROMPT_GRACE_MS / 1000}s)")
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
        promptShownAt = 0L
        _stillWatching.value = false
    }

    // ── The decision ──────────────────────────────────────────────────────────

    private suspend fun tick() {
        // Every one of these is a reason this class has no opinion, not a reason to
        // release. Releasing on "I could not tell" is how an idle timer locks out a
        // paying user who was sitting right there.
        if (!foreground) return
        if (!SessionManager.get().isAuthenticated) return
        if (DeviceEventStream.isIntentionallyExited) return
        if (releasedForIdle) return

        val idleMs  = SystemClock.elapsedRealtime() - lastInputAt
        val playing = snapshotPlaying()

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

        // Something is playing. Either put the prompt up, or act on one that has
        // been up long enough.
        if (promptShownAt == 0L) {
            if (idleMs >= STILL_WATCHING_MS) {
                promptShownAt = SystemClock.elapsedRealtime()
                _stillWatching.value = true
                Log.i(TAG, "no input for ${idleMs / 60000}m during playback — asking if anyone is there")
            }
            return
        }

        if (SystemClock.elapsedRealtime() - promptShownAt >= PROMPT_GRACE_MS) {
            Log.i(TAG, "still-watching prompt timed out — pausing and releasing slot")
            // Pause FIRST. Releasing the slot while frames keep flowing would leave
            // a stream running against a slot the server has already handed to
            // somebody else, which is the one outcome worse than holding it.
            val hooks = synchronized(playingSources) { pauseHooks.values.toList() }
            hooks.forEach { hook -> runCatching { hook() }.onFailure {
                Log.w(TAG, "pause hook failed: ${it.message}")
            } }
            promptShownAt = 0L
            _stillWatching.value = false
            release()
        }
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
