// host/HostConnection.kt
package com.example.bluehive.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.example.bluehive.auth.SessionManager
import io.companion.host.CompanionHostContract
import io.companion.host.ICompanionHost
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "HostConnection"

/**
 * Wraps binding to the host's ICompanionHost service.
 *
 * Usage: call bind(), await the connected ICompanionHost, use it, then unbind()
 * in onDestroy. Binding is async — bind() suspends until the service connects
 * (or fails/times out at the caller's discretion).
 */
class HostConnection(private val context: Context) {

    @Volatile private var host: ICompanionHost? = null
    private var connection: ServiceConnection? = null

    /**
     * Binds to the host service and suspends until connected.
     * Returns the ICompanionHost proxy, or null if the bind couldn't be initiated
     * (host not installed / service not found).
     */
    suspend fun bind(): ICompanionHost? = suspendCancellableCoroutine { cont ->
        val hostPackage = SessionManager.get().hostPackage
        if (hostPackage == null) {
            Log.e(TAG, "No host package resolved — cannot bind (launch BlueHive from a host app)")
            if (cont.isActive) cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val intent = Intent(CompanionHostContract.ACTION_BIND_HOST).apply {
            setPackage(hostPackage)
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val h = ICompanionHost.Stub.asInterface(binder)
                host = h
                Log.i(TAG, "Bound to host service: $name")
                // Push-path: this is the bind that actually happens at startup
                // (HostTokenProvider.ensureBound() is lazy — it only runs on a 401
                // token refresh). Register here so the host can push
                // onIdentityRevoked() the instant this device is removed. The host
                // keys callbacks by binder, so registering from both paths is safe.
                HostTokenProvider.registerRevocationCallback(h)
                if (cont.isActive) cont.resume(h)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "Host service disconnected: $name")
                host = null
            }
        }
        connection = conn

        val started = try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.e(TAG, "bindService SecurityException: ${e.message}")
            false
        }

        if (!started) {
            Log.e(TAG, "bindService returned false — host service not found/bindable")
            if (cont.isActive) cont.resume(null)
        }

        cont.invokeOnCancellation { unbind() }
    }

    fun unbind() {
        connection?.let {
            try { context.unbindService(it) } catch (e: Exception) {
                Log.w(TAG, "unbind error: ${e.message}")
            }
        }
        connection = null
        host = null
    }
}