package com.example.bluehive.singleShelfComponents

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.BuildConfig
import com.example.bluehive.models.MediaItem
import com.example.bluehive.utilities.AppTypography
import com.example.bluehive.utilities.TvWideLazyRow
import com.example.bluehive.utilities.TV_WIDE_ROW_VIEWPORT_WIDTH
import com.example.bluehive.utilities.TV_WIDE_ROW_RIGHT_BODY_X
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs


/**
 * The vertical stack of content shelves (POPULAR, TOP_RATED, NETFLIX, ...).
 *
 * Each shelf is its OWN TvWideLazyRow.  The focused row sits at a fixed Y;
 * the row below peeks up from the bottom edge; rows above are hidden.
 *
 * FOCUS MODEL (unified — see UnifiedShelfFocusTracker)
 *   - Vertical DPad is intercepted here, in onPreviewKeyEvent, so it never
 *     reaches a card.  That means focus CANNOT escape the stack during a
 *     transition — no parking node is needed.
 *   - A navigation performs exactly one focus move: from the old card to the
 *     target card in the newly-focused shelf.  ShelfStack moves the row,
 *     then bumps the target shelf's card-focus epoch; that shelf's
 *     TvWideLazyRow does the single explicit requestFocus on the right card.
 *   - Because the card-focus epoch is read by each row under an
 *     isFocusedShelf guard, a peek row re-entering the window can never grab
 *     focus with a stale epoch.  Card focus and row focus stay locked
 *     together — no drift.
 */

// Logical height of one TvWideLazyRow (its sectionHeight).  Don't change
// this independently of TvWideLazyRow's sectionHeight default.
val SHELF_STACK_ROW_HEIGHT: Dp = 250.dp

// Vertical gap between the TOP of the focused row and the TOP of the next
// row.  Peek control — lower values show MORE of the next row at the bottom
// edge.  Keep above ~185.dp or the peek row covers the focused row's cards.
val SHELF_ROW_SPACING: Dp = 208.dp   // The gap between "What's Popular" and the peeking row below it

// Above-focused rows hidden (no ghost bleed over the trailer section);
// below-focused rows fully opaque so their card tops peek naturally.
private const val PEEK_ALPHA_ABOVE = 0f
private const val PEEK_ALPHA_BELOW = 1f

// HOW MANY SHELVES ARE COMPOSED EITHER SIDE OF THE FOCUSED ONE.
//
// LEAVE THIS AT 1. Two ways of raising it were built and measured, and both
// lost to simply doing nothing. The numbers are here so nobody spends the
// afternoon again.
//
// THE PROBLEM THEY WERE AIMED AT IS REAL: a shelf change costs one 250-300ms
// frame, and that frame is where the incoming row gets composed -- a whole
// LazyRow and its title cards, built while the stack is already sliding. It is
// the worst single stutter on the home screen. It is NOT the images; the test
// below goes six shelves down then six back up, so the return leg re-enters
// rows whose posters are seconds old in Coil's memory cache and whose pages are
// still held by the parent's PageWindow, and it stutters exactly as often as
// the outbound leg does.
//
// ATTEMPT 1 -- radius 2, a permanently wider window. Failed because MOVING ONE
// SHELF BRINGS EXACTLY ONE NEW ROW INTO THE WINDOW NO MATTER WHAT THE RADIUS
// IS. A wider window does not avoid the cold compose, it only moves the row
// being composed further from the focus. The stall still landed on the
// transition frame, now beside an extra row being laid out and another being
// torn down off the far end.
//
// ATTEMPT 2 -- a "warm window": strict +/-1 at all times, widening to +/-2 only
// after 700ms of stillness, so the extra row was built on a quiet frame and the
// transition had nothing left to do. The reasoning was sound and it did recover
// most of what attempt 1 threw away -- but it still lost to the baseline.
//
// Four trials each, identical fresh-process warm-up, 6 x DOWN then 6 x UP at
// 800ms, discarding trial 1 every time because a freshly launched process
// always reads worse while it is still warming:
//
//                      rows      janky        99th pct
//     radius 1           3        7.9%         ~317ms      <-- what ships
//     warm window        5       11.9%         ~533ms
//     radius 2           5       18.7%         ~567ms
//
// THE CONCLUSION IS THE ROW COUNT, not the timing. Both attempts hold five rows
// composed; both are worse than holding three, and the gap between them is
// exactly what moving the churn off the transition buys you. Pre-building a row
// and holding a row are the same act, and holding it costs more than the stall
// it avoids. Anything shaped like "keep the next shelf ready" is on the wrong
// side of that line, including warming a single row in the direction of travel.
//
// ATTEMPT 3 -- make the row cheaper to BUILD rather than building it sooner.
// Every title card owned a titleVisible state, an animateFloatAsState and a
// LaunchedEffect for the hover title, although only the focused card ever draws
// one: two dozen objects per row, all constructed on precisely the frame we
// were trying to shorten. Hoisting them into a single row-level copy was
// behaviourally identical -- once the fade-out was snapped instead of tweened,
// because a shared alpha tweening down makes the card you just moved TO pick it
// up mid-fall and flash a half-lit title -- and it changed nothing:
//
//     hoisted title      3       10.6%         ~333ms
//
// The 99th percentile, the number that actually describes the stutter, did not
// move at all. Reverted: a change to focus-adjacent animation code that buys
// nothing measurable is not worth the next person's debugging time.
//
// WHAT PROFILING DID ESTABLISH. dumpsys gfxinfo framestats breaks a single frame
// into its phases, and the transition frames said this, averaged over five:
//
//     animation (recomposition)    52ms
//     traversal (measure/layout)  0.1ms
//     draw recording               212ms   <-- 72% of it
//
// Composition is NOT the cost; it is 0.1ms. That is the one durable finding here,
// and it is why all three attempts above failed: each aimed at a phase that was
// already free. It also explains the warm window specifically -- HWUI skips a
// RenderNode at alpha 0 entirely, so a "warmed" row was composed and laid out but
// never DRAWN, and drawing is where the time actually is.
//
// ATTEMPT 4, AND A WARNING ABOUT HOW IT WAS MEASURED. A fourth change looked like
// the answer: MediaCard was passing the CARD's modifier to three of its own
// children, so each title card built four RenderNodes, four focus targets and four
// key handlers instead of one. Cutting that appeared to take draw recording from
// 212ms to 9ms and the shelf stall from ~294ms to ~93ms.
//
// IT WAS NOT REAL, AND THE NUMBER WAS THE TRAP. Every caller builds the card
// modifier as .focusable() ... .focusRequester(fr), with focusable FIRST. A
// focusRequester binds only to a focus target AFTER it in the chain, so it was
// binding to those child nodes -- the duplication was the only thing making cards
// focusable at all. Cutting it silently killed focus on all four MediaCard screens.
// A card that never takes focus never runs its focus callbacks and never swaps to
// the focused halo drawable, so it is genuinely cheaper to draw. The measurement
// was recording a broken app, not a faster one. Reverted; see MediaCard.kt.
//
// A controlled A/B afterwards -- same device, builds interleaved, order flipped,
// six trials each -- put this build and the pre-work HEAD at the same p99 (350ms,
// though note gfxinfo quantises p99 to 50ms steps, so that equality is coarse).
// Nothing in this file's history made shelf navigation faster.
//
// THE LESSON, WHICH IS WHY ALL OF THIS IS STILL WRITTEN DOWN. Four hypotheses,
// four failures, and the most dangerous one was the one with the best numbers.
// Verify the feature still works before believing the frame counter -- a metric
// that improves because something stopped happening looks exactly like a win.
private const val SHELF_WINDOW_RADIUS = 1

