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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
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
import com.example.bluehive.api.ProfileQuotaResponse
import com.example.bluehive.api.ProfileResponse
import com.example.bluehive.auth.ActiveProfile
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
import com.example.bluehive.utilities.NoticeContent
import com.example.bluehive.utilities.NoticeOverlay
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
    "avatar7"  to R.drawable.avatar7,
    "avatar8"  to R.drawable.avatar8,
    "avatar9"  to R.drawable.avatar9,
    "avatar10" to R.drawable.avatar10,
    "avatar11" to R.drawable.avatar11,
    "avatar12" to R.drawable.avatar12,
    "avatar13" to R.drawable.avatar13,
    "avatar14" to R.drawable.avatar14,
    "avatar15" to R.drawable.avatar15,
    "avatar16" to R.drawable.avatar16,
    "avatar17" to R.drawable.avatar17,
    "avatar18" to R.drawable.avatar18,
    "avatar19" to R.drawable.avatar19,
    "avatar20" to R.drawable.avatar20,
    "avatar21" to R.drawable.avatar21,
    "avatar22" to R.drawable.avatar22,
    "avatar23" to R.drawable.avatar23,
    "avatar24" to R.drawable.avatar24,
    "avatar25" to R.drawable.avatar25,
    "avatar26" to R.drawable.avatar26,
    "avatar27" to R.drawable.avatar27,
    "avatar28" to R.drawable.avatar28,
    "avatar29" to R.drawable.avatar29,
    "avatar30" to R.drawable.avatar30,
    "avatar31" to R.drawable.avatar31,
    "avatar32" to R.drawable.avatar32,
    "avatar33" to R.drawable.avatar33,
    "avatar34" to R.drawable.avatar34,
    "avatar35" to R.drawable.avatar35,
    "avatar36" to R.drawable.avatar36,
)

// internal, not private: the lockout screen's slot list draws the same avatars.
internal fun resolveAvatarDrawable(resName: String?): Int =
    AVATAR_DRAWABLE_MAP[resName ?: ""] ?: R.drawable.avatar1

// ─────────────────────────────────────────────────────────────────────────────
//  ProfileScreenActivity
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "ProfileScreen"

/** Delay (ms) before launching ProfileCreationActivity after button press. */
private const val CREATE_BUTTON_LAUNCH_DELAY_MS = 700L

/**
 * Which control the user left this screen from, so focus can be put back on it
 * when they return.
 *
 * AN ENUM RATHER THAN THE FocusRequester ITSELF, for a dull but decisive
 * reason: the launchers that need to record this are declared above the
 * requesters they would have to name, and a Kotlin lambda cannot capture a
 * local declared later in the same block. A token has no such ordering
 * problem, and it survives the requesters being recreated.
 */
private enum class ReturnFocus { None, Manage, SignOut }

// ── Profile card grid config ──────────────────────────────────────────────────

val PROFILE_CARD_SIZE: Dp = 106.6.dp
val PROFILE_CARD_GAP: Dp = 19.5.dp
// SIX, NOT FOUR, because the account ceiling is 12 (four paired devices at
// three profiles each) and the grid draws exactly two rows. Six per row is what
// makes all twelve reachable without inventing a third row: at 126.1dp per card
// a full row spans 737dp of the 960dp design space, where a third row would
// have started below y475 in a space 540dp tall.
const val PROFILE_CARDS_PER_ROW = 6

/**
 * How many profile cards this screen can DRAW. Not the account's limit.
 *
 * The grid is two absolutely-positioned rows inside a fixed 960x540 dp design
 * space and the row Y is a two-way choice (top row or bottom row), so card 13
 * would be painted directly on top of card 7. Six per row times two rows is
 * therefore a hard ceiling on what can be SHOWN, and the create button hides at
 * this number rather than offering a slot the grid cannot render.
 *
 * IT MATCHES THE SERVER'S CEILING ON PURPOSE: MAX_PROFILES_PER_USER is 12, being
 * four paired devices at three profiles each. If that moves, this has to move
 * with it or the account can hold profiles the TV cannot show — and the layout
 * is what constrains it, not the arithmetic. Everything else (the create
 * button, the focus wiring, the row maths) reads from these two constants.
 */
const val PROFILE_GRID_CAPACITY = PROFILE_CARDS_PER_ROW * 2

/** A cell: the card plus its name label. Rows are built from these. */
val PROFILE_CELL_HEIGHT: Dp get() = PROFILE_CARD_SIZE + 5.dp + PROFILE_NAME_HEIGHT

/** Gap between rows. The old absolute layout used a 145dp pitch, so this is
 *  what is left of it once the cell itself is accounted for -- a laid-out grid
 *  then lands exactly where the positioned one used to. */
val PROFILE_ROW_SPACING: Dp get() = 145.dp - PROFILE_CELL_HEIGHT

/**
 * One PAGE of the grid, which is also exactly how much of it is visible.
 *
 * TWO ROWS, because that is what fits between the subtitle painted into the
 * artwork and the Sign Out strip -- and because keeping it at two means an
 * account with no subscription peers sees exactly the screen it saw before any
 * of this existed.
 *
 * THE GRID TURNS A WHOLE PAGE AT A TIME, never a row. Every page is built to
 * this exact height and the window is this exact height, so at rest the window
 * frames one page edge to edge and no row can be left half on screen. It used
 * to scroll by row, and Android TV's default for that parks the focused row
 * 30% of the way down the window (PivotBringIntoViewSpec, Compose foundation)
 * -- which in a window two rows tall sliced the row above it and the row below
 * it in half. See NoGridFocusScroll and flipGridTo().
 *
 * Page 0 is the account's own profiles (twelve at most, so always one page);
 * the household's follow, twelve to a page, each under its own heading. When
 * the account's own fit on ONE row, the household's first row takes page 0's
 * second, as a plain second row: no heading there, the seat triangles tell the
 * two apart.
 */
val GRID_PAGE_HEIGHT: Dp get() =
    GRID_PAGE_HEADER + PROFILE_CELL_HEIGHT * 2 + PROFILE_ROW_SPACING + GRID_GLOW_FOOTROOM

/**
 * The band across the top of every page, above its first row.
 *
 * On the account's own page it is empty: it is the room the first row's focus
 * glow needs (GRID_GLOW_HEADROOM), since the window clips whatever is painted
 * outside it. On a household page it also carries the "Others on your
 * subscription" heading, which is why it is 5dp deeper than the glow alone
 * needs -- see GRID_HEADING_CLEARANCE.
 *
 * The rows stay on GRID_OFFSET_Y_TOP_ROW / _BOTTOM_ROW on every page; only the
 * window's top edge moved, from 137 to 132, still 8.5dp below the subtitle,
 * and at rest nothing but a focused card's glow ever reaches it.
 */
