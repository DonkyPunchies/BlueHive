package com.example.bluehive

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.ProfileResponse
import com.example.bluehive.auth.HostEntitlementRelay
import com.example.bluehive.auth.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.bluehive.utilities.CloseApplicationHandler
import com.example.bluehive.utilities.CreateProfileButton
import com.example.bluehive.utilities.CreateProfileGlowConfig
import com.example.bluehive.utilities.ModularButton
import com.example.bluehive.utilities.ModularButtonAnimationConfig
import com.example.bluehive.utilities.ModularButtonColors
import com.example.bluehive.utilities.ModularButtonDimensions
import com.example.bluehive.utilities.ModularButtonGlowConfig
import com.example.bluehive.utilities.ModularButtonTextConfig
import com.example.bluehive.utilities.NetworkMonitor
import com.example.bluehive.utilities.ProfileButtonAnimationConfig
import com.example.bluehive.utilities.ProfileButtonDimensions
import com.example.bluehive.utilities.RetryOverlay
import com.example.bluehive.utilities.ConfirmationOverlay
import com.example.bluehive.utilities.ProfileEntryOverlay
import com.example.bluehive.utilities.AppTypography
import com.example.bluehive.auth.AuthActivity
import com.example.bluehive.auth.DeviceEventStream
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// ─────────────────────────────────────────────────────────────────────────────
//  Avatar resource map
//
//  Replaces getIdentifier() — compile-time safe, no reflection, no R8 issues.
//  Add any new avatar drawables here to keep the map in sync with res/drawable.
// ─────────────────────────────────────────────────────────────────────────────

private val AVATAR_DRAWABLE_MAP: Map<String, Int> = mapOf(
    "avatar1"  to R.drawable.avatar1,
    "avatar2"  to R.drawable.avatar2,
    "avatar3"  to R.drawable.avatar3,
    "avatar4"  to R.drawable.avatar4,
    "avatar5"  to R.drawable.avatar5,
    "avatar6"  to R.drawable.avatar6,
)

private fun resolveAvatarDrawable(resName: String?): Int =
    AVATAR_DRAWABLE_MAP[resName ?: ""] ?: R.drawable.avatar1

// ─────────────────────────────────────────────────────────────────────────────
//  ProfileScreenActivity
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "ProfileScreen"

/** Delay (ms) before launching ProfileCreationActivity after button press. */
private const val CREATE_BUTTON_LAUNCH_DELAY_MS = 700L

// ── Profile card grid config ──────────────────────────────────────────────────

val PROFILE_CARD_SIZE: Dp = 106.6.dp
val PROFILE_CARD_GAP: Dp = 19.5.dp
const val PROFILE_CARDS_PER_ROW = 4
val PROFILE_CARD_CORNER: Dp = 5.dp

val GRID_OFFSET_Y_TOP_ROW: Dp    = 185.dp
val GRID_OFFSET_Y_BOTTOM_ROW: Dp = 330.dp


// ── "Signed in as <email>" label ─────────────────────────────────────────────
// Position of the top-left account label. THESE ARE THE TWO KNOBS — the label is
// absolutely positioned inside the same 960x540 dp design space everything else
// on this screen uses (see `screenWidth` in ProfileScreen), so smaller Y = higher
// on screen and smaller X = further left.
//
// Y was 76.dp when the label was added; lowered to 40.dp on request to sit it
// nearer the top edge, clear of the title block.
val SIGNED_IN_LABEL_X: Dp = 45.dp
val SIGNED_IN_LABEL_Y: Dp = 50.dp

/** Font size of that label. Small on purpose — it is a reference, not a heading. */
val SIGNED_IN_LABEL_FONT_SIZE = 7.sp

/** Muted blue-grey. Readable against the dark background without pulling focus. */
val SIGNED_IN_LABEL_COLOR = Color(0xFF3D5CA8)


// ── Profile card focus glow config ───────────────────────────────────────────
val PROFILE_CARD_GLOW_WIDTH: Dp    = 152.5.dp
val PROFILE_CARD_GLOW_HEIGHT: Dp   = 152.dp
val PROFILE_CARD_GLOW_OFFSET_X: Dp = (-23).dp
val PROFILE_CARD_GLOW_OFFSET_Y: Dp = (-23).dp

// ── Profile name label config ─────────────────────────────────────────────────
val PROFILE_NAME_COLOR: Color         = Color(0xFF555566)
val PROFILE_NAME_FOCUSED_COLOR: Color = Color.White
const val PROFILE_NAME_FONT_SIZE_SP   = 11f
val PROFILE_NAME_PADDING_TOP: Dp      = 5.dp
val PROFILE_NAME_HEIGHT: Dp           = 22.dp

