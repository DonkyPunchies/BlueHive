package com.example.bluehive.utilities

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.R
import kotlinx.coroutines.delay

// SHARED BY BOTH DETAILS SCREENS.
//
// Lifted out of TVShowsDetailsScreenCompose when the movies screen needed the
// same control. Copying ~150 lines of hand-tuned geometry would have guaranteed
// the two drifted -- the glow rim and the lip took a dozen rounds to settle, and
// a second copy is a second place for the next tweak to be forgotten.

// ── Browser button (bottom-right of the TV detail page) ──────────────────────
//
// Built the same way as ModularButton -- a darker SECOND surface offset
// downward, the main face on top of it, a soft shadow stack underneath -- and
// with THE SAME NUMBERS every other button on this page uses:
//
//     mainHeight 24dp   mainYOffset 0.5dp
//     secondHeight 22dp secondYOffset 7dp
//     corners 7f main / 6f second        (raw px, ModularButton's convention)
//
// so total height is 7 + 22 = 29dp, identical to Favourites, Extras, Play
// Trailer and the arrows. An earlier version invented its own 36dp face and a
// 5dp strip, which came to 41dp -- about 40% taller than its neighbours, which
// is what made it read as a different kind of control.
//
// SQUARE MEANS THE FACE. The face is 24x24; the second surface and shadow extend
// below it, so the composable reserves 29dp of height. Sizing the whole widget
// square would squash the face to make room for its own side.
//
// FOCUSABLE BUT NOT YET IN THE D-PAD GRAPH. This screen routes focus explicitly
// in places and nothing currently points AT this button; it will take focus if
// something hands it over, and is otherwise inert.
// WHAT SCALES AND WHAT DOES NOT.
//
// The FACE scales -- that is the "make it bigger" part. The LIP does not.
//
// The lip is the sliver of the second surface that shows below the face, and it
// is the entire 3D effect. Every other button on this page shows exactly 4.5dp
// of it (face 0.5..24.5, second 7..29). Scaling it with everything else pushed
// it to 6.75dp, and a lip half again too deep does not read as "bigger button",
// it reads as a gap -- the face looking like it is floating above its own side
// rather than sitting on it.
//
// So: face 36dp (1.5x), lip 4.5dp (fixed, matching its neighbours), total 41dp.
internal const val BROWSER_SCALE = 1.5f

internal val BROWSER_FACE          = 24.dp * BROWSER_SCALE   // square face, 36dp
internal val BROWSER_MAIN_Y        = 0.5.dp                  // face inset from top, as elsewhere
internal val BROWSER_LIP           = 4.5.dp                  // EXACTLY what every other button shows
internal val BROWSER_TOTAL_H       = BROWSER_MAIN_Y + BROWSER_FACE + BROWSER_LIP   // 41dp
internal val BROWSER_SECOND_Y      = 7.dp * BROWSER_SCALE    // hidden behind the face; only its bottom shows
internal val BROWSER_SECOND_H      = BROWSER_TOTAL_H - BROWSER_SECOND_Y            // ends exactly one lip below the face
internal val BROWSER_ICON          = 17.dp * BROWSER_SCALE   // 71% of the face

// FOCUS GLOW, sized off the trailer arrows (TVShowsRewindButton / FastForward).
//
// button_focus_narrow_glow IS NOT A BLOOM. It is a near-white PLATE -- 43% of it
// is fully opaque at (242,241,255), with only a ~6px soft edge. It works by
// sitting behind the button and being almost entirely COVERED by it, so all the
// viewer sees is a lit rim around the edges.
//
// Which means THE RIM IS THE GLOW, and the rim does not scale. The arrows wrap a
// 26.5x29dp body in a 41x44dp plate: about 7dp of rim. Scaling that overhang by
// 1.5x with the rest of the button gave an 11dp rim, and an 11dp rim of opaque
// white is not a halo, it is a white box with a small dark button sitting in it.
//
// Same overhang as the arrows, therefore. Same rim, at any BROWSER_SCALE.
/**
 * HOW BIG THE HALO IS, as rim thickness on each side of the button.
 *
 * These two numbers are the whole control surface. Width, height and position
 * are all derived, and the plate is centred by the LAYOUT (Alignment.Center on
 * a child that overflows its parent) rather than by an X/Y offset I work out by
 * hand -- doing that arithmetic myself is what left the button sitting against
 * the right edge of its own glow.
 *
 * The trailer arrows use about 7.25dp. This button is half again their size, so
 * a slightly heavier rim keeps the halo in proportion.
 */
internal val BROWSER_GLOW_RIM_X = 8.5.dp
internal val BROWSER_GLOW_RIM_Y = 8.dp

internal val BROWSER_GLOW_W = BROWSER_FACE    + BROWSER_GLOW_RIM_X * 2   // 53dp
internal val BROWSER_GLOW_H = BROWSER_TOTAL_H + BROWSER_GLOW_RIM_Y * 2   // 57dp

