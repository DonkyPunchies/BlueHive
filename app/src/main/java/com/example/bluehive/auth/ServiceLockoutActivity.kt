package com.example.bluehive.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.ProfileScreenActivity
import com.example.bluehive.R
import com.example.bluehive.utilities.ModularButton
import com.example.bluehive.utilities.ModularButtonAnimationConfig
import com.example.bluehive.utilities.ModularButtonColors
import com.example.bluehive.utilities.ModularButtonDimensions
import com.example.bluehive.utilities.ModularButtonGlowConfig
import com.example.bluehive.utilities.ModularButtonTextConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown when the host reports this account as inactive.
 *
 * DISTINCT FROM LockoutActivity, deliberately. That one is the concurrent-device
 * cap: a temporary, self-resolving condition with its own countdown and its own
 * "re-enter" semantics. This is an account-level standing that only changes when
 * somebody pays, so sharing a screen would mean one class carrying two unrelated
 * state machines.
 *
 * THE SESSION IS NOT CLEARED. The user stays signed in throughout — closing the
 * app and forgetting who they are would mean re-authenticating after every
 * billing hiccup, and the standing is expected to flip back. Only the app is
 * closed; the identity survives.
 *
 * THE COPY IS DELIBERATELY VAGUE. It says the service is having trouble rather
 * than naming a subscription. That is a product decision, not an oversight.
 *
 * BACK EXITS. There is nothing beneath this screen — it is always launched with
 * CLEAR_TASK — so swallowing back would trap the user in an app they cannot use
 * with no way out but Home.
 */
class ServiceLockoutActivity : ComponentActivity() {

