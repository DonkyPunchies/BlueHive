package com.example.bluehive.utilities

import android.util.Log
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluehive.BlueHiveApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer

private const val TAG = "ModularButton"

@Immutable
data class ModularButtonDimensions(
    val mainWidth: Dp = 162.dp,
    val mainHeight: Dp = 23.5.dp,
    val mainYOffset: Dp = 0.dp,
    val secondWidth: Dp = 162.dp,
    val secondHeight: Dp = 22.dp,
    val secondYOffset: Dp = 7.dp,
    val shadowHeight: Dp = 6.dp,
    val glowWidth: Dp = 203.dp,
    val glowHeight: Dp = 58.5.dp,
    val mainCornerRadius: Float = 8f,
    val secondCornerRadius: Float = 6f,
    val shadowOffset: Dp = 0.dp
)

@Stable
data class ModularButtonColors(
    val mainDefault: Color = Color(0xFF6C6C6C),
    val mainToggled: Color = Color(0xFF535C67),
    val mainFocused: Color = Color(0xFF535C67),
    val secondDefault: Color = Color(0xFF2E2C37),
    val secondFocused: Color = Color(0xFF52505A),
    val secondToggled: Color = Color(0xFF372B67),
    val shadowColor: Color = Color(0x50000000),
    val textFocused: Color = Color.White,
    val textUnfocused: Color = Color(0xFF8A8F97),
    val textUnfocusedToggled: Color = Color(0xFF918181),
    // Used when toggleable & toggled (unfocused)
    val textToggled: Color = Color(0xFF8A8F97),
    // ⭐ NEW: used when toggleable & toggled & focused (glow on it)
    val textToggledFocus: Color = Color(0xFFCDCDCD)
)

// ── The "watch" button palettes ─────────────────────────────────────────────
//
// WHAT EACH LAYER IS, looking at one button:
//
//   main    the face of the button, the part carrying the label
//   second  the thin strip peeking out underneath it
//   shadow  the soft drop under the whole thing
//   text    the label
//
// and each has a Default (idle) and a Focused (D-pad on it) value. Toggled is
// unused by these three — none of them is a toggle.
//
// ALL THREE SHARED ONE BUG. Every call site set mainDefault and none set
// mainFocused, so focusing any of them fell back to this file's neutral default
// (0xFF535C67) and a blue, red or gold button turned GREY under the cursor.
//
// FOCUS NOW BRIGHTENS RATHER THAN DULLING. Each focused value is its own idle
// hue lifted in lightness, so focus reads as the button lighting up rather than
// becoming a different button. Several secondFocused values previously went
// DARKER than their idle counterpart, fighting the glow instead of supporting it.

/**
 * "Watch in DuB" — the red anime button on both detail screens.
 *
 * Same hole as the stream buttons had: mainDefault was set, mainFocused was not,
 * so focusing a RED button turned it the shared neutral grey (0xFF535C67).
 *
 * Focused values are the idle hue lifted in lightness at full saturation, which
 * is what makes focus read as the button lighting up rather than changing
 * identity.
 */
val AnimeDubButtonColors = ModularButtonColors(
    // Idle face: deep red.                       HSL   0°, 100%, 40%
    mainDefault   = Color(0xFFCC0000),
    // Focused face: same hue, lifted to 55%.     HSL   0°, 100%, 55%
    mainFocused   = Color(0xFFFF2020),
    mainToggled   = Color(0xFF7A0000),
    // Idle strip.                                HSL   0°, 100%, 24%
    secondDefault = Color(0xFF7A0000),
    // Focused strip, lifted to 41%.              HSL   0°, 100%, 41%
    secondFocused = Color(0xFFC21212),
    // Selected/pressed strip. Without this every palette here fell back to
    // ModularButtonColors' default 0xFF372B67 -- a PURPLE strip under a red,
    // green or blue button the moment it was toggled.
    secondToggled = Color(0xFF4A0000),
    textUnfocusedToggled = Color(0xFF9E3939),
    shadowColor   = Color(0x60000000),
    textFocused   = Color.White,
    textUnfocused = Color(0xFFD6D6D6),
)

