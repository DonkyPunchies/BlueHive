package com.example.bluehive.update

import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.bluehive.BuildConfig
import com.example.bluehive.host.HostEntryActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val TAG = "SelfUpdate"

/**
 * Full-screen "Updating BlueHive" screen shown at cold start when an update is
 * available — BEFORE the user reaches the app. Downloads with a live progress
 * bar, verifies, and installs. No black screen, no mid-use interruption.
 */
class SelfUpdateActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VERSION_CODE = "extra_version_code"
        const val EXTRA_VERSION_NAME = "extra_version_name"
        const val EXTRA_APK_URL = "extra_apk_url"
        const val EXTRA_SHA256 = "extra_sha256"
    }

    private val progress = MutableStateFlow(0f)
    private val status = MutableStateFlow("Preparing update…")
    private val isInstalling = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manifest = UpdateManager.Manifest(
            latestVersionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0),
            latestVersionName = intent.getStringExtra(EXTRA_VERSION_NAME) ?: "?",
            apkUrl = intent.getStringExtra(EXTRA_APK_URL) ?: "",
            sha256 = intent.getStringExtra(EXTRA_SHA256) ?: "",
            minSupported = 0
        )

        // Ignore Back during the update so the user can't strand themselves.
        onBackPressedDispatcher.addCallback(this, object :
            androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* no-op */ }
        })

        // If install is cancelled or fails, let the user into the app anyway.
        UpdateInstallBus.register { s ->
            if (s != PackageInstaller.STATUS_SUCCESS) {
                Log.i(TAG, "Install not completed (status=$s) — continuing to app")
                isInstalling.value = false
                proceedToApp()
            }
        }

        setContent {
            val p by progress.collectAsState()
            val s by status.collectAsState()
            val installing by isInstalling.collectAsState()
            UpdateScreen(
                fromVersion = BuildConfig.VERSION_NAME,
                toVersion = manifest.latestVersionName,
                progress = p,
                status = s,
                isInstalling = installing
            )
        }

        lifecycleScope.launch {
            status.value = "Downloading update…"
            val apk = UpdateManager.downloadAndVerify(applicationContext, manifest) { f ->
                progress.value = f
            }
            if (apk == null) {
                Log.w(TAG, "Download/verify failed — continuing to app on current version")
                proceedToApp()
                return@launch
            }
            progress.value = 1f
            status.value = "Ready to install"
            UpdateManager.install(applicationContext, apk)

            // After commit, the user taps the system confirm dialog, then Android
            // installs + AOT-compiles the APK (dex2oat) — on a 2GB TV box this
            // can take a while for a ~200MB app. Hold an indeterminate "Installing"
            // state here so the user sees a purposeful screen the whole time,
            // instead of falling back to the host / a blank screen. The process is
            // killed by the OS when the replace completes; the host then
            // relaunches BlueHive. We flip to installing AFTER a brief beat so the
            // system confirm dialog is what the user acts on first.
            isInstalling.value = true
            status.value = "Installing update…"
            // Success -> process replaced -> the host relaunches. This coroutine never
            // returns normally; it's killed with the process.
            // Cancel/fail -> UpdateInstallBus -> proceedToApp() (resets state).
        }
    }

    /** Re-enter via HostEntryActivity but SKIP the update check to avoid a loop. */
    private fun proceedToApp() {
        startActivity(
            Intent(this, HostEntryActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(io.bluehive.host.BlueHiveHostContract.EXTRA_SKIP_UPDATE_CHECK, true)
            }
        )
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        UpdateInstallBus.unregister()
    }
}

@Composable
private fun UpdateScreen(
    fromVersion: String,
    toVersion: String,
    progress: Float,
    status: String,
    isInstalling: Boolean
) {
    val bg = Color(0xFF0E0E10)
    val accent = Color(0xFF378ADD)
    val accentSoft = Color(0xFF85B7EB)
    val track = Color(0x14FFFFFF)
    val textPrimary = Color(0xFFF5F5F2)
    val textMuted = Color(0xFF888780)
    val textFaint = Color(0xFF5F5E5A)

    Box(
        modifier = Modifier.fillMaxSize().background(bg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.width(440.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(52.dp).clip(CircleShape).background(Color(0x26378ADD)),
                contentAlignment = Alignment.Center
            ) {
                Text("↓", color = accent, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(24.dp))
            Text("UPDATE AVAILABLE", color = textMuted, fontSize = 11.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            Text("BlueHive", color = textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text("$fromVersion  →  $toVersion", color = accentSoft, fontSize = 14.sp)
            Spacer(Modifier.height(32.dp))

            if (isInstalling || progress < 0f) {
                // Indeterminate — during install we can't know dex2oat progress.
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                    color = accent, trackColor = track
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                    color = accent, trackColor = track
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(status, if (isInstalling || progress < 0f) "" else "${(progress * 100).toInt()}%", textMuted)

            Spacer(Modifier.height(36.dp))
            Text(
                if (isInstalling)
                    "This can take a moment on first install.\nBlueHive will restart automatically — please wait."
                else
                    "Preparing your update. The app will restart\nautomatically when it's ready.",
                color = textFaint, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun Row(left: String, right: String, color: Color) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(left, color = color, fontSize = 13.sp)
        Text(right, color = color, fontSize = 13.sp)
    }
}