val GRID_PAGE_HEADER: Dp = 28.dp

/**
 * From the bottom of the section heading's text box to the first card.
 *
 * MEASURED ON THE TV, 2026-09-21: a focused card grows 1.06x, which lifts its
 * white ring 6dp above the card's resting top, and the bloom round the ring
 * fades into the background over the next ~10dp. 14dp keeps the heading's
 * descenders clear of all but the last faint edge of it, and leaves the
 * heading's cap tops 5dp inside the window.
 */
val GRID_HEADING_CLEARANCE: Dp = 14.dp

/** One page flip, top to bottom of the easing curve. */
const val GRID_PAGE_FLIP_MS = 350

/** The section heading and the hint that points to it share a colour. */
val GRID_SECTION_LABEL_COLOR = Color(0xFF6E7BA8)

/**
 * Top of the "N more on your subscription" hint. Sets its text on the same
 * baseline as the Sign Out label beside it (glyphs measured at 465.5..474.5,
 * baseline 472.7; a 9sp Inter line puts its baseline 8.7dp below its top).
 */
val GRID_MORE_HINT_Y: Dp = 464.dp

/**
 * Blank space the viewport keeps ABOVE the first row, and the reason it exists.
 *
 * Modifier.verticalScroll clips along its scroll axis. A profile card's focus
 * glow is drawn 23dp ABOVE the card (PROFILE_CARD_GLOW_OFFSET_Y), which cost
 * nothing while the grid was absolutely positioned and free to paint anywhere
 * on the screen -- but inside a scroll viewport whose top edge is the card's
 * own top edge, the ring on every first-row card was sliced flat. It read as
 * the row being cut off, which is exactly what it was.
 *
 * The headroom lives inside each page, as part of GRID_PAGE_HEADER, so the
 * cards still land on GRID_OFFSET_Y_TOP_ROW to the pixel; only the window
 * around them grew.
 *
 * (The cross axis needs no such treatment: clipScrollableContainer leaves 30dp
 * of slack perpendicular to the scroll direction for exactly this kind of
 * shadow, which is more than the glow's 23dp of horizontal overhang.)
 */
val GRID_GLOW_HEADROOM: Dp get() = -PROFILE_CARD_GLOW_OFFSET_Y

/**
 * And below a page's second row, for the 5dp a focused card's name label drops.
 *
 * Deliberately small: the window's bottom lands at 444.6dp and the Sign Out
 * strip's face begins at 454.5, so this is spending the last of the gap
 * between them.
 */
val GRID_GLOW_FOOTROOM: Dp = 6.dp

/** Six cells and the five gaps between them. */
val GRID_ROW_WIDTH: Dp get() =
    PROFILE_CARD_SIZE * PROFILE_CARDS_PER_ROW +
    PROFILE_CARD_GAP * (PROFILE_CARDS_PER_ROW - 1)
val PROFILE_CARD_CORNER: Dp = 5.dp

// The grey edge round every UNFOCUSED card (the focused one drops it for the
// white glow). 1dp -- a crisp 2px line on this 2x panel -- down from 1.5dp,
// which read as a heavy frame across a grid of twelve. Thinner also uncovers
// a little more of each seat triangle, whose outer edges this line overlays.
val PROFILE_CARD_EDGE_WIDTH: Dp = 1.dp

// The create button's tile, and how far into its grid cell it sits. Named
// because the row centring measures a row that ends in the button to the
// tile's right edge -- change the placement and the centring has to know.
val CREATE_TILE_SIZE: Dp  = 77.25.dp
val CREATE_TILE_INSET: Dp = 18.dp

// ── Seat triangles ───────────────────────────────────────────────────────────
//
// WHICH SEAT ON THE SUBSCRIPTION A PROFILE BELONGS TO, as a corner triangle on
// the card's top-left: amber for the admin's profiles, purple for a member's.
// Any profile can be entered by anyone on the subscription, so this is
// information, not permission.
//
// THE SAME DEVICE AS THE WATCHED MARKER on the episode grid (WatchedCornerFold),
// mirrored: that one sits top-RIGHT and means "you've seen this", so the seat
// mark takes the opposite corner and the two can never be read as each other.
// 20dp, about 19% of the 106.6dp card. It has been 22dp (a fifth of the card,
// the watched fold's proportion) and 16dp; 20 was settled on between the two.
//
// THIRD ATTEMPT AT THIS, and why each earlier one went. A coloured frame round
// the whole card fought the white focus glow, which has to stay the loudest
// thing on screen. A dot beside the name was too small to register from across
// a room. A corner triangle is large enough to read, and stays clear of the
// focus ring because the card clips it to its own rounded corner.
//
// 70% SATURATION, hue and lightness unchanged from the colours already
// approved (HLS 35.9/57.5% and 260.0/71.0%), so the pair is the same two
// colours as before, only stronger -- enough to read as a marker at card size.
//
// SHOWN WHENEVER THE SEAT IS KNOWN, household or not. An account that
// installed BlueHive without Off Grid Drive is its own admin, so its profiles
// carry the admin amber -- never the member purple, which would claim someone
// else pays for them. The server decides that (profiles._seats_for) from
// account-level evidence rather than the install flag, which adb reinstalls
// rewrite. A seat of null -- a host-paired account whose first entitlement
// report has not landed yet -- draws nothing rather than guessing.
val PROFILE_SEAT_TRIANGLE_SIZE: Dp = 20.dp
//
// FULL SATURATION ON THE FOCUSED CARD. Every triangle rests at 70%; the one on
// the card that has focus rises to 100%, on the same hue and lightness, so the
// focused card's seat reads loudest without a single colour changing identity.
// It animates on the card's own 200ms focus tween, alongside the tint and the
// name, rather than snapping.
// The admin amber is #D19814, chosen by eye rather than derived: a deeper,
// more golden amber (HLS 41.9 / 44.9% / 82.5%) than the 70%-rule colour it
// replaced, so it rests hotter than the member purple does. Its focus shade
// keeps the rule -- same hue and lightness, saturation to 100%.
val PROFILE_SEAT_ADMIN_COLOR         = Color(0xFFD19814)   // amber,  83% sat
val PROFILE_SEAT_ADMIN_FOCUS_COLOR   = Color(0xFFE5A000)   // amber, 100% sat
val PROFILE_SEAT_MEMBER_COLOR        = Color(0xFFA481E9)   // purple,  70% sat
val PROFILE_SEAT_MEMBER_FOCUS_COLOR  = Color(0xFF9D6BFF)   // purple, 100% sat

