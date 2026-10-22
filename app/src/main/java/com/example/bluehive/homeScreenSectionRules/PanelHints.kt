package com.example.bluehive.homeScreenSectionRules

// ─────────────────────────────────────────────────────────────────────────────
//  Remote hints for the two home panels (Latest Trailers, Highest Trending
//  Content), and the caption that moves when a panel is entered.
//
//  WHY THESE EXIST. Both panels take two presses where every other app takes
//  one: Click to step INSIDE the panel, then LEFT/RIGHT turn it, Click plays a
//  trailer or opens a title, and Back steps out again. Nothing on screen said
//  so. The hints name the very next press, in the place the eye already is:
//
//    on the panel   top-right of the picture:  [Click] to browse
//    inside it      bottom of the picture:     [‹›] Browse | [Click] to play | [↩] Exit
//                   plus a large arrow at each edge of the picture
//    trailer on     bottom of the picture:     [Click] to pause | [↩] Close
//                   for PANEL_PLAY_HINT_MS, with a gold hairline counting down
//
//  THEY ARE PERMANENT. The "Click to browse" chip shows for as long as a panel
//  has focus, and the bar for as long as a panel is entered, every time. They
//  used to retire after three uses per profile, and the owner's verdict on
//  seeing the bar vanish mid-test (2026-10-22) was that it must never go: the
//  layout is unusual enough that a hint which leaves reads as a bug. Only the
//  bar over a PLAYING trailer fades, after PANEL_PLAY_HINT_MS, so it does not
//  sit on the picture for the whole trailer; any Click brings it back.
//
//  INSIDE A PANEL THE TITLE MOVES to the top-right corner, opposite the badges,
//  so the hint bar can take the bottom. Long titles shrink to fit instead of
//  being cut off (PanelCaption).
// ─────────────────────────────────────────────────────────────────────────────

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.bluehive.utilities.AppTypography

/** The gold that marks "the button to press" and "you are inside the panel". */
val PANEL_HINT_GOLD = Color(0xFFD19814)

/** The bar's edge while its countdown runs, so the bright line reads against it. */
private val PANEL_HINT_GOLD_DIM = Color(0xFF5E4510)

/** How long the playing-trailer hint stays up after a trailer starts or is paused. */
const val PANEL_PLAY_HINT_MS = 3000

/** How long the caption takes to glide between corners. */
const val PANEL_CAPTION_MOVE_MS = 400

private val PANEL_EASE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

private val HINT_BG          = Color(0xF0070A16)
private val HINT_EDGE_FOCUS  = Color(0xFFE8E8EC)
private val HINT_TEXT_MAIN   = Color.White
private val HINT_TEXT_QUIET  = Color(0xFFAEB4C4)
private val HINT_SEPARATOR   = Color(0xFF2C3352)
private val KEY_EDGE         = Color(0xFF8D94A8)
private val KEY_INK          = Color(0xFF2A1C00)
private val ARROW_DISC       = Color(0xCC05070F)
private val ARROW_DISC_EDGE  = Color(0x2EFFFFFF)

private val CHIP_SHAPE = RoundedCornerShape(13.dp)
private val KEY_SHAPE  = RoundedCornerShape(9.dp)

