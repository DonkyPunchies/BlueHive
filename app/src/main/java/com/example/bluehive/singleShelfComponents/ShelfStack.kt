package com.example.bluehive.singleShelfComponents

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import kotlinx.coroutines.Dispatchers
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

// Shelf header (title) animation.
// REAPPEAR_DELAY: gap after a shelf change before the title fades back in.
// FADE_IN_DURATION: length of the alpha 0->1 fade once it starts.
// Snap-out is always instant.  Spamming UP/DOWN keeps the title hidden.
private const val HEADER_REAPPEAR_DELAY_MS   = 1_500L
private const val HEADER_FADE_IN_DURATION_MS = 2_000


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
    onFirstPageReady: () -> Unit = {},
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
    var lastShelfNavTime by remember { mutableLongStateOf(0L) }
    val shelfNavCooldownMs = 350L
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
    val animatedTranslationY by animateFloatAsState(
        targetValue = -focusedShelfIndex * rowSpacingPx,
        animationSpec = tween(durationMillis = 450),
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
                if (distance > 1) return@forEachIndexed   // outside the window

                val isFocused = (index == focusedShelfIndex)
                val targetAlpha = when {
                    isFocused -> 1f
                    index > focusedShelfIndex -> PEEK_ALPHA_BELOW
                    else -> PEEK_ALPHA_ABOVE
                }
                val animatedAlpha by animateFloatAsState(
                    targetValue = targetAlpha,
                    animationSpec = tween(durationMillis = 350),
                    label = "shelfAlpha_$index",
                )

                stateHolder.SaveableStateProvider(shelf.name) {
                    Box(
                        modifier = Modifier.graphicsLayer {
                            translationY = index * rowSpacingPx
                            alpha = animatedAlpha
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
                            onMediaClick         = onMediaClick,
                            profileId            = profileId,
                            onFirstPageReady     = if (shelf == ContentShelf.POPULAR) onFirstPageReady else ({ }),
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
) {
    // Pagination knobs — unchanged from the old HomeSingleShelf.
    val pageSize        = 20
    val maxPages        = 2
    val prefetchTrigger = 12
    val evictTrigger    = 20
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
        loadingJob = coroutineScope.launch(Dispatchers.IO) {
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

        // EVICT trailing page once we're deep enough into the head page
        if (pageNumber == pageWindow.windowEndPage && indexInPage >= evictTrigger) {
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
                pendingFocusTmdbId = cardTracker.cardTmdbId(shelf),
                cardFocusEpoch     = cardFocusEpoch,
                isFocusedShelf     = isFocused,
                onFocusRestored    = {
                    if (BuildConfig.DEBUG) Log.d("FOCUS_MEMORY", "restored $shelf")
                },
                showTitle             = false,
                viewportWidth         = 610.dp,
                rightLowerBodyOffsetX = 580.dp,
                rightUpperBodyOffsetX = 580.dp,
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
            pendingFocusTmdbId = cardTracker.cardTmdbId(shelf),
            cardFocusEpoch     = cardFocusEpoch,
            isFocusedShelf     = isFocused,
            onFocusRestored    = {
                if (BuildConfig.DEBUG) Log.d("FOCUS_MEMORY", "restored $shelf")
            },
            showTitle = false,
        )
    }
}