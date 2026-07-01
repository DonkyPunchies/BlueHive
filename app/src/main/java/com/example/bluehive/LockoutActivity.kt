package com.example.bluehive

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.LockoutBus
import com.example.bluehive.api.SlotFreedBus
import com.example.bluehive.auth.DeviceEventStream
import com.example.bluehive.auth.SessionManager
import com.example.bluehive.utilities.ModularButton
import com.example.bluehive.utilities.ModularButtonAnimationConfig
import com.example.bluehive.utilities.ModularButtonColors
import com.example.bluehive.utilities.ModularButtonDimensions
import com.example.bluehive.utilities.ModularButtonGlowConfig
import com.example.bluehive.utilities.ModularButtonTextConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────────────────────────────────────
// LockoutActivity
// ──────────────────────────────────────────────────────────────────────────────
// Shown for two distinct reasons:
//
//  REASON_REVOKED_BY_ADMIN
//    Pushed instantly via SSE when an admin revokes this device's access.
//    Covers both hard revoke (device unpaired) and session kick (soft revoke).
//    Shows a 3-minute countdown. After countdown the user taps Re-enter which
//    calls check-slot. Server response determines outcome:
//      200 → session was a soft kick — re-enter directly to ProfileScreenActivity
//      403 → device was hard-revoked — clear session → LoginScreenActivity to re-pair
//
//  REASON_WORKSPACE_FULL
//    All 6 concurrent streaming slots are occupied. No countdown — the user
//    just needs to wait for someone else to free a slot. The SSE stream keeps
//    running in the background; when a slot frees up the server pushes
//    slot_freed and a green hint appears. The Refresh button checks whether
//    a slot is now available.
//
// Back button (both scenarios):
//   moveTaskToBack(true) — minimizes app to TV home screen without touching
//   the session. SSE keeps running for workspace_full. For revoked, the user
//   can return later and tap Re-enter properly.
// ──────────────────────────────────────────────────────────────────────────────

class LockoutActivity : ComponentActivity() {

