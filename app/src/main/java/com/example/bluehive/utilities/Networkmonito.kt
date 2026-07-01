package com.example.bluehive.utilities

// ─────────────────────────────────────────────────────────────────────────────
// NetworkMonitor.kt
//
// Independent connectivity check for the home screen's offline / retry state.
// This exists because every repository swallows its exceptions and returns an
// empty list — so the UI can't tell "the catalog is empty" from "the network
// died." A direct connectivity check is the reliable signal.
//
// TESTING — FORCE_OFFLINE
//   Set FORCE_OFFLINE = true to make isOnline() always report offline, without
//   touching real connectivity. This is the easiest, most repeatable way to
//   test the retry banner. MUST be false in production builds.
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

object NetworkMonitor {

    private const val TAG = "NetworkMonitor"

    /**
     * DEBUG TEST SWITCH. When true, isOnline() always returns false and the
     * online callback never fires, simulating a fully offline device. Flip it
     * to true (in code or via the debugger) to exercise the retry banner.
     * Leave false for production.
     */
    var FORCE_OFFLINE = false

    /** True if the device currently has an internet-capable active network. */
    fun isOnline(context: Context): Boolean {
        if (FORCE_OFFLINE) {
            Log.d(TAG, "🧪 FORCE_OFFLINE active — reporting offline")
            return false
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps    = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Registers a callback that fires when an internet-capable network becomes
     * available (e.g. Wi-Fi reconnects). Used for auto-recovery — the home
     * screen hides the banner and reloads content on its own. Returns the
     * callback so the caller can unregister it on dispose.
     */
    fun registerOnlineCallback(
        context:     Context,
        onAvailable: () -> Unit,
    ): ConnectivityManager.NetworkCallback? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (FORCE_OFFLINE) return   // honour the test switch
                Log.d(TAG, "🌐 Network available — signalling recovery")
                onAvailable()
            }
        }
        return try {
            cm.registerNetworkCallback(request, callback)
            callback
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
            null
        }
    }

    fun unregister(context: Context, callback: ConnectivityManager.NetworkCallback?) {
        callback ?: return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) { /* no-op */ }
    }
}