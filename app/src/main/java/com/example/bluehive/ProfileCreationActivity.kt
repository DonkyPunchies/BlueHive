package com.example.bluehive

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.ProfileCreateRequest
import com.example.bluehive.utilities.AppTypography
import com.example.bluehive.utilities.AvatarGlowConfig
import com.example.bluehive.utilities.AvatarImageConfig
import com.example.bluehive.utilities.AvatarPickerButton
import com.example.bluehive.utilities.ModularButton
import com.example.bluehive.utilities.ModularButtonAnimationConfig
import com.example.bluehive.utilities.ModularButtonColors
import com.example.bluehive.utilities.ModularButtonDimensions
import com.example.bluehive.utilities.ModularButtonGlowConfig
import com.example.bluehive.utilities.ModularButtonTextConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

// ─────────────────────────────────────────────────────────────────────────────
//  ProfileCreationActivity
//
//  Nav chain:  ProfileScreen → ProfileCreationActivity → ProfileScreen (back)
//
//  On success: POSTs to /api/bluehive/profiles and calls setResult(RESULT_OK)
//  so ProfileScreenActivity knows to refresh its profile grid.
//
//  On failure: shows a red overlay error message with a blurred background.
// ─────────────────────────────────────────────────────────────────────────────

// ── Intent result extras — read by ProfileScreenActivity ─────────────────────

/** Extra key: the new profile's backend id (Int). */
const val EXTRA_NEW_PROFILE_ID   = "new_profile_id"

/** Extra key: the new profile's display name (String). */
const val EXTRA_NEW_PROFILE_NAME = "new_profile_name"

/** Extra key: the drawable resource name used as avatar, e.g. "avatar2" (String). */
const val EXTRA_NEW_AVATAR_RES_NAME = "new_avatar_res_name"

// ── Transition config ─────────────────────────────────────────────────────────

private const val ENTRY_DELAY_MS        = 700L
private const val ENTRY_FADE_DURATION_MS = 1100

// ── Profile type button positions ─────────────────────────────────────────────

private val STANDARD_BUTTON_OFFSET_X: Dp = 674.dp
private val STANDARD_BUTTON_OFFSET_Y: Dp = 222.dp

private val KIDS_BUTTON_OFFSET_X: Dp = 782.dp
private val KIDS_BUTTON_OFFSET_Y: Dp = 222.dp

// ── Profile name field config ─────────────────────────────────────────────────

private val NAME_FIELD_WIDTH: Dp        = 206.dp
private val NAME_FIELD_HEIGHT: Dp       = 22.75.dp
private val NAME_FIELD_OFFSET_Y: Dp     = 357.dp
private val NAME_FIELD_GLOW_WIDTH: Dp   = 241.4.dp
private val NAME_FIELD_GLOW_HEIGHT: Dp  = 69.7.dp
private val NAME_FIELD_GLOW_OFFSET_Y: Dp = (-22.8).dp

/** Hard cap enforced both here and on the backend. */
private const val PROFILE_NAME_MAX_CHARS = 20

// ── Action button config ──────────────────────────────────────────────────────

private val ACTION_BUTTONS_OFFSET_Y: Dp = 420.dp

// ── Avatar picker row config ──────────────────────────────────────────────────

private val AVATAR_SPACING: Dp = 65.dp

// ─────────────────────────────────────────────────────────────────────────────
//  AvatarPickerPanel
//
//  Self-contained reusable composable that renders:
//    • A dark rectangle base layer (563.7×274.7dp, #FF121213 at 80% opacity,
//      stroke #FF3A3737)
//    • Up to 32 avatars arranged in 4 rows of 8, auto-wrapping
//    • Per-row Y offsets so each row can be positioned independently
//    • All X positions are concurrent (driven by AVATAR_SPACING)
//
//  Parameters:
//    offsetX          — X position of the entire panel on screen
//    offsetY          — Y position of the entire panel on screen
//    rowOffsetYList   — list of 4 Y offsets, one per row, relative to the panel
//                       (index 0 = row 1 top, index 3 = row 4 bottom)
//    selectedAvatarRes — currently selected drawable res Int, or null
//    onAvatarSelected  — called with the drawable res Int when user picks one
//    focusRequesters   — list of FocusRequesters, one per avatar slot (size 32)
//
//  To add more avatars later: add entries to AVATAR_PANEL_DRAWABLES below.
//  Slots with a null drawable render nothing (placeholder for future avatars).
// ─────────────────────────────────────────────────────────────────────────────

