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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import com.example.bluehive.searchBarComponent.HoneycombNamePopup
import com.example.bluehive.searchBarComponent.hiveBackdropBlur
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
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
import com.example.bluehive.utilities.NoticeContent
import com.example.bluehive.utilities.NoticeOverlay
import kotlinx.coroutines.android.awaitFrame
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
//  On failure: raises the shared NoticeOverlay, which states the rule behind
//  the refusal and stays up until the user dismisses it.
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

/**
 * What the user reads when POST /api/bluehive/profiles fails.
 *
 * MATCHES ON THE SERVER'S `code`, NEVER ON ITS PROSE. Checked against
 * `bluehive_plugin/profiles.py`:
 *
 *  - **409 means a limit, never a name clash.** On create it is the only failure
 *    the handler raises itself, and it arrives as an object carrying a stable
 *    code — `account_profile_cap` or `device_profile_quota` — plus the `limit`
 *    that was hit. `create_profile` does not check for a duplicate display_name
 *    at all, only `update_profile` does, and no unique index backs it. The old
 *    code mapped a bare 409 to "A profile with that name already exists.", so it
 *    fired precisely when the account was FULL and told the user to rename when
 *    the remedy was to delete. Matching on the code is what stops that
 *    recurring the next time the sentence changes.
 *  - **403 is never a profile limit.** It is `entitlement_inactive` from
 *    `bh_auth.require_user` — suspended account, revoked device, locked host.
 *    ApiClient's interceptor normally catches it first and opens the lockout
 *    screen; this is the wording for when this screen sees it anyway.
 *
 * The two limit messages come from [profileLimitNotice] rather than being
 * written here, because ProfileScreen refuses the same two limits from the
 * other direction — up front, off the quota endpoint — and the user should
 * not get different words depending on which way they arrived.
 */
private fun createProfileError(e: HttpException): NoticeContent {
    // errorBody() is a one-shot stream — read it once, here, or the second
    // caller gets an empty body and every 409 collapses into one message.
    val raw = runCatching { e.response()?.errorBody()?.string().orEmpty() }.getOrDefault("")
    // FastAPI wraps the payload in "detail". The limits answer with an object
    // carrying `code`; older handlers answer with a bare string.
    val body  = runCatching { org.json.JSONObject(raw).opt("detail") }.getOrNull()
                    as? org.json.JSONObject
    val code  = body?.optString("code").orEmpty()
    val limit = body?.optInt("limit", 0) ?: 0          // 0 reads as "don't quote a number"

    return when {
        code == "device_profile_quota" -> profileLimitNotice(ProfileLimit.Device, limit)
        code == "account_profile_cap"  -> profileLimitNotice(ProfileLimit.Account, limit)

        raw.contains("already exists", ignoreCase = true) ->
            NoticeContent("A profile with that name already exists.")

        e.code() == 403 -> NoticeContent(
            title   = "This account isn't active right now.",
            message = "Profiles can't be added while the subscription or this device is " +
                      "inactive. Open the host app on this TV to check its status.",
        )

        else -> NoticeContent(
            "Connection to the servers not available, please try again later...",
        )
    }
}

// ── Action button config ──────────────────────────────────────────────────────

private val ACTION_BUTTONS_OFFSET_Y: Dp = 420.dp

// ── Avatar picker row config ──────────────────────────────────────────────────

private val AVATAR_SPACING: Dp = 65.dp

// ── Avatar grid geometry, all relative to the panel's top-left ──────────────
//
// The left inset is deliberately bigger than the margin left over on the right.
// The space it opens up is a gutter for a scrollbar, the way the manage grid
// keeps one 8dp inside its panel at MANAGE_SCROLLBAR_X -- nothing draws there
// yet, this just stops the grid sitting where it will go.
//
// Measured against a 563.7 x 274.7dp panel, with 54dp avatars under a 77dp glow
// that overhangs them by 11.65dp on every side:
//
//   left gutter   34 - 11.65                    = 22.35dp clear
//   right margin  563.7 - (34 + 455 + 54 + 11.65) = 9.05dp clear
//   top margin    16 - 11.65                      = 4.35dp clear
//   bottom margin 278.0 - (208 + 54 + 11.65)      = 4.35dp clear
private val AVATAR_GRID_LEFT_INSET: Dp = 34.dp

