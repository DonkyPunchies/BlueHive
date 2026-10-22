package com.example.bluehive

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.example.bluehive.api.SessionExpiredBus
import com.example.bluehive.auth.ActiveProfile
import com.example.bluehive.auth.HostEntitlementRelay
import com.example.bluehive.update.UpdateGate
import com.example.bluehive.auth.SessionManager
import com.example.bluehive.catalog.openMediaCatalogScreen
import com.example.bluehive.searchBarComponent.openSearchScreen
import com.example.bluehive.continueWatchingComponents.HomeScreenContinueWatchingCompose
import com.example.bluehive.homeScreenSectionRules.CAROUSEL_AUTO_CYCLE_DELAY_MS
import com.example.bluehive.homeScreenSectionRules.LocalCarouselCycleTick
import com.example.bluehive.latestTrailersComponents.HomeScreenTrailerSectionCompose
import com.example.bluehive.latestTrailersComponents.TrailerFrameState
import com.example.bluehive.models.MediaItem
import com.example.bluehive.sidebarComponents.HomeScreenSidebarCompose
import com.example.bluehive.sidebarComponents.openFavoritesScreen
import com.example.bluehive.sidebarComponents.openHistoryScreen
import com.example.bluehive.sidebarComponents.openProfileScreen
import com.example.bluehive.singleShelfComponents.ShelfStack
import com.example.bluehive.singleShelfComponents.MediaType
import com.example.bluehive.trendingComponents.HomeScreenTrendingCompose
import com.example.bluehive.trendingComponents.TrendingFrameState
import com.example.bluehive.utilities.AppTypography
import com.example.bluehive.utilities.ModularButton
import com.example.bluehive.utilities.ModularButtonAnimationConfig
import com.example.bluehive.utilities.ModularButtonColors
import com.example.bluehive.utilities.ModularButtonDimensions
import com.example.bluehive.utilities.ModularButtonGlowConfig
import com.example.bluehive.utilities.ModularButtonTextConfig
import com.example.bluehive.utilities.CloseApplicationHandler
import com.example.bluehive.utilities.overlayScrimColor
import com.example.bluehive.utilities.NetworkMonitor
import com.example.bluehive.utilities.RetryOverlay
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import com.example.bluehive.auth.DeviceEventStream
import com.example.bluehive.sidebarComponents.openLiveTvScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


enum class CatalogTab { MOVIES, TV_SHOWS, ANIME }

/**
 * Longest the navigation tick stays muted while focus returns from the
 * sidebar. The restore ends the mute itself the moment focus lands (~100 ms,
 * measured); this cap only matters if it never does.
 */
private const val SIDEBAR_RETURN_MUTE_CAP_MS = 1_000L

private enum class SidebarReturnTarget {
    SEARCH_BAR,
    TRAILER,
    MOVIES,
    TV_SHOWS,
    ANIME,
    CONTINUE_WATCHING,
    TRENDING,
    // Separate from MOVIES/TV_SHOWS (which return to the radio buttons).
    // These return directly into the ShelfStack so the card-focus tracker
    // can restore the exact title card the user was on.
    MOVIES_SHELF,
    TV_SHOWS_SHELF,
    // ANIME_SHELF exists for the same reason, and its absence was a quiet bug:
    // the Anime shelf used to report plain ANIME on focus, the same value its
    // radio button reports, so leaving the shelf for the sidebar and coming
    // back landed on the radio button instead of the card. It also made the
    // two states indistinguishable to anything asking "is focus on a card?".
    ANIME_SHELF,
}




// ── Shelf stack vertical position ─────────────────────────────────────────────
// Raise this value to push the "What's Popular" row further down the screen.
// The title label sits 48.dp above this line; the cards sit 40.dp above it.
private val SHELF_STACK_Y = 340.dp  // The whole shelf area up/down on screen — change this to lower "What's Popular"

// ── Shared button palette — used by all radio buttons + top buttons ───────────
private val BTN_MAIN_DEFAULT          = Color(0xFF2644A6)
private val BTN_MAIN_FOCUSED          = Color(0xFF0D00FF)
private val BTN_MAIN_TOGGLED          = Color(0xFF101628)
private val BTN_SECOND_DEFAULT        = Color(0xFF222340)
private val BTN_SECOND_FOCUSED        = Color(0xFF372B67)
private val BTN_SECOND_TOGGLED        = Color(0xFF1B1C34)
private val BTN_SHADOW                = Color(0x50000000)
private val BTN_TEXT_FOCUSED          = Color.White
private val BTN_TEXT_UNFOCUSED        = Color(0xFFD2D0D0)
private val BTN_TEXT_UNFOCUSED_TOGGLED = Color(0xFF343434)

// ── Radio button shared config ────────────────────────────────────────────────
private val RADIO_MAIN_W   = 58.7.dp
private val RADIO_GLOW_W   = 68.8.dp

private val passionFont = AppTypography.passionRegular


private val RADIO_COLORS = ModularButtonColors(
    mainDefault          = BTN_MAIN_DEFAULT,
    mainFocused          = BTN_MAIN_FOCUSED,
    mainToggled          = BTN_MAIN_TOGGLED,
    secondDefault        = BTN_SECOND_DEFAULT,
    secondFocused        = BTN_SECOND_FOCUSED,
    secondToggled        = BTN_SECOND_TOGGLED,
    shadowColor          = BTN_SHADOW,
    textFocused          = BTN_TEXT_FOCUSED,
    textUnfocused        = BTN_TEXT_UNFOCUSED,
    textUnfocusedToggled = BTN_TEXT_UNFOCUSED_TOGGLED,
)

private val RADIO_DIMENSIONS = ModularButtonDimensions(
    mainWidth          = RADIO_MAIN_W,
    mainHeight         = 21.dp,
    mainYOffset        = (-2.2).dp,
    secondWidth        = RADIO_MAIN_W,
    secondHeight       = 10.dp,
    secondYOffset      = 13.dp,
    shadowHeight       = 4.dp,
    glowWidth          = RADIO_GLOW_W,
    glowHeight         = 32.5.dp,
    mainCornerRadius   = 5f,
    secondCornerRadius = 5f,
)

private val RADIO_ANIMATION = ModularButtonAnimationConfig(
    pressOffset           = 2.5.dp,
    textOffsetDefault     = (-0.3).dp,
    textOffsetPressed     = 1.9.dp,
    durationMillis        = 180,
    bounceBackDelayMillis = 220
)

class HomeScreenCompose : ComponentActivity() {

    private val refreshTrigger = mutableIntStateOf(0)

    private val sessionExpiredListener: () -> Unit = {
        returnToHost()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!SessionManager.get().isAuthenticated) {
            returnToHost()
            return
        }

        (application as BlueHiveApplication).setAppState(
            BlueHiveApplication.AppState.HOME_ACTIVE
        )

        val profileId       = intent.getIntExtra("PROFILE_ID", -1)
        val profileName     = intent.getStringExtra("PROFILE_NAME") ?: ""
        val profileAvatarId = intent.getIntExtra("PROFILE_AVATAR_ID", R.drawable.avatar1)
        // Also set by the picker; this covers Android rebuilding the home screen
        // after killing the process, when the picker never ran.
        if (profileId > 0) ActiveProfile.id = profileId

