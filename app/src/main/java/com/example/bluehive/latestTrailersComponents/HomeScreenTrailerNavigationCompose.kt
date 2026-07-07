package com.example.bluehive.latestTrailersComponents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.models.LatestTrailer
import kotlinx.coroutines.delay
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
import com.example.bluehive.homeScreenSectionRules.CAROUSEL_AUTO_CYCLE_ANIMATION_MS
import com.example.bluehive.homeScreenSectionRules.CAROUSEL_TRAILER_PHASE_OFFSET_MS
import com.example.bluehive.homeScreenSectionRules.TRAILER_THUMB_PX_H
import com.example.bluehive.homeScreenSectionRules.TRAILER_THUMB_PX_W
import com.example.bluehive.homeScreenSectionRules.trailerThumbMemoryKey
import com.example.bluehive.homeScreenSectionRules.trailerThumbUrl
import com.example.bluehive.homeScreenSectionRules.CAROUSEL_MANUAL_NAVIGATION_ANIMATION_MS
import com.example.bluehive.homeScreenSectionRules.CAROUSEL_NAVIGATION_RATE_MS
import com.example.bluehive.homeScreenSectionRules.CAROUSEL_PRELOAD_RADIUS
import com.example.bluehive.homeScreenSectionRules.LocalCarouselCycleTick
import androidx.compose.runtime.rememberUpdatedState


