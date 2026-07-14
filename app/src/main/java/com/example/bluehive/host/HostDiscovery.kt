// host/HostDiscovery.kt
package com.example.bluehive.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import io.companion.host.CompanionHostContract

private const val TAG = "HostDiscovery"

/**
 * Resolves WHICH installed app is acting as BlueHive's host, so nothing in the
 * app hardcodes a specific host package. BlueHive is a companion: it trusts the
 * app that launched it to supply credentials over the io.companion.host contract,
 * and names no host in particular.
 *
 * Resolution order (most authoritative first):
 *   1. The launching app (activity referrer) — the app that fired ACTION_LAUNCH —
 *      but only if it actually exposes the ICompanionHost bind service. This lets
 *      the right host win even when several are installed, and rejects a spoofed
 *      referrer that isn't really a host.
 *   2. Otherwise, the sole installed service answering ACTION_BIND_HOST. If more
 *      than one is installed we pick the first (a multi-host device is not an
 *      expected configuration; the referrer path above is what disambiguates it).
 *
 * Returns null when no host can be found — BlueHive then tells the user to open
 * it from a host app.
 *
 * NOTE: package visibility (Android 11+) for all of this comes from the
 * <queries> entry in AndroidManifest for action ACTION_BIND_HOST. Without it,
 * queryIntentServices()/bindService() silently see nothing.
 */
object HostDiscovery {

    /** Best-effort host package for a fresh launch. Prefers the launcher (referrer). */
    fun resolveFromLaunch(activity: Activity): String? {
        referrerPackage(activity)?.let { pkg ->
            if (exposesHostService(activity, pkg)) {
                Log.i(TAG, "Host resolved from referrer: $pkg")
                return pkg
            }
            Log.d(TAG, "Referrer $pkg is not a host — falling back to service query")
        }
        return resolveInstalledHost(activity)
    }

    /** The single installed app exposing the host bind service, or null if none. */
    fun resolveInstalledHost(context: Context): String? {
        val intent = Intent(CompanionHostContract.ACTION_BIND_HOST)
        val pkgs = context.packageManager
            .queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
        return when {
            pkgs.isEmpty() -> {
                Log.w(TAG, "No installed host exposes ${CompanionHostContract.ACTION_BIND_HOST}")
                null
            }
            pkgs.size == 1 -> {
                Log.i(TAG, "Host resolved by service query: ${pkgs.first()}")
                pkgs.first()
            }
            else -> {
                Log.i(TAG, "Multiple hosts $pkgs — using ${pkgs.first()}")
                pkgs.first()
            }
        }
    }

    /** The app that started this Activity, when it came in as an android-app:// referrer. */
    private fun referrerPackage(activity: Activity): String? {
        val ref = activity.referrer ?: return null
        return if (ref.scheme == "android-app") ref.host else null
    }

    /** True IFF [pkg] declares a service answering ACTION_BIND_HOST. */
    private fun exposesHostService(context: Context, pkg: String): Boolean {
        val intent = Intent(CompanionHostContract.ACTION_BIND_HOST).setPackage(pkg)
        return context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
    }
}
