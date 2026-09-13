package com.example.bluehive

import android.util.Log
import com.example.bluehive.api.ApiClient
import com.example.bluehive.auth.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "I am still running." — the one liveness signal the backend can actually trust.
 *
 * WHY THIS EXISTS
 * ---------------
 * The server used to decide a device was alive from its own SSE keepalive: it
 * wrote `: keepalive` into the socket every 30s and stamped stream_seen_at each
 * time. That measures whether the SERVER'S generator is running, not whether
 * anyone is listening — and the difference is invisible at the network layer.
 * When Android freezes a cached process, the app runs no code at all, but the
 * kernel keeps ACKing, so those writes keep succeeding forever. Measured on a
 * real device: an ESTABLISHED socket with 2,173 bytes of keepalives sitting
 * unread in the receive buffer while the process was frozen in cpuset/background.
 *
 * The device therefore stayed is_device_active = true indefinitely and held one
 * of the four household slots with nobody watching.
 *
 * A POST from here is the only signal in the system that REQUIRES THE CLIENT TO
 * BE EXECUTING. A frozen process cannot send one; a dead one cannot either. That
 * is the whole design.
 *
 * FOREGROUND ONLY, AND THAT IS DELIBERATE
 * ---------------------------------------
 * Stopping on background is not a limitation to work around — it is the signal.
 * Backgrounding means the slot should start counting down, and /background says
 * so explicitly. If that call is lost (network blip, process frozen before it
 * lands) the absent heartbeats reach the same verdict on their own, which is
 * exactly the backstop that did not previously exist.
 *
 * Playback is foreground, so a film keeps beating for its whole runtime with no
 * special case.
 *
 * DELIBERATELY NOT TIED TO THE SSE STREAM. That stream reconnects, backs off and
 * survives in states this must not vouch for. Keeping the two independent means a
 * stream that is limping cannot make the app look alive, and an app that is alive
 * is not reported dead because its stream happens to be re-dialling.
 */
object SessionHeartbeat {

    private const val TAG = "SessionHeartbeat"

    /**
     * Fallback cadence, used only until the first response lands.
     *
     * The SERVER owns this number — every reply carries interval_seconds and we
     * adopt it. Retuning BH_HEARTBEAT_INTERVAL_S on the backend moves every
     * install at its next beat, with no app release. That matters more than usual
     * here: the update channel has lagged behind shipped builds before, so any
     * timing that can only be changed by shipping an APK is a timing that cannot
     * really be changed.
     */
    private const val DEFAULT_INTERVAL_MS = 120_000L

    /**
     * Floor on the server-supplied interval.
     *
     * A misconfigured or hostile value of 0 would spin this loop as fast as the
     * network allows, from every TV at once. Clamping costs nothing and removes a
     * way to turn one bad env var into a self-inflicted DDoS.
     */
    private const val MIN_INTERVAL_MS = 30_000L
    private const val MAX_INTERVAL_MS = 15 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var job: Job? = null

    @Volatile private var intervalMs: Long = DEFAULT_INTERVAL_MS

    /**
     * Begin beating. Idempotent — a second call while already running is ignored
     * rather than starting a second loop, because ProcessLifecycleOwner.onStart
     * can fire more than once for what a user experiences as one return to the
     * app.
     */
    fun start() {
        job?.let {
            if (it.isActive) {
                Log.d(TAG, "already beating — ignoring start()")
                return
            }
        }
        job = scope.launch {
            Log.d(TAG, "heartbeat started (interval=${intervalMs}ms)")
            while (isActive) {
                beatOnce()
                delay(intervalMs)
            }
        }
    }

    /**
     * Stop beating. The absence is the message: from here on the server's clock
     * runs, and 15 minutes of silence releases the slot.
     */
    fun stop() {
        job?.cancel()
        job = null
        Log.d(TAG, "heartbeat stopped — server-side countdown now owns the slot")
    }

    /**
     * One beat. EVERY FAILURE IS SWALLOWED.
     *
     * A failed heartbeat is not an error worth surfacing or retrying eagerly: it
     * is indistinguishable from the offline case, and the correct response to
     * both is to say nothing and try again at the next tick. Retrying hard would
     * turn a backend blip into a stampede from every TV simultaneously.
     *
     * Missing a beat is safe by design — the window is 15 minutes against a
     * 2-minute cadence, so roughly seven have to be lost in a row before the
     * server draws any conclusion.
     */
    private suspend fun beatOnce() {
        // Checked per beat rather than once at start(). On a cold start
        // ProcessLifecycleOwner.onStart fires while the user is still on the sign-in
        // screen, so a one-time guard here would refuse to start and nothing would
        // start it again until the next background/foreground round trip — leaving
        // a freshly signed-in viewer silently un-monitored for their whole first
        // session. Skipping individual beats costs one boolean every two minutes
        // and needs no second call site to remember.
        if (!SessionManager.get().isAuthenticated) return

        try {
            val resp = ApiClient.bluehiveApi.heartbeat()
            if (resp.isSuccessful) {
                resp.body()?.intervalSeconds
                    ?.let { it * 1000L }
                    ?.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
                    ?.let { serverInterval ->
                        if (serverInterval != intervalMs) {
                            Log.d(TAG, "interval ${intervalMs}ms -> ${serverInterval}ms (server)")
                            intervalMs = serverInterval
                        }
                    }
            } else {
                Log.d(TAG, "heartbeat HTTP ${resp.code()}")
            }
        } catch (e: Exception) {
            Log.d(TAG, "heartbeat failed (next tick will retry): ${e.message}")
        }
    }
}
