package com.example.bluehive

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.ProfileResponse
import com.example.bluehive.api.ProfileUpdateRequest
import com.example.bluehive.utilities.AppTypography
import com.example.bluehive.utilities.ModularButton
import com.example.bluehive.utilities.ModularButtonAnimationConfig
import com.example.bluehive.utilities.ModularButtonColors
import com.example.bluehive.utilities.ModularButtonDimensions
import com.example.bluehive.utilities.ModularButtonGlowConfig
import com.example.bluehive.utilities.ModularButtonTextConfig
import androidx.compose.material3.Text
import com.example.bluehive.api.DeletedProfileResponse
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.verticalScroll

// ─────────────────────────────────────────────────────────────────────────────
//  Avatar resource map
// ─────────────────────────────────────────────────────────────────────────────

private val MANAGE_AVATAR_DRAWABLE_MAP: Map<String, Int> = mapOf(
    "avatar1" to R.drawable.avatar1,
    "avatar2" to R.drawable.avatar2,
    "avatar3" to R.drawable.avatar3,
    "avatar4" to R.drawable.avatar4,
    "avatar5" to R.drawable.avatar5,
    "avatar6" to R.drawable.avatar6,
)

private fun resolveManageAvatarDrawable(resName: String?): Int =
    MANAGE_AVATAR_DRAWABLE_MAP[resName ?: ""] ?: R.drawable.avatar1

// ─────────────────────────────────────────────────────────────────────────────
//  Grid constants
// ─────────────────────────────────────────────────────────────────────────────

private val MANAGE_CARD_SIZE: Dp    = 107.6.dp
private val MANAGE_CARD_GAP: Dp     = 19.5.dp
private const val MANAGE_CARDS_PER_ROW = 4
private val MANAGE_CARD_CORNER: Dp  = 5.dp

private val MANAGE_GRID_OFFSET_X: Dp      = 47.5.dp
private val MANAGE_GRID_OFFSET_Y_ROW1: Dp = 155.dp
private val MANAGE_GRID_OFFSET_Y_ROW2: Dp = 170.dp

private val MANAGE_CHECKMARK_SIZE: Dp = 48.dp

private val MANAGE_NAME_LABEL_OFFSET_Y_UNFOCUSED: Dp = 132.5.dp
private val MANAGE_NAME_LABEL_OFFSET_Y_FOCUSED: Dp   = 139.dp
private val MANAGE_NAME_COLOR_UNFOCUSED              = Color(0xFF676767)
private val MANAGE_NAME_COLOR_FOCUSED                = Color.White

// ─────────────────────────────────────────────────────────────────────────────
//  Detail panel constants
// ─────────────────────────────────────────────────────────────────────────────

private val DETAIL_AVATAR_SIZE: Dp    = 74.dp
private val DETAIL_AVATAR_CORNER: Dp  = 5.dp
private val DETAIL_PANEL_OFFSET_X: Dp = 620.dp
private val DETAIL_PANEL_OFFSET_Y: Dp = 178.dp

private val DETAIL_NAME_OFFSET_X: Dp        = 90.dp
private val DETAIL_NAME_OFFSET_Y: Dp        = 0.dp
private val DETAIL_NAME_FONT_SIZE: TextUnit = 22.sp
private val DETAIL_NAME_COLOR: Color        = Color.White

private val DETAIL_TIME_LABEL_OFFSET_X: Dp        = 90.dp
private val DETAIL_TIME_LABEL_OFFSET_Y: Dp        = 28.dp
private val DETAIL_TIME_LABEL_FONT_SIZE: TextUnit = 16.sp
private val DETAIL_TIME_LABEL_COLOR: Color        = Color(0xFFA1A1A1)

private val DETAIL_TIME_VALUE_OFFSET_X: Dp        = 200.dp
private val DETAIL_TIME_VALUE_OFFSET_Y: Dp        = 28.dp
private val DETAIL_TIME_VALUE_FONT_SIZE: TextUnit = 16.sp

private val DETAIL_DATE_LABEL_OFFSET_X: Dp        = 90.dp
private val DETAIL_DATE_LABEL_OFFSET_Y: Dp        = 42.5.dp
private val DETAIL_DATE_LABEL_FONT_SIZE: TextUnit = 16.sp
private val DETAIL_DATE_LABEL_COLOR: Color        = Color(0xFFA1A1A1)

private val DETAIL_DATE_VALUE_OFFSET_X: Dp        = 200.dp
private val DETAIL_DATE_VALUE_OFFSET_Y: Dp        = 42.5.dp
private val DETAIL_DATE_VALUE_FONT_SIZE: TextUnit = 16.sp

private val DETAIL_CREATED_LABEL_OFFSET_X: Dp        = 90.dp
private val DETAIL_CREATED_LABEL_OFFSET_Y: Dp        = 56.5.dp
private val DETAIL_CREATED_LABEL_FONT_SIZE: TextUnit = 16.sp
private val DETAIL_CREATED_LABEL_COLOR: Color        = Color(0xFFA1A1A1)

private val DETAIL_CREATED_VALUE_OFFSET_X: Dp        = 200.dp
private val DETAIL_CREATED_VALUE_OFFSET_Y: Dp        = 56.5.dp
private val DETAIL_CREATED_VALUE_FONT_SIZE: TextUnit = 16.sp

private const val TAG = "ProfileManageActivity"

// ─────────────────────────────────────────────────────────────────────────────
//  Timestamp helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatLoginTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "--:-- --"
    return try {
        val instant = OffsetDateTime.parse(iso).toInstant()
        val zone    = ZoneId.systemDefault()
        val local   = instant.atZone(zone)
        val fmt = DateTimeFormatter.ofPattern("hh:mm a (z)", Locale.US)
        local.format(fmt)
    } catch (_: Exception) { "--:-- --" }
}

