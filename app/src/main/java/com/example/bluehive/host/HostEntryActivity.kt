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
        hostConn = HostConnection(applicationContext)

        Log.i(TAG, "Launched by host. action=${intent?.action}")

        // The primary update path is now HOST-orchestrated: OGD checks the
        // manifest and updates BlueHive (if needed) BEFORE firing this launch
        // intent, setting EXTRA_SKIP_UPDATE_CHECK=true — see BluehiveUpdater
        // and BlueHiveLaunch.kt on the OGD side. That path is gap-free because
        // OGD's own process never dies while installing BlueHive.
        //
        // This in-app check remains as the FALLBACK for any host that doesn't
        // orchestrate updates (the flag defaults to false/absent), and for
        // direct launches (adb, debugging). It still has the known compile-gap
        // blind spot documented in SelfUpdateActivity, which is exactly why
        // OGD-orchestration is preferred whenever the host supports it.
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
                    toastAndFinish("Set up BlueHive in Off-Grid Drive first.")
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
            toastAndFinish("Set up BlueHive in Off-Grid Drive first.")
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
        hostConn.unbind()
    }
}

private fun io.bluehive.host.IBlueHiveHost.contractVersionOrZero(): Int =
    try { hostContractVersion } catch (e: Exception) { 0 }

private fun io.bluehive.host.IBlueHiveHost.identityStateSafe(): Int =
    try { identityState } catch (e: Exception) { BlueHiveHostContract.IDENTITY_STATE_NOT_PAIRED }