/** Y of the first row inside the panel. */
private val AVATAR_ROW_FIRST_Y: Dp = 16.dp

/** The painted panel, and so also the window the grid scrolls inside. */
private val AVATAR_PANEL_WIDTH: Dp  = 563.7.dp
private val AVATAR_PANEL_HEIGHT: Dp = 278.0.dp

/** Rendered size of one avatar. The row pitch and the scrollbar both use it. */
private val AVATAR_SIZE: Dp = 54.dp

/** Row pitch: the avatar plus 10dp of clear space under it. */
private val AVATAR_ROW_PITCH: Dp = AVATAR_SIZE + 10.dp

// ─────────────────────────────────────────────────────────────────────────────
//  AvatarPickerPanel
//
//  Self-contained reusable composable that renders:
//    • A dark rectangle base layer (563.7×274.7dp, #FF121213 at 80% opacity,
//      stroke #FF3A3737)
//    • Up to 24 avatars arranged in 3 rows of 8, auto-wrapping
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
//    focusRequesters   — list of FocusRequesters, one per avatar slot (size 24)
//
//  To add more avatars later: add entries to AVATAR_PANEL_DRAWABLES below.
//  Slots with a null drawable render nothing (placeholder for future avatars).
// ─────────────────────────────────────────────────────────────────────────────

private const val AVATAR_PANEL_COLS     = 8

// THE PANEL SHOWS FOUR ROWS; THE GRID MAY HAVE MORE. Four rows is what the
// 278dp panel holds (4.35 -> 273.65dp, with the same 4.35dp clear top and
// bottom). Anything past that scrolls, so height no longer caps the avatar
// count -- AVATAR_PANEL_ROWS is now just slot capacity, with room to spare.
private const val AVATAR_PANEL_VISIBLE_ROWS = 4
private const val AVATAR_PANEL_ROWS     = 8
const val AVATAR_PANEL_TOTAL     = AVATAR_PANEL_COLS * AVATAR_PANEL_ROWS  // 64

/**
 * Rows the grid actually holds, from the drawables present -- NOT from the slot
 * capacity above. The scrollbar sizes itself from this, so adding avatars is
 * only ever a matter of dropping files in and listing them below.
 */
val AVATAR_PANEL_CONTENT_ROWS: Int
    get() = (AVATAR_PANEL_DRAWABLES.size + AVATAR_PANEL_COLS - 1) / AVATAR_PANEL_COLS

/**
 * Add new avatar drawables here as they become available. Null = not yet added.
 *
 * The list is as long as there are avatars -- no null padding. Slots past its
 * end are simply absent, which the grid loop and targetAt() both already treat
 * as "nothing there". Rows past the fourth scroll into view.
 */
private val AVATAR_PANEL_DRAWABLES: List<Int?> = listOf(
    // ── Row 1 — slots 0-7 ────────
    R.drawable.avatar1,  // slot 0
    R.drawable.avatar2,  // slot 1
    R.drawable.avatar3,  // slot 2
    R.drawable.avatar4,  // slot 3
    R.drawable.avatar5,  // slot 4
    R.drawable.avatar6,  // slot 5
    R.drawable.avatar7,  // slot 6
    R.drawable.avatar8,  // slot 7

    // ── Row 2 — slots 8-15 ────────
    R.drawable.avatar9,  // slot 8
    R.drawable.avatar10, // slot 9
    R.drawable.avatar11, // slot 10
    R.drawable.avatar12, // slot 11
    R.drawable.avatar13, // slot 12
    R.drawable.avatar14, // slot 13
    R.drawable.avatar15, // slot 14
    R.drawable.avatar16, // slot 15

    // ── Row 3 — slots 16-23 ────────
    R.drawable.avatar17, // slot 16
    R.drawable.avatar18, // slot 17
    R.drawable.avatar19, // slot 18
    R.drawable.avatar20, // slot 19
    R.drawable.avatar21, // slot 20
    R.drawable.avatar22, // slot 21
    R.drawable.avatar23, // slot 22
    R.drawable.avatar24, // slot 23

    // ── Row 4 — slots 24-31 ────────
    R.drawable.avatar25, // slot 24
    R.drawable.avatar26, // slot 25
    R.drawable.avatar27, // slot 26
    R.drawable.avatar28, // slot 27
    R.drawable.avatar29, // slot 28
    R.drawable.avatar30, // slot 29
    R.drawable.avatar31, // slot 30
    R.drawable.avatar32, // slot 31

    // ── Row 5 — slots 32-35 ────────
    R.drawable.avatar33, // slot 32
    R.drawable.avatar34, // slot 33
    R.drawable.avatar35, // slot 34
    R.drawable.avatar36, // slot 35
)