        setContent {
            HomeScreenComposeContent(
                onMediaClick     = { media -> openDetailsScreen(media, profileId) },
                profileAvatarRes = profileAvatarId,
                profileId        = profileId,
                profileName      = profileName,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        (application as BlueHiveApplication).setAppState(
            BlueHiveApplication.AppState.HOME_ACTIVE
        )
        SessionExpiredBus.register(sessionExpiredListener)
        if (!SessionManager.get().isAuthenticated) {
            returnToHost()
            return
        }
        refreshTrigger.intValue++

        // Re-check the host's account standing.
        //
        // THIS ONE HOOK COVERS EVERY RETURN PATH. Sidebar pages, watch history,
        // favorites, settings, the media catalog, a movie or show detail page, the
        // profile picker, and coming back from the TV launcher all resume this
        // activity — so there is no need to instrument each of them.
        //
        // Placed AFTER the auth guard on purpose: an unauthenticated session is on
        // its way out of the app and has nothing to report against.
        //
        // Rate-limited internally (3s floor — just long enough to collapse a
        // duplicate onResume) and self-cancelling when the token has not changed,
        // so bouncing in and out of a detail page costs nothing.
        HostEntitlementRelay.checkNow(this)

        // ── Is this TV still allowed to run this build? ──────────────────────
        //
        // Deliberately alongside the entitlement check rather than inside it.
        // They ask different questions of different servers and only one of them
        // can lock somebody out of a paid account; tangling them would mean an
        // update bug could take entitlement down with it.
        //
        // Same placement for the same reason though: every route back into the
        // app resumes this activity, and a cold start lands here after the
        // loading screen — so one hook covers reboot and navigation both.
        //
        // Rate limited internally, runs off the main thread, waits a couple of
        // seconds so the home screen is not yanked away the instant it appears,
        // and does nothing at all when the device is already current.
        UpdateGate.checkNow(this)
    }

    override fun onPause() {
        super.onPause()
        SessionExpiredBus.unregister(sessionExpiredListener)
    }

    // PHASE 2: under the host model BlueHive owns no pairing screen. A genuine
    // session loss (host reports revoked) means the HOST must handle re-pairing.
    // BlueHive's job is simply to stop and hand control back. We finishAffinity()
    // to tear down BlueHive's task; the user is dropped back to the host,
    // which owns the pairing front door. LoginScreenActivity is never launched.
    private fun returnToHost() {
        Log.w("HomeScreenCompose", "Session ended — returning to host")
        DeviceEventStream.stopAndMarkExited()
        finishAffinity()
    }




    private fun openDetailsScreen(media: MediaItem, profileId: Int) {
        val mediaType = media.mediaType  // Get from the MediaItem itself

        Log.d("HomeScreenCompose", "Opening ${mediaType.uppercase()} details for: ${media.title}")

        val intent = when (mediaType.lowercase()) {
            "tv" -> Intent(this, TVShowsDetailsScreenCompose::class.java)
            else -> Intent(this, MoviesDetailsScreenCompose::class.java)
        }.apply {
            putExtra("PROFILE_ID", profileId)
            putExtra("media_type", mediaType)
            putExtra("media_id", media.tmdbId)
            putExtra("media_title", media.title)
            putExtra("poster_url", media.posterUrl ?: "")
            putExtra("backdrop_url", media.backdropUrl ?: "")
            putExtra("youtube_trailer_url", media.trailerUrl ?: "")
            putExtra("overview", media.overview ?: "")
            putExtra("vote_average", media.voteAverage ?: 0.0)
            putExtra("contentRating", media.contentRating ?: "N/A")
            putExtra("original_language", media.originalLanguage ?: "N/A")
            putExtra("release_date", media.releaseDate ?: "N/A")
            putExtra("logo_url", media.logoUrl ?: "")
            putExtra("genres", media.genres?.joinToString(", ") ?: "N/A")

            // TV-specific extras
            if (mediaType.lowercase() == "tv") {
                putExtra("number_of_seasons", media.numberOfSeasons ?: 0)
                putExtra("status", media.status ?: "N/A")
            } else {
                // Movie-specific extras
                putExtra("budget", media.budget ?: 0L)
                putExtra("revenue", media.revenue ?: 0L)
            }
        }

        startActivity(intent)
    }
}



@Composable
fun HomeScreenComposeContent(
    onMediaClick:     (MediaItem) -> Unit,
    profileAvatarRes: Int = R.drawable.avatar1,
    profileId:        Int = -1,
    profileName:      String = "",
) {
    val context = LocalContext.current

    // ── Profile-entry transition overlay ────────────────────────────────────
    // Black screen shown the moment the home screen mounts, hiding the cold
    // load (posters popping in, empty frames) behind a clean message + spinner.
    // Dismisses once BOTH conditions hold: content is ready (startContent) AND
    // a minimum display time has elapsed so it never flashes. While visible it
    // blocks all remote input.
    var showEntryTransition by remember { mutableStateOf(true) }
    var minTransitionElapsed by remember { mutableStateOf(false) }
    // Set to true when the Popular shelf reports its first page is ready.
    // The entry overlay waits for this before fading out so the home screen
    // is never revealed with empty rows.
    var firstShelfDataReady by remember { mutableStateOf(false) }

    // Track whether the home activity is currently in the foreground.
    // When it's not (user navigated to catalog/search/details/etc.), we pause
    // all background work: trailer playback, any polling, etc.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isHomeInForeground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    var isSidebarFocused by remember { mutableStateOf(false) }
    var trailerSectionState by remember { mutableStateOf<TrailerFrameState>(TrailerFrameState.UnFocused) }
    var trendingState by remember { mutableStateOf<TrendingFrameState>(TrendingFrameState.UnFocused) }

    var isOnFirstShelf by remember { mutableStateOf(true) }

    // ── WHICH SHELF ROW AN OUTSIDE ARRIVAL WANTS ───────────────────────
    //
    // Non-null for the one frame it takes ShelfStack to act on it; the stack
    // clears it. An event, not a mode -- left set, it would re-fire every time
    // the stack recomposed.
    var enterShelfAt by remember { mutableStateOf<Int?>(null) }

    // The same kind of event for a row change that leaves focus where it is
    // (ShelfStack's scrollToShelf). "Top of Page" sets it to the first shelf.
    var scrollShelfTo by remember { mutableStateOf<Int?>(null) }

    // Whether this profile has any watch history, as the Continue Watching
    // panel last reported it: null until its first load answers. Only a known
    // "no" hides the panel, so a profile with history never sees the layout
    // change while it loads.
    var continueWatchingHasHistory by remember { mutableStateOf<Boolean?>(null) }

    var sidebarCanFocus by remember { mutableStateOf(false) }
    var requestSidebarFocus by remember { mutableStateOf(false) }
    var requestSidebarReturnFocus by remember { mutableStateOf(false) }
    var sidebarReturnTarget by remember { mutableStateOf<SidebarReturnTarget?>(null) }
    var startContent by remember { mutableStateOf(false) }

    // Item 6 — offline retry banner state.
    var showRetryBanner by remember { mutableStateOf(false) }
    val retryScope = rememberCoroutineScope()

    val tvLazyRowsSingleShelfFocusRequester = remember { FocusRequester() }

    // Tracks which home-screen area had focus when back was pressed,
    // so the sidebar can restore focus to exactly that spot on close.
    var lastFocusedTarget by remember { mutableStateOf(SidebarReturnTarget.SEARCH_BAR) }

    // ── Radio-button navigation sound ──────────────────────────────────────
    // The sound means "you moved onto this". The home screen OPENS with focus
    // already on a radio button, and announcing that is announcing a move that
    // never happened -- so the first arrival arms the flag instead of playing.
    //
    // ONE FLAG FOR ALL THREE, not one each: with a flag per button the first
    // hop from MOVIES to TV SHOWS would be silent too.
    var radioSoundArmed by remember { mutableStateOf(false) }
    val radioNavSound: () -> Unit = {
        if (radioSoundArmed) BlueHiveApplication.playNavClickSound()
        else radioSoundArmed = true
    }

    // IS FOCUS INSIDE A SHELF OF TITLE CARDS RIGHT NOW?
    //
    // Back means something different there -- see the router in
    // CloseApplication.kt -- so it needs a live answer, which lastFocusedTarget
    // cannot give: that one records the last place focus WAS and never clears.
    //
    // Read off the ShelfStack container, never off a list of rows. Every shelf,
    // on every tab, lives inside that one container, so adding a shelf,
    // removing one, or reordering them cannot reach this flag -- the thing being
    // watched is the box they all sit in, not its contents. Each ShelfStack call
    // site assigns it.hasFocus straight through, so it falls to false the moment
    // focus leaves for the tabs, a frame, or the sidebar.
    var isShelfFocused by remember { mutableStateOf(false) }

    // Raised when the choice overlay is dismissed with back, to confirm a beat
    // later that focus really did land back on the shelf. See the effect below.
    var restoreShelfFocusPending by remember { mutableStateOf(false) }



    // Single shared clock that drives both the Trailer and Trending carousels
    // in perfect sync. Both sections react to the same increment rather than
    // running independent timers.
    var cycleTick by remember { mutableLongStateOf(0L) }

    // The clock itself pauses when the carousels should pause (sidebar open or
    // home not in foreground). Keying the timer on this value means: while
    // paused, no ticks accumulate; on resume, the loop restarts and the FIRST
    // tick is a full interval away. Without this, the free-running timer would
    // fire a stale tick the instant the sidebar closed — landing on whatever
    // phase it happened to be at — and jump both carousels forward at once.
    val carouselClockPaused = isSidebarFocused || !isHomeInForeground

    LaunchedEffect(carouselClockPaused) {
        if (carouselClockPaused) return@LaunchedEffect
        while (isActive) {
            delay(CAROUSEL_AUTO_CYCLE_DELAY_MS)
            cycleTick++
        }
    }
    val sidebarParkingRequester = remember { FocusRequester() }
    var sidebarParkingEnabled by remember { mutableStateOf(false) }


    Box(
        modifier = Modifier
            .fillMaxSize()
            // While the entry transition is up, swallow every key so the user
            // can't interact with the half-loaded screen underneath.
            .onPreviewKeyEvent { showEntryTransition }
    ) {
        Box(
            modifier = Modifier
                .size(1.dp)
                .graphicsLayer(alpha = 0f)
                .focusRequester(sidebarParkingRequester)
                .focusProperties { canFocus = sidebarParkingEnabled }
                .onPreviewKeyEvent { event ->
                    if (sidebarParkingEnabled && event.type == KeyEventType.KeyDown) {
                        Log.d(
                            "SIDEBAR_PARKING",
                            "🚫 Blocking ${event.key} during sidebar transition"
                        )
                        true // Consume all keys
                    } else {
                        false
                    }
                }
                .focusable()
        )



        val trailerFrameFocusRequester = remember { FocusRequester() }
        val trendingFrameFocusRequester = remember { FocusRequester() }

        val moviesToggleButtonFocusRequester = remember { FocusRequester() }
        val tvShowsToggleButtonFocusRequester = remember { FocusRequester() }
        val animeToggleButtonFocusRequester = remember { FocusRequester() }
        val radioParkingRequester = remember { FocusRequester() }
        var radioParkingEnabled by remember { mutableStateOf(false) }
        var selectedTab by remember { mutableStateOf(CatalogTab.MOVIES) }
        var pendingFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }
        val continueWatchingRequester = remember { FocusRequester() }
        val sidebarFocusRequester = remember { FocusRequester() }



        LaunchedEffect(Unit) {
            Log.d("FOCUS_DEBUG", "🎯 continueWatchingRequester created: ${continueWatchingRequester.hashCode()}")
        }

        // IS THE CONTINUE WATCHING PANEL ON SCREEN? It is composed only on the
        // first shelf, and draws nothing when the profile has no watch history.
        // Everything that moves focus INTO it asks this first and goes
        // elsewhere when the answer is no -- a requester with nothing behind it
        // swallows the key press and moves focus nowhere. A function, so a
        // focusProperties lambda or an effect gets the answer at the moment it
        // asks, not a value captured when it was composed.
        fun continueWatchingShown(): Boolean =
            startContent && isOnFirstShelf && continueWatchingHasHistory != false

        // If the panel goes while it holds focus -- history cleared on the
        // server, noticed by the refresh on returning to this screen -- hand
        // focus to the shelf instead of letting it drop to the top of the page.
        LaunchedEffect(continueWatchingHasHistory) {
            if (continueWatchingHasHistory == false &&
                lastFocusedTarget == SidebarReturnTarget.CONTINUE_WATCHING &&
                !isSidebarFocused
            ) {
                kotlinx.coroutines.android.awaitFrame()
                runCatching { tvLazyRowsSingleShelfFocusRequester.requestFocus() }
            }
        }

        // SECOND CHANCE, not the main event. Dismissing the choice overlay asks
        // for the shelf synchronously (see onRestoreRowFocus, further down),
        // while the overlay is still composed, so focus never has a gap for
        // Compose to fill with the trailer section -- the same failure the
        // radio-tab parking node exists to prevent. This runs two frames later
        // and only if that did not stick, which is what isShelfFocused answers.
        LaunchedEffect(restoreShelfFocusPending) {
            if (!restoreShelfFocusPending) return@LaunchedEffect
            kotlinx.coroutines.android.awaitFrame()
            kotlinx.coroutines.android.awaitFrame()
            if (!isShelfFocused) {
                Log.d("FOCUS_DEBUG", "choice overlay dismissed: shelf lost focus, re-asking")
                // runCatching: the shelf can be gone by now -- a tab switch, or
                // the session ending underneath us -- and a detached requester
                // throws rather than returning false.
                runCatching { tvLazyRowsSingleShelfFocusRequester.requestFocus() }
            }
            restoreShelfFocusPending = false
        }

        LaunchedEffect(pendingFocusRequester) {
            pendingFocusRequester?.let { requester ->
                kotlinx.coroutines.android.awaitFrame()
                requester.requestFocus()
                pendingFocusRequester = null
                // Target button now owns focus — release the parking node.
                radioParkingEnabled = false
            }
        }

        LaunchedEffect(requestSidebarFocus, sidebarCanFocus) {
            if (requestSidebarFocus && sidebarCanFocus) {
                kotlinx.coroutines.android.awaitFrame()
                sidebarFocusRequester.requestFocus()
                requestSidebarFocus = false
            }
        }





        LaunchedEffect(Unit) {
            kotlinx.coroutines.android.awaitFrame()
            kotlinx.coroutines.android.awaitFrame() // let UI settle first

            sidebarCanFocus = false
            trailerFrameFocusRequester.requestFocus()

            // Stagger heavy content — let focus and background settle before
            // triggering all the data fetches and image loads
            delay(300)
            startContent = true
        }

        // Minimum on-screen time for the entry transition so it doesn't flash.
        LaunchedEffect(Unit) {
            delay(2_000L)
            minTransitionElapsed = true
        }

        // Safety net: if the shelf never signals ready (offline, server error),
        // force-dismiss after 6s so the RetryOverlay underneath becomes visible.
        LaunchedEffect(startContent) {
            if (startContent) {
                delay(6_000L)
                firstShelfDataReady = true
            }
        }

        // Dismiss only when BOTH the shelf has real cards AND the minimum
        // display time has elapsed. Cards are rendered behind the overlay
        // before it ever fades — the home screen is never revealed empty.
        LaunchedEffect(firstShelfDataReady, minTransitionElapsed) {
            if (firstShelfDataReady && minTransitionElapsed) {
                showEntryTransition = false
            }
        }

        fun openSidebar(from: SidebarReturnTarget) {
            sidebarReturnTarget = from
            sidebarCanFocus = true
            requestSidebarFocus = true
        }

        LaunchedEffect(requestSidebarReturnFocus, sidebarCanFocus) {
            if (requestSidebarReturnFocus && !sidebarCanFocus) {
                // ✅ Parking is ALREADY enabled from onExitToRight, and so is the
                // navigation-tick mute; finally ends it once focus has landed.
                try {
                    // Wait 1 frame for composition
                    kotlinx.coroutines.android.awaitFrame()

                    // Move focus to parking spot
                    sidebarParkingRequester.requestFocus()
                    kotlinx.coroutines.android.awaitFrame()

                    // Now restore to actual target
                    when (sidebarReturnTarget) {
                        SidebarReturnTarget.SEARCH_BAR -> trailerFrameFocusRequester.requestFocus()
                        SidebarReturnTarget.TRAILER -> trailerFrameFocusRequester.requestFocus()
                        SidebarReturnTarget.TRENDING -> trendingFrameFocusRequester.requestFocus()
                        SidebarReturnTarget.CONTINUE_WATCHING ->
                            if (continueWatchingShown()) continueWatchingRequester.requestFocus()
                            else tvLazyRowsSingleShelfFocusRequester.requestFocus()
                        SidebarReturnTarget.MOVIES -> moviesToggleButtonFocusRequester.requestFocus()
                        SidebarReturnTarget.TV_SHOWS -> tvShowsToggleButtonFocusRequester.requestFocus()
                        SidebarReturnTarget.ANIME -> animeToggleButtonFocusRequester.requestFocus()
                        SidebarReturnTarget.MOVIES_SHELF -> tvLazyRowsSingleShelfFocusRequester.requestFocus()
                        SidebarReturnTarget.TV_SHOWS_SHELF -> tvLazyRowsSingleShelfFocusRequester.requestFocus()
                        SidebarReturnTarget.ANIME_SHELF -> tvLazyRowsSingleShelfFocusRequester.requestFocus()
                        null -> trailerFrameFocusRequester.requestFocus()
                    }

                    // Wait for focus to settle
                    kotlinx.coroutines.android.awaitFrame()

                    // Cleanup
                    sidebarReturnTarget = null
                    requestSidebarReturnFocus = false
                    sidebarParkingEnabled = false

                    Log.d("SIDEBAR_PARKING", "✅ Focus restored and parking disabled")
                } finally {
                    // Focus has landed (or the restore was cancelled): from here
                    // on every tick is a move the viewer made.
                    BlueHiveApplication.unmuteHoverSounds()
                }
            }
        }


        // moved focus FIRST, then update selectedTab
        //Don’t wait a frame. Don’t use pendingFocusTab at all.
        fun neighborOf(tab: CatalogTab): CatalogTab = when (tab) {
            CatalogTab.MOVIES -> CatalogTab.TV_SHOWS
            CatalogTab.TV_SHOWS -> CatalogTab.ANIME
            CatalogTab.ANIME -> CatalogTab.MOVIES
        }

        fun requesterFor(tab: CatalogTab): FocusRequester = when (tab) {
            CatalogTab.MOVIES -> moviesToggleButtonFocusRequester
            CatalogTab.TV_SHOWS -> tvShowsToggleButtonFocusRequester
            CatalogTab.ANIME -> animeToggleButtonFocusRequester
        }






        // =================================================================================
        // ✅ MAIN CONTENT BOX - Gets blurred when sidebar opens
        // =================================================================================

        //        Radius | GPU Cost | Frame Time | Visual Quality
        //        -------|----------|------------|---------------
        //        5px    | 0.5ms    | Negligible | Subtle
        //        10px   | 1-2ms    | Negligible | Noticeable ✅ YOU ARE HERE
        //        15px   | 2-3ms    | Minor      | Strong
        //        25px   | 4-6ms    | Moderate   | Very strong
        //        50px   | 10-15ms  | Noticeable | Extreme

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isSidebarFocused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // API 31+ (onn box): real GPU blur — looks great, leave it.
                        Modifier.graphicsLayer {
                            renderEffect = android.graphics.RenderEffect
                                .createBlurEffect(
                                    10f, 10f,
                                    android.graphics.Shader.TileMode.CLAMP
                                )
                                .asComposeRenderEffect()
                        }
                    } else {
                        // API 25–30 (Fire TV): RenderEffect blur doesn't exist here.
                        // The old fallback dropped the whole screen to alpha 0.4 and
                        // scaled it 0.97 — that's the washed-out "haze" + shrink. Leave
                        // the content untouched; we darken it with a scrim below instead.
                        Modifier
                    }
                )
        ) {
            // Background image
            Image(
                painter = painterResource(id = R.drawable.home_screen),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )


            // ====================================================================================> Trailer Section (extracted)
            CompositionLocalProvider(LocalCarouselCycleTick provides cycleTick) {
                if (startContent) Box(
                    modifier = Modifier
                        .offset(y = (-18).dp)
                        .focusRequester(trailerFrameFocusRequester)
                        .onFocusChanged { if (it.hasFocus) lastFocusedTarget = SidebarReturnTarget.TRAILER }
                        .focusProperties {
                            right = trendingFrameFocusRequester
                            down = requesterFor(neighborOf(selectedTab))
                            left = sidebarFocusRequester
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        if (trailerSectionState !is TrailerFrameState.Selected) {
                                            BlueHiveApplication.playHoverSound()
                                            openSidebar(SidebarReturnTarget.TRAILER)
                                            true
                                        } else {
                                            false  // Let carousel handle it
                                        }
                                    }

                                    else -> false
                                }
                            } else false
                        }
                ) {
                    HomeScreenTrailerSectionCompose(
                        // Pause if sidebar is focused OR if the home activity is not the
                        // foreground activity (user is in catalog, search, details, etc.)
                        isPaused = isSidebarFocused || !isHomeInForeground,
                        onStateChanged = { newState ->
                            trailerSectionState = newState
                        }
                    )
                }

                // ====================================================================================>

                // ====================================================================================> trending Section (extracted)
                if (startContent) Box(
                    modifier = Modifier
                        // 458 dp before the panel narrowed by 35 dp to 16:9
                        // (2026-10-19); moved right by the same 35 so its right
                        // edge still lines up with Continue Watching below.
                        .offset(x = 493.dp, y = (-3.6).dp)
                        .focusRequester(trendingFrameFocusRequester)
                        .onFocusChanged { if (it.hasFocus) lastFocusedTarget = SidebarReturnTarget.TRENDING }
                        .focusProperties {
                            left = trailerFrameFocusRequester
                            // Straight down to the shelf when there is no panel --
                            // the first shelf then runs underneath this frame.
                            down = if (continueWatchingShown()) continueWatchingRequester
                                   else tvLazyRowsSingleShelfFocusRequester
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        if (trendingState !is TrendingFrameState.Selected) {
                                            BlueHiveApplication.playHoverSound()
                                            trailerFrameFocusRequester.requestFocus()
                                            true
                                        } else {
                                            false
                                        }
                                    }

                                    else -> false
                                }
                            } else false
                        }
                ) {
                    HomeScreenTrendingCompose(
                        trendType = "day",
                        isPaused = isSidebarFocused || !isHomeInForeground,
                        onItemClick = onMediaClick,
                        onStateChanged = { newState -> trendingState = newState }
                    )
                }
            }
            // ====================================================================================>


            // ====================================================================================> Continue Watching Section (extracted)
            // ✅ Shows Continue Watching for ALL media types on first shelf
            if (startContent && isOnFirstShelf) {
                Box(
                    modifier = Modifier
                        .offset(x = 690.dp, y = 290.dp)
                        .focusRequester(continueWatchingRequester)
                        .onFocusChanged { if (it.hasFocus) lastFocusedTarget = SidebarReturnTarget.CONTINUE_WATCHING }
                        .focusProperties {
                            up   = trendingFrameFocusRequester
                            left = animeToggleButtonFocusRequester
                        }
                ) {
                    HomeScreenContinueWatchingCompose(
                        profileId = profileId,
                        focusRequester = continueWatchingRequester,
                        upNeighbor = trendingFrameFocusRequester,
                        // DOWN SKIPS THE FIRST ROW ON PURPOSE.
                        //
                        // This panel sits BESIDE row 0, not above it -- the row
                        // runs underneath it and stops short of it. So "down"
                        // onto row 0 would be a sideways move dressed as a
                        // downward one, landing on a row that is already on
                        // screen and already reachable with LEFT. Row 1 is the
                        // first thing genuinely below the panel.
                        //
                        // Deliberately ONE WAY. Coming back UP from row 1 goes
                        // to row 0, not here, because that is the stack's own
                        // vertical order and the panel is not part of it.
                        //
                        // The sound is made here because nothing downstream
                        // will: requestNavigation is called directly rather
                        // than through the stack's key handler, which is where
                        // its own DOWN sound lives.
                        onNavigateDown = {
                            BlueHiveApplication.playHoverSound()
                            enterShelfAt = 1
                        },
                        onNavigateLeft = {
                            tvLazyRowsSingleShelfFocusRequester.requestFocus()
                        },
                        // Composed even when the profile has no history, so it keeps
                        // loading (and refreshing on every return here) -- it just
                        // draws nothing, and this is how the screen finds out.
                        onHistoryKnown = { hasHistory -> continueWatchingHasHistory = hasHistory },
                        knownHasHistory = continueWatchingHasHistory,
                    )
                }
            }
            // ====================================================================================>







            // ====================================================================================> Radio Buttons

            // Radio-tab parking node — invisible, 1dp. Clicking a radio button
            // makes that button unfocusable (selected → isFocusable=false),
            // leaving a one-frame gap before pendingFocusRequester re-homes
            // focus. Without a holder, Compose fills that gap by flashing focus
            // onto the trailer section. We park focus here synchronously the
            // instant a tab is clicked, so focus never escapes the radio area.
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .graphicsLayer { alpha = 0f }
                    .focusRequester(radioParkingRequester)
                    .focusProperties { canFocus = radioParkingEnabled }
                    .onPreviewKeyEvent { event ->
                        // While parked, swallow keys so a stray press during the
                        // swap frame can't move focus before the target lands.
                        radioParkingEnabled && event.type == KeyEventType.KeyDown
                    }
                    .focusable()
            )

            MoviesRadioButton(
                fontFamily = passionFont,
                focusRequester = moviesToggleButtonFocusRequester,
                selected = (selectedTab == CatalogTab.MOVIES),
                downNeighbor = tvLazyRowsSingleShelfFocusRequester,
                upNeighbor = trailerFrameFocusRequester,
                onOpenSidebar = { openSidebar(SidebarReturnTarget.MOVIES) },
                onFocused = { lastFocusedTarget = SidebarReturnTarget.MOVIES },
                onNavSound = radioNavSound,
                rightNeighbor = if (selectedTab == CatalogTab.TV_SHOWS) {
                    animeToggleButtonFocusRequester
                } else {
                    tvShowsToggleButtonFocusRequester
                },
                onSelect = {
                    radioParkingEnabled = true
                    radioParkingRequester.requestFocus()
                    selectedTab = CatalogTab.MOVIES
                    Log.d("FOCUS_DEBUG", "🔄 Tab switched to: $selectedTab")
                    pendingFocusRequester = tvShowsToggleButtonFocusRequester
                }
            )

            TvShowsRadioButton(
                fontFamily = passionFont,
                focusRequester = tvShowsToggleButtonFocusRequester,
                selected = (selectedTab == CatalogTab.TV_SHOWS),
                upNeighbor = trailerFrameFocusRequester,
                downNeighbor = tvLazyRowsSingleShelfFocusRequester,
                shouldOpenSidebar = (selectedTab == CatalogTab.MOVIES),
                onOpenSidebar = {
                    openSidebar(SidebarReturnTarget.TV_SHOWS)
                },
                onFocused = { lastFocusedTarget = SidebarReturnTarget.TV_SHOWS },
                onNavSound = radioNavSound,
                leftNeighbor = moviesToggleButtonFocusRequester,
                // When ANIME is the selected tab its radio button is toggled and
                // unfocusable, so RIGHT from TV SHOWS must skip it and land on
                // Continue Watching instead.
                rightNeighbor = if (selectedTab == CatalogTab.ANIME) {
                    // With no panel there is nothing to the right; stay put.
                    if (continueWatchingShown()) continueWatchingRequester else FocusRequester.Cancel
                } else {
                    animeToggleButtonFocusRequester
                },
                onSelect = {
                    radioParkingEnabled = true
                    radioParkingRequester.requestFocus()
                    selectedTab = CatalogTab.TV_SHOWS
                    Log.d("FOCUS_DEBUG", "🔄 Tab switched to: $selectedTab")
                    pendingFocusRequester = animeToggleButtonFocusRequester
                }
            )

            AnimeRadioButton(
                fontFamily = passionFont,
                focusRequester = animeToggleButtonFocusRequester,
                selected = (selectedTab == CatalogTab.ANIME),
                upNeighbor = trailerFrameFocusRequester,
                downNeighbor = tvLazyRowsSingleShelfFocusRequester,
                onFocused = { lastFocusedTarget = SidebarReturnTarget.ANIME },
                onNavSound = radioNavSound,
                leftNeighbor = if (selectedTab == CatalogTab.TV_SHOWS) {
                    moviesToggleButtonFocusRequester
                } else {
                    tvShowsToggleButtonFocusRequester
                },
                // With no panel there is nothing to the right; stay put.
                rightNeighbor = if (continueWatchingShown()) continueWatchingRequester
                                else FocusRequester.Cancel,
                onSelect = {
                    radioParkingEnabled = true
                    radioParkingRequester.requestFocus()
                    selectedTab = CatalogTab.ANIME
                    pendingFocusRequester = moviesToggleButtonFocusRequester
                }
            )


            // ====================================================================================>


            // ====================================================================================> Tv Lazy Column implementation (TV is class type not media type)

            // Movies shelf
            if (startContent && selectedTab == CatalogTab.MOVIES) {
                val backToTabsRequester = requesterFor(neighborOf(selectedTab))

                ShelfStack(
                    mediaType = MediaType.MOVIES,
                    // LEFT on the first card of any shelf opens the sidebar,
                    // identical to BACK. lastFocusedTarget is what lets RIGHT
                    // on the sidebar come back to this row and card.
                    onExitLeft = { openSidebar(lastFocusedTarget) },
                    modifier = Modifier
                        .focusRequester(tvLazyRowsSingleShelfFocusRequester)
                        .onFocusChanged {
                            if (it.hasFocus) lastFocusedTarget = SidebarReturnTarget.MOVIES_SHELF
                            isShelfFocused = it.hasFocus
                        }
                        .offset(x = 90.dp, y = SHELF_STACK_Y)
                        .width(880.dp),
                    onMediaClick = onMediaClick,
                    onNavigateBackToTabs = { backToTabsRequester.requestFocus() },
                    onShelfChanged = { newShelf ->
                        Log.d("HOME", "📺 Movies shelf changed to: $newShelf")
                    },
                    showContinueWatching = isOnFirstShelf,
                    onFirstShelfChanged = { isFirst ->
                        isOnFirstShelf = isFirst
                        Log.d("HOME", "📺 First shelf status: $isFirst")
                    },
                    profileId = profileId,
                    onFirstPageReady = { firstShelfDataReady = true },
                    reserveContinueWatching = continueWatchingHasHistory != false,
                    enterAtShelf = enterShelfAt,
                    onEnterAtShelfHandled = { enterShelfAt = null },
                    scrollToShelf = scrollShelfTo,
                    onScrollToShelfHandled = { scrollShelfTo = null },
                )
            }

            // TV Shows shelf
            if (startContent && selectedTab == CatalogTab.TV_SHOWS) {
                Log.d("FOCUS_DEBUG", "🎬 Rendering TV Shows shelf")
                val backToTabsRequester = requesterFor(neighborOf(selectedTab))

                ShelfStack(
                    mediaType = MediaType.TV_SHOWS,
                    // LEFT on the first card of any shelf opens the sidebar,
                    // identical to BACK. lastFocusedTarget is what lets RIGHT
                    // on the sidebar come back to this row and card.
                    onExitLeft = { openSidebar(lastFocusedTarget) },
                    modifier = Modifier
                        .focusRequester(tvLazyRowsSingleShelfFocusRequester)
                        .offset(x = 90.dp, y = SHELF_STACK_Y)
                        .width(880.dp)
                        .onFocusChanged { focusState ->
                            if (focusState.hasFocus) lastFocusedTarget = SidebarReturnTarget.TV_SHOWS_SHELF
                            isShelfFocused = focusState.hasFocus
                            Log.d("FOCUS_DEBUG", "TV Shows ShelfStack focus changed: ${focusState.hasFocus}")
                        },
                    onMediaClick = onMediaClick,
                    onNavigateBackToTabs = { backToTabsRequester.requestFocus() },
                    onShelfChanged = { newShelf ->
                        Log.d("HOME", "📺 TV shelf changed to: $newShelf")
                    },
                    showContinueWatching = isOnFirstShelf,
                    onFirstShelfChanged = { isFirst ->
                        isOnFirstShelf = isFirst
                        Log.d("HOME", "📺 TV first shelf status: $isFirst")
                    },
                    profileId = profileId,
                    reserveContinueWatching = continueWatchingHasHistory != false,
                    enterAtShelf = enterShelfAt,
                    onEnterAtShelfHandled = { enterShelfAt = null },
                    scrollToShelf = scrollShelfTo,
                    onScrollToShelfHandled = { scrollShelfTo = null },
                )
            }

            // Anime shelf
            if (startContent && selectedTab == CatalogTab.ANIME) {
                Log.d("ANIME_DEBUG", "🎌 Shelf rendering, shelves count check")
                val backToTabsRequester = requesterFor(neighborOf(selectedTab))

                ShelfStack(
                    mediaType = MediaType.ANIME,
                    // LEFT on the first card of any shelf opens the sidebar,
                    // identical to BACK. lastFocusedTarget is what lets RIGHT
                    // on the sidebar come back to this row and card.
                    onExitLeft = { openSidebar(lastFocusedTarget) },
                    modifier = Modifier
                        .focusRequester(tvLazyRowsSingleShelfFocusRequester)
                        .offset(x = 90.dp, y = SHELF_STACK_Y)
                        .width(880.dp)
                        .onFocusChanged { focusState ->
                            if (focusState.hasFocus) lastFocusedTarget = SidebarReturnTarget.ANIME_SHELF
                            isShelfFocused = focusState.hasFocus
                            Log.d("FOCUS_DEBUG", "Anime ShelfStack focus changed: ${focusState.hasFocus}")
                        },
                    onMediaClick = onMediaClick,
                    onNavigateBackToTabs = { backToTabsRequester.requestFocus() },
                    onShelfChanged = { newShelf ->
                        Log.d("HOME", "🎌 Anime shelf changed to: $newShelf")
                    },
                    showContinueWatching = isOnFirstShelf,
                    onFirstShelfChanged = { isFirst ->
                        isOnFirstShelf = isFirst
                        Log.d("HOME", "🎌 Anime first shelf status: $isFirst")
                    },
                    profileId = profileId,
                    reserveContinueWatching = continueWatchingHasHistory != false,
                    enterAtShelf = enterShelfAt,
                    onEnterAtShelfHandled = { enterShelfAt = null },
                    scrollToShelf = scrollShelfTo,
                    onScrollToShelfHandled = { scrollShelfTo = null },
                )
            }
            // ====================================================================================>
        }


        // ── Sidebar dim (Fire TV / API < 31 only) ───────────────────────────────
        // The onn box blurs the home content via RenderEffect; pre-31 devices can't,
        // so we drop the same dark scrim the close-app overlay uses over the home
        // screen while the sidebar is open. Sits above the content, below the sidebar.
        if (isSidebarFocused && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(overlayScrimColor())
            )
        }


        // ====================================================================================> SIDEBAR HERE
        HomeScreenSidebarCompose(
            focusRequester   = sidebarFocusRequester,
            canFocus         = sidebarCanFocus,
            profileAvatarRes = profileAvatarRes,
            onFocusChanged = { focused ->
                isSidebarFocused = focused
            },
            onExitToRight = {
                BlueHiveApplication.coilImageLoader.memoryCache?.clear()
                Log.d("CACHE", "🧹 Cleared Coil memory + disk caches on sidebar exit")

                // ONE PRESS, ONE TICK. The sidebar has just played the tick for
                // this RIGHT. Everything from here to the end of the restore
                // below moves focus on its own, and a radio button announces
                // every arrival -- so the press used to tick twice, ~100 ms
                // apart (measured 2026-10-16). Muted until the restore lands;
                // the cap only matters if that never happens.
                BlueHiveApplication.muteHoverSounds(SIDEBAR_RETURN_MUTE_CAP_MS)

                sidebarParkingEnabled = true
                sidebarCanFocus = false
                requestSidebarReturnFocus = true
            },
            onProfileClick = {
                openProfileScreen(context)
            },
            onSearchSubmit = { query ->
                openSearchScreen(context, profileId, query)
            },
            onMediaCatalogClick = {
                openMediaCatalogScreen(context, profileId)
            },
            onSettingsClick = {
                Log.d("SIDEBAR", "Settings clicked")
                context.startActivity(
                    Intent(context, com.example.bluehive.settings.SettingsActivity::class.java)
                )
            },
            onFavoritesClick = { openFavoritesScreen(context, profileId) },
            onLiveTvClick = {
                Log.d("SIDEBAR", "Live TV clicked")
                openLiveTvScreen(context, profileId)
            },
            onChangelogClick = {
                Log.d("SIDEBAR", "Changelog clicked")
            },
            onHistoryClick = { openHistoryScreen(context, profileId) },
        )
        // ====================================================================================>

        // ── Item 6: offline / retry banner ──────────────────────────────────────
        // Toggling startContent off→on remounts every content child, which
        // re-runs their fetch effects. That's our "reload" with zero child
        // changes. The connectivity check is the failure signal because the
        // repositories swallow errors and return empty lists.
        val reloadContent: () -> Unit = {
            retryScope.launch {
                startContent = false
                kotlinx.coroutines.android.awaitFrame()
                kotlinx.coroutines.android.awaitFrame()
                startContent = true
                kotlinx.coroutines.android.awaitFrame()
                trailerFrameFocusRequester.requestFocus()
            }
            Unit
        }

        // Initial check: if we mounted offline, surface the banner immediately.
        LaunchedEffect(Unit) {
            if (!NetworkMonitor.isOnline(context)) showRetryBanner = true
        }

        // Auto-recovery: when the network returns on its own, hide the banner
        // and reload. The callback fires on a binder thread, so hop to the
        // composition's main-dispatcher scope before touching state.
        DisposableEffect(Unit) {
            val cb = NetworkMonitor.registerOnlineCallback(context) {
                retryScope.launch {
                    if (showRetryBanner) {
                        showRetryBanner = false
                        reloadContent()
                    }
                }
            }
            onDispose { NetworkMonitor.unregister(context, cb) }
        }

        if (showRetryBanner) {
            RetryOverlay(
                onRetry = {
                    if (NetworkMonitor.isOnline(context)) {
                        showRetryBanner = false
                        reloadContent()
                    }
                    // Still offline → banner stays up, user can press again.
                }
            )
        }

        // ── Profile-entry transition overlay ────────────────────────────────────
        // Topmost layer. Fades out once content is ready + min time elapsed.
        AnimatedVisibility(
            visible = showEntryTransition,
            enter   = androidx.compose.animation.EnterTransition.None,
            exit    = fadeOut(animationSpec = tween(durationMillis = 400)),
        ) {
            // Shared with ProfileScreenActivity, which raises the identical
            // overlay the instant its press animation ends — see
            // ProfileEntryOverlay. The two must stay identical, or the
            // handoff between the activities becomes a visible flicker.
            com.example.bluehive.utilities.ProfileEntryOverlay(profileName)
        }

        // ── Exit confirmation overlay / back-button routing ─────────────────────
        // On the home screen (sidebar NOT open): back → open sidebar.
        // On the sidebar (sidebar IS open):      back → show exit overlay.
        // All close-app logic lives in CloseApplication.kt — nothing to maintain
        // here except passing the two context values below.
        CloseApplicationHandler(
            isSidebarOpen = isSidebarFocused,
            onOpenSidebar = { openSidebar(lastFocusedTarget) },
            // From a title card, back asks instead of assuming. The guard is the
            // container-focus flag, so it covers every shelf of every tab and
            // nothing else on the screen.
            isOnLazyRow = isShelfFocused,
            // "Top of the page" is the radio button for the NEXT tab along,
            // because the selected tab's own button is toggled and unfocusable.
            // Same destination UP from the first shelf already goes to, via
            // onNavigateBackToTabs.
            //
            // And the rows go back to the first shelf with it, so the page
            // really is at its top: What's Popular under the tabs and Continue
            // Watching beside it, instead of whichever row back was pressed on.
            // Focus moves first; the stack then changes row without taking it.
            onGoToTop = {
                requesterFor(neighborOf(selectedTab)).requestFocus()
                scrollShelfTo = 0
            },
            // Synchronous on purpose: this runs before the recomposition that
            // takes the overlay away, so focus steps straight from its button
            // to the title card with no unowned frames in between. Asking for
            // the STACK rather than a card is what makes its own tracker
            // restore the saved card of the shelf the viewer was on, so this
            // screen never has to know which row or which card that was.
            onRestoreRowFocus = {
                runCatching { tvLazyRowsSingleShelfFocusRequester.requestFocus() }
                restoreShelfFocusPending = true
            },
        )

    }
}