// ── Profile card press animation ─────────────────────────────────────────────
//
// ONE CONTINUOUS DEPARTURE, NOT A BOUNCE. Pressing a profile shrinks the avatar,
// drains its colour to grey, and fades the name and glow — all in the same
// direction, all the way down. The loading screen appears the moment it reaches
// its smallest, so the card hands off to the next screen instead of springing
// back to a state the user has already left.
//
// It used to dip and return, which meant the card ended the gesture looking
// exactly as it started; the press read as a twitch rather than as a commitment.
//
// The load is deferred to the end on purpose. Fired on the press frame, the next
// screen covered the animation before any of it was visible.
const val PROFILE_FOCUS_SCALE  = 1.06f
const val PROFILE_PRESS_SCALE  = 0.88f

/**
 * The whole gesture, and therefore how long until the loading screen appears.
 *
 * Holds the ~1s total that the previous dip-and-return version had — the length
 * was right, the return leg was not — so the press feels the same length and
 * simply stops resolving backwards.
 */
const val PROFILE_PRESS_MS     = 1050
const val PROFILE_NAME_FADE_MS = 500

/**
 * How grey the avatar ends up. 0f is fully greyscale.
 *
 * Colour drains over exactly the same clock as the shrink and does not come
 * back, so size and colour read as one gesture rather than as two effects that
 * happen to overlap.
 */
const val PROFILE_PRESS_SATURATION = 0f

/**
 * The focus glow fades out with the press and does NOT come back.
 *
 * One-way, like the name. The glow says "this is where you are"; once the press
 * is committed and the screen is leaving, re-lighting it as the card springs
 * back would say the selection had been cancelled.
 */
const val PROFILE_GLOW_FADE_MS = 450

class ProfileScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            ProfileScreen(
                onProfileSelected = { profileId, displayName, avatarResId ->
                    goHome(profileId, displayName, avatarResId)
                },
                onCreateProfile = { launcher ->
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        launcher.launch(
                            Intent(this, ProfileCreationActivity::class.java)
                        )
                    }, CREATE_BUTTON_LAUNCH_DELAY_MS)
                },
                onManageProfiles = { launcher ->
                    launcher.launch(Intent(this, ProfileManageActivity::class.java))
                },
            )
        }
    }

    private fun goHome(profileId: Int, displayName: String, avatarResId: Int) {
        Log.d(TAG, "Profile selected → id=$profileId name=$displayName")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ApiClient.bluehiveApi.selectProfile(profileId)
                Log.d(TAG, "✅ selectProfile stamped for id=$profileId")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ selectProfile failed (non-fatal): ${e.message}")
            }
        }
        // Persist the selected profile ID so the next cold-start warm-up can
        // start the personalized Netflix prefetch immediately — without waiting
        // on the profiles-list API to resolve — by reading this local value.
        SessionManager.get().setLastProfileId(profileId)
        // Offload the Continue-Watching list + its episode thumbnails to NOW — they warm
        // during the "Getting X ready" loading window (in an app-scoped job that outlives
        // this finishing activity), so the home screen's CW section renders from cache
        // instead of fetching + loading images on arrival.
        (applicationContext as? BlueHiveApplication)?.prefetchContinueWatching(profileId)
        startActivity(
            Intent(this, HomeScreenCompose::class.java).apply {
                putExtra("PROFILE_ID",        profileId)
                putExtra("PROFILE_NAME",      displayName)
                putExtra("PROFILE_AVATAR_ID", avatarResId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ProfileScreen(
    onProfileSelected: (profileId: Int, displayName: String, avatarResId: Int) -> Unit,
    onCreateProfile:   (androidx.activity.result.ActivityResultLauncher<Intent>) -> Unit,
    onManageProfiles:  (androidx.activity.result.ActivityResultLauncher<Intent>) -> Unit = {},
) {

    val scope   = rememberCoroutineScope()
    val context = LocalContext.current

    val profiles        = remember { mutableStateListOf<ProfileResponse>() }
    var isLoadingGrid   by remember { mutableStateOf(true) }
    var showRetryBanner by remember { mutableStateOf(false) }

    suspend fun reloadProfiles() {
        isLoadingGrid   = true
        showRetryBanner = false
        try {
            val fetched = ApiClient.bluehiveApi.listProfiles()
            Log.d("ProfileScreen", "✅ Fetched ${fetched.size} profiles: ${fetched.map { it.display_name }}")

            // LOCAL-ONLY ordering: the profile last selected ON THIS DEVICE leads
            // the grid. Reads SessionManager.lastProfileId — already persisted by
            // goHome() on every selection — so there's no backend involvement and
            // the set of profiles is untouched. sortedByDescending on a Boolean is
            // a STABLE sort: the match floats to slot 1, everyone else keeps the
            // server's order. First launch (lastProfileId == -1) matches nothing
            // → server order unchanged. Bonus: the grid auto-focuses slot 1, so
            // the remote's OK button now means "continue as me."
            val lastId  = SessionManager.get().lastProfileId
            val ordered = fetched.sortedByDescending { it.id == lastId }

            profiles.clear()
            profiles.addAll(ordered)
        } catch (e: Exception) {
            Log.e("ProfileScreen", "❌ Failed to load profiles: ${e.message}")
            // Only surface the retry banner when the device is genuinely offline.
            // Server errors leave the grid empty — same behaviour as before.
            if (!NetworkMonitor.isOnline(context)) showRetryBanner = true
        } finally {
            isLoadingGrid = false
        }
    }

    LaunchedEffect(Unit) {
        reloadProfiles()
    }

    // Auto-recovery: if the network returns while the user is stuck on the
    // profile screen, hide the banner and reload without them doing anything.
    DisposableEffect(Unit) {
        val cb = NetworkMonitor.registerOnlineCallback(context) {
            scope.launch {
                if (showRetryBanner) reloadProfiles()
            }
        }
        onDispose { NetworkMonitor.unregister(context, cb) }
    }

    val creationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data         = result.data ?: return@rememberLauncherForActivityResult
            val newId        = data.getIntExtra(EXTRA_NEW_PROFILE_ID, -1)
            val newName      = data.getStringExtra(EXTRA_NEW_PROFILE_NAME) ?: ""
            val newAvatarRes = data.getStringExtra(EXTRA_NEW_AVATAR_RES_NAME) ?: ""
            if (newId != -1 && newName.isNotEmpty()) {
                profiles.add(
                    ProfileResponse(
                        id                   = newId,
                        display_name         = newName,
                        avatar_url           = newAvatarRes,
                        slot                 = profiles.size + 1,
                        has_pin              = false,
                        created_by_device_id = null,
                        last_login_at        = "",
                        created_at           = "",
                        updated_at           = "",
                    )
                )
            }
        }
    }

    val manageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Re-fetch regardless of result code — names/avatars may have changed
        scope.launch { reloadProfiles() }
    }

    val focusRequesters       = remember { mutableStateListOf<FocusRequester>() }
    val createFocusRequester  = remember { FocusRequester() }
    val manageFocusRequester  = remember { FocusRequester() }
    val signOutFocusRequester = remember { FocusRequester() }

    // ── Sign out ──────────────────────────────────────────────────────────────
    // Read ONCE into a remembered value rather than on each recomposition. The
    // confirmation overlay names this address, and performSignOut clears the
    // session underneath it — so reading live would blank the text mid-dismiss.
    val signedInEmail = remember { SessionManager.get().email }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    // ── Profile-entry overlay ────────────────────────────────────────────────
    // Set when a card's press animation finishes. Covers the frozen, shrunken
    // card for the ~1.4s HomeScreenCompose needs to draw its first frame — see
    // ProfileEntryOverlay. Non-null means "this profile is being entered"; it is
    // never cleared, because the screen is on its way out.
    var enteringProfileName by remember { mutableStateOf<String?>(null) }

    // ── Input lock ───────────────────────────────────────────────────────────
    // Engaged the moment a profile is PRESSED, not when the overlay appears a
    // second later. Between those two points the card is visibly animating away
    // while the grid underneath is still fully live — without this you can walk
    // focus onto another profile, or press it, and end up entering two.
    //
    // Swallowing keys rather than moving focus is deliberate. Parking focus
    // elsewhere would drop isFocused on the pressed card, and its unfocused tint
    // would fade in over the top of the shrink animation.
    var inputLocked by remember { mutableStateOf(false) }
    val activity = LocalActivity.current






    // ── Focus parking ─────────────────────────────────────────────────────────
    // Holds focus on an invisible spot during every load so the Create button
    // doesn't flash-grab it before the profile cards exist. Re-engages on reload
    // (e.g. returning from Manage Profiles). The targeting effect drops it once
    // focus lands on a real target.
    val parkingFocusRequester = remember { FocusRequester() }
    var focusParked by remember { mutableStateOf(true) }

    // ── Navigation-sound arming ───────────────────────────────────────────────
    // The sound is for MOVING between profiles, so the screen's own opening
    // focus must not trigger it — otherwise every visit to the picker, and every
    // return from Manage Profiles, announces itself with a swap that never
    // happened. Armed one frame AFTER the initial requestFocus lands, which is
    // what guarantees that focus event has already been delivered.
    var navSoundArmed by remember { mutableStateOf(false) }

    LaunchedEffect(isLoadingGrid) {
        if (isLoadingGrid) {
            navSoundArmed = false
            focusParked = true
            kotlinx.coroutines.android.awaitFrame()
            if (focusParked) parkingFocusRequester.requestFocus()
        }
    }



    // AFTER
    LaunchedEffect(isLoadingGrid, profiles.size) {
        if (isLoadingGrid) return@LaunchedEffect
        navSoundArmed = false

        if (profiles.isEmpty()) {
            createFocusRequester.requestFocus()
            focusParked = false          // release the parking spot
            // Same one-frame wait as below: with no profiles the screen opens
            // focused on Create, and that landing is not a navigation.
            kotlinx.coroutines.android.awaitFrame()
            navSoundArmed = true
            return@LaunchedEffect
        }

        while (focusRequesters.isEmpty()) {
            kotlinx.coroutines.android.awaitFrame()
        }
        kotlinx.coroutines.android.awaitFrame()
        focusRequesters[0].requestFocus()
        focusParked = false              // release the parking spot
        // One more frame, so the focus event from the line above has already
        // been delivered and cannot be mistaken for a swap.
        kotlinx.coroutines.android.awaitFrame()
        navSoundArmed = true
    }




    // Button stays on row 1 until there are 5+ profiles (slots 0-4 = row 1 including button)
    val createButtonInRow1 = profiles.size <= PROFILE_CARDS_PER_ROW
    val createCol          = if (createButtonInRow1) profiles.size.coerceAtMost(PROFILE_CARDS_PER_ROW)
    else profiles.size % PROFILE_CARDS_PER_ROW
    val createRow          = if (createButtonInRow1) 0 else profiles.size / PROFILE_CARDS_PER_ROW

    // Total items in the first row = profiles in row 1 + create button position
    val firstRowProfileCount = profiles.size.coerceAtMost(PROFILE_CARDS_PER_ROW)
    val createButtonWidth    = 77.25.dp

    // Row 1 width always includes the create button when it's on row 1
    val totalRowWidth = if (createButtonInRow1) {
        (firstRowProfileCount * (PROFILE_CARD_SIZE + PROFILE_CARD_GAP)) + 13.dp + createButtonWidth
    } else {
        firstRowProfileCount * (PROFILE_CARD_SIZE + PROFILE_CARD_GAP) - PROFILE_CARD_GAP
    }

    val screenWidth  = 960.dp
    val dynamicGridX = (screenWidth - totalRowWidth) / 2

    val createButtonX = dynamicGridX + (createCol * (PROFILE_CARD_SIZE + PROFILE_CARD_GAP)) + 18.dp
    val createButtonY = (if (createRow == 0) GRID_OFFSET_Y_TOP_ROW else GRID_OFFSET_Y_BOTTOM_ROW) +
            (PROFILE_CARD_SIZE / 2) - (77.25.dp / 2)

    // AFTER
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Returning true consumes the event before it reaches the focused
            // child, so nothing on this screen responds while a profile is
            // being entered.
            .onPreviewKeyEvent { inputLocked }
    ) {

        // Background
        Image(
            painter            = painterResource(id = R.drawable.profile_screen),
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )

        // ── "Signed in as …" ──────────────────────────────────────────────────
        // Top-left, deliberately quiet. BlueHive keeps you signed in indefinitely
        // (the refresh token slides), so without this there is nothing anywhere in
        // the app that tells you WHICH account a TV is on — which matters as soon
        // as a household has more than one, and matters again the moment someone
        // presses Sign Out and has to get back in through that address.
        //
        // Not focusable: it is a label, and putting it in the D-pad path would
        // make every trip to the profile grid pass through dead weight.
        if (!signedInEmail.isNullOrBlank()) {
            Text(
                text       = "Signed in as $signedInEmail",
                color      = SIGNED_IN_LABEL_COLOR,
                fontFamily = AppTypography.interBold,
                fontSize   = SIGNED_IN_LABEL_FONT_SIZE,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = SIGNED_IN_LABEL_X, y = SIGNED_IN_LABEL_Y),
            )
        }

        // Invisible focus parking spot — declared first so it's the default focus
        // target during load, holding focus until the grid is ready. canFocus flips
        // to false afterward so D-pad navigation can never land back on it.
        Box(
            modifier = Modifier
                .size(1.dp)
                .focusRequester(parkingFocusRequester)
                .focusProperties { canFocus = focusParked }
                .focusable()
        )

        // Offline retry banner — shown when the profile list failed to load
        // because the device has no network. Auto-dismisses when connectivity
        // returns. Reuses the same overlay as the home screen for consistency.
        if (showRetryBanner) {
            RetryOverlay(
                message = "Couldn't load profiles.\nCheck your connection and try again.",
                onRetry = {
                    scope.launch {
                        if (NetworkMonitor.isOnline(context)) {
                            reloadProfiles()
                        }
                        // Still offline → banner stays up, user can press again.
                    }
                }
            )
        }

        // Profile grid
        if (!isLoadingGrid) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = dynamicGridX, y = GRID_OFFSET_Y_TOP_ROW),
            ) {
                profiles.forEachIndexed { index, profile ->
                    val row = index / PROFILE_CARDS_PER_ROW
                    val col = index % PROFILE_CARDS_PER_ROW

                    val cardX = col * (PROFILE_CARD_SIZE + PROFILE_CARD_GAP)
                    val cardY = if (row == 0) 0.dp
                    else (GRID_OFFSET_Y_BOTTOM_ROW - GRID_OFFSET_Y_TOP_ROW)

                    // Compile-time safe — no reflection, no getIdentifier warning
                    val avatarResId = remember(profile.avatar_url) {
                        resolveAvatarDrawable(profile.avatar_url)
                    }

                    val requester = if (index < focusRequesters.size) {
                        focusRequesters[index]
                    } else {
                        remember { FocusRequester() }.also { focusRequesters.add(it) }
                    }

                    ProfileCard(
                        profile        = profile,
                        avatarResId    = avatarResId,
                        focusRequester = requester,
                        modifier       = Modifier.offset(x = cardX, y = cardY),
                        // Fires on the key press itself, ahead of the animation.
                        onPressStart   = { inputLocked = true },
                        onClick        = {
                            // Raised BEFORE the activity launch, so the loading
                            // screen is already up when the press animation
                            // ends rather than 1.4s later.
                            enteringProfileName = profile.display_name
                            onProfileSelected(profile.id, profile.display_name, avatarResId)
                        },
                        // No edge check is needed: at the leftmost card a Left
                        // press moves focus nowhere, so no card ever gains it and
                        // nothing plays. The silence at the ends is the absence
                        // of an event, not a special case.
                        onFocusGained  = {
                            if (navSoundArmed) BlueHiveApplication.playProfileSwapSound()
                        },
                    )
                }
            }
        }

        // ── Create Profile button ─────────────────────────────────────────────
        if (profiles.size < 8) CreateProfileButton(
            focusRequester = createFocusRequester,
            onClick        = { onCreateProfile(creationLauncher) },
            onFocusGained  = { if (navSoundArmed) BlueHiveApplication.playNavClickSound() },
            modifier       = Modifier
                .align(Alignment.TopStart)
                // Sign Out now sits between Create and Manage, so right-from-Create
                // lands there first rather than skipping it.
                .offset(x = createButtonX, y = createButtonY)
                .focusProperties { right = signOutFocusRequester },
            dimensions = ProfileButtonDimensions(
                imageSize          = 77.25.dp,
                imageCornerRadius  = 6.dp,
                plinthWidth        = 77.25.dp,
                plinthHeight       = 60.dp,
                plinthOffsetX      = 0.dp,
                plinthOffsetY      = (-54).dp,
                plinthCornerRadius = 15f,
            ),
            glowConfig = CreateProfileGlowConfig(
                width                 = 109.3.dp,
                height                = 90.dp,
                offsetX               = (-15.8).dp,
                offsetY               = 8.dp,
                fadeOutDurationMillis = 200,
                fadeOutDelayMillis    = 200,
            ),
            animConfig = ProfileButtonAnimationConfig(
                pressOffset           = 5.dp,
                durationMillis        = 100,
                bounceBackDelayMillis = 80L,
            ),
        )

        // ── Sign Out button ───────────────────────────────────────────────────
        // Sits immediately left of Manage Profiles on the same row: x 678..780,
        // then a 10dp gap before Manage at 790. Same dimensions and colours as
        // Manage on purpose — the row reads as one control strip, and the red
        // "this is destructive" signal belongs on the confirm button inside the
        // overlay, where the user is actually deciding, not out here where it
        // would just shout at them every time they pick a profile.
        ModularButton(
            textConfig = ModularButtonTextConfig(
                text     = "Sign Out",
                fontSize = 10f,
            ),
            isToggleable   = false,
            fontFamily     = AppTypography.interBold,
            focusRequester = signOutFocusRequester,
            onFocusGained  = { if (navSoundArmed) BlueHiveApplication.playNavClickSound() },
            onClick        = { showSignOutConfirm = true },
            modifier       = Modifier
                .align(Alignment.TopStart)
                .offset(x = 678.dp, y = 445.dp)
                .focusProperties {
                    // Inherits the left-target Manage used to own; Manage now
                    // points here instead.
                    left  = if (profiles.size < 8) createFocusRequester
                            else focusRequesters.getOrNull(7) ?: FocusRequester.Cancel
                    right = manageFocusRequester
                },
            dimensions = ModularButtonDimensions(
                mainWidth          = 102.dp,
                mainHeight         = 27.dp,
                mainYOffset        = 6.5.dp,
                secondWidth        = 102.dp,
                secondHeight       = 12.dp,
                secondYOffset      = 26.dp,
                mainCornerRadius   = 7f,
                secondCornerRadius = 8f,
                shadowHeight       = 12.dp,
                glowWidth          = 117.dp,
                glowHeight         = 41.dp,
            ),
            colors = ModularButtonColors(
                mainDefault   = Color(0xFF1E2439),
                mainToggled   = Color(0xFF2644A6),
                mainFocused   = Color(0xFF2644A6),
                secondDefault = Color(0xFF191923),
                secondFocused = Color(0xFF282C57),
                textFocused          = Color(0xFFDADADA),
                textUnfocused        = Color(0xFF868686),
                textUnfocusedToggled = Color(0xFF868686),
                textToggledFocus     = Color(0xFFDADADA),
            ),
            glowConfig = ModularButtonGlowConfig(
                enabled    = true,
                defaultRes = R.drawable.button_focus_wide_glow,
                offsetX    = (-7.5).dp,
                offsetY    = (7).dp,
                cornerRadius          = 100.dp,
                fadeOutDurationMillis = 200,
                fadeInDurationMillis  = 400,
            ),
            animationConfig = ModularButtonAnimationConfig(
                pressOffset           = 3.5.dp,
                textOffsetDefault     = 7.9.dp,
                textOffsetPressed     = 9.9.dp,
                durationMillis        = 110,
                bounceBackDelayMillis = 200,
            ),
        )

        // ── Manage Profiles button ────────────────────────────────────────────
        ModularButton(
            textConfig = ModularButtonTextConfig(
                text     = "Manage Profiles",
                fontSize = 10f,
            ),
            isToggleable   = false,
            fontFamily     = com.example.bluehive.utilities.AppTypography.interBold,
            focusRequester = manageFocusRequester,
            onFocusGained  = { if (navSoundArmed) BlueHiveApplication.playNavClickSound() },
            onClick        = { onManageProfiles(manageLauncher) },
            modifier       = Modifier
                .align(Alignment.TopStart)
                .offset(x = 790.dp, y = 445.dp)
                .focusProperties { left = signOutFocusRequester },
            dimensions = ModularButtonDimensions(
                mainWidth          = 102.dp,
                mainHeight         = 27.dp,
                mainYOffset        = 6.5.dp,
                secondWidth        = 102.dp,
                secondHeight       = 12.dp,
                secondYOffset      = 26.dp,
                mainCornerRadius   = 7f,
                secondCornerRadius = 8f,
                shadowHeight       = 12.dp,
                glowWidth          = 117.dp,
                glowHeight         = 41.dp,
            ),
            colors = ModularButtonColors(
                mainDefault   = Color(0xFF1E2439),
                mainToggled   = Color(0xFF2644A6),
                mainFocused   = Color(0xFF2644A6),
                secondDefault = Color(0xFF191923),
                secondFocused = Color(0xFF282C57),
                textFocused          = Color(0xFFDADADA),
                textUnfocused        = Color(0xFF868686),
                textUnfocusedToggled = Color(0xFF868686),
                textToggledFocus     = Color(0xFFDADADA),
            ),
            glowConfig = ModularButtonGlowConfig(
                enabled    = true,
                defaultRes = R.drawable.button_focus_wide_glow,
                offsetX    = (-7.5).dp,
                offsetY    = (7).dp,
                cornerRadius          = 100.dp,
                fadeOutDurationMillis = 200,
                fadeInDurationMillis  = 400,
            ),
            animationConfig = ModularButtonAnimationConfig(
                pressOffset           = 3.5.dp,
                textOffsetDefault     = 7.9.dp,
                textOffsetPressed     = 9.9.dp,
                durationMillis        = 110,
                bounceBackDelayMillis = 200,
            ),
        )

        // Intercepts the hardware back button and shows the "Close app?"
        // confirmation overlay instead of immediately closing the activity.
        // All logic lives in CloseApplication.kt — nothing to maintain here.
        CloseApplicationHandler()

        // ── Sign-out confirmation ─────────────────────────────────────────────
        // DECLARED AFTER CloseApplicationHandler, and both lines below depend on
        // that ordering:
        //
        //   Drawing — later in a Box means on top, so this overlay is never
        //   painted underneath the close-app one.
        //
        //   Back button — CloseApplicationHandler registers an ALWAYS-enabled
        //   BackHandler. Compose dispatches back to the most recently registered
        //   enabled handler first, so registering ours afterwards and gating it on
        //   showSignOutConfirm gives exactly the right split: while this overlay is
        //   up, back dismisses it; the rest of the time back falls through to the
        //   close-app prompt as before. Without the gate we would swallow every
        //   back press on this screen.
        // TOPMOST, above every other overlay here: once a profile is being
        // entered, nothing else on this screen is actionable.
        enteringProfileName?.let { ProfileEntryOverlay(it) }

        // Back counts as navigation. Registered BEFORE the sign-out handler so
        // it wins while locked; leaving mid-commit would strand the selection.
        BackHandler(enabled = inputLocked) { /* swallowed */ }

        BackHandler(enabled = showSignOutConfirm) { showSignOutConfirm = false }

        if (showSignOutConfirm) {
            ConfirmationOverlay(
                // Naming the address is the entire point of the warning. Sign-out
                // is cheap to trigger from a remote and expensive to undo if the
                // account was created with an address the person at the TV cannot
                // actually open — a plausible mistake in a shared household, and
                // one they cannot discover AFTER signing out, because the address
                // is gone from the screen at that moment.
                message     = "Sign out of BlueHive?\n\n" +
                              "Signing back in will require access to\n" +
                              (signedInEmail ?: "your account email"),
                confirmText = "Yes, Sign Out",
                cancelText  = "No, Cancel",
                onConfirm   = {
                    showSignOutConfirm = false
                    activity?.let { performSignOut(it) }
                },
                onCancel    = { showSignOutConfirm = false },
            )
        }

    }
}