/**
 * "Watch in SuB" — the vibrant purple anime button.
 *
 * One hue (275°) at high saturation, lifted for focus like the other two. Reads
 * as a distinct third identity next to the red dub button without competing with
 * it for attention the way the previous yellow did.
 *
 * A SIDE BENEFIT OVER THE OLD YELLOW: white on 0xFFCCA000 was the weakest label
 * contrast of any button here, and it got worse as the focused face brightened.
 * Purple is dark enough at both states that white sits on it cleanly, so this no
 * longer needs the dark-text workaround gold was heading for.
 */
val AnimeSubButtonColors = ModularButtonColors(
    // Idle face: vibrant purple.                 HSL 275°,  75%, 48%
    mainDefault   = Color(0xFF8A1FD6),
    // Focused face: same hue, lifted.            HSL 275°,  85%, 63%
    mainFocused   = Color(0xFFAE50F1),
    mainToggled   = Color(0xFF3B0F63),
    // Idle strip: deeper purple.                 HSL 275°,  75%, 28%
    secondDefault = Color(0xFF50127D),
    // Focused strip, lifted.                     HSL 275°,  75%, 42%
    secondFocused = Color(0xFF781BBB),
    secondToggled = Color(0xFF2E0A4A),
    textUnfocusedToggled = Color(0xFF61388B),
    shadowColor   = Color(0x60000000),
    textFocused   = Color.White,
    textUnfocused = Color(0xFFD6D6D6),
)

/**
 * "Watch on <source>" — VidNest, VidLove, VidFast, MoviesAPI.
 *
 * ONE PLACE, because there were three: the same colour block was pasted into
 * MoviesDetailsScreenCompose and twice into TVShowsDetailsScreenCompose, each
 * carrying its own copy of the missing-mainFocused bug. Fixing that in three
 * copies is how two of them drift.
 */
val StreamSourceButtonColors = ModularButtonColors(
    // Idle face: pure saturated blue.            HSL 241°, 100%, 50%
    mainDefault   = Color(0xFF0004FF),
    // Focused face: same hue, lifted to 65%.     HSL 241°, 100%, 65%
    mainFocused   = Color(0xFF4F4DFF),
    mainToggled   = Color(0xFF07052F),
    // Idle strip: deep violet.                   HSL 262°,  69%, 35%
    secondDefault = Color(0xFF471C98),
    // Focused strip: same hue, lifted to 50%.    HSL 262°,  69%, 50%
    secondFocused = Color(0xFF6828D7),
    // Deep violet rather than the shared default: this palette is the one
    // place a purple strip was ever in-family, but it should be ITS violet.
    secondToggled = Color(0xFF2B1060),
    textUnfocusedToggled = Color(0xFF353269),
    shadowColor   = Color(0x60000000),
    textFocused   = Color.White,
    textUnfocused = Color(0xFFD6D6D6),
)

/**
 * Season chips on the TV detail screen.
 *
 * DERIVED FROM THE STREAM BUTTONS, not copied. Every base colour was already
 * identical -- same blue face, same violet strip, same shadow and label colours
 * -- including the same missing mainFocused, which is why a focused season also
 * turned grey. Deriving means the fix, and any future retune, reaches both.
 *
 * The two additions are toggle-only, and the reason this could not simply BE
 * StreamSourceButtonColors: a season chip is the one control here that is
 * toggleable, so it needs label colours for the selected state. ModularButton
 * resolves "toggled" ahead of "focused", so the SELECTED season keeps its dark
 * mainToggled face whether or not the cursor is on it -- only unselected chips
 * light up, which is what makes the selection readable at a glance.
 */
val SeasonButtonColors = StreamSourceButtonColors.copy(
    // Selected but not under the cursor: dimmed, so the cursor stays findable.
    textToggled      = Color(0xFF5D5B6A),
    // Selected AND under the cursor.
    textToggledFocus = Color(0xFFCDCDCD),
)

