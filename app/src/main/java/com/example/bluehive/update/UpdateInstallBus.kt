package com.example.bluehive.update

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Carries terminal PackageInstaller status from SelfUpdateInstallReceiver back to
 * SelfUpdateActivity. Same lightweight pattern as SlotFreedBus/LockoutBus.
 *
 * Only matters for the NON-success paths (user cancelled, install failed): the
 * activity needs to know so it can stop showing the update screen and let the
 * user into the app on the current version. On success the process is killed
 * before this fires — relaunch is handled by PackageReplacedReceiver instead.
 */
object UpdateInstallBus {
    private var listener: ((Int) -> Unit)? = null

    fun register(l: (Int) -> Unit) { listener = l }
    fun unregister() { listener = null }

    fun post(status: Int) {
        Log.d("SelfUpdate", "UpdateInstallBus posting status=$status")
        Handler(Looper.getMainLooper()).post { listener?.invoke(status) }
    }
}