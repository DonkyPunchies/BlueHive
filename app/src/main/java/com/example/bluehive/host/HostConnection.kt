// host/HostConnection.kt
package com.example.bluehive.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import io.bluehive.host.BlueHiveHostContract
import io.bluehive.host.IBlueHiveHost
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "HostConnection"

// The host package we bind to. For now this is Off-Grid Drive's id. Later, when
// BlueHive supports arbitrary hosts, the launching host's package can be read
// from the launch intent / referrer instead of hardcoding it.
private const val HOST_PACKAGE = "com.offgriddrive.tv"

/**
 * Wraps binding to the host's IBlueHiveHost service.
 *
 * Usage: call bind(), await the connected IBlueHiveHost, use it, then unbind()
 * in onDestroy. Binding is async — bind() suspends until the service connects
 * (or fails/times out at the caller's discretion).
 */
class HostConnection(private val context: Context) {

    @Volatile private var host: IBlueHiveHost? = null
    private var connection: ServiceConnection? = null

    /**
     * Binds to the host service and suspends until connected.
     * Returns the IBlueHiveHost proxy, or null if the bind couldn't be initiated
     * (host not installed / service not found).
     */
    suspend fun bind(): IBlueHiveHost? = suspendCancellableCoroutine { cont ->
        val intent = Intent(BlueHiveHostContract.ACTION_BIND_HOST).apply {
            setPackage(HOST_PACKAGE)
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val h = IBlueHiveHost.Stub.asInterface(binder)
                host = h
                Log.i(TAG, "Bound to host service: $name")
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