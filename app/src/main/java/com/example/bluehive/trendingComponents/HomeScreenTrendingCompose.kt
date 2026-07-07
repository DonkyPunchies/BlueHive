package com.example.bluehive.trendingComponents

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.imageLoader
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.R
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.TrendingItem
import com.example.bluehive.models.MediaItem
import com.example.bluehive.utilities.AppTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.example.bluehive.homeScreenSectionRules.CAROUSEL_AUTO_CYCLE_ANIMATION_MS
import com.example.bluehive.homeScreenSectionRules.TRENDING_BACKDROP_PX_H
import com.example.bluehive.homeScreenSectionRules.TRENDING_BACKDROP_PX_W
import com.example.bluehive.homeScreenSectionRules.trendingBackdropMemoryKey
import com.example.bluehive.homeScreenSectionRules.trendingBackdropUrl
import com.example.bluehive.homeScreenSectionRules.CAROUSEL_MANUAL_NAVIGATION_ANIMATION_MS
import com.example.bluehive.homeScreenSectionRules.CAROUSEL_NAVIGATION_RATE_MS
import com.example.bluehive.homeScreenSectionRules.CAROUSEL_PRELOAD_RADIUS
import com.example.bluehive.homeScreenSectionRules.LocalCarouselCycleTick
import androidx.compose.runtime.rememberUpdatedState

// ─────────────────────────────────────────────────────────────────────────────
// State
// ─────────────────────────────────────────────────────────────────────────────

sealed class TrendingFrameState {
    data object UnFocused : TrendingFrameState()
    data object Focused   : TrendingFrameState()
    data object Selected  : TrendingFrameState()
}


// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────


// Decode dimensions + URL transform + cache key come from the SHARED spec in
// homeScreenSectionRules — the window preloader and AppWarmup's splash prefetch
// build byte-identical requests, so renders here are always cache hits.

// Hoisted gradient — single allocation for the lifetime of the process.
// Brush.verticalGradient inside a composable allocates on every recompose.
private val BACKDROP_SCRIM = Brush.verticalGradient(
    colors = listOf(Color.Transparent, Color(0xCC000000))
)


// ─────────────────────────────────────────────────────────────────────────────
// Individual item renderer
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TrendingItemAdapterCompose(
    item: TrendingItem,
    modifier: Modifier = Modifier
) {
    val context     = LocalContext.current
    val titleFont   = AppTypography.pattayaRegular
    val metaFont    = AppTypography.passionRegular

    Box(modifier = modifier.fillMaxSize()) {

        // Backdrop image (w1280 already baked into the URL from the server)
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.backdropPath?.let { trendingBackdropUrl(it) })
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .allowRgb565(true)
                .allowHardware(true)
                .crossfade(150)
                .size(TRENDING_BACKDROP_PX_W, TRENDING_BACKDROP_PX_H)
                .memoryCacheKey(trendingBackdropMemoryKey(item.trendingId))
                .build(),
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Dark gradient overlay so text stays readable
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BACKDROP_SCRIM)
        )

        // Bottom-left: title + release year
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 8.dp, end = 8.dp)
                .widthIn(max = 200.dp)
        ) {
            Text(
                text = item.title,
                color = Color.White,
                fontFamily = titleFont,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 13.sp
            )

            val year = item.releaseDate?.take(4)
            if (!year.isNullOrBlank()) {
                Text(
                    text = year,
                    color = Color(0xFFD0D0D0),
                    fontFamily = metaFont,
                    fontSize = 8.sp,
                    maxLines = 1
                )
            }
        }

        // Top-left badges: media type + content rating + rank
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 6.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrendingPillBadge(
                text = item.mediaType.uppercase(),
                bg  = when (item.mediaType.lowercase()) {
                    "movie" -> Color(0xFF08CB00)
                    "tv"    -> Color(0xFF2196F3)
                    else    -> Color(0xFF757575)
                }
            )

            if (!item.contentRating.isNullOrBlank() && item.contentRating != "NR") {
                TrendingPillBadge(
                    text = item.contentRating,
                    bg   = Color(0xCC000000),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            TrendingPillBadge(
                text     = "#${item.trendRank}",
                bg       = Color(0xBB000000),
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        // Top-right: rating
        item.voteAverage?.let { rating ->
            if (rating > 0) {
                Text(
                    text = String.format(java.util.Locale.US, "%.1f", rating),
                    color    = Color.White,
                    fontFamily = metaFont,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 6.dp, top = 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xBB000000))
                        .padding(horizontal = 5.dp, vertical = 3.dp)
                )
            }
        }
    }
}