// Shelf header (title) animation.
// REAPPEAR_DELAY: gap after a shelf change before the title fades back in.
// FADE_IN_DURATION: length of the alpha 0->1 fade once it starts.
// Snap-out is always instant.  Spamming UP/DOWN keeps the title hidden.
//
// THESE TWO ADD UP, which is what made the title feel absent rather than slow:
// 1500ms of nothing, then a 2000ms fade, is 3.5 SECONDS before the row you are
// already looking at tells you what it is. The fade was also slow enough to sit
// at low alpha for most of its run, so the title read as a smudge for a second
// before becoming legible.
//
// The delay still exists and still does its job -- holding UP/DOWN never flashes
// a title -- it just settles at the speed a person changes rows rather than the
// speed they change their mind.
//
// The fade sits a little above the 350ms row cross-fade below, so the title
// arrives just after the rows settle rather than exactly with them. Landing
// together read as abrupt; trailing slightly reads as the title following the
// move.
// RE-BUDGETED TO FIT BETWEEN TWO PRESSES. These add up, and the sum is what
// matters: at 220 + 900 the title needed 1120ms to become legible, while the
// shelf nav cooldown lets a new press through every 500ms. Moving at any pace at
// all, the title was snapped back to zero and restarted before it had finished
// arriving even once -- so a viewer holding UP never saw a row name at all, and
// a single press made them wait over a second for one.
//
// 150 + 300 = 450ms fits inside the 500ms window, so the title now lands between
// presses instead of chasing them. The delay still does its original job of not
// flashing a title during a fast scroll; it just no longer outlives the scroll.
// Keep the sum under shelfNavCooldownMs if that value changes.
private const val HEADER_REAPPEAR_DELAY_MS   = 150L
private const val HEADER_FADE_IN_DURATION_MS = 300


