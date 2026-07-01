package com.example.bluehive.utilities

// ─────────────────────────────────────────────────────────────────────────────
//  ProfileUtilities.kt
//
//  Two button composables for the Profile screen:
//
//    1. ProfilePictureButton  — avatar image on top, 3-D plinth below,
//                               press-down animation on select.
//
//    2. CreateProfileButton   — identical construction; top image is always
//                               R.drawable.create_profile. Also renders
//                               create_profile_glow as a fully independent
//                               layer beneath both the plinth and top image.
//
//  Visual anatomy (Z order, back → front):
//    [glow image]        CreateProfileButton only — width/height/offsetX/offsetY
//                        are the only things that control it, nothing else
//    [plinth rectangle]  always visible, drawn via drawBehind
//    [top image]         avatar or create icon; clips to rounded square;
//                        animates downward on press then bounces back
// ─────────────────────────────────────────────────────────────────────────────

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.R
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
//  Config data classes
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Controls the visual dimensions of the top image layer and the plinth beneath.
 *
 * @param imageSize          Width AND height of the square top-image layer.
 * @param imageCornerRadius  Corner radius applied to the top image clip.
 * @param plinthWidth        Width of the plinth rectangle.
 * @param plinthHeight       Height of the plinth rectangle.
 * @param plinthOffsetX      Horizontal offset of the plinth relative to the
 *                           button's left edge. Positive = shifts right,
 *                           negative = shifts left.
 * @param plinthOffsetY      Vertical offset of the plinth relative to the
 *                           bottom edge of the image. Positive = shifts down
 *                           (more gap), negative = shifts up (more overlap
 *                           hidden behind the image).
 * @param plinthCornerRadius Corner radius of the plinth rectangle.
 * @param plinthColor        Fill colour of the plinth surface.
 */
data class ProfileButtonDimensions(
    val imageSize: Dp             = 110.dp,
    val imageCornerRadius: Dp     = 10.dp,
    val plinthWidth: Dp           = 110.dp,
    val plinthHeight: Dp          = 20.dp,
    val plinthOffsetX: Dp         = 0.dp,
    val plinthOffsetY: Dp         = (-4).dp,
    val plinthCornerRadius: Float = 8f,
    val plinthColor: Color        = Color(0xFF2E2C37),
    val shadowColor: Color        = Color(0x99000000),
)

/**
 * Controls the glow image rendered beneath the plinth and top image in
 * CreateProfileButton. Every dimension is fully independent — nothing else
 * in the file affects its size or position.
 *
 * @param width    Rendered width of the glow image.
 * @param height   Rendered height of the glow image.
 * @param offsetX  Horizontal shift relative to the button's left edge.
 *                 Negative = moves left, positive = moves right.
 * @param offsetY  Vertical shift relative to the button's top edge.
 *                 Negative = moves up, positive = moves down.
 */
data class CreateProfileGlowConfig(
    val width: Dp   = 120.dp,
    val height: Dp  = 120.dp,
    val offsetX: Dp = 0.dp,
    val offsetY: Dp = 0.dp,
    // ── Fade-out on press ─────────────────────────────────────────────────────
    /** Duration in milliseconds of the glow fade-out when the button is pressed. */
    val fadeOutDurationMillis: Int = 200,
    /** Easing curve for the fade-out. FastOutLinearInEasing = snappy disappear. */
    val fadeOutEasing: Easing      = FastOutLinearInEasing,
    /** Delay in milliseconds before the fade-out begins after the button is pressed. */
    val fadeOutDelayMillis: Int    = 0,
)

/**
 * Controls the press animation.
 *
 * @param pressOffset           How far (dp) the image drops on press.
 * @param durationMillis        Duration of the press spring animation.
 * @param bounceBackDelayMillis Delay before the image returns to rest position.
 */
data class ProfileButtonAnimationConfig(
    val pressOffset: Dp             = 5.dp,
    val durationMillis: Int         = 100,
    val bounceBackDelayMillis: Long = 80L,
)

// ─────────────────────────────────────────────────────────────────────────────
//  1.  ProfilePictureButton
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A focusable, pressable profile-avatar button.
 *
 * [avatarRes] is fully modular — pass any of your 30 bundled avatar drawables.
 *
 * @param avatarRes      Drawable resource ID for the avatar image.
 * @param focusRequester FocusRequester managed by the caller for D-pad routing.
 * @param onClick        Invoked on centre/enter key press or tap.
 * @param modifier       Positional modifier from the screen (offset, etc.).
 * @param dimensions     Size, plinth, and colour config.
 * @param animConfig     Press animation config.
 */
