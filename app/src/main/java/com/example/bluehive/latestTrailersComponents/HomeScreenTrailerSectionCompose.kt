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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import com.example.bluehive.homeScreenSectionRules.PANEL_HINT_GOLD
import com.example.bluehive.homeScreenSectionRules.PANEL_PLAY_HINT_MS
import com.example.bluehive.homeScreenSectionRules.PanelHintLayer


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

    // ── Remote hints (PanelHints.kt) ─────────────────────────────────────────
    // The "Click to pause | Close" bar over a playing trailer. It goes up with
    // the click and HOLDS, full, while "Loading trailer..." shows; the gold
    // hairline only starts counting down PANEL_PLAY_HINT_MS once the first
    // picture is on screen, and again after each Click. Bumping the nonce
    // restarts it, which also cancels a countdown left over from the last one.
    var videoPaused      by remember { mutableStateOf(false) }
    // First picture on screen: until then a Click (pause/play) must not start
    // the countdown, or the bar could fade before the trailer ever appeared.
    var videoStarted     by remember { mutableStateOf(false) }
    // The trailer could not be played: the bar shows only "Close", and stays.
    var videoFailed      by remember { mutableStateOf(false) }
    var playHintNonce    by remember { mutableIntStateOf(0) }
    var playHintCounting by remember { mutableStateOf(false) }
    var showPlayHint     by remember { mutableStateOf(false) }
    val playHintTimer = remember { Animatable(1f) }
    LaunchedEffect(playHintNonce, playHintCounting) {
        if (playHintNonce == 0) return@LaunchedEffect
        showPlayHint = true
        playHintTimer.snapTo(1f)
        if (!playHintCounting) return@LaunchedEffect
        playHintTimer.animateTo(0f, tween(PANEL_PLAY_HINT_MS, easing = LinearEasing))
        showPlayHint = false
    }

    // Shown immediately rather than one frame later from the effect, or the
    // bar would blink out and back in as the trailer replaces the carousel.
    fun flashPlayHint(counting: Boolean) {
        playHintCounting = counting
        showPlayHint = true
        playHintNonce++
    }

    // ONE way out of a playing trailer, whether Back reached the BackHandler or
    // the overlay's own key handler, so both leave exactly the same state behind.
    // Guarded, so the second of two near-simultaneous paths is a no-op.
    fun closeVideo() {
        if (!isVideoVisible) return
        // Hold focus in the viewport immediately so it can't jump to search bar
        focusGuardRequester.requestFocus()
        isVideoVisible = false
        playingTrailer = null
        showPlayHint = false
        videoStarted = false
        videoFailed = false
        pendingReturnToCarousel = true
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
    BackHandler(enabled = isVideoVisible) { closeVideo() }

    // Back exits Selected -> Focused and returns focus to hitbox (only if video not visible)
    // Leaving Selected is a back-out: it undoes the press that entered, and the
    // panel visibly changes state (the shadow clears). Without it the only exit
    // from the carousel was silent while the way in spoke.
    //
    // NOT on the isVideoVisible handler above -- that one closes a PLAYING
    // trailer, where a sound would land on top of the audio it is interrupting.
    BackHandler(enabled = !isVideoVisible && trailerState is TrailerFrameState.Selected) {
        BlueHiveApplication.playBackOutSound()
        trailerState = TrailerFrameState.Focused
        hitboxFocusRequester.requestFocus()
    }

    // The focus frame turns gold while the panel is entered, so "on the panel"
    // and "inside it" look different at a glance. The frame art is near-white,
    // so a Modulate tint IS the tint colour, and white leaves it untouched.
    val frameTint by animateColorAsState(
        targetValue   = if (trailerState is TrailerFrameState.Selected) PANEL_HINT_GOLD else Color.White,
        animationSpec = tween(250),
        label         = "trailerFrameTint"
    )

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
            painter = painterResource(id = R.drawable.trailer_frame_shadow),
            contentDescription = "Trailer frame shadow",
            modifier = Modifier
                .offset(x = shadowX, y = shadowY)
                .width(shadowW)
                .height(shadowH)
                .graphicsLayer(alpha = if (trailerState is TrailerFrameState.Selected) 1f else 0f),
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
            raisedCaption = trailerState is TrailerFrameState.Selected,
            onTrailerClick = { trailer ->
                // NO LINK, NO PLAYER. The overlay below is skipped for a blank
                // URL, so opening it anyway left nothing to take focus, nothing
                // to report started/failed, and a panel stuck until Back.
                if (trailer.youtubeUrl.isNullOrBlank()) {
                    Log.w("TrailerSection", "Trailer ${trailer.id} has no video link; not opening")
                    return@HomeScreenTrailerNavigationCompose
                }
                // when user selects a trailer (Enter/Center), show video in the viewport
                playingTrailer = trailer
                isVideoVisible = true
                pendingShowVideo = true
                videoPaused = false
                videoStarted = false
                videoFailed = false
                flashPlayHint(counting = false)   // hold until the first frame
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
            onPlayPauseToggled = { playing ->
                videoPaused = !playing
                flashPlayHint(counting = videoStarted)
            },
            onStarted = {
                videoStarted = true
                flashPlayHint(counting = true)
            },
            // Held, not counted down: "Close" is the one thing left to do.
            onFailed = {
                videoFailed = true
                flashPlayHint(counting = false)
            },
            onClose = { closeVideo() },
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
                        // RIGHT lands on the trending frame, which announces
                        // nothing on arrival, so this side speaks for the move.
                        Key.DirectionRight -> {
                            BlueHiveApplication.playHoverSound()
                            false
                        }

                        // DOWN IS SILENT HERE ON PURPOSE -- it is the one
                        // direction that lands on something which DOES announce
                        // itself. `down` is wired to requesterFor(neighborOf(
                        // selectedTab)), which is always one of the three radio
                        // buttons, and each of those plays radioNavSound on
                        // focus gain.
                        //
                        // Speaking here as well played button_navigation.mp3
                        // TWICE for one press: playHoverSound and
                        // playNavClickSound are the same sample at the same
                        // volume, and they keep separate debounce clocks, so
                        // neither one swallowed the other. Audible as a stutter
                        // on a move people make constantly.
                        Key.DirectionDown -> false

                        // UP IS AN EDGE. This frame sits at the top of the
                        // content area with nothing above it; measured, focus
                        // does not move. The sound said "you went somewhere" to
                        // a viewer looking at an unchanged screen, which reads
                        // as the box having missed the press rather than as the
                        // press having nowhere to go.
                        //
                        // Consumed rather than passed through, so no directional
                        // search can quietly take focus somewhere off-screen-up
                        // instead.
                        Key.DirectionUp -> true

                        else -> false
                    }
                }
                .clickable(
                    enabled = !isVideoVisible,
                    interactionSource = hitboxInteraction,
                    indication = null
                ) {
                    if (trailerState !is TrailerFrameState.Selected) {
                        BlueHiveApplication.playClickSound()
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

        // Remote hints over the picture, last so they draw above the trailer
        // itself. Not focusable and handles no keys, so the focus model above
        // is untouched.
        val selected = trailerState is TrailerFrameState.Selected
        PanelHintLayer(
            compact      = if (trailerState is TrailerFrameState.Focused) "to browse" else null,
            legend       = if (isVideoVisible) (showPlayHint || videoFailed) else selected,
            legendBrowse = !isVideoVisible,
            legendClick  = when {
                isVideoVisible && videoFailed -> null
                isVideoVisible                -> if (videoPaused) "to play" else "to pause"
                else                          -> "to play"
            },
            legendExit   = if (isVideoVisible) "Close" else "Exit",
            arrows       = selected && !isVideoVisible,
            arrowScale   = 0.8f,   // 20% smaller than the trending panel's
            timer        = if (isVideoVisible && !videoFailed) ({ playHintTimer.value }) else null,
            modifier     = Modifier
                .offset(x = viewportX, y = viewportY)
                .width(viewportW)
                .height(viewportH)
        )
    }
}
