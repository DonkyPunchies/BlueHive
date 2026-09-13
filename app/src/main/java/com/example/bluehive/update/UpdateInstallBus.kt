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

    /**
     * CARRIES THE REASON, not only the code.
     *
     * PackageInstaller puts a human-readable explanation in
     * EXTRA_STATUS_MESSAGE, and it used to die in a Log.w inside the receiver.
     * R8 strips w from release builds, so on the builds users actually run, the
     * one string saying WHY an update failed was discarded before anyone could
     * read it. An update that will not install and gives no reason is
     * unsupportable: nothing on the device, in logcat, or on screen said whether
     * the disk was full, the signature was wrong, or the user simply dismissed a
     * dialog -- and all three need a different response.
     */
    data class Result(val status: Int, val message: String?)

    private var listener: ((Result) -> Unit)? = null

    fun register(l: (Result) -> Unit) { listener = l }
    fun unregister() { listener = null }

    fun post(status: Int, message: String? = null) {
        // Log.e: this is a terminal failure of the one flow a user cannot skip,
        // and v/d/i/w do not survive R8 in release.
        Log.e("SelfUpdate", "UpdateInstallBus posting status=$status msg=$message")
        Handler(Looper.getMainLooper()).post { listener?.invoke(Result(status, message)) }
    }
}