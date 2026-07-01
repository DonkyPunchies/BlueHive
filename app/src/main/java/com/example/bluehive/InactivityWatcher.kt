package com.example.bluehive

import android.util.Log
import com.example.bluehive.auth.DeviceEventStream
import com.example.bluehive.auth.SessionManager

/**
 * InactivityWatcher
 *
 * Handles the home-button background event. The 10-minute grace timer
 * now lives entirely on the server:
 *
 *   1. Client backgrounds → POST /api/android/background
 *   2. Server sets Redis key bh:bg:{fingerprint} EX 600
 *   3. Redis TTL expires at exactly 10 minutes (wall-clock, server-side)
 *   4. Python keyspace listener fires → is_device_active = False → slot_freed
 *
 * The client no longer owns a local countdown. Android's process freezer
 * made client-side coroutine timers unreliable — they only ticked when the
 * process had CPU, which could be minutes late or never. The server's wall
 * clock is immune to Android process lifecycle.
 *
 * When the user returns:
 *   - DeviceEventStream.startAfterUserReturn() opens a new SSE
 *   - SSE connect block DELetes bh:bg:{fingerprint} and clears backgrounded_at
 *   - The keyspace listener never fires for this background event
 */
object InactivityWatcher {

    private const val TAG = "InactivityWatcher"

    /**
     * Called from ProcessLifecycleOwner.onStop — app is fully backgrounded.
     * Fires POST /api/android/background to start the server-side grace timer.
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
            Log.d(TAG, "⏭  skip — back button already called end-session")
            return
        }

        // PHASE 2: the platformApi.background() ping is REMOVED. It targeted the
        // platform identity backend (/api/android/background) which no longer
        // accepts BlueHive's host-injected token. The server-side grace timer is
        // already driven by the SSE lifecycle: when the app backgrounds and the
        // SSE connection drops (or its TCP times out ~90s), the server's SSE
        // finally block marks the device inactive. No HTTP ping needed.
        Log.d(TAG, "↪ background — relying on SSE disconnect for server-side grace timer")
    }

    /**
     * Called from ProcessLifecycleOwner.onStart — app is foregrounded.
     * The SSE reconnect handles all server-side state (DEL grace key,
     * clear backgrounded_at, set is_device_active = True). Nothing to do
     * client-side except log for observability.
     */
    fun onAppForegrounded() {
        val authed = SessionManager.get().isAuthenticated
        Log.d(TAG, "📤 onAppForegrounded  authed=$authed")

        if (!authed) {
            Log.d(TAG, "⏭  skip — not authenticated")
            return
        }

        // PHASE 2: the platformApi.foreground() ping is REMOVED (same reason as
        // background()). On foreground, DeviceEventStream.startAfterUserReturn()
        // opens a fresh SSE, and the server's SSE connect block clears the grace
        // key + sets is_device_active = true. That covers the reconnect case.
        //
        // KNOWN GAP (pre-existing, not host-specific): if the process is NOT
        // killed and the SSE stays alive through a brief background, there's no
        // explicit foreground signal — the grace key ticks until the next genuine
        // reconnect. This was the original justification for this ping. If that
        // gap matters in practice, the correct host-model fix is to expose a
        // foreground signal over the SSE/host path, NOT to call platformApi here.
        // Flagging rather than silently dropping the behavior.
        Log.d(TAG, "↪ foreground — SSE reconnect drives server-side active state")
    }
}