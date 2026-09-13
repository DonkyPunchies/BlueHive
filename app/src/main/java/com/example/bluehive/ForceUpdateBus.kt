package com.example.bluehive

import android.util.Log

/**
 * Announces that the operator has asked this device to re-check the update
 * channel now.
 *
 * Modelled on [com.example.bluehive.auth.EntitlementLockBus] — a plain listener
 * registry, because the consumer is an Application-level callback fired from the
 * SSE reader, which has no lifecycle to hang a collector off.
 *
 * WHAT THIS IS NOT. It is not "install version N". The event carries no version
 * and nothing here trusts it to name one: it only says "look at the manifest
 * again, sooner than you otherwise would". The manifest remains the single source
 * of truth for what the latest build is and whether it may be declined, so a
 * malformed or replayed event can at worst cause a redundant HTTP GET.
 *
 * That distinction matters because this arrives over a channel the device does
 * not control. An event that could itself demand a version would be a way to
 * push an arbitrary APK URL at every TV at once.
 */
object ForceUpdateBus {

    private const val TAG = "ForceUpdateBus"

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
        Log.i(TAG, "update re-check requested — notifying ${snapshot.size} listener(s)")
        snapshot.forEach { l ->
            runCatching { l() }.onFailure { Log.w(TAG, "listener threw: ${it.message}") }
        }
    }
}
