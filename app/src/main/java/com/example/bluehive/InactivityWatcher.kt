package com.example.bluehive

import android.util.Log
import com.example.bluehive.api.ApiClient
import com.example.bluehive.auth.DeviceEventStream
import com.example.bluehive.auth.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * InactivityWatcher
 *
 * Owns the Home-button (background) and return (foreground) signals. Restored in
 * PHASE 3.1 and pointed at BLUEHIVE-API (not the platform identity backend that
 * PHASE 2 could no longer reach). The 15-minute grace lives entirely on the
 * server:
 *
 *   1. Home button → POST /api/android/background → bluehive-api sets
 *      bh:bg:{fingerprint} EX 900 (15 min, wall-clock, server-side).
 *   2. If the user does NOT return, the Redis TTL expires and the keyspace
 *      listener flips is_device_active = False + broadcasts slot_freed.
 *   3. If the user returns → POST /api/android/foreground deletes the key, and
 *      the SSE reconnect (startAfterUserReturn) re-activates through the cap.
 *
 * These are EVENT-driven pings (only on background/foreground), not polling —
 * they don't reintroduce any periodic server↔host chatter.
 *
 * Why the server owns the timer: Android's process freezer makes client-side
 * countdowns unreliable (they only tick when the process has CPU). Redis' wall
 * clock is immune to Android lifecycle. If the ping itself is lost (network
 * blip), the stale-session reaper is the backstop — the device still flips
 * inactive ~15 min after its last keepalive.
 */
object InactivityWatcher {

    private const val TAG = "InactivityWatcher"

    // Fire-and-forget scope for the lifecycle pings. Process-scoped (SupervisorJob
    // so one failed ping never cancels the other), IO dispatcher for the network.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Called from ProcessLifecycleOwner.onStop — app is fully backgrounded.
     * Fires POST /api/android/background to start the server-side 15-min grace.
     */
    fun onAppBackgrounded() {
        val authed = SessionManager.get().isAuthenticated
        val exited = DeviceEventStream.isIntentionallyExited

        Log.d(TAG, "📥 onAppBackgrounded  authed=$authed  intentionallyExited=$exited")

        if (!authed) {
            Log.d(TAG, "⏭  skip — not authenticated")
            return
        }
        if (exited) {
            // Back button already ran end-session (instant inactive). Sending a
            // /background now would set a pointless grace key on a device that is
            // already leaving. Skip.
            Log.d(TAG, "⏭  skip — back button already called end-session")
            return
        }

        // Home button → start the server-side 15-min grace timer.
        scope.launch {
            try {
                val resp = ApiClient.bluehiveApi.background()
                Log.d(TAG, "↪ background ping ok=${resp.isSuccessful} code=${resp.code()}")
            } catch (e: Exception) {
                // Best-effort — if this is lost, the reaper flips the device
                // inactive ~15 min after the last keepalive anyway.
                Log.w(TAG, "background ping failed (reaper is the backstop): ${e.message}")
            }
        }
    }

    /**
     * Called from ProcessLifecycleOwner.onStart — app is foregrounded.
     * Fires POST /api/android/foreground to cancel any pending grace timer.
     */
    fun onAppForegrounded() {
        val authed = SessionManager.get().isAuthenticated
        Log.d(TAG, "📤 onAppForegrounded  authed=$authed")

        if (!authed) {
            Log.d(TAG, "⏭  skip — not authenticated")
            return
        }

        // Return → cancel the grace timer. This matters for the case where the SSE
        // never dropped during a brief background (so there's no reconnect to clear
        // the key) — without it the grace would expire and wrongly free the slot.
        // Re-activation + cap check remain the SSE reconnect's job.
        scope.launch {
            try {
                val resp = ApiClient.bluehiveApi.foreground()
                Log.d(TAG, "↪ foreground ping ok=${resp.isSuccessful} code=${resp.code()}")
            } catch (e: Exception) {
                Log.w(TAG, "foreground ping failed: ${e.message}")
            }
        }
    }
}