/**
 * Vertical nudge applied AFTER centring. Positive moves the halo down.
 *
 * Separate from BROWSER_GLOW_RIM_Y on purpose: the rim controls how big the
 * halo is, this controls where it sits. Folding the two together is what made
 * the earlier versions drift off-centre every time the size changed.
 */
internal val BROWSER_GLOW_NUDGE_Y = 5.dp

/** Press dip. NOT scaled: it is measured against the 4.5dp lip, which is also
 *  unscaled, and 2.5dp is the arrows' pressOffset. */
internal val BROWSER_PRESS_DIP = 2.5.dp
internal const val BROWSER_PRESS_MS      = 110    // travel, each way
internal const val BROWSER_PRESS_HOLD_MS = 80L    // time held down before bouncing back
internal const val BROWSER_GLOW_IN_MS    = 150
internal const val BROWSER_GLOW_OUT_MS   = 80
internal val BROWSER_BUTTON_MARGIN = 24.dp
internal val BROWSER_MAIN_CORNER_PX   = 7f * BROWSER_SCALE
internal val BROWSER_SECOND_CORNER_PX = 6f * BROWSER_SCALE
/**
 * How bright the glyph is when the button is NOT focused, as a fraction of white.
 *
 * Note this is brightness, not saturation -- the glyph is pure white, and white
 * has no saturation to remove. Scaling the RGB channels is what actually dims it;
 * dropping the alpha instead would let the grey face bleed through and muddy it.
 *
 * 0.70 keeps it at 5.36:1 against the idle face, still well clear of the 3:1
 * floor, so it stays legible while the focused state gets its contrast back.
 */
internal const val BROWSER_ICON_IDLE_DIM = 0.70f

internal val BROWSER_FACE_IDLE      = Color(0xFF3A3A3A)
internal val BROWSER_FACE_FOCUSED   = Color(0xFF5E5E5E)
internal val BROWSER_SECOND_IDLE    = Color(0xFF1F1F1F)
internal val BROWSER_SECOND_FOCUSED = Color(0xFF343434)