    companion object {
        const val EXTRA_REASON             = "lockout_reason"
        const val LOCKOUT_COOLDOWN_SECONDS = 3 * 60
        const val WORKSPACE_ACTIVE_LIMIT   = 6

        @Volatile var isOnTop: Boolean = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val initialReason = intent.getStringExtra(EXTRA_REASON)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // PHASE 2: nothing sits beneath the lockout screen and BlueHive
                // owns no pairing front door. Back closes BlueHive's task and
                // drops the user back to the host (OGD).
                DeviceEventStream.stopAndMarkExited()
                finishAffinity()
            }
        })

        setContent {
            LockoutScreen(
                initialReason = initialReason,
                onSlotFreed   = { goToProfile() },
                onReenter     = { returnToHost() },
            )
        }
    }

    override fun onResume()  { super.onResume();  isOnTop = true  }
    override fun onPause()   { super.onPause();   isOnTop = false }


    // workspace_full or session kick (200 from check-slot) — re-enter workspace
    private fun goToProfile() {
        DeviceEventStream.startAfterUserReturn()
        startActivity(
            Intent(this, ProfileScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    // Hard revoke — device is unpaired on the server. BlueHive can't re-pair
    // (the host owns identity), so clear the dead session and return to the
    // host, which owns the pairing front door.
    private fun returnToHost() {
        DeviceEventStream.stopAndMarkExited()
        SessionManager.get().clearSession()
        finishAffinity()
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Refresh state machine
// ──────────────────────────────────────────────────────────────────────────────

private sealed class RefreshState {
    object Idle        : RefreshState()
    object Checking    : RefreshState()
    object StillLocked : RefreshState()
    data class Failed(val message: String) : RefreshState()
}

// ──────────────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────────────

private fun formatCountdown(seconds: Int): String =
    "%02d:%02d".format(seconds / 60, seconds % 60)

// ──────────────────────────────────────────────────────────────────────────────
// LockoutScreen
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun LockoutScreen(
    initialReason: String?,
    onSlotFreed:   () -> Unit,
    onReenter:     () -> Unit,
) {
    val scope  = rememberCoroutineScope()
    var state  by remember { mutableStateOf<RefreshState>(RefreshState.Idle) }
    var reason by remember { mutableStateOf(initialReason) }

    val isRevoked       = reason == LockoutBus.REASON_REVOKED_BY_ADMIN
    val isWorkspaceFull = reason == LockoutBus.REASON_WORKSPACE_FULL

    // ── 3-minute countdown — revoked path only ────────────────────────────────
    var cooldownLeft by remember { mutableIntStateOf(LockoutActivity.LOCKOUT_COOLDOWN_SECONDS) }

    LaunchedEffect(reason) {
        if (!isRevoked) return@LaunchedEffect
        cooldownLeft = LockoutActivity.LOCKOUT_COOLDOWN_SECONDS
        while (cooldownLeft > 0) {
            delay(1_000L)
            cooldownLeft--
        }
    }

    // ── Slot freed hint — workspace_full path only ────────────────────────────
    var slotFreedHint by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener: () -> Unit = {
            // Always set the flag. The UI already gates display on isWorkspaceFull,
            // so this is harmless if reason is something else. Avoids capturing
            // isWorkspaceFull as a frozen snapshot inside a one-shot effect.
            slotFreedHint = true
            Log.d("LockoutScreen", "slot_freed received")
        }
        SlotFreedBus.register(listener)
        onDispose { SlotFreedBus.unregister(listener) }
    }

    val codeFont = FontFamily.Monospace

    val buttonFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { buttonFocusRequester.requestFocus() }

    // ── check-slot — shared by both revoked and workspace_full ────────────────
    // 200 → goToProfile (session cleared server-side, re-enter workspace)
    // 403 → device is hard-revoked on server → call onReenter → re-pair
    // 429 → workspace still full → show feedback
    fun onRefreshClick() {
        if (state is RefreshState.Checking) return
        state = RefreshState.Checking
        scope.launch {
            try {
                val resp = ApiClient.platformApi.checkSlot()
                when {
                    resp.isSuccessful -> {
                        onSlotFreed()
                        return@launch
                    }
                    resp.code() == 403 -> {
                        // Device is hard-revoked (device.status != "active").
                        // Soft-kicked devices always get 200 from check-slot
                        // since the server clears session_state on that call.
                        state = RefreshState.Idle
                        onReenter()
                        return@launch
                    }
                    resp.code() == 429 -> {
                        slotFreedHint = false
                        state = RefreshState.StillLocked
                    }
                    else -> {
                        Log.w("LockoutScreen", "check-slot unexpected ${resp.code()}")
                        state = RefreshState.Failed("Server returned ${resp.code()}. Try again.")
                    }
                }
            } catch (e: Exception) {
                Log.e("LockoutScreen", "check-slot exception: ${e.message}")
                state = RefreshState.Failed("Could not reach server. Check your connection.")
            }
        }
    }

    // ── Copy ──────────────────────────────────────────────────────────────────
    val headline: String
    val body:     String
    when (reason) {
        LockoutBus.REASON_REVOKED_BY_ADMIN -> {
            headline = "Your access was revoked."
            body     = "Your access has been revoked by the account holder.\n\n" +
                    "Wait for the countdown, then tap Re-enter.\n" +
                    "You will be taken back in or asked to re-pair\n" +
                    "depending on the type of revocation."
        }
        LockoutBus.REASON_WORKSPACE_FULL -> {
            headline = "All streaming slots are currently in use."
            body     = "You cannot enter the app right now because all\n" +
                    "${LockoutActivity.WORKSPACE_ACTIVE_LIMIT} streaming slots on this account are occupied.\n\n" +
                    "Slots are active instances of another person\n" +
                    "using the app at this moment. Please wait for\n" +
                    "a slot to free up, then tap Refresh to try again."
        }
        else -> {
            headline = "Session unavailable."
            body     = "Your device can't enter the workspace right now.\n" +
                    "Press Refresh to try again."
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C0C))
    ) {
        Column(
            modifier            = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Text(
                text          = headline,
                color         = Color.White,
                fontSize      = 22.sp,
                fontWeight    = FontWeight.Bold,
                fontFamily    = codeFont,
                letterSpacing = 1.sp,
                textAlign     = TextAlign.Center,
                modifier      = Modifier.padding(horizontal = 40.dp)
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text       = body,
                color      = Color.White.copy(alpha = 0.70f),
                fontSize   = 14.sp,
                fontFamily = codeFont,
                fontWeight = FontWeight.Light,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.padding(horizontal = 60.dp)
            )

            Spacer(Modifier.height(24.dp))

            // ── Countdown (revoked only, while still counting) ────────────────
            if (isRevoked && cooldownLeft > 0) {
                Text(
                    text = buildAnnotatedString {
                        append("You may re-enter in ")
                        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) {
                            append(formatCountdown(cooldownLeft))
                        }
                    },
                    color         = if (cooldownLeft <= 30) Color(0xFFBB1414)
                    else Color.White.copy(alpha = 0.65f),
                    fontSize      = 13.sp,
                    fontFamily    = codeFont,
                    fontWeight    = FontWeight.Light,
                    letterSpacing = 0.5.sp,
                    textAlign     = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            }

            // ── Countdown complete message (revoked only, after countdown) ────
            if (isRevoked && cooldownLeft == 0) {
                Text(
                    text          = "Countdown complete. You may now attempt to re-enter.",
                    color         = Color.White.copy(alpha = 0.85f),
                    fontSize      = 13.sp,
                    fontFamily    = codeFont,
                    fontWeight    = FontWeight.Light,
                    letterSpacing = 0.5.sp,
                    textAlign     = TextAlign.Center,
                    modifier      = Modifier.padding(horizontal = 60.dp)
                )
                Spacer(Modifier.height(16.dp))
            }

            // ── Slot freed hint (workspace_full only) ─────────────────────────
            if (isWorkspaceFull && slotFreedHint) {
                Text(
                    text          = "✓ A slot just opened — tap Refresh to re-enter",
                    color         = Color(0xFF4CAF50),
                    fontSize      = 13.sp,
                    fontFamily    = codeFont,
                    fontWeight    = FontWeight.Light,
                    letterSpacing = 0.5.sp,
                    textAlign     = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            } else if (!isRevoked || cooldownLeft == 0) {
                Spacer(Modifier.height(36.dp))
            }

            // ── Action button ─────────────────────────────────────────────────
            LockoutButton(
                fontFamily     = codeFont,
                focusRequester = buttonFocusRequester,
                state          = state,
                isRevoked      = isRevoked,
                cooldownLeft   = cooldownLeft,
                onClick        = { onRefreshClick() }
            )

            // ── Feedback text ─────────────────────────────────────────────────
            val feedback: String? = when (val s = state) {
                RefreshState.Idle        -> null
                RefreshState.Checking    -> "Checking…"
                RefreshState.StillLocked -> "All slots still in use. Try again shortly."
                is RefreshState.Failed   -> s.message
            }

            if (feedback != null) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text          = feedback,
                    color         = when (state) {
                        RefreshState.StillLocked -> Color(0xFFBB1414)
                        is RefreshState.Failed   -> Color(0xFFBB1414)
                        else                     -> Color.White.copy(alpha = 0.65f)
                    },
                    fontSize      = 13.sp,
                    fontFamily    = codeFont,
                    fontWeight    = FontWeight.Light,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Action button
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun LockoutButton(
    fontFamily:     FontFamily,
    focusRequester: FocusRequester,
    state:          RefreshState,
    isRevoked:      Boolean,
    cooldownLeft:   Int,
    onClick:        () -> Unit,
) {
    val isChecking   = state is RefreshState.Checking
    val countingDown = isRevoked && cooldownLeft > 0
    val isDisabled   = isChecking || countingDown

    val label = when {
        isChecking   -> "Checking…"
        isRevoked    -> "Re-enter"
        else         -> "Refresh"
    }

    ModularButton(
        textConfig     = ModularButtonTextConfig(text = label),
        isToggleable   = false,
        fontFamily     = fontFamily,
        focusRequester = focusRequester,
        modifier       = Modifier
            .focusProperties { canFocus = !isDisabled }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) BlueHiveApplication.playHoverSound()
                false
            },
        dimensions = ModularButtonDimensions(
            mainWidth          = 150.dp,
            mainHeight         = 23.dp,
            mainYOffset        = 0.5.dp,
            secondWidth        = 150.dp,
            secondHeight       = 20.dp,
            secondYOffset      = 7.dp,
            shadowHeight       = 6.dp,
            glowWidth          = 170.2.dp,
            glowHeight         = 40.5.dp,
            mainCornerRadius   = 7f,
            secondCornerRadius = 6f,
        ),
        colors = ModularButtonColors(
            mainDefault   = Color(0xFF6C6C6C),
            mainToggled   = Color(0xFF535C67),
            secondDefault = Color(0xFF2E2C37),
            secondFocused = Color(0xFF52505A),
            shadowColor   = Color(0x50000000),
            textFocused   = Color.White,
            textUnfocused = Color(0xFFBEBEBE)
        ),
        glowConfig = ModularButtonGlowConfig(
            enabled    = true,
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX    = (-10).dp,
            offsetY    = (-4).dp
        ),
        animationConfig = ModularButtonAnimationConfig(
            pressOffset           = 2.5.dp,
            textOffsetDefault     = 1.dp,
            textOffsetPressed     = 2.4.dp,
            durationMillis        = 110,
            bounceBackDelayMillis = 80
        ),
        onClick = {
            if (!isDisabled) {
                BlueHiveApplication.playClickSound()
                onClick()
            }
        }
    )
}