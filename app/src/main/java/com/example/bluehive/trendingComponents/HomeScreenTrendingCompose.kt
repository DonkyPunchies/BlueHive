package com.example.bluehive.trendingComponents

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
import androidx.compose.runtime.key
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.onSizeChanged
import com.example.bluehive.homeScreenSectionRules.PANEL_HINT_GOLD
import com.example.bluehive.homeScreenSectionRules.PanelCaption
import com.example.bluehive.homeScreenSectionRules.PanelHintLayer

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

// THE PICTURE AREA IS 16:9, the shape of every TMDB backdrop. At 424 dp wide
// it was 1.94:1, so each backdrop was zoomed ~9% and lost ~20 px off its top
// and bottom; the Latest Trailers panel beside it is 16:9 and shows its images
// whole. At 389 x 218.7 dp the picture is 778 x 437 px on the onn box, and the
// w780 files (780 x 439) fit it almost exactly. Also the slide distance of the
// carousel, which must match or a slide stops short of the edge.
private val TRENDING_VIEWPORT_W = 389.dp
private val TRENDING_VIEWPORT_H = 218.7.dp

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
    // POSITION IN THE CAROUSEL, not the item's trend rank.
    //
    // The badge used to read "#8" from item.trendRank, which answers a question
    // nobody watching a rotating frame is asking and is actively confusing when
    // a media_type filter makes the ranks sparse (1, 2, 5, 7...). "8/20" says
    // where you are and how much is left, and matches the trailers frame beside
    // it, which has always read "3/36".
    position: Int = 0,
    total: Int = 0,
    // The panel is entered: the title moves to the top-right (PanelCaption).
    raised: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context     = LocalContext.current
    val titleFont   = AppTypography.pattayaRegular
    val metaFont    = AppTypography.passionRegular
    val density     = androidx.compose.ui.platform.LocalDensity.current
    // How much of the top row the badges take, so the raised title stays clear.
    var badgesWidth by remember { mutableStateOf(0.dp) }

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

        // Title + release year: bottom-left, or top-right while the panel is
        // entered. Was a bottom-left Column capped at 200 dp that wrapped to two
        // lines; PanelCaption keeps each to one line and shrinks it to fit.
        PanelCaption(
            title         = item.title,
            subtitle      = item.releaseDate?.take(4),
            raised        = raised,
            reservedStart = badgesWidth,
            titleFont     = titleFont,
            metaFont      = metaFont
        )

        // Top-left badges: media type + content rating + rank
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                // 8dp so the larger badges are not crowded against the frame.
                .padding(start = 8.dp, top = 8.dp)
                .onSizeChanged { badgesWidth = with(density) { it.width.toDp() } },
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrendingPillBadge(
                text = item.mediaType.uppercase(),
                outlined = true,
                bg  = when (item.mediaType.lowercase()) {
                    "movie" -> Color(0xFF0BA92B)
                    "tv"    -> Color(0xFF2196F3)
                    else    -> Color(0xFF757575)
                }
            )

            if (!item.contentRating.isNullOrBlank() && item.contentRating != "NR") {
                TrendingPillBadge(
                    text = item.contentRating,
                    bg   = Color(0xCC000000),
                    modifier = Modifier.padding(start = 5.dp)
                )
            }

            if (total > 0) {
                TrendingPillBadge(
                    text     = "$position/$total",
                    bg       = Color(0xBB000000),
                    modifier = Modifier.padding(start = 5.dp)
                )
            }
        }

        // TOP-RIGHT HAS NO BADGE. The score badge that sat here was removed on
        // request, and the corner now belongs to the remote hints: "Click to
        // browse" while the panel is focused, and the raised title while it is
        // entered (PanelHints.kt). item.voteAverage is still carried on the
        // model and still populated from the detail fetch below.
    }
}


private val BADGE_SHAPE = RoundedCornerShape(3.dp)
// A hairline near-black edge so each overlay reads as a distinct chip. It
// defines the coloured badges cleanly against bright artwork; on the dark
// chips it is deliberately almost invisible, which keeps them quiet.
private val BADGE_BORDER = Color(0xFF0C0C0C)

