package com.example.bluehive.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Receives PackageInstaller session status callbacks.
 *  - PENDING_USER_ACTION -> launch the system confirm dialog.
 *  - SUCCESS             -> log; the process is being replaced, relaunch is
 *                           handled by PackageReplacedReceiver.
 *  - anything else       -> post to UpdateInstallBus so SelfUpdateActivity can
 *                           stop waiting and let the user into the app.
 */
class SelfUpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                    Log.i("SelfUpdate", "Launched system install confirm dialog.")
                } else {
                    Log.w("SelfUpdate", "PENDING_USER_ACTION but no confirm intent.")
                    UpdateInstallBus.post(PackageInstaller.STATUS_FAILURE)
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Log.i("SelfUpdate", "Update installed successfully.")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("SelfUpdate", "Install status=$status msg=$msg")
                UpdateInstallBus.post(status)
            }
        }
    }
}