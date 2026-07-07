package com.example.bluehive.sidebarComponents

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.bluehive.host.HostEntryActivity
import kotlin.system.exitProcess

/**
 * Phoenix relauncher for the sidebar Reboot button.
 *
 * Runs in its OWN process (android:process=":phoenix" in the manifest), so it
 * keeps executing while SidebarReboot hard-kills the MAIN process. From here it
 * relaunches BlueHive through the real front door — HostEntryActivity — which
 * re-binds the host for a fresh token AND runs the self-update check (no
 * EXTRA_SKIP_UPDATE_CHECK), so a freshly published build installs right on
 * reboot. That makes Reboot double as the dev test loop:
 * publish → press Reboot → watch the update screen install the new build.
 *
 * The immediate exitProcess after handing off is the proven ProcessPhoenix
 * pattern: startActivity() has already committed the launch to the system by
 * the time it returns, so killing this helper process cannot cancel it — and
 * it MUST die, or the next reboot would find a stale :phoenix process.
 */
class RebootActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, HostEntryActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
        exitProcess(0)
    }
}
