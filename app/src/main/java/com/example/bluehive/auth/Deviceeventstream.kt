package com.example.bluehive.auth

import android.util.Base64
import android.util.Log
import com.example.bluehive.api.LockoutBus
import com.example.bluehive.api.SessionExpiredBus
import com.example.bluehive.api.SlotFreedBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DeviceEventStream
 *
 * Replaces HeartbeatManager entirely.  Maintains a long-lived SSE connection
 * to GET /api/android/events on the platform backend.
 *
 * ── What it does ─────────────────────────────────────────────────────────────
 *
 *  • Opens an SSE stream on start().
 *  • Parses inbound events and dispatches them:
 *      "revoked"    → LockoutBus.post(REASON_REVOKED_BY_ADMIN) + stop()
 *      "slot_freed" → SlotFreedBus.post()
 *  • If the connection drops, reconnects with exponential back-off
 *    (2 s → 4 s → 8 s … capped at 60 s).
 *  • On EVERY (re)connect the server endpoint checks device.status before
 *    opening the stream — so a missed revocation event during a disconnect is
 *    surfaced the moment the connection re-establishes (server returns 403).
 *
 * ── Network fault tolerance ───────────────────────────────────────────────────
 *
 *  • 403 on connect  → device revoked.  Post REASON_REVOKED_BY_ADMIN.  Stop.
 *  • 429 on connect  → workspace full.  Post REASON_WORKSPACE_FULL.  Keep
 *                       reconnecting in background so slot_freed can arrive.
 *  • 401 on connect  → access token expired. ensureFreshToken() proactively
 *                       rotates via ApiClient.platformApi before every connect.
 *                       On a surprise 401, rotation is triggered again immediately
 *                       so the retry loop uses a fresh token, not the dead one.
 *  • Network error   → retry with backoff.  Never change UI state.
 *  • 5xx             → retry with backoff.  Never change UI state.
 *
 * ── Lifecycle ─────────────────────────────────────────────────────────────────
 *
 *  start()  — called in BlueHiveApplication.onCreate when user is authenticated,
 *             and by LockoutActivity.goToProfile on workspace-full re-entry.
 *  stop()   — called automatically via bus listeners on lockout / session expiry,
 *             and explicitly by SessionManager.clearSession on logout.
 */
object DeviceEventStream {

    private const val TAG = "DeviceEventStream"

    // Reconnection back-off
    private const val BACKOFF_INITIAL_MS = 2_000L
    private const val BACKOFF_MAX_MS     = 60_000L

    // Proactively refresh the token if it expires within this many seconds.
    // Must be greater than the SSE keepalive interval (30s) so a live stream
    // never loses its token mid-connection.
    private const val TOKEN_REFRESH_BUFFER_S = 120L

    // Platform base URL — must match ApiClient
    private const val PLATFORM_BASE = com.example.bluehive.BuildConfig.PLATFORM_BASE_URL

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    // Set to true when the user explicitly exits via the back button on the
    // home screen. Prevents any subsequent start() call — from re-routing,
    // onResume, or a second process — from re-occupying the slot.
    // Only resets when the process is fully killed and restarted from scratch.
    @Volatile private var intentionallyExited = false

    // Read-only so BlueHiveApplication can check before starting
    // the inactivity countdown — if the user already intentionally
    // exited via back button, the countdown is irrelevant.
    val isIntentionallyExited: Boolean get() = intentionallyExited

    // Dedicated HTTP client for the SSE stream:
    //   - No authenticator: we handle reconnection + 401 ourselves via ensureFreshToken().
    //   - readTimeout = 0: stream is infinite; OkHttp must never time out reads.
    //   - retryOnConnectionFailure = false: our coroutine loop handles retries.
    private val sseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // readTimeout MUST be greater than the server's keepalive interval (30s)
        // but small enough to detect dead TCP within a reasonable window. With
        // 90s, a black-holed connection (network change, server crash, NAT timeout)
        // surfaces as a SocketTimeoutException within 90s instead of blocking
        // the coroutine forever — which would leave the job "active" and cause
        // start() to silently no-op on subsequent calls, stranding is_device_active.
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        // Disable connection pooling for the SSE client. SSE streams are
        // long-lived and unique per session; reusing a pooled connection from
        // a previous stream causes "unexpected end of stream" errors when the
        // server has already torn down its side of the socket. Each SSE connect
        // gets a fresh TCP.
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .build()

    // Bus listeners registered once for the process lifetime.
    private var listenersRegistered = false

