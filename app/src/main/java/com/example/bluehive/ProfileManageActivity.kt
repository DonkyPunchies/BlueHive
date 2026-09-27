package com.example.bluehive

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalDensity
import com.example.bluehive.utilities.NoticeContent
import com.example.bluehive.utilities.NoticeOverlay

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

// ── The profile panel ─────────────────────────────────────────────────────
//
// THE DARK PANEL BEHIND THE GRID IS PAINTED INTO manage_profiles_screen, not
// laid out, so these are measurements of the artwork -- a 1920x1080 capture,
// exactly 2px per dp, 2026-09-21: x 52..579, y 155..458.5, corners ~6dp. The
// grid's window is cut to exactly this box, which is what keeps every card
// inside the panel however many there are. (Before, the grid was laid straight
// onto the screen, and a third row would have run out of the panel's bottom.)
private val MANAGE_PANEL_X: Dp      = 52.dp
private val MANAGE_PANEL_Y: Dp      = 155.dp
private val MANAGE_PANEL_WIDTH: Dp  = 527.dp
private val MANAGE_PANEL_HEIGHT: Dp = 303.5.dp

// TWO ROWS A PAGE: what the panel holds with the rows where they always sat
// (card tops 177.5 and 319.6), the second row's names and focus glow still
// clear of the panel's bottom edge. A page is the panel's own height, so a page
// flip always comes to rest on whole rows.
private const val MANAGE_ROWS_PER_PAGE = 2
private val MANAGE_ROW_PITCH: Dp get() =
    MANAGE_CARD_SIZE + MANAGE_CARD_GAP + (MANAGE_GRID_OFFSET_Y_ROW2 - MANAGE_GRID_OFFSET_Y_ROW1)

// A cell's left edge inside the window: the grid's screen x less the panel's.
// Negative, because a cell is its card plus 23dp of focus glow on each side.
private val MANAGE_CELL_X: Dp get() = MANAGE_GRID_OFFSET_X - MANAGE_PANEL_X

// ── The scroll bar ────────────────────────────────────────────────────────
//
// A slim track in the panel's LEFT margin -- the 18.5dp between the panel's
// edge and the first column -- centred in it, and running exactly the height of
// the two rows of cards, top of the first to bottom of the second. The thumb is
// one page long and slides with the page flip, on the flip's own clock. Shown
// only when there is more than one page.
private val MANAGE_SCROLLBAR_WIDTH: Dp        = 3.dp
private val MANAGE_SCROLLBAR_X: Dp            = 59.75.dp
private val MANAGE_SCROLLBAR_TOP: Dp get()    = MANAGE_GRID_OFFSET_Y_ROW1 + 22.5.dp
private val MANAGE_SCROLLBAR_HEIGHT: Dp get() = MANAGE_ROW_PITCH + MANAGE_CARD_SIZE
private val MANAGE_SCROLLBAR_MIN_THUMB: Dp    = 24.dp
private val MANAGE_SCROLLBAR_TRACK            = Color(0x14FFFFFF)
// The picker's section-label blue-grey: the same quiet "there is more" colour.
private val MANAGE_SCROLLBAR_THUMB: Color get() = GRID_SECTION_LABEL_COLOR

// The seat triangle on the detail panel's 74dp picture, in the cards'
// proportion (20dp on a 106.6dp card).
private val DETAIL_SEAT_TRIANGLE_SIZE: Dp = 14.dp