/**
 * performSignOut
 *
 * Tears the session down in an order that matters, then returns to the front door.
 *
 *  1. DeviceEventStream.stop() — NOT stopAndMarkExited(). That variant sets a
 *     process-lifetime flag which blocks start() until the process dies, and it is
 *     right for closing the app. It is wrong here: signing back in happens in this
 *     SAME process, so marking the stream permanently exited would leave the next
 *     session with no event stream and no obvious reason why.
 *
 *  2. endSession() BEFORE clearSession(). The call is authenticated, so clearing
 *     the token first would make it 401 and silently leave the device holding its
 *     slot until the reaper expires it ~15 minutes later. The 2s timeout means a
 *     dead network cannot trap the user on a screen they asked to leave, and the
 *     reaper remains the backstop if the ping is lost.
 *
 *  3. clearSession() in the finally, so the local session is destroyed whether or
 *     not the server was reachable. A sign-out that visibly fails to sign you out
 *     is worse than one that leaves a stale row for the reaper to collect.
 *
 *  4. CLEAR_TASK into AuthActivity so nothing behind this screen survives — back
 *     from the sign-in page must not walk into a profile grid with no session.
 *
 * KNOWN GAP: there is no server-side revoke endpoint yet, so the refresh token
 * stays valid until it expires even though it has been wiped from this device.
 * That is acceptable while the token never leaves the TV, but a real
 * /auth/logout that deletes the bluehive.refresh_tokens row is the correct fix.
 */
