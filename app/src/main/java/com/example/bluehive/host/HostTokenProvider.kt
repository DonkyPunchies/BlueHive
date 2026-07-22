package com.example.bluehive.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.bluehive.api.SessionExpiredBus
import com.example.bluehive.auth.SessionManager
import io.companion.host.CompanionHostContract
import io.companion.host.ICompanionHost
import io.companion.host.ICompanionHostCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "HostTokenProvider"
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
    @Volatile private var host: ICompanionHost? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        Log.d(TAG, "Initialised")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            host = ICompanionHost.Stub.asInterface(binder)
            Log.i(TAG, "Connected to host service: $name")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Host service disconnected: $name")
            host = null
        }
    }

    // ── Instant-revocation push-path ──────────────────────────────────────────
    // The host (Off-Grid Drive) fires onIdentityRevoked() the MOMENT an admin
    // kicks/removes this device — far sooner than the pull-path notices it (which
    // only sees it on the next token fetch coming back empty → HostToken.Revoked).
    //
    // onIdentityRevoked() is a `oneway` AIDL method, so it arrives on a BINDER
    // thread. We marshal to the main thread before touching session state or the
    // UI bus, matching every other logout path in the app.
    private val mainHandler = Handler(Looper.getMainLooper())

    private val revocationCallback = object : ICompanionHostCallback.Stub() {
        override fun onIdentityRevoked() {
            Log.w(TAG, "Host pushed onIdentityRevoked — dropping session")
            mainHandler.post {
                // Exactly what DeviceEventStream's 'unpaired' branch does.
                SessionManager.get().clearSession()
                SessionExpiredBus.post()
            }
        }
    }

    /**
     * Register our revocation callback with the host. Safe to call on every bind:
     * the host keys callbacks by binder (RemoteCallbackList), so re-registering the
     * same object is a no-op. A host that returns false — or throws, or predates the
     * push-path — simply means no instant push; the MANDATORY pull-path
     * (fetchFreshToken → HostToken.Revoked) still covers logout either way.
     */
    fun registerRevocationCallback(h: ICompanionHost?) {
        if (h == null) return
        try {
            val ok = h.registerCallback(revocationCallback)
            Log.i(TAG, "Host revocation callback registered: $ok")
        } catch (e: Exception) {
            Log.w(TAG, "registerCallback failed — falling back to pull-path: ${e.message}")
        }
    }

    /** Ensures we're bound. Blocks up to BIND_TIMEOUT_SECONDS for the connection. */
    private fun ensureBound(): ICompanionHost? {
        host?.let { return it }
        val ctx = appContext ?: run {
            Log.e(TAG, "Not initialised — call HostTokenProvider.init() in Application.onCreate()")
            return null
        }
        Log.d(TAG, "Binding to host service…")
        val latch = CountDownLatch(1)
        val oneShot = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                host = ICompanionHost.Stub.asInterface(binder)
                Log.i(TAG, "Bound to host (one-shot): $name")
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { host = null }
        }
        val hostPackage = SessionManager.get().hostPackage ?: run {
            Log.e(TAG, "No host package resolved — cannot bind for token refresh")
            return null
        }
        val intent = Intent(CompanionHostContract.ACTION_BIND_HOST).apply {
            setPackage(hostPackage)
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
        // Push-path: ask the host to tell us the INSTANT identity is revoked,
        // instead of waiting to discover it on the next token pull.
        registerRevocationCallback(host)
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