/**
 * Default per-row Y offsets relative to the panel origin.
 *
 * Derived from the pitch rather than written out, so changing the spacing is one
 * number and the rows cannot drift out of step with each other. Pass a list of
 * your own to rowOffsetYList to place rows independently.
 *
 * The four rows VISIBLE at a time occupy 4.35 → 81.65, 68.35 → 145.65, 132.35 →
 * 209.65 and 196.35 → 273.65dp of the 278dp panel. Later rows carry on past the
 * bottom edge and are scrolled up into that window.
 */
val AVATAR_PANEL_ROW_OFFSETS: List<Dp>
    get() = List(AVATAR_PANEL_CONTENT_ROWS) { row ->
        AVATAR_ROW_FIRST_Y + AVATAR_ROW_PITCH * row
    }

// ── Scrollbar ───────────────────────────────────────────────────────────────
//
// Lives in the gutter the grid's left inset opens up. Measured so the bloom
// clears the first column's focus glow:
//
//   bloom   10 - (12 - 4)/2 = 6dp  ..  6 + 12 = 18dp
//   glow    34 - 11.65      = 22.35dp
//
// so 4.35dp of clear air between the widest bloom and the artwork.
private val AVATAR_SCROLLBAR_X: Dp      = 10.dp
private val AVATAR_SCROLLBAR_WIDTH: Dp  = 4.dp
private val AVATAR_SCROLLBAR_TOP: Dp    = AVATAR_ROW_FIRST_Y

/** First row's top to the last visible row's bottom: 16 -> 262dp. */
private val AVATAR_SCROLLBAR_HEIGHT: Dp
    get() = AVATAR_ROW_PITCH * (AVATAR_PANEL_VISIBLE_ROWS - 1) + AVATAR_SIZE

private val AVATAR_SCROLLBAR_TRACK: Color = Color(0xFF3A3012)
private val AVATAR_SCROLLBAR_NEON:  Color = Color(0xFFFFC800)

/**
 * The grid's scrollbar: a dark track with a neon thumb that reads as lit rather
 * than merely coloured.
 *
 * The glow is three rounded rects behind the core, each wider and fainter than
 * the last. Compose has no cheap blur here, and a stack of translucent rounded
 * rects gives the falloff a blur would -- for the price of three draws that only
 * exist while the grid is scrollable.
 *
 * @param progress        0f at the top of the travel, 1f at the bottom.
 * @param visibleFraction thumb length as a fraction of the track: how much of
 *                        the grid is on screen.
 */
