package com.example.bluehive.latestTrailersComponents

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.bluehive.R
import com.example.bluehive.models.LatestTrailer
import com.example.bluehive.repository.TrailerRepository
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import com.example.bluehive.BlueHiveApplication


sealed class TrailerFrameState {
    data object UnFocused : TrailerFrameState()
    data object Focused : TrailerFrameState()
    data object Selected : TrailerFrameState()
}

@Composable
fun HomeScreenTrailerSectionCompose(
    modifier: Modifier = Modifier,
    isPaused: Boolean = false,
    onStateChanged: (TrailerFrameState) -> Unit = {},
    onTrailerClick: (LatestTrailer) -> Unit = {}
) {
    // Listen to app state changes
    val context = LocalContext.current
    val app = context.applicationContext as BlueHiveApplication
    var currentAppState by remember {
        mutableStateOf(app.getCurrentState())
    }


    DisposableEffect(Unit) {
        val listener = object : BlueHiveApplication.StateListener {
            override fun onStateChanged(
                newState: BlueHiveApplication.AppState,
                previousState: BlueHiveApplication.AppState
            ) {
                currentAppState = newState
                Log.d("TrailerSection", "App state changed: $previousState → $newState")
            }
        }

        app.registerStateListener(listener)

        onDispose {
            app.unregisterStateListener(listener)
        }
    }

    val shouldPauseAutoCycle = remember(isPaused, currentAppState) {
        isPaused || currentAppState != BlueHiveApplication.AppState.HOME_ACTIVE
    }


    // Focus tracking for the TRAILER SECTION hitbox (not the images)
    val hitboxInteraction = remember { MutableInteractionSource() }
    val isHitboxFocused by hitboxInteraction.collectIsFocusedAsState()

    val hitboxFocusRequester = remember { FocusRequester() }
    val carouselFocusRequester = remember { FocusRequester() }
    val videoFocusRequester = remember { FocusRequester() }
    val focusGuardRequester = remember { FocusRequester() }

    // API
    val repo = remember { TrailerRepository() }
    var trailers by remember { mutableStateOf<List<LatestTrailer>>(emptyList()) }

    LaunchedEffect(Unit) {
        // Item 5 — use warm prefetch if still fresh, otherwise fetch live.
        val app = context.applicationContext as? BlueHiveApplication
        val prefetched = app?.consumeTrailersPrefetch()

        trailers = if (!prefetched.isNullOrEmpty()) {
            Log.d("TrailerSection", "⚡ Using ${prefetched.size} prefetched trailers")
            prefetched
        } else {
            repo.getLatestTrailers(limit = 100, offset = 0, sourceTab = null)
        }
    }

    DisposableEffect(Unit) {
        onDispose { repo.cancelAllRequests() }
    }





    // State
    var trailerState by remember { mutableStateOf<TrailerFrameState>(TrailerFrameState.UnFocused) }
    var pendingEnterSelected by remember { mutableStateOf(false) }

    // Video overlay state (new, but DOES NOT change your focus model)
    var isVideoVisible by remember { mutableStateOf(false) }
    var playingTrailer by remember { mutableStateOf<LatestTrailer?>(null) }
    var pendingShowVideo by remember { mutableStateOf(false) }
    var pendingReturnToCarousel by remember { mutableStateOf(false) }


    // NEW: Report state changes to parent
    LaunchedEffect(trailerState) {
        onStateChanged(trailerState)
    }

    // Sync state with focus (from HITBOX)
    LaunchedEffect(isHitboxFocused) {
        trailerState =
            if (!isHitboxFocused) {
                // IMPORTANT: if Selected, don't auto-drop to UnFocused
                if (trailerState is TrailerFrameState.Selected) TrailerFrameState.Selected
                else TrailerFrameState.UnFocused
            } else {
                when (trailerState) {
                    TrailerFrameState.Selected -> TrailerFrameState.Selected
                    else -> TrailerFrameState.Focused
                }
            }
    }

    // Enter Selected -> push focus into carousel AFTER recomposition (no "search bar flash")
    LaunchedEffect(pendingEnterSelected, trailerState) {
        if (pendingEnterSelected && trailerState is TrailerFrameState.Selected) {
            withFrameNanos { /* yield 1 frame */ }
            carouselFocusRequester.requestFocus()
            pendingEnterSelected = false
        }
    }

    // Show video -> request focus AFTER recomposition (prevents focus “falling” elsewhere)
    LaunchedEffect(pendingShowVideo, isVideoVisible) {
        if (pendingShowVideo && isVideoVisible) {
            withFrameNanos { /* yield 1 frame */ }
            videoFocusRequester.requestFocus()
            pendingShowVideo = false
        }
    }

    // Close video -> return focus to carousel AFTER recomposition
    LaunchedEffect(pendingReturnToCarousel, isVideoVisible) {
        if (pendingReturnToCarousel && !isVideoVisible) {
            carouselFocusRequester.requestFocus()
            pendingReturnToCarousel = false
        }
    }


    // Back exits video first (if visible)
    BackHandler(enabled = isVideoVisible) {
        // Hold focus in the viewport immediately so it can't jump to search bar
        focusGuardRequester.requestFocus()

        isVideoVisible = false
        playingTrailer = null
        pendingReturnToCarousel = true
    }

    // Back exits Selected -> Focused and returns focus to hitbox (only if video not visible)
    BackHandler(enabled = !isVideoVisible && trailerState is TrailerFrameState.Selected) {
        trailerState = TrailerFrameState.Focused
        hitboxFocusRequester.requestFocus()
    }

    // ===== specs =====
    val shadowW = 387.dp
    val shadowH = 247.dp
    val shadowX = 47.dp
    val shadowY = 28.dp

    val focusedW = 390.5.dp
    val focusedH = 255.7.dp
    val focusedX = 45.dp
    val focusedY = 21.dp

    val frameW = 357.dp
    val frameH = 222.528.dp
    val frameX = 61.7.dp
    val frameY = 37.7.dp

    val viewportW = 325.19415283203125.dp
    val viewportH = 182.35919189453125.dp
    val viewportX = 77.5.dp
    val viewportY = 53.5.dp

    // trailerGlass specs
    val glassW = 328.dp
    val glassH = 186.dp
    val glassX = 76.dp
    val glassY = 52.dp



    Box(modifier = modifier) {
        // Focused overlay (visible whenever Focused OR Selected)
        Image(
            painter = painterResource(id = R.drawable.new_trailer_frame_focused),
            contentDescription = "Trailer frame focused overlay",
            modifier = Modifier
                .offset(x = focusedX, y = focusedY)
                .width(focusedW)
                .height(focusedH)
                .graphicsLayer(alpha = if (trailerState is TrailerFrameState.UnFocused) 0f else 1f),
            contentScale = ContentScale.FillBounds
        )

        // Shadow (ONLY disappears when Selected)
        Image(
            painter = painterResource(id = R.drawable.trailer_frame_shadow),
            contentDescription = "Trailer frame shadow",
            modifier = Modifier
                .offset(x = shadowX, y = shadowY)
                .width(shadowW)
                .height(shadowH)
                .graphicsLayer(alpha = if (trailerState is TrailerFrameState.Selected) 0f else 1f),
            contentScale = ContentScale.FillBounds
        )

        // Frame image BEHIND the carousel (so trailers remain visible)
        Image(
            painter = painterResource(id = R.drawable.trailer_frame),
            contentDescription = "Trailer frame",
            modifier = Modifier
                .offset(x = frameX, y = frameY)
                .width(frameW)
                .height(frameH),
            contentScale = ContentScale.FillBounds
        )

        // Carousel viewport (drawn ABOVE frame)
        HomeScreenTrailerNavigationCompose(
            trailers = trailers,
            // IMPORTANT: when video is visible, carousel must NOT consume keys
            isInteractive = (trailerState is TrailerFrameState.Selected) && !isVideoVisible,
            isTrailerPlaying = isVideoVisible,
            isPaused = shouldPauseAutoCycle,
            onTrailerClick = { trailer ->
                // when user selects a trailer (Enter/Center), show video in the viewport
                playingTrailer = trailer
                isVideoVisible = true
                pendingShowVideo = true
                onTrailerClick(trailer) // keep your external hook, if you want it
            },
            modifier = Modifier
                .offset(x = viewportX, y = viewportY)
                .width(viewportW)
                .height(viewportH)
                .focusRequester(carouselFocusRequester)
        )

        // Video overlay rendered INSIDE the viewport (same box)
        HomeScreenTrailerVideoOverlayCompose(
            youtubeUrl = playingTrailer?.youtubeUrl.orEmpty(),
            visible = isVideoVisible && !playingTrailer?.youtubeUrl.isNullOrBlank(),
            onClose = {
                focusGuardRequester.requestFocus()
                isVideoVisible = false
                playingTrailer = null
                pendingReturnToCarousel = true
            },
            modifier = Modifier
                .offset(x = viewportX, y = viewportY)
                .width(viewportW)
                .height(viewportH)
                .focusRequester(videoFocusRequester)
        )



        // ✅ Focus guard: prevents focus from jumping to search bar during close/open transitions
        if (isVideoVisible || pendingReturnToCarousel) {
            Box(
                modifier = Modifier
                    .offset(x = viewportX, y = viewportY)
                    .width(viewportW)
                    .height(viewportH)
                    .focusRequester(focusGuardRequester)
                    .focusable()
            )
        }


        // ✅ Focus/click hitbox:
        // Keep your exact behavior: DO NOT disable focus immediately on Selected.
        // Only disable while video is visible (overlay holds focus).
        Box(
            modifier = Modifier
                .offset(x = frameX, y = frameY)
                .width(frameW)
                .height(frameH)
                .focusRequester(hitboxFocusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (isVideoVisible) return@onPreviewKeyEvent false

                    // FIX: When Selected, don't intercept ANY navigation keys
                    // Let the carousel handle them
                    if (trailerState is TrailerFrameState.Selected) {
                        return@onPreviewKeyEvent false
                    }

                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionRight -> {
                            BlueHiveApplication.playHoverSound()
                            false
                        }
                        else -> false
                    }
                }
                .clickable(
                    enabled = !isVideoVisible,
                    interactionSource = hitboxInteraction,
                    indication = null
                ) {
                    if (trailerState !is TrailerFrameState.Selected) {
                        trailerState = TrailerFrameState.Selected
                        pendingEnterSelected = true
                    }
                }
                .focusable(enabled = !isVideoVisible, interactionSource = hitboxInteraction)
        )

        // trailerGlass layer (placeholder until you give the PNG)
        Box(
            modifier = Modifier
                .offset(x = glassX, y = glassY)
                .width(glassW)
                .height(glassH)
        )
    }
}