private fun performSignOut(activity: Activity) {
    DeviceEventStream.stop()

    CoroutineScope(Dispatchers.IO).launch {
        try {
            withTimeout(2_000) { ApiClient.bluehiveApi.endSession() }
        } catch (e: Exception) {
            Log.w("ProfileScreen", "sign-out end-session ping failed (reaper backstop): ${e.message}")
        } finally {
            SessionManager.get().clearSession()
            // The host standing belongs to the account that just left, not to the
            // device. Without this, signing out of a locked account and signing
            // in as somebody else inherits the lock: the next person is held
            // behind a "connection trouble" screen over a subscription that was
            // never theirs. Cleared here rather than at sign-IN so the device is
            // never carrying a stale verdict between accounts.
            HostEntitlementRelay.clearStanding(activity)
            withContext(Dispatchers.Main) {
                activity.startActivity(
                    Intent(activity, AuthActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                )
                activity.finish()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  ProfileCard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ProfileCard(
    profile:        ProfileResponse,
    avatarResId:    Int,
    focusRequester: FocusRequester,
    onClick:        () -> Unit,
    modifier:       Modifier = Modifier,
    // Fired only on a GAIN, never on a loss, and never on a recomposition that
    // reports the same state. The caller uses it for the profile-swap sound.
    onFocusGained:  () -> Unit = {},
    // Fired on the key press, BEFORE the animation starts — as opposed to
    // onClick, which fires when it finishes. The caller locks input here.
    onPressStart:   () -> Unit = {},
) {
    var isFocused by remember { mutableStateOf(false) }

    // ── Press state ──────────────────────────────────────────────────────────
    // BOTH FLAGS ARE ONE-WAY. `pressed` drives the shrink and the colour drain,
    // `leaving` the name and glow fade. Nothing here reverses: the card is on its
    // way off screen, and every part of it should be heading the same direction.
    var pressed      by remember { mutableStateOf(false) }
    var leaving      by remember { mutableStateOf(false) }
    var clickPending by remember { mutableStateOf(false) }
    val pressScope = rememberCoroutineScope()

    val glowAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue   = if (isFocused && !leaving) 1f else 0f,
        animationSpec = tween(
            durationMillis = when {
                leaving   -> PROFILE_GLOW_FADE_MS
                isFocused -> 150
                else      -> 100
            }
        ),
        label = "profileCardGlow",
    )

    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = when {
            pressed   -> PROFILE_PRESS_SCALE
            isFocused -> PROFILE_FOCUS_SCALE
            else      -> 1f
        },
        animationSpec = tween(durationMillis = PROFILE_PRESS_MS),
        label = "profileCardScale",
    )

    val nameAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue   = if (leaving) 0f else 1f,
        animationSpec = tween(durationMillis = PROFILE_NAME_FADE_MS),
        label         = "profileNameAlpha",
    )

    // Deliberately the SAME clock as the scale, so colour and size move as one
    // gesture instead of two effects that merely overlap.
    val saturation by androidx.compose.animation.core.animateFloatAsState(
        targetValue   = if (pressed) PROFILE_PRESS_SATURATION else 1f,
        animationSpec = tween(durationMillis = PROFILE_PRESS_MS),
        label         = "profileCardSaturation",
    )

    // NULL AT REST, which is the point. A ColorFilter allocates a matrix and
    // costs a GPU pass; at full saturation there is nothing to correct, so the
    // idle card — every card, all the time — pays neither. Only the pressed card
    // allocates, and only for the half-second the animation runs.
    val avatarColorFilter = if (saturation >= 0.999f) null else {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
    }

    /**
     * Run the press animation, then load the profile.
     *
     * The click sound is immediate while the load is not: feedback has to be
     * instant even when the action is deferred, or the press feels ignored and
     * gets repeated.
     *
     * Guarded against a second press. Without it an impatient double-tap would
     * launch the profile twice, and the animation would restart mid-flight.
     */
    fun handlePress() {
        if (clickPending) return
        clickPending = true
        onPressStart()
        BlueHiveApplication.playClickSound()
        leaving = true
        pressed    = true
        pressScope.launch {
            // Hands off exactly as the card reaches its smallest — `pressed` is
            // never cleared, so the avatar is still at PROFILE_PRESS_SCALE and
            // fully grey underneath the loading screen.
            kotlinx.coroutines.delay(PROFILE_PRESS_MS.toLong())
            onClick()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        // Outer Box — avatar is the layout anchor, glow overflows freely
        Box(
            modifier = Modifier
                .size(PROFILE_CARD_SIZE)
                .wrapContentSize(align = Alignment.TopStart, unbounded = true),
        ) {
            // ── Glow — behind avatar, only visible when focused ───────────────
            Image(
                painter            = painterResource(id = R.drawable.avatar_button_glow),
                contentDescription = null,
                contentScale       = ContentScale.FillBounds,
                modifier           = Modifier
                    .size(width = PROFILE_CARD_GLOW_WIDTH, height = PROFILE_CARD_GLOW_HEIGHT)
                    .offset(x = PROFILE_CARD_GLOW_OFFSET_X, y = PROFILE_CARD_GLOW_OFFSET_Y)
                    .alpha(glowAlpha),
            )

            val avatarAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue   = if (isFocused) 1f else 1.0f,
                animationSpec = tween(durationMillis = 200),
                label         = "profileCardAvatarAlpha",
            )

            val tintAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue   = if (isFocused) 0f else 0.7f,
                animationSpec = tween(durationMillis = 200),
                label         = "profileCardTint",
            )

            Box(
                modifier = Modifier
                    .size(PROFILE_CARD_SIZE)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha  = avatarAlpha
                    }
                    .then(
                        if (!isFocused) Modifier.border(
                            width = 1.5.dp,
                            color = Color(0xB9555566),
                            shape = RoundedCornerShape(PROFILE_CARD_CORNER),
                        ) else Modifier
                    )
                    .clip(RoundedCornerShape(PROFILE_CARD_CORNER))
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        // Compare BEFORE assigning: onFocusChanged also fires on
                        // recompositions that carry the same state, and a bare
                        // "if (it.isFocused) play()" would re-fire the sound on
                        // every one of them while the card simply sat there.
                        val gained = it.isFocused && !isFocused
                        isFocused = it.isFocused
                        if (gained) onFocusGained()
                    }
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.DirectionCenter ||
                                    event.key == Key.Enter       ||
                                    event.key == Key.NumPadEnter)
                        ) {
                            handlePress()
                            true
                        } else false
                    },
            ) {
                Image(
                    painter            = painterResource(id = avatarResId),
                    contentDescription = profile.display_name,
                    contentScale       = ContentScale.Crop,
                    colorFilter        = avatarColorFilter,
                    modifier           = Modifier.matchParentSize(),
                )
                // Grey tint overlay — fades in when unfocused
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color(0xFF000000).copy(alpha = tintAlpha))
                )
            }
        }

        // ── Profile name label ────────────────────────────────────────────────
        val PROFILE_NAME_OFFSET_Y_UNFOCUSED: Dp = 0.dp
        val PROFILE_NAME_OFFSET_Y_FOCUSED: Dp   = 5.dp

        val nameOffsetY by androidx.compose.animation.core.animateDpAsState(
            targetValue   = if (isFocused) PROFILE_NAME_OFFSET_Y_FOCUSED else PROFILE_NAME_OFFSET_Y_UNFOCUSED,
            animationSpec = tween(durationMillis = 200),
            label         = "profileNameOffsetY",
        )

        Text(
            text       = profile.display_name,
            color      = if (isFocused) PROFILE_NAME_FOCUSED_COLOR else PROFILE_NAME_COLOR,
            fontSize   = PROFILE_NAME_FONT_SIZE_SP.sp,
            fontFamily = com.example.bluehive.utilities.AppTypography.interBold,
            textAlign  = TextAlign.Center,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier
                .padding(top = PROFILE_NAME_PADDING_TOP)
                .offset(y = nameOffsetY)
                .alpha(nameAlpha)
                .size(width = PROFILE_CARD_SIZE, height = PROFILE_NAME_HEIGHT),
        )
    }
}


//@Preview(
//    showBackground = true,
//    widthDp = 960,
//    heightDp = 540
//)
//@Composable
//fun ProfileScreenPreview() {
//    ProfileScreen(
//        onProfileSelected = { _, _ -> },
//        onCreateProfile   = { _ -> },
//        onManageProfiles  = {},
//    )
//}