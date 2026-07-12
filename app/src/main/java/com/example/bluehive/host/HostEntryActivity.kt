// host/HostEntryActivity.kt
package com.example.bluehive.host

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.bluehive.BuildConfig
import com.example.bluehive.LoadingScreenActivity
import com.example.bluehive.auth.SessionManager
import com.example.bluehive.update.SelfUpdateActivity
import com.example.bluehive.update.UpdateManager
import io.bluehive.host.BlueHiveHostContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
private const val TAG = "HostEntryActivity"

/**
 * BlueHive's front door when launched by a host.
 *
 * Flow:
 *   0. Self-update check (unless EXTRA_SKIP_UPDATE_CHECK). If an update exists,
 *      route to SelfUpdateActivity and stop — the update screen owns the rest.
 *      Done BEFORE host bind so a fix ships even if the host handshake is broken.
 *   1. Bind to the host's IBlueHiveHost service.
 *   2. Readiness gate: getIdentityState().
 *   3. READY -> getAccessToken(); inject into SessionManager; launch the app.
 */
class HostEntryActivity : ComponentActivity() {

    private lateinit var hostConn: HostConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Resume-in-place guard ─────────────────────────────────────────────
        // If this HostEntry is NOT the task root, BlueHive is ALREADY RUNNING
        // beneath us — the user pressed Home, went back through the host, and hit
        // Launch again. Android stacked this fresh entry on top of the living
        // task; running the cold-start flow from here would CLEAR_TASK the whole
        // thing and dump the user back at the splash. Instead: step aside and
        // reveal the app exactly where they left it. Fresh tokens are pulled on
        // demand (401 → host), and updates install on the next cold entry or
        // sidebar Reboot.
        if (!isTaskRoot) {
            Log.i(TAG, "BlueHive already running — resuming in place (skipping cold-start flow)")
            finish()
            return
        }

        hostConn = HostConnection(applicationContext)

        // Resolve WHICH app is acting as our host (the launcher, else the sole
        // installed host) and remember it, so background token refreshes can
        // rebind without a launch intent. Companion apps trust the host that
        // launched them; nothing here names a specific host. Best-effort — if it
        // can't be resolved we still run the update check below, then the bind
        // step fails cleanly with a "couldn't reach the host" message.
        val resolvedHost = HostDiscovery.resolveFromLaunch(this)
        if (resolvedHost != null) {
            SessionManager.get().setHostPackage(resolvedHost)
            Log.i(TAG, "Host resolved: $resolvedHost")
        } else {
            Log.w(TAG, "No host could be resolved at launch")
        }

        Log.i(TAG, "Launched by host. action=${intent?.action}")

        // The primary update path is now HOST-orchestrated: the host checks the
        // manifest and updates BlueHive (if needed) BEFORE firing this launch
        // intent, setting EXTRA_SKIP_UPDATE_CHECK=true — see the host's launch +
        // updater code. That path is gap-free because the host's own process
        // never dies while installing BlueHive.
        //
        // This in-app check remains as the FALLBACK for any host that doesn't
        // orchestrate updates (the flag defaults to false/absent), and for
        // direct launches (adb, debugging). It still has the known compile-gap
        // blind spot documented in SelfUpdateActivity, which is exactly why
        // host-orchestration is preferred whenever the host supports it.
        val skipUpdate = intent?.getBooleanExtra(
            BlueHiveHostContract.EXTRA_SKIP_UPDATE_CHECK, false
        ) ?: false

        lifecycleScope.launch {
            // ── 0. Update-first gate ──────────────────────────────────────────
            if (!skipUpdate) {
                val update = try {
                    UpdateManager.checkForUpdate()
                } catch (e: Exception) {
                    Log.w(TAG, "Update check failed: ${e.message}")
                    null
                }
                if (update != null) {
                    Log.i(TAG, "Update ${BuildConfig.VERSION_CODE} -> ${update.latestVersionCode}; routing to update screen")
                    startActivity(Intent(this@HostEntryActivity, SelfUpdateActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(SelfUpdateActivity.EXTRA_VERSION_CODE, update.latestVersionCode)
                        putExtra(SelfUpdateActivity.EXTRA_VERSION_NAME, update.latestVersionName)
                        putExtra(SelfUpdateActivity.EXTRA_APK_URL, update.apkUrl)
                        putExtra(SelfUpdateActivity.EXTRA_SHA256, update.sha256)
                    })
                    finish()
                    return@launch
                }
            }

            // ── 1. Host handshake ─────────────────────────────────────────────
            val host = hostConn.bind()
            if (host == null) {
                toastAndFinish("Couldn't reach the host app.")
                return@launch
            }

            val state = try {
                val version = withContext(Dispatchers.IO) { host.contractVersionOrZero() }
                Log.i(TAG, "Host contract version = $version")
                withContext(Dispatchers.IO) { host.identityStateSafe() }
            } catch (e: Exception) {
                Log.e(TAG, "Host call failed: ${e.message}")
                toastAndFinish("Host communication error.")
                return@launch
            }

            when (state) {
                BlueHiveHostContract.IDENTITY_STATE_READY -> handleReady(host)
                BlueHiveHostContract.IDENTITY_STATE_HOST_BUSY ->
                    toastAndFinish("Host is still setting up — try again shortly.")
                else ->
                    toastAndFinish("Set up BlueHive in your host app first.")
            }
        }
    }

    private suspend fun handleReady(host: io.bluehive.host.IBlueHiveHost) {
        val token = try {
            withContext(Dispatchers.IO) { host.accessToken }
        } catch (e: Exception) {
            Log.e(TAG, "getAccessToken threw: ${e.message}")
            null
        }

        if (token.isNullOrEmpty()) {
            Log.w(TAG, "Host READY but token null/empty — treating as not paired")
            toastAndFinish("Set up BlueHive in your host app first.")
            return
        }

        SessionManager.get().setHostAccessToken(token)
        Log.i(TAG, "Host token injected — launching app")

        startActivity(Intent(this, LoadingScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    private fun toastAndFinish(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // hostConn is never initialized on the resume-in-place path (the
        // !isTaskRoot early-finish above) — guard the lateinit.
        if (::hostConn.isInitialized) hostConn.unbind()
    }
}

private fun io.bluehive.host.IBlueHiveHost.contractVersionOrZero(): Int =
    try { hostContractVersion } catch (e: Exception) { 0 }

private fun io.bluehive.host.IBlueHiveHost.identityStateSafe(): Int =
    try { identityState } catch (e: Exception) { BlueHiveHostContract.IDENTITY_STATE_NOT_PAIRED }
