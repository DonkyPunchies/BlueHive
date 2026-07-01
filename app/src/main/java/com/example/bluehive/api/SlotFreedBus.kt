package com.example.bluehive.api

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * SlotFreedBus
 *
 * Lightweight event bus posted by DeviceEventStream when the server pushes
 * a {"event": "slot_freed"} message down the SSE stream.
 *
 * This happens when another device in the same workspace is revoked, freeing
 * a slot.  The only subscriber right now is LockoutActivity, which uses it
 * to show a "A slot just opened" hint while the device is on the lockout
 * screen for REASON_WORKSPACE_FULL.
 *
 * Same Application-scope pattern as SessionExpiredBus / LockoutBus:
 *   - Register in onResume, unregister in onPause to avoid leaks.
 *   - post() always dispatches to the main thread.
 */
object SlotFreedBus {

    private val listeners = mutableListOf<() -> Unit>()

    fun register(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun unregister(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun post() {
        Log.d("SlotFreedBus", "Posting slot_freed to ${listeners.size} listener(s)")
        Handler(Looper.getMainLooper()).post {
            synchronized(listeners) { listeners.toList() }.forEach { it() }
        }
    }
}