// Change-picture and Delete on ANOTHER ACCOUNT'S profile: still focusable -- a
// press explains why it cannot be done -- but drained of the blue and red that
// say "this will act".
private val RESTRICTED_ACTION_COLORS = ModularButtonColors(
    mainDefault          = Color(0xFF181A24),
    mainToggled          = Color(0xFF282C3E),
    mainFocused          = Color(0xFF282C3E),
    secondDefault        = Color(0xFF111219),
    secondFocused        = Color(0xFF1C1F2C),
    textFocused          = Color(0xFF9A9A9A),
    textUnfocused        = Color(0xFF4C4C4C),
    textUnfocusedToggled = Color(0xFF4C4C4C),
    textToggledFocus     = Color(0xFF9A9A9A),
)

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
    // This account's profiles -- the ones every action on this screen can reach.
    val profiles = remember { mutableStateListOf<ProfileResponse>() }

    // THE REST OF THE SUBSCRIPTION'S, listed after them. Anyone on the licence
    // can rename one of these -- an unsuitable name is on everyone's screen --
    // so they belong here; the picture and deleting stay with the account that
    // made them. A separate list, as on the picker, because every rule here
    // that means "mine" reads `profiles`.
    val peerProfiles = remember { mutableStateListOf<ProfileResponse>() }

    LaunchedEffect(Unit) {
        try {
            // Household first, own-only as the fallback, as on the picker: a
            // backend without /profiles/household still gets a working screen,
            // just without the household.
            val household = runCatching { ApiClient.bluehiveApi.getHousehold() }
                .onFailure { Log.w(TAG, "household unavailable: ${it.message}") }
                .getOrNull()
            val fetched = household?.own ?: ApiClient.bluehiveApi.listProfiles()
            Log.d(TAG, "✅ Fetched ${fetched.size} profiles, " +
                       "${household?.others?.size ?: 0} more on the subscription")
            profiles.clear()
            profiles.addAll(fetched)
            peerProfiles.clear()
            peerProfiles.addAll(household?.others.orEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load profiles: ${e.message}")
        }
    }

    // ONE INDEX SPACE ACROSS BOTH LISTS -- own first, then the household's --
    // shared by the grid, its focus requesters and the selection.
    fun profileAt(i: Int): ProfileResponse? =
        if (i < profiles.size) profiles.getOrNull(i) else peerProfiles.getOrNull(i - profiles.size)
    fun isOwn(i: Int): Boolean = i >= 0 && i < profiles.size

    val focusRequesters = remember { mutableStateListOf<FocusRequester>() }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // ── Paging the grid ─────────────────────────────────────────────────────
    val density      = LocalDensity.current
    val manageScroll = rememberScrollState()
    var managePage   by remember { mutableIntStateOf(0) }

    /**
     * Turn the grid to [page]. Each card calls this as it takes focus, so the
     * page follows the focus -- the picker's flipGridTo, on this screen's panel.
     */
    fun flipManageTo(page: Int) {
        val target = page * with(density) { MANAGE_PANEL_HEIGHT.roundToPx() }
        if (page == managePage && (manageScroll.value == target || manageScroll.isScrollInProgress)) return
        managePage = page
        scope.launch {
            manageScroll.animateScrollTo(
                target,
                tween(durationMillis = GRID_PAGE_FLIP_MS, easing = FastOutSlowInEasing),
            )
        }
    }

    // ── Refusals on another account's profile ─────────────────────────────
    // Set when Change-picture or Delete is pressed on a profile this account
    // does not own; the button it came from takes focus back when it closes.
    var notYours          by remember { mutableStateOf<NoticeContent?>(null) }
    var notYoursReturn    by remember { mutableStateOf<FocusRequester?>(null) }
    var notYoursWasOpened by remember { mutableStateOf(false) }
    val deleteFocus       = remember { FocusRequester() }

    LaunchedEffect(notYours) {
        if (notYours != null) {
            notYoursWasOpened = true
        } else if (notYoursWasOpened) {
            awaitFrame()
            runCatching { notYoursReturn?.requestFocus() }
        }
    }

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
        // This account's profiles only; the button refuses anything else first.
        if (!isOwn(selectedIndex)) return
        val profileToDelete = profiles[selectedIndex]
        scope.launch {
            try {
                ApiClient.bluehiveApi.deleteProfile(profileToDelete.id)
                Log.d(TAG, "🗑️ Deleted profile: ${profileToDelete.display_name}")
                profiles.removeAt(selectedIndex)
                val remaining = profiles.size + peerProfiles.size
                selectedIndex = when {
                    remaining == 0 -> -1
                    selectedIndex >= remaining -> remaining - 1
                    else -> selectedIndex
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to delete profile: ${e.message}")
            }
        }
    }

    // ── Update avatar ─────────────────────────────────────────────────────────
    fun changeSelectedAvatar(newAvatarRes: Int) {
        // This account's profiles only; the button refuses anything else first.
        if (!isOwn(selectedIndex)) return
        val index   = selectedIndex
        val profile = profiles[index]
        val resName = MANAGE_AVATAR_DRAWABLE_MAP.entries
            .firstOrNull { it.value == newAvatarRes }?.key ?: return
        scope.launch {
            try {
                val updated = ApiClient.bluehiveApi.updateProfile(
                    profile.id,
                    ProfileUpdateRequest(display_name = profile.display_name, avatar_url = resName)
                )
                // The PATCH answer carries no seat -- only /profiles/household
                // attaches one -- so keep the card's, or its triangle vanishes.
                profiles[index] = updated.copy(seat = profile.seat)
                Log.d(TAG, "✅ Avatar updated to $resName")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to update avatar: ${e.message}")
            }
            showAvatarPicker = false
        }
    }

    // ── Update name ───────────────────────────────────────────────────────────
    fun changeSelectedName(newName: String) {
        val index   = selectedIndex
        val profile = profileAt(index) ?: return
        val own     = isOwn(index)
        val trimmed = newName.trim().take(15)
        if (trimmed.isEmpty()) {
            showNameEditor = false; return
        }
        scope.launch {
            try {
                val updated = ApiClient.bluehiveApi.updateProfile(
                    profile.id,
                    // Another account's profile takes the NAME ONLY. The server
                    // refuses any other field on one -- even the avatar it
                    // already has (403 profile_not_yours) -- so it goes as null.
                    ProfileUpdateRequest(
                        display_name = trimmed,
                        avatar_url   = if (own) profile.avatar_url else null,
                    )
                ).copy(seat = profile.seat)   // the PATCH answer carries no seat
                if (own) profiles[index] = updated
                else     peerProfiles[index - profiles.size] = updated
                Log.d(TAG, "✅ Name updated to $trimmed")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to update name: ${e.message}")
            }
            showNameEditor = false
        }
    }

    // ANY OVERLAY UP. The cards ask this in their own focusProperties, because
    // the background's canFocus switch below does not reach them -- the grid's
    // scroll container is a focus group of its own, and the switch stops at it.
    //
    // A FUNCTION, READ AT THE MOMENT OF ASKING, not a value taken at
    // composition, and that is the whole fix. Opening an overlay flips the
    // background's switch the instant the state changes, before anything
    // recomposes; the focused button drops focus, Android hands focus straight
    // back into the screen, and Compose looks for somewhere to put it. Asked
    // then, a composition-time value still said "no overlay", so a card took
    // focus and turned the grid to its page -- page one, behind the notice
    // (seen in a logged stack, 2026-09-21).
    fun overlayOpen(): Boolean = showAvatarPicker || showNameEditor || showDeleteConfirm ||
                                 showRecoverOverlay || notYours != null

    // ── The grid as rows ─────────────────────────────────────────────────────
    // Own profiles cut into rows of four, then the household's, from a fresh
    // row. Row numbers run straight through both.
    val ownRows   = (profiles.size + MANAGE_CARDS_PER_ROW - 1) / MANAGE_CARDS_PER_ROW
    val peerRows  = (peerProfiles.size + MANAGE_CARDS_PER_ROW - 1) / MANAGE_CARDS_PER_ROW
    val rowCount  = ownRows + peerRows
    val pageCount = (rowCount + MANAGE_ROWS_PER_PAGE - 1) / MANAGE_ROWS_PER_PAGE

    fun rowStart(row: Int): Int =
        if (row < ownRows) row * MANAGE_CARDS_PER_ROW
        else profiles.size + (row - ownRows) * MANAGE_CARDS_PER_ROW

    fun rowLength(row: Int): Int =
        if (row < ownRows) minOf(MANAGE_CARDS_PER_ROW, profiles.size - row * MANAGE_CARDS_PER_ROW)
        else minOf(MANAGE_CARDS_PER_ROW, peerProfiles.size - (row - ownRows) * MANAGE_CARDS_PER_ROW)

    // The same column one row up or down, or that row's last card when it is
    // shorter; nothing above the first row, and Compose's own search below the
    // last.
    fun cellAt(row: Int, col: Int): FocusRequester =
        focusRequesters.getOrNull(rowStart(row) + minOf(col, rowLength(row) - 1))
            ?: FocusRequester.Default
    fun cellAbove(row: Int, col: Int): FocusRequester =
        if (row <= 0) FocusRequester.Default else cellAt(row - 1, col)
    fun cellBelow(row: Int, col: Int): FocusRequester =
        if (row >= rowCount - 1) FocusRequester.Default else cellAt(row + 1, col)

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
                        !showAvatarPicker && !showNameEditor && !showDeleteConfirm &&
                        !showRecoverOverlay && notYours == null
                }
        ) {
            // ── Profile grid: pages inside the panel ──────────────────────────
            //
            // A window cut to the painted panel's exact bounds, turning two rows
            // at a time -- see MANAGE_PANEL_* and MANAGE_ROWS_PER_PAGE. This
            // account's profiles come first, then the household's, which start on
            // a row of their own; the seat triangles tell the two apart.
            //
            // UP AND DOWN ARE WIRED, NOT SEARCHED, and the end of a row goes to
            // the actions: left to Compose's geometric search, a move could land
            // on a card on a page that is not showing.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = MANAGE_PANEL_X, y = MANAGE_PANEL_Y)
                    .size(width = MANAGE_PANEL_WIDTH, height = MANAGE_PANEL_HEIGHT),
            ) {
                WithoutFocusScroll {
                    Column(modifier = Modifier.verticalScroll(manageScroll)) {
                        for (page in 0 until pageCount) {
                            Box(modifier = Modifier.height(MANAGE_PANEL_HEIGHT).fillMaxWidth()) {
                                for (pageRow in 0 until MANAGE_ROWS_PER_PAGE) {
                                    val row = page * MANAGE_ROWS_PER_PAGE + pageRow
                                    if (row >= rowCount) break
                                    for (col in 0 until rowLength(row)) {
                                        val index   = rowStart(row) + col
                                        val profile = profileAt(index) ?: continue
                                        val avatarResId = remember(profile.avatar_url) {
                                            resolveManageAvatarDrawable(profile.avatar_url)
                                        }
                                        val requester = if (index < focusRequesters.size) {
                                            focusRequesters[index]
                                        } else {
                                            remember { FocusRequester() }.also { focusRequesters.add(it) }
                                        }
                                        ManageProfileCard(
                                            profile        = profile,
                                            avatarResId    = avatarResId,
                                            focusRequester = requester,
                                            seat           = profile.seat,
                                            modifier       = Modifier
                                                .offset(
                                                    x = MANAGE_CELL_X +
                                                        col * (MANAGE_CARD_SIZE + MANAGE_CARD_GAP),
                                                    y = pageRow * MANAGE_ROW_PITCH,
                                                )
                                                .focusProperties {
                                                    canFocus = !overlayOpen()
                                                    up   = cellAbove(row, col)
                                                    down = cellBelow(row, col)
                                                    if (col == rowLength(row) - 1) right = changeImageFocus
                                                },
                                            anyCardFocused = focusedIndex >= 0,
                                            isSelected     = selectedIndex == index,
                                            onFocusChanged = { focused ->
                                                focusedIndex = if (focused) index else -1
                                                if (focused)                                                if (focused) flipManageTo(row / MANAGE_ROWS_PER_PAGE)
                                            },
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
                            }
                        }
                    }
                }
            }

            // Where the grid is among its pages -- only when it has more than one.
            if (pageCount > 1) {
                ManageScrollbar(
                    page      = managePage.coerceIn(0, pageCount - 1),
                    pageCount = pageCount,
                    modifier  = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = MANAGE_SCROLLBAR_X, y = MANAGE_SCROLLBAR_TOP),
                )
            }

            val selectedProfile = profileAt(selectedIndex)
            if (selectedProfile != null) {
                val selectedIsOwn = isOwn(selectedIndex)
                val selectedResId = resolveManageAvatarDrawable(selectedProfile.avatar_url)
                SelectedProfileDetailPanel(
                    profile          = selectedProfile,
                    avatarResId      = selectedResId,
                    isOwn            = selectedIsOwn,
                    changeImageFocus = changeImageFocus,
                    changeNameFocus  = changeNameFocus,
                    recoverBtnFocus  = recoverBtnFocus,
                    deleteFocus      = deleteFocus,
                    gridFocus        = focusRequesters.getOrNull(selectedIndex) ?: FocusRequester.Default,
                    // Picture and delete stay with the account that made the
                    // profile; on anyone else's, say so rather than attempt it.
                    onChangeImage    = {
                        if (selectedIsOwn) showAvatarPicker = true
                        else {
                            notYoursReturn = changeImageFocus
                            notYours       = profileNotYoursNotice(NotYours.Picture)
                        }
                    },
                    onChangeName     = {
                        nameEditorText = selectedProfile.display_name
                        showNameEditor = true
                    },
                    onDelete         = {
                        if (selectedIsOwn) showDeleteConfirm = true
                        else {
                            notYoursReturn = deleteFocus
                            notYours       = profileNotYoursNotice(NotYours.Delete)
                        }
                    },
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
            } else if (profiles.isEmpty() && peerProfiles.isEmpty()) {
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
                                                        // Own again, so it wears the
                                                        // account's seat; and a household
                                                        // selection stays on the same card
                                                        // as the own run grows by one.
                                                        // (With no own profile left to copy
                                                        // a seat from, ask the server.)
                                                        val accountSeat = profiles.firstNotNullOfOrNull { it.seat }
                                                        if (selectedIndex >= profiles.size) selectedIndex += 1
                                                        profiles.add(recovered.copy(seat = accountSeat))
                                                        if (accountSeat == null) {
                                                            val seat = runCatching { ApiClient.bluehiveApi.getHousehold() }
                                                                .getOrNull()?.own?.firstOrNull { it.id == recovered.id }?.seat
                                                            val i = profiles.indexOfFirst { it.id == recovered.id }
                                                            if (seat != null && i >= 0) profiles[i] = profiles[i].copy(seat = seat)
                                                        }
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

        // ── Refused on another account's profile ─────────────────────────────
        notYours?.let { notice ->
            NoticeOverlay(notice) { notYours = null }
        }

    } // end outer Box
} // end ProfileManageScreen


// ─────────────────────────────────────────────────────────────────────────────
//  ManageScrollbar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Where the grid is among its pages: a track the height of the two rows on
 * screen, and a thumb one page long that slides to the page showing, on the
 * page flip's own clock so the two move as one.
 */
@Composable
private fun ManageScrollbar(page: Int, pageCount: Int, modifier: Modifier = Modifier) {
    val thumb  = maxOf(MANAGE_SCROLLBAR_MIN_THUMB, MANAGE_SCROLLBAR_HEIGHT / pageCount)
    val travel = MANAGE_SCROLLBAR_HEIGHT - thumb
    val thumbY by animateDpAsState(
        targetValue   = if (pageCount > 1) travel * page / (pageCount - 1) else 0.dp,
        animationSpec = tween(durationMillis = GRID_PAGE_FLIP_MS, easing = FastOutSlowInEasing),
        label         = "manageScrollThumb",
    )
    Box(
        modifier = modifier
            .size(width = MANAGE_SCROLLBAR_WIDTH, height = MANAGE_SCROLLBAR_HEIGHT)
            .background(MANAGE_SCROLLBAR_TRACK, RoundedCornerShape(percent = 50)),
    ) {
        Box(
            modifier = Modifier
                .offset(y = thumbY)
                .size(width = MANAGE_SCROLLBAR_WIDTH, height = thumb)
                .background(MANAGE_SCROLLBAR_THUMB, RoundedCornerShape(percent = 50)),
        )
    }
}

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
    // "owner" / "member", or null for no triangle -- as on the picker.
    seat:           String?  = null,
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

        // Seat triangle. A layer of its own that takes the card's focus scale
        // and rounded clip but NOT its dimming (the avatar rests at 40%), so
        // the seat reads at full strength on every card, as on the picker.
        Box(
            modifier = Modifier
                .size(MANAGE_CARD_SIZE)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(MANAGE_CARD_CORNER)),
        ) {
            SeatTriangle(
                seat     = seat,
                focused  = isFocused,
                side     = PROFILE_SEAT_TRIANGLE_SIZE,
                modifier = Modifier.align(Alignment.TopStart),
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
    // False for a profile belonging to another account on the subscription:
    // its picture and Delete buttons are drawn restricted, and the screen
    // answers a press on either with a notice instead of acting.
    isOwn:            Boolean,
    changeImageFocus: FocusRequester,
    changeNameFocus:  FocusRequester,
    recoverBtnFocus:  FocusRequester,
    deleteFocus:      FocusRequester,
    // The selected card: left from these buttons goes back to it, even when
    // it is on a page that is not showing (the grid turns to it).
    gridFocus:        FocusRequester,
    onChangeImage:    () -> Unit,
    onChangeName:     () -> Unit,
    onDelete:         () -> Unit,
    onRecover:        () -> Unit,
    onReturn:         () -> Unit,
) {
    val returnFocus = remember { FocusRequester() }
    val backToGrid  = Modifier.focusProperties { left = gridFocus }

    Box(
        modifier = Modifier.offset(x = DETAIL_PANEL_OFFSET_X, y = DETAIL_PANEL_OFFSET_Y)
    ) {
        Box(
            modifier = Modifier
                .size(DETAIL_AVATAR_SIZE)
                .clip(RoundedCornerShape(DETAIL_AVATAR_CORNER)),
        ) {
            Image(
                painter            = painterResource(id = avatarResId),
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
            SeatTriangle(
                seat     = profile.seat,
                focused  = false,
                side     = DETAIL_SEAT_TRIANGLE_SIZE,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

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

        DetailBtnChangeImage(focusRequester = changeImageFocus, onClick = onChangeImage,
                             restricted = !isOwn, modifier = backToGrid)
        DetailBtnChangeName(focusRequester = changeNameFocus,   onClick = onChangeName,
                            modifier = backToGrid)
        DetailBtnDelete(focusRequester = deleteFocus,           onClick = onDelete,
                        restricted = !isOwn)
        DetailBtnRecoverProfile(focusRequester = recoverBtnFocus, onClick = onRecover,
                                modifier = backToGrid)
        DetailBtnReturn(focusRequester = returnFocus,           onClick = onReturn,
                        modifier = backToGrid)
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
    restricted: Boolean = false,
    modifier: Modifier = Modifier,
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
        modifier        = Modifier.offset(x = 0.dp, y = offsetY).then(modifier),
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
        colors          = if (restricted) RESTRICTED_ACTION_COLORS else ModularButtonColors(
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
    modifier: Modifier = Modifier,
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
        modifier        = Modifier.offset(x = 0.dp, y = offsetY).then(modifier),
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
    modifier: Modifier = Modifier,
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
        modifier        = Modifier.offset(x = 0.dp, y = 187.dp).then(modifier),
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
    restricted: Boolean = false,
    modifier: Modifier = Modifier,
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
        modifier        = Modifier.offset(x = 140.dp, y = offsetY).then(modifier),
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
        colors          = if (restricted) RESTRICTED_ACTION_COLORS else ModularButtonColors(
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
    modifier: Modifier = Modifier,
) {
    val offsetY = 245.dp
    val width   = 132.dp

    ModularButton(
        textConfig = ModularButtonTextConfig(text = "Recover a deleted profile", fontSize = 8f),
        isToggleable    = false,
        fontFamily      = AppTypography.interBold,
        focusRequester  = focusRequester,
        onClick         = onClick,
        modifier        = Modifier.offset(x = 140.dp, y = offsetY).then(modifier),
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



