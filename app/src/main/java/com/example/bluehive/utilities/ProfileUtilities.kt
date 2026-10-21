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
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.alpha
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
    // Fired only on a focus GAIN — see ProfileButtonBase.
    onFocusGained: () -> Unit = {},
) {
    ProfileButtonBase(
        topImageRes    = R.drawable.create_profile,
        focusRequester = focusRequester,
        onClick        = onClick,
        modifier       = modifier,
        dimensions     = dimensions,
        animConfig     = animConfig,
        glowConfig     = glowConfig,
        onFocusGained  = onFocusGained,
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
    // Fired only when this button GAINS focus, never on a loss and never on a
    // recomposition reporting the same state. Default does nothing, so
    // ProfilePictureButton and every other caller are unaffected.
    onFocusGained: () -> Unit = {},
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
                    .onFocusChanged {
                        // Compared BEFORE assigning: onFocusChanged also
                        // fires on recompositions carrying the same state.
                        val gained = it.isFocused && !isFocused
                        isFocused = it.isFocused
                        if (gained) onFocusGained()
                    }
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

/** Focus scale on an avatar, matching PROFILE_FOCUS_SCALE on the profile picker. */
private const val AVATAR_FOCUS_SCALE = 1.06f

// ProfileCard uses 0.7f. These tiles are half a profile card's size, so
// the same veil reads much heavier on them -- at 0.7f the artwork went muddy and
// the colours stopped telling the avatars apart. Lightened until the unfocused
// row is still clearly subordinate to the focused one but still legible.
// Lifted twice, a tenth each time: 0.5 -> 0.45 -> 0.395, so 60.5% of the artwork
// comes through where 50% did. Past about 0.35 the unfocused row stops reading as
// subordinate to the focused avatar at all, which is the point of the tint.
private const val AVATAR_UNFOCUSED_TINT = 0.395f

/**
 * A focusable avatar image for the profile creation and profile management
 * pickers.
 *
 * The focus treatment is a port of ProfileCard on the profile picker screen, so
 * the two controls read as the same thing:
 *
 *   focused   -> avatar at full brightness, no border, glow visible, scaled 1.06
 *   unfocused -> black tint at 50%, thin border, no glow, unscaled
 *
 * The avatar image itself is NEVER made translucent. A translucent avatar lets
 * the glow behind it show through the picture, and that bleed-through is what
 * made the focused avatar look hazy. The dimming is a tint layer drawn ON TOP
 * of the image, where the glow cannot reach it.
 *
 * Selection is independent of focus and is shown by the checkmark alone -- the
 * selected avatar is not held at its own brightness, because brightness is what
 * tells you where the focus is.
 *
 * @param avatarRes      Drawable resource ID for the avatar image.
 * @param focusRequester FocusRequester managed by the caller for D-pad routing.
 * @param onClick        Invoked on centre/enter key press or tap.
 * @param modifier       Positional modifier from the screen (offset, etc.).
 * @param imageConfig    Avatar image size, position, corner radius, and border.
 * @param glowConfig     Glow image size and position -- shown only when focused.
 * @param isSelected     True when this avatar is the currently selected one.
 * @param onFocusGained  Fired only on a focus GAIN -- never on a loss, and never
 *                       on a recomposition reporting the state it already had.
 *                       The caller uses it for the profile-swap sound, exactly
 *                       as ProfileCard does on the picker.
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
    onFocusGained: () -> Unit      = {},
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnFocusGained by rememberUpdatedState(onFocusGained)

    // Every timing and magnitude below is ProfileCard's, not a new one.
    val glowAlpha by animateFloatAsState(
        targetValue   = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = if (isFocused) 150 else 100),
        label         = "avatarPickerGlow",
    )

    val scale by animateFloatAsState(
        targetValue   = if (isFocused) AVATAR_FOCUS_SCALE else 1f,
        animationSpec = tween(durationMillis = 200),
        label         = "avatarPickerScale",
    )

    val tintAlpha by animateFloatAsState(
        targetValue   = if (isFocused) 0f else AVATAR_UNFOCUSED_TINT,
        animationSpec = tween(durationMillis = 200),
        label         = "avatarPickerTint",
    )

    // Sized to the avatar so the grid spacing stays put, but unbounded so the
    // larger glow can overflow without pushing anything around.
    Box(
        modifier = modifier
            .size(width = imageConfig.width, height = imageConfig.height)
            .wrapContentSize(align = Alignment.TopStart, unbounded = true),
    ) {
        // -- Layer 1: Glow -- behind the avatar, fades with focus -------------
        Image(
            painter            = painterResource(id = R.drawable.avatar_button_glow),
            contentDescription = null,
            contentScale       = ContentScale.FillBounds,
            modifier           = Modifier
                .size(width = glowConfig.width, height = glowConfig.height)
                .offset(x = glowConfig.offsetX, y = glowConfig.offsetY)
                .alpha(glowAlpha),
        )

        // -- Layer 2: Avatar -- focus target, border, tint, checkmark ---------
        Box(
            modifier = Modifier
                .size(width = imageConfig.width, height = imageConfig.height)
                .offset(x = imageConfig.offsetX, y = imageConfig.offsetY)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .then(
                    if (!isFocused) Modifier.border(
                        width = imageConfig.strokeWidth,
                        color = imageConfig.strokeColor,
                        shape = RoundedCornerShape(imageConfig.cornerRadius),
                    ) else Modifier
                )
                .clip(RoundedCornerShape(imageConfig.cornerRadius))
                .focusRequester(focusRequester)
                .onFocusChanged {
                    // Compare BEFORE assigning: onFocusChanged also fires on
                    // recompositions carrying the state it already had, and a
                    // bare "if (it.isFocused) play()" would re-fire the sound
                    // while the avatar simply sat there.
                    val gained = it.isFocused && !isFocused
                    isFocused = it.isFocused
                    if (gained) currentOnFocusGained()
                }
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
            Image(
                painter            = painterResource(id = avatarRes),
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.matchParentSize(),
            )

            // Tint sits ON TOP of the picture, so the glow behind cannot bleed
            // through it. This is the whole reason the avatar is never alpha'd.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0xFF000000).copy(alpha = tintAlpha)),
            )

            // -- Checkmark -- full opacity, above the tint --------------------
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