@Composable
fun ProfilePictureButton(
    avatarRes: Int,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimensions: ProfileButtonDimensions = ProfileButtonDimensions(),
    animConfig: ProfileButtonAnimationConfig = ProfileButtonAnimationConfig(),
) {
    ProfileButtonBase(
        topImageRes    = avatarRes,
        focusRequester = focusRequester,
        onClick        = onClick,
        modifier       = modifier,
        dimensions     = dimensions,
        animConfig     = animConfig,
        glowConfig     = null,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  2.  CreateProfileButton
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A focusable, pressable "create new profile" button.
 *
 * Top image is always R.drawable.create_profile.
 * The glow (R.drawable.create_profile_glow) renders as its own independent
 * layer beneath the plinth and top image. Only [glowConfig] controls its size
 * and position — nothing else in the file affects it.
 *
 * @param focusRequester FocusRequester managed by the caller for D-pad routing.
 * @param onClick        Invoked on centre/enter key press or tap.
 * @param modifier       Positional modifier from the screen (offset, etc.).
 * @param dimensions     Size, plinth, and colour config.
 * @param glowConfig     Glow image size and position — fully independent.
 * @param animConfig     Press animation config.
 */
@Composable
fun CreateProfileButton(
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimensions: ProfileButtonDimensions = ProfileButtonDimensions(),
    glowConfig: CreateProfileGlowConfig = CreateProfileGlowConfig(),
    animConfig: ProfileButtonAnimationConfig = ProfileButtonAnimationConfig(),
) {
    ProfileButtonBase(
        topImageRes    = R.drawable.create_profile,
        focusRequester = focusRequester,
        onClick        = onClick,
        modifier       = modifier,
        dimensions     = dimensions,
        animConfig     = animConfig,
        glowConfig     = glowConfig,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Internal shared implementation
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProfileButtonBase(
    topImageRes: Int,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimensions: ProfileButtonDimensions,
    animConfig: ProfileButtonAnimationConfig,
    // null = no glow (ProfilePictureButton). Non-null = render glow (CreateProfileButton).
    glowConfig: CreateProfileGlowConfig?,
) {
    var isFocused by remember { mutableStateOf(false) }
    var pressWave by remember { mutableIntStateOf(0) }

    val currentOnClick by rememberUpdatedState(onClick)
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(pressWave) {
        if (pressWave % 2 == 1) {
            // Wait for whichever is longer: the press bounce-back delay, or the
            // glow fade-out duration. This stops the fade being cancelled and
            // reversed before it finishes.
            val waitMs = maxOf(
                animConfig.bounceBackDelayMillis,
                (glowConfig?.fadeOutDelayMillis?.toLong() ?: 0L) +
                        (glowConfig?.fadeOutDurationMillis?.toLong() ?: 0L),
            )
            delay(waitMs)
            pressWave++
        }
    }

    val isPressed = pressWave % 2 == 1

    val shadowColor by androidx.compose.animation.animateColorAsState(
        targetValue   = if (isFocused) Color.Transparent else dimensions.shadowColor,
        animationSpec = tween(durationMillis = 200),
        label         = "shadowColor",
    )

    val imageYOffset by animateDpAsState(
        targetValue   = if (isPressed) animConfig.pressOffset else 0.dp,
        animationSpec = tween(durationMillis = animConfig.durationMillis),
        label         = "profileButtonPress",
    )

    // Glow alpha: 1f (fully visible) at rest, 0f (invisible) when pressed.
    // Uses the fade config from glowConfig if present, otherwise unused.
    val glowAlpha by animateFloatAsState(
        targetValue   = if (!isFocused || isPressed) 0f else 1f,
        animationSpec = if (glowConfig != null) tween(
            durationMillis = glowConfig.fadeOutDurationMillis,
            delayMillis    = glowConfig.fadeOutDelayMillis,
            easing         = glowConfig.fadeOutEasing,
        ) else tween(durationMillis = 200),
        label         = "glowFadeOut",
    )

    val visiblePlinthBelow = (dimensions.plinthHeight + dimensions.plinthOffsetY)
        .coerceAtLeast(0.dp)
    val totalHeight = dimensions.imageSize + visiblePlinthBelow
    val totalWidth  = dimensions.imageSize

    // Outer Box: unconstrained so the glow Image can overflow freely in all
    // directions without being clipped by the button's logical bounds.
    // The glow's .size() is the ONLY thing that controls how large it renders.
    Box(modifier = modifier) {

        // ── Layer 1: Glow (CreateProfileButton only) ──────────────────────────
        // Declared first → sits furthest back in Z order, behind everything.
        // Outer Box does not constrain it, so width/height values render exactly
        // as specified — they are not capped by imageSize or totalWidth.
        if (glowConfig != null) {
            Image(
                painter            = painterResource(id = R.drawable.create_profile_glow),
                contentDescription = null,
                contentScale       = ContentScale.FillBounds,
                modifier           = Modifier
                    .requiredSize(width = glowConfig.width, height = glowConfig.height)
                    .offset(x = glowConfig.offsetX, y = glowConfig.offsetY)
                    .graphicsLayer { alpha = glowAlpha },
            )
        }

        // Inner Box: the logical button bounds (plinth + top image live here).
        Box(
            modifier         = Modifier.size(width = totalWidth, height = totalHeight),
            contentAlignment = Alignment.TopCenter,
        ) {

            // ── Layer 2: Plinth ───────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(width = totalWidth, height = totalHeight)
                    .drawBehind {
                        val plinthTop = dimensions.imageSize.toPx() +
                                dimensions.plinthOffsetY.toPx()

                        // Blurred shadow — only when unfocused
                        if (shadowColor != Color.Transparent) {
                            drawIntoCanvas { canvas ->
                                val paint = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    color = android.graphics.Color.TRANSPARENT
                                    setShadowLayer(
                                        16f,   // blur radius — increase for softer/larger blur
                                        0f,    // dx
                                        10f,   // dy — how far below
                                        shadowColor.copy(alpha = 0.50f).hashCode().let {
                                            android.graphics.Color.argb(
                                                (0.50f * 255).toInt(),
                                                0, 0, 0,
                                            )
                                        }
                                    )
                                }
                                val rect = android.graphics.RectF(
                                    dimensions.plinthOffsetX.toPx() - 5f,
                                    plinthTop + 4f,
                                    dimensions.plinthOffsetX.toPx() + dimensions.plinthWidth.toPx() + 4f,
                                    plinthTop + dimensions.plinthHeight.toPx() + 7f,
                                )
                                canvas.nativeCanvas.drawRoundRect(
                                    rect,
                                    dimensions.plinthCornerRadius,
                                    dimensions.plinthCornerRadius,
                                    paint,
                                )
                            }
                        }

                        // Plinth
                        drawRoundRect(
                            color        = dimensions.plinthColor,
                            topLeft      = Offset(
                                x = dimensions.plinthOffsetX.toPx(),
                                y = plinthTop,
                            ),
                            size         = Size(
                                width  = dimensions.plinthWidth.toPx(),
                                height = dimensions.plinthHeight.toPx(),
                            ),
                            cornerRadius = CornerRadius(
                                dimensions.plinthCornerRadius,
                                dimensions.plinthCornerRadius,
                            ),
                        )
                    },
            )

            // ── Layer 3: Top image ────────────────────────────────────────────────
            Image(
                painter            = painterResource(id = topImageRes),
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .size(dimensions.imageSize)
                    .offset(y = imageYOffset)
                    .clip(RoundedCornerShape(dimensions.imageCornerRadius))
                    .align(Alignment.TopCenter)
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused }
                    .focusable(interactionSource = interactionSource)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.DirectionCenter ||
                                    event.key == Key.Enter          ||
                                    event.key == Key.NumPadEnter)
                        ) {
                            BlueHiveApplication.playClickSound()
                            pressWave++
                            currentOnClick()
                            true
                        } else {
                            false
                        }
                    }
                    .clickable(
                        interactionSource = interactionSource,
                        indication        = null,
                    ) {
                        BlueHiveApplication.playClickSound()
                        pressWave++
                        currentOnClick()
                    },
            )
        } // end inner Box
    } // end outer Box
}