@Composable
fun ShelfStack(
    mediaType: MediaType,
    modifier: Modifier = Modifier,
    onMediaClick: (MediaItem) -> Unit,
    onNavigateBackToTabs: () -> Unit,
    showContinueWatching: Boolean = false,
    onFirstShelfChanged: (Boolean) -> Unit = {},
    onShelfChanged: (ContentShelf) -> Unit = {},
    profileId: Int = -1,
    // Invoked when LEFT is pressed on the FIRST card of a shelf. The home screen
    // turns this into openSidebar(lastFocusedTarget), so the sidebar opens with a
    // return target and RIGHT comes back to this exact row and card.
    onExitLeft: () -> Unit = {},
    onFirstPageReady: () -> Unit = {},
    // False when the home screen is not showing the Continue Watching panel
    // (the profile has no watch history): the first shelf then runs the full
    // width of every other row instead of stopping short of the panel. Kept
    // apart from showContinueWatching on purpose -- that one flips with the
    // focused shelf, this one only when the history does.
    reserveContinueWatching: Boolean = true,
) {
    val shelves = remember(mediaType) {
        when (mediaType) {
            MediaType.ANIME -> listOf(
                ContentShelf.ANIME_NEW_SEASON,
                ContentShelf.ANIME_SERIES,
                ContentShelf.ANIME_MOVIES,
            )
            else -> ContentShelf.entries.filter {
                it != ContentShelf.ANIME_SERIES &&
                        it != ContentShelf.ANIME_MOVIES &&
                        it != ContentShelf.ANIME_NEW_SEASON
            }
        }
    }
    val repository = remember { ShelfRepository() }

    // Visual position of the focused row.  Lives here (not in the tracker)
    // because it's rememberSaveable — survives rotation.
    var focusedShelfIndex by rememberSaveable { mutableIntStateOf(0) }
    val focusedShelf = shelves[focusedShelfIndex]

    // Per-shelf caches held by the PARENT — survive child decompose.
    val pageWindows = remember { shelves.associateWith { PageWindow() } }
    val rowFocusRequesters = remember { shelves.associateWith { FocusRequester() } }

    // The ONE focus brain: card memory + nav intent + card-focus epochs.
    val tracker = remember { UnifiedShelfFocusTracker() }

    // Holds each shelf's rememberSaveable state (focusedIndex, listState)
    // while it is out of the +/-1 window, so re-entry restores it.
    val stateHolder = rememberSaveableStateHolder()

    LaunchedEffect(focusedShelfIndex) {
        onFirstShelfChanged(focusedShelfIndex == 0)
        onShelfChanged(focusedShelf)
    }

    // Prevents DPad mash from queueing multiple transitions, and blocks a
    // second nav while one is still in flight.
    //
    // HALF A SECOND, deliberately slower than the animation needs. The row slide
    // is a 450ms tween and the focus handoff clears after ~7 frames, so a purely
    // technical floor would sit near 350ms -- which is what this was. The extra
    // 150ms is not for the machine, it is to stop the shelves being scrolled past
    // faster than anyone can read them: at 500ms a held button walks two shelves a
    // second, which is about the rate you can actually take in a row title and a
    // few posters.
    //
    // Presses inside the window are swallowed, not queued (the handler returns
    // true and does nothing), so mashing cannot bank up moves that fire later.
    // Raising this much further starts to read as an unresponsive remote rather
    // than a deliberate pace.
    var lastShelfNavTime by remember { mutableLongStateOf(0L) }
    val shelfNavCooldownMs = 500L
    fun canNavigateShelvesNow(): Boolean {
        if (tracker.isNavigating) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastShelfNavTime < shelfNavCooldownMs) return false
        lastShelfNavTime = now
        return true
    }

    // Translate the whole stack so the focused row sits at Y=0.
    val density = LocalDensity.current
    val rowSpacingPx = with(density) { SHELF_ROW_SPACING.toPx() }
    // THE ONE CONTINUOUS VALUE. Everything else about the stack's appearance is
    // derived from this -- see the per-row alpha further down. There is exactly
    // one animation here, so there is nothing to fall out of sync with.
    //
    // SPRING, NOT TWEEN, AND THAT IS THE WHOLE POINT. A tween that is retargeted
    // mid-flight starts a brand new tween from wherever it happens to be, at ZERO
    // velocity. With FastOutSlowIn that means the stack decelerates almost to a
    // stop and then re-accelerates on every press -- which is what reads as the
    // animation "restarting" when you move through rows quickly. A spring is
    // modelled on position AND velocity, so a new target is absorbed into the
    // motion already in progress and the row keeps flowing.
    //
    // Critically damped (NoBouncy) because shelves overshooting their resting
    // position would look like a bug, not a flourish. Stiffness is the speed
    // knob: raise it toward Spring.StiffnessMedium (1500) for snappier, lower it
    // toward StiffnessLow (200) for lazier. visibilityThreshold is half a pixel
    // -- the default for a generic Float is 0.01, which for a value measured in
    // PIXELS leaves the spring running a long invisible tail.
    val animatedTranslationY by animateFloatAsState(
        targetValue = -focusedShelfIndex * rowSpacingPx,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = 0.5f,
        ),
        label = "stackTranslationY",
    )

    // Navigation handoff.
    // The outgoing card's node churns during the swap recomposition, leaving
    // a few frames with no valid focus owner inside the stack — during which
    // Compose would relocate focus to the search bar.  We prevent that by
    // PARKING focus on a stack-internal node for the duration of the swap,
    // then handing it to the target card.  Card focus itself is still the
    // single epoch-driven move (guarded by isFocusedShelf), so this does NOT
    // reintroduce the titlecard drift — parking only holds the interim.
    LaunchedEffect(tracker.pendingTarget) {
        val target = tracker.pendingTarget ?: return@LaunchedEffect

        // parkingActive was set true synchronously in requestNavigation().
        // One frame lets that canFocus=true reach the parking node.
        kotlinx.coroutines.android.awaitFrame()
        tracker.parkingRequester.requestFocus()   // focus now lives in the stack
        kotlinx.coroutines.android.awaitFrame()

        // Slide the row into place.  The outgoing card can churn freely now —
        // focus is held by the parking node, not by any card.
        focusedShelfIndex = target
        kotlinx.coroutines.android.awaitFrame()
        kotlinx.coroutines.android.awaitFrame()

        // The single, explicit focus move: target shelf focuses its card.
        // (Only that shelf's epoch effect acts — isFocusedShelf guard.)
        tracker.requestCardFocus(shelves[target])

        // Give the card a few frames to actually claim focus BEFORE we let
        // the parking node become unfocusable again.  Releasing parking
        // while it still held focus would re-open the search-bar escape.
        kotlinx.coroutines.android.awaitFrame()
        kotlinx.coroutines.android.awaitFrame()
        kotlinx.coroutines.android.awaitFrame()

        tracker.completeNavigation()   // clears pendingTarget + parkingActive
    }

    // Header (title) visibility state machine.
    // Snap out the instant a nav is requested; fade the new title back in
    // after a settle delay.  Rendered in ShelfStack (fixed screen position),
    // not inside TvWideLazyRow.
    var headerVisible by remember { mutableStateOf(true) }
    var hasInitializedHeader by remember { mutableStateOf(false) }

    LaunchedEffect(tracker.pendingTarget) {
        if (tracker.pendingTarget != null) headerVisible = false
    }

    LaunchedEffect(focusedShelfIndex) {
        if (!hasInitializedHeader) {
            hasInitializedHeader = true
            return@LaunchedEffect
        }
        headerVisible = false
        delay(HEADER_REAPPEAR_DELAY_MS)
        headerVisible = true
    }

    val headerAlpha by animateFloatAsState(
        targetValue = if (headerVisible) 1f else 0f,
        animationSpec = if (headerVisible) {
            tween(durationMillis = HEADER_FADE_IN_DURATION_MS)
        } else {
            snap()
        },
        label = "headerAlpha",
    )


    // focus ENTERING from outside (the tab buttons) and restore the focused
    // shelf's saved card on the way in.
    // Tracks whether focus is currently inside the stack, so we can detect
    // focus ENTERING from outside (the tab buttons) and restore the focused
    // shelf's saved card on the way in.
    var stackHadFocus by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            // Entering from the tabs above: route to the focused shelf's row
            // (not Compose's default topmost focusable).
            .focusProperties {
                onEnter = {
                    rowFocusRequesters[shelves[focusedShelfIndex]]
                        ?: FocusRequester.Default
                }
            }
            // When focus first crosses INTO the stack, restore the focused
            // shelf's saved card.  Does NOT fire on shelf-to-shelf moves
            // (focus stays inside the stack, so hasFocus stays true).
            .onFocusChanged { fs ->
                if (fs.hasFocus && !stackHadFocus) {
                    tracker.requestCardFocus(shelves[focusedShelfIndex])
                }
                stackHadFocus = fs.hasFocus
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                when (event.key) {
                    Key.DirectionDown -> {
                        if (focusedShelfIndex >= shelves.lastIndex) {
                            return@onPreviewKeyEvent false
                        }
                        if (!canNavigateShelvesNow()) return@onPreviewKeyEvent true

                        BlueHiveApplication.playHoverSound()
                        // Record intent only.  The handoff LaunchedEffect
                        // moves the row and the card focus together.
                        tracker.requestNavigation(focusedShelfIndex + 1)
                        true
                    }
                    Key.DirectionUp -> {
                        if (focusedShelfIndex == 0) {
                            onNavigateBackToTabs()
                            return@onPreviewKeyEvent true
                        }
                        if (!canNavigateShelvesNow()) return@onPreviewKeyEvent true

                        BlueHiveApplication.playHoverSound()
                        tracker.requestNavigation(focusedShelfIndex - 1)
                        true
                    }
                    else -> false
                }
            },
    ) {
        // Parking node — invisible, 1dp, stack-internal.  Holds focus during
        // a transition so Compose can't relocate focus to the search bar
        // while the rows swap.  canFocus is gated to parkingActive, so
        // between navigations it is NOT a focus target; it only becomes
        // focusable for the few frames of a swap, and we explicitly
        // requestFocus on it from the handoff effect above.
        Box(
            modifier = Modifier
                .size(1.dp)
                .graphicsLayer { alpha = 0f }
                .focusRequester(tracker.parkingRequester)
                .focusProperties { canFocus = tracker.parkingActive }
                .onPreviewKeyEvent { event ->
                    // While parked, swallow keys so a stray press during the
                    // ~5-frame swap can't move focus anywhere.
                    tracker.parkingActive && event.type == KeyEventType.KeyDown
                }
                .focusable(),
        )

        // Inner Box carries the translation.  Holds all in-window shelves.
        Box(
            modifier = Modifier.graphicsLayer { translationY = animatedTranslationY },
        ) {
            shelves.forEachIndexed { index, shelf ->
                val distance = abs(index - focusedShelfIndex)
                if (distance > SHELF_WINDOW_RADIUS) return@forEachIndexed

                val isFocused = (index == focusedShelfIndex)

                stateHolder.SaveableStateProvider(shelf.name) {
                    Box(
                        modifier = Modifier.graphicsLayer {
                            // MODULATE, DO NOT BUFFER. This is load-bearing, and
                            // the symptom without it is bizarre enough to be
                            // worth spelling out: the instant a row's alpha drops
                            // below 1, the tops of its title cards are sliced off.
                            //
                            // By default an alpha below 1 makes Compose render the
                            // whole row into an offscreen buffer and composite
                            // that, and the buffer is only as big as the row's
                            // LAYOUT bounds. TvWideLazyRow is a 250dp Column whose
                            // card viewport is offset -40dp (see viewportOffsetY),
                            // so the card tops, the side frame pieces and the focus
                            // halo are all drawn ABOVE the row's own bounds. Into a
                            // buffer, they land outside it and are discarded. At
                            // alpha 1 no buffer is allocated, nothing clips, and
                            // the row looks correct -- which is why this only ever
                            // showed up mid-transition.
                            //
                            // ModulateAlpha applies the alpha to each draw
                            // instruction instead, so no buffer is allocated and
                            // out-of-bounds drawing survives. The documented
                            // trade-off is that overlapping translucent draws no
                            // longer composite as a single flattened group -- here
                            // that is only ever visible while a row is mid-fade,
                            // and it is a far better deal than losing the cards.
                            compositingStrategy = CompositingStrategy.ModulateAlpha

                            translationY = index * rowSpacingPx

                            // ALPHA IS A FUNCTION OF WHERE THE ROW IS, not a
                            // second animation racing the first.
                            //
                            // This used to be its own animateFloatAsState on a
                            // 350ms tween, keyed on whether the row was focused,
                            // above or below. Two timers meant two things to get
                            // out of step: move up twice quickly and one row was
                            // still fading in while the next started its own
                            // fade, at a third position, over a stack that was
                            // itself still sliding. It read as the animation
                            // restarting because, in effect, it was.
                            //
                            // Now there is one animation -- the spring above --
                            // and opacity is read off the result. `rows` is how
                            // many row-heights this row currently sits below the
                            // focus line: 0 when it IS the focused row, +1 when
                            // it is the peek beneath, negative when above. It
                            // updates continuously with the spring, so a row that
                            // is half way to the focus line is half faded in, by
                            // construction. Interrupt it however fast you like:
                            // there is no timer to restart, and alpha can never
                            // disagree with position.
                            //
                            // Evaluated in the graphicsLayer lambda, so all of
                            // this runs in the DRAW phase and costs no
                            // recomposition per frame.
                            val rows =
                                (index * rowSpacingPx + animatedTranslationY) / rowSpacingPx
                            alpha = when {
                                // Above the focus line: fade out across one row
                                // of travel, so arriving from above fades in.
                                rows < 0f -> PEEK_ALPHA_ABOVE +
                                        (PEEK_ALPHA_BELOW - PEEK_ALPHA_ABOVE) *
                                        (1f + rows).coerceIn(0f, 1f)
                                // Further than one row below. Unreachable while
                                // SHELF_WINDOW_RADIUS is 1, and kept for whoever
                                // raises it: a second row down sits squarely on
                                // screen and would otherwise draw as a second
                                // peek, leaving the shelf area three rows deep.
                                rows > 1f ->
                                    (PEEK_ALPHA_BELOW * (2f - rows))
                                        .coerceIn(0f, PEEK_ALPHA_BELOW)
                                // The focused row and the single peek beneath it.
                                else -> PEEK_ALPHA_BELOW
                            }
                        },
                    ) {
                        ShelfRowContent(
                            shelf                = shelf,
                            mediaType            = mediaType,
                            pageWindow           = pageWindows[shelf]!!,
                            cardTracker          = tracker,
                            rowFocusRequester    = rowFocusRequesters[shelf]!!,
                            repository           = repository,
                            isFocused            = isFocused,
                            cardFocusEpoch       = tracker.cardFocusEpoch(shelf),
                            showContinueWatching = showContinueWatching
                                    && shelf == ContentShelf.POPULAR,
                            reserveContinueWatching = reserveContinueWatching,
                            onMediaClick         = onMediaClick,
                            profileId            = profileId,
                            onFirstPageReady     = if (shelf == ContentShelf.POPULAR) onFirstPageReady else ({ }),
                            onExitLeft           = onExitLeft,
                        )
                    }
                }
            }
        }

        // Header text — outer Box, fixed screen position, never slides.
        val focusedShelfTitle = remember(focusedShelf, mediaType) {
            repository.getShelfTitle(mediaType, focusedShelf)
        }
        Text(
            text = focusedShelfTitle,
            color = Color.White,
            fontSize = 23.sp,
            fontFamily = AppTypography.passionRegular,
            modifier = Modifier
                .offset(x = (-29).dp, y = (-70).dp)
                .graphicsLayer { alpha = headerAlpha },
        )
    }
}