// ─────────────────────────────────────────────────────────────────────────────
//  Hint layer — drawn over a panel's picture, never focusable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Everything a panel shows over its picture to explain the remote. Sized by
 * the caller to exactly the picture. Takes no focus and consumes no keys.
 *
 * @param compact      label for the small top-right hint ("to browse"), or null
 * @param legend       whether the bottom bar shows at all
 * @param legendBrowse whether the bar starts with the "‹› Browse" item
 * @param legendClick  label for its Click item ("to play", "to open", "to
 *                     pause"), or null to leave the item out -- a trailer that
 *                     failed to load has nothing for Click to do
 * @param legendExit   label for the Back item ("Exit", or "Close" over a trailer)
 * @param arrows       the big LEFT/RIGHT arrows at the picture's edges
 * @param arrowScale   their size relative to the full 52 dp. The trailer panel
 *                     draws them at 0.8: its picture is smaller, and at full size
 *                     they crowded it (owner's call, 2026-10-22)
 * @param timer        1 → 0 progress of the gold line along the bar's bottom
 *                     edge, read at draw time so a running countdown never
 *                     recomposes the panel
 *
 * THE CHIP AND THE BAR ARRIVE A BEAT LATE, on purpose. They appear in the
 * corner the caption is leaving (PanelCaption glides top-right <-> bottom-left
 * over PANEL_CAPTION_MOVE_MS), and arriving with it drew the two over each
 * other for the first part of the glide.
 */
@Composable
fun PanelHintLayer(
    compact: String?,
    legend: Boolean,
    legendBrowse: Boolean,
    legendClick: String?,
    legendExit: String,
    arrows: Boolean,
    modifier: Modifier = Modifier,
    arrowScale: Float = 1f,
    timer: (() -> Float)? = null,
) {
    // The last content shown, kept so an exit animation still has words to fade.
    val lastCompact = remember { arrayOf("") }
    if (compact != null) lastCompact[0] = compact
    val lastLegend = remember { arrayOf<String?>("", "Exit") }
    val lastBrowse = remember { booleanArrayOf(true) }
    if (legend) {
        lastLegend[0] = legendClick
        lastLegend[1] = legendExit
        lastBrowse[0] = legendBrowse
    }

    Box(modifier) {
        AnimatedVisibility(
            visible  = compact != null,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
            enter    = fadeIn(tween(250, delayMillis = 200)) +
                       slideInVertically(tween(250, delayMillis = 200, easing = PANEL_EASE)) { -it / 4 },
            exit     = fadeOut(tween(120))
        ) {
            HintChip(edge = HINT_EDGE_FOCUS) {
                HintItem(lastCompact[0], main = true) { ClickKey() }
            }
        }

        AnimatedVisibility(
            visible  = arrows,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
            enter    = fadeIn(tween(250)),
            exit     = fadeOut(tween(200))
        ) { BrowseDisc(left = true, scale = arrowScale) }

        AnimatedVisibility(
            visible  = arrows,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            enter    = fadeIn(tween(250)),
            exit     = fadeOut(tween(200))
        ) { BrowseDisc(left = false, scale = arrowScale) }

        AnimatedVisibility(
            visible  = legend,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            enter    = fadeIn(tween(250, delayMillis = 150)) +
                       slideInVertically(tween(300, delayMillis = 150, easing = PANEL_EASE)) { it / 3 },
            exit     = fadeOut(tween(150))
        ) {
            HintChip(edge = PANEL_HINT_GOLD, timer = timer) {
                if (lastBrowse[0]) {
                    HintItem("Browse", main = false) { ArrowsKey() }
                    HintSeparator()
                }
                val click = lastLegend[0]
                if (click != null) {
                    HintItem(click, main = true) { ClickKey() }
                    HintSeparator()
                }
                HintItem(lastLegend[1] ?: "Exit", main = false) { BackKey() }
            }
        }
    }
}

@Composable
private fun HintChip(
    edge: Color,
    timer: (() -> Float)? = null,
    content: @Composable RowScope.() -> Unit
) {
    // WHILE A COUNTDOWN RUNS the edge dims and the bright gold line runs along
    // it, shrinking right to left. Drawn ON TOP of the border (drawWithContent
    // sits outside .border in the chain, so its drawContent() includes the
    // stroke): drawn underneath, a gold line beneath a gold border of the same
    // width was simply invisible, and the bar vanished with no warning.
    val edgeColor = if (timer != null) PANEL_HINT_GOLD_DIM else edge
    Box(
        Modifier
            .height(26.dp)
            .clip(CHIP_SHAPE)
            .background(HINT_BG)
            .drawWithContent {
                drawContent()
                if (timer != null) {
                    val h = 2.dp.toPx()
                    drawRect(
                        color   = PANEL_HINT_GOLD,
                        topLeft = Offset(0f, size.height - h),
                        size    = Size(size.width * timer().coerceIn(0f, 1f), h)
                    )
                }
            }
            .border(1.5.dp, edgeColor, CHIP_SHAPE)
    ) {
        Row(
            modifier              = Modifier.fillMaxHeight().padding(horizontal = 9.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content               = content
        )
    }
}

@Composable
private fun HintItem(label: String, main: Boolean, key: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        key()
        Spacer(Modifier.width(5.dp))
        Text(
            text       = label,
            color      = if (main) HINT_TEXT_MAIN else HINT_TEXT_QUIET,
            fontFamily = AppTypography.passionRegular,
            fontSize   = 11.sp,
            maxLines   = 1
        )
    }
}

@Composable
private fun HintSeparator() {
    Box(Modifier.width(1.dp).height(12.dp).background(HINT_SEPARATOR))
}

// ── Keys, drawn to look like the buttons on the remote ──────────────────────

@Composable
private fun ClickKey() {
    Box(
        modifier         = Modifier.height(18.dp).clip(KEY_SHAPE).background(PANEL_HINT_GOLD).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = "Click",
            color      = KEY_INK,
            fontFamily = AppTypography.passionRegular,
            fontSize   = 10.sp,
            maxLines   = 1
        )
    }
}

@Composable
private fun ArrowsKey() {
    OutlineKey(width = 26.dp) {
        val w = size.width; val h = size.height
        chevron(cx = w * 0.27f, cy = h / 2, half = h * 0.42f, left = true, stroke = 1.6.dp.toPx())
        chevron(cx = w * 0.73f, cy = h / 2, half = h * 0.42f, left = false, stroke = 1.6.dp.toPx())
    }
}

@Composable
private fun BackKey() {
    OutlineKey(width = 18.dp) {
        // Tabler's arrow-back-up, the curved arrow on the remote's Back button,
        // drawn from its 24-unit outline.
        val k = size.minDimension / 24f
        val p = Path().apply {
            moveTo(9f * k, 14f * k); lineTo(5f * k, 10f * k); lineTo(9f * k, 6f * k)
            moveTo(5f * k, 10f * k); lineTo(16f * k, 10f * k)
            arcTo(Rect(12f * k, 10f * k, 20f * k, 18f * k), -90f, 180f, false)
            lineTo(15f * k, 18f * k)
        }
        drawPath(p, Color.White, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun OutlineKey(width: Dp, glyph: DrawScope.() -> Unit) {
    Box(
        modifier         = Modifier.size(width, 18.dp).border(1.5.dp, KEY_EDGE, KEY_SHAPE),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(width - 7.dp, 11.dp), onDraw = glyph)
    }
}

/** Full size of the edge arrows; a panel may draw them smaller (arrowScale). */
private val BROWSE_DISC = 52.dp

@Composable
private fun BrowseDisc(left: Boolean, scale: Float) {
    Box(
        modifier         = Modifier.size(BROWSE_DISC * scale).clip(CircleShape).background(ARROW_DISC).border(1.5.dp, ARROW_DISC_EDGE, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp * scale)) {
            chevron(
                cx     = size.width / 2 + (if (left) -1f else 1f) * size.width * 0.06f,
                cy     = size.height / 2,
                half   = size.height * 0.36f,
                left   = left,
                stroke = 3.dp.toPx() * scale
            )
        }
    }
}

private fun DrawScope.chevron(cx: Float, cy: Float, half: Float, left: Boolean, stroke: Float) {
    val dx = half * 0.62f * (if (left) 1f else -1f)
    val p = Path().apply {
        moveTo(cx + dx / 2, cy - half)
        lineTo(cx - dx / 2, cy)
        lineTo(cx + dx / 2, cy + half)
    }
    drawPath(p, Color.White, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ─────────────────────────────────────────────────────────────────────────────
//  Caption — title + subtitle that move and shrink to fit
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A panel's title and subtitle. Bottom-left normally; top-right, right-aligned,
 * while the panel is entered ([raised]), so the hint bar can take the bottom.
 * The move is a glide of the same text, not a swap.
 *
 * SHRINKS TO FIT rather than cutting off. Each line is one line, given the room
 * between the badges and the far edge; a line that does not fit steps its size
 * down -- to 75% at most, so it stays readable from the sofa -- and only a line
 * that still does not fit is cut with an ellipsis. The room is the same in both
 * corners, so the text never resizes mid-glide.
 *
 * @param reservedStart the width of the badge row at the top-left, which the
 *                      raised caption must stay clear of
 */
@Composable
fun PanelCaption(
    title: String,
    subtitle: String?,
    raised: Boolean,
    reservedStart: Dp,
    modifier: Modifier = Modifier,
    titleFont: FontFamily = AppTypography.pattayaRegular,
    metaFont: FontFamily = AppTypography.passionRegular,
) {
    val h by animateFloatAsState(
        targetValue   = if (raised) 1f else -1f,
        animationSpec = tween(PANEL_CAPTION_MOVE_MS, easing = PANEL_EASE),
        label         = "captionH"
    )
    val v by animateFloatAsState(
        targetValue   = if (raised) -1f else 1f,
        animationSpec = tween(PANEL_CAPTION_MOVE_MS, easing = PANEL_EASE),
        label         = "captionV"
    )

    BoxWithConstraints(modifier.fillMaxSize().padding(8.dp)) {
        val room = (maxWidth - reservedStart - 10.dp).coerceAtLeast(80.dp)
        // Each line aligns by the SAME animated bias as the block, so the
        // shorter line slides from under the title's start to under its end
        // with the glide. A Column-wide Start/End switch snapped it across on
        // the first frame instead.
        val lineAlign = BiasAlignment.Horizontal(h)
        Column(modifier = Modifier.align(BiasAlignment(h, v)).widthIn(max = room)) {
            BasicText(
                text     = title,
                modifier = Modifier.align(lineAlign),
                style    = TextStyle(color = Color.White, fontFamily = titleFont, fontSize = 13.sp, lineHeight = 1.05.em),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 9.75.sp, maxFontSize = 13.sp, stepSize = 0.25.sp)
            )
            if (!subtitle.isNullOrBlank()) {
                BasicText(
                    text     = subtitle,
                    modifier = Modifier.align(lineAlign),
                    style    = TextStyle(color = Color(0xFFD0D0D0), fontFamily = metaFont, fontSize = 8.sp, lineHeight = 1.15.em),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 8.sp, stepSize = 0.25.sp)
                )
            }
        }
    }
}