private const val AVATAR_PANEL_COLS     = 8
private const val AVATAR_PANEL_ROWS     = 4
const val AVATAR_PANEL_TOTAL     = AVATAR_PANEL_COLS * AVATAR_PANEL_ROWS  // 32

/** Add new avatar drawables here as they become available. Null = not yet added. */
private val AVATAR_PANEL_DRAWABLES: List<Int?> = listOf(
    R.drawable.avatar1,  // slot 0
    R.drawable.avatar2,  // slot 1
    R.drawable.avatar3,  // slot 2
    R.drawable.avatar4,  // slot 3
    R.drawable.avatar5,  // slot 4
    R.drawable.avatar6,  // slot 5

    null, null,  // slots 3-7  (row 1 remaining)
    null, null, null, null, null, null, null, null,  // slots 8-15  (row 2)
    null, null, null, null, null, null, null, null,  // slots 16-23 (row 3)
    null, null, null, null, null, null, null, null,  // slots 24-31 (row 4)
)

/** Default per-row Y offsets relative to the panel origin. Tune freely. */
val AVATAR_PANEL_ROW_OFFSETS: List<Dp> = listOf(
    16.dp,   // row 1
    90.dp,   // row 2
    164.dp,  // row 3
    238.dp,  // row 4
)





@Composable
fun AvatarPickerPanel(
    offsetX: Dp,
    offsetY: Dp,
    rowOffsetYList:    List<Dp>       = AVATAR_PANEL_ROW_OFFSETS,
    selectedAvatarRes: Int?,
    onAvatarSelected:  (Int) -> Unit,
    focusRequesters:   List<FocusRequester>,
) {
    Box(
        modifier = Modifier
            .offset(x = 60.dp, y = 187.dp)
    ) {
        // ── Background rectangle ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .width(563.7.dp)
                .height(274.7.dp)
                .background(
                    color = Color(0xFF121213).copy(alpha = 0.8f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                )
                .then(
                    Modifier.border(
                        width = 0.7.dp,
                        color = Color(0xFF242323),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    )
                )
        )

        // ── Avatar grid ───────────────────────────────────────────────────────
        for (slot in 0 until AVATAR_PANEL_TOTAL) {
            val drawableRes = AVATAR_PANEL_DRAWABLES.getOrNull(slot) ?: continue
            val row         = slot / AVATAR_PANEL_COLS
            val col         = slot % AVATAR_PANEL_COLS
            val rowY        = rowOffsetYList.getOrElse(row) { AVATAR_PANEL_ROW_OFFSETS[0] }
            val focusReq    = focusRequesters.getOrNull(slot) ?: continue

            AvatarPickerButton(
                avatarRes      = drawableRes,
                focusRequester = focusReq,
                onClick        = { onAvatarSelected(drawableRes) },
                isSelected     = selectedAvatarRes == drawableRes,
                isAnySelected  = selectedAvatarRes != null,
                modifier       = Modifier.offset(
                    x = 16.dp + col * AVATAR_SPACING,
                    y = rowY,
                ),
                imageConfig = AvatarImageConfig(
                    width        = 54.dp,
                    height       = 54.dp,
                    offsetX      = 0.dp,
                    offsetY      = 0.dp,
                    cornerRadius = 4.dp,
                    strokeColor  = Color(0xFFB0A6A6),
                    strokeWidth  = 1.5.dp,
                ),
                glowConfig = AvatarGlowConfig(
                    width   = 74.1.dp,
                    height  = 74.dp,
                    offsetX = (-10.2).dp,
                    offsetY = (-9.8).dp,
                ),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────







class ProfileCreationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent { ProfileCreationScreen() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ProfileCreationScreen() {
    val activity = androidx.activity.compose.LocalActivity.current as Activity
    val scope    = rememberCoroutineScope()

    // ── Entry animation ───────────────────────────────────────────────────────
    var isReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(ENTRY_DELAY_MS)
        isReady = true
    }

    // ── Profile type state ────────────────────────────────────────────────────
    var isStandardSelected by remember { mutableStateOf(false) }
    var isKidsSelected     by remember { mutableStateOf(false) }

    // ── Step 1: Selected avatar ───────────────────────────────────────────────
    var selectedAvatarRes by remember { mutableStateOf<Int?>(null) }

    // ── Step 3: Profile name ──────────────────────────────────────────────────
    var profileName        by remember { mutableStateOf("") }
    var isNameFieldEditing by remember { mutableStateOf(false) }
    var isNameFieldFocused by remember { mutableStateOf(false) }

    // ── Submission state ──────────────────────────────────────────────────────
    var isSubmitting    by remember { mutableStateOf(false) }
    var errorMessage    by remember { mutableStateOf<String?>(null) }

    // ── Focus requesters ──────────────────────────────────────────────────────
    val standardFocusRequester  = remember { FocusRequester() }
    val kidsFocusRequester      = remember { FocusRequester() }
    val avatarFocusRequesters   = remember { List(AVATAR_PANEL_TOTAL) { FocusRequester() } }
    val nameContainerRequester  = remember { FocusRequester() }
    val nameFieldRequester     = remember { FocusRequester() }
    val createButtonRequester  = remember { FocusRequester() }
    val cancelButtonRequester  = remember { FocusRequester() }

    // ── Radio button focus transfer ───────────────────────────────────────────
    LaunchedEffect(isStandardSelected, isKidsSelected) {
        if (isReady) {
            if (isStandardSelected) kidsFocusRequester.requestFocus()
            else if (isKidsSelected) standardFocusRequester.requestFocus()
        }
    }

    // makes the first avatar the first focusable element on the page
    LaunchedEffect(isReady) {
        if (isReady) {
            avatarFocusRequesters[0].requestFocus()
        }
    }

    // ── Submit handler ────────────────────────────────────────────────────────
    // Architecture:
    //   1. Validate fields client-side (avatar chosen, name not empty).
    //   2. Map the selected drawable res Int → its resource name string (e.g. "avatar2").
    //      The backend stores this string; ProfileScreen resolves it back to a
    //      drawable via resources.getIdentifier() so no URLs or uploads are needed.
    //   3. POST to /api/bluehive/profiles via platformApi (Bearer token auth).
    //   4. On HTTP 201: setResult(RESULT_OK) with the new profile extras, then finish().
    //      ProfileScreenActivity's onActivityResult refreshes its grid automatically.
    //   5. On any failure (network, 409 name conflict, 403 device cap):
    //      show the red error overlay — never silently swallow errors.
    fun submitProfile() {
        if (isSubmitting) return

        // Client-side validation
        val trimmedName = profileName.trim()
        if (trimmedName.isEmpty()) {
            errorMessage = "Please enter a profile name."
            return
        }
        if (selectedAvatarRes == null) {
            errorMessage = "Please choose a profile picture."
            return
        }

        // Resolve drawable Int → resource name string for backend storage
        val avatarResName = activity.resources.getResourceEntryName(selectedAvatarRes!!)

        isSubmitting = true
        errorMessage = null

        scope.launch {
            try {
                val created = ApiClient.bluehiveApi.createProfile(
                    ProfileCreateRequest(
                        display_name = trimmedName,
                        avatar_url   = avatarResName,
                        profile_type = if (isKidsSelected) "kids" else "standard",
                    )
                )

                // Success — hand the new profile data back to ProfileScreenActivity
                // so it can add the card to the grid without a full re-fetch.
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_NEW_PROFILE_ID,       created.id)
                    putExtra(EXTRA_NEW_PROFILE_NAME,     created.display_name)
                    putExtra(EXTRA_NEW_AVATAR_RES_NAME,  created.avatar_url ?: "")
                }
                activity.setResult(Activity.RESULT_OK, resultIntent)
                activity.finish()

            } catch (e: HttpException) {
                isSubmitting = false
                errorMessage = when (e.code()) {
                    409  -> "A profile with that name already exists."
                    403  -> "This device has reached the maximum number of profiles."
                    else -> "Connection to the servers not available, please try again later..."
                }
            } catch (e: Exception) {
                isSubmitting = false
                errorMessage = "Connection to the servers not available, please try again later..."
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    AnimatedVisibility(
        visible = isReady,
        enter   = fadeIn(animationSpec = tween(durationMillis = ENTRY_FADE_DURATION_MS)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // ── Background ────────────────────────────────────────────────────
            Image(
                painter            = painterResource(id = R.drawable.create_profile_page),
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )

            // ── Step 1: Avatar picker panel ───────────────────────────────────
            AvatarPickerPanel(
                offsetX           = 73.dp,
                offsetY           = 197.dp,
                selectedAvatarRes = selectedAvatarRes,
                onAvatarSelected  = { selectedAvatarRes = it },
                focusRequesters   = avatarFocusRequesters,
            )


            // ── Step 2: Standard button ───────────────────────────────────────
            val widthProfileType = 100.dp

            ModularButton(
                textConfig      = ModularButtonTextConfig(text = "Standard", fontSize = 17f),
                isToggleable    = true,
                externalToggled = isStandardSelected,
                isFocusable     = !isStandardSelected,
                onClick = { isStandardSelected = true; isKidsSelected = false },
                fontFamily      = AppTypography.dongleBold,
                focusRequester  = standardFocusRequester,
                modifier        = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = STANDARD_BUTTON_OFFSET_X, y = STANDARD_BUTTON_OFFSET_Y),
                dimensions      = ModularButtonDimensions(
                    mainWidth          = widthProfileType,
                    mainHeight         = 27.dp,
                    mainYOffset        = 6.5.dp,
                    secondWidth        = widthProfileType,
                    secondHeight       = 12.dp,
                    secondYOffset      = 26.dp,
                    mainCornerRadius   = 7f,
                    secondCornerRadius = 8f,
                    shadowHeight       = 12.dp,
                    glowWidth          = widthProfileType + 16.dp,
                    glowHeight         = 41.dp,
                ),
                colors          = ModularButtonColors(
                    mainDefault          = Color(0xFF1A30B8),
                    mainFocused          = Color(0xFF2B45E0),
                    mainToggled          = Color(0xFF101840),

                    secondDefault        = Color(0xFF152165),
                    secondFocused        = Color(0xFF1A2B8A),
                    secondToggled        = Color(0xFF11122F),

                    textFocused          = Color(0xFFDADADA),
                    textUnfocused        = Color(0xFFB6B5B5),
                    textUnfocusedToggled = Color(0xFF203284),
                    textToggledFocus     = Color(0xFFDADADA),
                ),
                glowConfig      = ModularButtonGlowConfig(
                    enabled               = true,
                    defaultRes            = R.drawable.button_focus_wide_glow,
                    offsetX               = (-8).dp,
                    offsetY               = 7.dp,
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

            // ── Step 2: Kids button ───────────────────────────────────────────
            ModularButton(
                textConfig      = ModularButtonTextConfig(text = "Kids", fontSize = 17f),
                isToggleable    = true,
                externalToggled = isKidsSelected,
                isFocusable     = !isKidsSelected,
                onClick = { isKidsSelected = true; isStandardSelected = false },
                fontFamily      = AppTypography.dongleBold,
                focusRequester  = kidsFocusRequester,
                modifier        = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = KIDS_BUTTON_OFFSET_X, y = KIDS_BUTTON_OFFSET_Y),
                dimensions      = ModularButtonDimensions(
                    mainWidth          = widthProfileType,
                    mainHeight         = 27.dp,
                    mainYOffset        = 6.5.dp,
                    secondWidth        = widthProfileType,
                    secondHeight       = 12.dp,
                    secondYOffset      = 26.dp,
                    mainCornerRadius   = 7f,
                    secondCornerRadius = 8f,
                    shadowHeight       = 12.dp,
                    glowWidth          = widthProfileType + 16.dp,
                    glowHeight         = 41.dp,
                ),
                colors          = ModularButtonColors(
                    mainDefault          = Color(0xFFC49518),
                    mainFocused          = Color(0xFFD4A017),
                    mainToggled          = Color(0xFF8B6810),

                    secondDefault        = Color(0xFF956812),
                    secondFocused        = Color(0xFF8A6010),
                    secondToggled        = Color(0xFF3B2B07),

                    textFocused          = Color(0xFFAC2B2B),
                    textUnfocused        = Color(0xFFAA1D1D),
                    textUnfocusedToggled = Color(0xFF891F1F),
                ),
                glowConfig      = ModularButtonGlowConfig(
                    enabled               = true,
                    defaultRes            = R.drawable.button_focus_wide_glow,
                    offsetX               = (-8).dp,
                    offsetY               = 7.dp,
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

            // ── Step 3: Profile name input ────────────────────────────────────
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = STANDARD_BUTTON_OFFSET_X, y = NAME_FIELD_OFFSET_Y),
            ) {
                if (isNameFieldFocused || isNameFieldEditing) {
                    Image(
                        painter            = painterResource(id = R.drawable.search_bar_focused),
                        contentDescription = null,
                        contentScale       = ContentScale.FillBounds,
                        modifier           = Modifier
                            .width(NAME_FIELD_GLOW_WIDTH)
                            .height(NAME_FIELD_GLOW_HEIGHT)
                            .offset(
                                x = (NAME_FIELD_WIDTH - NAME_FIELD_GLOW_WIDTH) / 2,
                                y = NAME_FIELD_GLOW_OFFSET_Y,
                            ),
                    )
                }

                Box(
                    modifier = Modifier
                        .width(NAME_FIELD_WIDTH)
                        .height(NAME_FIELD_HEIGHT)
                        .focusRequester(nameContainerRequester)
                        .onFocusChanged { isNameFieldFocused = it.isFocused }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp &&
                                (event.key == Key.DirectionCenter ||
                                        event.key == Key.Enter ||
                                        event.key == Key.NumPadEnter) &&
                                !isNameFieldEditing
                            ) {
                                isNameFieldEditing = true
                                nameFieldRequester.requestFocus()
                                true
                            } else false
                        },
                ) {
                    Image(
                        painter            = painterResource(id = R.drawable.search_bar_background),
                        contentDescription = null,
                        contentScale       = ContentScale.FillBounds,
                        modifier           = Modifier.matchParentSize(),
                    )

                    if (profileName.isEmpty()) {
                        Text(
                            text     = "Enter profile name...",
                            color    = Color(0xFF757575),
                            fontSize = 9.sp,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 10.dp),
                        )
                    }

                    BasicTextField(
                        value         = profileName,
                        onValueChange = { if (it.length <= PROFILE_NAME_MAX_CHARS) profileName = it },
                        singleLine    = true,
                        cursorBrush   = SolidColor(Color.White),
                        textStyle     = TextStyle(color = Color.White, fontSize = 9.sp),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 10.dp)
                            .width(NAME_FIELD_WIDTH - 20.dp)
                            .focusRequester(nameFieldRequester)
                            .focusProperties { canFocus = isNameFieldEditing }
                            .onFocusChanged { state ->
                                if (!state.isFocused && isNameFieldEditing) {
                                    isNameFieldEditing = false
                                }
                            }
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyUp && event.key == Key.Back) {
                                    isNameFieldEditing = false
                                    nameContainerRequester.requestFocus()
                                    true
                                } else false
                            },
                    )
                }
            }

            // ── Action buttons ────────────────────────────────────────────────

            val width   = 115.dp

            // Create Profile
            ModularButton(
                textConfig      = ModularButtonTextConfig(text = "Create Profile", fontSize = 18f),
                isToggleable    = false,
                fontFamily      = AppTypography.dongleBold,
                focusRequester  = createButtonRequester,
                modifier        = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 660.dp, y = ACTION_BUTTONS_OFFSET_Y),
                dimensions      = ModularButtonDimensions(
                    mainWidth          = width,
                    mainHeight         = 27.dp,
                    mainYOffset        = 6.5.dp,
                    secondWidth        = width,
                    secondHeight       = 12.dp,
                    secondYOffset      = 26.dp,
                    mainCornerRadius   = 7f,
                    secondCornerRadius = 8f,
                    shadowHeight       = 12.dp,
                    glowWidth          = width + 17.dp,
                    glowHeight         = 41.dp,
                ),
                colors          = ModularButtonColors(
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
                glowConfig      = ModularButtonGlowConfig(
                    enabled               = true,
                    defaultRes            = R.drawable.button_focus_wide_glow,
                    offsetX               = (-8.5).dp,
                    offsetY               = 7.dp,
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
                onClick = { submitProfile() },
            )

            // Cancel
            ModularButton(
                textConfig      = ModularButtonTextConfig(text = "Cancel", fontSize = 17.5f),
                isToggleable    = false,
                fontFamily      = AppTypography.dongleBold,
                focusRequester  = cancelButtonRequester,
                modifier        = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 787.dp, y = ACTION_BUTTONS_OFFSET_Y),
                dimensions      = ModularButtonDimensions(
                    mainWidth          = width,
                    mainHeight         = 27.dp,
                    mainYOffset        = 6.5.dp,
                    secondWidth        = width,
                    secondHeight       = 12.dp,
                    secondYOffset      = 26.dp,
                    mainCornerRadius   = 7f,
                    secondCornerRadius = 8f,
                    shadowHeight       = 12.dp,
                    glowWidth          = width + 17.dp,
                    glowHeight         = 41.dp,
                ),
                colors          = ModularButtonColors(
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
                glowConfig      = ModularButtonGlowConfig(
                    enabled               = true,
                    defaultRes            = R.drawable.button_focus_wide_glow,
                    offsetX               = (-8.5).dp,
                    offsetY               = 7.dp,
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
                onClick = { activity.finish() },
            )

            // ── Error overlay ─────────────────────────────────────────────────
            // Shown on any backend or network failure.
            // Semi-opaque dark background + bright red message, auto-dismisses
            // after 4 seconds or when the user presses a button.
            errorMessage?.let { msg ->
                LaunchedEffect(msg) {
                    delay(4_000)
                    errorMessage = null
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier         = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000)), // 80% black scrim
                ) {
                    Text(
                        text       = msg,
                        color      = Color(0xFFFF2020),
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AppTypography.dongleBold,
                        modifier   = Modifier
                            .background(
                                color  = Color(0x99000000),
                                shape  = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 32.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}