    private fun ensureBusListeners() {
        if (listenersRegistered) return
        LockoutBus.register { reason ->
            // Only stop on hard/session revoke. For workspace_full, keep the
            // stream alive so slot_freed events can arrive and update
            // LockoutActivity without the user needing to press Refresh.
            if (reason != LockoutBus.REASON_WORKSPACE_FULL) {
                stop()
            }
        }
        SessionExpiredBus.register { stop() }
        listenersRegistered = true
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    // Lock object used to serialize start() so two concurrent callers can't both
// see `job?.isActive == false` and both create new jobs.
    private val startLock = Any()

fun start() {
    synchronized(startLock) {
        if (intentionallyExited) {
            Log.d(TAG, "Ignoring start() — session was intentionally ended")
            return
        }
        if (job?.isActive == true) {
            Log.d(TAG, "Already streaming — ignoring start() (job=${job.hashCode()})")
            return
        }
        ensureBusListeners()
        val newJob = scope.launch {
            var backoffMs = BACKOFF_INITIAL_MS

            while (isActive) {
                val result = connectAndStream()

                when (result) {
                    ConnectResult.REVOKED -> {
                        // LockoutBus already posted inside connectAndStream.
                        // Stop the loop — no point reconnecting a revoked device.
                        Log.w(TAG, "Device revoked — stopping stream")
                        stop()
                        return@launch
                    }
                    ConnectResult.WORKSPACE_FULL -> {
                        // Stay in the loop so we can receive slot_freed.
                        // Use the back-off so we don't hammer the server.
                        Log.w(TAG, "Workspace full — will retry in ${backoffMs}ms")
                        delay(backoffMs)
                        backoffMs = minOf(backoffMs * 2, BACKOFF_MAX_MS)
                    }
                    ConnectResult.STREAM_ENDED_CLEANLY -> {
                        // Server closed the stream. Reconnect immediately once.
                        Log.d(TAG, "SSE stream ended cleanly — reconnecting")
                        backoffMs = BACKOFF_INITIAL_MS
                    }
                    ConnectResult.ERROR -> {
                        Log.w(TAG, "SSE error — retrying in ${backoffMs}ms")
                        delay(backoffMs)
                        backoffMs = minOf(backoffMs * 2, BACKOFF_MAX_MS)
                    }
                }
            }


        }
        Log.d(TAG, "Started SSE event stream (job=${newJob.hashCode()})")
        job = newJob
    }
}
    /**
     * Called from every user-initiated entry point into the app:
     *   - ProcessLifecycleOwner.onStart  (resume from background)
     *   - LoginScreenActivity            (post-login)
     *   - LockoutActivity                (post-recovery re-entry)
     *
     * Clears the intentionallyExited flag before delegating to start(). This is
     * necessary because finishAffinity() does NOT kill the process — Android
     * routinely keeps it alive for fast relaunch, which means the static
     * intentionallyExited flag persists across what the user perceives as a
     * fresh app launch. Without this reset, start() silently no-ops on the
     * second-and-subsequent launches in the same process lifetime, and the
     * server-side is_device_active flag never flips back to true.
     */
    fun startAfterUserReturn() {
        if (intentionallyExited) {
            Log.d(TAG, "User returning — clearing exited flag")
            intentionallyExited = false
        }
        start()
    }



    fun stop() {
        val current = job
        if (current == null) {
            Log.d(TAG, "Stop called but no job exists — nothing to do")
            return
        }
        if (!current.isActive) {
            Log.d(TAG, "Stop called but job already inactive — clearing reference")
            job = null
            return
        }
        Log.d(TAG, "Stopping SSE event stream")
        current.cancel()
        job = null
    }

    /**
     * Called on intentional app exit (back button from HomeScreenCompose).
     * Sets a permanent flag that blocks start() for the rest of this process
     * lifetime. Resets only when the process is killed and relaunched fresh
     * from the launcher — which is exactly when we WANT the stream to restart.
     */
    fun stopAndMarkExited() {
        intentionallyExited = true
        stop()
        Log.d(TAG, "Stream stopped — intentional exit, start() now blocked")
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private enum class ConnectResult {
        REVOKED,
        WORKSPACE_FULL,
        STREAM_ENDED_CLEANLY,
        ERROR,
    }

    /**
     * Ensures the access token is fresh before opening the SSE connection.
     *
     * sseClient has no TokenAuthenticator — it cannot rotate tokens itself.
     * This function decodes the JWT expiry claim locally (no network cost) and,
     * if the token expires within TOKEN_REFRESH_BUFFER_S seconds, fires a
     * lightweight call through ApiClient.platformApi (which HAS TokenAuthenticator)
     * to trigger silent rotation before the SSE connect attempt.
     *
     * Returns the freshest available access token, or null if none exists.
     *
     * Called before every SSE connect attempt and immediately after a 401.
     */
    private suspend fun ensureFreshToken(): String? = withContext(Dispatchers.IO) {
        val session = SessionManager.get()
        val token   = session.accessToken ?: return@withContext null

        // Decode the JWT payload (middle segment) to read the exp claim.
        // JWT uses base64url encoding — Android's Base64.URL_SAFE handles this.
        // We add = padding so the decoder doesn't reject short segments.
        try {
            val parts = token.split(".")
            if (parts.size == 3) {
                val padded       = parts[1].let { it + "=".repeat((4 - it.length % 4) % 4) }
                val payloadBytes = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
                val payloadJson  = JSONObject(String(payloadBytes))
                val exp          = payloadJson.optLong("exp", 0L)
                val nowSeconds   = System.currentTimeMillis() / 1000L

                if (exp - nowSeconds < TOKEN_REFRESH_BUFFER_S) {
                    Log.d(TAG, "Access token expires in ${exp - nowSeconds}s — asking host for fresh token")
                    // PHASE 2: BlueHive holds no refresh token, so it CANNOT self-
                    // rotate. The host owns the only refresh token + the correct
                    // device fingerprint, so we pull a fresh access token over IPC.
                    when (val result = com.example.bluehive.host.HostTokenProvider.fetchFreshToken()) {
                        is com.example.bluehive.host.HostToken.Fresh -> {
                            SessionManager.get().setHostAccessToken(result.token)
                            Log.d(TAG, "Host supplied a fresh token for SSE — using it")
                        }
                        com.example.bluehive.host.HostToken.Revoked -> {
                            // Host reachable and explicitly has no identity → genuine
                            // logout/unpair. Drop the session so the app routes to setup.
                            Log.w(TAG, "Host reports revoked — identity gone, stopping stream")
                            SessionManager.get().clearSession()
                            SessionExpiredBus.post()
                            return@withContext null
                        }
                        com.example.bluehive.host.HostToken.Unavailable -> {
                            // Transient (host not ready / IPC failed). Do NOT clear the
                            // session. Fall through and use whatever token we still have;
                            // the SSE connect will surface a real 401 if it's truly dead,
                            // and the next loop iteration retries.
                            Log.w(TAG, "Host unavailable (transient) — keeping session, using existing token")
                        }
                        else -> {
                            // Unknown HostToken result — treat as transient. Keep the
                            // session and fall through to the existing token, same as
                            // Unavailable. Never clear the session on an unknown result.
                            Log.w(TAG, "Unknown host result for SSE — keeping session, using existing token")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // JWT decode failed (malformed token). Log and continue — the SSE
            // connect will surface the 401 if the token truly is invalid.
            Log.w(TAG, "Could not decode JWT for expiry check: ${e.message}")
        }

        // Return the latest token. May have been rotated by the call above.
        return@withContext SessionManager.get().accessToken
    }

    /**
     * Opens one SSE connection, reads until it ends or errors, returns why.
     *
     * The server endpoint performs a device-status check on every connect.
     * This means reconnecting after a network fault IS the "check status on
     * reconnect" mechanism — the server returns 403 if the device was revoked
     * while the connection was down, surfacing a missed event automatically.
     */
    private suspend fun connectAndStream(): ConnectResult = withContext(Dispatchers.IO) {
        val session     = SessionManager.get()
        val accessToken = ensureFreshToken() ?: return@withContext ConnectResult.ERROR
        val fingerprint = session.deviceFingerprint

        val request = Request.Builder()
            .url("$PLATFORM_BASE/api/android/events")
            .header("Authorization",        "Bearer $accessToken")
            .header("X-Device-Fingerprint", fingerprint)
            .header("Accept",               "text/event-stream")
            .header("Cache-Control",        "no-cache")
            // Deliberately NO X-Refresh-Token — the SSE endpoint uses JWT-only auth.
            .build()

        val response = try {
            sseClient.newCall(request).execute()
        } catch (e: IOException) {
            Log.w(TAG, "SSE connect network error: ${e.message}")
            return@withContext ConnectResult.ERROR
        }

        response.use { resp ->
            when (resp.code) {
                403 -> {
                    val reason = try {
                        val body = resp.body.string()
                        JSONObject(body).optJSONObject("detail")
                            ?.optString("reason")
                            ?.takeIf { it.isNotBlank() }
                            ?: LockoutBus.REASON_REVOKED_BY_ADMIN
                    } catch (_: Exception) {
                        LockoutBus.REASON_REVOKED_BY_ADMIN
                    }
                    Log.w(TAG, "SSE connect 403 — reason=$reason")
                    LockoutBus.post(reason)
                    return@withContext ConnectResult.REVOKED
                }
                429 -> {
                    Log.w(TAG, "SSE connect 429 — workspace full")
                    LockoutBus.post(LockoutBus.REASON_WORKSPACE_FULL)
                    return@withContext ConnectResult.WORKSPACE_FULL
                }
                401 -> {
                    // Token expired despite the proactive check — ask the host for
                    // a fresh one over IPC. Next loop iteration reconnects with it.
                    Log.w(TAG, "SSE connect 401 — asking host for fresh token")
                    when (val result = com.example.bluehive.host.HostTokenProvider.fetchFreshToken()) {
                        is com.example.bluehive.host.HostToken.Fresh -> {
                            SessionManager.get().setHostAccessToken(result.token)
                            Log.d(TAG, "Host supplied a fresh token after 401 — will retry connect")
                        }
                        com.example.bluehive.host.HostToken.Revoked -> {
                            Log.w(TAG, "Host reports revoked after 401 — identity gone, dropping session")
                            SessionManager.get().clearSession()
                            SessionExpiredBus.post()
                        }
                        com.example.bluehive.host.HostToken.Unavailable -> {
                            // Transient — do NOT clear the session. Just retry the
                            // connect on the next loop iteration with backoff.
                            Log.w(TAG, "Host unavailable (transient) after 401 — keeping session, will retry connect")
                        }
                        else -> {
                            // Unknown HostToken result — treat as transient. Keep the
                            // session and let the loop retry the connect with backoff.
                            Log.w(TAG, "Unknown host result after 401 — keeping session, will retry connect")
                        }
                    }
                    return@withContext ConnectResult.ERROR
                }
            }

            if (!resp.isSuccessful) {
                Log.w(TAG, "SSE connect ${resp.code} — will retry")
                return@withContext ConnectResult.ERROR
            }

            Log.d(TAG, "SSE stream open (fp=…${fingerprint.takeLast(8)})")

            // ── Read the stream line-by-line ───────────────────────────────────
            val source = resp.body.source()

            val dataBuffer = StringBuilder()

            try {
                while (coroutineContext.isActive) {
                    val line = source.readUtf8Line()
                        ?: return@withContext ConnectResult.STREAM_ENDED_CLEANLY

                    when {
                        // Data line — accumulate (SSE allows multi-line data)
                        line.startsWith("data: ") -> {
                            dataBuffer.append(line.removePrefix("data: "))
                        }
                        // Blank line = end of event block → dispatch
                        line.isEmpty() && dataBuffer.isNotEmpty() -> {
                            dispatchEvent(dataBuffer.toString())
                            dataBuffer.clear()
                        }
                        // Comment / keepalive — silently ignored
                        line.startsWith(":") -> Unit
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "SSE read error: ${e.message}")
                return@withContext ConnectResult.ERROR
            }
        }

        ConnectResult.STREAM_ENDED_CLEANLY
    }

    /** Parse a complete SSE data payload and dispatch to the right bus. */
    private fun dispatchEvent(raw: String) {
        try {
            val obj   = JSONObject(raw)
            val event = obj.optString("event")
            Log.d(TAG, "SSE event: $event")

            when (event) {
                // Soft revoke — admin kicked the session but the device row
                // still exists. User waits out the 3-min lockout countdown
                // then taps Re-enter, which calls /check-slot and either
                // succeeds (back into the app) or 404s (device since removed,
                // bounced to pairing). The lockout UI is the right surface.
                "revoked" -> {
                    LockoutBus.post(LockoutBus.REASON_REVOKED_BY_ADMIN)
                }


                "unpaired" -> {
                    Log.w(TAG, "Device was unpaired by admin — routing to pairing screen")
                    SessionManager.get().clearSession()
                    SessionExpiredBus.post()
                }

                "slot_freed" -> {
                    SlotFreedBus.post()
                }

                else -> Log.d(TAG, "Unknown SSE event type: $event")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SSE event: $raw — ${e.message}")
        }
    }
}