@Composable
private fun TrendingPillBadge(
    text: String,
    bg: Color,
    // Draws a black outline around the LETTERS, not a box around the pill.
    // Used for the media-type badge, whose green and blue backgrounds are light
    // enough that white text on them loses its edges over bright artwork.
    //
    // Compose has no text-stroke property, so the string is drawn twice: once
    // as a stroked black path, then filled white on top. Both copies take the
    // same font and size, so they occupy the same box and line up exactly.
    outlined: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (outlined) {
        Box(
            modifier = modifier
                .clip(BADGE_SHAPE)
                .background(bg)
                .border(1.dp, BADGE_BORDER, BADGE_SHAPE)
                .padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Text(
                text       = text,
                color      = Color.Black,
                fontFamily = AppTypography.passionRegular,
                fontSize   = 10.sp,
                style      = TextStyle(
                    drawStyle = Stroke(width = 1.5f, join = StrokeJoin.Round)
                ),
                maxLines   = 1,
                overflow   = TextOverflow.Clip
            )
            Text(
                text       = text,
                color      = Color.White,
                fontFamily = AppTypography.passionRegular,
                fontSize   = 10.sp,
                maxLines   = 1,
                overflow   = TextOverflow.Clip
            )
        }
        return
    }

    Text(
        text       = text,
        color      = Color.White,
        fontFamily = AppTypography.passionRegular,
        // 10sp. Was 7sp, which was decorative rather than readable on a
        // television viewed from a sofa; 13sp overcorrected and the badges
        // started competing with the artwork. This is the midpoint.
        fontSize   = 10.sp,
        modifier   = modifier
            .clip(BADGE_SHAPE)
            .background(bg)
            .border(1.dp, BADGE_BORDER, BADGE_SHAPE)
            .padding(horizontal = 9.dp, vertical = 4.dp),
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
        // FINISH AN INTERRUPTED SLIDE FIRST -- see the trailer carousel
        // (HomeScreenTrailerNavigationCompose) for the freeze this prevents: a
        // press mid-slide cancelled the commit at the bottom of this effect, and
        // RIGHT-then-LEFT left the panel stuck half-way between two titles.
        incomingIndex?.let { interrupted ->
            shownIndex    = interrupted
            incomingIndex = null
            slideProgress.snapTo(1f)
        }
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
                        // WRAPS BACKWARDS, mirroring the RIGHT handler below.
                        // Stopping at the first item left LEFT as a dead press
                        // in exactly the way RIGHT used to be at the last one:
                        // consumed, so focus did not move either.
                        //
                        // "+ size" before the modulo because Kotlin's % keeps the
                        // sign of the left operand - (0 - 1) % 20 is -1, not 19,
                        // and an index of -1 would crash on the next read.
                        if (items.isNotEmpty()) {
                            lastNavTime  = now
                            slideDir     = -1
                            isManualNav  = true
                            currentIndex = (currentIndex - 1 + items.size) % items.size
                            BlueHiveApplication.playHoverSound()
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        val now = System.currentTimeMillis()
                        if (now - lastNavTime < CAROUSEL_NAVIGATION_RATE_MS) return@onKeyEvent true
                        // WRAPS AT THE END, exactly as the auto-advance above does.
                        // Stopping at the last item made RIGHT a dead press: the
                        // event was still consumed, so focus did not move either
                        // and the remote simply did nothing.
                        //
                        // Same "% size" and slideDir = 1 as the automatic cycle,
                        // so the wrap animates identically whether the carousel
                        // turned itself or the viewer turned it.
                        if (items.isNotEmpty()) {
                            lastNavTime  = now
                            slideDir     = 1
                            isManualNav  = true
                            currentIndex = (currentIndex + 1) % items.size
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
        val cardWidthPx = with(density) { TRENDING_VIEWPORT_W.toPx() }
        val inc         = incomingIndex
        val shown       = shownIndex
        val dir         = slideDir

        // ONE CALL SITE, KEYED BY INDEX, for the resting card and both sliding
        // cards, as in the trailer carousel: the card that finishes a slide is
        // the same instance that will rest, so its caption keeps its measured
        // badge width and any glide in flight instead of starting over.
        //
        // graphicsLayer moves the already-drawn bitmap on the RenderThread, and
        // the slide offset is read INSIDE it, so the running animation costs no
        // layout pass and no recomposition per frame.
        if (items.isNotEmpty()) {
            val slots = if (inc == null) listOf(shown) else listOf(shown, inc)
            for (i in slots) {
                if (i in items.indices) key(i) {
                    Box(modifier = Modifier.graphicsLayer {
                        val p = slideProgress.value
                        translationX = when {
                            inc == null -> 0f
                            i == shown  -> -dir * (p * cardWidthPx)
                            else        ->  dir * ((1f - p) * cardWidthPx)
                        }
                    }) {
                        TrendingItemAdapterCompose(
                            item     = items[i],
                            position = i + 1,
                            total    = items.size,
                            raised   = isInteractive,
                        )
                    }
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

    // Leaving Selected is a back-out, matching the trailer panel: it undoes the
    // press that entered, and the shadow clears with it.
    // The detail fetch a Click started, until it has opened the title. ONE at a
    // time: a second Click during the ~1 s fetch used to start a second one and
    // open the details screen twice. Back cancels it -- leaving the panel used to
    // be followed, a moment later, by the details screen opening anyway.
    val detailsJob = remember { mutableStateOf<Job?>(null) }

    BackHandler(enabled = trendingState is TrendingFrameState.Selected) {
        detailsJob.value?.cancel()
        BlueHiveApplication.playBackOutSound()
        trendingState = TrendingFrameState.Focused
        hitboxFocusRequester.requestFocus()
    }

    // ── Remote hints (PanelHints.kt) ─────────────────────────────────────────
    // Gold while entered, exactly as the trailer panel: near-white frame art,
    // so a Modulate tint IS the colour and white leaves it untouched.
    val frameTint by animateColorAsState(
        targetValue   = if (trendingState is TrendingFrameState.Selected) PANEL_HINT_GOLD else Color.White,
        animationSpec = tween(250),
        label         = "trendingFrameTint"
    )

    // ── Layout dimensions — mirror HRC frame pattern, adjust to taste ────────
    //
    // Every width is 35 dp narrower than it was before 2026-10-19, to make the
    // picture 16:9 (see TRENDING_VIEWPORT_W). The three frame images were cut
    // down by the same 70 px rather than squeezed, so these sizes still draw
    // them 1:1. Home moved the panel 35 dp right to keep its right edge.
    val shadowW = 460.dp;  val shadowH = 295.dp
    val shadowX = 0.dp;    val shadowY = 1.5.dp

    val focusedW = 453.dp; val focusedH = 285.75.dp
    val focusedX = 4.dp;   val focusedY = 6.5.dp

    val frameW   = 420.dp; val frameH   = 252.75.dp
    val frameX   = 20.dp;  val frameY   = 23.dp

    val viewportW = TRENDING_VIEWPORT_W; val viewportH = TRENDING_VIEWPORT_H
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
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(frameTint, BlendMode.Modulate)
        )

        // Shadow — visible ONLY when Selected.
        //
        // INVERTED FROM ITS ORIGINAL SENSE, matching the Continue Watching
        // panel. The focus overlay is drawn for BOTH Focused and Selected, so
        // this shadow is the only thing separating "I am on this panel" from
        // "I am inside it" -- and inside is the state worth marking, because it
        // is where LEFT/RIGHT stop moving between sections and start turning
        // the carousel. Spending the effect at rest and removing it on entry
        // said the least at the moment something actually changed.
        Image(
            painter = painterResource(id = R.drawable.hrc_frame_shadow),
            contentDescription = "Trending frame shadow",
            modifier = Modifier
                .offset(x = shadowX, y = shadowY)
                .width(shadowW)
                .height(shadowH)
                .zIndex(1f)
                .graphicsLayer(alpha = if (trendingState is TrendingFrameState.Selected) 1f else 0f),
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
                if (detailsJob.value?.isActive == true) return@TrendingNavigationCompose
                detailsJob.value = scope.launch {
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
                    } catch (e: CancellationException) {
                        // Back cancelled the fetch. Rethrown, never treated as a
                        // failure: the fallback below would open the title anyway.
                        throw e
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
                        // DOWN lands on something -- the Continue Watching
                        // panel or the shelf row below -- so the departing side
                        // speaks for the move and then lets focus go.
                        Key.DirectionDown -> {
                            BlueHiveApplication.playHoverSound(); false
                        }

                        // UP IS AN EDGE TOO. The comment this replaces claimed
                        // UP reached "the frame above"; there is no frame above.
                        // This panel sits at the top of the content area, and
                        // measured, focus does not move.
                        Key.DirectionUp -> true

                        // RIGHT IS AN EDGE. This frame is the rightmost thing
                        // on its row, so the press moved nothing while still
                        // chirping as though it had: the sound said "you went
                        // somewhere" to a viewer looking at an unchanged screen,
                        // which is worse than silence because it reads as the
                        // screen having missed the press.
                        //
                        // Consumed rather than passed through, so nothing below
                        // can quietly take focus off the edge instead -- the
                        // Continue Watching panel sits right-and-below and is a
                        // plausible target for a directional search.
                        //
                        // Only the UNSELECTED frame. Once the carousel is
                        // entered, RIGHT turns it, and that handler is upstream
                        // of this one (see the Selected early-return above).
                        Key.DirectionRight -> true

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

        // Remote hints over the picture, above the carousel (zIndex 5). Not
        // focusable and handles no keys, so the focus model above is untouched.
        val selected = trendingState is TrendingFrameState.Selected
        PanelHintLayer(
            compact      = if (trendingState is TrendingFrameState.Focused) "to browse" else null,
            legend       = selected,
            legendBrowse = true,
            legendClick  = "to open",
            legendExit   = "Exit",
            arrows       = selected,
            modifier     = Modifier
                .offset(x = viewportX, y = viewportY)
                .width(viewportW)
                .height(viewportH)
                .zIndex(6f)
        )
    }
}