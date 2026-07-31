package com.example.bluehive.webview.hls

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * The English-subtitle picker shown when CC is pressed.
 *
 * OpenSubtitles lists several English subtitles per episode and only one of them
 * is timed to the release we're streaming (see [HlsSubtitles] for why). There is
 * no release name in the listing to choose by, so this shows what each candidate
 * actually CONTAINS — cue count and time span — next to the episode's real
 * runtime, which is enough to spot a mistimed one before watching it drift.
 *
 * Styled to match ExtractionLoadingOverlay: same scrim, same blue, same wordmark,
 * so the player's modals read as one family.
 */

private val BlueHiveBlue = Color(0xFF4477FF)
private val PanelBg = Color(0xF2070B18)

/** A focusable line in the panel — a subtitle, or the turn-off action. */
private sealed interface Row {
    data class Option(val choice: HlsSubtitles.SubtitleChoice) : Row
    data object TurnOff : Row
}

@Composable
fun SubtitlePickerOverlay(
    loading: Boolean,
    options: List<HlsSubtitles.SubtitleChoice>,
    /** [HlsSubtitles.EnglishOption.index] currently rendering, or -1. */
    activeIndex: Int,
    /** Stream duration, so a candidate's span can be judged against it. 0 if unknown. */
    durationMs: Long,
    error: String?,
    onPick: (HlsSubtitles.SubtitleChoice) -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rows: List<Row> = remember(options, activeIndex) {
        options.map { Row.Option(it) } + if (activeIndex >= 0) listOf(Row.TurnOff) else emptyList()
    }

    // One requester per row so traversal can be wired explicitly. Rebuilt only
    // when the row count changes, so focus survives a row's contents updating.
    val requesters = remember(rows.size) { List(rows.size) { FocusRequester() } }

    // Focus the list as soon as there is something to focus, so the D-pad lands
    // inside the panel instead of on the player controls behind it.
    LaunchedEffect(loading, rows.size) {
        if (!loading && rows.isNotEmpty()) runCatching { requesters.first().requestFocus() }
    }

    // Closest end-time to the real runtime: the cheapest signal we have for
    // "this one was timed to this cut".
    val bestIndex = remember(options, durationMs) {
        if (durationMs <= 0 || options.isEmpty()) -1
        else options.minByOrNull { abs(durationMs - it.lastCueMs) }?.option?.index ?: -1
    }

    // BACK closes the panel instead of leaving the player. A BackHandler rather
    // than an onKeyEvent: the activity's dispatchKeyEvent hands BACK straight to
    // the view hierarchy, and only a registered handler beats the default
    // "finish the activity" that would otherwise end playback.
    BackHandler(enabled = true) { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD9000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth(0.50f)
                .heightIn(max = 416.dp)
                .background(PanelBg, RoundedCornerShape(14.dp))
                .border(1.dp, BlueHiveBlue.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(horizontal = 26.dp, vertical = 21.dp)
        ) {
            Text(
                "B L U E H I V E",
                color = BlueHiveBlue.copy(alpha = 0.65f),
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 3.2.sp,
            )
            Spacer(Modifier.height(11.dp))
            Text(
                "English subtitles",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                when {
                    loading -> "Checking what's available for this episode…"
                    error != null -> error
                    options.isEmpty() -> "No English subtitles are listed for this episode."
                    else -> "Out of sync? Pick another — each is timed to a different release."
                },
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(16.dp))

            when {
                loading -> Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) { Spinner() }

                rows.isEmpty() -> Spacer(Modifier.height(8.dp))

                else -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .focusGroup()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rows.forEachIndexed { i, row ->
                        // Wire each neighbour explicitly and CANCEL at the edges.
                        // Without this, pressing up on the first row (or down on
                        // the last) hands focus to the PlayerView behind the
                        // scrim — which is still focusable — and the panel goes
                        // dead with no way to get focus back into it.
                        val nav = Modifier
                            .focusRequester(requesters[i])
                            .focusProperties {
                                up = if (i == 0) FocusRequester.Cancel else requesters[i - 1]
                                down = if (i == rows.lastIndex) FocusRequester.Cancel
                                       else requesters[i + 1]
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                        when (row) {
                            is Row.Option -> OptionRow(
                                choice = row.choice,
                                durationMs = durationMs,
                                isActive = row.choice.option.index == activeIndex,
                                isBest = row.choice.option.index == bestIndex,
                                onClick = { onPick(row.choice) },
                                modifier = nav,
                            )
                            Row.TurnOff -> PlainRow(
                                label = "Turn captions off",
                                onClick = onTurnOff,
                                modifier = nav,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "BACK to close",
                color = Color.White.copy(alpha = 0.38f),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun OptionRow(
    choice: HlsSubtitles.SubtitleChoice,
    durationMs: Long,
    isActive: Boolean,
    isBest: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (focused) BlueHiveBlue.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f),
                RoundedCornerShape(8.dp)
            )
            .border(
                1.dp,
                if (focused) BlueHiveBlue else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(8.dp)
            )
            .focusable(interactionSource = interaction)
            .onSelectKey(onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    choice.label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (isBest) {
                    Spacer(Modifier.width(8.dp))
                    Badge("BEST MATCH", BlueHiveBlue)
                }
                if (isActive) {
                    Spacer(Modifier.width(6.dp))
                    Badge("IN USE", Color(0xFF3ECF7B))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append("${choice.cueCount} lines · ")
                    append(fmt(choice.firstCueMs))
                    append(" → ")
                    append(fmt(choice.lastCueMs))
                    if (durationMs > 0) {
                        val gap = durationMs - choice.lastCueMs
                        // A subtitle that ends WAY before the video, or after it,
                        // was timed to a different cut. Say so rather than making
                        // the user infer it from two timestamps.
                        if (gap < -5_000) append("  ·  runs past the episode")
                        else if (gap > 180_000) append("  ·  ends ${fmt(gap)} early")
                    }
                },
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun PlainRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        label,
        color = if (focused) Color.White else Color.White.copy(alpha = 0.65f),
        fontSize = 12.sp,
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (focused) BlueHiveBlue.copy(alpha = 0.22f) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .border(
                1.dp,
                if (focused) BlueHiveBlue else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(8.dp)
            )
            .focusable(interactionSource = interaction)
            .onSelectKey(onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

/** Centre / OK / Enter activates the row. Acts on KeyUp so a held key can't fire twice. */
private fun Modifier.onSelectKey(onClick: () -> Unit): Modifier = onKeyEvent { e ->
    val isSelect = e.key == Key.Enter || e.key == Key.DirectionCenter || e.key == Key.NumPadEnter
    if (e.type == KeyEventType.KeyUp && isSelect) { onClick(); true } else false
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** The extraction overlay's arc, scaled down — same idiom, drawn once and spun
 *  on the RenderThread rather than recomposed. */
@Composable
private fun Spinner() {
    val inf = rememberInfiniteTransition(label = "subpicker")
    val angle by inf.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "spin"
    )
    Spacer(
        Modifier
            .size(45.dp)
            .graphicsLayer { rotationZ = angle }
            .drawWithCache {
                val center = Offset(size.width / 2f, size.height / 2f)
                val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                val brush = Brush.sweepGradient(
                    listOf(Color.Transparent, Color(0xFF0044FF), Color(0xFF9922FF), Color.Transparent),
                    center = center
                )
                onDrawBehind { drawArc(brush, 0f, 270f, false, style = stroke) }
            }
    )
}

private fun fmt(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}
