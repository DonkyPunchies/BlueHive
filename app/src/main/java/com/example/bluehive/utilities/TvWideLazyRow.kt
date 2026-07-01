package com.example.bluehive.utilities

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.R
import com.example.bluehive.models.MediaItem
import kotlinx.coroutines.launch
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.graphicsLayer


// NEVER DELETE ANY OF MY COMMENTS!!!!
// You are not actually moving between two separate rows in the focus system. You’re swapping the same TvWideLazyRow() instance between POPULAR and TOP_RATED

@Composable
fun TvWideLazyRow(

    // what items controls inside TvWideLazyRow: How many cards exist,
    // Each MediaItem in that list is expected to contain the data you need to render a card, like:
    // posterPath / image URL (or resource id)
    // title
    // tmdbId (your stable key/identity)
    title: String,
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,   // --> Unit = the function doesn’t return anything back (it just does something)
    modifier: Modifier = Modifier,

    // Hide the title text when this row is not the focused shelf — keeps
    // peeking rows from cluttering the screen with their headers.
    showTitle: Boolean = true,

    // === paging hooks ===
    hasPreviousPage: Boolean = false,
    hasNextPage: Boolean = false,
    isPageLoading: Boolean = false,
    onRequestPreviousPage: () -> Unit = {},
    onRequestNextPage: () -> Unit = {},
    onFocusChanged: (absoluteIndex: Int, tmdbId: Int) -> Unit = { _, _ -> },
    pendingFocusTmdbId: Int = -1,
    // Bumped by ShelfStack (via the unified tracker) when THIS shelf becomes
    // the focused shelf and should move card focus to its saved card.
    cardFocusEpoch: Int = 0,
    // True only when this row is the focused shelf.  Guards the epoch effect
    // below so a peek row re-entering the window can never grab card focus
    // with a stale epoch — this is what stops the card-vs-row drift.
    isFocusedShelf: Boolean = false,
    onFocusRestored: () -> Unit = {},
    onNavigateToRightModule: (() -> Unit)? = null,
    onNavigateUp: (() -> Unit)? = null,
    onNavigateDown: (() -> Unit)? = null,

    rowFocusRequester: FocusRequester? = null,
    firstItemFocusRequester: FocusRequester? = null,

    // === dimensions ===
    sectionHeight: Dp = 250.dp,
    titleOffsetX: Dp = (-29).dp,
    titleOffsetY: Dp = (-48).dp,
    viewportWidth: Dp = 850.dp,
    viewportHeight: Dp = 218.dp,
    viewportOffsetX: Dp = (-20).dp,
    viewportOffsetY: Dp = (-40).dp,
    itemSpacing: Dp = 2.dp,
    contentPaddingStart: Dp = 18.dp,
    contentPaddingEnd: Dp = 15.dp,
    leftLowerBodyOffsetX: Dp = (-7.9).dp,
    leftLowerBodyOffsetY: Dp = (-32.5).dp,
    rightLowerBodyOffsetX: Dp = (816).dp,
    rightLowerBodyOffsetY: Dp = (-32.5).dp,
    leftUpperBodyOffsetX: Dp = (-31).dp,
    leftUpperBodyOffsetY: Dp = (-46).dp,
    rightUpperBodyOffsetX: Dp = 816.dp,
    rightUpperBodyOffsetY: Dp = (-46).dp,

    navigationCooldownMs: Long = 120L,
) {
    val backgroundPainter = painterResource(id = R.drawable.titlecard_base)
    val focusedPainter = painterResource(id = R.drawable.titlecard_focused)
    val shadowPainter = painterResource(id = R.drawable.titlecard_shadow)

    val baseCardModifier = remember {
        Modifier.focusable()
            .wrapContentSize(unbounded = true)
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var focusedIndex by rememberSaveable { mutableIntStateOf(-1) }
    var hasRowFocus by remember { mutableStateOf(false) }
    var lastNavigationTime by remember { mutableLongStateOf(0L) }
    val activity = LocalActivity.current as? ComponentActivity


    val focusRequesters = remember(items.size, firstItemFocusRequester) {
        List(items.size) { idx ->
            if (idx == 0 && firstItemFocusRequester != null) firstItemFocusRequester
            else FocusRequester()
        }
    }



    var savedFirstIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedFirstOffset by rememberSaveable { mutableIntStateOf(0) }
    var hasSavedScroll by rememberSaveable { mutableStateOf(false) }

    var hadRowFocus by remember { mutableStateOf(false) }
    var pendingReentryFix by remember { mutableStateOf(false) }



    fun focusAndScrollTo(index: Int) {
        if (index !in items.indices) return

        // The bounds guard above confirms index is in range, but the
        // FocusRequester's node may not be attached yet if the card just
        // entered the visible viewport.  runCatching swallows the
        // IllegalStateException instead of crashing the composition.
        runCatching { focusRequesters[index].requestFocus() }

        coroutineScope.launch {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return@launch

            val first = visible.first().index
            val last = visible.last().index

            val shouldNudge = index <= first + 1 || index >= last - 1
            if (shouldNudge) {
                val target = maxOf(index - 2, 0)
                listState.scrollToItem(target)
            }
        }
    }

    Column(modifier = modifier.height(sectionHeight))
    {
        if (showTitle) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontFamily = AppTypography.passionRegular,
                modifier = Modifier.offset(x = titleOffsetX, y = titleOffsetY)
            )
        }

        Box {
            Image(
                painter = painterResource(id = R.drawable.left_lower_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(2.25.dp)
                    .height(167.dp)
                    .offset(x = leftLowerBodyOffsetX, y = leftLowerBodyOffsetY),
                contentScale = ContentScale.Fit
            )

            Image(
                painter = painterResource(id = R.drawable.right_lower_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(2.25.dp)
                    .height(167.dp)
                    .offset(x = rightLowerBodyOffsetX, y = rightLowerBodyOffsetY),
                contentScale = ContentScale.Fit
            )

            Box(
                modifier = Modifier
                    .width(viewportWidth)
                    .height(viewportHeight)
                    .offset(x = viewportOffsetX, y = viewportOffsetY)
            ) {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                    contentPadding = PaddingValues(start = contentPaddingStart, end = contentPaddingEnd),
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (rowFocusRequester != null) Modifier.focusRequester(rowFocusRequester)
                            else Modifier
                        )
                        .focusGroup()

                        .onFocusChanged { focusState ->
                            hasRowFocus = focusState.hasFocus  // A4) TvWideLazyRow LazyRow.onFocusChanged fires after Focus enters row area (into LazyRow/children)

                            // leaving the row (true -> false)
                            if (hadRowFocus && !focusState.hasFocus) {
                                savedFirstIndex = listState.firstVisibleItemIndex
                                savedFirstOffset = listState.firstVisibleItemScrollOffset
                                hasSavedScroll = true
                            }

                            // entering the row (false -> true)
                            if (!hadRowFocus && focusState.hasFocus) {
                                pendingReentryFix = true
                            }

                            hadRowFocus = focusState.hasFocus
                        },
                    userScrollEnabled = false,
                    reverseLayout = false
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> item.tmdbId }
                    ) { index, mediaItem ->

                        val releaseDateText = remember(mediaItem.releaseDate) {
                            mediaItem.releaseDate
                                ?.takeIf { it.isNotBlank() && it != "N/A" }
                                ?.let { raw ->
                                    val parts = raw.split("-")
                                    if (parts.size == 3) "${parts[1]}/${parts[2]}/${parts[0]}" else raw
                                }
                        }

                        val runTimeText = remember(mediaItem.runtime) {
                            val minutes = mediaItem.runtime ?: return@remember null
                            if (minutes <= 0) null else {
                                val h = minutes / 60
                                val m = minutes % 60
                                when {
                                    h > 0 && m > 0 -> "${h}h ${m}m"
                                    h > 0 -> "${h}h"
                                    m > 0 -> "${m}m"
                                    else -> null
                                }
                            }
                        }

                        // AFTER
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(112.dp)
                        ) {
                            val isItemFocused by remember(index) {
                                derivedStateOf { hasRowFocus && focusedIndex == index }
                            }

                            var isPressed by remember { mutableStateOf(false) }
                            var titleVisible by remember { mutableStateOf(false) }
                            val titleAlpha by animateFloatAsState(
                                targetValue = if (titleVisible) 1f else 0f,
                                animationSpec = androidx.compose.animation.core.tween(durationMillis = 700),
                                label = "titleAlpha"
                            )

                            LaunchedEffect(isItemFocused) {
                                if (isItemFocused) {
                                    titleVisible = false
                                    kotlinx.coroutines.delay(800L)
                                    titleVisible = true
                                } else {
                                    titleVisible = false
                                }
                            }


                            val cardScale by animateFloatAsState(
                                targetValue   = if (isItemFocused && isPressed) 0.80f else 1f,
                                animationSpec = androidx.compose.animation.core.tween(250),
                                label         = "cardScale",
                            )

                            MediaCard(
                                mediaItem = mediaItem,
                                backgroundPainter = backgroundPainter,
                                focusedPainter = focusedPainter,
                                shadowPainter = shadowPainter,
                                isFocused = isItemFocused,
                                releaseDateText = releaseDateText,
                                runTimeText = runTimeText,
                                rating = mediaItem.voteAverage?.takeIf { it > 0.0 },
                                modifier = baseCardModifier
                                    .graphicsLayer {
                                        scaleX = cardScale
                                        scaleY = cardScale
                                    }
                                    .focusRequester(focusRequesters[index])
                                    .onFocusChanged { fs ->
                                        if (fs.isFocused) {
                                            focusedIndex = index
                                            onFocusChanged(index, mediaItem.tmdbId)
                                        }
                                    }
                                    .onKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyUp) {
                                            if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                                isPressed = false
                                            }
                                            return@onKeyEvent false
                                        }
                                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false

                                        when (event.key) {
                                            Key.DirectionLeft -> {
                                                // ✅ CHECK COOLDOWN FIRST
                                                val currentTime = System.currentTimeMillis()
                                                if (currentTime - lastNavigationTime < navigationCooldownMs) {
                                                    Log.d("NAV_THROTTLE", "⏸️ LEFT blocked (${navigationCooldownMs - (currentTime - lastNavigationTime)}ms remaining)")
                                                    return@onKeyEvent true  // Consume but ignore
                                                }
                                                lastNavigationTime = currentTime

                                                if (focusedIndex > 0) {
                                                    focusAndScrollTo(focusedIndex - 1)
                                                    BlueHiveApplication.playTitleCardNavigation()
                                                } else if (hasPreviousPage && !isPageLoading) {
                                                    onRequestPreviousPage()
                                                    BlueHiveApplication.playTitleCardNavigation()
                                                }
                                                true
                                            }

                                            Key.DirectionRight -> {
                                                // ✅ CHECK COOLDOWN FIRST
                                                val currentTime = System.currentTimeMillis()
                                                if (currentTime - lastNavigationTime < navigationCooldownMs) {
                                                    Log.d("NAV_THROTTLE", "⏸️ RIGHT blocked (${navigationCooldownMs - (currentTime - lastNavigationTime)}ms remaining)")
                                                    return@onKeyEvent true
                                                }
                                                lastNavigationTime = currentTime

                                                if (focusedIndex < items.lastIndex) {
                                                    focusAndScrollTo(focusedIndex + 1)
                                                    BlueHiveApplication.playTitleCardNavigation()
                                                } else {
                                                    // ✅ NEW: Check if we should navigate to right module
                                                    if (onNavigateToRightModule != null) {
                                                        BlueHiveApplication.playTitleCardNavigation()
                                                        onNavigateToRightModule.invoke()
                                                    } else if (hasNextPage && !isPageLoading) {
                                                        onRequestNextPage()
                                                        BlueHiveApplication.playTitleCardNavigation()
                                                    }
                                                }
                                                true
                                            }

                                            Key.DirectionUp -> {
                                                if (onNavigateUp != null) {
                                                    BlueHiveApplication.playHoverSound()
                                                    onNavigateUp.invoke()
                                                    true
                                                } else false
                                            }

                                            Key.DirectionDown -> {
                                                if (onNavigateDown != null) {
                                                    BlueHiveApplication.playHoverSound()
                                                    onNavigateDown.invoke()
                                                    true
                                                } else false
                                            }

                                            Key.Back -> {
                                                activity?.onBackPressedDispatcher?.onBackPressed()
                                                true
                                            }

                                            Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                                                isPressed = true
                                                BlueHiveApplication.playClickSound()
                                                onItemClick(mediaItem)
                                                true
                                            }

                                            else -> false
                                        }
                                    }
                            )


                            var fontSize by remember(mediaItem.title) { mutableStateOf(16.sp) }

                            if (isItemFocused && !isPressed) {
                                Box(
                                    modifier = Modifier
                                        .width(145.dp)
                                        .height(50.dp)
                                        .offset(y = (-2).dp)
                                        .graphicsLayer { alpha = titleAlpha },
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    Text(
                                        text = mediaItem.title,
                                        color = Color.White,                                         fontSize = fontSize,
                                        fontFamily = AppTypography.passionRegular,
                                        maxLines = 2,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                        onTextLayout = { layoutResult ->
                                            if (layoutResult.lineCount > 1 && fontSize != 13.sp) {
                                                fontSize = 13.sp
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Image(
                painter = painterResource(id = R.drawable.left_upper_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(25.4.dp)
                    .height(193.dp)
                    .offset(x = leftUpperBodyOffsetX, y = leftUpperBodyOffsetY),
                contentScale = ContentScale.Fit
            )

            Image(
                painter = painterResource(id = R.drawable.right_upper_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(25.4.dp)
                    .height(193.dp)
                    .offset(x = rightUpperBodyOffsetX, y = rightUpperBodyOffsetY),
                contentScale = ContentScale.Fit
            )
        }
    }



    LaunchedEffect(pendingFocusTmdbId, items.size) {
        // If this row is not the focused shelf, do nothing.  This guard is
        // essential for the POPULAR row specifically: when navigating DOWN
        // from POPULAR, onFirstShelfChanged(false) flips showContinueWatching
        // and rebuilds this TvWideLazyRow.  The fresh instance sees a non-zero
        // pendingFocusTmdbId and fires immediately — racing the handoff's
        // parking node and corrupting focus.  isFocusedShelf=false at that
        // moment, so the guard stops it cleanly.  The cardFocusEpoch mechanism
        // handles all re-entry focus; this effect only needs to run for
        // in-session pagination restoration while the row is already focused.
        if (!isFocusedShelf) return@LaunchedEffect

        if (pendingFocusTmdbId > 0 && items.isNotEmpty()) {
            val targetIndex = items.indexOfFirst { it.tmdbId == pendingFocusTmdbId }
            if (targetIndex >= 0) {
                pendingReentryFix = false
                Log.d("PAGING", "🎯 Restoring focus to tmdbId $pendingFocusTmdbId at new index $targetIndex")

                kotlinx.coroutines.android.awaitFrame()
                kotlinx.coroutines.android.awaitFrame()

                focusedIndex = targetIndex
                focusRequesters.getOrNull(targetIndex)?.requestFocus()

                onFocusRestored()
            }
        }
    }


    // ── Card focus, driven explicitly by ShelfStack ─────────────────────────
    // Fires when this shelf's epoch ticks (ShelfStack bumps it after sliding
    // the row into the focused position).  The isFocusedShelf guard is the
    // critical line: when a peek row re-enters the ±1 window its LaunchedEffect
    // is recreated and re-runs with a STALE epoch — but isFocusedShelf is
    // false for it, so it does nothing.  Only the genuinely-focused shelf
    // ever moves card focus.  Card focus therefore always lands in the row
    // that focusedShelfIndex points to — no drift, no lag, no overshoot.
    LaunchedEffect(cardFocusEpoch) {
        if (cardFocusEpoch == 0) return@LaunchedEffect      // never triggered yet
        if (!isFocusedShelf) return@LaunchedEffect          // stale re-entry guard
        if (items.isEmpty()) return@LaunchedEffect

        // Which card: the saved one (by tmdbId, survives pagination), else
        // the current focusedIndex, else card 0.
        val target = run {
            if (pendingFocusTmdbId > 0) {
                val byTmdb = items.indexOfFirst { it.tmdbId == pendingFocusTmdbId }
                if (byTmdb >= 0) return@run byTmdb
            }
            focusedIndex.coerceAtLeast(0).coerceAtMost(items.lastIndex)
        }

        focusedIndex = target

        // Make sure the target is composed/visible before focusing it —
        // LazyRow only attaches FocusRequesters for visible items.
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == target }) {
            listState.scrollToItem((target - 2).coerceAtLeast(0), 0)
        }

        // Retry across a few frames: on the first frame after a row slides in,
        // the FocusRequester may not be attached yet.
        repeat(6) {
            kotlinx.coroutines.android.awaitFrame()
            val req = focusRequesters.getOrNull(target) ?: return@LaunchedEffect
            try {
                req.requestFocus()
                onFocusRestored()
                return@LaunchedEffect
            } catch (_: Exception) { /* try again next frame */ }
        }
    }





    // ✅ IMPORTANT: do NOT steal focus when items arrive.
    // This block = default landing spot (index 0) only if nothing else is set yet.
    // Your restore logic = return to last place (tmdbId/index) when you have memory.


    // Only initialize focus if the row ALREADY has focus, items not empty, and focusedIndex == -1 (no item chosen yet).
    LaunchedEffect(items.size, hasRowFocus) {    // 4A) Runs after hasRowFocus becomes TRUE
        if (hasRowFocus && items.isNotEmpty() && focusedIndex == -1) {
            focusedIndex = 0
            // Card 0 is in bounds (items.isNotEmpty() guard above), but its
            // FocusRequester may not be attached yet on the first frame after
            // items arrive.  runCatching prevents an IllegalStateException crash.
            runCatching { focusRequesters[0].requestFocus() }
        }
    }
//                    V
//                  After
//                    V
//    ┌──────────────────────────────┐
//    │   MediaCard index 0 focused   │  ✅ smooth landing
//    └──────────────────────────────┘



    LaunchedEffect(pendingReentryFix, hasRowFocus, items.size) {
        if (!pendingReentryFix) return@LaunchedEffect
        if (!hasRowFocus) return@LaunchedEffect
        if (items.isEmpty()) return@LaunchedEffect

        // PREFER the externally-supplied tmdbId.  focusedIndex may already
        // have been overwritten by Compose's focusGroup landing on the
        // first visible card right before this LaunchedEffect ran.  The
        // tracker's tmdbId is the trustworthy source — the ShelfStack
        // restoration lock guarantees it wasn't corrupted during handoff.
        val target = run {
            if (pendingFocusTmdbId > 0) {
                val byTmdbId = items.indexOfFirst { it.tmdbId == pendingFocusTmdbId }
                if (byTmdbId >= 0) return@run byTmdbId
            }
            focusedIndex.coerceIn(0, items.lastIndex)
        }

        // Make sure focusedIndex agrees with where we're actually putting
        // focus, so subsequent restoration passes (and onFocusChanged
        // updates) don't argue with us.
        focusedIndex = target

        // IMMEDIATE CLAIM: focus a specific card right now, before any frame
        // awaits.  This closes the window where the LazyRow focusGroup has
        // hasFocus=true but no leaf card owns it — which is the exact
        // condition that causes Compose to fall back to the search bar.
        // Scroll restoration below may re-focus after layout settles, but
        // the gap is closed from this point forward.
        focusRequesters.getOrNull(target)?.requestFocus()

        // 1) Restore EXACT last view first (this avoids "snap to offset 0" jerk)
        if (hasSavedScroll) {
            listState.scrollToItem(savedFirstIndex, savedFirstOffset)
        }

        // Let LazyRow measure at that restored position
        withFrameNanos { }
        withFrameNanos { }

        // 2) If target is already comfortably visible, don't scroll at all
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty()) {
            val first = visible.first().index
            val last = visible.last().index

            val safeFirst = first + 1
            val safeLast = last - 1

            if (target < safeFirst || target > safeLast) {
                // one correction scroll ONLY if needed
                val anchor = (target - 2).coerceAtLeast(0)
                listState.scrollToItem(anchor, 0)

                withFrameNanos { }
                withFrameNanos { }
            }
        }

        pendingReentryFix = false
        // Use getOrNull rather than a direct subscript.  target was clamped
        // to items.lastIndex earlier in this effect, but two withFrameNanos
        // suspensions above give page eviction time to shrink both items and
        // focusRequesters.  If that happens target is now out of range on the
        // new list, which would throw IndexOutOfBoundsException.  getOrNull
        // returns null instead, and the safe-call operator skips the
        // requestFocus rather than crashing.  runCatching additionally covers
        // the IllegalStateException if the node is not currently attached.
        runCatching { focusRequesters.getOrNull(target)?.requestFocus() }
    }
}