/** Triangle colour for a seat in a given focus state, or null for no triangle. */
fun profileSeatColor(seat: String?, focused: Boolean): Color? = when (seat) {
    "owner"  -> if (focused) PROFILE_SEAT_ADMIN_FOCUS_COLOR  else PROFILE_SEAT_ADMIN_COLOR
    "member" -> if (focused) PROFILE_SEAT_MEMBER_FOCUS_COLOR else PROFILE_SEAT_MEMBER_COLOR
    else     -> null
}

/**
 * The seat triangle itself, for any card that shows one: a right triangle in
 * the top-left corner of the box it is placed in, [side] along each leg, in the
 * seat's colour -- resting, or full saturation while [focused], easing between
 * the two on the cards' 200ms focus tween. Nothing at all for a null seat.
 *
 * Place it INSIDE the card's clip, so the card's rounded corner shapes it, and
 * outside anything that dims the avatar, so the seat reads at full strength on
 * an unfocused card too -- both as on the profile picker.
 */
@Composable
internal fun SeatTriangle(
    seat:     String?,
    focused:  Boolean,
    side:     Dp,
    modifier: Modifier = Modifier,
) {
    val target = profileSeatColor(seat, focused) ?: return
    val color by androidx.compose.animation.animateColorAsState(
        targetValue   = target,
        animationSpec = tween(durationMillis = 200),
        label         = "seatTriangle",
    )
    androidx.compose.foundation.Canvas(modifier = modifier.size(side)) {
        val leg = size.width
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(leg, 0f)
                lineTo(0f, leg)
                close()
            },
            color = color,
        )
    }
}

// ROW Y, MEASURED AGAINST THE ARTWORK RATHER THAN CHOSEN.
//
// The blue card behind this screen is painted into the profile_screen drawable,
// not laid out in code, so nothing here knows where its edges are and the two
// drifted apart as the grid grew. Measured off a 1920x1080 capture (exactly 2px
// per dp) on 2026-09-20:
//
//     panel        40 .. 504      title 81 .. 108.5     subtitle 112 .. 123.5
//
// A card column is 133.6dp tall (106.6 card + 5 padding + 22 name) and THE FOCUS
// GLOW REACHES 23dp ABOVE IT -- which is the measurement that actually sets this
// number. The card art at 160 looks a comfortable 36dp clear of the subtitle,
// but the glow that appears the moment a top-row card takes focus starts at 137,
// and it was the glow crowding the subtitle at 148 (1.5dp) that read as "too
// close to the text" on the panel.
//
// Now: 13.5dp from the subtitle to the glow, 15.9dp from the row-2 name to the
// button strip, 8.5dp from the strip's own bloom to the panel edge.
//
// The 145dp pitch between the rows is not a free number -- it is the card block
// plus an 11.4dp breather -- so both rows move together and only the pair's
// position is tuned.
val GRID_OFFSET_Y_TOP_ROW: Dp    = 160.dp
val GRID_OFFSET_Y_BOTTOM_ROW: Dp = 305.dp

/**
 * Y of the Sign Out / Manage strip.
 *
 * IT USED TO HANG OFF THE BOTTOM OF THE PANEL. At 490 the button faces ran from
 * 496 to 528 while the artwork's card ends at 504, so the strip straddled the
 * edge and the lower half sat on bare background -- visible in any screenshot,
 * and the thing this was moved to fix.
 *
 * 448 puts the whole control inside: the faces occupy 448..486 and even the
 * focus bloom, which extends 41dp from +7, stops at 496, leaving 8.5dp to the
 * panel edge.
 *
 * TIED TO THE GRID, SO CHECK BOTH TOGETHER. The row-2 cards now end at 438.6,
 * so the gap above this strip is 15.9dp and it is the first thing consumed by
 * moving GRID_OFFSET_Y_BOTTOM_ROW down again. There is about 10dp of headroom
 * left here before the bloom starts touching the panel edge.
 */