// =================================================================================> Radio buttons
@Composable
fun MoviesRadioButton(
    fontFamily: FontFamily,
    focusRequester: FocusRequester,
    selected: Boolean,
    offsetX: androidx.compose.ui.unit.Dp = 228.dp,
    offsetY: androidx.compose.ui.unit.Dp = 270.dp,
    enableFocus: Boolean = true,
    downNeighbor: FocusRequester,
    upNeighbor: FocusRequester,
    rightNeighbor: FocusRequester,
    onOpenSidebar: () -> Unit,
    onFocused: () -> Unit = {},
    // Fired when focus LANDS here from anywhere. The old sound fired from this
    // button's own key handler, so it could only speak when it already had
    // focus -- arriving from the shelf row above was silent.
    onNavSound: () -> Unit = {},
    onSelect: () -> Unit = {}
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(
            text = "MOVIES",
            fontSize = 11f
        ),
        isToggleable = true,
        toggled = selected,
        isFocusable = enableFocus && !selected,
        fontFamily = fontFamily,
        focusRequester = focusRequester,
        onFocusGained   = onNavSound,
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .focusProperties {
                canFocus = enableFocus && !selected
                down = downNeighbor
                right = rightNeighbor
                up = upNeighbor  // ✅ ADD THIS
            }
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                    BlueHiveApplication.playHoverSound()
                    onOpenSidebar()
                    true // consume
                } else {
                    false
                }
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        // LEFT and RIGHT land on another radio button, which
                        // announces its own arrival, so speaking here too would
                        // play the same sample twice.
                        Key.DirectionLeft,
                        Key.DirectionRight -> false

                        // UP AND DOWN BOTH LEAVE THE ROW, and neither place they
                        // land says anything: the trailer frame above has no
                        // arrival sound, and a title card in the shelf below
                        // plays from the card you LEAVE, never the one you reach.
                        // So nothing downstream will speak for either move and
                        // both have to be made here.
                        //
                        // UP was missing. The comment this replaces claimed
                        // "everything except DOWN lands on something that
                        // announces its own arrival" -- true of LEFT and RIGHT,
                        // never of UP, so moving onto the trailer frame was
                        // silent while every other move on this screen spoke.
                        Key.DirectionUp -> {
                            BlueHiveApplication.playNavClickSound()
                            false
                        }

                        // DOWN IS THE EXCEPTION. It leaves for the shelf row,
                        // and a title card makes no sound when focus lands on
                        // it -- that row plays from the card you LEAVE, never
                        // the one you reach. So nothing downstream will speak
                        // for this move and it has to be made here.
                        Key.DirectionDown -> {
                            BlueHiveApplication.playNavClickSound()
                            false
                        }

                        else -> false
                    }
                } else {
                    false
                }
            },
        dimensions      = RADIO_DIMENSIONS,
        colors          = RADIO_COLORS,
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX = (RADIO_MAIN_W - RADIO_GLOW_W) / 2,
            offsetY = (-1.5).dp
        ),
        animationConfig = RADIO_ANIMATION,
        playClickSound = false,
        onClick = {
            if (!selected) {
                BlueHiveApplication.playClickSound()
                onSelect()
            }
            Log.d("RADIO", "Movies button click")
        }
    )
}



