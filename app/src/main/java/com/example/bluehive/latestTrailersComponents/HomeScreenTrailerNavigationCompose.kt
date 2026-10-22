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
import androidx.compose.runtime.key


@Composable
fun HomeScreenTrailerNavigationCompose(
    trailers: List<LatestTrailer>,
    isInteractive: Boolean, // true ONLY when your frame is Selected (and video not visible)
    isTrailerPlaying: Boolean,
    modifier: Modifier = Modifier,
    isPaused: Boolean = false,
    // The panel is entered (even while its trailer plays): titles sit top-right.
    raisedCaption: Boolean = false,
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

        // FINISH AN INTERRUPTED SLIDE FIRST. A press (or tick) that lands while a
        // slide is still running restarts this effect, cancelling it inside
        // animateTo -- so the two lines that commit it at the bottom never ran.
        // The next run then found currentIndex == shownIndex (RIGHT then LEFT,
        // 150-250 ms apart), returned, and left the panel frozen half-way with
        // two titles side by side and the halves swapped. Snapping the old slide
        // home before deciding what to do next makes every press start clean.
        incomingIndex?.let { interrupted ->
            shownIndex = interrupted
            incomingIndex = null
            slideProgress.snapTo(1f)
        }

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
                        // WRAPS BACKWARDS, mirroring the RIGHT handler below.
                        // Stopping at the first item left LEFT as a dead press
                        // in exactly the way RIGHT used to be at the last one:
                        // consumed, so focus did not move either.
                        //
                        // "+ size" before the modulo because Kotlin's % keeps the
                        // sign of the left operand - (0 - 1) % 20 is -1, not 19,
                        // and an index of -1 would crash on the next read.
                        if (trailers.isNotEmpty()) {
                            lastNavTime = now
                            slideDir = -1
                            isManualNavigation = true
                            currentIndex = (currentIndex - 1 + trailers.size) % trailers.size
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
                        if (trailers.isNotEmpty()) {
                            lastNavTime = now
                            slideDir = 1
                            isManualNavigation = true
                            currentIndex = (currentIndex + 1) % trailers.size
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
        val shown = shownIndex
        val dir = slideDir

        // ONE CALL SITE, KEYED BY INDEX, for the resting card and both sliding
        // cards. Separate call sites made every slide start and end dispose one
        // card and build a fresh one; the fresh card's caption measured its
        // badges as 0 dp for a frame (long titles flashed full-width, over the
        // badges when raised) and lost any glide still in flight. Keyed, the
        // card that finishes a slide is the same instance that will rest.
        //
        // The slide offset is read inside graphicsLayer, at draw time, so the
        // running animation moves the cards without recomposing them.
        if (trailers.isNotEmpty()) {
            val slots = if (inc == null) listOf(shown) else listOf(shown, inc)
            for (i in slots) {
                if (i in trailers.indices) key(i) {
                    Box(modifier = Modifier.graphicsLayer {
                        val p = slideProgress.value
                        translationX = when {
                            inc == null -> 0f
                            i == shown  -> -dir * (p * cardWidthPx)
                            else        ->  dir * ((1f - p) * cardWidthPx)
                        }
                    }) {
                        HomeScreenTrailerAdapterCompose(
                            trailer = trailers[i],
                            index = i,
                            total = trailers.size,
                            raised = raisedCaption
                        )
                    }
                }
            }
        }

    }
}
