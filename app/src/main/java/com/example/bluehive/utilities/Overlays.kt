package com.example.bluehive.utilities

// ─────────────────────────────────────────────────────────────────────────────
// Overlays.kt
//
// Central home for all overlay / dialog UI used across the app.
// Every visual property is broken into its own named function so any overlay
// can be customised — colors, button sizes, text, shape — without hunting
// through composable logic.
//
// Structure
// ─────────
//  1. Scrim colors
//  2. Dialog card specs (background, border, shape, padding, width)
//  3. Button color specs — one function per button variant
//  4. Button dimension spec — shared by all overlay buttons
//  5. Button glow spec     — shared by all overlay buttons
//  6. Button animation spec — shared by all overlay buttons
//  7. Composables — ConfirmationOverlay (the reusable two-button dialog)
// ─────────────────────────────────────────────────────────────────────────────

import androidx.activity.compose.BackHandler
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.Image
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluehive.R
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.example.bluehive.BlueHiveApplication
import androidx.compose.ui.graphics.Brush


// ─────────────────────────────────────────────────────────────────────────────
// 1. Scrim
//    The full-screen dark layer placed behind the dialog card.
// ─────────────────────────────────────────────────────────────────────────────

/** The semi-transparent black used to dim the content behind any overlay. */
fun overlayScrimColor(): Color = Color(0xCC000000)


// ─────────────────────────────────────────────────────────────────────────────
// 2. Dialog card
//    Background, border, shape, padding and width of the centered dialog box.
// ─────────────────────────────────────────────────────────────────────────────

/** Background fill of the dialog card. */
fun overlayCardBackgroundColor(): Color = Color(0xFF121213)

/** Vertical gradient fill of the dialog card — matches the search-bar gradient. */
fun overlayCardBackgroundBrush(): Brush = Brush.verticalGradient(
    listOf(Color(0xFF222222), Color(0xFF141414))   // same top/bottom as the search overlay
)

/** 1 dp border drawn around the dialog card. */
fun overlayCardBorderColor(): Color = Color(0xFF3A3737)

/** Corner radius of the dialog card. */
fun overlayCardShape(): RoundedCornerShape = RoundedCornerShape(12.dp)

/** Horizontal padding inside the dialog card. */
val OVERLAY_CARD_PADDING_HORIZONTAL = 32.dp

/** Vertical padding inside the dialog card. */
val OVERLAY_CARD_PADDING_VERTICAL = 24.dp

/** Width of the dialog card. Increase to fit longer messages. */
val OVERLAY_CARD_WIDTH = 360.dp


// ─────────────────────────────────────────────────────────────────────────────
// 3. Button color specs
//    One function per variant so changing a variant never affects others.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Colors for the red confirm/destructive button (left side).
 * Used for actions like "Yes, Close App", "Yes, Delete Profile", etc.
 */
fun overlayConfirmButtonColors(): ModularButtonColors = ModularButtonColors(
    // Dark crimson default — button is visually present but not loud when unfocused
    mainDefault          = Color(0xFF4A1010),
    // Brighter red when focused — clearly indicates a destructive action
    mainToggled          = Color(0xFF991B1B),
    mainFocused          = Color(0xFF991B1B),
    // Slightly darker layer that sits below the main surface
    secondDefault        = Color(0xFF330B0B),
    secondFocused        = Color(0xFF5C1414),
    // Text stays readable in both states
    textFocused          = Color(0xFFDADADA),
    textUnfocused        = Color(0xBAD78080),
    textUnfocusedToggled = Color(0xFF868686),
    textToggledFocus     = Color(0xFFDADADA),
)

/**
 * Colors for the blue cancel/safe button (right side).
 * Used for actions like "No, Stay", "No, Cancel Delete", etc.
 */
fun overlayCancelButtonColors(): ModularButtonColors = ModularButtonColors(
    // Dark navy default — neutral, non-alarming appearance when unfocused
    mainDefault          = Color(0xFF1E2854),
    // Bright blue when focused — safe, go-back action
    mainToggled          = Color(0xFF2644A6),
    mainFocused          = Color(0xFF2644A6),
    secondDefault        = Color(0xFF151C3A),
    secondFocused        = Color(0xFF282C57),
    textFocused          = Color(0xFFDADADA),
    textUnfocused        = Color(0xFF868686),
    textUnfocusedToggled = Color(0xFF868686),
    textToggledFocus     = Color(0xFFDADADA),
)


// ─────────────────────────────────────────────────────────────────────────────
// 4. Button dimension spec
//    Shared by every button inside an overlay dialog.
//    Adjust mainWidth / glowWidth together if button text is long.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Dimensions for overlay dialog buttons.
 * Both the confirm (red) and cancel (blue) button use these same dimensions.
 */
fun overlayButtonDimensions(): ModularButtonDimensions = ModularButtonDimensions(
    mainWidth          = 132.dp,
    mainHeight         = 27.dp,
    // Vertical offset of the raised face relative to the shadow slab
    mainYOffset        = 6.5.dp,
    secondWidth        = 132.dp,
    secondHeight       = 12.dp,
    // Vertical offset of the shadow slab
    secondYOffset      = 26.dp,
    mainCornerRadius   = 7f,
    secondCornerRadius = 8f,
    shadowHeight       = 12.dp,
    // Glow drawable is wider than the button to produce the bloom effect
    glowWidth          = 150.dp,
    glowHeight         = 41.dp,
)


// ─────────────────────────────────────────────────────────────────────────────
// 5. Button glow spec
//    Controls the focus glow drawable that appears behind a focused button.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Glow configuration for overlay dialog buttons.
 * Uses the standard wide glow drawable with a pill-shaped corner radius.
 */
fun overlayButtonGlowConfig(): ModularButtonGlowConfig = ModularButtonGlowConfig(
    enabled               = true,
    defaultRes            = R.drawable.button_focus_wide_glow,
    // Negative X offset centres the wider glow under the button
    offsetX               = (-9).dp,
    offsetY               = 7.dp,
    // Fully rounded corners on the glow so it reads as a soft bloom
    cornerRadius          = 100.dp,
    fadeOutDurationMillis = 200,
    fadeInDurationMillis  = 400,
)


// ─────────────────────────────────────────────────────────────────────────────
// 6. Button animation spec
//    Press travel distance and timing for the 3-D press animation.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Press animation config for overlay dialog buttons.
 * Matches the "snappy" feel used throughout the profile management screens.
 */