private fun formatLoginDate(iso: String?): String {
    if (iso.isNullOrBlank()) return "--/--/----"
    return try {
        val instant = OffsetDateTime.parse(iso).toInstant()
        val zone    = ZoneId.systemDefault()
        val local   = instant.atZone(zone)
        DateTimeFormatter.ofPattern("MM/dd/yyyy").format(local)
    } catch (_: Exception) { "--/--/----" }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Activity
// ─────────────────────────────────────────────────────────────────────────────

class ProfileManageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            ProfileManageScreen(
                onBack = { setResult(RESULT_OK); finish() }
            )
        }
    }

    init {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                setResult(RESULT_OK)
                finish()
            }
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Screen composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ProfileManageScreen(
    onBack: () -> Unit = {},
) {
    val profiles = remember { mutableStateListOf<ProfileResponse>() }

    LaunchedEffect(Unit) {
        try {
            val fetched = ApiClient.bluehiveApi.listProfiles()
            Log.d(TAG, "✅ Fetched ${fetched.size} profiles")
            profiles.clear()
            profiles.addAll(fetched)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load profiles: ${e.message}")
        }
    }

    val focusRequesters = remember { mutableStateListOf<FocusRequester>() }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // ── Overlay state ─────────────────────────────────────────────────────────
    var showAvatarPicker    by remember { mutableStateOf(false) }
    var showDeleteConfirm   by remember { mutableStateOf(false) }
    var showRecoverOverlay  by remember { mutableStateOf(false) }
    val deletedProfiles     = remember { mutableStateListOf<DeletedProfileResponse>() }
    var recoverFocusedIndex by remember { mutableIntStateOf(0) }
    val recoverFocusRequesters = remember { mutableStateListOf<FocusRequester>() }

    val avatarPickerFocusRequesters = remember { List(AVATAR_PANEL_TOTAL) { FocusRequester() } }

    var showNameEditor by remember { mutableStateOf(false) }
    var nameEditorText by remember { mutableStateOf("") }
    val nameFieldRequester = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }

    // ── Detail panel button focus requesters (lifted so overlays can return focus)
    val changeImageFocus  = remember { FocusRequester() }
    val changeNameFocus   = remember { FocusRequester() }
    val recoverBtnFocus   = remember { FocusRequester() }

    // Guard flags — prevent the else branch firing on initial composition
    var avatarPickerWasOpened by remember { mutableStateOf(false) }
    var nameEditorWasOpened by remember { mutableStateOf(false) }


    var recoverOverlayWasOpened by remember { mutableStateOf(false) }

    LaunchedEffect(showRecoverOverlay) {
        if (showRecoverOverlay) {
            recoverOverlayWasOpened = true
        } else if (recoverOverlayWasOpened) {
            awaitFrame()
            try { recoverBtnFocus.requestFocus() } catch (_: Exception) {}
        }
    }

    // Focus first avatar slot when picker opens; return to Change Image btn when it closes
    LaunchedEffect(showAvatarPicker) {
        if (showAvatarPicker) {
            avatarPickerWasOpened = true
            awaitFrame()
            avatarPickerFocusRequesters[0].requestFocus()
        } else if (avatarPickerWasOpened) {
            awaitFrame()
            try {
                changeImageFocus.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    // Focus the text field when the name editor opens; return to Change Name btn when it closes
    LaunchedEffect(showNameEditor) {
        if (showNameEditor) {
            nameEditorWasOpened = true
            awaitFrame()
            nameFieldRequester.requestFocus()
        } else if (nameEditorWasOpened) {
            awaitFrame()
            try {
                changeNameFocus.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    // Focus the text field when the name editor opens; return to Change Name btn when it closes
    LaunchedEffect(showNameEditor) {
        if (showNameEditor) {
            awaitFrame()
            nameFieldRequester.requestFocus()
        } else {
            awaitFrame()
            try {
                changeNameFocus.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────
    fun deleteSelectedProfile() {
        if (selectedIndex < 0 || selectedIndex >= profiles.size) return
        val profileToDelete = profiles[selectedIndex]
        scope.launch {
            try {
                ApiClient.bluehiveApi.deleteProfile(profileToDelete.id)
                Log.d(TAG, "🗑️ Deleted profile: ${profileToDelete.display_name}")
                profiles.removeAt(selectedIndex)
                selectedIndex = when {
                    profiles.isEmpty() -> -1
                    selectedIndex >= profiles.size -> profiles.size - 1
                    else -> selectedIndex
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to delete profile: ${e.message}")
            }
        }
    }

    // ── Update avatar ─────────────────────────────────────────────────────────
    fun changeSelectedAvatar(newAvatarRes: Int) {
        if (selectedIndex < 0 || selectedIndex >= profiles.size) return
        val profile = profiles[selectedIndex]
        val resName = MANAGE_AVATAR_DRAWABLE_MAP.entries
            .firstOrNull { it.value == newAvatarRes }?.key ?: return
        scope.launch {
            try {
                val updated = ApiClient.bluehiveApi.updateProfile(
                    profile.id,
                    ProfileUpdateRequest(display_name = profile.display_name, avatar_url = resName)
                )
                profiles[selectedIndex] = updated
                Log.d(TAG, "✅ Avatar updated to $resName")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to update avatar: ${e.message}")
            }
            showAvatarPicker = false
        }
    }

    // ── Update name ───────────────────────────────────────────────────────────
    fun changeSelectedName(newName: String) {
        if (selectedIndex < 0 || selectedIndex >= profiles.size) return
        val profile = profiles[selectedIndex]
        val trimmed = newName.trim().take(15)
        if (trimmed.isEmpty()) {
            showNameEditor = false; return
        }
        scope.launch {
            try {
                val updated = ApiClient.bluehiveApi.updateProfile(
                    profile.id,
                    ProfileUpdateRequest(display_name = trimmed, avatar_url = profile.avatar_url)
                )
                profiles[selectedIndex] = updated
                Log.d(TAG, "✅ Name updated to $trimmed")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to update name: ${e.message}")
            }
            showNameEditor = false
        }
    }

    LaunchedEffect(profiles.size) {
        if (profiles.isNotEmpty() && focusRequesters.isNotEmpty()) {
            awaitFrame()
            focusRequesters[0].requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Back) {
                    when {
                        showAvatarPicker -> {
                            showAvatarPicker = false; true
                        }

                        showNameEditor -> {
                            showNameEditor = false; true
                        }

                        showDeleteConfirm  -> { showDeleteConfirm  = false; true }
                        showRecoverOverlay -> { showRecoverOverlay = false; true }
                        else               -> false
                    }
                } else false
            }
    ) {

        Image(
            painter = painterResource(id = R.drawable.manage_profiles_screen),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // ── Background content — focus blocked while any overlay is open ──────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusProperties {
                    canFocus =
                        !showAvatarPicker && !showNameEditor && !showDeleteConfirm && !showRecoverOverlay
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = MANAGE_GRID_OFFSET_X, y = MANAGE_GRID_OFFSET_Y_ROW1),
            ) {
                profiles.forEachIndexed { index, profile ->
                    val row = index / MANAGE_CARDS_PER_ROW
                    val col = index % MANAGE_CARDS_PER_ROW

                    val cardX = col * (MANAGE_CARD_SIZE + MANAGE_CARD_GAP)
                    val rowOffsetY =
                        if (row == 0) MANAGE_GRID_OFFSET_Y_ROW1 else MANAGE_GRID_OFFSET_Y_ROW2
                    val cardY =
                        (row * (MANAGE_CARD_SIZE + MANAGE_CARD_GAP)) + (rowOffsetY - MANAGE_GRID_OFFSET_Y_ROW1)

                    val avatarResId = remember(profile.avatar_url) {
                        resolveManageAvatarDrawable(profile.avatar_url)
                    }

                    val requester = if (index < focusRequesters.size) {
                        focusRequesters[index]
                    } else {
                        remember { FocusRequester() }.also { focusRequesters.add(it) }
                    }

                    ManageProfileCard(
                        profile = profile,
                        avatarResId = avatarResId,
                        focusRequester = requester,
                        modifier = Modifier.offset(x = cardX, y = cardY),
                        anyCardFocused = focusedIndex >= 0,
                        isSelected = selectedIndex == index,
                        onFocusChanged = { focused -> focusedIndex = if (focused) index else -1 },
                        onSelect = {
                            if (selectedIndex != index) {
                                selectedIndex = index
                                BlueHiveApplication.playClickSound()
                                Log.d(TAG, "Selected profile: ${profile.display_name}")
                            }
                        },
                    )
                }
            }

            if (selectedIndex >= 0 && selectedIndex < profiles.size) {
                val selectedProfile = profiles[selectedIndex]
                val selectedResId   = resolveManageAvatarDrawable(selectedProfile.avatar_url)
                SelectedProfileDetailPanel(
                    profile          = selectedProfile,
                    avatarResId      = selectedResId,
                    changeImageFocus = changeImageFocus,
                    changeNameFocus  = changeNameFocus,
                    recoverBtnFocus  = recoverBtnFocus,
                    onChangeImage    = { showAvatarPicker = true },
                    onChangeName     = {
                        nameEditorText = selectedProfile.display_name
                        showNameEditor = true
                    },
                    onDelete         = { showDeleteConfirm = true },
                    onRecover        = {
                        scope.launch {
                            try {
                                val fetched = ApiClient.bluehiveApi.listDeletedProfiles()
                                deletedProfiles.clear()
                                deletedProfiles.addAll(fetched)
                                recoverFocusedIndex = 0
                                recoverFocusRequesters.clear()
                                repeat(fetched.size) { recoverFocusRequesters.add(FocusRequester()) }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Failed to fetch deleted profiles: ${e.message}")
                            }
                            showRecoverOverlay = true
                        }
                    },
                    onReturn         = { onBack() },
                )
            } else if (profiles.isEmpty()) {
                // No profiles — show minimal panel with just recover and return
                EmptyProfileActionPanel(
                    recoverBtnFocus = recoverBtnFocus,
                    returnFocus     = remember { FocusRequester() },
                    onRecover       = {
                        scope.launch {
                            try {
                                val fetched = ApiClient.bluehiveApi.listDeletedProfiles()
                                deletedProfiles.clear()
                                deletedProfiles.addAll(fetched)
                                recoverFocusedIndex = 0
                                recoverFocusRequesters.clear()
                                repeat(fetched.size) { recoverFocusRequesters.add(FocusRequester()) }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Failed to fetch deleted profiles: ${e.message}")
                            }
                            showRecoverOverlay = true
                        }
                    },
                    onReturn        = { onBack() },
                )
            }
        }

        // ── Avatar picker overlay ─────────────────────────────────────────────
        if (showAvatarPicker && selectedIndex >= 0 && selectedIndex < profiles.size) {
            val currentRes = resolveManageAvatarDrawable(profiles[selectedIndex].avatar_url)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000))
            )

            AvatarPickerPanel(
                offsetX = 198.dp,
                offsetY = 133.dp,
                selectedAvatarRes = currentRes,
                onAvatarSelected = { newRes -> changeSelectedAvatar(newRes) },
                focusRequesters = avatarPickerFocusRequesters,
            )
        }

        // ── Name editor overlay ───────────────────────────────────────────────
        if (showNameEditor) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000))
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(color = Color(0xFF121213), shape = RoundedCornerShape(12.dp))
                        .border(
                            width = 1.dp,
                            color = Color(0xFF3A3737),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 32.dp, vertical = 24.dp)
                        .width(320.dp),
                ) {
                    // Preview
                    Text(
                        text = nameEditorText.ifEmpty { "Enter a name..." },
                        color = if (nameEditorText.isEmpty()) Color(0xFF555555) else Color.White,
                        fontFamily = AppTypography.interSemiBold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    )

                    // Input field
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .background(color = Color(0xFF1E1E1F), shape = RoundedCornerShape(6.dp))
                            .border(
                                width = 1.dp,
                                color = Color(0xFF3A3737),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = nameEditorText,
                            onValueChange = { if (it.length <= 15) nameEditorText = it },
                            singleLine = true,
                            cursorBrush = SolidColor(Color.White),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 14.sp,
                                fontFamily = AppTypography.interSemiBold,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(nameFieldRequester),
                        )
                    }

                    // Char count
                    Text(
                        text = "${nameEditorText.length}/15",
                        color = Color(0xFF666666),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 4.dp, bottom = 16.dp),
                    )

                    // Confirm button
                    ModularButton(
                        textConfig = ModularButtonTextConfig(text = "Confirm", fontSize = 12f),
                        isToggleable = false,
                        fontFamily = AppTypography.interBold,
                        focusRequester = confirmFocus,
                        onClick = { changeSelectedName(nameEditorText) },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                            .offset(x = 12.dp),
                        dimensions = ModularButtonDimensions(
                            mainWidth = 120.dp,
                            mainHeight = 27.dp,
                            mainYOffset = 6.5.dp,
                            secondWidth = 120.dp,
                            secondHeight = 12.dp,
                            secondYOffset = 26.dp,
                            mainCornerRadius = 7f,
                            secondCornerRadius = 8f,
                            shadowHeight = 12.dp,
                            glowWidth = 138.dp,
                            glowHeight = 41.dp,
                        ),
                        colors = ModularButtonColors(
                            mainDefault = Color(0xFF1E2854),
                            mainToggled = Color(0xFF2644A6),
                            mainFocused = Color(0xFF2644A6),
                            secondDefault = Color(0xFF151C3A),
                            secondFocused = Color(0xFF282C57),
                            textFocused = Color(0xFFDADADA),
                            textUnfocused = Color(0xFF868686),
                            textUnfocusedToggled = Color(0xFF868686),
                            textToggledFocus = Color(0xFFDADADA),
                        ),
                        glowConfig = ModularButtonGlowConfig(
                            enabled = true,
                            defaultRes = R.drawable.button_focus_wide_glow,
                            offsetX = (-9).dp,
                            offsetY = 7.dp,
                            cornerRadius = 100.dp,
                            fadeOutDurationMillis = 200,
                            fadeInDurationMillis = 400,
                        ),
                        animationConfig = ModularButtonAnimationConfig(
                            pressOffset = 3.5.dp,
                            textOffsetDefault = 7.9.dp,
                            textOffsetPressed = 9.9.dp,
                            durationMillis = 110,
                            bounceBackDelayMillis = 200,
                        ),
                    )
                }
            }
        }


        // ── Delete confirmation overlay ───────────────────────────────────────
        if (showDeleteConfirm) {
            val confirmDeleteFocus = remember { FocusRequester() }
            val cancelDeleteFocus = remember { FocusRequester() }

            LaunchedEffect(Unit) {
                awaitFrame()
                cancelDeleteFocus.requestFocus()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000))
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(color = Color(0xFF121213), shape = RoundedCornerShape(12.dp))
                        .border(
                            width = 1.dp,
                            color = Color(0xFF3A3737),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 32.dp, vertical = 24.dp)
                        .width(360.dp),
                ) {
                    Text(
                        text = "Are you sure you want to delete this profile? Once deleted it can be recovered but its data will be stored on our servers only for a limited time.",
                        color = Color.White,
                        fontFamily = AppTypography.interSemiBold,
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                    )

                    Box(modifier = Modifier.fillMaxWidth()) {

                        // Yes, Delete
                        ModularButton(
                            textConfig = ModularButtonTextConfig(
                                text = "Yes, Delete Profile",
                                fontSize = 10f
                            ),
                            isToggleable = false,
                            fontFamily = AppTypography.interBold,
                            focusRequester = confirmDeleteFocus,
                            onClick = {
                                showDeleteConfirm = false
                                deleteSelectedProfile()
                            },
                            modifier = Modifier.align(Alignment.CenterStart)
                                .offset(x = 35.dp)
                                .focusProperties {
                                    up = FocusRequester.Cancel
                                    down = FocusRequester.Cancel
                                    left = FocusRequester.Cancel
                                    right = cancelDeleteFocus
                                },

                            dimensions = ModularButtonDimensions(
                                mainWidth = 132.dp,
                                mainHeight = 27.dp,
                                mainYOffset = 6.5.dp,
                                secondWidth = 132.dp,
                                secondHeight = 12.dp,
                                secondYOffset = 26.dp,
                                mainCornerRadius = 7f,
                                secondCornerRadius = 8f,
                                shadowHeight = 12.dp,
                                glowWidth = 150.dp,
                                glowHeight = 41.dp,
                            ),
                            colors = ModularButtonColors(
                                mainDefault = Color(0xFF4A1010),
                                mainToggled = Color(0xFF991B1B),
                                mainFocused = Color(0xFF991B1B),
                                secondDefault = Color(0xFF330B0B),
                                secondFocused = Color(0xFF5C1414),
                                textFocused = Color(0xFFDADADA),
                                textUnfocused = Color(0xBAD78080),
                                textUnfocusedToggled = Color(0xFF868686),
                                textToggledFocus = Color(0xFFDADADA),
                            ),
                            glowConfig = ModularButtonGlowConfig(
                                enabled = true,
                                defaultRes = R.drawable.button_focus_wide_glow,
                                offsetX = (-9).dp,
                                offsetY = 7.dp,
                                cornerRadius = 100.dp,
                                fadeOutDurationMillis = 200,
                                fadeInDurationMillis = 400,
                            ),
                            animationConfig = ModularButtonAnimationConfig(
                                pressOffset = 3.5.dp,
                                textOffsetDefault = 7.9.dp,
                                textOffsetPressed = 9.9.dp,
                                durationMillis = 110,
                                bounceBackDelayMillis = 200,
                            ),
                        )

                        // No, Cancel
                        ModularButton(
                            textConfig = ModularButtonTextConfig(
                                text = "No, Cancel Delete",
                                fontSize = 10f
                            ),
                            isToggleable = false,
                            fontFamily = AppTypography.interBold,
                            focusRequester = cancelDeleteFocus,
                            onClick = { showDeleteConfirm = false },
                            modifier = Modifier.align(Alignment.CenterEnd)
                                .offset(x = (-20).dp)
                                .focusProperties {
                                    up = FocusRequester.Cancel
                                    down = FocusRequester.Cancel
                                    left = confirmDeleteFocus
                                    right = FocusRequester.Cancel
                                },
                            dimensions = ModularButtonDimensions(
                                mainWidth = 132.dp,
                                mainHeight = 27.dp,
                                mainYOffset = 6.5.dp,
                                secondWidth = 132.dp,
                                secondHeight = 12.dp,
                                secondYOffset = 26.dp,
                                mainCornerRadius = 7f,
                                secondCornerRadius = 8f,
                                shadowHeight = 12.dp,
                                glowWidth = 150.dp,
                                glowHeight = 41.dp,
                            ),
                            colors = ModularButtonColors(
                                mainDefault = Color(0xFF1E2854),
                                mainToggled = Color(0xFF2644A6),
                                mainFocused = Color(0xFF2644A6),
                                secondDefault = Color(0xFF151C3A),
                                secondFocused = Color(0xFF282C57),
                                textFocused = Color(0xFFDADADA),
                                textUnfocused = Color(0xFF868686),
                                textUnfocusedToggled = Color(0xFF868686),
                                textToggledFocus = Color(0xFFDADADA),
                            ),
                            glowConfig = ModularButtonGlowConfig(
                                enabled = true,
                                defaultRes = R.drawable.button_focus_wide_glow,
                                offsetX = (-9).dp,
                                offsetY = 7.dp,
                                cornerRadius = 100.dp,
                                fadeOutDurationMillis = 200,
                                fadeInDurationMillis = 400,
                            ),
                            animationConfig = ModularButtonAnimationConfig(
                                pressOffset = 3.5.dp,
                                textOffsetDefault = 7.9.dp,
                                textOffsetPressed = 9.9.dp,
                                durationMillis = 110,
                                bounceBackDelayMillis = 200,
                            ),
                        )
                    }
                }
            }
        }


        // ── Recover profile overlay ───────────────────────────────────────────
        if (showRecoverOverlay) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000))
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier         = Modifier.fillMaxSize(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(color = Color(0xFF121213), shape = RoundedCornerShape(12.dp))
                        .border(width = 1.dp, color = Color(0xFF3A3737), shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 28.dp, vertical = 24.dp)
                        .width(420.dp),
                ) {
                    Text(
                        text       = "Recover a Deleted Profile",
                        color      = Color.White,
                        fontFamily = AppTypography.interSemiBold,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 16.sp,
                        modifier   = Modifier.padding(bottom = 4.dp),
                    )
                    Text(
                        text       = "Profiles are stored for a limited time before permanent removal.",
                        color      = Color(0xFF777777),
                        fontFamily = AppTypography.interSemiBold,
                        fontSize   = 11.sp,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier.padding(bottom = 20.dp),
                    )

                    if (deletedProfiles.isEmpty()) {
                        Text(
                            text       = "No deleted profiles found.",
                            color      = Color(0xFF555555),
                            fontFamily = AppTypography.interSemiBold,
                            fontSize   = 13.sp,
                            modifier   = Modifier.padding(bottom = 16.dp),
                        )
                    } else {

                        val scrollState = androidx.compose.foundation.rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .verticalScroll(scrollState),
                        ) {
                            deletedProfiles.forEachIndexed { index, deleted ->
                                val deleted  = deletedProfiles[index]
                                val rowFocus = recoverFocusRequesters[index]

                                val isFocused = recoverFocusedIndex == index
                                val rowAlpha by animateFloatAsState(
                                    targetValue   = if (isFocused) 1f else 0.45f,
                                    animationSpec = tween(200),
                                    label         = "recoverRowAlpha$index",
                                )
                                val avatarRes = resolveManageAvatarDrawable(deleted.avatar_url)

                                LaunchedEffect(showRecoverOverlay) {
                                    if (showRecoverOverlay && index == 0) {
                                        awaitFrame()
                                        try { rowFocus.requestFocus() } catch (_: Exception) {}
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp)
                                        .alpha(rowAlpha)
                                        .background(
                                            color = if (isFocused) Color(0xFF1A1F35) else Color(0xFF0E0E0F),
                                            shape = RoundedCornerShape(8.dp),
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isFocused) Color(0xFF3A4580) else Color(0xFF2A2A2A),
                                            shape = RoundedCornerShape(8.dp),
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .focusRequester(rowFocus)
                                        .onFocusChanged { recoverFocusedIndex = if (it.isFocused) index else recoverFocusedIndex }
                                        .focusable()
                                        .focusProperties {
                                            up    = if (index > 0) recoverFocusRequesters.getOrNull(index - 1) ?: FocusRequester.Cancel else FocusRequester.Cancel
                                            down  = if (index < deletedProfiles.size - 1) recoverFocusRequesters.getOrNull(index + 1) ?: FocusRequester.Cancel else FocusRequester.Cancel
                                            left  = FocusRequester.Cancel
                                            right = FocusRequester.Cancel
                                        }
                                        .onKeyEvent { event ->
                                            // Trap down on last item and up on first item
                                            if (event.type == KeyEventType.KeyDown &&
                                                event.key == Key.DirectionDown &&
                                                index == deletedProfiles.size - 1
                                            ) return@onKeyEvent true
                                            if (event.type == KeyEventType.KeyDown &&
                                                event.key == Key.DirectionUp &&
                                                index == 0
                                            ) return@onKeyEvent true
                                            if (event.type == KeyEventType.KeyDown &&
                                                (event.key == Key.DirectionCenter ||
                                                        event.key == Key.Enter ||
                                                        event.key == Key.NumPadEnter)
                                            ) {
                                                scope.launch {
                                                    try {
                                                        val recovered = ApiClient.bluehiveApi.recoverProfile(deleted.id)
                                                        profiles.add(recovered)
                                                        deletedProfiles.removeAt(index)
                                                        recoverFocusedIndex = minOf(recoverFocusedIndex, deletedProfiles.size - 1).coerceAtLeast(0)
                                                        Log.d(TAG, "✅ Recovered: ${recovered.display_name}")
                                                        if (deletedProfiles.isEmpty()) showRecoverOverlay = false
                                                    } catch (e: Exception) {
                                                        if (e.message?.contains("409") == true || e.message?.contains("slots") == true) {
                                                            showRecoverOverlay = false
                                                            Log.e(TAG, "❌ Slots full")
                                                        }
                                                        Log.e(TAG, "❌ Failed to recover: ${e.message}")
                                                    }
                                                }
                                                true
                                            } else false
                                        },
                                ) {
                                    Image(
                                        painter            = painterResource(id = avatarRes),
                                        contentDescription = null,
                                        contentScale       = ContentScale.Crop,
                                        modifier           = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .align(Alignment.CenterStart),
                                    )
                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .padding(start = 50.dp),
                                    ) {
                                        Text(
                                            text       = deleted.display_name,
                                            color      = Color.White,
                                            fontFamily = AppTypography.interSemiBold,
                                            fontWeight = FontWeight.Bold,
                                            fontSize   = 13.sp,
                                        )
                                        Text(
                                            text       = "Deleted ${formatLoginDate(deleted.deleted_at)}",
                                            color      = Color(0xFF666666),
                                            fontFamily = AppTypography.interSemiBold,
                                            fontSize   = 10.sp,
                                        )
                                    }
                                    Text(
                                        text       = if (isFocused) "Press ● to Recover" else "Recover",
                                        color      = if (isFocused) Color(0xFF6C8FE8) else Color(0xFF3A3A3A),
                                        fontFamily = AppTypography.interBold,
                                        fontSize   = 10.sp,
                                        modifier   = Modifier.align(Alignment.CenterEnd),
                                    )
                                }
                            } // end items
                        } // end LazyColumn
                    } // end else



                } // end Column
            } // end center Box
        } // end if (showRecoverOverlay)

    } // end outer Box
} // end ProfileManageScreen