// ─────────────────────────────────────────────────────────────────────────────
//  3.  AvatarPickerButton
//
//  A focusable avatar image used on the ProfileCreation screen.
//
//  Visual anatomy (Z order, back → front):
//    [glow image]   only while focused — fully independent size/position
//    [avatar image] clipped to rounded square with a stroke border
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Controls the avatar image layer.
 *
 * @param width          Rendered width of the avatar image.
 * @param height         Rendered height of the avatar image.
 * @param offsetX        Horizontal shift of the image relative to the button origin.
 * @param offsetY        Vertical shift of the image relative to the button origin.
 * @param cornerRadius   Corner radius applied to the image clip and stroke.
 * @param strokeColor    Colour of the border drawn around the image.
 * @param strokeWidth    Thickness of the border in dp.
 */
data class AvatarImageConfig(
    val width: Dp         = 54.dp,
    val height: Dp        = 54.dp,
    val offsetX: Dp       = 0.dp,
    val offsetY: Dp       = 0.dp,
    val cornerRadius: Dp  = 8.dp,
    val strokeColor: Color = Color(0xFF6A6767), // 0xFFB0A6A6
    val strokeWidth: Dp   = 1.2.dp,
)

/**
 * Controls the glow image shown when the avatar button is focused.
 *
 * @param width    Rendered width of the glow image.
 * @param height   Rendered height of the glow image.
 * @param offsetX  Horizontal shift of the glow relative to the button origin.
 * @param offsetY  Vertical shift of the glow relative to the button origin.
 */