fun overlayButtonAnimationConfig(): ModularButtonAnimationConfig = ModularButtonAnimationConfig(
    // How far the button face travels downward on press (simulates depth)
    pressOffset           = 3.5.dp,
    // Resting text position relative to the button face
    textOffsetDefault     = 7.9.dp,
    // Text position while the button is held down
    textOffsetPressed     = 9.9.dp,
    durationMillis        = 110,
    bounceBackDelayMillis = 200,
)


// ─────────────────────────────────────────────────────────────────────────────
// 7. Composables
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ConfirmationOverlay
 *
 * A centered two-button confirmation dialog placed over a dark scrim.
 * The cancel (blue) button receives focus by default so that an accidental
 * confirm press is harder to trigger on a TV remote.
 *
 * @param message      Body text shown inside the dialog card.
 * @param confirmText  Label for the red confirm/destructive button (left).
 * @param cancelText   Label for the blue cancel/safe button (right).
 * @param onConfirm    Called when the user confirms.
 * @param onCancel     Called when the user cancels or dismisses.
 */
@Composable
fun ConfirmationOverlay(
    message:     String,
    confirmText: String,
    cancelText:  String,
    onConfirm:   () -> Unit,
    onCancel:    () -> Unit,
) {
    val confirmFocus = remember { FocusRequester() }
    val cancelFocus  = remember { FocusRequester() }

    // Default focus lands on the safe (cancel) button so a stray remote
    // click doesn't accidentally trigger the destructive action.
    LaunchedEffect(Unit) {
        awaitFrame()
        cancelFocus.requestFocus()
    }

    // ── Scrim ─────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(overlayScrimColor())
    )

    // ── Centered dialog card ──────────────────────────────────────────────────
    Box(
        contentAlignment = Alignment.Center,
        modifier         = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(
                    color = overlayCardBackgroundColor(),
                    shape = overlayCardShape(),
                )
                .border(
                    width = 1.dp,
                    color = overlayCardBorderColor(),
                    shape = overlayCardShape(),
                )
                .padding(
                    horizontal = OVERLAY_CARD_PADDING_HORIZONTAL,
                    vertical   = OVERLAY_CARD_PADDING_VERTICAL,
                )
                .width(OVERLAY_CARD_WIDTH),
        ) {

            // ── Message text ──────────────────────────────────────────────────
            Text(
                text       = message,
                color      = Color.White,
                fontFamily = AppTypography.interBold,
                fontWeight = FontWeight.Bold,
                fontSize   = 16.sp,
                textAlign  = TextAlign.Center,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            )

            // ── Buttons row ───────────────────────────────────────────────────
            // Arrangement.spacedBy with CenterHorizontally keeps both buttons
            // grouped together and centred as a unit inside the card.
            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    space     = 15.dp,
                    alignment = Alignment.CenterHorizontally,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {

                // Red confirm button — left
                ModularButton(
                    textConfig      = ModularButtonTextConfig(text = confirmText, fontSize = 10f),
                    isToggleable    = false,
                    fontFamily      = AppTypography.interBold,
                    focusRequester  = confirmFocus,
                    onClick         = onConfirm,
                    modifier        = Modifier.focusProperties {
                        up    = FocusRequester.Cancel
                        down  = FocusRequester.Cancel
                        left  = FocusRequester.Cancel
                        right = cancelFocus
                    },
                    dimensions      = overlayButtonDimensions(),
                    colors          = overlayConfirmButtonColors(),
                    glowConfig      = overlayButtonGlowConfig(),
                    animationConfig = overlayButtonAnimationConfig(),
                )

                // Blue cancel button — right
                ModularButton(
                    textConfig      = ModularButtonTextConfig(text = cancelText, fontSize = 10f),
                    isToggleable    = false,
                    fontFamily      = AppTypography.interBold,
                    focusRequester  = cancelFocus,
                    onClick         = onCancel,
                    modifier        = Modifier.focusProperties {
                        up    = FocusRequester.Cancel
                        down  = FocusRequester.Cancel
                        left  = confirmFocus
                        right = FocusRequester.Cancel
                    },
                    dimensions      = overlayButtonDimensions(),
                    colors          = overlayCancelButtonColors(),
                    glowConfig      = overlayButtonGlowConfig(),
                    animationConfig = overlayButtonAnimationConfig(),
                )
            }
        }
    }
}


/**
 * VideoQualityOverlay
 *
 * Centered video-quality picker over the standard dialog scrim + card, matching
 * ConfirmationOverlay's look (same background, border, shape, padding, font).
 * Each option is a focusable row with a radio marker; the currently-selected one
 * auto-focuses. Selecting a row fires onSelect(index). Dismissal (BACK) is handled
 * by the caller. Index 0 is "Auto".
 */
@Composable
fun VideoQualityOverlay(
    options:       List<String>,
    selectedIndex: Int,
    onSelect:      (Int) -> Unit,
) {
    // ── Scrim ───────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(overlayScrimColor())
    )

    // ── Centered dialog card ────────────────────────────────────────────────────
    Box(
        contentAlignment = Alignment.Center,
        modifier         = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .width(OVERLAY_CARD_WIDTH)
                .clip(overlayCardShape())
                .background(overlayCardBackgroundColor(), overlayCardShape())
                .border(1.dp, overlayCardBorderColor(), overlayCardShape())
                .padding(
                    horizontal = OVERLAY_CARD_PADDING_HORIZONTAL,
                    vertical   = OVERLAY_CARD_PADDING_VERTICAL,
                ),
        ) {
            Text(
                text       = "Video Quality",
                color      = Color.White,
                fontFamily = AppTypography.interBold,
                fontWeight = FontWeight.Bold,
                fontSize   = 16.sp,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 230.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEachIndexed { i, label ->
                    var focused by remember { mutableStateOf(false) }
                    val fr = remember { FocusRequester() }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (focused) Color(0xFF2644A6) else Color.Transparent)
                            .onFocusChanged { focused = it.isFocused }
                            .focusRequester(fr)
                            .clickable { onSelect(i) }   // clickable is already focusable — a second .focusable() ate the first press
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    ) {
                        Text(
                            text       = label,
                            color      = if (focused) Color.White else Color(0xFFB8B8B8),
                            fontFamily = AppTypography.interBold,
                            fontSize   = 14.sp,
                            modifier   = Modifier.weight(1f),
                        )
                        // Radio marker — filled when this row is the active quality.
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .border(
                                    width = 1.5.dp,
                                    color = if (i == selectedIndex) Color(0xFF6AA0FF) else Color(0xFF777777),
                                    shape = CircleShape,
                                ),
                        ) {
                            if (i == selectedIndex) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF6AA0FF)),
                                )
                            }
                        }
                    }
                    if (i == selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))) {
                        LaunchedEffect(Unit) { awaitFrame(); fr.requestFocus() }
                    }
                }
            }
        }
    }
}