@Composable
fun BrowserCornerButton(
    focusRequester: FocusRequester,
    /**
     * Where focus goes when the viewer presses LEFT or UP off this button.
     *
     * It has to be handed in rather than searched for. The button sits alone in
     * the bottom-right corner, past the end of every row on the page, so
     * Compose's own directional focus search finds nothing in any direction --
     * without an explicit way out this is a focus trap, and the only escape
     * would be Back.
     *
     * Whatever routes focus INTO the button sets this to a lambda returning to
     * itself, so the button is always left the way it was entered.
     */
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val density = LocalDensity.current
    var isFocused by remember { mutableStateOf(false) }
    var isPressed by remember { mutableStateOf(false) }

    // FOCUS AND PRESS HAVE TO LOOK DIFFERENT.
    //
    // The face used to sink 3px on FOCUS. Sinking toward its own side is the one
    // thing this button does that reads as "pressed", so merely landing on it
    // looked like activating it. Every other button on the page changes colour
    // and lights a glow on focus, and sinks only on press -- so does this one
    // now, and the dip is driven by the press, nothing else.
    val pressDipPx = with(density) { BROWSER_PRESS_DIP.toPx() }
    val faceDrop by animateFloatAsState(
        targetValue   = if (isPressed) pressDipPx else 0f,
        animationSpec = tween(BROWSER_PRESS_MS),
        label         = "browserFaceDrop",
    )

    // Glyph dims when unfocused. Animated rather than switched so it rises with
    // the face colour instead of snapping a frame ahead of it.
    val iconTint by animateColorAsState(
        targetValue = if (isFocused) Color.White
                      else Color(BROWSER_ICON_IDLE_DIM, BROWSER_ICON_IDLE_DIM, BROWSER_ICON_IDLE_DIM, 1f),
        animationSpec = tween(BROWSER_GLOW_IN_MS),
        label = "browserIconTint",
    )

    // Glow drops out while the button is down, same as ModularButton: the halo
    // belongs to "you are here", not to "this is being pressed".
    val glowAlpha by animateFloatAsState(
        targetValue   = if (isFocused && !isPressed) 1f else 0f,
        animationSpec = tween(if (isPressed) BROWSER_GLOW_OUT_MS else BROWSER_GLOW_IN_MS),
        label         = "browserGlowAlpha",
    )

    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(BROWSER_PRESS_HOLD_MS)
            isPressed = false
        }
    }

    Box(
        // Sized to the BUTTON, not to the glow. The glow is a child that
        // deliberately overflows -- sizing this box to the halo instead would
        // move the button itself, because the call site pins this box's
        // bottom-right corner to the screen corner.
        modifier = modifier
            .size(width = BROWSER_FACE, height = BROWSER_TOTAL_H)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            // onKeyEvent BEFORE focusable, matching the episode tiles. Key events
            // bubble outward from the focus target, so a handler placed after
            // focusable() sits inside it and never sees them -- which would make
            // this button a genuine trap, with LEFT/UP dead as well as Enter.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        BlueHiveApplication.playClickSound()
                        isPressed = true
                        onClick()
                        true
                    }
                    // The two ways back to the page.
                    Key.DirectionLeft, Key.DirectionUp -> {
                        BlueHiveApplication.playHoverSound()
                        onExit()
                        true
                    }
                    // Bottom-right corner: nothing right of it, nothing below it.
                    // Consumed so holding the key does not repeat a nav sound
                    // against a wall.
                    Key.DirectionRight, Key.DirectionDown -> true
                    else -> false
                }
            }
            .focusable(),
    ) {
        // Declared first, so it draws behind the face, which covers all of it
        // except the rim. Shares the arrows' asset so the lit edge is the same
        // colour and thickness as every other focused button on the page.
        if (glowAlpha > 0f) {
            Image(
                painter            = painterResource(id = R.drawable.button_focus_narrow_glow),
                contentDescription = null,
                modifier           = Modifier
                    // Centred by the layout. Alignment.Center on a child that is
                    // BIGGER than its parent puts the overhang evenly on both
                    // sides, so the rim is equal by construction and there is no
                    // offset to keep in sync with the size.
                    .align(Alignment.Center)
                    // requiredSize, NOT size: the parent Box is fixed at 36x41dp
                    // and passes that down as a hard max. size() honours incoming
                    // constraints, so the plate was silently CLAMPED to the
                    // button's own size and only the offset moved it -- which is
                    // why it read as a second white square beside the button.
                    .requiredSize(width = BROWSER_GLOW_W, height = BROWSER_GLOW_H)
                    .offset(y = BROWSER_GLOW_NUDGE_Y)
                    .graphicsLayer { alpha = glowAlpha },
                contentScale = ContentScale.FillBounds,
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val w  = BROWSER_FACE.toPx()
                    val fh = BROWSER_FACE.toPx()
                    val sy = BROWSER_SECOND_Y.toPx()
                    val sh = BROWSER_SECOND_H.toPx()

                    // Drop shadow only while unfocused -- the glow takes over as
                    // the lift cue, exactly as ModularButton does it. Drawing both
                    // muddies the halo.
                    if (!isFocused) {
                        for (i in 1..3) {
                            val spread = i * 3f * BROWSER_SCALE
                            drawRoundRect(
                                color = Color(0xFF040404).copy(alpha = 0.22f - (i * 0.06f)),
                                topLeft = Offset(-spread / 2f, sy + (i * 2f * BROWSER_SCALE)),
                                size = Size(w + spread, sh),
                                cornerRadius = CornerRadius(
                                    BROWSER_SECOND_CORNER_PX + i * BROWSER_SCALE,
                                    BROWSER_SECOND_CORNER_PX + i * BROWSER_SCALE,
                                ),
                            )
                        }
                    }

                    drawRoundRect(
                        color = if (isFocused) BROWSER_SECOND_FOCUSED else BROWSER_SECOND_IDLE,
                        topLeft = Offset(0f, sy),
                        size = Size(w, sh),
                        cornerRadius = CornerRadius(BROWSER_SECOND_CORNER_PX, BROWSER_SECOND_CORNER_PX),
                    )

                    drawRoundRect(
                        color = if (isFocused) BROWSER_FACE_FOCUSED else BROWSER_FACE_IDLE,
                        topLeft = Offset(0f, BROWSER_MAIN_Y.toPx() + faceDrop),
                        size = Size(w, fh),
                        cornerRadius = CornerRadius(BROWSER_MAIN_CORNER_PX, BROWSER_MAIN_CORNER_PX),
                    )
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            // Centred on the FACE, not on the composable -- the strip below is
            // not part of the button's visual middle. Rides the press dip with
            // the face.
            Image(
                painter            = painterResource(id = R.drawable.browser),
                contentDescription = "Browser",
                // The asset's ink is pure black, which sat at 1.85:1 against the
                // idle face -- under the 3:1 floor for non-text UI, and the button
                // is idle almost all of the time. Tinting rather than shipping a
                // second white asset keeps one file on disk and one glyph to
                // maintain; the state change is carried by the FACE lightening
                // under it, so the glyph itself stays white throughout.
                colorFilter        = ColorFilter.tint(iconTint),
                modifier           = Modifier
                    .padding(top = BROWSER_MAIN_Y + (BROWSER_FACE - BROWSER_ICON) / 2)
                    .offset { IntOffset(0, faceDrop.toInt()) }
                    .size(BROWSER_ICON),
            )
        }
    }
}
