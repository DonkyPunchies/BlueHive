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

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        activity.startActivity(intent)

        activity.finishAffinity()
        exitProcess(0)
    }
}