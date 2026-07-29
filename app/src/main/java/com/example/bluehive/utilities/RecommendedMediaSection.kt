// utilities/RecommendedMediaSection.kt
package com.example.bluehive.utilities

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.R
import com.example.bluehive.models.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ⬅️➡️ Hold-to-repeat navigation speed for the Recommended Media row: the minimum time
// (ms) between focus moves while LEFT/RIGHT is HELD DOWN. HIGHER = SLOWER.
private const val RECOMMENDED_NAV_REPEAT_MS = 105L

// ⏱️ Title reveal timing — matched to the front-page TvWideLazyRow shelves. A card must hold
// focus for TITLE_REVEAL_DELAY_MS, then its title fades in over TITLE_FADE_MS. Fast swiping
// keeps the title at alpha 0 (no flicker); settling triggers the same relaxed fade as home.
private const val TITLE_REVEAL_DELAY_MS = 800L
private const val TITLE_FADE_MS = 700

@Composable
fun RecommendedMediaSection(
    recommendations: List<MediaItem>,
    onMediaClick: (MediaItem) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundPainter = painterResource(id = R.drawable.titlecard_base)
    val focusedPainter = painterResource(id = R.drawable.titlecard_focused)
    val shadowPainter = painterResource(id = R.drawable.titlecard_shadow)

    val baseCardModifier = remember {
        Modifier
            .wrapContentSize(unbounded = true)
            .focusable()
    }

    val listState = rememberLazyListState()
    // Hold-to-repeat throttle timestamp for the row (see RECOMMENDED_NAV_REPEAT_MS).
    // Plain 1-slot array (NOT observable state) so ticking it never triggers recomposition.
    val recNavGate = remember { longArrayOf(0L) }
    val coroutineScope = rememberCoroutineScope()
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var hasRowFocus by remember { mutableStateOf(false) }

    val activity = LocalActivity.current as? ComponentActivity

    val focusRequesters = remember(recommendations.size) {
        List(recommendations.size) { FocusRequester() }
    }

    Column(
        modifier = modifier
            .offset(x = 60.dp, y = 340.25.dp)
            .height(250.dp)
    ) {
        Text(
            text = "Recommended Media",
            color = Color.White,
            fontSize = 24.sp,
            fontFamily = AppTypography.passionRegular,
            modifier = Modifier.offset(x = (-29).dp, y = (-48).dp)
        )

        Box {
            Image(
                painter = painterResource(id = R.drawable.left_lower_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(2.25.dp)
                    .height(167.dp)
                    .offset(x = (-7.9).dp, y = (-32.5).dp),
                contentScale = ContentScale.Fit
            )

            Image(
                painter = painterResource(id = R.drawable.right_lower_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(2.25.dp)
                    .height(167.dp)
                    .offset(x = (839.5).dp, y = (-32.5).dp),
                contentScale = ContentScale.Fit
            )

            Box(
                modifier = Modifier
                    .width(870.dp)
                    .height(208.dp)
                    .offset(y = (-40).dp)
                    .offset(x = (-20).dp)
            ) {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(start = 18.dp, end = 15.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .onFocusChanged { focusState ->
                            hasRowFocus = focusState.hasFocus

                            if (focusState.hasFocus) {
                                if (recommendations.isNotEmpty()) {
                                    if (focusedIndex !in recommendations.indices) {
                                        focusedIndex = 0
                                    }

                                    val targetIndex = focusedIndex.coerceIn(
                                        0,
                                        focusRequesters.lastIndex
                                    )

                                    // Only restore focus – scrolling handled in LaunchedEffect
                                    focusRequesters[targetIndex].requestFocus()
                                }
                            } else {
                                Log.d(
                                    "RecommendedMediaSection",
                                    "LazyRow lost focus (keeping focusedIndex=$focusedIndex)"
                                )
                            }
                        },
                    // 👇 Let focus logic drive scroll; user scroll via D-pad is handled by us
                    userScrollEnabled = false,
                    reverseLayout = false
                ) {
                    itemsIndexed(
                        items = recommendations,
                        key = { _, item -> item.tmdbId }
                    ) { index, mediaItem ->
                        // Per-item focus state (LOCAL) so a nav step recomposes only the two
                        // cards whose focus actually changed — not every visible card. Reading
                        // the shared focusedIndex here made ALL cards (each an AsyncImage)
                        // recompose on every keypress, which is the navigation lag.
                        var isItemFocused by remember { mutableStateOf(false) }

                        // Title reveal debounce + fade, matched to the front-page TvWideLazyRow
                        // shelves: the title Box is always composed while focused but sits at
                        // alpha 0 until the card has HELD focus for TITLE_REVEAL_DELAY_MS, then
                        // fades in over TITLE_FADE_MS. Fast swiping never lets titleVisible reach
                        // true, so alpha stays 0 — no flicker; settling triggers the relaxed fade.
                        var titleVisible by remember { mutableStateOf(false) }
                        val titleAlpha by animateFloatAsState(
                            targetValue = if (titleVisible) 1f else 0f,
                            animationSpec = tween(durationMillis = TITLE_FADE_MS),
                            label = "recTitleAlpha"
                        )
                        LaunchedEffect(isItemFocused) {
                            if (isItemFocused) {
                                titleVisible = false
                                delay(TITLE_REVEAL_DELAY_MS)
                                titleVisible = true
                            } else {
                                titleVisible = false
                            }
                        }

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

                        val rating = remember(mediaItem.voteAverage) {
                            mediaItem.voteAverage?.takeIf { it > 0.0 }
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(112.dp)
                        ) {
                            MediaCard(
                                mediaItem = mediaItem,
                                backgroundPainter = backgroundPainter,
                                focusedPainter = focusedPainter,
                                shadowPainter = shadowPainter,
                                isFocused = isItemFocused,
                                releaseDateText = releaseDateText,
                                runTimeText = runTimeText,
                                rating = rating,
                                modifier = baseCardModifier
                                    .focusRequester(focusRequesters[index])
                                    .onFocusChanged { focusState ->
                                        // Drive the LOCAL focus flag (glow/size/title) from this
                                        // card's own focus — this is what was missing and made the
                                        // glow layer disappear (isItemFocused never went true).
                                        isItemFocused = focusState.isFocused
                                        if (focusState.isFocused) {
                                            // 🔹 Only update state, no scroll here
                                            focusedIndex = index
                                        }
                                    }
                                    .onKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown) {
                                            when (event.key) {
                                                Key.DirectionLeft -> {
                                                    // Throttle hold-to-repeat (see RECOMMENDED_NAV_REPEAT_MS); the
                                                    // boundary guard keeps the sound off at the first card.
                                                    val now = SystemClock.uptimeMillis()
                                                    if (focusedIndex > 0 && now - recNavGate[0] >= RECOMMENDED_NAV_REPEAT_MS) {
                                                        recNavGate[0] = now
                                                        focusRequesters[focusedIndex - 1].requestFocus()
                                                        BlueHiveApplication.playTitleCardNavigation()
                                                    }
                                                    true
                                                }

                                                Key.DirectionRight -> {
                                                    val now = SystemClock.uptimeMillis()
                                                    if (focusedIndex < recommendations.size - 1 && now - recNavGate[0] >= RECOMMENDED_NAV_REPEAT_MS) {
                                                        recNavGate[0] = now
                                                        focusRequesters[focusedIndex + 1].requestFocus()
                                                        BlueHiveApplication.playTitleCardNavigation()
                                                    }
                                                    true
                                                }

                                                Key.DirectionUp -> {
                                                    onNavigateUp()
                                                    true
                                                }

                                                Key.DirectionDown -> true

                                                Key.Back -> {
                                                    activity?.onBackPressedDispatcher?.onBackPressed()
                                                    true
                                                }

                                                Key.Enter,
                                                Key.NumPadEnter,
                                                Key.DirectionCenter -> {
                                                    BlueHiveApplication.playClickSound()
                                                    onMediaClick(mediaItem)
                                                    true
                                                }

                                                else -> false
                                            }
                                        } else {
                                            false
                                        }
                                    }
                            )

                            if (isItemFocused) {
                                var fontSize by remember(mediaItem.title) { mutableStateOf(16.sp) }

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
                                        color = Color.White,
                                        fontSize = fontSize,
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
                    .offset(x = (-31).dp, y = (-46).dp),
                contentScale = ContentScale.Fit
            )

            Image(
                painter = painterResource(id = R.drawable.right_upper_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(25.4.dp)
                    .height(193.dp)
                    .offset(x = 840.dp, y = (-46).dp),
                contentScale = ContentScale.Fit
            )
        }
    }

    // 🔹 Centralized, debounced scroll logic
    LaunchedEffect(focusedIndex) {
        if (focusedIndex < 0 || recommendations.isEmpty()) return@LaunchedEffect

        // Snapshot of current visible range
        val layoutInfo = listState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty()) return@LaunchedEffect

        val firstVisibleIndex = visibleItems.first().index
        val lastVisibleIndex = visibleItems.last().index

        // If focused item is safely inside the visible window, do nothing
        if (focusedIndex in (firstVisibleIndex + 1)..(lastVisibleIndex - 1)) {
            return@LaunchedEffect
        }

        // Otherwise nudge the list so the focused item is slightly in from the edge
        val targetIndex = when {
            focusedIndex <= firstVisibleIndex -> maxOf(focusedIndex - 1, 0)
            focusedIndex >= lastVisibleIndex -> maxOf(focusedIndex - 1, 0)
            else -> focusedIndex
        }

        coroutineScope.launch {
            listState.scrollToItem(targetIndex)
        }
    }

    // Auto-focus first item when section appears
    LaunchedEffect(recommendations.size) {
        if (recommendations.isNotEmpty() && focusedIndex == -1) {
            focusedIndex = 0
            focusRequesters[0].requestFocus()
        }
    }
}
