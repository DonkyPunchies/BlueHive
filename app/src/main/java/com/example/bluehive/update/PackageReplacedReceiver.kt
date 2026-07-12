package com.example.bluehive.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires after BlueHive is replaced by a self-update.
 *
 * PRIMARY relaunch is handled by the HOST (the host's own PackageReplacedReceiver), which
 * has foreground context at this moment and can legally startActivity. BlueHive
 * cannot relaunch itself here — a broadcast receiver with no visible window is
 * blocked by Android's background-activity-launch rules (BAL_BLOCK, API 29+).
 *
 * This is left as a log-only marker. For a bare third-party host that does NOT
 * relaunch BlueHive, the user simply reopens BlueHive from that host — the update
 * is already installed and the next launch runs the new version.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i("SelfUpdate", "Package replaced. Host is expected to relaunch BlueHive.")
    }
}