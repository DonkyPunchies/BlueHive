package com.example.bluehive

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.LockoutBus
import com.example.bluehive.api.SlotFreedBus
import com.example.bluehive.api.SlotHolder
import com.example.bluehive.api.SlotHolderText
import com.example.bluehive.api.SlotHolders
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
//    Every active-device slot on the subscription is occupied. The count is the
//    server's (BH_ACTIVE_DEVICE_LIMIT, sent as detail.limit, held in
//    LockoutBus.slotLimit) -- never a number baked into the app. No countdown — the user
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
        // WORKSPACE_ACTIVE_LIMIT = 6 lived here until 2026-10-22 while the server
        // enforced 4. The slot count now comes only from the server: see
        // LockoutBus.slotLimit.

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
                // drops the user back to the host.
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

    // ── Who holds the slots — workspace_full path only ────────────────────────
    // From the 429 that put us here, refreshed by every SSE retry (at most a
    // minute apart). Null when the server sent no list; the screen is then the
    // pre-v360 one.
    val holders by LockoutBus.holders.collectAsState()

    // A clock for the countdowns between refreshes.
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(holders) {
        while (holders != null) {
            nowMs = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }

    // ── Let in automatically — workspace_full path only ───────────────────────
    // DeviceEventStream keeps retrying its connect behind this screen, and a
    // connect that succeeds has ALREADY taken the slot: the server checks the
    // cap on connect. Before v360 the TV then sat here holding that slot until
    // someone pressed Refresh. Only a connect made after the screen opened
    // counts, so a stream that was already up can never bounce us out.
    val opensAtStart = remember { DeviceEventStream.opens.value }
    val opens by DeviceEventStream.opens.collectAsState()
    var entering by remember { mutableStateOf(false) }
    LaunchedEffect(opens, reason) {
        if (isWorkspaceFull && opens > opensAtStart && !entering) {
            entering = true
            Log.d("LockoutScreen", "stream connected — the slot is ours, entering")
            onSlotFreed()
        }
    }

    // Sans-serif, as in the approved design C mockup. This screen used to be
    // monospace, and v360 kept that: it did not look like the design.
    val uiFont = FontFamily.Default

    // Did we land here because THIS TV released its own slot after sitting idle?
    //
    // Without this the copy is actively misleading: the user walked away, we handed
    // their slot back, somebody else took it, and they return to be told that other
    // people are using the app — true, but it reads as though the app lost their
    // place for no reason. Naming the cause is the difference between a rule and a
    // fault.
    //
    // remember, so consuming the latch happens once per composition rather than on
    // every recomposition — the first read clears it.
    val afterIdleRelease = remember { IdleWatcher.consumeIdleReleaseFlag() }

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
                // PHASE 3: slot state moved to bluehive-api (bluehive.device_sessions).
                val resp = ApiClient.bluehiveApi.checkSlot()
                when {
                    resp.isSuccessful -> {
                        if (!entering) {
                            entering = true
                            onSlotFreed()
                        }
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
        // THE SERVER'S COUNT, OR NONE. "All 4 slots" when the server has said 4;
        // plain "All slots" before it has (an older backend, or a lockout reached
        // without a 429 body) -- never a number that might be wrong.
        // One slot reads "the only streaming slot", never "all 1 streaming slots"
        // (seen 2026-10-22 with BH_ACTIVE_DEVICE_LIMIT=1). Wording: SlotHolderText.
        LockoutBus.REASON_WORKSPACE_FULL -> {
            val limit = LockoutBus.slotLimit
            headline = if (afterIdleRelease) "Your session timed out."
                       else SlotHolderText.headline(limit)
            body     = SlotHolderText.body(limit, afterIdleRelease)
        }
        else -> {
            headline = "Session unavailable."
            body     = "Your device can't enter the workspace right now.\n" +
                    "Press Refresh to try again."
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sizes are design C's mockup px x 1.3; see the note above SlotBanner.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
    ) {
        Column(
            modifier            = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Text(
                text       = headline,
                color      = HeadlineText,
                fontSize   = 25.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = uiFont,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.padding(horizontal = 40.dp)
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text       = body,
                color      = BodyText,
                fontSize   = 16.sp,
                lineHeight = 24.sp,
                fontFamily = uiFont,
                textAlign  = TextAlign.Center,
                // A width, not hand-placed line breaks: the slot-full copy flows
                // around a number that may be 1, 4 or absent.
                modifier   = Modifier.widthIn(max = BODY_WIDTH)
            )

            // ── Who holds the slots (workspace_full, when the server listed them)
            val list = holders?.takeIf { isWorkspaceFull && it.holders.isNotEmpty() }
            if (list != null) {
                val elapsedMs = nowMs - list.receivedAtMs
                Spacer(Modifier.height(14.dp))
                SlotBanner(SlotHolderText.banner(list, elapsedMs), uiFont)
                Spacer(Modifier.height(10.dp))
                SlotHolderList(list, elapsedMs, uiFont)
                Spacer(Modifier.height(12.dp))
                Text(
                    text       = "You'll be let in automatically when a slot frees.",
                    color      = MutedText,
                    fontSize   = 16.sp,
                    fontFamily = uiFont,
                    textAlign  = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
            } else {
                Spacer(Modifier.height(24.dp))
            }

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
                    fontSize      = 16.sp,
                    fontFamily    = uiFont,
                    textAlign     = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            }

            // ── Countdown complete message (revoked only, after countdown) ────
            if (isRevoked && cooldownLeft == 0) {
                Text(
                    text          = "Countdown complete. You may now attempt to re-enter.",
                    color         = Color.White.copy(alpha = 0.85f),
                    fontSize      = 16.sp,
                    fontFamily    = uiFont,
                    textAlign     = TextAlign.Center,
                    modifier      = Modifier.padding(horizontal = 60.dp)
                )
                Spacer(Modifier.height(16.dp))
            }

            // ── Slot freed hint (workspace_full only) ─────────────────────────
            if (isWorkspaceFull && slotFreedHint) {
                Text(
                    text          = "✓ A slot just opened — tap Refresh to re-enter",
                    color         = StatusBlue,   // was green; the screen is blue now
                    fontSize      = 16.sp,
                    fontFamily    = uiFont,
                    textAlign     = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            } else if ((!isRevoked || cooldownLeft == 0) && list == null) {
                Spacer(Modifier.height(36.dp))
            }

            // ── Action button ─────────────────────────────────────────────────
            LockoutButton(
                fontFamily     = uiFont,
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
                Spacer(Modifier.height(14.dp))
                Text(
                    text          = feedback,
                    color         = when (state) {
                        RefreshState.StillLocked -> Color(0xFFBB1414)
                        is RefreshState.Failed   -> Color(0xFFBB1414)
                        else                     -> Color.White.copy(alpha = 0.65f)
                    },
                    fontSize      = 16.sp,
                    fontFamily    = uiFont,
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

    // The same button as before, at design C's size: every dimension x 1.3,
    // like the rest of the screen (it was 150 x 23 dp, small next to the list).
    ModularButton(
        textConfig     = ModularButtonTextConfig(text = label, fontSize = 15f),
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
            mainWidth          = 195.dp,
            mainHeight         = 30.dp,
            mainYOffset        = 0.65.dp,
            secondWidth        = 195.dp,
            secondHeight       = 26.dp,
            secondYOffset      = 9.dp,
            shadowHeight       = 8.dp,
            glowWidth          = 221.dp,
            glowHeight         = 52.5.dp,
            mainCornerRadius   = 9f,
            secondCornerRadius = 8f,
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
            offsetX    = (-13).dp,
            offsetY    = (-5.2).dp
        ),
        animationConfig = ModularButtonAnimationConfig(
            pressOffset           = 3.25.dp,
            textOffsetDefault     = 1.3.dp,
            textOffsetPressed     = 3.1.dp,
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

// ──────────────────────────────────────────────────────────────────────────────
// Who holds the slots (design C, 2026-10-22). Wording and order: SlotHolderText.
//
// DRAWN FROM THE APPROVED MOCKUP, at 1.3 dp per mockup px. The mockup is a
// 16:9 frame about 640 px wide and the TV is 960 dp, so 1.5 would be exact;
// 1.3 keeps four rows plus the Refresh feedback line inside 540 dp. Colours
// are the mockup's. v360 used the old screen's monospace at about two thirds
// of these sizes, and it did not look like the design.
// ──────────────────────────────────────────────────────────────────────────────

private val ScreenBg     = Color(0xFF0B0C10)
private val HeadlineText = Color(0xFFECECEF)
private val BodyText     = Color(0xFFA4A4AD)
private val BannerBlue   = Color(0xFF122EB9)
private val BannerEdge   = Color(0xFF5C78F2)
private val BannerText   = Color(0xFFF2F5FF)
private val BannerSoft   = Color(0xFFC9D4FF)
private val BarTrack     = Color(0xFF0A1C78)
private val StatusBlue   = Color(0xFF9DB0FF)
private val RowText      = Color(0xFFECECEF)
private val DimText      = Color(0xFF8B8B94)
private val MutedText    = Color(0xFFB4B2A9)
private val RowDivider   = Color(0xFF22252C)
private val EmptySeat    = Color(0xFF5F5E5A)

private val LIST_WIDTH = 624.dp   // the mockup's 480 px banner and list
private val BODY_WIDTH = 612.dp   // its 470 px paragraph

/** More TVs than this collapse into "+ N more", so the screen never overflows. */
private const val MAX_ROWS = 4

@Composable
private fun SlotBanner(banner: SlotHolderText.Banner, fontFamily: FontFamily) {
    val shape = RoundedCornerShape(13.dp)
    Column(
        modifier = Modifier
            .width(LIST_WIDTH)
            .background(BannerBlue, shape)
            .border(1.5.dp, BannerEdge, shape)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClockGlyph(color = BannerSoft, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text       = banner.title,
                color      = BannerText,
                fontSize   = 18.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text       = banner.detail,
            color      = BannerSoft,
            fontSize   = 14.sp,
            fontFamily = fontFamily,
        )
        banner.progress?.let { done ->
            Spacer(Modifier.height(9.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(BarTrack)
            ) {
                Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(StatusBlue))
            }
        }
    }
}

@Composable
private fun SlotHolderList(list: SlotHolders, elapsedMs: Long, fontFamily: FontFamily) {
    val rows  = SlotHolderText.sorted(list.holders)
    val shown = rows.take(MAX_ROWS)
    Column(Modifier.width(LIST_WIDTH)) {
        shown.forEachIndexed { i, h ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(RowDivider))
            Row(
                modifier          = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HolderAvatar(h)
                Spacer(Modifier.width(13.dp))
                Text(
                    text = buildAnnotatedString {
                        append(SlotHolderText.who(h))
                        withStyle(SpanStyle(color = DimText)) {
                            append(" · ${SlotHolderText.device(h.device)}")
                        }
                    },
                    color      = if (h.state == SlotHolder.State.CHOOSING) MutedText else RowText,
                    fontSize   = 16.sp,
                    fontFamily = fontFamily,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.weight(1f),
                )
                Spacer(Modifier.width(13.dp))
                Text(
                    text       = SlotHolderText.status(h, elapsedMs),
                    color      = when (h.state) {
                        SlotHolder.State.LEAVING, SlotHolder.State.WATCHING -> StatusBlue
                        else                                                -> MutedText
                    },
                    fontSize   = 14.sp,
                    fontFamily = fontFamily,
                    maxLines   = 1,
                )
            }
        }
        if (rows.size > shown.size) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(RowDivider))
            Text(
                text       = "+ ${rows.size - shown.size} more",
                color      = MutedText,
                fontSize   = 14.sp,
                fontFamily = fontFamily,
                modifier   = Modifier.padding(start = 42.dp, top = 5.dp),
            )
        }
    }
}

/** The profile's own avatar, or an empty dashed seat for the picker or an unknown profile. */
@Composable
private fun HolderAvatar(h: SlotHolder) {
    val box = Modifier.size(26.dp)
    if (h.state == SlotHolder.State.CHOOSING || (h.avatar == null && h.profileName == null)) {
        Canvas(box) {
            val stroke = 1.3.dp.toPx()
            drawCircle(
                color  = EmptySeat,
                radius = size.minDimension / 2 - stroke / 2,
                style  = Stroke(
                    width      = stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.5.dp.toPx())),
                ),
            )
        }
    } else {
        Image(
            painter            = painterResource(resolveAvatarDrawable(h.avatar)),
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = box.clip(CircleShape),
        )
    }
}

/** A small clock face, drawn: the app has no icon font. */
@Composable
private fun ClockGlyph(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val stroke = 2.dp.toPx()
        val r = this.size.minDimension / 2 - stroke / 2
        drawCircle(color = color, radius = r, style = Stroke(width = stroke))
        drawLine(color, center, Offset(center.x, center.y - r * 0.55f), stroke, StrokeCap.Round)
        drawLine(color, center, Offset(center.x + r * 0.45f, center.y), stroke, StrokeCap.Round)
    }
}