@Composable
private fun AvatarGridScrollbar(progress: Float, visibleFraction: Float) {
    val track  = AVATAR_SCROLLBAR_HEIGHT
    val thumb  = track * visibleFraction.coerceIn(0.1f, 1f)
    val thumbY = AVATAR_SCROLLBAR_TOP + (track - thumb) * progress.coerceIn(0f, 1f)
    val core   = AVATAR_SCROLLBAR_WIDTH

    Box(
        modifier = Modifier
            .offset(x = AVATAR_SCROLLBAR_X, y = AVATAR_SCROLLBAR_TOP)
            .size(width = core, height = track)
            .background(AVATAR_SCROLLBAR_TRACK, RoundedCornerShape(percent = 50)),
    )

    // Widest and faintest first, so the core lands on top.
    listOf(8.dp to 0.13f, 5.dp to 0.24f, 2.5.dp to 0.42f).forEach { (spread, alpha) ->
        // CLAMPED TO THE RAIL. Each rect is drawn half its spread above
        // the thumb and half below, so at either end of the travel the widest
        // one hangs 4dp past the track's rounded cap -- glow with no rail under
        // it, which reads as light pouring out of the end of the tube rather
        // than sitting inside it.
        //
        // Only the ENDS move. Everywhere in between top and bottom are
        // unchanged, so the bloom looks exactly as it always did.
        val top    = (thumbY - spread / 2)
            .coerceAtLeast(AVATAR_SCROLLBAR_TOP)
        val bottom = (thumbY + thumb + spread / 2)
            .coerceAtMost(AVATAR_SCROLLBAR_TOP + track)
        Box(
            modifier = Modifier
                .offset(x = AVATAR_SCROLLBAR_X - spread / 2, y = top)
                .size(width = core + spread, height = bottom - top)
                .background(
                    AVATAR_SCROLLBAR_NEON.copy(alpha = alpha),
                    RoundedCornerShape(percent = 50),
                ),
        )
    }

    Box(
        modifier = Modifier
            .offset(x = AVATAR_SCROLLBAR_X, y = thumbY)
            .size(width = core, height = thumb)
            .background(AVATAR_SCROLLBAR_NEON, RoundedCornerShape(percent = 50)),
    )
}





// ── Step headings ───────────────────────────────────────────────────────────
//
// "1st Step: Choose a profile picture" and its two siblings used to be PAINTED
// INTO create_profile_page.webp, which fixed their colour and their weight at
// export time -- changing either meant re-exporting the artwork. They are drawn
// here now, and the backdrop was repainted to remove them, so both are
// properties of the screen.
//
// Positions are the ones the artwork used, in dp: the bitmap is 1920x1080 over a
// 960x540dp screen, so every measured pixel is half a dp.

/** Bright gold for the step number. The same gold the selection tick uses. */
private val STEP_NUMBER_COLOR: Color = Color(0xFFFFD230)
/** Sampled off the original artwork: a cool light grey, not a pure white. */
private val STEP_LABEL_COLOR:  Color = Color(0xFFC9CADF)
private val STEP_FONT_SIZE               = 13.sp

/** Pulls the line box up so the glyphs land where the painted ones did. */
private val STEP_BASELINE_NUDGE_Y: Dp = (-3).dp