    companion object {
        @Volatile
        var isOnTop: Boolean = false
            private set

        /**
         * Raise the lock screen over whatever is running.
         *
         * NEW_TASK because callers are background coroutines with no activity on
         * the stack; CLEAR_TASK because the lock has to interrupt rather than
         * queue behind whatever the user was doing.
         */
        fun launch(context: android.content.Context) {
            if (isOnTop) return          // already up; do not rebuild it underneath them
            runCatching {
                context.applicationContext.startActivity(
                    Intent(context.applicationContext, ServiceLockoutActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                DeviceEventStream.stopAndMarkExited()
                finishAffinity()
            }
        })

        setContent {
            ServiceLockoutScreen(
                onRestored = {
                    // Standing came back. Straight to the profile picker rather
                    // than AuthActivity: the session was never cleared, so there
                    // is nothing to sign in to.
                    DeviceEventStream.startAfterUserReturn()
                    startActivity(
                        Intent(this, ProfileScreenActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                },
            )
        }
    }

    override fun onResume() { super.onResume(); isOnTop = true }
    override fun onPause()  { super.onPause();  isOnTop = false }
}


/**
 * How often this screen re-asks while it is open.
 *
 * Short because the thing being waited on is a host that may take a minute or
 * two to re-mint after a backend outage, and the user has nothing to do but
 * stare at the screen meanwhile. Long enough that it is one cheap local provider
 * read per tick, not a network poll.
 */
private const val RECHECK_INTERVAL_MS = 20_000L

@Composable
private fun ServiceLockoutScreen(onRestored: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    var checking by remember { mutableStateOf(false) }
    var stillDown by remember { mutableStateOf(false) }

    // The notice clears itself so a user pressing retry repeatedly gets fresh
    // feedback each time rather than a message that was already on screen.
    LaunchedEffect(stillDown) {
        if (stillDown) { delay(5_000); stillDown = false }
    }

    // Only ever fires on the transition INTO locked, so there is nothing to do:
    // we are already here. Kept because the registration is what stops a second
    // lock event stacking another screen.
    //
    // THIS IS NOT AN AUTOMATIC WAY OUT, and it never was. An earlier comment here
    // claimed the screen leaves by itself once standing is restored; it cannot,
    // because HostEntitlementRelay posts `if (locked)` and there has never been an
    // unlock signal to listen for. The polling effect below is the real one.
    DisposableEffect(Unit) {
        val listener: () -> Unit = { /* locked again: already here, nothing to do */ }
        EntitlementLockBus.register(listener)
        onDispose { EntitlementLockBus.unregister(listener) }
    }

    // ASK AGAIN, ON OUR OWN, FOR AS LONG AS THIS SCREEN IS UP.
    //
    // Until this existed the button was the only way off this screen — and the
    // button only succeeds once the HOST has re-minted its assertion, which lags
    // the backend coming back by however long the host takes to notice. A user
    // who pressed during that window was told the service was still down and
    // reasonably concluded the app was broken, then had no reason to press again
    // at the moment it would have worked.
    //
    // recheckNow reads the host's provider, which also WAKES the host if Android
    // reaped it — so this poll is not merely watching, it is prodding the very
    // component that has to act. Twenty seconds is frequent enough that recovery
    // feels immediate and far too slow to matter as load: it is one content
    // provider read against an app on the same device.
    LaunchedEffect(Unit) {
        while (true) {
            delay(RECHECK_INTERVAL_MS)
            // Don't race a press that is already in flight; it is the same call.
            if (checking) continue
            val standing = withContext(Dispatchers.IO) {
                HostEntitlementRelay.recheckNow(context)
            }
            // Same rule as the button: anything short of a definite "inactive"
            // lets them back in, INCLUDING a null. A poll that cannot reach the
            // host must never be the reason someone stays locked out.
            if (standing != HostEntitlementRelay.STANDING_INACTIVE) {
                onRestored()
                return@LaunchedEffect
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0D)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.widthIn(max = 560.dp).padding(horizontal = 40.dp),
        ) {
            Text(
                text = "Connection trouble",
                color = Color(0xFFF2F3F5),
                fontSize = 26.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
            Box(Modifier.height(14.dp))
            Text(
                text = "BlueHive is currently having trouble connecting. " +
                        "This is usually temporary — try again in a moment.",
                color = Color(0xFF9AA0A8),
                fontSize = 14.sp,
                lineHeight = 22.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )

            Box(Modifier.height(34.dp))

            RetryButton(
                label = if (checking) "Checking…" else "Retry connection",
                enabled = !checking,
                focusRequester = focus,
                onClick = {
                    checking = true
                    stillDown = false
                    scope.launch {
                        // Asks the HOST, via its provider, and re-reports. Reading
                        // the provider is also what wakes the host if Android
                        // reaped it, so this genuinely re-checks rather than
                        // replaying what we already had.
                        val standing = withContext(Dispatchers.IO) {
                            HostEntitlementRelay.recheckNow(context)
                        }
                        checking = false
                        // Anything that is not a definite "inactive" lets them
                        // back in, INCLUDING a null. A failed re-check must not
                        // strand someone whose subscription is fine behind a
                        // screen they cannot dismiss.
                        if (standing == HostEntitlementRelay.STANDING_INACTIVE) stillDown = true
                        else onRestored()
                    }
                },
            )

            // BELOW the button, and in a fixed-height slot.
            //
            // Fixed height because the notice is transient — it clears itself
            // after five seconds — and a slot that collapses would slide the
            // button down the screen the moment it vanished, under a user whose
            // finger is already heading for it.
            //
            // fillMaxWidth() rather than relying on the Column's alignment: an
            // unconstrained Text is only as wide as its own content, so it gets
            // centred as a BLOCK. The glow's negative offsetX shifts the button
            // slightly left of that block, and the two then visibly disagree.
            // Spanning the full width and centring the text inside it makes the
            // notice line up with the title and body instead.
            Box(
                modifier = Modifier.fillMaxWidth().height(40.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (stillDown) {
                    Text(
                        text = "Still unable to connect.",
                        color = Color(0xFFD98A3A),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    )
                }
            }
        }
    }
}


// ── Retry button geometry ────────────────────────────────────────────────────
// Wider than the app's other ModularButtons because "Retry connection" is a
// longer label than "Refresh" or "Continue".
private val BTN_W = 190.dp

// GLOW IS DERIVED FROM THE BUTTON, using the ratios of the tuned instance on the
// movie details screen: a 146x24 button under a 168x44 glow, i.e. ~1.15x its
// width and ~1.83x its height.
//
// The first version of this screen copied the device-cap lockout screen's
// absolute numbers (170.2 x 40.5) onto a button 40dp wider. Those numbers were
// tuned for a 150dp button, so on a 190dp one the glow was proportionally too
// small in BOTH axes and read as a thin outline hugging the edge rather than a
// glow behind it. Scaling instead of copying is what keeps it right at any width.
private val GLOW_W = BTN_W * 1.15f
private val GLOW_H = 44.dp

/** BlueHive's standard button, matched to the one on the device-cap lockout screen. */
@Composable
private fun RetryButton(
    label: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(text = label),
        isToggleable = false,
        fontFamily = FontFamily.Monospace,
        focusRequester = focusRequester,
        modifier = Modifier
            .focusable()
            .focusRequester(focusRequester),
        dimensions = ModularButtonDimensions(
            mainWidth = BTN_W,
            mainHeight = 24.dp,
            mainYOffset = 0.5.dp,
            secondWidth = BTN_W,
            secondHeight = 22.dp,
            secondYOffset = 7.dp,
            shadowHeight = 6.dp,
            glowWidth = GLOW_W,
            glowHeight = GLOW_H,
            mainCornerRadius = 7f,
            secondCornerRadius = 8f,
        ),
        colors = ModularButtonColors(
            mainDefault = Color(0xFF6C6C6C),
            mainToggled = Color(0xFF535C67),
            secondDefault = Color(0xFF2E2C37),
            secondFocused = Color(0xFF52505A),
            shadowColor = Color(0x50000000),
            textFocused = Color.White,
            textUnfocused = Color(0xFFBEBEBE),
        ),
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            // COMPUTED, never a hand-tuned literal. Every other ModularButton in
            // the app centres its glow this way — MoviesDetailsScreenCompose and
            // HomeScreenCompose both write the same expression. Hardcoding the
            // number means the glow silently drifts off-centre the moment anyone
            // changes the button width, which is exactly how this one ended up
            // looking wrong.
            offsetX = (BTN_W - GLOW_W) / 2,
            offsetY = (-4).dp,
        ),
        animationConfig = ModularButtonAnimationConfig(
            pressOffset = 2.5.dp,
            textOffsetDefault = 1.dp,
            textOffsetPressed = 2.4.dp,
            durationMillis = 110,
            bounceBackDelayMillis = 80,
        ),
        onClick = {
            if (enabled) {
                BlueHiveApplication.playClickSound()
                onClick()
            }
        },
    )
}
