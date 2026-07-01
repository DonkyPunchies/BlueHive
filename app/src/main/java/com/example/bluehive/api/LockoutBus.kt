package com.example.bluehive.api

import android.util.Log

/**
 * LockoutBus
 *
 * Lightweight event bus for "this TV has been locked out of the workspace
 * because all 6 active device slots are in use."
 *
 * Posted from:
 *   - The 429 interceptor in ApiClient (any platform API call that gets 429)
 *   - HeartbeatManager when its heartbeat returns 429
 *
 * Subscribed by:
 *   - Whatever Activity is on top — to navigate the user to LockoutActivity.
 *
 * Same pattern as SessionExpiredBus. Listeners register/unregister on
 * onResume/onPause so we don't keep references to dead Activities.
 */
object LockoutBus {

    /**
     * Reason values from the server's 429 detail. Kept as string constants
     * so the bus, ApiClient, and LockoutActivity all agree on the wire format
     * without having to import an enum across module boundaries.
     */
    const val REASON_WORKSPACE_FULL   = "workspace_full"
    const val REASON_REVOKED_BY_ADMIN = "revoked_by_admin"   // hard — device unpaired
    const val REASON_SESSION_REVOKED  = "session_revoked"    // soft — device still paired

    private val listeners = mutableListOf<(String?) -> Unit>()

    fun register(listener: (String?) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun unregister(listener: (String?) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    /**
     * Post a lockout event. [reason] should be one of the REASON_* constants
     * above; null is acceptable when the caller couldn't parse a reason from
     * the response (the lockout screen falls back to generic copy).
     */
    fun post(reason: String?) {
        Log.w("LockoutBus", "Posting lockout (reason=$reason) to ${listeners.size} listener(s)")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            synchronized(listeners) { listeners.toList() }.forEach { it(reason) }
        }
    }
}