@Composable
fun TvShowsRadioButton(
    fontFamily: FontFamily,
    focusRequester: FocusRequester,
    selected: Boolean,
    offsetX: androidx.compose.ui.unit.Dp = 294.dp,
    offsetY: androidx.compose.ui.unit.Dp = 270.dp,
    enableFocus: Boolean = true,
    downNeighbor: FocusRequester,
    upNeighbor: FocusRequester,
    leftNeighbor: FocusRequester,
    rightNeighbor: FocusRequester,
    onOpenSidebar: () -> Unit,
    shouldOpenSidebar: Boolean = false,
    onFocused: () -> Unit = {},
    // Fired when focus LANDS here from anywhere. The old sound fired from this
    // button's own key handler, so it could only speak when it already had
    // focus -- arriving from the shelf row above was silent.
    onNavSound: () -> Unit = {},
    onSelect: () -> Unit = {}
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(
            text = "TV SHOWS",
            fontSize = 11f
        ),
        isToggleable = true,
        toggled = selected,
        isFocusable = enableFocus && !selected,
        fontFamily = fontFamily,
        focusRequester = focusRequester,
        onFocusGained   = onNavSound,
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .focusProperties {
                canFocus = enableFocus && !selected
                up = upNeighbor
                right = rightNeighbor
                left = leftNeighbor
                down =
                    downNeighbor  // 2B) when DPAD DOWN happens while TV SHOWS is focused, Compose’s focus engine doesn’t guess. It already knows:
            }
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                    if (shouldOpenSidebar) {  // ✅ ONLY consume if sidebar should open
                        BlueHiveApplication.playHoverSound()
                        onOpenSidebar()
                        true
                    } else {
                        false  // ✅ Don't consume - allow normal focus navigation
                    }
                } else false
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        // LEFT and RIGHT land on another radio button, which
                        // announces its own arrival, so speaking here too would
                        // play the same sample twice.
                        Key.DirectionLeft,
                        Key.DirectionRight -> false

                        // UP AND DOWN BOTH LEAVE THE ROW, and neither place they
                        // land says anything: the trailer frame above has no
                        // arrival sound, and a title card in the shelf below
                        // plays from the card you LEAVE, never the one you reach.
                        // So nothing downstream will speak for either move and
                        // both have to be made here.
                        //
                        // UP was missing. The comment this replaces claimed
                        // "everything except DOWN lands on something that
                        // announces its own arrival" -- true of LEFT and RIGHT,
                        // never of UP, so moving onto the trailer frame was
                        // silent while every other move on this screen spoke.
                        Key.DirectionUp -> {
                            BlueHiveApplication.playNavClickSound()
                            false
                        }

                        // DOWN IS THE EXCEPTION. It leaves for the shelf row,
                        // and a title card makes no sound when focus lands on
                        // it -- that row plays from the card you LEAVE, never
                        // the one you reach. So nothing downstream will speak
                        // for this move and it has to be made here.
                        Key.DirectionDown -> {
                            BlueHiveApplication.playNavClickSound()
                            false  // 2B) your onKeyEvent returns false for DirectionDown, which is good — it doesn’t consume the event, so the focus engine is allowed to move focus.
                        }

                        else -> false
                    }
                } else {
                    false
                }
            },
        dimensions      = RADIO_DIMENSIONS,
        colors          = RADIO_COLORS,
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX = (RADIO_MAIN_W - RADIO_GLOW_W) / 2,
            offsetY = (-1.5).dp
        ),
        animationConfig = RADIO_ANIMATION,
        playClickSound = false, // ✅ avoid double pop sound
        onClick = {
            if (!selected) {
                BlueHiveApplication.playClickSound()
                onSelect()
            }
            Log.d("RADIO", "Tv Shows button click")
        }
    )
}