// ─────────────────────────────────────────────────────────────────────────────
//  ManageProfileCard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ManageProfileCard(
    profile:        ProfileResponse,
    avatarResId:    Int,
    focusRequester: FocusRequester,
    onSelect:       () -> Unit,
    anyCardFocused: Boolean,
    isSelected:     Boolean,
    onFocusChanged: (Boolean) -> Unit,
    modifier:       Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    val glowAlpha by animateFloatAsState(
        targetValue   = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = if (isFocused) 150 else 80),
        label         = "manageGlowAlpha",
    )

    val scale by animateFloatAsState(
        targetValue   = if (isFocused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 320),
        label         = "manageCardScale",
    )

    val avatarAlpha by animateFloatAsState(
        targetValue   = if (isFocused) 1f else 0.4f,
        animationSpec = tween(durationMillis = 250),
        label         = "avatarAlpha",
    )

    val checkmarkAlpha by animateFloatAsState(
        targetValue   = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label         = "checkmarkAlpha",
    )

    val nameLabelOffsetY by animateFloatAsState(
        targetValue   = if (isFocused) MANAGE_NAME_LABEL_OFFSET_Y_FOCUSED.value
        else           MANAGE_NAME_LABEL_OFFSET_Y_UNFOCUSED.value,
        animationSpec = tween(durationMillis = 200),
        label         = "nameLabelOffsetY",
    )

    val nameLabelColor by animateColorAsState(
        targetValue   = if (isFocused) MANAGE_NAME_COLOR_FOCUSED else MANAGE_NAME_COLOR_UNFOCUSED,
        animationSpec = tween(durationMillis = 200),
        label         = "nameLabelColor",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .width(MANAGE_CARD_SIZE + 46.dp)
            .height(MANAGE_CARD_SIZE + 45.dp),
    ) {
        Image(
            painter            = painterResource(id = R.drawable.avatar_button_glow),
            contentDescription = null,
            contentScale       = ContentScale.FillBounds,
            modifier           = Modifier
                .matchParentSize()
                .alpha(glowAlpha),
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(MANAGE_CARD_SIZE)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha  = avatarAlpha
                }
                .then(
                    if (!isFocused) {
                        Modifier.border(
                            width = 1.5.dp,
                            color = Color(0xFF676767),
                            shape = RoundedCornerShape(MANAGE_CARD_CORNER),
                        )
                    } else Modifier
                )
                .clip(RoundedCornerShape(MANAGE_CARD_CORNER))
                .focusRequester(focusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused
                    onFocusChanged(it.isFocused)
                }
                .focusable()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onSelect() })
                }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionCenter ||
                                event.key == Key.Enter       ||
                                event.key == Key.NumPadEnter)
                    ) {
                        onSelect()
                        true
                    } else false
                },
        ) {
            Image(
                painter            = painterResource(id = avatarResId),
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(MANAGE_CARD_CORNER)),
            )

            Image(
                painter            = painterResource(id = R.drawable.check_mark),
                contentDescription = null,
                modifier           = Modifier
                    .size(MANAGE_CHECKMARK_SIZE)
                    .alpha(checkmarkAlpha),
            )
        }

        Text(
            text       = profile.display_name.take(13),
            color      = nameLabelColor,
            fontSize   = 11.sp,
            fontFamily = AppTypography.interBold,
            fontWeight = FontWeight.Medium,
            textAlign  = TextAlign.Center,
            modifier   = Modifier
                .align(Alignment.TopCenter)
                .offset(y = nameLabelOffsetY.dp)
                .width(MANAGE_CARD_SIZE + 46.dp)
                .padding(horizontal = 4.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  SelectedProfileDetailPanel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SelectedProfileDetailPanel(
    profile:          ProfileResponse,
    avatarResId:      Int,
    changeImageFocus: FocusRequester,
    changeNameFocus:  FocusRequester,
    recoverBtnFocus:  FocusRequester,
    onChangeImage:    () -> Unit,
    onChangeName:     () -> Unit,
    onDelete:         () -> Unit,
    onRecover:        () -> Unit,
    onReturn:         () -> Unit,
) {
    val deleteFocus = remember { FocusRequester() }
    val returnFocus = remember { FocusRequester() }

    Box(
        modifier = Modifier.offset(x = DETAIL_PANEL_OFFSET_X, y = DETAIL_PANEL_OFFSET_Y)
    ) {
        Image(
            painter            = painterResource(id = avatarResId),
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier
                .size(DETAIL_AVATAR_SIZE)
                .clip(RoundedCornerShape(DETAIL_AVATAR_CORNER)),
        )

        Text(
            text       = profile.display_name.take(13),
            color      = DETAIL_NAME_COLOR,
            fontFamily = AppTypography.interSemiBold,
            fontSize   = DETAIL_NAME_FONT_SIZE,
            fontWeight = FontWeight.Bold,
            modifier   = Modifier.offset(x = DETAIL_NAME_OFFSET_X, y = DETAIL_NAME_OFFSET_Y),
        )

        Text(
            text       = "Time last logged in:",
            color      = DETAIL_TIME_LABEL_COLOR,
            fontFamily = AppTypography.dongleRegular,
            fontSize   = DETAIL_TIME_LABEL_FONT_SIZE,
            modifier   = Modifier.offset(x = DETAIL_TIME_LABEL_OFFSET_X, y = DETAIL_TIME_LABEL_OFFSET_Y),
        )
        Text(
            text       = formatLoginTime(profile.last_login_at),
            color      = Color.White,
            fontFamily = AppTypography.dongleRegular,
            fontSize   = DETAIL_TIME_VALUE_FONT_SIZE,
            modifier   = Modifier.offset(x = DETAIL_TIME_VALUE_OFFSET_X, y = DETAIL_TIME_VALUE_OFFSET_Y),
        )

        Text(
            text       = "Date last logged in:",
            color      = DETAIL_DATE_LABEL_COLOR,
            fontFamily = AppTypography.dongleRegular,
            fontSize   = DETAIL_DATE_LABEL_FONT_SIZE,
            modifier   = Modifier.offset(x = DETAIL_DATE_LABEL_OFFSET_X, y = DETAIL_DATE_LABEL_OFFSET_Y),
        )
        Text(
            text       = formatLoginDate(profile.last_login_at),
            color      = Color.White,
            fontFamily = AppTypography.dongleRegular,
            fontSize   = DETAIL_DATE_VALUE_FONT_SIZE,
            modifier   = Modifier.offset(x = DETAIL_DATE_VALUE_OFFSET_X, y = DETAIL_DATE_VALUE_OFFSET_Y),
        )

        Text(
            text       = "Profile created:",
            color      = DETAIL_CREATED_LABEL_COLOR,
            fontFamily = AppTypography.dongleRegular,
            fontSize   = DETAIL_CREATED_LABEL_FONT_SIZE,
            modifier   = Modifier.offset(x = DETAIL_CREATED_LABEL_OFFSET_X, y = DETAIL_CREATED_LABEL_OFFSET_Y),
        )
        Text(
            text       = formatLoginDate(profile.created_at),
            color      = Color.White,
            fontFamily = AppTypography.dongleRegular,
            fontSize   = DETAIL_CREATED_VALUE_FONT_SIZE,
            modifier   = Modifier.offset(x = DETAIL_CREATED_VALUE_OFFSET_X, y = DETAIL_CREATED_VALUE_OFFSET_Y),
        )

        DetailBtnChangeImage(focusRequester = changeImageFocus, onClick = onChangeImage)
        DetailBtnChangeName(focusRequester = changeNameFocus,   onClick = onChangeName)
        DetailBtnDelete(focusRequester = deleteFocus,           onClick = onDelete)
        DetailBtnRecoverProfile(focusRequester = recoverBtnFocus, onClick = onRecover)
        DetailBtnReturn(focusRequester = returnFocus,           onClick = onReturn)
    }
}




@Composable
fun EmptyProfileActionPanel(
    recoverBtnFocus: FocusRequester,
    returnFocus:     FocusRequester,
    onRecover:       () -> Unit,
    onReturn:        () -> Unit,
) {
    Box(
        modifier = Modifier.offset(x = DETAIL_PANEL_OFFSET_X, y = DETAIL_PANEL_OFFSET_Y)
    ) {
        Text(
            text       = "No profiles on this account.",
            color      = Color(0xFF666666),
            fontFamily = AppTypography.interSemiBold,
            fontSize   = 13.sp,
            modifier   = Modifier.offset(x = 0.dp, y = 50.dp),
        )
        Text(
            text       = "Use the button below to recover a deleted profile.",
            color      = Color(0xFF444444),
            fontFamily = AppTypography.interSemiBold,
            fontSize   = 11.sp,
            modifier   = Modifier.offset(x = 0.dp, y = 68.dp),
        )

        DetailBtnRecoverProfile(focusRequester = recoverBtnFocus, onClick = onRecover)
        DetailBtnReturn(focusRequester = returnFocus,             onClick = onReturn)
    }
}



// ─────────────────────────────────────────────────────────────────────────────
//  Detail panel buttons
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DetailBtnChangeImage(
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val offsetY = 90.dp
    val width   = 200.dp

    val mainDefault   = Color(0xFF1E2854)
    val mainFocused   = Color(0xFF2644A6)
    val secondDefault = Color(0xFF151C3A)
    val secondFocused = Color(0xFF282C57)

    ModularButton(
        textConfig = ModularButtonTextConfig(text = "Change profile image", fontSize = 10f),
        isToggleable    = false,
        fontFamily      = AppTypography.interBold,
        focusRequester  = focusRequester,
        onClick         = onClick,
        modifier        = Modifier.offset(x = 0.dp, y = offsetY),
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
            glowWidth          = width + 26.dp,
            glowHeight         = 41.dp,
        ),
        colors          = ModularButtonColors(
            mainDefault          = mainDefault,
            mainToggled          = mainFocused,
            mainFocused          = mainFocused,
            secondDefault        = secondDefault,
            secondFocused        = secondFocused,
            textFocused          = Color(0xFFDADADA),
            textUnfocused        = Color(0xFF868686),
            textUnfocusedToggled = Color(0xFF868686),
            textToggledFocus     = Color(0xFFDADADA),
        ),
        glowConfig      = ModularButtonGlowConfig(
            enabled               = true,
            defaultRes            = R.drawable.button_focus_wide_glow,
            offsetX               = (-12.8).dp,
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
}

@Composable
private fun DetailBtnChangeName(
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val offsetY = 130.dp
    val width   = 200.dp

    val mainDefault   = Color(0xFF1E2854)
    val mainFocused   = Color(0xFF2644A6)
    val secondDefault = Color(0xFF151C3A)
    val secondFocused = Color(0xFF282C57)

    ModularButton(
        textConfig = ModularButtonTextConfig(text = "Change profile name", fontSize = 10f),
        isToggleable    = false,
        fontFamily      = AppTypography.interBold,
        focusRequester  = focusRequester,
        onClick         = onClick,
        modifier        = Modifier.offset(x = 0.dp, y = offsetY),
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
            glowWidth          = width + 26.dp,
            glowHeight         = 41.dp,
        ),
        colors          = ModularButtonColors(
            mainDefault          = mainDefault,
            mainToggled          = mainFocused,
            mainFocused          = mainFocused,
            secondDefault        = secondDefault,
            secondFocused        = secondFocused,
            textFocused          = Color(0xFFDADADA),
            textUnfocused        = Color(0xFF868686),
            textUnfocusedToggled = Color(0xFF868686),
            textToggledFocus     = Color(0xFFDADADA),
        ),
        glowConfig      = ModularButtonGlowConfig(
            enabled               = true,
            defaultRes            = R.drawable.button_focus_wide_glow,
            offsetX               = (-12.8).dp,
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
}



@Composable
private fun DetailBtnReturn(
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val width = 132.dp

    val mainDefault   = Color(0xFF1E2854)
    val mainFocused   = Color(0xFF2644A6)
    val secondDefault = Color(0xFF151C3A)
    val secondFocused = Color(0xFF282C57)

    ModularButton(
        textConfig = ModularButtonTextConfig(text = "Return to profiles", fontSize = 10f),
        isToggleable    = false,
        fontFamily      = AppTypography.interBold,
        focusRequester  = focusRequester,
        onClick         = onClick,
        modifier        = Modifier.offset(x = 0.dp, y = 187.dp),
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
            glowWidth          = width + 18.dp,
            glowHeight         = 41.dp,
        ),
        colors          = ModularButtonColors(
            mainDefault          = mainDefault,
            mainToggled          = mainFocused,
            mainFocused          = mainFocused,
            secondDefault        = secondDefault,
            secondFocused        = secondFocused,
            textFocused          = Color(0xFFDADADA),
            textUnfocused        = Color(0xFF868686),
            textUnfocusedToggled = Color(0xFF868686),
            textToggledFocus     = Color(0xFFDADADA),
        ),
        glowConfig      = ModularButtonGlowConfig(
            enabled               = true,
            defaultRes            = R.drawable.button_focus_wide_glow,
            offsetX               = (-9).dp,
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
}



@Composable
private fun DetailBtnDelete(
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val offsetY = 187.dp
    val width   = 132.dp

    val mainDefault   = Color(0xFF4A1010)
    val mainFocused   = Color(0xFF991B1B)
    val secondDefault = Color(0xFF330B0B)
    val secondFocused = Color(0xFF5C1414)

    ModularButton(
        textConfig = ModularButtonTextConfig(text = "Delete profile", fontSize = 10f),
        isToggleable    = false,
        fontFamily      = AppTypography.interBold,
        focusRequester  = focusRequester,
        onClick         = onClick,
        modifier        = Modifier.offset(x = 140.dp, y = offsetY),
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
            glowWidth          = width + 18.dp,
            glowHeight         = 41.dp,
        ),
        colors          = ModularButtonColors(
            mainDefault          = mainDefault,
            mainToggled          = mainFocused,
            mainFocused          = mainFocused,
            secondDefault        = secondDefault,
            secondFocused        = secondFocused,
            textFocused          = Color(0xFFDADADA),
            textUnfocused        = Color(0xBAD78080),
            textUnfocusedToggled = Color(0xFF868686),
            textToggledFocus     = Color(0xFFDADADA),
        ),
        glowConfig      = ModularButtonGlowConfig(
            enabled               = true,
            defaultRes            = R.drawable.button_focus_wide_glow,
            offsetX               = (-9).dp,
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
}


@Composable
private fun DetailBtnRecoverProfile(
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val offsetY = 245.dp
    val width   = 132.dp

    ModularButton(
        textConfig = ModularButtonTextConfig(text = "Recover a deleted profile", fontSize = 8f),
        isToggleable    = false,
        fontFamily      = AppTypography.interBold,
        focusRequester  = focusRequester,
        onClick         = onClick,
        modifier        = Modifier.offset(x = 140.dp, y = offsetY),
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
            glowWidth          = width + 18.dp,
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
        glowConfig      = ModularButtonGlowConfig(
            enabled               = true,
            defaultRes            = R.drawable.button_focus_wide_glow,
            offsetX               = (-9).dp,
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
}