// -----------------------------------------------------------------------------
// Per-row content.  Owns its own pagination state and forwards focus events.
// -----------------------------------------------------------------------------
@Composable
private fun ShelfRowContent(
    shelf: ContentShelf,
    mediaType: MediaType,
    pageWindow: PageWindow,
    cardTracker: UnifiedShelfFocusTracker,
    rowFocusRequester: FocusRequester,
    repository: ShelfRepository,
    isFocused: Boolean,
    cardFocusEpoch: Int,
    showContinueWatching: Boolean,
    onMediaClick: (MediaItem) -> Unit,
    profileId: Int = -1,
    onFirstPageReady: () -> Unit = {},
    onExitLeft: () -> Unit = {},
    reserveContinueWatching: Boolean = true,
) {
    // Pagination knobs — unchanged from the old HomeSingleShelf.
    val pageSize        = 20
    val maxPages        = 2
    val prefetchTrigger = 12
    val reloadTrigger   = 5

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var loadingJob   by remember { mutableStateOf<Job?>(null) }
    var prefetchJob  by remember { mutableStateOf<Job?>(null) }
    var reloadJob    by remember { mutableStateOf<Job?>(null) }

    var items   by remember { mutableStateOf(pageWindow.getAllItems()) }
    var loading by remember { mutableStateOf(false) }

    // Flips true when a forward prefetch comes back empty — the source has no
    // more pages. Stops the row re-probing the backend every cooldown while the
    // user idles on the last card. Resets when the shelf re-enters composition.
    var endReached by remember { mutableStateOf(false) }

    // Which page a forward prefetch is currently loading (null = none in flight).
    // Guards against cancelling+restarting the SAME page load on every card move —
    // the bug that kept the slow personalized query from ever finishing mid-scroll.
    var prefetchingPage by remember { mutableStateOf<Int?>(null) }

    // Tell the home screen the curtain can rise — fires once the first time
    // this shelf has renderable items, whether from prefetch (instant) or a
    // live fetch. The Boolean key means the effect only re-runs on the single
    // false→true transition, never spuriously on subsequent item updates.
    var hasReportedReady by remember { mutableStateOf(false) }
    LaunchedEffect(items.isNotEmpty()) {
        if (items.isNotEmpty() && !hasReportedReady) {
            hasReportedReady = true
            onFirstPageReady()
        }
    }

    var lastReloadTime   by remember { mutableLongStateOf(0L) }
    val reloadCooldownMs   = 400L

    suspend fun loadPage(pageNum: Int) {
        if (pageWindow.loadedPages.containsKey(pageNum)) return

        loadingJob?.cancelAndJoin()
        loading = true
        // MAIN, DELIBERATELY. ShelfRepository.loadPage is itself
        // withContext(Dispatchers.IO), so the network never touches this thread —
        // dropping the dispatcher here costs nothing and moves only the bookkeeping.
        //
        // WHY IT MATTERS: everything below mutates pageWindow — loadedPages (a
        // plain mutableMapOf), windowStartPage, windowEndPage — and evictPage does
        // the same from the focus callback on the main thread. Two threads writing
        // an unsynchronised LinkedHashMap can lose or corrupt entries, and the two
        // window indices are non-volatile ints, so a main-thread read can see a
        // stale range and getAllItems then returns a list that disagrees with the
        // map: cards missing or repeated, with nothing thrown.
        //
        // This was unreachable until eviction started working. Puts are serialised
        // by the cancelAndJoin above and removes never happened, so the map only
        // ever had one writer. Fixing the dead evict trigger is what introduced
        // the second one, and this closes it in the same breath.
        loadingJob = coroutineScope.launch {
            try {
                val result = repository.loadPage(mediaType, shelf, pageNum, pageSize, profileId)
                if (result.items.isNotEmpty()) {
                    pageWindow.loadedPages[pageNum] = result.items
                    if (pageNum > pageWindow.pageNumberIncrement) {
                        pageWindow.pageNumberIncrement = pageNum
                    }
                    if (pageWindow.loadedPages.size == 1 || pageNum < pageWindow.windowStartPage) {
                        pageWindow.windowStartPage = pageNum
                    }
                    if (pageWindow.loadedPages.size == 1 || pageNum > pageWindow.windowEndPage) {
                        pageWindow.windowEndPage = pageNum
                    }
                    items = pageWindow.getAllItems()
                    if (BuildConfig.DEBUG) Log.d(
                        "PAGING",
                        "OK $shelf page $pageNum loaded (${result.items.size} items, window=${items.size})"
                    )
                }
            } catch (e: Exception) {
                Log.e("PAGING", "FAIL $shelf page $pageNum load failed: ${e.message}", e)
            }
        }
        loadingJob?.join()
        loading = false
    }

    fun evictPage(pageNum: Int) {
        if (!pageWindow.loadedPages.containsKey(pageNum)) return
        pageWindow.loadedPages.remove(pageNum)
        if (pageNum == pageWindow.windowStartPage)      pageWindow.windowStartPage++
        else if (pageNum == pageWindow.windowEndPage)   pageWindow.windowEndPage--
        items = pageWindow.getAllItems()
    }

    LaunchedEffect(Unit) {
        if (pageWindow.loadedPages.isEmpty()) {
            val app = context.applicationContext as? BlueHiveApplication

            // Resolve any warm prefetch for THIS exact shelf+type, captured on
            // the loading screen. Each consume is one-shot (returns then nulls);
            // a null result simply falls through to a normal live fetch. The
            // Netflix slices are profile-keyed, so they only return for the
            // profile they were fetched for.
            val prefetched = when {
                mediaType == MediaType.MOVIES   && shelf == ContentShelf.POPULAR ->
                    app?.consumeMoviesPopularPrefetch()
                mediaType == MediaType.MOVIES   && shelf == ContentShelf.NETFLIX ->
                    app?.consumeNetflixMoviesPrefetch(profileId)
                mediaType == MediaType.TV_SHOWS && shelf == ContentShelf.NETFLIX ->
                    app?.consumeNetflixTvShowsPrefetch(profileId)
                else -> null
            }

            if (prefetched != null && prefetched.items.isNotEmpty()) {
                pageWindow.loadedPages[0]   = prefetched.items
                pageWindow.windowStartPage  = 0
                pageWindow.windowEndPage    = 0
                items = pageWindow.getAllItems()
                if (BuildConfig.DEBUG) Log.d("PAGING", "⚡ $shelf used ${prefetched.items.size} prefetched items")
            } else {
                if (BuildConfig.DEBUG) Log.d("PAGING", "INIT $shelf initial load")
                loadPage(0)
            }
        } else {
            items = pageWindow.getAllItems()
            if (BuildConfig.DEBUG) Log.d("PAGING", "RESTORE $shelf (${items.size} items)")
        }
    }

    fun handleFocusChange(listIndex: Int, tmdbId: Int) {
        if (!isFocused) return

        pageWindow.focusedTmdbId = tmdbId

        val firstItemInWindow = pageWindow.windowStartPage * pageSize
        val absoluteIndex     = firstItemInWindow + listIndex
        val pageNumber        = absoluteIndex / pageSize
        val indexInPage       = absoluteIndex % pageSize

        // Always record.  There's only one focus move per navigation, so the
        // value written here is always the card focus actually landed on.
        cardTracker.recordCard(shelf, tmdbId, listIndex)

        // PREFETCH forward — conveyor belt.
        // Trigger on proximity to the END of the flattened, deduped list, NOT on
        // page-relative index math (short blend pages / dedup desync the old
        // indexInPage trigger). On a full 20-card page this fires at listIndex 15.
        //
        // CRITICAL: do NOT cancel an in-flight load for the same page. The old
        // code re-fired on every card move and called prefetchJob?.cancel(), which
        // — with the slower personalized query (~480ms vs ~350ms/card) — kept
        // killing the load before it finished, so it only completed when the user
        // PAUSED at the last card. That was the brick wall. prefetchingPage lets a
        // started load run to completion; further moves toward the same page are
        // no-ops until it lands.
        val prefetchAhead = pageSize - prefetchTrigger   // = 5 cards from the end
        if (!endReached && items.isNotEmpty() && listIndex >= items.size - prefetchAhead) {
            val nextPage = pageWindow.windowEndPage + 1
            if (!pageWindow.loadedPages.containsKey(nextPage) && prefetchingPage != nextPage) {
                prefetchingPage = nextPage
                prefetchJob = coroutineScope.launch {
                    loadPage(nextPage)
                    // Empty result never gets added → source exhausted.
                    if (!pageWindow.loadedPages.containsKey(nextPage)) {
                        endReached = true
                    }
                    prefetchingPage = null
                }
            }
        }

        // EVICT the trailing page once focus is inside the newest one.
        //
        // THIS TEST COULD NEVER BE TRUE. It read `indexInPage >= evictTrigger`
        // with evictTrigger 20 and pageSize 20, and indexInPage is
        // `absoluteIndex % pageSize`, so it runs 0..19 and never reaches 20. The
        // branch was dead, maxPages was never enforced while browsing forward,
        // and every page scrolled past stayed resident for the life of the
        // screen — which also made every recomposition of the row more expensive,
        // since the flattened list only ever grew.
        //
        // Rewritten against the flattened list for exactly the reason the
        // prefetch above was moved off the same arithmetic: short blend pages and
        // dedup desync page-relative index maths. That is how a trigger equal to
        // the page size survived unnoticed.
        if (items.size > pageSize && listIndex >= items.size - pageSize) {
            val pageCount = pageWindow.getPageCount()
            if (pageCount > maxPages && pageWindow.windowStartPage < pageNumber) {
                evictPage(pageWindow.windowStartPage)
            }
        }

        // RELOAD backward when scrolling toward the start
        if (pageNumber == pageWindow.windowStartPage && indexInPage <= reloadTrigger) {
            val now = System.currentTimeMillis()
            if (now - lastReloadTime >= reloadCooldownMs) {
                val prevPage = pageWindow.windowStartPage - 1
                if (prevPage >= 0 && !pageWindow.loadedPages.containsKey(prevPage)) {
                    lastReloadTime = now
                    reloadJob?.cancel()
                    reloadJob = coroutineScope.launch {
                        loadPage(prevPage)
                        val pageCount = pageWindow.getPageCount()
                        if (pageCount > maxPages && pageWindow.windowEndPage > pageNumber) {
                            evictPage(pageWindow.windowEndPage)
                        }
                    }
                }
            }
        }
    }

    // Remembered so the row sees a stable parameter and can still skip. Reading
    // cardTracker here instead would put the snapshot read back into composition,
    // which is the whole thing being removed.
    val pendingFocusProvider = remember(shelf) { { cardTracker.cardTmdbId(shelf) } }

    val title = repository.getShelfTitle(mediaType, shelf)

    // POPULAR ALWAYS renders inside this Row with the 610.dp viewport — the
    // branch is gated on the shelf ALONE, not on showContinueWatching.
    //
    // Why this matters (this is the search-bar bug):
    //   Previously the condition was `shelf == POPULAR && showContinueWatching`.
    //   Navigating DOWN from POPULAR fires onFirstShelfChanged(false), which
    //   flips showContinueWatching to false MID-TRANSITION.  That flipped this
    //   branch from "Row { TvWideLazyRow }" to a bare "TvWideLazyRow" — a
    //   STRUCTURAL change, so Compose tore down and rebuilt POPULAR's row.
    //   The rebuild churned composition for several frames, delaying
    //   TOP_RATED's card-focus claim past the parking node's release, so focus
    //   stranded and Compose handed it to the search bar.  POPULAR is index 0,
    //   so it is only ever VISIBLE while focused (otherwise a hidden peek-above),
    //   and it is focused exactly when continue-watching shows — so 610.dp is
    //   always correct when it matters, and the structure now never changes.
    // Narrow viewport for the FIRST shelf of any tab — it shares the screen
    // with the Continue Watching panel on the right, so it must stop short of
    // it. POPULAR is the first shelf for Movies/TV; ANIME_NEW_SEASON is the
    // first shelf for the Anime tab. Both need the 610.dp viewport. Gating on
    // shelf identity (not showContinueWatching) keeps the structure stable so
    // the search-bar focus bug stays fixed.
    if (shelf == ContentShelf.POPULAR || shelf == ContentShelf.ANIME_NEW_SEASON) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            TvWideLazyRow(
                title              = title,
                items              = items,
                onItemClick        = onMediaClick,
                onFocusChanged     = ::handleFocusChange,
                rowFocusRequester  = rowFocusRequester,
                isPageLoading      = loading,
                pendingFocusTmdbId = pendingFocusProvider,
                cardFocusEpoch     = cardFocusEpoch,
                isFocusedShelf     = isFocused,
                onFocusRestored    = {
                    if (BuildConfig.DEBUG) Log.d("FOCUS_MEMORY", "restored $shelf")
                },
                showTitle             = false,
                // FULL WIDTH WHEN THERE IS NO CONTINUE WATCHING PANEL. Only these
                // three sizes change -- the Row and the row inside it stay exactly
                // as they are, so the structure the search-bar fix above depends on
                // never changes. And the flag behind them moves only when the
                // history does, never mid-transition between shelves.
                viewportWidth         = if (reserveContinueWatching) 610.dp else TV_WIDE_ROW_VIEWPORT_WIDTH,
                rightLowerBodyOffsetX = if (reserveContinueWatching) 580.dp else TV_WIDE_ROW_RIGHT_BODY_X,
                rightUpperBodyOffsetX = if (reserveContinueWatching) 580.dp else TV_WIDE_ROW_RIGHT_BODY_X,
                onNavigateToLeftModule = onExitLeft,
            )
        }
    } else {
        TvWideLazyRow(
            title              = title,
            items              = items,
            onItemClick        = onMediaClick,
            onFocusChanged     = ::handleFocusChange,
            rowFocusRequester  = rowFocusRequester,
            isPageLoading      = loading,
            pendingFocusTmdbId = pendingFocusProvider,
            cardFocusEpoch     = cardFocusEpoch,
            isFocusedShelf     = isFocused,
            onFocusRestored    = {
                if (BuildConfig.DEBUG) Log.d("FOCUS_MEMORY", "restored $shelf")
            },
            showTitle = false,
            onNavigateToLeftModule = onExitLeft,
        )
    }
}