private val BADGE_SHAPE = RoundedCornerShape(3.dp)
@Composable
private fun TrendingPillBadge(
    text: String,
    bg: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text       = text,
        color      = Color.White,
        fontFamily = AppTypography.passionRegular,
        fontSize   = 7.sp,
        modifier   = modifier
            .clip(BADGE_SHAPE)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        maxLines   = 1,
        overflow   = TextOverflow.Clip
    )
}


// ─────────────────────────────────────────────────────────────────────────────
// Navigation carousel (same slide animation pattern as trailer section)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TrendingNavigationCompose(
    modifier: Modifier = Modifier,
    items: List<TrendingItem>,
    isInteractive: Boolean,
    isPaused: Boolean = false,
    onItemClick: (TrendingItem) -> Unit = {},
) {
    val context          = LocalContext.current
    val interaction      = remember { MutableInteractionSource() }

    var currentIndex     by remember  { mutableIntStateOf(0) }
    var lastNavTime      by remember { mutableLongStateOf(0L) }

    var shownIndex       by remember  { mutableIntStateOf(0) }
    var incomingIndex    by remember { mutableStateOf<Int?>(null) }
    var slideDir         by remember { mutableIntStateOf(1) }
    val slideProgress    = remember { Animatable(1f) }
    var isManualNav      by remember { mutableStateOf(false) }

    SideEffect {
        if (items.isNotEmpty() && currentIndex > items.lastIndex) currentIndex = 0
        if (items.isNotEmpty() && shownIndex > items.lastIndex)   shownIndex   = 0
    }

    // Auto-cycle ONLY in response to the shared clock tick. isInteractive /
    // isPaused are read live but are NOT keys — keying on them made the block
    // re-fire on every pause/resume edge, advancing the carousel the instant
    // the sidebar closed (the skip-forward bug) instead of resuming on the
    // next tick. A tick that lands while paused is simply ignored.
    val cycleTick        = LocalCarouselCycleTick.current
    val pausedState      = rememberUpdatedState(isPaused)
    val interactiveState = rememberUpdatedState(isInteractive)
    LaunchedEffect(cycleTick) {
        if (!interactiveState.value && !pausedState.value && items.isNotEmpty()) {
            slideDir     = 1
            isManualNav  = false
            currentIndex = (currentIndex + 1) % items.size
        }
    }

    // Preload window — shared image spec, so with AppWarmup having disk-filled
    // the whole set during the splash, this just promotes disk→memory right
    // before each backdrop is shown. Network never rides on a carousel tick.
    LaunchedEffect(items, currentIndex) {
        if (items.isEmpty()) return@LaunchedEffect
        val start = maxOf(0, currentIndex - CAROUSEL_PRELOAD_RADIUS)
        val end   = minOf(items.lastIndex, currentIndex + CAROUSEL_PRELOAD_RADIUS)
        for (i in start..end) {
            val url = items[i].backdropPath ?: continue
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(trendingBackdropUrl(url))
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .allowHardware(true)
                    .allowRgb565(true)
                    .size(TRENDING_BACKDROP_PX_W, TRENDING_BACKDROP_PX_H)
                    .memoryCacheKey(trendingBackdropMemoryKey(items[i].trendingId))
                    .build()
            )
            delay(10)
        }
    }

    // Slide animation
    LaunchedEffect(items, currentIndex) {
        if (items.isEmpty()) return@LaunchedEffect
        if (shownIndex == currentIndex && incomingIndex == null) return@LaunchedEffect
        if (incomingIndex == null && shownIndex !in items.indices) {
            shownIndex = currentIndex; return@LaunchedEffect
        }
        if (currentIndex == shownIndex) return@LaunchedEffect

        incomingIndex = currentIndex
        slideProgress.snapTo(0f)
        slideProgress.animateTo(
            1f,
            animationSpec = tween(if (isManualNav) CAROUSEL_MANUAL_NAVIGATION_ANIMATION_MS else CAROUSEL_AUTO_CYCLE_ANIMATION_MS)
        )
        shownIndex    = currentIndex
        incomingIndex = null
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onKeyEvent { event ->
                if (!isInteractive) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false

                when (event.key) {
                    Key.DirectionLeft -> {
                        val now = System.currentTimeMillis()
                        if (now - lastNavTime < CAROUSEL_NAVIGATION_RATE_MS) return@onKeyEvent true
                        if (items.isNotEmpty() && currentIndex > 0) {
                            lastNavTime  = now
                            slideDir     = -1
                            isManualNav  = true
                            currentIndex -= 1
                            BlueHiveApplication.playHoverSound()
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        val now = System.currentTimeMillis()
                        if (now - lastNavTime < CAROUSEL_NAVIGATION_RATE_MS) return@onKeyEvent true
                        if (items.isNotEmpty() && currentIndex < items.lastIndex) {
                            lastNavTime  = now
                            slideDir     = 1
                            isManualNav  = true
                            currentIndex += 1
                            BlueHiveApplication.playHoverSound()
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (items.isNotEmpty()) {
                            BlueHiveApplication.playClickSound()
                            onItemClick(items[currentIndex])
                        }
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> true  // block while browsing
                    else -> false
                }
            }
            .focusable(enabled = isInteractive, interactionSource = interaction)
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val cardWidthPx = with(density) { 424.dp.toPx() }
        val inc         = incomingIndex
        val p           = slideProgress.value

        if (items.isNotEmpty()) {
            if (inc == null) {
                TrendingItemAdapterCompose(
                    item     = items[shownIndex],
                )
            } else {
                val outTranslation = -slideDir * (p * cardWidthPx)
                val inTranslation  =  slideDir * ((1f - p) * cardWidthPx)

                // graphicsLayer moves the already-drawn bitmap on the RenderThread —
                // no layout pass, no recomposition, no UI-thread involvement per frame.
                Box(modifier = Modifier.graphicsLayer { translationX = outTranslation }) {
                    TrendingItemAdapterCompose(item = items[shownIndex])
                }
                Box(modifier = Modifier.graphicsLayer { translationX = inTranslation }) {
                    TrendingItemAdapterCompose(item = items[inc])
                }
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// Top-level composable  (matches HomeScreenTrailerSectionCompose pattern)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun HomeScreenTrendingCompose(
    modifier: Modifier = Modifier,
    trendType: String = "day",           // "day" or "week"
    isPaused: Boolean = false,
    onItemClick: (MediaItem) -> Unit = {},
    onStateChanged: (TrendingFrameState) -> Unit = {}
) {
    // ── Data ─────────────────────────────────────────────────────────────────
    val repo    = remember { TrendingRepository() }
    var items   by remember { mutableStateOf<List<TrendingItem>>(emptyList()) }
    val scope   = rememberCoroutineScope()
    val api     = remember { ApiClient.trailerApi }
    val context = LocalContext.current

    LaunchedEffect(trendType) {
        // Item 5 — use warm prefetch if it's still fresh, otherwise fetch live.
        // Only "day" can use the prefetch (that's what the warm-up fetched);
        // "week" always fetches fresh.
        val app = context.applicationContext as? BlueHiveApplication
        val prefetched = if (trendType == "day") app?.consumeTrendingPrefetch() else null

        items = if (!prefetched.isNullOrEmpty()) {
            android.util.Log.d("TrendingCompose", "⚡ Using ${prefetched.size} prefetched trending items")
            prefetched
        } else {
            repo.getTrending(trendType = trendType, limit = 40)
        }
        android.util.Log.d("TrendingCompose", "Loaded ${items.size} items, first backdrop: ${items.firstOrNull()?.backdropPath}")
    }

    // ── Focus / state ────────────────────────────────────────────────────────
    val hitboxInteraction    = remember { MutableInteractionSource() }
    val isHitboxFocused      by hitboxInteraction.collectIsFocusedAsState()
    val hitboxFocusRequester = remember { FocusRequester() }
    val carouselFocusRequester = remember { FocusRequester() }

    var trendingState        by remember { mutableStateOf<TrendingFrameState>(TrendingFrameState.UnFocused) }
    var pendingEnterSelected by remember { mutableStateOf(false) }

    LaunchedEffect(trendingState) { onStateChanged(trendingState) }

    LaunchedEffect(isHitboxFocused) {
        trendingState = if (!isHitboxFocused) {
            if (trendingState is TrendingFrameState.Selected) TrendingFrameState.Selected
            else TrendingFrameState.UnFocused
        } else {
            when (trendingState) {
                TrendingFrameState.Selected -> TrendingFrameState.Selected
                else                        -> TrendingFrameState.Focused
            }
        }
    }

    LaunchedEffect(pendingEnterSelected, trendingState) {
        if (pendingEnterSelected && trendingState is TrendingFrameState.Selected) {
            withFrameNanos { }
            carouselFocusRequester.requestFocus()
            pendingEnterSelected = false
        }
    }

    BackHandler(enabled = trendingState is TrendingFrameState.Selected) {
        trendingState = TrendingFrameState.Focused
        hitboxFocusRequester.requestFocus()
    }

    // ── Layout dimensions — mirror HRC frame pattern, adjust to taste ────────
    val shadowW = 495.dp;  val shadowH = 295.dp
    val shadowX = 0.dp;    val shadowY = 1.5.dp

    val focusedW = 488.dp; val focusedH = 285.75.dp
    val focusedX = 4.dp;   val focusedY = 6.5.dp

    val frameW   = 455.dp; val frameH   = 252.75.dp
    val frameX   = 20.dp;  val frameY   = 23.dp

    val viewportW = 424.dp; val viewportH = 218.7.dp
    val viewportX = 36.5.dp;  val viewportY = 38.5.dp

    Box(modifier = modifier) {

        // Focused overlay — visible when Focused OR Selected
        Image(
            painter = painterResource(id = R.drawable.hrc_frame_focused),
            contentDescription = "Trending frame focused",
            modifier = Modifier
                .offset(x = focusedX, y = focusedY)
                .width(focusedW)
                .height(focusedH)
                .zIndex(0f)
                .graphicsLayer(alpha = if (trendingState is TrendingFrameState.UnFocused) 0f else 1f),
            contentScale = ContentScale.FillBounds
        )

        // Shadow — disappears only when Selected
        Image(
            painter = painterResource(id = R.drawable.hrc_frame_shadow),
            contentDescription = "Trending frame shadow",
            modifier = Modifier
                .offset(x = shadowX, y = shadowY)
                .width(shadowW)
                .height(shadowH)
                .zIndex(1f)
                .graphicsLayer(alpha = if (trendingState is TrendingFrameState.Selected) 0f else 1f),
            contentScale = ContentScale.FillBounds
        )

        // Frame PNG (front border)
        Image(
            painter = painterResource(id = R.drawable.hrc_frame),
            contentDescription = "Trending frame border",
            modifier = Modifier
                .offset(x = frameX, y = frameY)
                .width(frameW)
                .height(frameH)
                .zIndex(2f),
            contentScale = ContentScale.FillBounds
        )

        // Carousel viewport
        TrendingNavigationCompose(
            items         = items,
            isInteractive = trendingState is TrendingFrameState.Selected,
            isPaused      = isPaused,
            // trendingItem is TrendingItem here — fetch full details before navigating
            onItemClick   = { trendingItem ->
                scope.launch {
                    try {
                        val detail = api.getMediaDetails(
                            tmdbId    = trendingItem.tmdbId,
                            mediaType = trendingItem.mediaType
                        )
                        val genreList = when (val g = detail.genres) {
                            is List<*> -> when (g.firstOrNull()) {
                                is String -> g.filterIsInstance<String>()          // TEXT[] from trending_media_items
                                is Map<*, *> -> @Suppress("UNCHECKED_CAST")
                                (g as? List<Map<String, Any>>)
                                    ?.mapNotNull { it["name"] as? String } ?: emptyList()
                                else -> emptyList()
                            }
                            else -> emptyList()
                        }
                        val logoUrl = when (val l = detail.logos) {
                            is String -> l.ifBlank { null }
                            else      -> null
                        }
                        onItemClick(
                            MediaItem(
                                tmdbId           = detail.tmdb_id,
                                mediaId          = detail.tmdb_id,
                                title            = detail.title ?: trendingItem.title,
                                mediaType        = detail.media_type,
                                posterUrl        = detail.poster_path,
                                backdropUrl      = detail.backdrop_path ?: trendingItem.backdropPath,
                                logoUrl          = logoUrl,
                                overview         = detail.overview,
                                releaseDate      = detail.release_date ?: trendingItem.releaseDate,
                                status           = detail.status,
                                voteAverage      = detail.vote_average ?: trendingItem.voteAverage,
                                voteCount        = null,
                                popularity       = null,
                                popularityRank   = null,
                                originalLanguage = detail.original_language,
                                numberOfSeasons  = detail.number_of_seasons,
                                numberOfEpisodes = null,
                                contentRating    = detail.content_rating ?: trendingItem.contentRating,
                                runtime          = detail.runtime,
                                budget           = null,
                                revenue          = null,
                                trailerUrl       = detail.youtube_trailer_url,
                                genres           = genreList.ifEmpty { null },
                                similarItems     = null,
                                whereToWatch     = null
                            )
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("TrendingCompose", "Detail fetch failed for ${trendingItem.tmdbId}, falling back", e)
                        // Fallback: navigate with partial data from TrendingItem
                        onItemClick(
                            MediaItem(
                                tmdbId           = trendingItem.tmdbId,
                                mediaId          = trendingItem.tmdbId,
                                title            = trendingItem.title,
                                mediaType        = trendingItem.mediaType,
                                posterUrl        = null,
                                backdropUrl      = trendingItem.backdropPath,
                                logoUrl          = null,
                                overview         = null,
                                releaseDate      = trendingItem.releaseDate,
                                status           = null,
                                voteAverage      = trendingItem.voteAverage,
                                voteCount        = null,
                                popularity       = null,
                                popularityRank   = null,
                                originalLanguage = null,
                                numberOfSeasons  = null,
                                numberOfEpisodes = null,
                                contentRating    = trendingItem.contentRating,
                                runtime          = null,
                                budget           = null,
                                revenue          = null,
                                trailerUrl       = null,
                                genres           = null,
                                similarItems     = null,
                                whereToWatch     = null
                            )
                        )
                    }
                }
            },
            modifier      = Modifier
                .offset(x = viewportX, y = viewportY)
                .width(viewportW)
                .height(viewportH)
                .zIndex(5f)
                .focusRequester(carouselFocusRequester)
        )

        // Focus/click hitbox
        Box(
            modifier = Modifier
                .offset(x = frameX, y = frameY)
                .width(frameW)
                .height(frameH)
                .zIndex(3f)
                .focusRequester(hitboxFocusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (trendingState is TrendingFrameState.Selected) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionRight -> {
                            BlueHiveApplication.playHoverSound(); false
                        }
                        else -> false
                    }
                }
                .clickable(
                    interactionSource = hitboxInteraction,
                    indication        = null
                ) {
                    if (trendingState !is TrendingFrameState.Selected) {
                        BlueHiveApplication.playClickSound()
                        trendingState        = TrendingFrameState.Selected
                        pendingEnterSelected = true
                    }
                }
                .focusable(interactionSource = hitboxInteraction)
        )
    }
}