@Composable
private fun StepHeading(
    step: String,
    label: String,
    offsetX: Dp,
    offsetY: Dp,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.offset(x = offsetX, y = offsetY + STEP_BASELINE_NUDGE_Y),
    ) {
        Text(
            text       = step,
            color      = STEP_NUMBER_COLOR,
            fontSize   = STEP_FONT_SIZE,
            fontFamily = AppTypography.interBold,
        )
        Spacer(Modifier.width(5.dp))
        // NOT Inter. The app bundles Inter only in SemiBold and Bold, and both
        // are heavier than the face the artwork used here, so matching the
        // original's lightness means the default family at Normal rather than
        // the nearest Inter, which would be a weight too far.
        Text(
            text       = label,
            color      = STEP_LABEL_COLOR,
            fontSize   = STEP_FONT_SIZE,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
fun AvatarPickerPanel(
    offsetX: Dp,
    offsetY: Dp,
    rowOffsetYList:    List<Dp>       = AVATAR_PANEL_ROW_OFFSETS,
    selectedAvatarRes: Int?,
    onAvatarSelected:  (Int) -> Unit,
    focusRequesters:   List<FocusRequester>,
    // Lets the screen above mute the swap sound for a moment. A LAMBDA, not a
    // Boolean: it is read when focus actually arrives, which is a frame or so
    // after the composition that set it, and a captured Boolean would be stale
    // by exactly the margin that matters.
    soundsEnabled:     () -> Boolean = { true },
    // Keeps D-pad focus inside the grid at all four edges. True in the manage
    // overlay, where the panel IS the whole interaction and the screen behind it
    // is deactivated -- a step off the edge there does not move focus somewhere
    // else, it loses focus entirely, and the user is left with nothing lit.
    //
    // False on the creation screen, where a step right off the last column is
    // the intended way to reach the rest of the form.
    trapFocus:         Boolean = false,
) {
    // The swap sound means "you moved between avatars", so the focus that lands
    // when the panel opens must not play it -- otherwise every visit announces a
    // move that never happened. Armed BY the opening focus rather than by a
    // frame count, because the requestFocus that delivers it lives in the screen
    // above and its timing is not ours to assume.
    var navSoundArmed by remember { mutableStateOf(false) }

    // ── Scrolling ───────────────────────────────────────────────────────────
    // Measured in ROWS, not pixels. There is no pointer here and no free
    // scrolling: the grid only ever moves because focus went somewhere it could
    // not be seen, so the smallest honest unit is one row. The animation is what
    // makes it read as scrolling rather than as the grid teleporting.
    val contentRows  = AVATAR_PANEL_CONTENT_ROWS
    val maxScrollRow = (contentRows - AVATAR_PANEL_VISIBLE_ROWS).coerceAtLeast(0)
    var scrollRow by remember { mutableStateOf(0) }

    val scrollY by animateDpAsState(
        targetValue   = AVATAR_ROW_PITCH * scrollRow,
        animationSpec = tween(durationMillis = 220),
        label         = "avatarGridScroll",
    )

    // Scroll by the least that brings `row` into the window -- never recentre.
    // Pulling the grid further than it needs to go loses the user their place.
    fun revealRow(row: Int) {
        scrollRow = when {
            row < scrollRow -> row
            row >= scrollRow + AVATAR_PANEL_VISIBLE_ROWS ->
                row - AVATAR_PANEL_VISIBLE_ROWS + 1
            else -> scrollRow
        }.coerceIn(0, maxScrollRow)
    }

    Box(
        modifier = Modifier
            .offset(x = 60.dp, y = 187.dp)
    ) {
        // ── Background rectangle ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .width(AVATAR_PANEL_WIDTH)
                .height(AVATAR_PANEL_HEIGHT)
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

        // Only slots that actually hold an avatar are reachable, so a part-
        // filled last row cannot be walked into. Anything off the grid, off the
        // ends of a row, or past the last avatar resolves to Cancel, which eats
        // the key press and leaves focus where it is.
        fun targetAt(i: Int): FocusRequester =
            if (i in 0 until AVATAR_PANEL_TOTAL && AVATAR_PANEL_DRAWABLES.getOrNull(i) != null) {
                focusRequesters.getOrNull(i) ?: FocusRequester.Cancel
            } else {
                FocusRequester.Cancel
            }

        // ── Avatar grid, inside a window the size of the panel ───────────────
        // Clipped, so rows scrolled out of the window stop at the panel's edge
        // instead of drawing across the screen behind it.
        Box(
            modifier = Modifier
                .size(width = AVATAR_PANEL_WIDTH, height = AVATAR_PANEL_HEIGHT)
                .clipToBounds(),
        ) {
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
                onFocusGained  = {
                    revealRow(row)
                    // Arm on ANY arrival, play only when allowed -- a muted
                    // arrival still counts as the opening one, so the next real
                    // move is not swallowed to pay for it.
                    val wasArmed = navSoundArmed
                    navSoundArmed = true
                    if (wasArmed && soundsEnabled()) {
                        BlueHiveApplication.playProfileSwapSound()
                    }
                },
                modifier       = Modifier
                    .offset(
                        x = AVATAR_GRID_LEFT_INSET + col * AVATAR_SPACING,
                        y = rowY - scrollY,
                    )
                    .then(
                        if (!trapFocus) Modifier else Modifier.focusProperties {
                            // Wired, not searched: Compose's geometric search
                            // finds nothing past the edge and drops focus.
                            left  = if (col > 0) targetAt(slot - 1)
                                    else FocusRequester.Cancel
                            right = if (col < AVATAR_PANEL_COLS - 1) targetAt(slot + 1)
                                    else FocusRequester.Cancel
                            up    = targetAt(slot - AVATAR_PANEL_COLS)
                            down  = targetAt(slot + AVATAR_PANEL_COLS)
                        }
                    ),
                imageConfig = AvatarImageConfig(
                    width        = AVATAR_SIZE,
                    height       = AVATAR_SIZE,
                    offsetX      = 0.dp,
                    offsetY      = 0.dp,
                    cornerRadius = 4.dp,
                    // PROFILE_CARD_EDGE_WIDTH and its colour, from the picker.
                    strokeColor  = Color(0xB9555566),
                    strokeWidth  = 1.dp,
                ),
                // The picker's glow is 1.43x its card and centred on it
                // (152.5 / 106.6, offset -23). Same ratios at avatar scale.
                glowConfig = AvatarGlowConfig(
                    width   = 77.25.dp,
                    height  = 77.0.dp,
                    offsetX = (-11.65).dp,
                    offsetY = (-11.65).dp,
                ),
            )
        }
        } // end scroll window

        // ── Scrollbar ────────────────────────────────────────────────────────
        // Outside the window on purpose: it must not scroll with the grid, and
        // its bloom must not be clipped by the window's edge.
        if (maxScrollRow > 0) {
            AvatarGridScrollbar(
                progress        = scrollY / (AVATAR_ROW_PITCH * maxScrollRow),
                visibleFraction = AVATAR_PANEL_VISIBLE_ROWS.toFloat() / contentRows,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────







class ProfileCreationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        // Edge to edge: the system bars are hidden below, and the layout is
        // absolutely positioned on a full-screen Box, so nothing depends on the
        // decor fitting.
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
    // True while the honeycomb keyboard is up for the name (HoneycombNamePopup).
    var isNameFieldEditing by remember { mutableStateOf(false) }
    var isNameFieldFocused by remember { mutableStateOf(false) }
    var nameKeyboardWasOpen by remember { mutableStateOf(false) }

    // ── Submission state ──────────────────────────────────────────────────────
    var isSubmitting    by remember { mutableStateOf(false) }
    var errorMessage    by remember { mutableStateOf<NoticeContent?>(null) }

    // ── Focus requesters ──────────────────────────────────────────────────────
    val standardFocusRequester  = remember { FocusRequester() }
    val kidsFocusRequester      = remember { FocusRequester() }
    val avatarFocusRequesters   = remember { List(AVATAR_PANEL_TOTAL) { FocusRequester() } }
    val nameContainerRequester  = remember { FocusRequester() }
    val createButtonRequester  = remember { FocusRequester() }
    val cancelButtonRequester  = remember { FocusRequester() }

    // ── Navigation sound ──────────────────────────────────────────────────────
    // The sound on the right-hand buttons means "you moved onto this". Focus
    // being PUT on one is not that, so both programmatic moves below -- the
    // opening focus, and the radio pair handing focus to each other -- run
    // through moveFocusSilently and stay quiet. Same contract the profile picker
    // and the manage screen use.
    //
    // The avatar grid keeps its own arming inside AvatarPickerPanel: it plays a
    // different sound, and the manage screen reuses that panel without this one.
    var navSoundArmed by remember { mutableStateOf(false) }

    suspend fun moveFocusSilently(target: () -> Unit) {
        navSoundArmed = false
        awaitFrame()
        runCatching { target() }
        awaitFrame()
        navSoundArmed = true
    }

    // ── Radio button focus transfer ───────────────────────────────────────────
    // Picking one of the pair makes it unfocusable, so focus has to go to the
    // other. That is the screen tidying up after a press that already made its
    // own sound -- chirping here would double it.
    LaunchedEffect(isStandardSelected, isKidsSelected) {
        if (isReady) {
            if (isStandardSelected) moveFocusSilently { kidsFocusRequester.requestFocus() }
            else if (isKidsSelected) moveFocusSilently { standardFocusRequester.requestFocus() }
        }
    }

    // makes the first avatar the first focusable element on the page
    LaunchedEffect(isReady) {
        if (isReady) {
            moveFocusSilently { avatarFocusRequesters[0].requestFocus() }
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
    //   5. On any failure: show the red error overlay — never silently swallow
    //      errors. The wording comes from [createProfileErrorMessage], which
    //      maps the codes the backend ACTUALLY sends (see its doc comment).
    fun submitProfile() {
        if (isSubmitting) return

        // Client-side validation
        val trimmedName = profileName.trim()
        if (trimmedName.isEmpty()) {
            errorMessage = NoticeContent(
                title   = "Please enter a profile name.",
                message = "Up to 20 characters. You can rename the profile later "
                          + "under Manage Profiles.",
            )
            return
        }
        if (selectedAvatarRes == null) {
            errorMessage = NoticeContent(
                title   = "Please choose a profile picture.",
                message = "Pick any avatar from the panel on the left. This can be "
                          + "changed later under Manage Profiles.",
            )
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
                errorMessage = createProfileError(e)
            } catch (e: Exception) {
                isSubmitting = false
                errorMessage = NoticeContent(
                    "Connection to the servers not available, please try again later...",
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    // Closing the name keyboard hands the focus back to the name field.
    LaunchedEffect(isNameFieldEditing) {
        if (isNameFieldEditing) {
            nameKeyboardWasOpen = true
        } else if (nameKeyboardWasOpen) {
            kotlinx.coroutines.android.awaitFrame()
            runCatching { nameContainerRequester.requestFocus() }
        }
    }

    AnimatedVisibility(
        visible = isReady,
        enter   = fadeIn(animationSpec = tween(durationMillis = ENTRY_FADE_DURATION_MS)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Blurred behind the name keyboard, like search.
                .hiveBackdropBlur(isNameFieldEditing)
        ) {

            // ── Background ────────────────────────────────────────────────────
            Image(
                painter            = painterResource(id = R.drawable.create_profile_page),
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )

            // ── Step headings ─────────────────────────────────────────────────
            StepHeading("1st Step:", "Choose a profile picture", 62.5.dp, 168.dp)
            StepHeading("2nd Step:", "Choose your profile type", 659.0.dp, 168.dp)
            StepHeading("3rd Step:", "Choose your profile type", 658.5.dp, 298.dp)

            // ── Step 1: Avatar picker panel ───────────────────────────────────
            AvatarPickerPanel(
                // One flag now governs both sounds on this screen. Picking a
                // profile type makes that button unfocusable, and Compose parks
                // focus on the first avatar for ~30ms before the radio transfer
                // below moves it to the other button -- which fired the swap
                // sound on a press that never touched the grid.
                soundsEnabled     = { navSoundArmed },
                offsetX           = 73.dp,
                offsetY           = 197.dp,
                selectedAvatarRes = selectedAvatarRes,
                onAvatarSelected  = { res ->
                    selectedAvatarRes = res
                    // Step 1 is done the moment a picture is chosen, so move to
                    // step 2 rather than leaving the user parked in a grid they
                    // have finished with. Silently: the press made its own click
                    // sound, and a nav chirp on top of it reads as a stutter.
                    scope.launch {
                        moveFocusSilently {
                            if (isStandardSelected) kidsFocusRequester.requestFocus()
                            else standardFocusRequester.requestFocus()
                        }
                    }
                },
                focusRequesters   = avatarFocusRequesters,
            )


            // ── Step 2: Standard button ───────────────────────────────────────
            val widthProfileType = 100.dp

            ModularButton(
                textConfig      = ModularButtonTextConfig(text = "Standard", fontSize = 17f),
                isToggleable    = true,
                externalToggled = isStandardSelected,
                isFocusable     = !isStandardSelected,
                onClick = {
                    // Mute first. This press makes the button unfocusable, and
                    // focus leaves it during the recomposition that follows --
                    // before any effect gets a turn.
                    navSoundArmed = false
                    isStandardSelected = true
                    isKidsSelected = false
                },
                fontFamily      = AppTypography.dongleBold,
                focusRequester  = standardFocusRequester,
                onFocusGained   = {
                    if (navSoundArmed) BlueHiveApplication.playNavClickSound()
                },
                modifier        = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = STANDARD_BUTTON_OFFSET_X, y = STANDARD_BUTTON_OFFSET_Y)
                    .focusProperties { down = nameContainerRequester },
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
                onClick = {
                    navSoundArmed = false
                    isKidsSelected = true
                    isStandardSelected = false
                },
                fontFamily      = AppTypography.dongleBold,
                focusRequester  = kidsFocusRequester,
                onFocusGained   = {
                    if (navSoundArmed) BlueHiveApplication.playNavClickSound()
                },
                modifier        = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = KIDS_BUTTON_OFFSET_X, y = KIDS_BUTTON_OFFSET_Y)
                    .focusProperties { down = nameContainerRequester },
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
                // No glow while the keyboard is up -- it has the focus.
                if (isNameFieldFocused && !isNameFieldEditing) {
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
                        // WIRED, NOT SEARCHED. The profile-type pair sits above
                        // this field, but the selected one of the two is made
                        // unfocusable, and Compose's geometric search upward
                        // would land on it and stop. Name the one that can
                        // actually take focus instead.
                        .focusProperties {
                            up = if (isStandardSelected) kidsFocusRequester
                                 else standardFocusRequester
                            down = createButtonRequester
                        }
                        .onFocusChanged { isNameFieldFocused = it.isFocused }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp &&
                                (event.key == Key.DirectionCenter ||
                                        event.key == Key.Enter ||
                                        event.key == Key.NumPadEnter) &&
                                !isNameFieldEditing
                            ) {
                                BlueHiveApplication.playClickSound()
                                isNameFieldEditing = true
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

                    Text(
                        text     = profileName,
                        color    = Color.White,
                        fontSize = 9.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 10.dp)
                            .width(NAME_FIELD_WIDTH - 20.dp),
                    )
                }
            }

            // The honeycomb keyboard, over the blurred page. The name above
            // follows every key, so BACK keeps what was typed, as the system
            // keyboard's did; Done closes it the same way.
            if (isNameFieldEditing) {
                HoneycombNamePopup(
                    initialText = profileName,
                    maxLength   = PROFILE_NAME_MAX_CHARS,
                    onChange    = { profileName = it },
                    onDone      = { name ->
                        profileName = name
                        isNameFieldEditing = false
                    },
                    onDismiss   = { isNameFieldEditing = false },
                )
            }

            // ── Action buttons ────────────────────────────────────────────────

            val width   = 115.dp

            // Create Profile
            ModularButton(
                textConfig      = ModularButtonTextConfig(text = "Create Profile", fontSize = 18f),
                isToggleable    = false,
                fontFamily      = AppTypography.dongleBold,
                focusRequester  = createButtonRequester,
                onFocusGained   = {
                    if (navSoundArmed) BlueHiveApplication.playNavClickSound()
                },
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
                onFocusGained   = {
                    if (navSoundArmed) BlueHiveApplication.playNavClickSound()
                },
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
            // Every refusal on this screen, from an empty name field to a
            // server limit, goes through the app-wide NoticeOverlay.
            //
            // IT USED TO BE A BARE Text THAT FADED AFTER FOUR SECONDS, and both
            // halves of that were wrong. The Text had no width bound, so any
            // message longer than a few words ran the full width of the screen
            // and into overscan. The timer set up a race between reading and
            // understanding: miss it and the only way back to the words was to
            // repeat the action that failed. It now stays until the user
            // dismisses it, with OK or with BACK.
            errorMessage?.let { err ->
                NoticeOverlay(err) { errorMessage = null }
            }
        }
    }
}