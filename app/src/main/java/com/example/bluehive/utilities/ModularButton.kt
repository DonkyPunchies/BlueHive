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

@Immutable
data class ModularButtonTextConfig(
    val text: String,
    val toggledText: String? = null,
    val fontSize: Float = 12f,
    val offsetX: Dp = 0.dp
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
    val durationMillis: Int = 110,
    val bounceBackDelayMillis: Int = 80
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
    playClickSound: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    var keepGlow by remember { mutableStateOf(false) }
    var internalToggled by remember(isToggleable, toggled) {
        mutableStateOf(if (isToggleable) toggled else false)
    }
    var pressWave by remember { mutableIntStateOf(0) }

    val interactionSource = remember { MutableInteractionSource() }
    val currentOnClick by rememberUpdatedState(newValue = onClick)

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

        if (playClickSound) {
            BlueHiveApplication.playClickSound()
        }
        currentOnClick()
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
                        isFocused = if (isFocusable) focusState.isFocused else false
                        Log.d(TAG, "FOCUS STATE (${textConfig.text}): $isFocused")
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