/**
 * RetryOverlay
 *
 * Shown over the home screen when content couldn't load (device offline).
 * A single centered Retry button auto-focuses so the remote can act on it
 * immediately. Reuses the same card / scrim / button styling as
 * ConfirmationOverlay for visual consistency.
 *
 * @param message    Body text. Defaults to a generic connection message.
 * @param retryText  Label for the retry button.
 * @param onRetry    Called when the user presses Retry.
 */
@Composable
fun RetryOverlay(
    message:   String = "Couldn't load content.\nCheck your connection and try again.",
    retryText: String = "Retry",
    onRetry:   () -> Unit,
) {
    val retryFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        awaitFrame()
        retryFocus.requestFocus()
    }

    // ── Scrim ─────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(overlayScrimColor())
    )

    // ── Centered card with a single Retry button ───────────────────────────────
    Box(
        contentAlignment = Alignment.Center,
        modifier         = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(
                    color = overlayCardBackgroundColor(),
                    shape = overlayCardShape(),
                )
                .border(
                    width = 1.dp,
                    color = overlayCardBorderColor(),
                    shape = overlayCardShape(),
                )
                .padding(
                    horizontal = OVERLAY_CARD_PADDING_HORIZONTAL,
                    vertical   = OVERLAY_CARD_PADDING_VERTICAL,
                )
                .width(OVERLAY_CARD_WIDTH),
        ) {
            Text(
                text       = message,
                color      = Color.White,
                fontFamily = AppTypography.interBold,
                fontWeight = FontWeight.Bold,
                fontSize   = 16.sp,
                textAlign  = TextAlign.Center,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            )

            ModularButton(
                textConfig      = ModularButtonTextConfig(text = retryText, fontSize = 10f),
                isToggleable    = false,
                fontFamily      = AppTypography.interBold,
                focusRequester  = retryFocus,
                onClick         = onRetry,
                modifier        = Modifier.focusProperties {
                    up    = FocusRequester.Cancel
                    down  = FocusRequester.Cancel
                    left  = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                },
                dimensions      = overlayButtonDimensions(),
                colors          = overlayCancelButtonColors(),
                glowConfig      = overlayButtonGlowConfig(),
                animationConfig = overlayButtonAnimationConfig(),
            )
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// 8. DefaultServerPrompt
//    Instant Yes/No gate shown the moment a DuB/SuB button is pressed. No network
//    happens here — it's pure UI, so it pops up and dismisses immediately.
//    The "Default server" button is focused by default (most people want it).
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DefaultServerPrompt(
    message: String = "Watch on the default server,\nor choose one yourself?",
    defaultText: String = "Default server",
    chooseText: String = "Choose a server",
    onDefault: () -> Unit,
    onChoose: () -> Unit,
) {
    val defaultFocus = remember { FocusRequester() }
    val chooseFocus  = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        awaitFrame()
        defaultFocus.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(overlayScrimColor())
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(color = overlayCardBackgroundColor(), shape = overlayCardShape())
                .border(width = 1.dp, color = overlayCardBorderColor(), shape = overlayCardShape())
                .padding(
                    horizontal = OVERLAY_CARD_PADDING_HORIZONTAL,
                    vertical   = OVERLAY_CARD_PADDING_VERTICAL,
                )
                .width(OVERLAY_CARD_WIDTH),
        ) {
            Text(
                text = message,
                color = Color.White,
                fontFamily = AppTypography.interBold,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    space = 15.dp,
                    alignment = Alignment.CenterHorizontally,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // "Default server" — focused by default
                ModularButton(
                    textConfig = ModularButtonTextConfig(text = defaultText, fontSize = 10f),
                    isToggleable = false,
                    fontFamily = AppTypography.interBold,
                    focusRequester = defaultFocus,
                    onClick = onDefault,
                    modifier = Modifier.focusProperties {
                        up    = FocusRequester.Cancel
                        down  = FocusRequester.Cancel
                        left  = FocusRequester.Cancel
                        right = chooseFocus
                    },
                    dimensions = overlayButtonDimensions(),
                    colors = overlayCancelButtonColors(),
                    glowConfig = overlayButtonGlowConfig(),
                    animationConfig = overlayButtonAnimationConfig(),
                )

                // "Choose a server"
                ModularButton(
                    textConfig = ModularButtonTextConfig(text = chooseText, fontSize = 10f),
                    isToggleable = false,
                    fontFamily = AppTypography.interBold,
                    focusRequester = chooseFocus,
                    onClick = onChoose,
                    modifier = Modifier.focusProperties {
                        up    = FocusRequester.Cancel
                        down  = FocusRequester.Cancel
                        left  = defaultFocus
                        right = FocusRequester.Cancel
                    },
                    dimensions = overlayButtonDimensions(),
                    colors = overlayCancelButtonColors(),
                    glowConfig = overlayButtonGlowConfig(),
                    animationConfig = overlayButtonAnimationConfig(),
                )
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// 9. ServerPickerOverlay
//    Scrollable, D-pad-navigable list of the servers returned by ENUMERATE.
//    Uses a plain Column(verticalScroll) so every row stays composed (reliable
//    explicit focus wiring) and Compose auto-scrolls the focused row into view.
//    Back is handled by the host screen (BackHandler), not here.
// ─────────────────────────────────────────────────────────────────────────────

data class ServerOption(
    val name: String,
    val tags: List<String> = emptyList(),
)

@Composable
fun ServerPickerOverlay(
    servers: List<ServerOption>,
    defaultIndex: Int,
    audioLabel: String,                 // "Dub" or "Sub" — used in the title
    onSelect: (String) -> Unit,         // server name
) {
    val focusRequesters = remember(servers) { List(servers.size) { FocusRequester() } }
    val startIndex = remember(servers) {
        if (servers.isEmpty()) 0 else defaultIndex.coerceIn(0, servers.lastIndex)
    }
    val scrollState = rememberScrollState()

    LaunchedEffect(servers) {
        if (servers.isNotEmpty()) {
            awaitFrame()
            awaitFrame()   // let the rows lay out before grabbing focus
            try { focusRequesters[startIndex].requestFocus() } catch (_: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(overlayScrimColor())
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(color = overlayCardBackgroundColor(), shape = overlayCardShape())
                .border(width = 1.dp, color = overlayCardBorderColor(), shape = overlayCardShape())
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .width(300.dp),
        ) {
            Text(
                text = "Available $audioLabel servers",
                color = Color.White,
                fontFamily = AppTypography.interBold,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(scrollState),
            ) {
                servers.forEachIndexed { index, server ->
                    ServerRow(
                        server = server,
                        focusRequester = focusRequesters[index],
                        upTarget = if (index == 0) FocusRequester.Cancel
                        else focusRequesters[index - 1],
                        downTarget = if (index == servers.lastIndex) FocusRequester.Cancel
                        else focusRequesters[index + 1],
                        onSelect = { onSelect(server.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: ServerOption,
    focusRequester: FocusRequester,
    upTarget: FocusRequester,
    downTarget: FocusRequester,
    onSelect: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg     = if (focused) Color(0xFF2644A6) else Color(0xFF1B1B1D)
    val stroke = if (focused) Color(0xFF4F7BE0) else Color(0xFF333033)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .border(1.dp, stroke, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp)
            .focusRequester(focusRequester)
            .focusProperties {
                up    = upTarget
                down  = downTarget
                left  = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) BlueHiveApplication.playHoverSound()
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter ||
                            event.key == Key.Enter ||
                            event.key == Key.NumPadEnter)
                ) {
                    BlueHiveApplication.playClickSound()
                    onSelect()
                    true
                } else {
                    false
                }
            }
            .focusable(),
    ) {
        Text(
            text = server.name,
            color = if (focused) Color.White else Color(0xFFD6D6D6),
            fontFamily = AppTypography.interBold,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
        if (server.tags.isNotEmpty()) {
            Text(
                text = server.tags.joinToString(" · "),
                color = if (focused) Color(0xFFCFE0FF) else Color(0xFF8B8B8B),
                fontFamily = AppTypography.interBold,
                fontSize = 11.sp,
            )
        }
    }
}
/**
 * "Welcome back, <name>" — the profile-entry transition.
 *
 * SHARED BY TWO SCREENS ON PURPOSE, and that sharing is the whole point.
 *
 * MEASURED 2026-09-04 on the onn 4K box: pressing a profile fires
 * startActivity(HomeScreenCompose) as the press animation ends, but Android
 * keeps the OLD activity on screen until the new one draws its first frame —
 * and that frame costs 1355ms ("Displayed HomeScreenCompose: +1s355ms"). This
 * overlay lived only inside HomeScreenCompose, so for that whole second the
 * viewer sat looking at a frozen, shrunken, greyed-out profile card with no
 * indication anything was happening. The loading screen could not cover the
 * wait for itself.
 *
 * ProfileScreenActivity now raises this the instant the press animation
 * finishes, and HomeScreenCompose raises the identical thing when it finally
 * draws. Because both render the same composable, the handoff is invisible: one
 * continuous loading screen spanning two activities, rather than a dead second
 * followed by a loading screen.
 *
 * STILL CHEAP, AND THAT CONSTRAINT DID NOT RELAX WHEN THIS GOT PRETTIER. One of
 * the two places this renders IS HomeScreenCompose's first frame — the very
 * frame it exists to cover — so anything it does is paid for out of the wait it
 * is hiding. Hence: no bitmap anywhere. The comb is seven Paths of six points
 * each, recomputed per frame from two floats, which costs orders of magnitude
 * less than decoding a logo PNG on a 2GB box that is simultaneously inflating a
 * home screen. A spinner was the old answer to the same constraint; this is a
 * better answer to it, not a relaxation of it.
 */
@Composable
fun ProfileEntryOverlay(profileName: String) {
    // ── Comb palette ─────────────────────────────────────────────────────────
    //
    // A royal blue sitting between the app accent (378ADD) and a near-pure
    // electric blue (0D00FF). Both ends were tried on the panel and both were
    // wrong: the accent is a sky blue that goes weightless on black, and 0D00FF
    // at full chroma blooms on a large screen and posterises as it falls off.
    //
    // 2A4AD6 keeps enough green in the mix to stay a BLUE rather than drifting
    // violet, which is what made the electric version read as harsh, while still
    // carrying far more depth than the accent. The gradient does the rest: the
    // eye reads the range instead of any single value, so no one stop has to
    // carry the whole impression.
    val combBase = Color(0xFF2A4AD6)   // the colour the comb actually reads as
    val combLift = Color(0xFF7A90E8)   // top, so upper cells clear the black
    val combDeep = Color(0xFF121E5E)   // bottom, same hue well down in value
    val edgeLift = Color(0xFFA8BCF2)   // outlines run brighter than the fills,
    val edgeDeep = Color(0xFF2B3C9E)   // so the comb keeps its shape when lit
    val bg       = Color(0xFF0C0C0C)

    val motion = rememberInfiniteTransition(label = "profileEntry")

    // One phase drives the whole comb. Each cell reads it at its own offset, so
    // the wave costs a single animation rather than seven.
    val phase by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    // The core breathes on its own clock, slower than the wave, so the middle of
    // the comb never lands in perfect lockstep with the ring travelling round it.
    val core by motion.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "core",
    )

    Box(
        modifier = Modifier.fillMaxSize().background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {

            Box(contentAlignment = Alignment.Center) {
                // Halo. Kept faint on purpose: a TV panel bands dark blues badly,
                // and this only needs to lift the comb off the black.
                Box(
                    modifier = Modifier
                        .size(PROFILE_COMB_SIZE * 1.8f)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    combBase.copy(alpha = 0.16f * core),
                                    Color.Transparent,
                                ),
                            ),
                            shape = CircleShape,
                        )
                )

                Canvas(modifier = Modifier.size(PROFILE_COMB_SIZE)) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = size.width / 5.4f           // cell circumradius
                    val step = r * 1.732f               // centre-to-centre, pointy-top
                    val stroke = Stroke(width = r * 0.13f)

                    // HOISTED OUT OF THE CELL LOOP. Both brushes span the whole
                    // comb, so a cell's colour depends on where it sits in the
                    // cluster rather than on its own bounds — that is what makes
                    // this read as one object lit from above instead of seven
                    // separately-shaded tiles. Building them once per frame
                    // rather than seven times also keeps the allocation cost of
                    // this screen where the header promises it is.
                    val fillBrush = Brush.linearGradient(
                        colors = listOf(combLift, combBase, combDeep),
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                    )
                    val edgeBrush = Brush.linearGradient(
                        colors = listOf(edgeLift, combBase, edgeDeep),
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                    )

                    // Centre first, then the ring clockwise from the right.
                    for (i in 0..6) {
                        val centre = if (i == 0) {
                            Offset(cx, cy)
                        } else {
                            val a = ((i - 1) * 60f) * PI_OVER_180
                            Offset(cx + step * cos(a), cy + step * sin(a))
                        }

                        // Cell brightness. The centre holds steady and breathes;
                        // the ring runs a comet — sharp rise, slow decay — so the
                        // comb reads as filling rather than blinking.
                        val lit = if (i == 0) {
                            core
                        } else {
                            val t = (((phase - (i - 1) * 0.14f) % 1f) + 1f) % 1f
                            val decay = 1f - t
                            0.18f + 0.82f * decay * decay * decay
                        }

                        // Brush for the colour, alpha for the animation. Keeping
                        // those separate is what lets one gradient serve every
                        // cell while each still pulses on its own clock.
                        val path = hexPath(centre, r)
                        drawPath(path, brush = fillBrush, alpha = 0.14f + 0.34f * lit)
                        drawPath(
                            path,
                            brush = edgeBrush,
                            alpha = 0.24f + 0.76f * lit,
                            style = stroke,
                        )
                    }
                }
            }

            Spacer(Modifier.height(38.dp))

            // PERSONAL FIRST, STATUS SECOND. The old copy led with the machine's
            // job ("Getting X's profile ready") and buried the person in the
            // middle of it. Greeting them first is the whole difference between a
            // progress message and a welcome.
            Text(
                text = if (profileName.isNotBlank()) "Welcome back, $profileName" else "Welcome back",
                color = Color(0xFFF5F5F2),
                fontSize = 30.sp,
                fontFamily = AppTypography.interBold,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "Setting up your space",
                color = Color(0xFF9A9AA2),
                fontSize = 14.sp,
                fontFamily = AppTypography.interSemiBold,
                letterSpacing = 0.4.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Diameter of the honeycomb cluster. */
private val PROFILE_COMB_SIZE = 132.dp

private const val PI_OVER_180 = 0.017453292f

/**
 * One pointy-top hexagon.
 *
 * Vertices start at -90 degrees so a point sits at the top; in screen space y
 * grows downward, which is why that reads as "up" rather than 90.
 */
private fun hexPath(centre: Offset, radius: Float): Path = Path().apply {
    for (v in 0..5) {
        val a = (60f * v - 90f) * PI_OVER_180
        val x = centre.x + radius * cos(a)
        val y = centre.y + radius * sin(a)
        if (v == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}


// ─────────────────────────────────────────────────────────────────────────────
// 9. PlaybackSettingsOverlay
//    Reached from the browser button in the corner of a details screen. Controls
//    whether picking a streaming source raises the "our player, or the site?"
//    prompt (DefaultServerPrompt, above) or goes straight to the built-in player.
//
//    WHY THIS EXISTS. That prompt is correct the first few times and tiresome
//    after that: most viewers pick the same answer every time, and it stands
//    between them and playback on every single episode. Rather than removing it
//    — the site path is a genuine fallback when extraction fails — this lets a
//    viewer turn it off once.
//
//    Two buttons rather than a checkbox: on a remote, "which of these two" is
//    one press, while a checkbox is a press plus working out what state it
//    ended up in. Each button names its OUTCOME, not "on"/"off", so neither
//    needs the label read twice.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PlaybackSettingsOverlay(
    askEachTime: Boolean,
    onChoose: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val askFocus    = remember { FocusRequester() }
    val directFocus = remember { FocusRequester() }

    // Focus lands on the setting that is CURRENTLY active, so the overlay opens
    // showing the viewer where they already are. Confirming without reading is
    // then a no-op rather than a silent change of behaviour.
    LaunchedEffect(Unit) {
        awaitFrame()
        if (askEachTime) askFocus.requestFocus() else directFocus.requestFocus()
    }

    BackHandler(enabled = true) { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(overlayScrimColor())
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(color = overlayCardBackgroundColor(), shape = overlayCardShape())
                .border(width = 1.dp, color = overlayCardBorderColor(), shape = overlayCardShape())
                .padding(
                    horizontal = OVERLAY_CARD_PADDING_HORIZONTAL,
                    vertical   = OVERLAY_CARD_PADDING_VERTICAL,
                )
                .width(OVERLAY_CARD_WIDTH),
        ) {
            Text(
                text = "Playback method",
                color = Color.White,
                fontFamily = AppTypography.interBold,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Some sources can be played in the built-in player or " +
                       "opened directly on the streaming site. Choose whether " +
                       "to be asked each time, or to go straight to the " +
                       "built-in player.",
                color = Color(0xFFB8B8C0),
                fontFamily = AppTypography.interSemiBold,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 20.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    space = 15.dp,
                    alignment = Alignment.CenterHorizontally,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ModularButton(
                    textConfig = ModularButtonTextConfig(text = "Ask each time", fontSize = 10f),
                    isToggleable = false,
                    fontFamily = AppTypography.interBold,
                    focusRequester = askFocus,
                    onClick = { onChoose(true) },
                    modifier = Modifier.focusProperties {
                        up    = FocusRequester.Cancel
                        down  = FocusRequester.Cancel
                        left  = FocusRequester.Cancel
                        right = directFocus
                    },
                    dimensions = overlayButtonDimensions(),
                    colors = overlayCancelButtonColors(),
                    glowConfig = overlayButtonGlowConfig(),
                    animationConfig = overlayButtonAnimationConfig(),
                )

                ModularButton(
                    textConfig = ModularButtonTextConfig(text = "Built-in player", fontSize = 10f),
                    isToggleable = false,
                    fontFamily = AppTypography.interBold,
                    focusRequester = directFocus,
                    onClick = { onChoose(false) },
                    modifier = Modifier.focusProperties {
                        up    = FocusRequester.Cancel
                        down  = FocusRequester.Cancel
                        left  = askFocus
                        right = FocusRequester.Cancel
                    },
                    dimensions = overlayButtonDimensions(),
                    colors = overlayCancelButtonColors(),
                    glowConfig = overlayButtonGlowConfig(),
                    animationConfig = overlayButtonAnimationConfig(),
                )
            }

            // Current state, spelled out. The two buttons say what WILL happen;
            // without this there is nothing saying which one is already in force,
            // and focus position alone is too subtle to carry it.
            Text(
                text = if (askEachTime) "Currently: asking each time"
                       else            "Currently: using the built-in player",
                color = Color(0xFF8A8A93),
                fontFamily = AppTypography.interSemiBold,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// 10. UpdateRequiredOverlay
//     The consent gate shown BEFORE a byte of the update is downloaded.
//
//     WHY A GATE AT ALL. The updater used to open straight onto a progress bar,
//     so the first thing a user learned about an update was that one was already
//     happening to them. On the home-screen path that arrived on top of a screen
//     they were using, which reads as the app breaking rather than the app
//     maintaining itself. Asking first costs one press and turns the same event
//     into something the user agreed to.
//
//     NO "LATER" BUTTON, DELIBERATELY. The two outcomes are accept or leave,
//     because the build the operator has published is the only build the backend
//     is being kept compatible with. A third button offering to postpone would be
//     offering something the app cannot honour.
//
//     Specs are broken out into their own functions, matching the rest of this
//     file, so the card can be re-proportioned without touching the composable.
// ─────────────────────────────────────────────────────────────────────────────

/** Width of the update card. Wider than [OVERLAY_CARD_WIDTH] — it carries prose. */
val UPDATE_CARD_WIDTH = 500.dp

/** Horizontal padding inside the update card. */
val UPDATE_CARD_PADDING_HORIZONTAL = 40.dp

/** Vertical padding inside the update card. */
val UPDATE_CARD_PADDING_VERTICAL = 26.dp

/** Rendered size of the BlueHive mark at the top of the card. */
val UPDATE_LOGO_SIZE = 84.dp

/** Hairline rule between the heading and the body copy. */
fun updateDividerColor(): Color = Color(0xFF2E2C2C)

/** Body copy colour — softer than the heading so the hierarchy reads at 10 feet. */
fun updateBodyTextColor(): Color = Color(0xFFB8B8C0)

/** The version line and the closing note. Quietest text on the card. */
fun updateFootnoteTextColor(): Color = Color(0xFF8A8A93)

/**
 * Wider than [overlayButtonDimensions] so "Close Application" fits on one line
 * without dropping the font size below what is readable from a sofa.
 */
fun updateButtonDimensions(): ModularButtonDimensions = ModularButtonDimensions(
    mainWidth          = 152.dp,
    mainHeight         = 27.dp,
    mainYOffset        = 6.5.dp,
    secondWidth        = 152.dp,
    secondHeight       = 12.dp,
    secondYOffset      = 26.dp,
    mainCornerRadius   = 7f,
    secondCornerRadius = 8f,
    shadowHeight       = 12.dp,
    glowWidth          = 170.dp,
    glowHeight         = 41.dp,
)

/** Glow spec for the update buttons. Re-centred for the wider face. */
fun updateButtonGlowConfig(): ModularButtonGlowConfig = ModularButtonGlowConfig(
    enabled               = true,
    defaultRes            = R.drawable.button_focus_wide_glow,
    offsetX               = (-9).dp,
    offsetY               = 7.dp,
    cornerRadius          = 100.dp,
    fadeOutDurationMillis = 200,
    fadeInDurationMillis  = 400,
)

/**
 * UpdateRequiredOverlay
 *
 * @param currentVersion  versionName of the build that is running now.
 * @param newVersion      versionName the update server is offering.
 * @param onAccept        begin the update. The caller owns permission + download.
 * @param onClose         leave the app. Nothing is downloaded.
 *
 * Focus lands on Accept, which is the opposite of [ConfirmationOverlay]'s rule
 * and intentionally so. There the default had to be the harmless button because
 * the other one deleted something; here the harmless button is the one that
 * proceeds, and the press worth guarding against is the one that closes the app.
 */
@Composable
fun UpdateRequiredOverlay(
    currentVersion: String,
    newVersion:     String,
    onAccept:       () -> Unit,
    onClose:        () -> Unit,
) {
    val acceptFocus = remember { FocusRequester() }
    val closeFocus  = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        awaitFrame()
        acceptFocus.requestFocus()
    }

    // BACK MEANS THE SAME THING AS "CLOSE APPLICATION", because on this screen
    // those are the same intent. Back is how you leave, and the only way to
    // leave this gate is to stop using the app until you take the update.
    //
    // It used to be swallowed, on the reasoning that Back must not dismiss a
    // gate the user has not answered. That is still true -- this does not
    // dismiss anything, it takes the decline branch explicitly -- but a button
    // that does nothing at all reads as the remote being broken, which is worse
    // than either real outcome.
    //
    // ONLY WHILE THIS OVERLAY IS COMPOSED. SelfUpdateActivity keeps its own
    // swallow-everything callback for the download and install phase, and this
    // one is registered later, so it wins while it exists and hands Back back to
    // the activity the moment the user accepts. Pressing Back mid-install still
    // does nothing, which is what you want with an install in flight.
    BackHandler(enabled = true) { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(overlayScrimColor())
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(brush = overlayCardBackgroundBrush(), shape = overlayCardShape())
                .border(width = 1.dp, color = overlayCardBorderColor(), shape = overlayCardShape())
                .padding(
                    horizontal = UPDATE_CARD_PADDING_HORIZONTAL,
                    vertical   = UPDATE_CARD_PADDING_VERTICAL,
                )
                .width(UPDATE_CARD_WIDTH),
        ) {

            // ── Mark ──────────────────────────────────────────────────────────
            // painterResource, not the BitmapFactory path BeeLogo uses. That
            // cache exists because the extraction overlay appears at a memory
            // peak on 2 GB boxes; this screen appears at launch or on an idle
            // home screen, where the ordinary resource loader is fine.
            Image(
                painter            = painterResource(R.drawable.blue_bee),
                contentDescription = null,
                contentScale       = ContentScale.Fit,
                modifier           = Modifier.size(UPDATE_LOGO_SIZE),
            )

            // ── Heading ───────────────────────────────────────────────────────
            Text(
                text       = "A new version of BlueHive is ready",
                color      = Color.White,
                fontFamily = AppTypography.interBold,
                fontWeight = FontWeight.Bold,
                fontSize   = 18.sp,
                lineHeight = 24.sp,
                textAlign  = TextAlign.Center,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )

            // ── Rule ──────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .padding(top = 14.dp, bottom = 14.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(updateDividerColor())
            )

            // ── Body ──────────────────────────────────────────────────────────
            // Says what is being asked, why, and what happens next. The line
            // about the confirmation screen is not filler: Android raises its own
            // install dialog over the top of this app, and someone who is not
            // expecting it tends to dismiss it and end up back where they
            // started with no idea what went wrong.
            //
            // KEPT SHORT ON PURPOSE, and the copy is load-bearing on the layout.
            // An Android TV viewport is 960x540 dp whatever the panel's pixel
            // count, so this card has ~540 dp of height to live in and no
            // scrolling to fall back on. At [UPDATE_CARD_WIDTH] each of these
            // paragraphs wraps to three lines and the whole card stacks to about
            // 420 dp. Adding a sentence is not free — it is 19 dp off the margin.
            Text(
                text = "This release is required to continue using BlueHive. It keeps " +
                       "your device in step with our servers, so sign-in, your profiles " +
                       "and your library all keep working as they should.",
                color      = updateBodyTextColor(),
                fontFamily = AppTypography.interSemiBold,
                fontSize   = 12.sp,
                lineHeight = 19.sp,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth(),
            )

            // HEDGED ON PURPOSE. On API 31+ the system confirm dialog is now
            // suppressed (see UpdateManager.install), so promising it outright
            // would describe a screen most devices never show. It can still
            // appear on an older box, or if the platform declines the request,
            // so the copy prepares for it without depending on it.
            Text(
                text = "The download takes only a moment, then BlueHive will close while " +
                       "the update installs. If your device asks you to confirm, please accept.",
                color      = updateBodyTextColor(),
                fontFamily = AppTypography.interSemiBold,
                fontSize   = 12.sp,
                lineHeight = 19.sp,
                textAlign  = TextAlign.Center,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )

            Text(
                text       = "Thank you for your patience.",
                color      = updateBodyTextColor(),
                fontFamily = AppTypography.interSemiBold,
                fontSize   = 12.sp,
                lineHeight = 19.sp,
                textAlign  = TextAlign.Center,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )

            // ── Versions ──────────────────────────────────────────────────────
            Text(
                text       = "Version $currentVersion  →  Version $newVersion",
                color      = updateFootnoteTextColor(),
                fontFamily = AppTypography.interBold,
                fontSize   = 11.sp,
                textAlign  = TextAlign.Center,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 20.dp),
            )

            // ── Buttons ───────────────────────────────────────────────────────
            // Red on the left, blue on the right, matching every other two-button
            // overlay in this file so the colour keeps meaning the same thing.
            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    space     = 15.dp,
                    alignment = Alignment.CenterHorizontally,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ModularButton(
                    textConfig      = ModularButtonTextConfig(text = "Close Application", fontSize = 10f),
                    isToggleable    = false,
                    fontFamily      = AppTypography.interBold,
                    focusRequester  = closeFocus,
                    onClick         = onClose,
                    modifier        = Modifier.focusProperties {
                        up    = FocusRequester.Cancel
                        down  = FocusRequester.Cancel
                        left  = FocusRequester.Cancel
                        right = acceptFocus
                    },
                    dimensions      = updateButtonDimensions(),
                    colors          = overlayConfirmButtonColors(),
                    glowConfig      = updateButtonGlowConfig(),
                    animationConfig = overlayButtonAnimationConfig(),
                )

                ModularButton(
                    textConfig      = ModularButtonTextConfig(text = "Accept Update", fontSize = 10f),
                    isToggleable    = false,
                    fontFamily      = AppTypography.interBold,
                    focusRequester  = acceptFocus,
                    onClick         = onAccept,
                    modifier        = Modifier.focusProperties {
                        up    = FocusRequester.Cancel
                        down  = FocusRequester.Cancel
                        left  = closeFocus
                        right = FocusRequester.Cancel
                    },
                    dimensions      = updateButtonDimensions(),
                    colors          = overlayCancelButtonColors(),
                    glowConfig      = updateButtonGlowConfig(),
                    animationConfig = overlayButtonAnimationConfig(),
                )
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// 8. Notice overlay
//    The app-wide "this went wrong, here is why, press OK" dialog.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Which kind of notice is on screen. Only the accents move — card, typography,
 * button and spacing are shared, so an error and a plain confirmation of fact
 * read as the same family seen twice rather than two different dialogs.
 */
enum class NoticeTone { Error, Info }

/**
 * One notice, as a value a screen can hold in state and hand to [NoticeOverlay].
 *
 * WHY A TYPE AND NOT THREE PARAMETERS. The message usually has to be composed
 * somewhere other than where it is drawn — from an error body, from a quota
 * response, from a failed precondition two functions up. Passing it as one value
 * keeps that composition at the point that has the facts, lets two screens share
 * the same wording, and makes `null` the natural way to say "nothing to show".
 */
data class NoticeContent(
    val title:   String,
    val message: String?    = null,
    val tone:    NoticeTone = NoticeTone.Error,
)

/**
 * Card fill for a notice.
 *
 * DELIBERATELY NOT [overlayCardBackgroundColor]. A notice interrupts — it
 * appears because something the user just tried did not happen — while the
 * confirmation dialogs are something they asked for. Two shades apart is enough
 * for the eye to register "different kind of thing" before a word is read, and
 * close enough that it is plainly the same app.
 */
fun noticeCardBackgroundColor(): Color = Color(0xFF191920)

/** Border, tinted by tone. The standard card uses a flat 0xFF3A3737. */
fun noticeCardBorderColor(tone: NoticeTone): Color = when (tone) {
    NoticeTone.Error -> Color(0xFF5E3435)
    NoticeTone.Info  -> Color(0xFF34405E)
}

/** Title colour and the short rule under it. */
fun noticeAccentColor(tone: NoticeTone): Color = when (tone) {
    NoticeTone.Error -> Color(0xFFFF5A52)
    NoticeTone.Info  -> Color(0xFF7FA6FF)
}

/** Body copy. Same grey as the update card's body — one voice for prose. */
fun noticeBodyTextColor(): Color = Color(0xFFB8B8C0)

/**
 * Wider than [OVERLAY_CARD_WIDTH]'s 360.
 *
 * A confirmation asks one short question; a notice often has to explain a rule,
 * and at 360dp that explanation stacks into five or six cramped lines. 430 of
 * the 960dp design space holds about nine words a line at 11sp and still leaves
 * a wide margin on either side of a television.
 */
val NOTICE_CARD_WIDTH = 430.dp


/**
 * NoticeOverlay
 *
 * One dismissible message, used anywhere in the app. Title, optional body, and
 * a single button the user presses to say they have read it.
 *
 * NOTHING HERE DISAPPEARS ON A TIMER, on purpose. The overlays this replaces
 * faded after four seconds, which sets up a race between reading and
 * comprehending: miss it and the only way back to the text is to repeat the
 * action that failed. A rule worth explaining is worth leaving on screen until
 * the person says they are done with it.
 *
 * IT IS A TRUE MODAL. Three things keep the screen underneath inert while it is
 * up, and all three are needed — a Compose overlay drawn inside another
 * screen's tree does not get focus merely by being painted on top of it:
 *   1. the button asks for focus for several frames, because the screen behind
 *      may re-assert its own focus on the same frame this appears;
 *   2. the button's four directions are [FocusRequester.Cancel], so a D-pad
 *      press cannot walk the focus search out into the content below;
 *   3. the root swallows every key it does not use, so even if focus somehow
 *      sits elsewhere in this subtree, nothing reaches what is behind.
 * BACK is deliberately let through to the [BackHandler], which dismisses.
 *
 * @param title      The headline. One line, said plainly.
 * @param message    The rule, the cause, or what to do about it. Optional.
 * @param tone       Accent colours. [NoticeTone.Error] by default.
 * @param buttonText Label of the acknowledge button.
 * @param onDismiss  Called on OK and on BACK. Clear the caller's state here.
 */
@Composable
fun NoticeOverlay(
    title:      String,
    message:    String?    = null,
    tone:       NoticeTone = NoticeTone.Error,
    buttonText: String     = "OK, Understood",
    onDismiss:  () -> Unit,
) {
    val ok = remember { FocusRequester() }

    // GUARD 4 — a select KeyUp only dismisses if THIS overlay saw its KeyDown.
    //
    // WITHOUT IT THE OVERLAY EATS THE RELEASE OF THE PRESS THAT OPENED IT.
    // Measured on the profile screen: ProfileButtonBase fires its onClick
    // inline on KeyDown (ProfileUtilities.kt) and returns true, leaving the
    // KeyUp unconsumed; the caller sets its notice state in that same dispatch;
    // this composable mounts on the next frame and GUARD 1 pulls focus in
    // within one or two more. The release of that same physical press — 60 to
    // 200ms later, for any human — then arrives HERE, and a handler that
    // dismissed on a bare KeyUp read it as the acknowledgement. The notice was
    // on screen for the length of a button press.
    //
    // It is the shape Modifier.clickable itself uses: arm on the down, act on
    // the up. A plain holder rather than mutableStateOf because arming must not
    // schedule a recomposition.
    val selectArmed = remember { booleanArrayOf(false) }

    // GUARD 1 — win the focus race, then check once more after it settles.
    LaunchedEffect(Unit) {
        repeat(NOTICE_FOCUS_ATTEMPTS) {
            if (noticeTakeFocus(ok)) return@LaunchedEffect
            withFrameNanos { }
        }
        delay(NOTICE_FOCUS_SETTLE_MS)
        noticeTakeFocus(ok)
    }

    BackHandler(enabled = true) { onDismiss() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(overlayScrimColor())
            .focusGroup()
            // GUARD 3 — swallow everything. BACK and ESCAPE pass through so the
            // BackHandler above sees them; OK/ENTER dismisses from anywhere,
            // but only a press that BEGAN while this overlay was up (GUARD 4).
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back || e.key == Key.Escape) return@onPreviewKeyEvent false
                val select = e.key == Key.Enter || e.key == Key.DirectionCenter ||
                        e.key == Key.NumPadEnter
                if (select) {
                    when (e.type) {
                        // repeatCount 0 only. Android re-sends ACTION_DOWN for a
                        // key held past ~400ms, and counting those would arm the
                        // latch from the opening press after all — the exact hole
                        // this guard exists to close, reopened on long holds.
                        KeyEventType.KeyDown ->
                            if (e.nativeKeyEvent.repeatCount == 0) selectArmed[0] = true
                        KeyEventType.KeyUp ->
                            if (selectArmed[0]) {
                                selectArmed[0] = false
                                onDismiss()
                            }
                        else -> {}
                    }
                }
                true
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(color = noticeCardBackgroundColor(), shape = overlayCardShape())
                .border(1.dp, noticeCardBorderColor(tone), overlayCardShape())
                .padding(
                    horizontal = OVERLAY_CARD_PADDING_HORIZONTAL,
                    vertical   = OVERLAY_CARD_PADDING_VERTICAL,
                )
                .width(NOTICE_CARD_WIDTH),
        ) {

            Text(
                text       = title,
                color      = noticeAccentColor(tone),
                fontFamily = AppTypography.interBold,
                fontWeight = FontWeight.Bold,
                fontSize   = 16.sp,
                lineHeight = 21.sp,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth(),
            )

            // A short rule rather than a full-width divider: it reads as an
            // underline belonging to the title, not as a section break.
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .width(54.dp)
                    .height(2.dp)
                    .background(
                        noticeAccentColor(tone).copy(alpha = 0.55f),
                        RoundedCornerShape(1.dp),
                    )
            )

            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                // Inter at 11sp, not the condensed display face: this is a
                // paragraph read from a sofa, not a headline.
                Text(
                    text       = message,
                    color      = noticeBodyTextColor(),
                    fontFamily = AppTypography.interSemiBold,
                    fontSize   = 11.sp,
                    lineHeight = 17.sp,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(22.dp))

            ModularButton(
                textConfig      = ModularButtonTextConfig(text = buttonText, fontSize = 10f),
                isToggleable    = false,
                fontFamily      = AppTypography.interBold,
                focusRequester  = ok,
                onClick         = onDismiss,
                // GUARD 2 — nowhere to go. Cancel ends the focus search instead
                // of letting it reach the screen behind the scrim.
                modifier        = Modifier.focusProperties {
                    up    = FocusRequester.Cancel
                    down  = FocusRequester.Cancel
                    left  = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                },
                dimensions      = overlayButtonDimensions(),
                colors          = overlayCancelButtonColors(),
                glowConfig      = overlayButtonGlowConfig(),
                animationConfig = overlayButtonAnimationConfig(),
            )
        }
    }
}

private const val NOTICE_FOCUS_ATTEMPTS  = 6
private const val NOTICE_FOCUS_SETTLE_MS = 150L

/** requestFocus() returns Unit and no-ops when the target is detached rather
 *  than throwing, so use the overload that reports whether focus actually moved. */
@OptIn(ExperimentalComposeUiApi::class)
private fun noticeTakeFocus(requester: FocusRequester): Boolean =
    runCatching { requester.requestFocus(FocusDirection.Enter) }.getOrDefault(false)


/** [NoticeOverlay] taking a prepared [NoticeContent]. The usual call shape:
 *  `notice?.let { NoticeOverlay(it) { notice = null } }`. */
@Composable
fun NoticeOverlay(
    content:    NoticeContent,
    buttonText: String = "OK, Understood",
    onDismiss:  () -> Unit,
) = NoticeOverlay(
    title      = content.title,
    message    = content.message,
    tone       = content.tone,
    buttonText = buttonText,
    onDismiss  = onDismiss,
)