/**
 * Play Trailer and the two episode arrows on both detail screens.
 *
 * RED, WHERE THEY USED TO BE GREY. These are the page's playback controls and
 * were sharing one neutral grey with Add to Favorites and Extras -- five
 * identical buttons in a row, none of which looked like it did anything
 * different from its neighbour.
 *
 * They carried the same hole as every other palette here: mainDefault was set,
 * mainFocused was not, so focusing one fell through to this file's neutral
 * default (0xFF535C67) -- very slightly DARKER and bluer than their idle grey.
 * The button dulled under the cursor rather than lighting up, which is why the
 * focus state read as nothing happening at all.
 *
 * Muted rather than vivid on purpose. These sit beside the anime dub button's
 * 0xFFCC0000, and a second saturated red in the same app would read as the same
 * control. This one is desaturated and darker: clearly red, clearly secondary.
 */
val TrailerControlButtonColors = ModularButtonColors(
    // Idle face: muted brick red.                HSL 0°, 57%, 39%
    mainDefault   = Color(0xFF9E2B2B),
    // Focused face: same hue, lifted.            HSL 0°, 57%, 54%
    mainFocused   = Color(0xFFCD4747),
    mainToggled   = Color(0xFF5C1F1F),
    // Idle strip, replacing the old near-black 0xFF2E2C37.
    secondDefault = Color(0xFF3A1618),
    // Focused strip, lifted.
    secondFocused = Color(0xFF6B2224),
    secondToggled = Color(0xFF250D0E),
    textUnfocusedToggled = Color(0xFF804444),
    shadowColor   = Color(0x50000000),
    textFocused   = Color.White,
    textUnfocused = Color(0xFFBEBEBE),
)

/**
 * Extras / Providers — the toggle on both detail screens.
 *
 * Forest green: dark and desaturated enough to sit beside the red playback
 * controls without competing with them, and distinct from the blue of anything
 * that starts playback. This button reveals information; it does not play
 * anything, and the colour should not suggest otherwise.
 */
val ExtrasButtonColors = ModularButtonColors(
    // Idle face: deep forest.                    HSL 137°, 51%, 28%
    mainDefault   = Color(0xFF246B38),
    // Focused face: same hue, lifted.            HSL 137°, 46%, 43%
    mainFocused   = Color(0xFF3AA057),
    mainToggled   = Color(0xFF143D20),
    secondDefault = Color(0xFF143D20),
    secondFocused = Color(0xFF276E3E),
    secondToggled = Color(0xFF0D2715),
    textUnfocusedToggled = Color(0xFF316141),
    shadowColor   = Color(0x50000000),
    textFocused   = Color.White,
    textUnfocused = Color(0xFFBEBEBE),
)

/**
 * Add to Favorites — steel blue.
 *
 * A DIFFERENT BLUE FROM THE WATCH BUTTONS, on purpose. Those are hue 241 at full
 * saturation (0xFF0004FF) -- the loudest colour in the app, because starting
 * playback is the loudest action. This is hue 210 at 55%: recognisably the same
 * family, clearly not the same job. Putting the vivid blue here would make
 * "favourite this" look like "play this".
 *
 * Also the best label contrast of the four palettes on these screens. The gold
 * this replaced was the worst — white on a bright gold face is thin, and it got
 * thinner as focus lifted it.
 */
val FavoriteButtonColors = ModularButtonColors(
    // Idle face: steel blue.                     HSL 210°, 55%, 40%
    mainDefault   = Color(0xFF2E669E),
    // Focused face: same hue, lifted.            HSL 210°, 55%, 54%
    mainFocused   = Color(0xFF498ACA),
    mainToggled   = Color(0xFF16304A),
    secondDefault = Color(0xFF16304A),
    secondFocused = Color(0xFF2A5C8C),
    secondToggled = Color(0xFF0E2032),
    // THE SELECTED-BUT-NOT-FOCUSED LABEL. Left unset, this fell back to
    // ModularButtonColors' default 0xFF918181 -- a warm grey that belongs to
    // no palette here, so every toggled button wore the same washed-out
    // label whatever colour its face was.
    //
    // Each is now a lighter tone of that palette's OWN toggled face, sitting
    // near 1.7:1 against it: deliberately faint, sunk into the face
    // rather than on top of it. A selected control
    // the cursor has left should read as settled, not as shouting.
    textUnfocusedToggled = Color(0xFF355373),
    shadowColor   = Color(0x50000000),
    textFocused   = Color.White,
    textUnfocused = Color(0xFFBEBEBE),
)

