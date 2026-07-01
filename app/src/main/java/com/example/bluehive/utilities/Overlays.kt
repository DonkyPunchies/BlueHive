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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluehive.R
import kotlinx.coroutines.android.awaitFrame
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
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