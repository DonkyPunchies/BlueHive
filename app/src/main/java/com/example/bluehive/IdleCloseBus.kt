package com.example.bluehive

import android.util.Log

/**
 * Announces that the app has been idle long enough to shut itself down.
 *
 * Modelled on [com.example.bluehive.auth.EntitlementLockBus] — a plain listener
 * registry rather than a Flow, because the consumer is an Application-level
 * callback fired from IdleWatcher's background ticker, which has no lifecycle to
 * hang a collector off.
 *
 * WHY A BUS RATHER THAN IdleWatcher CLOSING THINGS ITSELF. Closing needs an
 * Activity to call finishAffinity() on, and IdleWatcher is a process-wide object
 * with no Activity reference. BlueHiveApplication already tracks the foreground
 * Activity for exactly this class of teardown (SessionExpiredBus does the same
 * thing), so the news travels there and the existing close path runs.
 *
 * FIRES ONCE PER IDLE PERIOD. IdleWatcher latches after posting, so a ticker that
 * keeps running past the threshold cannot post every 15 seconds — the close is
 * asynchronous and the process may live for a moment afterwards.
 */
object IdleCloseBus {

    private const val TAG = "IdleCloseBus"

    private val listeners = mutableListOf<() -> Unit>()

    fun register(listener: () -> Unit) {
        synchronized(listeners) {
            if (listeners.none { it === listener }) listeners.add(listener)
        }
    }

    fun unregister(listener: () -> Unit) {
        synchronized(listeners) { listeners.removeAll { it === listener } }
    }

    fun post() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        Log.i(TAG, "idle close requested — notifying ${snapshot.size} listener(s)")
        snapshot.forEach { l ->
            // One dead listener must not stop the others being told.
            runCatching { l() }.onFailure { Log.w(TAG, "listener threw: ${it.message}") }
        }
    }
}