@Immutable
data class ModularButtonTextConfig(
    val text: String,
    val toggledText: String? = null,
    val fontSize: Float = 12f,
    val offsetX: Dp = 0.dp,

    /**
     * Draw this drawable instead of [text].
     *
     * Added for symbols no font can be trusted with. A play triangle is a glyph
     * every font has; a pause that reads correctly at 11sp is not -- U+25AE
     * doubled looked like a snout, U+2016 merged into one bar, and spacing them
     * apart needed the button widened around the workaround.
     *
     * It renders in the SAME box as the text, so it inherits the press offset
     * and the focus colour: an icon overlaid on the button from outside would
     * sit still while the button moved under it.
     *
     * TINTED WITH THE LABEL COLOUR, so an icon button and a text button light up
     * identically on focus. Supply a white or single-colour asset.
     */
    val iconRes: Int? = null,
    val iconSize: Dp = 12.dp,
)

@Immutable
data class ModularButtonGlowConfig(
    val enabled: Boolean = true,
    val defaultRes: Int? = null,
    val toggledRes: Int? = null,
    val offsetX: Dp = 0.dp,
    val offsetY: Dp = (-10).dp,

//The red background IS rounded — you can see it in the screenshot. The clip is working correctly. The problem is simply that your glow PNG is larger than the clip bounds, so the glow image is rendering outside/beyond the clipped area and you're seeing the unclipped overflow.
//The glow image overflows because it's a soft light bloom — its visible pixels extend beyond the declared glowWidth x glowHeight size, so the corners you're trying to clip are actually transparent in the PNG already, and the visible glow parts are within the bounds.
//This means cornerRadius on the clip will never visually round the glow because the glow PNG doesn't have hard rectangular edges to begin with — it's already a soft oval/blob shape.
//So the question is: what are you actually trying to achieve with cornerRadius?
//
//If you want to constrain the glow to not bleed outside a rounded container, the clip is already working (red bg proves it)
//If you want the glow itself to have a different shape, you need a different glow PNG that has harder rectangular edges that can actually be clipped
//
//Can you show me what you're trying to achieve visually? Because based on what I can see, the cornerRadius parameter may simply not be applicable to your current glow PNG's shape.
    val cornerRadius: Dp = 0.dp,          // controls glow roundness
    val fadeOutDurationMillis: Int = 80,   // how fast glow fades out on press/toggle
    val fadeInDurationMillis: Int = 150,   // how fast glow fades back in on release
)

@Immutable
data class ModularButtonAnimationConfig(
    val pressOffset: Dp = 4.dp,
    val textOffsetDefault: Dp = 1.dp,
    val textOffsetPressed: Dp = 2.4.dp,
    // How long the button takes to travel, each way.
    val durationMillis: Int = 110,
    // How long it stays down before starting back up. The full press therefore
    // lasts bounceBackDelayMillis + durationMillis, not twice the duration —
    // the downward tween is cut short when the bounce begins.
    val bounceBackDelayMillis: Int = 80,
    /**
     * How long after the press to actually run onClick. 0 = immediately.
     *
     * WHY THIS IS NOT JUST A SLOWER ANIMATION. onClick used to fire on the same
     * frame as the press, so whatever it opened — the server-choice overlay, the
     * player — appeared while the button was still travelling downward. The
     * animation was never seen; it was covered by its own result, which read as
     * the button stuttering rather than pressing.
     *
     * Set this a little longer than bounceBackDelayMillis + durationMillis and
     * the button completes its press, settles, and THEN the overlay arrives.
     *
     * The click SOUND still plays immediately — feedback has to be instant even
     * when the action is not, or the button feels unresponsive rather than
     * deliberate.
     *
     * Defaults to 0, so every existing button behaves exactly as before.
     */
    val clickDelayMillis: Int = 0
)