data class AvatarGlowConfig(
    val width: Dp        = 77.dp,
    val height: Dp       = 77.dp,
    val offsetX: Dp      = 0.dp,
    val offsetY: Dp      = 0.dp,
    val cornerRadius: Dp = 0.dp,
)

/**
 * A focusable avatar image for the profile creation picker.
 *
 * Selection states:
 *   isSelected=true, isAnySelected=true  → 80% opacity + checkmark overlay
 *   isSelected=false, isAnySelected=true → 40% opacity, no checkmark
 *   isAnySelected=false                  → full opacity (nothing selected yet)
 *
 * @param avatarRes      Drawable resource ID for the avatar image.
 * @param focusRequester FocusRequester managed by the caller for D-pad routing.
 * @param onClick        Invoked on centre/enter key press or tap.
 * @param modifier       Positional modifier from the screen (offset, etc.).
 * @param imageConfig    Avatar image size, position, corner radius, and stroke.
 * @param glowConfig     Glow image size and position — shown only when focused.
 * @param isSelected     True when this avatar is the currently selected one.
 * @param isAnySelected  True when any avatar in the row has been selected.
 */
@Composable
fun AvatarPickerButton(
    avatarRes: Int,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageConfig: AvatarImageConfig = AvatarImageConfig(),
    glowConfig: AvatarGlowConfig   = AvatarGlowConfig(),
    isSelected: Boolean            = false,
    isAnySelected: Boolean         = false,
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnClick by rememberUpdatedState(onClick)

    // Opacity rules:
    //   selected            → 0.80f
    //   not selected + any  → 0.40f
    //   nothing selected    → 1.00f
    val imageAlpha = when {
        isSelected              -> 0.80f
        isAnySelected           -> 0.40f
        else                    -> 1.00f
    }

    // Outer Box: unconstrained so the glow can overflow freely without
    // pushing the image around — same pattern as CreateProfileButton.
    Box(modifier = modifier) {

        // ── Layer 1: Glow — behind everything, only while focused ─────────────
        if (isFocused) {
            Box(
                modifier = Modifier
                    .offset(x = glowConfig.offsetX, y = glowConfig.offsetY)
                    .wrapContentSize(align = Alignment.TopStart, unbounded = true),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = glowConfig.width, height = glowConfig.height)
                        .clip(RoundedCornerShape(glowConfig.cornerRadius)),
                ) {
                    Image(
                        painter            = painterResource(id = R.drawable.avatar_button_glow),
                        contentDescription = null,
                        contentScale       = ContentScale.FillBounds,
                        modifier           = Modifier.matchParentSize(),
                    )
                }
            }
        }

        // ── Layer 2: Outer interaction Box (handles focus, clicks, stroke) ──────
        // graphicsLayer alpha is NOT applied here — we apply opacity individually
        // to the image and stroke so the checkmark stays at full opacity.
        Box(
            modifier = Modifier
                .size(width = imageConfig.width, height = imageConfig.height)
                .offset(x = imageConfig.offsetX, y = imageConfig.offsetY)
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable(interactionSource = interactionSource)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionCenter ||
                                event.key == Key.Enter          ||
                                event.key == Key.NumPadEnter)
                    ) {
                        BlueHiveApplication.playClickSound()
                        currentOnClick()
                        true
                    } else false
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication        = null,
                ) {
                    BlueHiveApplication.playClickSound()
                    currentOnClick()
                },
        ) {
            // ── Avatar image — clipped + dimmed based on selection state ─────
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(imageConfig.cornerRadius))
                    .graphicsLayer { alpha = imageAlpha },
            ) {
                Image(
                    painter            = painterResource(id = avatarRes),
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.matchParentSize(),
                )
            }

            // ── Stroke border — same opacity as the image, hidden while focused
            if (!isFocused) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = imageAlpha }
                        .border(
                            width = imageConfig.strokeWidth,
                            color = imageConfig.strokeColor,
                            shape = RoundedCornerShape(imageConfig.cornerRadius),
                        ),
                )
            }

            // ── Checkmark — full opacity, centred over the selected avatar ────
            if (isSelected) {
                Image(
                    painter            = painterResource(id = R.drawable.check_mark),
                    contentDescription = null,
                    modifier           = Modifier
                        .size(imageConfig.width / 2, imageConfig.height / 2)
                        .align(Alignment.Center),
                )
            }
        }
    }
}