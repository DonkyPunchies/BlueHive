package com.example.bluehive.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.example.bluehive.update.SelfUpdateActivity
import com.example.bluehive.update.UpdateGate
import com.example.bluehive.update.UpdateManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.LoadingScreenActivity
import com.example.bluehive.R
import com.example.bluehive.api.ApiClient
import com.example.bluehive.models.BhHostProvisionRequest
import com.example.bluehive.models.BhLoginRequest
import com.example.bluehive.models.BhSignupRequest
import com.example.bluehive.utilities.AppTypography
import com.example.bluehive.utilities.ModularButton
import com.example.bluehive.utilities.ModularButtonTextConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's front door, now that there is no host to open it.
 *
 * WHAT THIS REPLACES
 * ------------------
 * HostEntryActivity. Off-Grid Drive used to launch BlueHive over an intent and
 * inject a token it had obtained from platform-api, which meant BlueHive could
 * not start on its own — no launcher tile, no way in. This activity carries the
 * LEANBACK_LAUNCHER category instead, so BlueHive behaves like any other TV app.
 *
 * TWO PATHS, decided before anything is drawn:
 *   a stored session  -> straight through to LoadingScreenActivity, no UI at all
 *   no session        -> email + phone, create or sign in
 *
 * WHY THE SESSION CHECK HAPPENS IN onCreate AND NOT IN A COMPOSABLE
 * ----------------------------------------------------------------
 * A returning user must never see a flash of the sign-in form. Composing first
 * and redirecting afterwards guarantees exactly that flash, because the frame is
 * already on screen by the time the effect runs.
 *
 * NO PASSWORD, AND NO VERIFICATION YET
 * ------------------------------------
 * Deliberate and temporary. Mailgun is still on a sandbox domain that only
 * delivers to pre-authorised recipients, so a verification step would lock out
 * every real user. Today, knowing an email is enough to sign in — acceptable at
 * three accounts, not acceptable at three hundred. The one-time code slots in
 * between the button and the network call without changing anything else here.
 */
class AuthActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Host entitlement assertion ────────────────────────────────────────
        // A host app that launched us may have attached a signed statement of its
        // account's standing. Relay it and forget it.
        //
        // WE DO NOT READ IT. The token is opaque here — parsing or trusting it
        // client-side would be worthless, since anything the client can read it
        // can also fabricate. Only the backend holds the public key that makes it
        // mean anything.
        //
        // NOTHING IS GATED ON THIS. BlueHive is free; the assertion is recorded so
        // the operator can see usage behind a lapsed host subscription. It must
        // never become a lock, and it must never delay startup — hence
        // fire-and-forget, with no branch on the result.
        //
        // REPORTED ON EVERY LAUNCH, from cache when nothing fresh arrives.
        //
        // Only re-sending what a host just handed us made 'lapsed' unreachable in
        // practice: the host app locks itself once the account lapses, so it stops
        // launching us, so the fresh assertion never comes and the last thing the
        // backend ever heard was "active". Re-sending the STORED one lets the
        // backend watch it pass its own expiry and record the lapse itself.
        //
        // Storing it is safe because it is worthless to us — signed, opaque, and
        // meaningful only to the key-holder. There is nothing here to protect and
        // nothing we could forge.
        // Three sources, best first:
        //   1. an extra, if a host launched us just now
        //   2. the host's provider, which works even when the host app itself is
        //      locked and can no longer launch anything — this is the ONLY path
        //      that delivers the news that an account lapsed
        //   3. whatever we stored last time
        //
        // AND REPORTED TWICE. When our provider read is what starts the host's
        // process, the host has not yet learned the account's current standing —
        // it comes up and connects its event stream in the milliseconds AFTER our
        // query is already answered from disk. So the first read of a cold host is
        // stale by construction, and it is stale in exactly the case that matters:
        // the account changed while nothing was running. The catch-up pass reads
        // again once the host has caught up, and skips the POST if nothing moved.
        //
        // Lives in HostEntitlementRelay on a process-level scope rather than here
        // on lifecycleScope: this activity finishes almost immediately for a
        // returning user, which would cancel the delayed pass before it ran.
        HostEntitlementRelay.submitWithCatchUp(
            this,
            HostEntitlementRelay.extraFrom(intent),
        )

        // ── Account-standing gate ─────────────────────────────────────────────
        // Re-entry while the host reports this account inactive lands on the lock
        // screen instead of the app. Decided from the LAST SERVER VERDICT on disk,
        // not from a fresh network call: this runs before anything is drawn, and
        // blocking the front door on a request would stall every launch for
        // everyone to catch a state almost nobody is in. The relay above is
        // already re-reporting in the background, and the lock screen's own retry
        // is what asks again.
        //
        // isLockedOut() is a positive test for one exact value — never-reported,
        // unknown, and every failure read as "not locked". A sideloaded install
        // with no host must open normally.
        if (HostEntitlementRelay.isLockedOut(this)) {
            startActivity(Intent(this, ServiceLockoutActivity::class.java))
            finish()
            return
        }

        val session = SessionManager.init(applicationContext)

        // ── Update-first gate ────────────────────────────────────────────────
        // INHERITED FROM HostEntryActivity, which used to own it. With no host
        // there is no host-orchestrated update path any more, so this in-app
        // check is not a fallback — it is the ONLY way a fielded TV ever learns
        // a new version exists. Deleting the host package without moving this
        // would have silently ended self-update.
        //
        // EXTRA_SKIP_UPDATE_CHECK is gone with the contract that defined it.
        // SelfUpdateActivity re-enters here after installing, so the loop is
        // broken by an explicit extra instead — without it, a device that fails
        // to install would check, route to the updater, fail, come back, and
        // check again forever.
        // Read for its side effect on the gate, not for a branch here. When the
        // updater hands control back it sets this, and the gate must not turn
        // straight round and route to the updater again on the next home screen.
        if (intent?.getBooleanExtra(EXTRA_SKIP_UPDATE_CHECK, false) == true) {
            UpdateGate.suppressNext()
        }

        // ── NO UPDATE CHECK HERE ANY MORE ───────────────────────────────────
        //
        // It used to be the first thing on this path: a blocking HTTP call that
        // had to finish before the app would route anywhere. MEASURED on real
        // hardware, it failed in two ways at once.
        //
        //   * It timed out on 4 of 6 cold launches and silently left the device
        //     on the old version. The budget had to stay small BECAUSE it blocked
        //     the launch, and no value fixes that: larger misses fewer updates but
        //     lengthens the wait in front of an undrawn window; smaller does the
        //     reverse.
        //
        //   * It raced ProcessLifecycleOwner.onStart, which checked at the same
        //     moment on the same cold start. Both found the update and both
        //     launched the updater with NEW_TASK|CLEAR_TASK — flags that target
        //     the caller's own task. The second tore down the task the first had
        //     just rooted, so nothing drew and 163MB downloaded behind a screen
        //     that still showed the TV launcher.
        //
        // UpdateGate owns this now, hooked to HomeScreenCompose.onResume the way
        // HostEntitlementRelay is. Off the launch path nothing waits on the
        // network, and there is exactly one checker, so neither failure is
        // reachable.
        //
        // EXTRA_SKIP_UPDATE_CHECK is still read above and still honoured — it is
        // what SelfUpdateActivity sets when handing control back, and UpdateGate
        // reads it through rearm()/routed rather than through this intent.
        lifecycleScope.launch {
            // Decided BEFORE any UI is composed so a signed-in user never sees a
            // flash of the sign-in form.
            if (!session.refreshToken.isNullOrBlank()) {
                startActivity(Intent(this@AuthActivity, LoadingScreenActivity::class.java))
                finish()
                return@launch
            }

            // ── Sign in with the host's pass, if there is one ──────────────────
            //
            // An install that arrived through the host already carries everything
            // needed to identify its owner, so making them type an email on a
            // remote asks for something we can already prove. This creates the
            // account on first use and returns the SAME one every time after —
            // including on a second TV, which is what makes profiles follow the
            // person rather than the television.
            //
            // FALLS THROUGH ON EVERY FAILURE, and that is the whole safety rule of
            // this block. Expired pass, no host installed, offline, backend down,
            // several accounts sharing one host — all of them land on the email
            // screen, which is where the user would have started anyway. Nothing
            // here may ever become the reason somebody cannot sign in.
            if (trySignInWithHostPass()) {
                startActivity(Intent(this@AuthActivity, LoadingScreenActivity::class.java))
                finish()
                return@launch
            }

            setContent { AuthScreen() }
        }
    }

    /**
     * Exchange the host's signed pass for a session. Returns true if we are in.
     *
     * Runs on IO and swallows everything: the caller treats false as "show the
     * email screen", so a thrown exception here would turn a recoverable miss into
     * a crash on the launch path.
     */
    private suspend fun trySignInWithHostPass(): Boolean = withContext(Dispatchers.IO) {
        val assertion = HostEntitlementRelay.bestAssertion(
            this@AuthActivity, HostEntitlementRelay.extraFrom(intent)
        ) ?: return@withContext false

        runCatching {
            // Gathered per attempt, matching the email path: the keystore may only
            // become available after first boot, and a null key must not be baked
            // in for the life of the process.
            val resp = ApiClient.bluehiveApi.bhHostProvision(
                BhHostProvisionRequest(
                    assertion         = assertion,
                    deviceFingerprint = DeviceIdentity.fingerprint(this@AuthActivity),
                    publicKey         = DeviceIdentity.publicKeyBase64(),
                    installSource     = DeviceIdentity.installSource(this@AuthActivity),
                    deviceName        = DeviceIdentity.deviceName(),
                )
            )
            val body = resp.body()
            if (!resp.isSuccessful || body == null) {
                // 401 expired/invalid and 409 several-accounts all arrive here.
                // None is worth surfacing: the email screen handles every one of
                // them, and naming the reason would only expose plumbing the user
                // has no way to act on.
                Log.i(TAG, "host sign-in unavailable (HTTP ${resp.code()}) — using the email screen")
                return@runCatching false
            }
            SessionManager.get().saveSession(
                accessToken  = body.accessToken,
                refreshToken = body.refreshToken,
                userId       = body.userId,
                // No name and no real address exist for an auto-provisioned
                // account. The public id is the only human-readable handle it has,
                // so it stands in rather than leaving these blank.
                fullName     = body.userId,
                email        = body.userId,
            )
            Log.i(TAG, "signed in via host pass as ${body.userId}")
            true
        }.getOrElse {
            Log.w(TAG, "host sign-in failed: ${it.message}")
            false
        }
    }

    // The provider read, the prefs mirror and the POST all moved to
    // HostEntitlementRelay. They had to leave this class: the corrective second
    // read runs on a delay, and this activity finishes almost immediately for a
    // returning user, so anything tied to its lifecycleScope was cancelled before
    // it could run.

    companion object {
        private const val TAG = "AuthActivity"

        /** Set by SelfUpdateActivity when re-entering, to avoid a check loop. */
        const val EXTRA_SKIP_UPDATE_CHECK = "com.example.bluehive.extra.SKIP_UPDATE_CHECK"

        /**
         * Extra a host app uses to pass an account-standing assertion.
         *
         * Names a CAPABILITY, not a product — any host implementing the same
         * convention works, and nothing about this key ties BlueHive to one
         * vendor. That genericness is deliberate, not incidental.
         */
        const val EXTRA_HOST_ASSERTION = "io.host.entitlement.ASSERTION"

        // The prefs name and key moved to HostEntitlementRelay along with
        // everything else that touches the mirror. Kept identical there, so an
        // upgrade re-reports the assertion already on disk rather than losing it.
    }

    @Composable
    private fun AuthScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var email by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        val emailFocus = remember { FocusRequester() }
        val phoneFocus = remember { FocusRequester() }
        val createFocus = remember { FocusRequester() }
        val signInFocus = remember { FocusRequester() }

        LaunchedEffect(Unit) { runCatching { emailFocus.requestFocus() } }

        /**
         * Shared by both buttons. `creating` picks the endpoint; everything else —
         * the device identity, the failure handling, the hand-off — is identical,
         * so the two paths cannot drift apart.
         */
        fun submit(creating: Boolean) {
            if (busy) return
            val addr = email.trim()
            if (addr.isEmpty() || !addr.contains("@")) {
                error = "Enter your email address"
                return
            }
            busy = true
            error = null

            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        // Gathered per attempt rather than cached: the keystore
                        // may only become available after first boot on some
                        // devices, and a null key must not be baked in for the
                        // lifetime of the process.
                        val fp = DeviceIdentity.fingerprint(context)
                        val pk = DeviceIdentity.publicKeyBase64()
                        val src = DeviceIdentity.installSource(context)
                        val name = DeviceIdentity.deviceName()

                        if (creating) {
                            ApiClient.bluehiveApi.bhSignup(
                                BhSignupRequest(
                                    email = addr,
                                    phoneNumber = phone.trim().ifBlank { null },
                                    deviceFingerprint = fp,
                                    publicKey = pk,
                                    installSource = src,
                                    deviceName = name,
                                )
                            )
                        } else {
                            ApiClient.bluehiveApi.bhLogin(
                                BhLoginRequest(
                                    email = addr,
                                    deviceFingerprint = fp,
                                    publicKey = pk,
                                    installSource = src,
                                    deviceName = name,
                                )
                            )
                        }
                    }
                }

                busy = false
                val resp = result.getOrNull()
                val body = resp?.body()

                when {
                    resp != null && resp.isSuccessful && body != null -> {
                        SessionManager.get().saveSession(
                            accessToken = body.accessToken,
                            refreshToken = body.refreshToken,
                            userId = body.userId,
                            // BlueHive collects no name — the backend has no
                            // such column. saveSession requires non-null, so the
                            // email stands in as the display value rather than
                            // an empty string that would render as blank space.
                            fullName = addr,
                            email = addr,
                        )
                        context.startActivity(Intent(context, LoadingScreenActivity::class.java))
                        finish()
                    }
                    // 409 on create and 404 on sign-in are the two halves of the
                    // same confusion, so each points at the other button rather
                    // than restating the status code.
                    resp?.code() == 409 -> error = "That email already has an account — choose Sign in"
                    resp?.code() == 404 -> error = "No account for that email — choose Create account"
                    resp != null -> error = "Something went wrong (${resp.code()}). Try again."
                    else -> error = "Can't reach BlueHive. Check the connection and try again."
                }
            }
        }

        Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12))) {
            Image(
                painter = painterResource(id = R.drawable.profile_screen),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            Column(
                modifier = Modifier.align(Alignment.Center).width(520.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Welcome to BlueHive",
                    fontFamily = AppTypography.passionRegular,
                    fontSize = 34.sp,
                    color = Color.White,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Enter your email to create an account or sign in",
                    fontFamily = AppTypography.dongleRegular,
                    fontSize = 17.sp,
                    color = Color(0xFF8A8F97),
                )

                Spacer(Modifier.height(26.dp))
                TvField("Email", email, emailFocus) { email = it }
                Spacer(Modifier.height(12.dp))
                TvField("Phone number (optional)", phone, phoneFocus) { phone = it }

                Spacer(Modifier.height(10.dp))
                // Reserved whether or not there is an error, so the buttons do
                // not jump when one appears.
                Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
                    if (error != null) {
                        Text(
                            text = error!!,
                            fontFamily = AppTypography.dongleRegular,
                            fontSize = 15.sp,
                            color = Color(0xFFE08F86),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    ModularButton(
                        textConfig = ModularButtonTextConfig(
                            text = if (busy) "Please wait" else "Create account",
                            fontSize = 11f,
                        ),
                        isToggleable = false,
                        isFocusable = !busy,
                        fontFamily = AppTypography.interBold,
                        focusRequester = createFocus,
                        onClick = { submit(creating = true) },
                        modifier = Modifier.onFocusChanged {
                            if (it.isFocused) BlueHiveApplication.playHoverSound()
                        },
                    )
                    ModularButton(
                        textConfig = ModularButtonTextConfig(text = "Sign in", fontSize = 11f),
                        isToggleable = false,
                        isFocusable = !busy,
                        fontFamily = AppTypography.interBold,
                        focusRequester = signInFocus,
                        onClick = { submit(creating = false) },
                        modifier = Modifier.onFocusChanged {
                            if (it.isFocused) BlueHiveApplication.playHoverSound()
                        },
                    )
                }
            }
        }
    }

    /**
     * A focusable text field sized for a ten-foot interface.
     *
     * BasicTextField rather than a Material TextField: the Material ones bring
     * their own focus indicators and container colours that fight the rest of
     * this app's look, and on TV the focus ring has to be unmistakable from
     * across a room.
     */
    @Composable
    private fun TvField(
        label: String,
        value: String,
        focusRequester: FocusRequester,
        onChange: (String) -> Unit,
    ) {
        var focused by remember { mutableStateOf(false) }

        Column(Modifier.fillMaxWidth()) {
            Text(
                text = label,
                fontFamily = AppTypography.dongleRegular,
                fontSize = 14.sp,
                color = if (focused) Color.White else Color(0xFF8A8F97),
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(if (focused) Color(0xFF52505A) else Color(0xFF2E2C37))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 18.sp,
                        fontFamily = AppTypography.interSemiBold,
                    ),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            if (it.isFocused && !focused) BlueHiveApplication.playHoverSound()
                            focused = it.isFocused
                        },
                )
            }
        }
    }
}
