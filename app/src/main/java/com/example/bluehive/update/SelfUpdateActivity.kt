package com.example.bluehive.update

import android.app.AppOpsManager
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.Build
import android.provider.Settings
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.bluehive.R
import com.example.bluehive.BuildConfig
import com.example.bluehive.auth.AuthActivity
import com.example.bluehive.utilities.UpdateRequiredOverlay
import com.example.bluehive.utilities.performClose
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.focusable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "SelfUpdate"

/**
 * How long to wait before re-presenting a failed required install unattended.
 *
 * Long enough that a user who is actively dismissing the dialog is not fought
 * with, short enough that someone who simply missed it gets another chance
 * without having to work out what to press.
 */
private const val AUTO_RETRY_DELAY_MS = 8_000L

/**
 * How many times a failed required install is re-attempted unattended.
 *
 * Enough to cover a dialog dismissed by accident more than once; few enough
 * that a failure which will never succeed stops reopening a system dialog on a
 * screen the user cannot leave. The manual button remains after the budget is
 * spent -- a person deciding to press again is not a loop.
 */
private const val MAX_AUTO_RETRIES = 3

/**
 * The update screen. Two phases, in this order:
 *
 *   1. CONSENT — [UpdateRequiredOverlay]. States that the release is required,
 *      names both versions, and offers exactly two ways out: accept, or close
 *      the app. Nothing is fetched while this is on screen.
 *   2. PROGRESS — the original "Updating BlueHive" screen. Download with a live
 *      bar, sha256 verify, then PackageInstaller.
 *
 * PHASE 1 IS NEW AND IS THE POINT. This activity used to open straight onto
 * phase 2, so a user's first news of an update was a progress bar already
 * moving. Reached from the loading screen that is merely abrupt; reached from
 * [UpdateGate]'s home-screen hook it lands on top of a screen someone is using,
 * which reads as a fault rather than as maintenance.
 *
 * Entered from three places, all through UpdateGate: the loading screen at cold
 * start, HomeScreenCompose.onResume, and the operator's force_update push. All
 * three now open on the consent gate.
 */
class SelfUpdateActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VERSION_CODE = "extra_version_code"
        const val EXTRA_VERSION_NAME = "extra_version_name"
        const val EXTRA_APK_URL = "extra_apk_url"
        const val EXTRA_SHA256 = "extra_sha256"

        /**
         * The manifest's min_supported, carried through so this screen can decide
         * whether the update may be declined. Previously hardcoded to 0 here,
         * which made every update optional no matter what the manifest said.
         */
        const val EXTRA_MIN_SUPPORTED = "extra_min_supported"

        /**
         * AppOpsManager.OPSTR_REQUEST_INSTALL_PACKAGES is @hide, so the literal is
         * spelled out. It is a stable platform string -- the value the Settings
         * toggle writes and PackageInstaller reads -- not something we chose.
         */
        private const val OP_REQUEST_INSTALL_PACKAGES = "android:request_install_packages"

        /**
         * True from onCreate to onDestroy. The lockout screen gives way to this
         * one while it is up (see UpdateGate.updaterUp): an update needs no
         * slot, and a TV that cannot get in is exactly the one that most needs
         * the newest build.
         */
        @Volatile var isAlive: Boolean = false
            private set
    }

    private val progress = MutableStateFlow(0f)
    private val status = MutableStateFlow("Preparing update…")
    private val isInstalling = MutableStateFlow(false)

    /**
     * True until the user has pressed Accept, which is the whole of this screen
     * until they do.
     *
     * ONE-WAY. Nothing sets it back to true, so a failed install, a permission
     * trip to Settings and a retry all stay on the progress screen rather than
     * re-asking a question that has already been answered. Re-consenting per
     * attempt would be asking somebody to agree to the same update three times.
     */
    private val awaitingConsent = MutableStateFlow(true)

    /** Set while the user is away in the settings screen granting the permission. */
    private var awaitingPermission = false

    /**
     * Whether we have actually been backgrounded since asking.
     *
     * WITHOUT THIS THE CHECK FIRES INSTANTLY. onResume runs as part of the normal
     * onCreate -> onStart -> onResume sequence, before the settings Activity we
     * just launched has taken the foreground. The first version read that as "the
     * user came back and refused", and dropped straight into the app -- so the
     * permission screen appeared for a fraction of a second and the update was
     * abandoned every time.
     *
     * onPause is what proves they genuinely went somewhere.
     */
    private var pausedSinceAsking = false

    /** Held so onResume can start the download it deferred. */
    private lateinit var pendingManifest: UpdateManager.Manifest

    /** Whether declining leaves the user with a usable app. See UpdateManager.isMandatory. */
    private var isMandatoryUpdate = false

    /**
     * Shown when a REQUIRED install did not complete.
     *
     * THE SCREEN USED TO BE A DEAD END. On a mandatory update a failed or
     * dismissed install set the text "This update is required to continue. Please
     * try again." and stopped there — no button, no retry, no focusable element
     * anywhere on the screen, with Back already blocked and proceedToApp()
     * deliberately not called. The instruction was to try again and there was
     * nothing to try again with.
     *
     * That is a brick, and an easy one to hit: PackageInstaller always raises a
     * system confirm dialog, so looking away at the wrong moment is enough. Every
     * release being mandatory makes it a brick for every user who does.
     */
    private val canRetry = MutableStateFlow(false)

    // NO WAY PAST A FAILED UPDATE, at the owner's call (2026-10-21). A canSkip
    // flag used to put a quiet "Continue without updating" under the retry button
    // on optional updates. The failure screen now offers exactly two things, Try
    // again and Close Application, and Back is the second way to close.
    //
    // WHAT THAT COSTS, stated so nobody rediscovers it: the consent screen offers
    // only Accept or Close, so a device that cannot install -- a full disk above
    // all -- cannot open BlueHive until the cause is fixed. That is why the
    // failure screen has to name the cause in its title and in red.

    /** The status in support form ("code 6: INSTALL_FAILED_..."), shown small under the reason. */
    private val failureCode = MutableStateFlow<String?>(null)

    /** True when the stopped screen is reporting a failure, which draws it in red. */
    private val isError = MutableStateFlow(false)

    /**
     * True on a "not enough storage" failure only. Adds a first, focused Clear
     * space button that opens the device's installed-apps page, where the user
     * can uninstall apps or clear their caches (see openAppsSettings).
     */
    private val canClearSpace = MutableStateFlow(false)

    /** The verified APK, kept so a retry does not re-download 163MB. */
    @Volatile private var downloadedApk: java.io.File? = null

    /** Guards against two installs in flight from a double press. */
    @Volatile private var installing = false

    /** Same idea for the exit: Back and the button both land on closeApplication. */
    @Volatile private var closing = false

    /** What the on-screen button should say, since it does not always retry. */
    private val retryLabel = MutableStateFlow("Try again")

    /**
     * The failure in words, shown on screen.
     *
     * On a TV there is no logcat and no support channel; if the screen does not
     * say what went wrong, nobody can act on it -- not the user, who may simply
     * need to delete an app, and not the operator, who needs to know a publish
     * was mis-signed before the whole fleet is stuck on it.
     */
    private val failureDetail = MutableStateFlow<String?>(null)

    /**
     * Unattended retries used so far.
     *
     * CAPPED. An automatic retry is worth having because the usual failure is a
     * confirm dialog nobody noticed, and re-presenting it fixes that without the
     * user understanding anything. But an uncapped loop turns a permanent
     * failure into a screen that reopens a system dialog every 8 seconds and
     * cannot be left. The manual button stays available after the cap: a person
     * choosing to press again is not a loop.
     */
    @Volatile private var autoRetries = 0

    /** One fresh download is allowed for a corrupt APK; after that it is real. */
    @Volatile private var redownloaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isAlive = true

        val manifest = UpdateManager.Manifest(
            latestVersionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0),
            latestVersionName = intent.getStringExtra(EXTRA_VERSION_NAME) ?: "?",
            apkUrl = intent.getStringExtra(EXTRA_APK_URL) ?: "",
            sha256 = intent.getStringExtra(EXTRA_SHA256) ?: "",
            minSupported = intent.getIntExtra(EXTRA_MIN_SUPPORTED, 0),
        )

        // Default 0 above keeps every existing caller behaving exactly as before:
        // no extra means minSupported 0, which no build is below, so the update
        // stays optional. Only a manifest that actually sets min_supported can
        // make one mandatory.
        pendingManifest = manifest
        val mandatory = UpdateManager.isMandatory(manifest)
        isMandatoryUpdate = mandatory
        if (mandatory) Log.i(TAG, "mandatory update — build ${manifest.minSupported} is the floor")

        // BACK CLOSES THE APP ON A STOPPED SCREEN and does nothing while a download
        // or install is running. Swallowing it mid-flight keeps a stray press from
        // abandoning an update half-written; once the screen has stopped -- a
        // failure, or waiting on the install permission -- Back is the second way
        // to the same Close Application the button offers. The consent screen has
        // its own BackHandler, which takes precedence while it is composed.
        onBackPressedDispatcher.addCallback(this, object :
            androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canRetry.value) closeApplication()
            }
        })

        // If install is cancelled or fails, let the user into the app anyway —
        // UNLESS the build is below the manifest's floor, in which case there is
        // no app to let them into. Declining is then a choice to stop, not a
        // choice to continue on the old version, so the screen stays put and says
        // so rather than silently looping them back to a blocked app.
        UpdateInstallBus.register { result ->
            val code = result.status
            if (code != PackageInstaller.STATUS_SUCCESS) {
                isInstalling.value = false
                installing = false
                if (mandatory) {
                    // Log.e: R8 strips w from release builds, and an install that
                    // will not complete is the failure that strands a user.
                    Log.e(TAG, "Install not completed (status=$code msg=${result.message})")

                    when {
                        // Permission first, whatever the status says. Nothing else
                        // can succeed until the toggle is on, and on this TV build
                        // the app's own intent is the only route to it.
                        !canInstallPackages() -> {
                            status.value = "BlueHive needs permission to install updates."
                            failureDetail.value =
                                "Turn on \u201CAllow from this source\u201D on the next screen, " +
                                    "then come back here."
                            failureCode.value = null
                            isError.value = false
                            canClearSpace.value = false
                            retryLabel.value = "Open settings"
                            canRetry.value = true
                            awaitingPermission = true
                            openInstallPermissionSettings()
                        }

                        classify(code) == Failure.REDOWNLOAD && !redownloaded -> {
                            // Corrupt bytes are the one failure a fresh fetch fixes,
                            // and re-installing the same damaged file cannot.
                            Log.e(TAG, "damaged APK — discarding and re-downloading once")
                            redownloaded = true
                            downloadedApk?.delete()
                            downloadedApk = null
                            canRetry.value = false
                            failureDetail.value = null
                            failureCode.value = null
                            isError.value = false
                            canClearSpace.value = false
                            startDownload(pendingManifest)
                        }

                        classify(code) == Failure.TERMINAL ||
                            (classify(code) == Failure.REDOWNLOAD && redownloaded) -> {
                            // NO AUTO-RETRY. Repeating this produces the same result,
                            // so the loop would only reopen a system dialog every few
                            // seconds on a screen nobody can leave. Say what happened
                            // instead — the reason is the only useful thing left, and
                            // for a full disk it is something the user can act on.
                            Log.e(TAG, "terminal install failure — not retrying")
                            showFailure(code, result.message)
                            canRetry.value = true   // manual only; a person is not a loop
                        }

                        else -> {
                            showFailure(code, result.message)
                            canRetry.value = true
                            // AND retry on its own, up to a limit. The commonest
                            // cause is nobody seeing the system dialog — it appears
                            // over the app and is easy to miss on a TV — so
                            // re-presenting it unattended fixes the ordinary case
                            // without anyone needing to understand what happened.
                            if (autoRetries < MAX_AUTO_RETRIES) {
                                autoRetries++
                                val attempt = autoRetries
                                lifecycleScope.launch {
                                    delay(AUTO_RETRY_DELAY_MS)
                                    if (!installing && canRetry.value) {
                                        Log.e(TAG, "auto-retry $attempt/$MAX_AUTO_RETRIES")
                                        beginInstall()
                                    }
                                }
                            } else {
                                Log.e(TAG, "auto-retry budget spent — waiting for the user")
                            }
                        }
                    }
                } else if (code == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    // THEY DECLINED, AND THAT IS AN ANSWER. Aborted is the system
                    // confirm dialog being dismissed, so the user already knows
                    // why nothing installed. Explaining it back to them would be
                    // arguing with a decision they just made.
                    Log.i(TAG, "Install declined by the user — continuing to app")
                    proceedToApp()
                } else {
                    // EVERYTHING ELSE OWES AN EXPLANATION. An optional update that
                    // fails for a reason the user did not choose — a full disk
                    // above all, which is reported here from the download path
                    // too — used to vanish silently and leave them in the app with
                    // no idea an update had been attempted. The screen now says
                    // what happened and offers Try again or Close Application; the
                    // way past it was removed at the owner's call (see failureCode).
                    Log.e(TAG, "Install not completed (status=$code) — explaining, offering retry or close")
                    showFailure(code, result.message)
                    canRetry.value = true
                }
            }
        }

        setContent {
            val needsConsent by awaitingConsent.collectAsState()
            if (needsConsent) {
                // NOTHING HAS BEEN FETCHED YET. Reaching this screen only means the
                // manifest said a newer build exists; the download starts when the
                // user says so, not before. Closing here costs no bandwidth.
                UpdateRequiredOverlay(
                    currentVersion = BuildConfig.VERSION_NAME,
                    newVersion     = manifest.latestVersionName,
                    onAccept       = { onUpdateAccepted() },
                    onClose        = { closeApplication() },
                )
            } else {
                val p by progress.collectAsState()
                val s by status.collectAsState()
                val installing by isInstalling.collectAsState()
                val retry by canRetry.collectAsState()
                val label by retryLabel.collectAsState()
                val detail by failureDetail.collectAsState()
                val code by failureCode.collectAsState()
                val error by isError.collectAsState()
                val clearSpace by canClearSpace.collectAsState()
                UpdateScreen(
                    fromVersion = BuildConfig.VERSION_NAME,
                    toVersion = manifest.latestVersionName,
                    progress = p,
                    status = s,
                    isInstalling = installing,
                    showRetry = retry,
                    onRetry = { performRetry() },
                    retryLabel = label,
                    detail = detail,
                    errorCode = code,
                    isError = error,
                    onClose = { closeApplication() },
                    showClearSpace = clearSpace,
                    onClearSpace = { openAppsSettings() },
                )
            }
        }
    }

    /**
     * The user pressed Accept. Everything from here is the old onCreate tail.
     *
     * WHY THE WORK MOVED OUT OF onCreate. It used to run the moment this activity
     * existed, which is what made an update something that happened TO somebody.
     * The steps themselves are unchanged and still run in the same order; the only
     * difference is that a person now starts them.
     *
     * GUARDED, because a TV remote double-press is a real event and this is
     * reachable from a focused button. Without the latch the second press would
     * start a second download of the same 163MB.
     */
    private fun onUpdateAccepted() {
        if (!awaitingConsent.value) return
        awaitingConsent.value = false
        Log.i(TAG, "user accepted the update — beginning permission + download")

        // ── PERMISSION BEFORE DOWNLOAD ──────────────────────────────────────
        //
        // Android will not let an app install an APK unless the user has granted
        // it "Install unknown apps" — a per-app switch that defaults to OFF and
        // cannot be pre-granted by anyone, because it is the permission that lets
        // an app install other apps.
        //
        // This used to be discovered at the END: download ~165MB, hand it to
        // PackageInstaller, and only then get the system's security warning. Press
        // Cancel and the whole download was wasted, and the next check repeated it.
        // On a metered or slow connection that is a bad first impression, and it is
        // the first thing a standalone install ever sees from the update system.
        //
        // Asking first costs the user the same single press but nothing if they
        // decline, and the ask arrives with a reason attached instead of as a
        // security warning after a long wait.
        //
        // STILL FIRST, NOW THAT CONSENT PRECEDES IT. The consent screen has just
        // told the user in as many words that Android will ask them to confirm,
        // so the settings trip is expected rather than a surprise interruption —
        // and the reason for the ordering is unchanged: a refusal here must not
        // come after a 163MB download that then has to be thrown away.
        if (!canInstallPackages()) {
            Log.i(TAG, "install permission not granted — asking before downloading")
            awaitingPermission = true
            status.value = "BlueHive needs permission to install its own updates."
            openInstallPermissionSettings()
            return   // onResume picks it up from here
        }

        startDownload(pendingManifest)
    }

    /**
     * The user chose to leave rather than update.
     *
     * THE SAME CLOSE PATH AS THE BACK BUTTON, not a finish() of our own. That path
     * releases the device slot with an end-session ping and stops the event stream
     * before tearing the task down; skipping it would leave the server believing
     * this TV is still watching until its reaper times the session out.
     *
     * killProcess = true because there is nothing to come back to warm. The next
     * launch has to be a fresh process anyway: it needs to re-check the manifest,
     * and UpdateGate's routed latch lives for the life of the process.
     */
    private fun closeApplication() {
        // GUARDED because this is now reachable from two inputs -- the button and
        // the Back key -- and a TV remote double-press is a real event. performClose
        // fires an end-session ping and then kills the process; running it twice
        // races two teardowns of the same session for no benefit.
        if (closing) return
        closing = true
        Log.i(TAG, "user declined the update — closing the app")
        performClose(this, killProcess = true)
    }

    /**
     * True when we may hand an APK to PackageInstaller.
     *
     * API 25 has no per-app switch — unknown sources is a single global setting
     * there — so there is nothing to check and nothing to ask for. Returning true
     * keeps that path behaving exactly as it did.
     */
    /**
     * May we actually hand an APK to PackageInstaller?
     *
     * READS THE APPOP DIRECTLY, because BOTH documented APIs lie on this hardware.
     * Measured on the onn 4K Streaming Box (Android 14, SDK 34):
     *
     *                                  not granted    granted
     *   canRequestPackageInstalls()       true         true     <- never false
     *   checkSelfPermission(...)          false        false    <- never true
     *   appop request_install_packages    default      allow    <- tracks reality
     *
     * The first is the API documented for exactly this question and it answered
     * "yes" while PackageInstaller went on to refuse the install. The second stays
     * "denied" even after the user grants the permission, because the Settings
     * toggle writes the APPOP and leaves the permission grant state alone.
     *
     * Each was tried in turn. canRequestPackageInstalls() alone put the download
     * back before the check, which is the bug this exists to prevent. Requiring
     * both was worse -- a deadlock, since the second can never become true, so the
     * app would have asked for permission forever and never updated.
     *
     * The appop is what the Settings toggle writes and what PackageInstaller
     * enforces, so it is the only one that answers the question being asked.
     *
     * API 25 has no per-app switch -- unknown sources is one global setting there
     * -- so there is nothing to check and nothing to ask for.
     *
     * TWO NAMES FOR THE SAME READ, SPLIT AT API 29. unsafeCheckOpNoThrow only
     * exists from Android 10; on Android 8 and 9 the same appop is read with
     * checkOpNoThrow, which Android 11 later deprecated in its favour. Calling
     * the new name unguarded crashed every Accept on a Fire TV Stick (Fire OS 7,
     * Android 9) with NoSuchMethodError, 2026-10-21 -- before any download, so
     * no Android 8 or 9 device could update itself.
     */
    private fun canInstallPackages(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val appOps = getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(OP_REQUEST_INSTALL_PACKAGES, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(OP_REQUEST_INSTALL_PACKAGES, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Open the per-app "Install unknown apps" screen for THIS package.
     *
     * The package: URI matters. Without it Android opens the full list of apps
     * and the user has to find BlueHive in it; with it they land on our own
     * toggle. On some TV builds -- the onn 4K box among them -- that screen has no
     * menu entry anywhere in Settings, so this intent is the only way a user can
     * reach it at all.
     */
    private fun openInstallPermissionSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            // No such screen on this device. Fall through to the old behaviour:
            // download and let PackageInstaller raise its own warning, which is
            // worse but still works.
            Log.w(TAG, "could not open install-permission settings: ${it.message}")
            awaitingPermission = false
            startDownload(pendingManifest)
        }
    }

    /**
     * The Clear space button: open the device's installed-apps page, where the
     * user uninstalls apps or clears their caches by hand. BlueHive cannot free
     * another app's space itself -- only the system or the user can.
     *
     * TRIED IN ORDER, FIRST THAT OPENS WINS. MANAGE_APPLICATIONS_SETTINGS is the
     * installed-apps list: on Fire OS 7 it resolves to Settings > Applications
     * (com.amazon.tv.settings.v2, checked on the Fire TV Stick 2026-10-22), and
     * Android TV / Google TV Settings handles the same action for Settings >
     * Apps. Fire OS has no storage page an app can open -- the storage action
     * resolves to nothing there -- which is why the apps list is the target on
     * both. Plain Settings is the last resort.
     *
     * TRIED WITH startActivity, NOT resolveActivity. From Android 11, package
     * visibility hides other apps' activities from resolveActivity unless the
     * manifest declares them, while startActivity is not filtered, so actually
     * trying is the honest test.
     *
     * The user returns with Back to this same screen and presses Try again.
     */
    private fun openAppsSettings() {
        val actions = listOf(
            Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
            Settings.ACTION_APPLICATION_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        for (action in actions) {
            if (runCatching { startActivity(Intent(action)) }.isSuccess) {
                Log.e(TAG, "clear space: opened $action")
                return
            }
        }
        Log.e(TAG, "clear space: no settings screen could be opened")
    }

    override fun onPause() {
        super.onPause()
        if (awaitingPermission) pausedSinceAsking = true
    }

    override fun onResume() {
        super.onResume()
        // Back from the settings screen. Requires BOTH that we sent them there and
        // that we were actually backgrounded in between -- see pausedSinceAsking.
        if (!awaitingPermission || !pausedSinceAsking) return
        pausedSinceAsking = false
        if (canInstallPackages()) {
            Log.i(TAG, "install permission granted — starting download")
            awaitingPermission = false
            startDownload(pendingManifest)
        } else if (isMandatoryUpdate) {
            // Required update, permission still refused.
            //
            // THIS BRANCH USED TO BE THE SECOND BRICK. It set this string and
            // stopped: canRetry stayed false, so the button was not composed,
            // and with Back swallowed and proceedToApp() unreachable on a
            // required update there was no focusable element on the screen at
            // all. Backing out of an unfamiliar system settings screen without
            // finding the toggle -- easy to do, since it is off by default and
            // this is the first update every device takes -- ended the session
            // there permanently.
            //
            // It now offers the same way back to the toggle that the install
            // failure path does. Not an escape hatch: the update is still
            // required, this is simply the only route to the switch that lets it
            // proceed.
            Log.e(TAG, "install permission still refused on a required update — offering settings")
            status.value = "BlueHive needs permission to install updates."
            failureDetail.value =
                "Open settings, turn on \u201CAllow from this source\u201D for BlueHive, " +
                    "then come back here."
            failureCode.value = null
            isError.value = false
            canClearSpace.value = false
            retryLabel.value = "Open settings"
            canRetry.value = true
        } else {
            Log.i(TAG, "install permission declined — continuing on the current version")
            awaitingPermission = false
            proceedToApp()
        }
    }

    private fun startDownload(manifest: UpdateManager.Manifest) {
        lifecycleScope.launch {
            status.value = "Downloading update…"
            val outcome = UpdateManager.downloadAndVerify(applicationContext, manifest) { f ->
                progress.value = f
            }
            val apk = when (outcome) {
                is UpdateManager.DownloadOutcome.Ok -> outcome.apk

                // ALREADY HANDLED — DO NOT NAVIGATE. The bus handler has both put
                // the reason on screen and decided whether this user may continue
                // on the old build (they may not, if the build is under the
                // manifest's floor). Calling proceedToApp() here would erase the
                // explanation and override that decision.
                UpdateManager.DownloadOutcome.Reported -> {
                    Log.e(TAG, "download stopped — reason already on the install bus")
                    return@launch
                }

                // SAY WHY, THEN STOP. This used to call proceedToApp() on the
                // spot, which is how a failed download became invisible: the
                // screen vanished and the user was in the app on the old build
                // with nothing to explain it, on a required update as readily as
                // an optional one. The reason goes on the same failure screen the
                // install path uses, with the same two ways on: Try again or
                // Close Application.
                is UpdateManager.DownloadOutcome.Failed -> {
                    Log.e(TAG, "download failed — ${outcome.reason}")
                    progress.value = 0f
                    status.value = "Couldn't download the update"
                    failureDetail.value = outcome.reason
                    failureCode.value = null
                    isError.value = true
                    canClearSpace.value = false
                    // beginInstall() restarts the download when no APK is held,
                    // so the existing dispatcher already does the right thing.
                    retryLabel.value = "Try again"
                    canRetry.value = true
                    return@launch
                }
            }
            progress.value = 1f
            status.value = "Ready to install"
            // KEPT so a retry re-installs the file we already verified rather than
            // pulling 163MB again. On a slow connection a re-download is the
            // difference between "press it again" and "wait four minutes".
            downloadedApk = apk
            beginInstall()

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


    /**
     * Hand the verified APK to PackageInstaller.
     *
     * ONE ENTRY POINT for the first attempt, the auto-retry and the button, so
     * all three go through the same guard and the same state. Two code paths for
     * "install this file" is how one of them ends up missing the in-flight check.
     */
    private fun beginInstall() {
        // exists(), not just non-null. The APK lives in cacheDir, which the
        // system reclaims under storage pressure — precisely the condition an
        // update on a nearly-full box creates. A stale File reference then failed
        // every install forever with nothing saying the file had gone.
        val apk = downloadedApk?.takeIf { it.exists() && it.length() > 0 } ?: run {
            Log.e(TAG, "no usable downloaded APK — restarting the download")
            downloadedApk = null
            canRetry.value = false
            failureDetail.value = null
            failureCode.value = null
            isError.value = false
            canClearSpace.value = false
            startDownload(pendingManifest)
            return
        }
        if (installing) return
        installing = true
        canRetry.value = false
        failureDetail.value = null
        failureCode.value = null
        isError.value = false
        canClearSpace.value = false
        isInstalling.value = true
        status.value = "Installing update…"
        UpdateManager.install(applicationContext, apk)
    }

    /**
     * How to respond to a terminal PackageInstaller status.
     *
     * The distinction that matters is not success/failure -- it is whether doing
     * the same thing again could plausibly produce a different result.
     */
    private enum class Failure {
        /** A different outcome is plausible: the dialog was dismissed, or timed out. */
        RETRYABLE,

        /** The bytes are bad. A fresh download is the one thing that can fix it. */
        REDOWNLOAD,

        /** Nothing this app can do will change the answer. Say so and stop. */
        TERMINAL,
    }

    private fun classify(status: Int): Failure = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED,
        PackageInstaller.STATUS_FAILURE_TIMEOUT -> Failure.RETRYABLE

        // A truncated or corrupt APK that still matched the manifest hash, or a
        // cache file the system reclaimed mid-flight. Re-fetching is the fix.
        PackageInstaller.STATUS_FAILURE_INVALID -> Failure.REDOWNLOAD

        // CONFLICT is a signing-key or downgrade mismatch, BLOCKED is a policy
        // or Play Protect refusal, INCOMPATIBLE is the wrong ABI or API level,
        // STORAGE is a full disk. Retrying any of these is guaranteed to fail
        // identically, and CONFLICT in particular arrives fleet-wide from a
        // single bad publish -- looping on it would strand every device at once.
        PackageInstaller.STATUS_FAILURE_CONFLICT,
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
        PackageInstaller.STATUS_FAILURE_BLOCKED,
        PackageInstaller.STATUS_FAILURE_STORAGE -> Failure.TERMINAL

        // Bare STATUS_FAILURE is what UpdateManager posts when the session threw.
        // The cause is unknown, so one more attempt is reasonable -- the cap
        // stops it becoming a loop.
        else -> Failure.RETRYABLE
    }

    /**
     * Put a failed install on screen: why in the title, what to do in the red
     * box, and the status code underneath for support.
     *
     * THE TITLE NAMES THE CAUSE. It used to read "Update could not be installed"
     * whatever happened, with the reason in small grey text at the foot of the
     * screen -- on a Fire TV Stick out of space, nobody saw it (2026-10-21).
     */
    private fun showFailure(code: Int, msg: String?) {
        status.value = failureTitle(code)
        failureDetail.value = explain(code)
        failureCode.value = if (msg.isNullOrBlank()) "code $code" else "code $code: $msg"
        isError.value = true
        canClearSpace.value = code == PackageInstaller.STATUS_FAILURE_STORAGE
        retryLabel.value = "Try again"
    }

    /** The cause as a short title. It is the first thing read, so it says why. */
    private fun failureTitle(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "This update doesn't match the installed BlueHive"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update isn't compatible with this device"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "This device blocked the update"
        PackageInstaller.STATUS_FAILURE_INVALID -> "The update download was damaged"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "The install was cancelled"
        PackageInstaller.STATUS_FAILURE_TIMEOUT -> "The install timed out"
        else -> "The update could not be installed"
    }

    /** The failure in language a person can act on. The code is shown separately. */
    private fun explain(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_STORAGE ->
            "There isn't enough free space on this device for the update. " +
                "Press Clear space to open your apps, uninstall one you don't use " +
                "or clear its cache, then come back here and press Try again."
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "This update does not match the copy of BlueHive already installed."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "This update is not compatible with this device."
        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "The system blocked this install."
        PackageInstaller.STATUS_FAILURE_INVALID ->
            "The downloaded update was damaged. Press Try again to download it again."
        PackageInstaller.STATUS_FAILURE_ABORTED ->
            "Android's install confirmation closed before it finished. " +
                "Press Try again and choose Install."
        PackageInstaller.STATUS_FAILURE_TIMEOUT ->
            "Android took too long to confirm the install. Press Try again."
        else -> "Android did not give a reason. Press Try again."
    }

    /**
     * What the on-screen button does.
     *
     * ONE DISPATCHER so the button is never wired to an action that cannot help.
     * When the permission is missing, retrying the install is pointless -- the
     * toggle is the only thing that changes the outcome, and on this TV build
     * the app's own intent is the only way to reach it.
     */
    private fun performRetry() {
        if (!canInstallPackages()) {
            awaitingPermission = true
            pausedSinceAsking = false
            openInstallPermissionSettings()
            return
        }
        beginInstall()
    }

    /** Re-enter via HostEntryActivity but SKIP the update check to avoid a loop. */
    private fun proceedToApp() {
        startActivity(
            Intent(this, AuthActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(AuthActivity.EXTRA_SKIP_UPDATE_CHECK, true)
            }
        )
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        isAlive = false
        UpdateInstallBus.unregister()
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// The progress screen — phase 2, after the user has accepted.
//
// ONE FOCAL POINT, NOT FOUR. The previous version stacked a glyph, a wordmark, a
// version pair and a flat bar down the middle, so nothing led and the eye had to
// read all of it to learn the one thing that matters: is this moving. The dial
// below carries progress, identity and motion in a single object, which is what
// a screen watched from a sofa needs.
//
// THE MARK SITS INSIDE THE RING because the arc is then literally a progress
// bar drawn around BlueHive's own logo, and it lets the heading go: the logo
// already says whose update this is, so the words underneath are free to say
// something useful instead of repeating the app's name.
// ─────────────────────────────────────────────────────────────────────────────

/** Outer diameter of the progress dial. */
private val RING_DIAMETER = 188.dp

/** Arc thickness. Thin enough to read as a ring rather than a donut. */
private val RING_STROKE_WIDTH = 5.dp

/**
 * The mark inside the ring. 1.5x the original 96dp at the owner's request
 * (2026-10-21); the ring keeps its size so the screen still fits a 540dp-tall
 * TV layout, leaving the logo about 19dp clear of the arc.
 */
private val RING_LOGO_SIZE = 144.dp

/** Sweep of the chasing arc while progress is unknown. */
private const val INDETERMINATE_SWEEP_DEG = 96f

@Composable
private fun UpdateScreen(
    fromVersion: String,
    toVersion: String,
    progress: Float,
    status: String,
    isInstalling: Boolean,
    showRetry: Boolean = false,
    onRetry: () -> Unit = {},
    retryLabel: String = "Try again",
    detail: String? = null,
    /** The status code in support form, drawn small under [detail]. */
    errorCode: String? = null,
    /** A stopped screen reporting a failure is drawn in red; a permission prompt is not. */
    isError: Boolean = false,
    /** The second button on a stopped screen; Back reaches it too. */
    onClose: () -> Unit = {},
    /** Adds a first, focused Clear space button. Storage failures only. */
    showClearSpace: Boolean = false,
    onClearSpace: () -> Unit = {},
) {
    val bg = Color(0xFF0E0E10)
    val accent = Color(0xFF378ADD)
    val accentSoft = Color(0xFF85B7EB)
    val track = Color(0x14FFFFFF)
    val textPrimary = Color(0xFFF5F5F2)
    val textMuted = Color(0xFF888780)
    val textFaint = Color(0xFF5F5E5A)

    // The failure palette. Red has to read on a TV across a room without
    // blooming, so the fill is a translucent red over the near-black ground and
    // the full-strength red is kept for the edge and the title.
    val errorTitle = Color(0xFFFF6B6B)
    val errorEdge = Color(0xFFE24B4A)
    val errorFill = Color(0x40E24B4A)
    val errorText = Color(0xFFFFE3E3)
    val errorCodeText = Color(0xFFF2A7A7)

    // Two states share this screen and they need different motion. A download
    // reports real fractions; an install cannot, because dex2oat gives nothing
    // back. Treating "no Content-Length" the same as installing is deliberate —
    // both mean "working, duration unknown".
    val indeterminate = isInstalling || progress < 0f

    // Nothing is in flight: something failed, or we are waiting on the user to
    // grant a permission. Either way the dial is a leftover and the words are
    // the content. showRetry is the honest signal for this because the button
    // exists exactly when, and only when, the screen has stopped moving.
    val stopped = showRetry

    // ANIMATED, because the raw value arrives in bursts. Progress is emitted per
    // 64KB chunk over a LAN, so the unsmoothed bar jumps in visible steps and
    // reads as stuttering rather than downloading.
    val shown by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "progress",
    )

    val motion = rememberInfiniteTransition(label = "dial")

    // The chasing arc for the unknown-duration case.
    val spin by motion.animateFloat(
        initialValue = -90f,
        targetValue = 270f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )

    // A slow pulse on the halo. The one moving thing on an otherwise still
    // screen, so a stalled download still looks alive rather than frozen.
    val halo by motion.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo",
    )

    Box(
        modifier = Modifier.fillMaxSize().background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(680.dp).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            // ── The dial ──────────────────────────────────────────────────────
            Box(
                modifier = Modifier.size(RING_DIAMETER),
                contentAlignment = Alignment.Center,
            ) {
                // Halo. Drawn behind everything and kept very low alpha: it is
                // depth, not decoration, and a TV panel crushes dark blues badly
                // enough that anything stronger bands.
                Box(
                    modifier = Modifier
                        .size(RING_DIAMETER)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    accent.copy(alpha = 0.16f * halo),
                                    Color.Transparent,
                                ),
                            )
                        )
                )

                Canvas(modifier = Modifier.size(RING_DIAMETER)) {
                    val stroke = RING_STROKE_WIDTH.toPx()
                    // Inset by half the stroke, otherwise a round cap is clipped
                    // by the canvas edge and the ring looks flattened at 0 and
                    // 90 degrees.
                    val arcTopLeft = Offset(stroke / 2f, stroke / 2f)
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val style = Stroke(width = stroke, cap = StrokeCap.Round)

                    drawArc(
                        color = track,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = style,
                    )

                    // A STOPPED SCREEN KEEPS THE BARE TRACK AND NOTHING ELSE.
                    // Both arcs claim work is happening: the chase says "busy"
                    // and a filled dial says "done". Above the words "could not
                    // be installed" either one contradicts the message, and the
                    // full ring left behind by an install that failed at 100%
                    // was the worse of the two.
                    if (stopped) {
                        // track only, drawn above
                    } else if (indeterminate) {
                        drawArc(
                            color = accent,
                            startAngle = spin,
                            sweepAngle = INDETERMINATE_SWEEP_DEG,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = style,
                        )
                    } else if (shown > 0f) {
                        // From twelve o'clock, clockwise, like every dial.
                        drawArc(
                            color = accent,
                            startAngle = -90f,
                            sweepAngle = shown * 360f,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = style,
                        )
                    }
                }

                Image(
                    painter = painterResource(R.drawable.blue_bee),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(RING_LOGO_SIZE),
                )
            }

            Spacer(Modifier.height(22.dp))

            // ── The number ────────────────────────────────────────────────────
            // Only when it means something. An install shows no figure at all
            // rather than a fake one; the spinning arc is the honest signal.
            //
            // AND NOT ON A STOPPED SCREEN. Whatever the bar last reached is stale
            // the moment something fails: an install that died at 100% and a
            // download that never started both leave a number behind, and neither
            // is what the reader needs. The status line takes over instead, at
            // the same weight the indeterminate state uses.
            if (!indeterminate && !stopped) {
                Text(
                    text = "${(shown * 100).toInt()}%",
                    color = textPrimary,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
            }

            // A stopped screen's title is the cause ("Not enough storage to
            // install the update"), so it is the largest text here, and red when
            // it reports a failure.
            Text(
                text = status,
                color = when {
                    stopped && isError -> errorTitle
                    indeterminate || stopped -> textPrimary
                    else -> textMuted
                },
                fontSize = when {
                    stopped -> 24.sp
                    indeterminate -> 19.sp
                    else -> 14.sp
                },
                fontWeight = when {
                    stopped -> FontWeight.SemiBold
                    indeterminate -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(14.dp))

            // ── Versions ──────────────────────────────────────────────────────
            // Quiet, and last of the status block. Useful for support, not
            // something a viewer needs to read.
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(fromVersion, color = textFaint, fontSize = 13.sp)
                Box(
                    modifier = Modifier
                        .size(width = 22.dp, height = 1.dp)
                        .background(Color(0x33FFFFFF))
                )
                Text(toVersion, color = accentSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            // ── THE REASON, BOXED AND IN RED ──────────────────────────────────
            //
            // This used to be 12sp faint grey at the very foot of the screen, under
            // the buttons -- on a Fire TV Stick that ran out of space the cause was
            // there and nobody saw it (2026-10-21). It now sits between the title
            // and the buttons, so it is read before anyone acts, at a size that
            // reads from a sofa. A permission prompt gets the same box in blue:
            // it is an instruction, not a failure.
            if (stopped && detail != null) {
                Spacer(Modifier.height(18.dp))
                val shape = RoundedCornerShape(12.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(if (isError) errorFill else accent.copy(alpha = 0.15f))
                        .border(2.dp, if (isError) errorEdge else accent.copy(alpha = 0.6f), shape)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = detail,
                        color = if (isError) errorText else textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        lineHeight = 25.sp,
                    )
                    if (errorCode != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = errorCode,
                            color = errorCodeText,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(Modifier.height(if (showRetry) 22.dp else 20.dp))

            // ── THE WAY OUT OF A FAILED REQUIRED INSTALL ──────────────────────
            //
            // Only rendered when an install has actually failed, so the normal
            // path is unchanged and there is nothing to focus while a download
            // runs. Before this existed the screen said "please try again" and
            // offered nothing to press: Back is blocked, proceedToApp is not
            // called on a required update, and there was no focusable element on
            // the screen at all. That is a brick, and missing one system dialog
            // was enough to reach it.
            //
            // TWO WAYS ON, AND ONLY TWO (owner's call, 2026-10-21): Try again, or
            // Close Application. Side by side so the pair costs one row of a
            // 540dp-tall TV layout; D-pad left and right move between them, and
            // Back is the second way to close (see onCreate).
            //
            // A STORAGE FAILURE GETS A THIRD, FIRST AND FOCUSED (2026-10-22):
            // Clear space opens the installed-apps page, because freeing room is
            // the only thing that changes the answer and Try again cannot.
            if (showRetry) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showClearSpace) {
                        UpdateScreenButton(
                            label = "Clear space",
                            primary = true,
                            takeFocus = true,
                            onClick = onClearSpace,
                        )
                    }
                    UpdateScreenButton(
                        label = retryLabel,
                        primary = true,
                        takeFocus = !showClearSpace,
                        onClick = onRetry,
                    )
                    UpdateScreenButton(
                        label = "Close Application",
                        primary = false,
                        takeFocus = false,
                        onClick = onClose,
                    )
                }
                Spacer(Modifier.height(18.dp))
            }

            // In-progress guidance only. A stopped screen carries its reason in
            // the box above instead, where it is read before the buttons.
            if (!stopped) {
                Text(
                    // HEDGED ON THE RESTART, deliberately. BlueHive cannot relaunch
                    // itself after being replaced — a receiver with no window is
                    // blocked — so the host does it, and on a bare host nobody does.
                    // The old copy promised an automatic restart outright, which is a
                    // promise this app is not in a position to keep.
                    text = if (isInstalling) {
                        "Installing. This can take a minute on first run.\nBlueHive will close while it finishes."
                    } else {
                        "Keep this screen open. BlueHive will close to install,\nthen reopen from your home screen."
                    },
                    color = textFaint,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

/**
 * One button on the stopped update screen.
 *
 * Remote-driven: OK and Enter press it, and focus is drawn as a fill because a TV
 * has no pointer to hover with. The primary button is the accent blue; the
 * secondary one is neutral, so the eye still lands on Try again first.
 *
 * TAKES FOCUS ON APPEARANCE when asked. On a remote there is no pointer to aim;
 * if nothing is focused, pressing OK does nothing and the screen looks dead. The
 * primary button has to be armed the moment it is drawn.
 */
@Composable
private fun UpdateScreenButton(
    label: String,
    primary: Boolean,
    takeFocus: Boolean,
    onClick: () -> Unit,
) {
    val accent = Color(0xFF378ADD)
    val accentSoft = Color(0xFF85B7EB)
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    if (takeFocus) {
        LaunchedEffect(Unit) {
            awaitFrame()
            runCatching { focus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.key == Key.DirectionCenter || e.key == Key.Enter ||
                     e.key == Key.NumPadEnter)
                ) { onClick(); true } else false
            }
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    primary && focused -> accent
                    primary -> Color(0x26378ADD)
                    focused -> Color(0xFFF5F5F2)
                    else -> Color(0x1AFFFFFF)
                }
            )
            .padding(horizontal = 30.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                focused -> Color(0xFF0E0E10)
                primary -> accentSoft
                else -> Color(0xFFBDBDB8)
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
