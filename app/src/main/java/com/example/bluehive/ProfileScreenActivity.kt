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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
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

    val focusRequesters      = remember { mutableStateListOf<FocusRequester>() }
    val createFocusRequester = remember { FocusRequester() }
    val manageFocusRequester = remember { FocusRequester() }






    // ── Focus parking ─────────────────────────────────────────────────────────
    // Holds focus on an invisible spot during every load so the Create button
    // doesn't flash-grab it before the profile cards exist. Re-engages on reload
    // (e.g. returning from Manage Profiles). The targeting effect drops it once
    // focus lands on a real target.
    val parkingFocusRequester = remember { FocusRequester() }
    var focusParked by remember { mutableStateOf(true) }

    LaunchedEffect(isLoadingGrid) {
        if (isLoadingGrid) {
            focusParked = true
            kotlinx.coroutines.android.awaitFrame()
            if (focusParked) parkingFocusRequester.requestFocus()
        }
    }



    // AFTER
    LaunchedEffect(isLoadingGrid, profiles.size) {
        if (isLoadingGrid) return@LaunchedEffect

        if (profiles.isEmpty()) {
            createFocusRequester.requestFocus()
            focusParked = false          // release the parking spot
            return@LaunchedEffect
        }

        while (focusRequesters.isEmpty()) {
            kotlinx.coroutines.android.awaitFrame()
        }
        kotlinx.coroutines.android.awaitFrame()
        focusRequesters[0].requestFocus()
        focusParked = false              // release the parking spot
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
    Box(modifier = Modifier.fillMaxSize()) {

        // Background
        Image(
            painter            = painterResource(id = R.drawable.profile_screen),
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )

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
                        onClick        = { onProfileSelected(profile.id, profile.display_name, avatarResId) },
                    )
                }
            }
        }

        // ── Create Profile button ─────────────────────────────────────────────
        if (profiles.size < 8) CreateProfileButton(
            focusRequester = createFocusRequester,
            onClick        = { onCreateProfile(creationLauncher) },
            modifier       = Modifier
                .align(Alignment.TopStart)
                .offset(x = createButtonX, y = createButtonY)
                .focusProperties { right = manageFocusRequester },
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

        // ── Manage Profiles button ────────────────────────────────────────────
        ModularButton(
            textConfig = ModularButtonTextConfig(
                text     = "Manage Profiles",
                fontSize = 10f,
            ),
            isToggleable   = false,
            fontFamily     = com.example.bluehive.utilities.AppTypography.interBold,
            focusRequester = manageFocusRequester,
            onClick        = { onManageProfiles(manageLauncher) },
            modifier       = Modifier
                .align(Alignment.TopStart)
                .offset(x = 790.dp, y = 445.dp)
                .focusProperties {
                    left = if (profiles.size < 8) createFocusRequester
                    else focusRequesters.getOrNull(7) ?: FocusRequester.Cancel
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

        // Intercepts the hardware back button and shows the "Close app?"
        // confirmation overlay instead of immediately closing the activity.
        // All logic lives in CloseApplication.kt — nothing to maintain here.
        CloseApplicationHandler()

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
) {
    var isFocused by remember { mutableStateOf(false) }

    val glowAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue   = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = if (isFocused) 150 else 100),
        label         = "profileCardGlow",
    )

    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue   = if (isFocused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 200),
        label         = "profileCardScale",
    )

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
                    .onFocusChanged { isFocused = it.isFocused }
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.DirectionCenter ||
                                    event.key == Key.Enter       ||
                                    event.key == Key.NumPadEnter)
                        ) {
                            onClick()
                            true
                        } else false
                    },
            ) {
                Image(
                    painter            = painterResource(id = avatarResId),
                    contentDescription = profile.display_name,
                    contentScale       = ContentScale.Crop,
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