@Composable
fun HomeScreenTrailerNavigationCompose(
    trailers: List<LatestTrailer>,
    isInteractive: Boolean, // true ONLY when your frame is Selected (and video not visible)
    isTrailerPlaying: Boolean,
    modifier: Modifier = Modifier,
    isPaused: Boolean = false,
    onTrailerClick: (LatestTrailer) -> Unit = {}
) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }

    var currentIndex by rememberSaveable { mutableIntStateOf(0) }
    var lastNavTime by remember { mutableLongStateOf(0L) }

    // ---- Slide animation state ----
    var shownIndex by rememberSaveable { mutableIntStateOf(0) }              // currently displayed
    var incomingIndex by remember { mutableStateOf<Int?>(null) }        // sliding in
    var slideDir by remember { mutableIntStateOf(1) }                  // +1 forward, -1 backward
    val slideProgress = remember { Animatable(1f) }                    // 1 = idle, 0..1 anim

    // Track whether current animation is manual or auto
    var isManualNavigation by remember { mutableStateOf(false) }


    // Clamp index safely whenever trailers change
    SideEffect {
        if (trailers.isNotEmpty() && currentIndex > trailers.lastIndex) {
            currentIndex = 0
        }
        if (trailers.isNotEmpty() && shownIndex > trailers.lastIndex) {
            shownIndex = 0
        }
    }


    // Auto-cycle ONLY in response to the shared clock tick. The gating flags
    // are read live but are NOT keys — keying on them re-fired this block on
    // every pause/resume (or trailer-playing) edge, which jumped the carousel
    // forward the instant the sidebar closed. A tick during pause is ignored.
    val cycleTick           = LocalCarouselCycleTick.current
    val pausedState         = rememberUpdatedState(isPaused)
    val interactiveState    = rememberUpdatedState(isInteractive)
    val trailerPlayingState = rememberUpdatedState(isTrailerPlaying)
    LaunchedEffect(cycleTick) {
        // Half-beat phase offset (see CAROUSEL_TRAILER_PHASE_OFFSET_MS): trending
        // slides on the tick, trailers 3s later, so the two full-panel slide
        // animations never share frames. The pause/interactive gates are read
        // AFTER the delay so they reflect the moment this slide would actually
        // start (e.g. sidebar opened during the offset window → skip cleanly).
        delay(CAROUSEL_TRAILER_PHASE_OFFSET_MS)
        if (!interactiveState.value && !trailerPlayingState.value && !pausedState.value && trailers.isNotEmpty()) {
            slideDir           = 1
            isManualNavigation = false
            currentIndex       = (currentIndex + 1) % trailers.size
        }
    }



    // Preload window around currentIndex. Built from the SHARED image spec
    // (homeScreenSectionRules) so it hits the exact same disk/memory keys the
    // adapter renders from. It previously fetched the raw w1280/original URL
    // at 650×364 with no cache key — images the UI never read, so every tick
    // downloaded twice. With AppWarmup having disk-filled the whole set, this
    // window now just promotes disk→memory right before display.
    LaunchedEffect(trailers, currentIndex) {
        if (trailers.isEmpty()) return@LaunchedEffect

        val start = maxOf(0, currentIndex - CAROUSEL_PRELOAD_RADIUS)
        val end = minOf(trailers.lastIndex, currentIndex + CAROUSEL_PRELOAD_RADIUS)

        for (i in start..end) {
            val trailer = trailers[i]
            val req = ImageRequest.Builder(context)
                .data(trailerThumbUrl(trailer.imgSrc))
                .size(TRAILER_THUMB_PX_W, TRAILER_THUMB_PX_H)
                .memoryCacheKey(trailerThumbMemoryKey(trailer.id))
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .allowHardware(true)       // GPU memory, not Java heap
                .allowRgb565(true)
                .build()

            context.imageLoader.enqueue(req)
            delay(10)
        }
    }


    LaunchedEffect(trailers, currentIndex) {
        if (trailers.isEmpty()) return@LaunchedEffect

        // First-time sync
        if (shownIndex == currentIndex && incomingIndex == null) return@LaunchedEffect

        // If this is the first render, avoid animating from nothing
        if (incomingIndex == null && shownIndex !in trailers.indices) {
            shownIndex = currentIndex
            return@LaunchedEffect
        }

        if (currentIndex == shownIndex) return@LaunchedEffect

        incomingIndex = currentIndex
        slideProgress.snapTo(0f)

        // ✅ Use different speeds based on navigation type
        val animationDuration = if (isManualNavigation) {
            CAROUSEL_MANUAL_NAVIGATION_ANIMATION_MS
        } else {
            CAROUSEL_AUTO_CYCLE_ANIMATION_MS
        }

        slideProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = animationDuration)
        )

        shownIndex = currentIndex
        incomingIndex = null
    }



    Box(
        modifier = modifier
            // D-pad navigation only when interactive
            .size(325.19415283203125.dp, 182.35919189453125.dp)
            .clipToBounds()
            .onKeyEvent { event ->
                if (!isInteractive) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false

                when (event.key) {
                    Key.DirectionLeft -> {
                        val now = System.currentTimeMillis()
                        if (now - lastNavTime < CAROUSEL_NAVIGATION_RATE_MS) return@onKeyEvent true
                        if (trailers.isNotEmpty() && currentIndex > 0) {
                            lastNavTime = now
                            slideDir = -1
                            isManualNavigation = true
                            currentIndex -= 1
                            BlueHiveApplication.playHoverSound()
                        }
                        true
                    }

                    Key.DirectionRight -> {
                        val now = System.currentTimeMillis()
                        if (now - lastNavTime < CAROUSEL_NAVIGATION_RATE_MS) return@onKeyEvent true
                        if (trailers.isNotEmpty() && currentIndex < trailers.lastIndex) {
                            lastNavTime = now
                            slideDir = 1
                            isManualNavigation = true
                            currentIndex += 1
                            BlueHiveApplication.playHoverSound()
                        }
                        true
                    }

                    Key.DirectionUp,
                    Key.DirectionDown -> true // block while browsing

                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        trailers.getOrNull(currentIndex)?.let {
                            BlueHiveApplication.playClickSound()
                            onTrailerClick(it)
                        }
                        true
                    }

                    else -> false
                }
            }
            .focusable(enabled = isInteractive, interactionSource = interaction)
            .clickable(
                enabled = isInteractive,
                interactionSource = interaction,
                indication = null
            ) {
                trailers.getOrNull(currentIndex)?.let {
                    BlueHiveApplication.playClickSound()
                    onTrailerClick(it)
                }
            }
    ) {
        val density = LocalDensity.current
        val cardWidthPx = with(density) { 325.19415283203125.dp.toPx() }

        val inc = incomingIndex
        val p = slideProgress.value

        if (trailers.isNotEmpty()) {
            if (inc == null) {
                // Idle: show current
                HomeScreenTrailerAdapterCompose(
                    trailer = trailers[shownIndex],
                    index = shownIndex,
                    total = trailers.size
                )
            } else {

                Box(modifier = Modifier.graphicsLayer { translationX = (-slideDir * (p * cardWidthPx)) }) {
                    HomeScreenTrailerAdapterCompose(trailer = trailers[shownIndex],
                        index = shownIndex,
                        total = trailers.size
                    )
                }

                Box(modifier = Modifier.graphicsLayer { translationX = (slideDir * ((1f - p) * cardWidthPx)) }) {
                    HomeScreenTrailerAdapterCompose(trailer = trailers[inc],
                        index = inc,
                        total = trailers.size
                    )
                }
            }
        }

    }
}
