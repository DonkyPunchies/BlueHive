package com.example.bluehive.sidebarComponents

import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import android.view.ViewGroup
import com.example.bluehive.BlueHiveApplication.Companion.releaseSoundPool
import com.example.bluehive.utilities.ConfirmationOverlay
import kotlin.system.exitProcess
import android.content.Intent
import androidx.activity.compose.BackHandler

object SidebarReboot {

    fun performReboot(activity: Activity, showConfirmation: Boolean = false) {
        if (showConfirmation) {
            showRebootDialog(activity)
        } else {
            executeReboot(activity)
        }
    }

    private fun showRebootDialog(activity: Activity) {
        val dialogView = ComposeView(activity).apply {
            // AFTER
            setContent {
                var showDialog by remember { mutableStateOf(true) }

                if (showDialog) {
                    // BACK = same as Cancel. Consuming it here stops it bubbling to the home
                    // screen's back handling (that bubbling was the focus bug). Removing the
                    // view returns focus to the sidebar.
                    BackHandler {
                        showDialog = false
                        (parent as? ViewGroup)?.removeView(this@apply)
                    }

                    ConfirmationOverlay(
                        message     = "Reboot the application?",
                        confirmText = "Yes, Reboot",
                        cancelText  = "Cancel",
                        onConfirm   = {
                            showDialog = false
                            executeReboot(activity)
                        },
                        onCancel    = {
                            showDialog = false
                            (parent as? ViewGroup)?.removeView(this@apply)
                        }
                    )
                }
            }
        }

        activity.addContentView(
            dialogView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun executeReboot(activity: Activity) {
        releaseSoundPool()

        // Hand the relaunch to RebootActivity, which runs in its OWN process
        // (:phoenix) and therefore survives the kill below. It restarts BlueHive
        // through HostEntryActivity — fresh host token + the self-update check,
        // so a newly published build installs right on reboot.
        activity.startActivity(Intent(activity, RebootActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })

        // Hard-kill ONLY BlueHive's main process — a genuine cold restart:
        // fresh singletons, fresh Gecko, full splash + warm-up. The host's task is
        // untouched and stays right behind us, where Back expects it.
        //
        // (The old implementation launched ACTION_MAIN/CATEGORY_HOME — the
        // system LAUNCHER — then exitProcess'd with no relaunch. That's why
        // "reboot" used to dump the user on the TV home screen looking like
        // BlueHive AND the host had both crashed shut.)
        exitProcess(0)
    }
}