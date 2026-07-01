// utilities/RecommendedMediaSection.kt
package com.example.bluehive.utilities

import android.util.Log
import androidx.activity.ComponentActivity
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
import kotlinx.coroutines.launch

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
                        val isItemFocused = hasRowFocus && (focusedIndex == index)

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
                                        if (focusState.isFocused) {
                                            // 🔹 Only update state, no scroll here
                                            focusedIndex = index
                                        }
                                    }
                                    .onKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown) {
                                            when (event.key) {
                                                Key.DirectionLeft -> {
                                                    if (focusedIndex > 0) {
                                                        val targetIndex = focusedIndex - 1
                                                        focusRequesters[targetIndex].requestFocus()
                                                        BlueHiveApplication.playTitleCardNavigation()
                                                    }
                                                    true
                                                }

                                                Key.DirectionRight -> {
                                                    if (focusedIndex < recommendations.size - 1) {
                                                        val targetIndex = focusedIndex + 1
                                                        focusRequesters[targetIndex].requestFocus()
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
                                        .offset(y = (-2).dp),
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
