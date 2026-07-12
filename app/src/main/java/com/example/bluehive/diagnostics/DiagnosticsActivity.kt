// diagnostics/DiagnosticsActivity.kt
package com.example.bluehive.diagnostics

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.BuildConfig
import com.example.bluehive.auth.SessionManager
import kotlinx.coroutines.launch

/**
 * Diagnostics — reached from Settings. Shows what's needed to support a device
 * remotely (device identity + version, account, storage/memory) plus the
 * SEND LOGS button, which uploads a 'diagnostic' report (current logcat +
 * runtime snapshot) through the same pipeline crash reports use.
 *
 * DELIBERATELY NO server/connectivity rows here: BlueHive's UI stays fully
 * separated from Off-Grid infrastructure — the uploaded logcat already tells
 * support everything about connectivity without the UI advertising it.
 */
class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back is NOT intercepted — system default (finish → Settings) is right.
        setContent { DiagnosticsScreen() }
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

private val PageBg   = Color(0xFF0A0A0A)
private val PanelBg  = Color(0xFF141418)
private val Hairline = Color(0xFF26262C)
private val TextHi   = Color.White
private val TextLo   = Color(0xFF9A9AA3)
private val Accent   = Color(0xFF2644A6)
private val AccentHi = Color(0xFF3D63D9)
private val Ok       = Color(0xFF2ECC71)
private val Bad      = Color(0xFFE5484D)

@Composable
private fun DiagnosticsScreen() {
    val scope   = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // 'idle' | 'sending' | 'sent:<id>' | 'failed'
    var sendState by remember { mutableStateOf("idle") }

    val session = SessionManager.get()
    val loader  = BlueHiveApplication.coilImageLoader
    val runtime = Runtime.getRuntime()

    val diskUsedMB = remember { (loader.diskCache?.size ?: 0L) / (1024 * 1024) }
    val diskMaxMB  = remember { (loader.diskCache?.maxSize ?: 0L) / (1024 * 1024) }
    val heapUsedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val heapMaxMB  = runtime.maxMemory() / (1024 * 1024)

    val sendFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { sendFocus.requestFocus() }

    // TV scroll: a verticalScroll Column only follows FOCUS on a TV, and the
    // only focusable element here is Send Logs at the very top — so the remote
    // could never reach the content below it. Intercept D-pad Up/Down at the
    // ROOT (onPreviewKeyEvent fires before the focused button) and drive the
    // scroll state directly. DPAD_CENTER/Back are NOT intercepted, so the
    // button still clicks and Back still returns.
    val scrollState = rememberScrollState()
    val step = with(LocalDensity.current) { 180.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .background(PageBg)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> { scope.launch { scrollState.animateScrollBy(step) }; true }
                    Key.DirectionUp   -> { scope.launch { scrollState.animateScrollBy(-step) }; true }
                    else              -> false
                }
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 48.dp, vertical = 32.dp)
        ) {
            Text("Diagnostics", color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Device health & support info — press Send Logs to share this with support.",
                color = TextLo, fontSize = 13.sp,
            )
            Spacer(Modifier.height(20.dp))

            // ── Send Logs — the one action on the page ──────────────────────
            SendLogsButton(
                state = sendState,
                focusRequester = sendFocus,
                onClick = {
                    if (sendState != "sending") {
                        sendState = "sending"
                        scope.launch {
                            val result = CrashReporter.sendDiagnostic(
                                context,
                                extraMeta = mapOf(
                                    "source"        to "diagnostics_screen",
                                    "disk_cache_mb" to diskUsedMB,
                                ),
                            )
                            sendState = result.fold(
                                onSuccess = { id -> Log.i("Diagnostics", "logs sent, report id=$id"); "sent:$id" },
                                onFailure = { e -> Log.w("Diagnostics", "send failed: ${e.message}"); "failed" },
                            )
                        }
                    }
                },
            )
            Spacer(Modifier.height(20.dp))

            // ── Device ──────────────────────────────────────────────────────
            Section("Device") {
                InfoRow("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                RowDivider()
                InfoRow("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                RowDivider()
                InfoRow("Version", "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                RowDivider()
                InfoRow("Fingerprint", session.deviceFingerprint.take(20) + "…")
            }
            Spacer(Modifier.height(14.dp))

            // ── Storage & memory ────────────────────────────────────────────
            Section("Storage & Memory") {
                InfoRow("Image disk cache", "$diskUsedMB MB / $diskMaxMB MB")
                RowDivider()
                InfoRow("App heap", "$heapUsedMB MB / $heapMaxMB MB")
            }
            Spacer(Modifier.height(18.dp))

            Text("Press Back to return to Settings.", color = TextLo, fontSize = 12.sp)
        }
    }
}

// ── Building blocks ──────────────────────────────────────────────────────────

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title.uppercase(), color = TextLo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(PanelBg)
                .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                .padding(horizontal = 18.dp, vertical = 4.dp)
        ) { content() }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextLo, fontSize = 14.sp)
        Text(value, color = TextHi, fontSize = 14.sp)
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
}

@Composable
private fun SendLogsButton(state: String, focusRequester: FocusRequester, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val label = when {
        state == "sending"        -> "Sending…"
        state.startsWith("sent:") -> "Sent ✓  (report #${state.removePrefix("sent:")})"
        state == "failed"         -> "Failed — press to retry"
        else                      -> "Send Logs"
    }
    val bg = when {
        state.startsWith("sent:") -> Ok.copy(alpha = 0.25f)
        state == "failed"         -> Bad.copy(alpha = 0.25f)
        focused                   -> AccentHi
        else                      -> Accent
    }

    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Hairline,
                shape = RoundedCornerShape(8.dp),
            )
            .focusRequester(focusRequester)
            // clickable is ITSELF a focus target and handles DPAD_CENTER/ENTER;
            // stacking .focusable() on top created two focus nodes and made the
            // OK button need multiple presses. ONE node = one press.
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 12.dp)
    ) {
        Text(label, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
