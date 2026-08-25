package com.example.bluehive.auth

import android.util.Log

/**
 * Announces that the host has reported this account as inactive.
 *
 * Modelled on LockoutBus: a plain listener registry rather than a Flow, because
 * the consumer is an Application-level callback that has to work from a
 * background coroutine with no lifecycle to hang off.
 *
 * FIRES ON THE TRANSITION ONLY. HostEntitlementRelay posts here when the standing
 * CHANGES to inactive, never on every report — otherwise a user already sitting
 * on the lock screen would have the app torn down under them on a loop.
 *
 * Nothing here decides anything. The relay owns the fail-open rule (only a
 * definite "inactive" locks); this is only the wire that carries the news.
 */
object EntitlementLockBus {

    private const val TAG = "EntitlementLockBus"

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
        Log.w(TAG, "entitlement inactive — notifying ${snapshot.size} listener(s)")
        snapshot.forEach { l ->
            // One dead listener must not stop the others being told.
            runCatching { l() }.onFailure { Log.w(TAG, "listener threw: ${it.message}") }
        }
    }
}