@Composable
fun AnimeRadioButton(
    fontFamily: FontFamily,
    focusRequester: FocusRequester,
    selected: Boolean,
    offsetX: androidx.compose.ui.unit.Dp = 360.dp, // 321.4
    offsetY: androidx.compose.ui.unit.Dp = 270.dp,
    enableFocus: Boolean = true,
    downNeighbor: FocusRequester,
    upNeighbor: FocusRequester,
    leftNeighbor: FocusRequester,
    rightNeighbor: FocusRequester,
    onFocused: () -> Unit = {},
    // Fired when focus LANDS here from anywhere. The old sound fired from this
    // button's own key handler, so it could only speak when it already had
    // focus -- arriving from the shelf row above was silent.
    onNavSound: () -> Unit = {},
    onSelect: () -> Unit = {}
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(
            text = "ANIME",
            fontSize = 11f
        ),
        isToggleable = true,
        toggled = selected,
        isFocusable = enableFocus && !selected,
        fontFamily = fontFamily,
        focusRequester = focusRequester,
        onFocusGained   = onNavSound,
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .focusProperties {
                canFocus = enableFocus && !selected
                up = upNeighbor
                down = downNeighbor
                left = leftNeighbor
                right = rightNeighbor
            }
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        // LEFT and RIGHT land on another radio button, which
                        // announces its own arrival, so speaking here too would
                        // play the same sample twice.
                        Key.DirectionLeft,
                        Key.DirectionRight -> false

                        // UP AND DOWN BOTH LEAVE THE ROW, and neither place they
                        // land says anything: the trailer frame above has no
                        // arrival sound, and a title card in the shelf below
                        // plays from the card you LEAVE, never the one you reach.
                        // So nothing downstream will speak for either move and
                        // both have to be made here.
                        //
                        // UP was missing. The comment this replaces claimed
                        // "everything except DOWN lands on something that
                        // announces its own arrival" -- true of LEFT and RIGHT,
                        // never of UP, so moving onto the trailer frame was
                        // silent while every other move on this screen spoke.
                        Key.DirectionUp -> {
                            BlueHiveApplication.playNavClickSound()
                            false
                        }

                        // DOWN IS THE EXCEPTION. It leaves for the shelf row,
                        // and a title card makes no sound when focus lands on
                        // it -- that row plays from the card you LEAVE, never
                        // the one you reach. So nothing downstream will speak
                        // for this move and it has to be made here.
                        Key.DirectionDown -> {
                            BlueHiveApplication.playNavClickSound()
                            false
                        }

                        else -> false
                    }
                } else {
                    false
                }
            },
        dimensions      = RADIO_DIMENSIONS,
        colors          = RADIO_COLORS,
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX = (RADIO_MAIN_W - RADIO_GLOW_W) / 2,
            offsetY = (-1.5).dp
        ),
        animationConfig = RADIO_ANIMATION,
        playClickSound = false, // ✅ avoid double pop sound
        onClick = {
            if (!selected) {
                BlueHiveApplication.playClickSound()
                onSelect()
            }
            Log.d("RADIO", "ANIME button click")
        }
    )
}
// =================================================================================> Radio buttons