val BOTTOM_STRIP_Y: Dp = 448.dp


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
// 9sp: taken down 2sp from 11 on request, to sit quieter under the cards now
// that a seat triangle carries part of the identification the name used to.
const val PROFILE_NAME_FONT_SIZE_SP   = 9f
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

    override fun onResume() {
        super.onResume()
        // The picker is on screen, so nobody is on a profile. The next heartbeat
        // tells the server, for other TVs' lockout lists.
        ActiveProfile.id = null
    }

    private fun goHome(profileId: Int, displayName: String, avatarResId: Int) {
        Log.d(TAG, "Profile selected → id=$profileId name=$displayName")
        // Before the select call, so no request built after it still says "none".
        ActiveProfile.id = profileId
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
    val density = LocalDensity.current

    val profiles        = remember { mutableStateListOf<ProfileResponse>() }

    // Profiles belonging to OTHER accounts on the same subscription licence.
    // A separate list from `profiles` on purpose: every existing rule here reads
    // that one as "mine" -- the quota, the create ceiling, the focus targeting
    // and the sign-out focus fallback are all counted in own profiles and must
    // not start counting the household's.
    val peerProfiles        = remember { mutableStateListOf<ProfileResponse>() }
    val peerFocusRequesters = remember { mutableStateListOf<FocusRequester>() }
    val gridScroll          = rememberScrollState()

    // The page the grid was last sent to: 0 is this account's own, then the
    // household's, twelve to a page. The TARGET of the last flip rather than
    // the scroll position, so the hint below the grid changes as a flip starts
    // instead of halfway through it.
    var gridPage by remember { mutableStateOf(0) }

    /**
     * Turn the grid to [page].
     *
     * Every cell calls this as it takes focus, so the page follows the focus:
     * down from the last row of one page lands on the first row of the next,
     * and the window turns with it. The pages are exactly the window's height
     * (GRID_PAGE_HEIGHT), so page N starts at N window-heights and the flip
     * always comes to rest on a whole page. A flip still running when another
     * is asked for is cancelled by it, so the last request wins.
     */
    fun flipGridTo(page: Int) {
        val target = page * with(density) { GRID_PAGE_HEIGHT.roundToPx() }
        if (page == gridPage && (gridScroll.value == target || gridScroll.isScrollInProgress)) return
        gridPage = page
        scope.launch {
            gridScroll.animateScrollTo(
                target,
                tween(durationMillis = GRID_PAGE_FLIP_MS, easing = FastOutSlowInEasing),
            )
        }
    }
    var isLoadingGrid   by remember { mutableStateOf(true) }
    var showRetryBanner by remember { mutableStateOf(false) }

    // WHY THE SCREEN ASKS THE SERVER INSTEAD OF COUNTING CARDS. The profile list
    // is account-wide, so its size says nothing about whether THIS device has
    // used up its own share — a TV that created three of the account's profiles
    // is full while the grid still looks half empty. Only the server knows the
    // per-device figure, and the button has to be gone BEFORE the user picks an
    // avatar and types a name to find out.
    //
    // THE WHOLE RESPONSE, not just can_create: when a press is refused the
    // message has to name which ceiling was hit and what its number is, and a
    // boolean can say neither. null means "not answered yet" and is treated as
    // permission — a timed-out quota lookup should not be able to take profile
    // creation away, and a real attempt still answers 409.
    var quota by remember { mutableStateOf<ProfileQuotaResponse?>(null) }

    // Set when a blocked press needs explaining; null the rest of the time.
    var limitNotice by remember { mutableStateOf<NoticeContent?>(null) }

    // Its own function because the quota has to be re-read after a creation as
    // well as on load — see creationLauncher. A failure leaves it null, which
    // creationBlockedBy() reads as "unknown, let them try".
    suspend fun fetchQuota(): ProfileQuotaResponse? =
        runCatching { ApiClient.bluehiveApi.getProfileQuota() }
            .onFailure { Log.w("ProfileScreen", "quota lookup failed: ${it.message}") }
            .getOrNull()

    suspend fun reloadProfiles() {
        isLoadingGrid   = true
        showRetryBanner = false
        try {
            // HOUSEHOLD FIRST, OWN-ONLY AS THE FALLBACK. /profiles/household is
            // newer than some backends this APK will meet, and a 404 there has
            // to degrade to the picker working as it always did rather than to
            // an empty screen.
            val household = runCatching { ApiClient.bluehiveApi.getHousehold() }
                .onFailure { Log.w("ProfileScreen", "household unavailable: ${it.message}") }
                .getOrNull()
            val fetched = household?.own ?: ApiClient.bluehiveApi.listProfiles()
            peerProfiles.clear()
            peerProfiles.addAll(household?.others.orEmpty())
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

            // Its own runCatching: a failed quota lookup must not throw away the
            // grid that was just fetched successfully.
            quota = fetchQuota()
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

    /**
     * A profile to land on instead of slot 0 the next time focus is placed.
     *
     * Set when one has just been CREATED. The grid effect's default is slot 0,
     * which is right on a cold open — OK then means "continue as me" — but wrong
     * straight after creating, where the profile the user was just describing
     * has appeared somewhere down the row and they have to walk to it.
     *
     * An id rather than an index, because the card is appended locally here and
     * then reordered by the reload that follows; an index would point at
     * whatever took that slot.
     */
    var focusProfileId by remember { mutableStateOf(-1) }

    val creationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data         = result.data ?: return@rememberLauncherForActivityResult
            val newId        = data.getIntExtra(EXTRA_NEW_PROFILE_ID, -1)
            val newName      = data.getStringExtra(EXTRA_NEW_PROFILE_NAME) ?: ""
            val newAvatarRes = data.getStringExtra(EXTRA_NEW_AVATAR_RES_NAME) ?: ""
            if (newId != -1 && newName.isNotEmpty()) {
                // THE SEAT COMES WITH IT. The card is patched in from the
                // creation screen's result rather than fetched, and that result
                // says nothing about seats -- so a new profile used to arrive
                // with none, and no triangle until the next reload. Every
                // profile on an account shares the account's seat, so it is
                // copied from a sibling; an account's very first profile has
                // none to copy, and asks the server just below.
                val accountSeat = profiles.firstNotNullOfOrNull { it.seat }
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
                        seat                 = accountSeat,
                    )
                )

                // Land on it when the grid effect next runs — which the size
                // change on the line above is what triggers.
                focusProfileId = newId

                // RE-READ THE QUOTA, do not just add the card.
                //
                // The grid is patched locally here to avoid a round trip, and
                // for the profile list that is fine. The quota is a different
                // thing: it was fetched on load and creating a profile is
                // precisely what invalidates it. Left stale, a user who creates
                // their first three profiles in one sitting still holds
                // device_used = 0, so the button stays armed, creationBlockedBy()
                // returns null, and the fourth attempt is refused only after an
                // avatar has been picked and a name typed — the exact outcome
                // the quota endpoint exists to prevent.
                scope.launch {
                    quota = fetchQuota()
                    if (accountSeat == null) {
                        val seat = runCatching { ApiClient.bluehiveApi.getHousehold() }
                            .getOrNull()?.own?.firstOrNull { it.id == newId }?.seat
                        val i = profiles.indexOfFirst { it.id == newId }
                        if (seat != null && i >= 0) profiles[i] = profiles[i].copy(seat = seat)
                    }
                }
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

    // ── Where focus goes when a child screen closes ────────────────────────
    // Set by the button that leaves, consumed by whichever effect below sees
    // the screen come back. Compose keeps no focus history — when a focused
    // node is removed, or an Activity is resumed over the top of one, the focus
    // is simply gone — so the intent has to be recorded at the press or it
    // cannot be recovered afterwards.
    var returnFocusTo by remember { mutableStateOf(ReturnFocus.None) }

    // ── Focus after the sign-out overlay closes ─────────────────────────
    //
    // ITS OWN EFFECT BECAUSE DISMISSING AN OVERLAY RELOADS NOTHING. The Manage
    // path is repaired inside the targeting effect below, which only runs when
    // isLoadingGrid moves; closing this overlay moves nothing, so that effect
    // never re-runs and the restore has to hang off the overlay's own flag.
    //
    // ConfirmationOverlay pulls focus onto its Cancel button when it mounts
    // (Overlays.kt) and both dismissal routes — the BackHandler and onCancel —
    // only flip this Boolean. The focused node then leaves the composition with
    // nothing focusable above it, so focus is dropped entirely and the next
    // D-pad press restarts the search from the top of the tree.
    //
    // Guarded on returnFocusTo rather than a separate "was open" latch: the
    // token is only ever SignOut because the user pressed Sign Out, so it
    // doubles as the record that there is something to restore. On first
    // composition it is None and this does nothing.
    LaunchedEffect(showSignOutConfirm) {
        if (showSignOutConfirm || returnFocusTo != ReturnFocus.SignOut) {
            return@LaunchedEffect
        }
        returnFocusTo = ReturnFocus.None
        // Silenced across the move: the nav sound means "you moved between
        // profiles", and focus landing back where it started is not a move.
        val wasArmed = navSoundArmed
        navSoundArmed = false
        kotlinx.coroutines.android.awaitFrame()   // let the overlay finish leaving
        runCatching { signOutFocusRequester.requestFocus() }
        kotlinx.coroutines.android.awaitFrame()
        navSoundArmed = wasArmed
    }

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

        // ── Coming back from a child screen beats the opening focus ────────
        //
        // RETURNING FROM MANAGE PROFILES IS NOT A MISSING RESTORE, IT IS A
        // THEFT, and this is the thief. The launcher reloads unconditionally on
        // return, which flips isLoadingGrid and re-runs this effect, and the
        // effect's job on a first launch is to put focus on slot 0 so the OK
        // button means "continue as me". On a return that same line drags focus
        // off the Manage button and onto the first card.
        //
        // Checked BEFORE the empty-grid branch on purpose: if the reload came
        // back empty, or failed and raised the retry banner, the user still
        // pressed Manage and should still land on Manage.
        val returning = returnFocusTo
        if (returning != ReturnFocus.None) {
            returnFocusTo = ReturnFocus.None
            // One frame so this pass of the composition has placed the buttons;
            // both are drawn unconditionally, so there is nothing else to wait
            // for and no risk of requesting focus on a detached node.
            kotlinx.coroutines.android.awaitFrame()
            when (returning) {
                ReturnFocus.Manage  -> runCatching { manageFocusRequester.requestFocus() }
                ReturnFocus.SignOut -> runCatching { signOutFocusRequester.requestFocus() }
                ReturnFocus.None    -> Unit
            }
            focusParked = false          // release the parking spot
            kotlinx.coroutines.android.awaitFrame()
            navSoundArmed = true
            return@LaunchedEffect
        }

        // ── A profile just created beats the opening focus ─────────────────
        //
        // Same precedence as the return-from-a-child case above and for the
        // same reason: slot 0 is the right landing on a cold open, and the
        // wrong one when the user has just finished describing a profile that
        // is now sitting further along the row.
        val wantedId = focusProfileId
        if (wantedId != -1) {
            focusProfileId = -1
            val index = profiles.indexOfFirst { it.id == wantedId }
            if (index >= 0) {
                // The cards are built as the grid composes, so the requester
                // for a late slot may not exist for a frame or two yet.
                while (focusRequesters.size <= index) {
                    kotlinx.coroutines.android.awaitFrame()
                }
                kotlinx.coroutines.android.awaitFrame()
                runCatching { focusRequesters[index].requestFocus() }
                focusParked = false      // release the parking spot
                kotlinx.coroutines.android.awaitFrame()
                navSoundArmed = true
                return@LaunchedEffect
            }
            // Fell through: the id is not in the list after all. Nothing is
            // returned here on purpose, so the ordinary slot-0 landing below
            // still happens rather than the screen opening with no focus.
        }

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




    // VISIBILITY IS THE GRID'S BUSINESS, NOT THE QUOTA'S.
    //
    // This also required the server's can_create, and so the button simply
    // vanished once this TV had used its three profiles — no message, no trace,
    // nothing left to press to find out why. A control that disappears teaches
    // nothing; it reads as broken. It now stays wherever the grid has a cell to
    // draw it in, and a press that cannot succeed explains itself.
    //
    // At PROFILE_GRID_CAPACITY there is genuinely nowhere to put it: both rows
    // are full of cards and the button has no cell of its own.
    // HIDES ON THE ACCOUNT'S OWN CEILING, which is not the same number for
    // everyone: an owner seat pairs 4 devices at 3 profiles each and so tops out
    // at 12, a member seat pairs 1 and tops out at 3. The server does that
    // arithmetic and hands back account_limit; the client must not assume 12.
    //
    // Read against profiles.size rather than quota.account_used so the button
    // goes the instant the last card appears, without waiting for the quota
    // refetch that follows a creation. The two agree -- the profile list is
    // account-wide -- but only one of them is already on screen.
    //
    // Falls back to the grid's own capacity while the quota is unknown, which
    // is the behaviour that shipped before the ceiling was per-account.
    val accountCeiling   = quota?.account_limit ?: PROFILE_GRID_CAPACITY
    val canCreateProfile = profiles.size < PROFILE_GRID_CAPACITY &&
                           profiles.size < accountCeiling

    /** Null when creation is possible, otherwise the reason it is not. */
    fun creationBlockedBy(): NoticeContent? {
        val q = quota ?: return null                      // unknown — let them try
        if (q.can_create) return null
        return if (q.device_used >= q.device_limit)
                   profileLimitNotice(ProfileLimit.Device,  q.device_limit)
               else
                   profileLimitNotice(ProfileLimit.Account, q.account_limit)
    }

    // ── The grid as rows, for the pages and the D-pad ───────────────────────
    //
    // Own cells first -- the profiles, then the create button when there is
    // one -- then the household's, each run cut into rows of six. The own rows
    // are page 0; the household's rows follow, two to a page. Row numbers run
    // straight through both, so "the row below" means the same thing whether
    // or not it is on the next page.
    val ownCells      = profiles.size + (if (canCreateProfile) 1 else 0)
    val ownRowCount   = (ownCells + PROFILE_CARDS_PER_ROW - 1) / PROFILE_CARDS_PER_ROW
    val peerRowCount  = (peerProfiles.size + PROFILE_CARDS_PER_ROW - 1) / PROFILE_CARDS_PER_ROW
    val gridRowCount  = ownRowCount + peerRowCount
    val ownPageCount  = if (ownRowCount > 0) 1 else 0

    // THE HOUSEHOLD'S FIRST ROW JOINS PAGE 0 WHEN THIS ACCOUNT'S FIT ON ONE ROW.
    // Page 0 has room for two rows. Leaving the second empty and pushing every
    // member profile a whole page down made the household look missing when
    // there was room for it; so it only gets a page of its own once this
    // account's second row has something in it -- a profile or the create
    // button.
    val sharedFirstPage   = ownRowCount == 1 && peerRowCount > 0
    // The household rows that are paged, and their pages.
    val firstPagedPeerRow = if (sharedFirstPage) 1 else 0
    val peerPageCount     = (peerRowCount - firstPagedPeerRow + 1) / 2
    val shownPage         = gridPage.coerceIn(0, (ownPageCount + peerPageCount - 1).coerceAtLeast(0))

    fun rowSize(row: Int): Int =
        if (row < ownRowCount) minOf(PROFILE_CARDS_PER_ROW, ownCells - row * PROFILE_CARDS_PER_ROW)
        else minOf(PROFILE_CARDS_PER_ROW, peerProfiles.size - (row - ownRowCount) * PROFILE_CARDS_PER_ROW)

    fun pageOfRow(row: Int): Int {
        if (row < ownRowCount) return 0
        val peerRow = row - ownRowCount
        return if (peerRow < firstPagedPeerRow) 0
               else ownPageCount + (peerRow - firstPagedPeerRow) / 2
    }

    // How many of the household's profiles are on pages 0..[page] -- for the
    // "more below" count, and for which card Sign Out's left lands on.
    fun peersThrough(page: Int): Int {
        val rows = if (page < ownPageCount) firstPagedPeerRow
                   else firstPagedPeerRow + (page - ownPageCount + 1) * 2
        return minOf(peerProfiles.size, rows * PROFILE_CARDS_PER_ROW)
    }

    fun cellRequester(row: Int, col: Int): FocusRequester {
        if (row < ownRowCount) {
            val i = row * PROFILE_CARDS_PER_ROW + col
            return if (i < profiles.size) focusRequesters.getOrNull(i) ?: FocusRequester.Default
                   else createFocusRequester
        }
        val j = (row - ownRowCount) * PROFILE_CARDS_PER_ROW + col
        return peerFocusRequesters.getOrNull(j) ?: FocusRequester.Default
    }

    // The same column one row up or down, or that row's last cell when it is
    // shorter. Off either end of the grid, Default: nothing above the first
    // row, and the Sign Out strip below the last.
    fun cellAbove(row: Int, col: Int): FocusRequester =
        if (row <= 0) FocusRequester.Default
        else cellRequester(row - 1, minOf(col, rowSize(row - 1) - 1))

    fun cellBelow(row: Int, col: Int): FocusRequester =
        if (row >= gridRowCount - 1) FocusRequester.Default
        else cellRequester(row + 1, minOf(col, rowSize(row + 1) - 1))

    // RIGHT OFF THE END OF ANY ROW GOES TO SIGN OUT -- this account's rows, the
    // household's row that shares page 0, and every row on a household page --
    // the way the create button, which always ends its row, already did.
    //
    // Named rather than left to Compose's geometric search, which looked for
    // the nearest card to the right and often found one on a page OFF screen
    // (the own page hidden above the window, dragging the grid back to it) or,
    // with both sections on one page, diagonally across into the other.
    fun endOfRow(row: Int, col: Int): Boolean = col == rowSize(row) - 1

    // One row of the household's profiles -- household row [peerRow], which is
    // grid row ownRowCount + peerRow. Shared by page 0 (when the first one
    // joins it) and the household's own pages.
    @Composable
    fun PeerRow(peerRow: Int) {
        val row = ownRowCount + peerRow
        Row(horizontalArrangement = Arrangement.spacedBy(PROFILE_CARD_GAP)) {
            for (col in 0 until rowSize(row)) {
                val index = peerRow * PROFILE_CARDS_PER_ROW + col
                val profile = peerProfiles[index]
                val avatarResId = remember(profile.avatar_url) {
                    resolveAvatarDrawable(profile.avatar_url)
                }
                val requester = if (index < peerFocusRequesters.size) {
                    peerFocusRequesters[index]
                } else {
                    remember { FocusRequester() }
                        .also { peerFocusRequesters.add(it) }
                }
                Box(modifier = Modifier.width(PROFILE_CARD_SIZE)) {
                    ProfileCard(
                        profile        = profile,
                        avatarResId    = avatarResId,
                        focusRequester = requester,
                        seat           = profile.seat,
                        modifier       = Modifier.focusProperties {
                            up   = cellAbove(row, col)
                            down = cellBelow(row, col)
                            if (endOfRow(row, col)) right = signOutFocusRequester
                        },
                        // IDENTICAL TO AN OWN PROFILE, because on this
                        // subscription it is one. The household shares a
                        // licence and a bill, so a profile made on any of its
                        // TVs is watchable from any other. The heading above
                        // still earns its place: deleting is what stays with
                        // the owning account.
                        onPressStart   = { inputLocked = true },
                        onClick        = {
                            enteringProfileName = profile.display_name
                            onProfileSelected(
                                profile.id, profile.display_name, avatarResId,
                            )
                        },
                        onFocusGained  = {
                            flipGridTo(pageOfRow(row))
                            if (navSoundArmed) {
                                BlueHiveApplication.playProfileSwapSound()
                            }
                        },
                    )
                }
            }
        }
    }

    // ── Where the grid sits across the screen ───────────────────────────────
    //
    // ONE GRID, ONE SET OF COLUMNS, CENTRED AS A WHOLE. Every page shares the
    // same left edge, so the household's cards stand in the columns this
    // account's stand in -- Priya directly under the first own card, not
    // centred on a line of her own -- and the whole grid is centred on the
    // widest row any page has. On an owner's TV that is usually their own first
    // row; on a member's TV, usually the owner's page of six, so a member's own
    // three sit in that grid's first three columns with the owner's below them.
    //
    // Tried and dropped, in order: squaring the grid up to the full six-card
    // width whenever there was a household (four cards pinned left of an empty
    // panel), then centring each page on its own first row (every column
    // shifted sideways on each flip, and the pages no longer lined up).
    //
    // Only first rows can be the widest: an own second row exists only once the
    // first is full, and each household page's rows fill in order.
    //
    // MEASURED ON WHAT IS DRAWN, not on the cells. The create button is a
    // CREATE_TILE_SIZE tile set CREATE_TILE_INSET into a card-wide cell, so a
    // row ending in it ends 11.35dp short of its last cell, and a grid that is
    // only the button starts 18dp in. The formula this replaces (a 13dp gap
    // plus the tile) came out 5dp short, leaving every such row 2.5dp right of
    // centre, and it counted the button even when there was none -- a member
    // at their three-profile ceiling sat 55dp left of centre.
    val screenWidth = 960.dp
    val cardPitch   = PROFILE_CARD_SIZE + PROFILE_CARD_GAP

    // Drawn right edge of a row of [cards] profile cards, measured from the
    // grid's left edge, with the create tile after them when [thenCreate].
    fun rowEnd(cards: Int, thenCreate: Boolean): Dp =
        if (thenCreate) cards * cardPitch + CREATE_TILE_INSET + CREATE_TILE_SIZE
        else            cards * cardPitch - PROFILE_CARD_GAP

    val gridX: Dp = run {
        val ownCards  = minOf(profiles.size, PROFILE_CARDS_PER_ROW)
        val ownCreate = canCreateProfile && profiles.size < PROFILE_CARDS_PER_ROW
        val ownEnd    = if (ownRowCount > 0) rowEnd(ownCards, ownCreate) else 0.dp
        val peerEnd   = if (peerProfiles.isEmpty()) 0.dp
                        else rowEnd(minOf(peerProfiles.size, PROFILE_CARDS_PER_ROW), thenCreate = false)
        // Drawn from the first card's left edge -- unless the only thing in
        // the whole grid is the create button, which is inset into its cell.
        val start     = if (ownCards == 0 && peerProfiles.isEmpty()) CREATE_TILE_INSET else 0.dp
        val end       = maxOf(ownEnd, peerEnd)
        (screenWidth - (end - start)) / 2 - start
    }

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

        // ── Profile grid ──────────────────────────────────────────────────────
        //
        // PAGES IN A FIXED WINDOW. The picker shows the whole subscription,
        // which can run to four times what a 960x540 panel can hold, so the
        // rows live in a window exactly one page tall and the grid turns a whole
        // page at a time: page 0 this account's own, then the household's,
        // twelve to a page (GRID_PAGE_HEIGHT has the why). An account with no
        // peers has one page and looks exactly as it did before.
        //
        // EAGERLY COMPOSED, NOT LAZY, and that is a focus decision rather than a
        // performance one. This screen's machinery -- the parking box, the
        // targeting effect, the return-focus tokens, the sign-out left target --
        // all hold FocusRequesters and assume the node behind one is attached. A
        // lazy grid recycles, so a requester for a scrolled-off tile detaches
        // and those all break. At a ceiling of 15 seats x 3 profiles the whole
        // household is ~45 small cards, which is nothing to compose at once.
        //
        // UP AND DOWN ARE WIRED, NOT SEARCHED (cellAbove / cellBelow). Left to
        // Compose's geometric search, down from the two right-hand columns of
        // the last own row went to Sign Out, which is nearer than a household
        // page sitting below the fold -- so from there the household could not
        // be reached at all.
        if (!isLoadingGrid) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    // Lifted by the page header so the first row still sits on
                    // GRID_OFFSET_Y_TOP_ROW. Every page shares this left edge.
                    .offset(x = gridX, y = GRID_OFFSET_Y_TOP_ROW - GRID_PAGE_HEADER)
                    .width(GRID_ROW_WIDTH)
                    .height(GRID_PAGE_HEIGHT),
            ) {
                WithoutFocusScroll {
                    Column(modifier = Modifier.verticalScroll(gridScroll)) {

                        // ── Page 0: this account's own ───────────────────────
                        //
                        // No heading. The own section is the default and reads
                        // as such by coming first and holding the create button;
                        // the boundary worth labelling is the one where the
                        // rules change.
                        if (ownRowCount > 0) GridPage(heading = null) {
                            for (row in 0 until ownRowCount) {
                                if (row > 0) Spacer(Modifier.height(PROFILE_ROW_SPACING))
                                Row(horizontalArrangement = Arrangement.spacedBy(PROFILE_CARD_GAP)) {
                                    for (col in 0 until rowSize(row)) {
                                        val index = row * PROFILE_CARDS_PER_ROW + col
                                        Box(modifier = Modifier.width(PROFILE_CARD_SIZE)) {
                                            if (index < profiles.size) {
                                                val profile = profiles[index]
                                                val avatarResId = remember(profile.avatar_url) {
                                                    resolveAvatarDrawable(profile.avatar_url)
                                                }
                                                val requester = if (index < focusRequesters.size) {
                                                    focusRequesters[index]
                                                } else {
                                                    remember { FocusRequester() }
                                                        .also { focusRequesters.add(it) }
                                                }
                                                ProfileCard(
                                                    profile        = profile,
                                                    avatarResId    = avatarResId,
                                                    focusRequester = requester,
                                                    seat           = profile.seat,
                                                    modifier       = Modifier.focusProperties {
                                                        up   = cellAbove(row, col)
                                                        down = cellBelow(row, col)
                                                        if (endOfRow(row, col)) right = signOutFocusRequester
                                                    },
                                                    onPressStart   = { inputLocked = true },
                                                    onClick        = {
                                                        enteringProfileName = profile.display_name
                                                        onProfileSelected(
                                                            profile.id, profile.display_name, avatarResId,
                                                        )
                                                    },
                                                    onFocusGained  = {
                                                        flipGridTo(pageOfRow(row))
                                                        if (navSoundArmed) {
                                                            BlueHiveApplication.playProfileSwapSound()
                                                        }
                                                    },
                                                )
                                            } else {
                                                CreateProfileButton(
                                                    focusRequester = createFocusRequester,
                                                    onClick        = {
                                                        val blocked = creationBlockedBy()
                                                        if (blocked != null) limitNotice = blocked
                                                        else                 onCreateProfile(creationLauncher)
                                                    },
                                                    onFocusGained  = {
                                                        flipGridTo(pageOfRow(row))
                                                        if (navSoundArmed) BlueHiveApplication.playNavClickSound()
                                                    },
                                                    modifier       = Modifier
                                                        // UNBOUNDED, SO THE GLOW KEEPS ITS SHAPE.
                                                        // The glow image is 109.3dp wide and this
                                                        // cell 106.6, and an image with a
                                                        // requiredSize that does not fit is
                                                        // centred on its slot rather than placed
                                                        // where its offset says -- 1.35dp left,
                                                        // which left 3.7dp of glow on the tile's
                                                        // left and 1.2 on its right. Measured
                                                        // loose, as it was when the button sat
                                                        // directly on the screen: 2.4 and 2.6.
                                                        .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                                                        .offset(x = CREATE_TILE_INSET, y = 14.7.dp)
                                                        .focusProperties {
                                                            up    = cellAbove(row, col)
                                                            down  = cellBelow(row, col)
                                                            right = signOutFocusRequester
                                                        },
                                                    dimensions = ProfileButtonDimensions(
                                                        imageSize          = CREATE_TILE_SIZE,
                                                        imageCornerRadius  = 6.dp,
                                                        plinthWidth        = CREATE_TILE_SIZE,
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
                                            }
                                        }
                                    }
                                }
                            }

                            // ── The household's first row, in the room left ────
                            //
                            // NO HEADING HERE, on request: it sits exactly where an
                            // own second row would, and the purple seat triangles
                            // are what set it apart. The heading is kept for pages
                            // that are the household's alone.
                            if (sharedFirstPage) {
                                Spacer(Modifier.height(PROFILE_ROW_SPACING))
                                PeerRow(peerRow = 0)
                            }
                        }

                        // ── The rest of the household's, twelve to a page ─────
                        for (page in 0 until peerPageCount) {
                            GridPage(heading = "Others on your subscription") {
                                for (pageRow in 0 until 2) {
                                    val peerRow = firstPagedPeerRow + page * 2 + pageRow
                                    if (peerRow >= peerRowCount) break
                                    if (pageRow > 0) Spacer(Modifier.height(PROFILE_ROW_SPACING))
                                    PeerRow(peerRow)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── "More below" hint ─────────────────────────────────────────────────
        //
        // THE WINDOW HOLDS ONE PAGE, SO THE NEXT ONE IS INVISIBLE UNTIL YOU GO
        // TO IT. With twelve profiles of your own the household's page sits
        // entirely below the fold and nothing on screen said it existed. This
        // says it: bottom-left, on the Sign Out strip's baseline, counting the
        // profiles on the pages below the one showing. It goes once there is
        // nothing further down.
        //
        // A LABEL, NOT A CONTROL. Down from the last row turns the page, which
        // is the way down this screen already has; a focusable hint would only
        // add a stop between the grid and the buttons.
        //
        // Left-aligned with the grid above it.
        //
        // A Crossfade rather than a visibility toggle so the outgoing count is
        // the one that fades: the hint leaving on "3 more" must not flash
        // "0 more" on its way out.
        val profilesBelow =
            if (isLoadingGrid) 0
            else (peerProfiles.size - peersThrough(shownPage)).coerceAtLeast(0)
        Crossfade(
            targetState   = profilesBelow,
            animationSpec = tween(durationMillis = 200),
            label         = "gridMoreHint",
            modifier      = Modifier
                .align(Alignment.TopStart)
                .offset(x = gridX, y = GRID_MORE_HINT_Y),
        ) { count ->
            if (count > 0) GridMoreHint(count)
        }


        // Same overlay, and the same words the creation screen would have
        // used had the press been let through to fail over there instead.
        limitNotice?.let { notice ->
            NoticeOverlay(notice) {
                limitNotice = null
                // Focus is handed back deliberately: the overlay took it from
                // the button, and nothing else claims it on dismissal.
                runCatching { createFocusRequester.requestFocus() }
            }
        }

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
            onClick        = {
                returnFocusTo      = ReturnFocus.SignOut
                showSignOutConfirm = true
            },
            modifier       = Modifier
                .align(Alignment.TopStart)
                .offset(x = 678.dp, y = BOTTOM_STRIP_Y)
                .focusProperties {
                    // Inherits the left-target Manage used to own; Manage now
                    // points here instead.
                    // Falls back to the LAST card rather than a fixed index 7:
                    // the button now hides for two different reasons, and at a
                    // device quota the grid can be far shorter than full.
                    //
                    // WHEN THE PAGE SHOWING HAS HOUSEHOLD CARDS, ITS LAST ONE:
                    // they are its bottom row, the one level with this strip.
                    // On a household page the create button and the own cards
                    // are a page away, and focusing one would turn the grid
                    // back underneath you.
                    left  = run {
                        val through = peersThrough(shownPage)
                        val before  = if (shownPage == 0) 0 else peersThrough(shownPage - 1)
                        when {
                            through > before -> peerFocusRequesters.getOrNull(through - 1)
                                                ?: FocusRequester.Cancel
                            canCreateProfile -> createFocusRequester
                            else -> focusRequesters.getOrNull(profiles.size - 1)
                                    ?: FocusRequester.Cancel
                        }
                    }
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
            onClick        = {
                returnFocusTo = ReturnFocus.Manage
                onManageProfiles(manageLauncher)
            },
            modifier       = Modifier
                .align(Alignment.TopStart)
                .offset(x = 790.dp, y = BOTTOM_STRIP_Y)
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

/**
 * A section heading inside the scrolling grid.
 *
 * Quiet on purpose. It is a signpost for the delete rule -- what is above it
 * you own, what is below you may only rename -- not a piece of chrome that
 * should compete with the twelve faces underneath it.
 */
@Composable
private fun GridSectionHeading(text: String) {
    Text(
        text       = text,
        color      = GRID_SECTION_LABEL_COLOR,
        fontFamily = AppTypography.interBold,
        fontSize   = 9.sp,
        maxLines   = 1,
        overflow   = TextOverflow.Ellipsis,
        // Held off the cards below by what a focused card's glow actually
        // reaches -- measured, not the glow image's full 23dp. See
        // GRID_HEADING_CLEARANCE.
        modifier   = Modifier.padding(bottom = GRID_HEADING_CLEARANCE),
    )
}

/**
 * One page of the profile grid: exactly GRID_PAGE_HEIGHT tall whatever it
 * holds, so page N always starts at N page-heights and a flip lands on it
 * exactly -- a page with one row keeps the second row's space empty rather
 * than letting the next page ride up into it.
 *
 * The band across the top is the first row's glow headroom and, on a
 * household page, the section heading, which sits at the band's bottom.
 */
@Composable
private fun GridPage(heading: String?, rows: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.height(GRID_PAGE_HEIGHT)) {
        Box(
            contentAlignment = Alignment.BottomStart,
            modifier         = Modifier
                .height(GRID_PAGE_HEADER)
                .fillMaxWidth(),
        ) {
            if (heading != null) GridSectionHeading(heading)
        }
        rows()
    }
}

/**
 * "▾ 3 more on your subscription" -- see the hint at its call site.
 *
 * The chevron is drawn rather than typed: a ▾ glyph would come from whatever
 * fallback font the TV has instead of Inter, at that font's size and on that
 * font's baseline.
 */
@Composable
private fun GridMoreHint(count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.size(width = 7.dp, height = 4.5.dp),
        ) {
            drawPath(
                path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                },
                color = GRID_SECTION_LABEL_COLOR,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text       = "$count more on your subscription",
            color      = GRID_SECTION_LABEL_COLOR,
            fontFamily = AppTypography.interBold,
            fontSize   = 9.sp,
            maxLines   = 1,
        )
    }
}

/**
 * Tells the profile grid's scroll container NOT to move when something in it
 * takes focus, because the grid moves itself: flipGridTo() turns a whole page.
 *
 * Every scroll container answers "bring this into view" when a child takes
 * focus, and on a TV Compose's answer is to pivot that child to 30% of the
 * container (PivotBringIntoViewSpec) -- the half-rows this replaces. Answering
 * zero turns that off for this one container and nothing else on the screen.
 */
@OptIn(ExperimentalFoundationApi::class)
private val NoGridFocusScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

// Internal: Manage Profiles pages its grid the same way.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WithoutFocusScroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoGridFocusScroll, content = content)
}

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
    // Seat of the profile's account ("owner" / "member"), or null for no
    // triangle. The caller decides WHETHER to show one -- it knows whether there
    // is a household to tell apart -- and the card decides the colour, because
    // that depends on focus and only the card knows its focus state.
    seat:           String? = null,
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

            // Lifted twice, a tenth each time: 0.7 -> 0.67 -> 0.637, so an
            // unfocused card shows 36.3% of its artwork where it once showed 30%.
            // This screen dims the hardest of the three, so the same proportional
            // step buys it the least, and it is the one to push again if a row of
            // unfocused cards still reads as flat.
            val tintAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue   = if (isFocused) 0f else 0.637f,
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
                            width = PROFILE_CARD_EDGE_WIDTH,
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

                // Seat triangle, top-left. After the tint so it is not dimmed with
                // the avatar, and inside the clipped box so it follows the card's
                // rounded corner -- both exactly as the watched fold does.
                SeatTriangle(
                    seat     = seat,
                    focused  = isFocused,
                    side     = PROFILE_SEAT_TRIANGLE_SIZE,
                    modifier = Modifier.align(Alignment.TopStart),
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