/**
 * Press timing for every "watch" button — the four stream sources and the two
 * anime dub/sub buttons — on both detail screens.
 *
 * The two screens had drifted to different feels (movies 180/140, TV 110/80)
 * for the same control. This is the single timing; each screen still overrides
 * pressOffset and the text offsets, which are tuned to its own button size.
 *
 * Full press = 150 down-and-hold + 190 travel = 340ms, then a 60ms breath before
 * the overlay. Tune the weight of the press with the first two and how long the
 * pause afterwards feels with clickDelayMillis.
 */
val WatchButtonAnimation = ModularButtonAnimationConfig(
    pressOffset = 2.5.dp,
    textOffsetDefault = 1.dp,
    textOffsetPressed = 2.4.dp,
    durationMillis = 190,
    bounceBackDelayMillis = 150,
    clickDelayMillis = 400,
)

@Composable
fun ModularButton(
    modifier: Modifier = Modifier,
    textConfig: ModularButtonTextConfig,
    isToggleable: Boolean,
    toggled: Boolean = false,
    isFocusable: Boolean = true,
    onClick: () -> Unit,
    fontFamily: FontFamily,
    focusRequester: FocusRequester,
    dimensions: ModularButtonDimensions = ModularButtonDimensions(),
    colors: ModularButtonColors = ModularButtonColors(),
    glowConfig: ModularButtonGlowConfig = ModularButtonGlowConfig(),
    animationConfig: ModularButtonAnimationConfig = ModularButtonAnimationConfig(),
    externalToggled: Boolean? = null,       // parent-controlled toggle (for radio-style groups)
    playClickSound: Boolean = true,
    // Fired only when this button GAINS focus, never on a loss and never
    // on a recomposition reporting the same state. Default does nothing,
    // so every existing call site is unaffected.
    onFocusGained: () -> Unit = {},
) {
    var isFocused by remember { mutableStateOf(false) }
    var keepGlow by remember { mutableStateOf(false) }
    var internalToggled by remember(isToggleable, toggled) {
        mutableStateOf(if (isToggleable) toggled else false)
    }
    var pressWave by remember { mutableIntStateOf(0) }

    val interactionSource = remember { MutableInteractionSource() }
    val currentOnClick by rememberUpdatedState(newValue = onClick)

    // Drives the deferred onClick — see ModularButtonAnimationConfig.clickDelayMillis.
    // Scoped to the composition, so a button that leaves the screen mid-press
    // drops its pending action rather than firing into a dead tree.
    val scope = rememberCoroutineScope()
    var clickPending by remember { mutableStateOf(false) }

    // ✅ single source-of-truth for toggle state
    val effectiveToggled = externalToggled ?: internalToggled

    val mainYOffset by animateDpAsState(
        targetValue = if ((isToggleable && effectiveToggled) || (!isToggleable && pressWave % 2 == 1))
            animationConfig.pressOffset else 0.dp,
        animationSpec = tween(durationMillis = animationConfig.durationMillis),
        label = "mainYOffset"
    )

    val textYOffset by animateDpAsState(
        targetValue = if ((isToggleable && effectiveToggled) || (!isToggleable && pressWave % 2 == 1))
            animationConfig.textOffsetPressed else animationConfig.textOffsetDefault,
        animationSpec = tween(durationMillis = animationConfig.durationMillis),
        label = "textYOffset"
    )


    // NEW BLOCK ADDED
    val glowAlpha by animateFloatAsState(
        targetValue = when {
            !isFocusable                                  -> 0f
            !isFocused                                    -> 0f
            !isToggleable && pressWave % 2 == 1           -> 0f
            isToggleable && effectiveToggled && isFocused -> 0.6f
            else                                          -> 1f
        },
        animationSpec = tween(
            durationMillis = if (!isToggleable && pressWave % 2 == 1)
                glowConfig.fadeOutDurationMillis
            else
                glowConfig.fadeInDurationMillis
        ),
        label = "glowAlpha"
    )

    LaunchedEffect(pressWave) {
        if (!isToggleable && pressWave % 2 == 1) {
            delay(animationConfig.bounceBackDelayMillis.toLong())
            pressWave++
        }
    }

    fun handleButtonClick() {
        if (isToggleable && externalToggled == null) {
            // Only manage state locally if parent isn't controlling it
            internalToggled = !internalToggled
        } else if (!isToggleable) {
            pressWave++
        }

        // ALWAYS IMMEDIATE, even when the action is deferred below. A press that
        // is silent until its overlay appears reads as a missed input, and the
        // viewer presses again.
        if (playClickSound) {
            BlueHiveApplication.playClickSound()
        }

        // Unchanged for every button that has not asked for a delay.
        if (animationConfig.clickDelayMillis <= 0) {
            currentOnClick()
            return
        }

        // A second press while one is already in flight still animates and still
        // clicks audibly, but must not run the action twice — that is how one
        // press on a stream button opens two players.
        if (clickPending) return
        clickPending = true
        scope.launch {
            delay(animationConfig.clickDelayMillis.toLong())
            clickPending = false
            currentOnClick()
        }
    }

    val isPressed = !isToggleable && pressWave % 2 == 1

    val mainColor = when {
        isToggleable && effectiveToggled -> colors.mainToggled
        isPressed -> colors.mainToggled
        isFocused -> colors.mainFocused
        else -> colors.mainDefault
    }

    val totalButtonHeight = dimensions.secondYOffset + dimensions.secondHeight + dimensions.shadowHeight





    Box(
        contentAlignment = Alignment.TopStart,
        modifier = modifier.size(
            width = dimensions.glowWidth,
            height = dimensions.glowHeight
        )
    ) {
        // 🔹 Glow image behind the button
        if (glowConfig.enabled && isFocusable) {

            val glowRes =
                if (isToggleable && effectiveToggled)
                    glowConfig.toggledRes ?: glowConfig.defaultRes
                else
                    glowConfig.defaultRes

            glowRes?.let {
                Image(
                    painter = painterResource(id = it),
                    contentDescription = null,
                    modifier = Modifier
                        .size(
                            width = dimensions.glowWidth,
                            height = dimensions.glowHeight
                        )
                        .align(Alignment.TopStart)
                        .offset(x = glowConfig.offsetX, y = glowConfig.offsetY)
                        .graphicsLayer {
                            clip = true
                            shape = RoundedCornerShape(glowConfig.cornerRadius)
                            alpha = glowAlpha
                        },
                    contentScale = ContentScale.FillBounds
                )
            }
        }

        // 🔹 Main button body + shadow
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .size(width = dimensions.mainWidth, height = totalButtonHeight)
                .align(Alignment.TopStart)
                .drawBehind {
                    // Shadow stack (only when unfocused)
                    if (!isFocused) {
                        val shadowBaseY =
                            dimensions.mainHeight.toPx() + dimensions.shadowOffset.toPx()

                        for (i in 0..2) {
                            val expansion = i * 2f
                            drawRoundRect(
                                color = Color(0xFF040404).copy(alpha = 0.3f - (i * 0.05f)),
                                topLeft = Offset(
                                    -expansion / 2f,
                                    shadowBaseY + i
                                ),
                                size = Size(
                                    dimensions.mainWidth.toPx() + expansion,
                                    dimensions.shadowHeight.toPx()
                                ),
                                cornerRadius = CornerRadius(
                                    dimensions.secondCornerRadius,
                                    dimensions.secondCornerRadius
                                )
                            )
                        }
                        for (i in 3..5) {
                            val expansion = i * 3f
                            drawRoundRect(
                                color = Color(0xFF040404).copy(alpha = 0.15f - (i * 0.02f)),
                                topLeft = Offset(
                                    -expansion / 2f,
                                    shadowBaseY + (i * 1.5f)
                                ),
                                size = Size(
                                    dimensions.mainWidth.toPx() + expansion,
                                    dimensions.shadowHeight.toPx() + (i * 0.8f)
                                ),
                                cornerRadius = CornerRadius(
                                    dimensions.secondCornerRadius + (i * 0.5f),
                                    dimensions.secondCornerRadius + (i * 0.5f)
                                )
                            )
                        }
                        for (i in 6..8) {
                            val expansion = i * 4f
                            drawRoundRect(
                                color = Color(0xFF040404).copy(alpha = 0.08f - (i * 0.01f)),
                                topLeft = Offset(
                                    -expansion / 2f,
                                    shadowBaseY + (i * 2f)
                                ),
                                size = Size(
                                    dimensions.mainWidth.toPx() + expansion,
                                    dimensions.shadowHeight.toPx() + (i * 1.2f)
                                ),
                                cornerRadius = CornerRadius(
                                    dimensions.secondCornerRadius + i,
                                    dimensions.secondCornerRadius + i
                                )
                            )
                        }
                    }

                    // Second (middle) surface
                    drawRoundRect(
                        color = when {
                            isToggleable && effectiveToggled -> colors.secondToggled
                            isFocused -> colors.secondFocused
                            else -> colors.secondDefault
                        },
                        topLeft = Offset(0f, dimensions.secondYOffset.toPx()),
                        size = Size(
                            dimensions.secondWidth.toPx(),
                            dimensions.secondHeight.toPx()
                        ),
                        cornerRadius = CornerRadius(
                            dimensions.secondCornerRadius,
                            dimensions.secondCornerRadius
                        )
                    )


                    // Main top surface
                    drawRoundRect(
                        color = mainColor,
                        topLeft = Offset(
                            0f,
                            (dimensions.mainYOffset + mainYOffset).toPx()
                        ),
                        size = Size(
                            dimensions.mainWidth.toPx(),
                            dimensions.mainHeight.toPx()
                        ),
                        cornerRadius = CornerRadius(
                            dimensions.mainCornerRadius,
                            dimensions.mainCornerRadius
                        )
                    )
                }
        ) {
            val labelText =
                if (isToggleable && effectiveToggled && textConfig.toggledText != null)
                    textConfig.toggledText
                else
                    textConfig.text

            val isGlowActive = isFocusable && (isFocused || keepGlow)

            val labelColor = when {
                // focused + toggled
                isToggleable && effectiveToggled && isGlowActive -> colors.textToggledFocus

                // focused (not toggled)
                isGlowActive -> colors.textFocused

                // ✅ toggled but NOT focused
                isToggleable && effectiveToggled -> colors.textUnfocusedToggled

                // normal unfocused
                else -> colors.textUnfocused
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(
                        width = dimensions.mainWidth,
                        height = dimensions.mainHeight
                    )
                    .align(Alignment.TopCenter)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        val next = if (isFocusable) focusState.isFocused else false
                        // Compared BEFORE assigning: onFocusChanged also fires on
                        // recompositions carrying the same state, so a plain
                        // "if (isFocused)" would re-fire on each of them while the
                        // button simply sat there holding focus.
                        val gained = next && !isFocused
                        isFocused = next
                        Log.d(TAG, "FOCUS STATE (${textConfig.text}): $isFocused")
                        if (gained) onFocusGained()
                    }
                    .focusable(enabled = isFocusable, interactionSource = interactionSource)
                    .onKeyEvent { keyEvent ->
                        if (!isFocusable) return@onKeyEvent false

                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.key) {
                                Key.DirectionCenter,
                                Key.Enter,
                                Key.NumPadEnter -> {
                                    Log.d(TAG, "CENTER PRESSED on ${textConfig.text}")
                                    handleButtonClick()
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }
                    .clickable(
                        enabled = isFocusable,
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        handleButtonClick()
                    }
            ) {
                if (textConfig.iconRes != null) {
                    Image(
                        painter = painterResource(id = textConfig.iconRes),
                        contentDescription = labelText,
                        colorFilter = ColorFilter.tint(labelColor),
                        modifier = Modifier
                            .size(textConfig.iconSize)
                            .offset(y = textYOffset, x = textConfig.offsetX)
                    )
                } else {
                    Text(
                        text = labelText,
                        color = labelColor,
                        fontSize = textConfig.fontSize.sp,
                        fontFamily = fontFamily,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .offset(y = textYOffset, x = textConfig.offsetX)
                            .semantics { contentDescription = labelText }
                    )
                }
            }
        }
    }
}
