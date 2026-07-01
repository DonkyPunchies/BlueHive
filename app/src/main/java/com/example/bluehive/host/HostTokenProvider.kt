package com.example.bluehive.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import io.bluehive.host.BlueHiveHostContract
import io.bluehive.host.IBlueHiveHost
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "HostTokenProvider"
private const val HOST_PACKAGE = "com.offgriddrive.tv"
private const val BIND_TIMEOUT_SECONDS = 5L

// ─────────────────────────────────────────────────────────────────────────────
// HostToken — file-level sealed class, referenced by FQN from ApiClient and
// DeviceEventStream. Must NOT be nested inside HostTokenProvider; nesting it
// gives it a different FQN (HostTokenProvider.HostToken) which causes type
// mismatches in every `when` block that uses com.example.bluehive.host.HostToken.
//
// Distinguishes a DEFINITIVE "host has no identity" (revoke/unpair → drop the
// session) from a TRANSIENT failure (host not bound yet, IPC threw → keep the
// session and retry). Conflating the two was what bounced users to the pairing
// screen on a momentary bind race.
// ─────────────────────────────────────────────────────────────────────────────
sealed class HostToken {
    /** Host IPC succeeded and returned a non-empty token. */
    data class Fresh(val token: String) : HostToken()
    /** Host IPC succeeded but returned an empty/null token — genuine identity loss (logout/unpair). */
    object Revoked : HostToken()
    /** Host IPC failed (not bound, timed out, threw) — transient, keep the session. */
    object Unavailable : HostToken()
}

/**
 * Process-wide bridge that lets ApiClient pull fresh access tokens from the host
 * over IPC — synchronously, because it's called from an OkHttp Authenticator.
 *
 * Why this exists: bluehive-api reads the device from the JWT and the platform
 * refresh endpoint is fingerprint-bound to the HOST's device. BlueHive cannot
 * self-refresh (it would 403 on device mismatch). So on a 401, BlueHive must ask
 * the host — which owns the correct device + refresh token — for a new token.
 *
 * Keeps a cached binding; rebinds if the host process was killed.
 */
object HostTokenProvider {

    @Volatile private var appContext: Context? = null
    @Volatile private var host: IBlueHiveHost? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        Log.d(TAG, "Initialised")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            host = IBlueHiveHost.Stub.asInterface(binder)
            Log.i(TAG, "Connected to host service: $name")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Host service disconnected: $name")
            host = null
        }
    }

    /** Ensures we're bound. Blocks up to BIND_TIMEOUT_SECONDS for the connection. */
    private fun ensureBound(): IBlueHiveHost? {
        host?.let { return it }
        val ctx = appContext ?: run {
            Log.e(TAG, "Not initialised — call HostTokenProvider.init() in Application.onCreate()")
            return null
        }
        Log.d(TAG, "Binding to host service…")
        val latch = CountDownLatch(1)
        val oneShot = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                host = IBlueHiveHost.Stub.asInterface(binder)
                Log.i(TAG, "Bound to host (one-shot): $name")
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { host = null }
        }
        val intent = Intent(BlueHiveHostContract.ACTION_BIND_HOST).apply {
            setPackage(HOST_PACKAGE)
        }
        val ok = try {
            ctx.bindService(intent, oneShot, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.e(TAG, "bindService SecurityException: ${e.message}"); false
        }
        if (!ok) {
            Log.e(TAG, "bindService returned false — host not bindable")
            return null
        }
        if (!latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.e(TAG, "Timed out waiting for host binding")
            return null
        }
        return host
    }

    /**
     * Pulls a fresh access token from the host.
     *
     * Returns:
     *   Fresh(token) — host IPC succeeded, use this token
     *   Revoked      — host is reachable but has no identity; drop the session
     *   Unavailable  — couldn't reach host (transient); keep the session, retry later
     */
    fun fetchFreshToken(): HostToken {
        val h = ensureBound() ?: run {
            Log.w(TAG, "Host not bound — TRANSIENT (keeping session)")
            return HostToken.Unavailable
        }
        return try {
            val token = h.accessToken
            if (token.isNullOrEmpty()) {
                // IPC succeeded but host returned nothing → genuine identity loss.
                Log.w(TAG, "Host reachable but returned empty token — REVOKED (identity gone)")
                HostToken.Revoked
            } else {
                Log.i(TAG, "Host supplied a fresh access token")
                HostToken.Fresh(token)
            }
        } catch (e: Exception) {
            // IPC threw — host process may have died mid-call. Transient, not a logout.
            Log.e(TAG, "fetchFreshToken IPC error: ${e.javaClass.simpleName}: ${e.message} — TRANSIENT", e)
            host = null   // force rebind next call
            HostToken.Unavailable
        }
    }
}