package com.example.bluehive.webview.hls

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.R
import com.example.bluehive.utilities.AppTypography
import com.example.bluehive.utilities.ModularButton
import com.example.bluehive.utilities.ModularButtonAnimationConfig
import com.example.bluehive.utilities.ModularButtonColors
import com.example.bluehive.utilities.ModularButtonDimensions
import com.example.bluehive.utilities.ModularButtonGlowConfig
import com.example.bluehive.utilities.ModularButtonTextConfig
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
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
    onDismiss: () -> Unit,
) {
    // Closest end-time to the real runtime: the cheapest signal we have for
    // "this one was timed to this cut".
    val best = remember(options, durationMs) {
        if (durationMs <= 0) null
        else options.minByOrNull { abs(durationMs - it.lastCueMs) }
    }
    val bestIndex = best?.option?.index ?: -1
    val bestGapMs = remember(best, durationMs) {
        if (best == null) Long.MAX_VALUE else abs(durationMs - best.lastCueMs)
    }

    // THE RECOMMENDATION GOES FIRST. The backend offers up to eight candidates
    // and the panel shows five before it scrolls, so sorting by provider order
    // could — and did — leave the one option we actually vouch for below the
    // fold, under four we do not.
    val rows: List<Row> = remember(options, activeIndex, bestIndex) {
        val ordered = options.sortedBy { if (it.option.index == bestIndex) 0 else 1 }
        // Tracks and nothing else. Fix timing and Turn captions off moved up to
        // the menu that opens this, because a list whose length changes with the
        // backend's answer is the wrong place for a control whose position
        // people need to learn.
        ordered.map { Row.Option(it) }
    }

    // One requester per row so traversal can be wired explicitly. Rebuilt only
    // when the row count changes, so focus survives a row's contents updating.
    val requesters = remember(rows.size) { List(rows.size) { FocusRequester() } }

    // Focus the list as soon as there is something to focus, so the D-pad lands
    // inside the panel instead of on the player controls behind it.
    LaunchedEffect(loading, rows.size) {
        if (!loading && rows.isNotEmpty()) runCatching { requesters.first().requestFocus() }
    }

    // BACK closes the panel instead of leaving the player. A BackHandler rather
    // than an onKeyEvent: the activity's dispatchKeyEvent hands BACK straight to
    // the view hierarchy, and only a registered handler beats the default
    // "finish the activity" that would otherwise end playback.
    // The list arrives asynchronously and focus lands on the first row the
    // moment it does, so without the arming flag every open would announce a
    // move the viewer never made.
    var navArmed by remember { mutableStateOf(false) }
    val navSound: () -> Unit = {
        if (navArmed) BlueHiveApplication.playNavClickSound() else navArmed = true
    }

    BackOutHandler { onDismiss() }

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
                    else -> "Each one is timed to a different release — " +
                            "if this one is off, try another."
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
                                bestGapMs = bestGapMs,
                                isActive = row.choice.option.index == activeIndex,
                                isBest = row.choice.option.index == bestIndex,
                                onClick = { onPick(row.choice) },
                                onFocusGained = navSound,
                                modifier = nav,
                            )
                            Row.TurnOff -> Unit   // not offered here any more
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Press back to go back",
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
    bestGapMs: Long,
    isActive: Boolean,
    isBest: Boolean,
    onClick: () -> Unit,
    onFocusGained: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // Keyed on the state rather than an onFocusChanged callback, which also
    // fires on losses and on same-state recompositions -- see MenuRow.
    LaunchedEffect(focused) { if (focused) onFocusGained() }

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
                    // "RECOMMENDED" rather than "BEST MATCH": best-of-what is a
                    // question the viewer should not have to ask.
                    Badge("RECOMMENDED", BlueHiveBlue)
                }
                if (isActive) {
                    Spacer(Modifier.width(6.dp))
                    Badge("IN USE", Color(0xFF3ECF7B))
                }
            }
            // A VERDICT, NOT A READOUT. This line used to say
            // "2103 lines · 0:42 → 1:51:20 · ends 4m early", which asks the
            // viewer to hold a runtime in their head and do the subtraction
            // themselves. The numbers are still what decides it — they just do
            // not belong on a television. Nothing is said when there is nothing
            // useful to say, so the warnings stand out instead of being one more
            // grey line among many.
            verdictFor(choice, durationMs, bestGapMs, isBest)?.let { verdict ->
                Spacer(Modifier.height(2.dp))
                Text(verdict, color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp)
            }
        }
    }
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

/**
 * One plain sentence about a candidate, or nothing at all.
 *
 * JUDGED AGAINST THE OTHER CANDIDATES, not against the runtime. The first
 * version of this compared each subtitle's last cue to the video length and
 * warned when it fell more than three minutes short — which, on a 2h09 film
 * whose subtitles all ended around 2:03, marked every single option "probably
 * wrong". It was measuring the closing credits.
 *
 * Credits are exactly the part with no dialogue, and their length is a property
 * of the FILM, shared by every candidate. Comparing candidates to each other
 * cancels it out: the real signal is one subtitle ending minutes away from where
 * all the others end. Self-calibrating, and it can never condemn the whole list.
 */
private fun verdictFor(
    choice: HlsSubtitles.SubtitleChoice,
    durationMs: Long,
    bestGapMs: Long,
    isBest: Boolean,
): String? {
    if (isBest) return "Closest match to this video"
    if (durationMs <= 0 || bestGapMs == Long.MAX_VALUE) return null
    val gap = abs(durationMs - choice.lastCueMs)
    return if (gap - bestGapMs > 120_000)
        "Probably wrong — timed to a different version" else null
}


/**
 * Seconds without trailing noise: 0.25, 1, 1.5 -- never 1.00.
 *
 * Two decimals, then trimmed. The nudge step is 100ms, which a single decimal
 * would render exactly -- but the by-ear anchor lands on arbitrary values, and
 * showing a measured 0.15s as "0.2" misstates it. Trailing zeros on a whole
 * number are just clutter, hence the trim rather than a second format string.
 */
private fun fmtSecs(ms: Long): String =
    String.format(java.util.Locale.US, "%.2f", abs(ms) / 1000.0)
        .trimEnd('0').trimEnd('.')

/**
 * What the sequence row states on the right.
 *
 * STATE, NOT ADVICE. The other rows of that menu use the right-hand column for
 * what they are currently set to -- "English 3", "Small / Inter SemiBold" -- and
 * this one was using it for a hint, which made it the only row whose column
 * could not be read the same way.
 *
 * NOT "In sync" AT ZERO. That was the old read-out's wording and it claimed
 * something it could not know: zero means nobody has adjusted anything, not that
 * the subtitles are right. Someone opening this menu BECAUSE the subtitles are
 * wrong would have been told they were fine. "Not adjusted" says only what is
 * known, and it still reads as a starting point rather than a problem.
 */
private fun offsetSummary(ms: Long): String = when {
    ms == 0L -> "Not adjusted"
    ms > 0   -> "${fmtSecs(ms)}s later"
    else     -> "${fmtSecs(ms)}s sooner"
}

// ── Panel buttons ───────────────────────────────────────────────────────────
//
// SCALED FROM SeasonButton, not invented. Every number below is that button's
// value times 104/117.5, because hand-picking these produced a glow that read as
// a white slab under the button twice running. The season chips are the look
// being matched, so the arithmetic comes from them.
//
// The one that mattered most is glow cornerRadius. ModularButton CLIPS the glow
// image to it, and the default is 0 -- so a soft bloom gets cut into a hard
// rectangle. That was the slab, not the sizing.
private val NudgeDimensions = ModularButtonDimensions(
    mainWidth = 104.dp,
    mainHeight = 26.5.dp,
    mainYOffset = 0.6.dp,
    secondWidth = 104.dp,
    secondHeight = 19.5.dp,
    secondYOffset = 12.dp,
    shadowHeight = 10.6.dp,
    shadowOffset = (-2.65).dp,
    glowWidth = 120.dp,
    glowHeight = 45.dp,
    mainCornerRadius = 7f,
    secondCornerRadius = 8f,
)

/** The panel's own blue, so the buttons read as part of it rather than dropped in. */
private val NudgeColors = ModularButtonColors(
    mainDefault = Color(0xFF1E2854),
    mainFocused = Color(0xFF2644A6),
    mainToggled = Color(0xFF2644A6),
    secondDefault = Color(0xFF151C3A),
    secondFocused = Color(0xFF282C57),
    secondToggled = Color(0xFF282C57),
)

private val NudgeGlow = ModularButtonGlowConfig(
    enabled = true,
    defaultRes = R.drawable.button_focus_wide_glow,
    offsetX = (-8.2).dp,
    offsetY = (-2.2).dp,
    cornerRadius = 9.dp,
)

// TAKEN FROM THE TRAILER ARROWS, the small < and > beside Play Trailer, so the
// transport button in the middle of the pair is the size the app already uses
// for a one-glyph control. Its glow is the narrow asset, not the wide one the
// text buttons use -- a wide bloom on a 26dp button is a halo with a button
// somewhere inside it.
private val PlayDimensions = ModularButtonDimensions(
    mainWidth = 26.5.dp,
    mainHeight = 24.dp,
    mainYOffset = 0.5.dp,
    secondWidth = 26.5.dp,
    secondHeight = 22.dp,
    secondYOffset = 7.dp,
    shadowHeight = 6.dp,
    glowWidth = 41.dp,
    glowHeight = 44.dp,
    mainCornerRadius = 7f,
    secondCornerRadius = 6f,
)

private val PlayGlow = ModularButtonGlowConfig(
    enabled = true,
    defaultRes = R.drawable.button_focus_narrow_glow,
    offsetX = (26.5.dp - 41.dp) / 2,
    offsetY = (-4).dp,
)

/** SeasonButton's press feel, so these answer a press the same way the rest do. */
private val NudgeAnimation = ModularButtonAnimationConfig(
    pressOffset = 2.5.dp,
    textOffsetDefault = 1.dp,
    textOffsetPressed = 2.4.dp,
    durationMillis = 110,
    bounceBackDelayMillis = 80,
)

/**
 * "Subtitles out of sync? Click me to fix it", above the caption line.
 *
 * WHY IT EXISTS: a tester could find the subtitle menu and switch captions on,
 * and never discovered that the timing could be corrected at all. The old
 * notice was a five second toast in a corner, fired the instant a track was
 * chosen — which is before anyone has watched long enough to know whether it
 * is out of sync. It was answering a question nobody had yet.
 *
 * RED, ALONE IN THIS APP. Everything else the player draws is blue, so blue
 * reads as chrome and gets skipped. The outline form keeps the body dark: a
 * filled red slab directly over white captions puts two bright blocks in one
 * place and the eye takes the notice before the dialogue, which is backwards —
 * the dialogue is the thing they just turned on. A dark body also survives any
 * frame, where a red wash over a sunset or a red wall stops looking deliberate.
 *
 * PHRASED AS A QUESTION on purpose. Red carries an error convention, and a
 * statement ("subtitles are out of sync") reads as the app reporting a fault
 * with the stream — some people would assume it was broken and leave. A
 * question is an offer the viewer can decline by ignoring it.
 *
 * IT HOLDS NO FOCUS. See handleSyncHintKey: the activity owns the centre key
 * during playback and never lets Compose see it, so focus here would buy
 * nothing and cost the whole class of focus bugs this screen has already had.
 * The lit border is the affordance; the key routing is what makes it real.
 *
 * @param visible drives both the fade and, in the activity, whether the key is
 *   live — so the bar is inert the moment it starts fading, not when it ends.
 * @param captionFraction the caption layer's own bottom padding fraction, which
 *   MOVES: the captions ride up when the control bar appears. Sitting at a
 *   fixed offset would have this collide with them on the first key press.
 * @param seconds what is left on the clock. Shown in plain white, the one
 *   element that does not dim with the rest: it is a fact about the offer
 *   rather than part of it, and it stays true whether or not the remote is
 *   pointed here.
 * @param active whether OK will act on this RIGHT NOW. False while the control
 *   bar is up, because the band's focused button owns centre then.
 *
 *   IT HAS TO LOOK DIFFERENT IN THE TWO STATES. Holding no real focus is what
 *   keeps this safe, but it also means nothing in the system lights it — so the
 *   styling has to say out loud what focus would otherwise say. Rendered the
 *   same in both, the bar reads as a button you cannot tell is selected, which
 *   is worse than a label: a label is not asking to be pressed.
 */
@Composable
fun BoxScope.SubtitleSyncHint(
    visible: Boolean,
    captionFraction: Float,
    active: Boolean,
    seconds: Int,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Clear of a TWO-line cue at the largest caption size, which is the
        // tallest the block below can get.
        val lift = maxHeight * (captionFraction + 0.125f)
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(650)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = lift),
        ) {
            // Live: a 2dp ring and a red-lit body, the same language the rest
            // of the player uses for a focused control -- only red, because
            // this is the one thing on screen that is not ordinary chrome.
            // Waiting: thinner, dimmer, no fill. Still legible, plainly not the
            // thing the remote is pointed at.
            val ring = if (active) Color(0xFFFF5A58) else Color(0x59E24B4A)
            val body = if (active) Color(0xCC2A0B10) else Color(0x990E0A12)
            val ink = if (active) Color(0xFFFFD6D6) else Color(0x8CFF9E9E)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(body, RoundedCornerShape(7.dp))
                    .border(
                        if (active) 2.dp else 1.dp,
                        ring,
                        RoundedCornerShape(7.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_cc),
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Subtitles out of sync? Click me to fix it",
                    color = ink,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(14.dp))
                // Bracketed and a weight up: at 14sp white-on-dark the digits
                // were thinner than the sentence beside them, so the one part
                // that changes every second read as the faintest thing in the
                // bar. The brackets also stop a bare number reading as part of
                // the sentence it follows.
                Text(
                    "(" + seconds.toString() + ")",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
fun SubtitleMenuOverlay(
    /** "English 4", or null when captions are off. */
    activeLabel: String?,
    /** "Medium / Inter SemiBold", for the customization row to state. */
    styleSummary: String,
    /** The live subtitle offset, for the sequence row to state. */
    offsetMs: Long,
    onChooseSubtitle: () -> Unit,
    onFixSequence: () -> Unit,
    onCustomize: () -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rowCount = if (activeLabel != null) 4 else 1
    val requesters = remember(rowCount) { List(rowCount) { FocusRequester() } }
    LaunchedEffect(rowCount) { runCatching { requesters.first().requestFocus() } }

    // As on every other panel: the menu OPENS with a row focused, and announcing
    // that is announcing a move nobody made. The first arrival arms the sound
    // instead of playing it, and one flag covers all three rows -- a flag each
    // and the first hop onto every row would be silent too.
    var navArmed by remember { mutableStateOf(false) }
    val navSound: () -> Unit = {
        if (navArmed) BlueHiveApplication.playNavClickSound() else navArmed = true
    }

    BackOutHandler { onDismiss() }

    // NO SCRIM, matching the fix-speed panel below. The card already states
    // itself with a border and a near-opaque fill, so the dim was separating it
    // from a picture it was not competing with — and it put a full-screen black
    // wash over the one thing the viewer is here to keep watching. The panel
    // this menu leads to never had one, and the two reading differently was the
    // only reason this looked deliberate.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth(0.42f)
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
            Text("Subtitles", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier.fillMaxWidth().focusGroup(),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                // Edges cancel rather than fall through: the PlayerView behind
                // the scrim is still focusable, and focus landing there leaves
                // the menu lit but dead.
                fun nav(i: Int) = Modifier
                    .focusRequester(requesters[i])
                    .focusProperties {
                        up = if (i == 0) FocusRequester.Cancel else requesters[i - 1]
                        down = if (i == rowCount - 1) FocusRequester.Cancel
                               else requesters[i + 1]
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    }

                MenuRow(
                    label = "Choose subtitle",
                    detail = activeLabel ?: "Off",
                    onClick = onChooseSubtitle,
                    onFocusGained = navSound,
                    modifier = nav(0),
                )
                if (activeLabel != null) {
                    // ABOVE Fix timing, because this one MEASURES and that one
                    // asks you to estimate. Fix timing keeps its place directly
                    // underneath: it is the coarse tool that gets a badly-out
                    // subtitle within earshot, which is the one thing by-ear
                    // cannot do for itself. Hence "Most accurate" on this row
                    // rather than anything implying it replaces the other.
                    // THE ONLY TIMING TOOL NOW. The two it replaced -- press a
                    // button when you hear a named line, and nudge the offset by
                    // a tenth of a second at a time -- both asked the viewer to
                    // judge a moment while the picture moved. This one asks them
                    // to recognise words on a frozen frame, and it has no range
                    // limit, so a two-second error and a ten-minute error cost
                    // the same effort.
                    MenuRow(
                        label = "Fix subtitle speed",
                        detail = offsetSummary(offsetMs),
                        onClick = onFixSequence,
                        onFocusGained = navSound,
                        modifier = nav(1),
                    )
                    MenuRow(
                        label = "Subtitle customization",
                        detail = styleSummary,
                        onClick = onCustomize,
                        onFocusGained = navSound,
                        modifier = nav(2),
                    )
                    MenuRow(
                        label = "Turn captions off",
                        detail = "",
                        onClick = onTurnOff,
                        onFocusGained = navSound,
                        modifier = nav(3),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Press back to close",
                color = Color.White.copy(alpha = 0.38f),
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * One button on a subtitle panel: the shared look, every focus edge named.
 *
 * An unnamed edge hands focus to the PlayerView behind the panel, which is still
 * focusable, and the panel is then lit but completely dead with no way back in.
 */
@Composable
private fun PanelButton(
    label: String,
    requester: FocusRequester,
    onFocusGained: () -> Unit,
    onClick: () -> Unit,
    leftTo: FocusRequester? = null,
    rightTo: FocusRequester? = null,
    upTo: FocusRequester? = null,
    downTo: FocusRequester? = null,
    dimensions: ModularButtonDimensions = NudgeDimensions,
    glow: ModularButtonGlowConfig = NudgeGlow,
    /** Drawn instead of [label] when set. ModularButton tints it like the text. */
    iconRes: Int? = null,
    iconSize: Dp = 12.dp,
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(
            text = label,
            iconRes = iconRes,
            iconSize = iconSize,
            fontSize = 11f,
        ),
        isToggleable = false,
        onClick = onClick,
        onFocusGained = onFocusGained,
        fontFamily = AppTypography.interBold,
        focusRequester = requester,
        dimensions = dimensions,
        colors = NudgeColors,
        glowConfig = glow,
        animationConfig = NudgeAnimation,
        modifier = Modifier.focusProperties {
            up = upTo ?: FocusRequester.Cancel
            down = downTo ?: FocusRequester.Cancel
            left = leftTo ?: FocusRequester.Cancel
            right = rightTo ?: FocusRequester.Cancel
        },
    )
}

private const val IME_POLL_MS = 100L
private const val IME_APPEAR_TIMEOUT_MS = 4_000L

/**
 * Wait for the soft keyboard to be dismissed. Returns false if that cannot be
 * told from here.
 *
 * WHY POLLING, AND WHY AT ALL. The first BACK press while the keyboard is up
 * never reaches the app: the IME is a separate window, it takes that press to
 * dismiss itself, and nothing downstream hears about it. So "resume when they
 * back out" cannot be hung off a key event -- measured on the box, the app saw
 * only the SECOND press, which is why the video stayed frozen through the first.
 *
 * The usual answer is Compose's WindowInsets.isImeVisible, which this activity
 * cannot use: it never calls setDecorFitsSystemWindows(false), and adding that
 * would change how the fullscreen video lays out. The platform insets are still
 * there to be read directly, though, so this reads them.
 *
 * IT WAITS TO SEE THE KEYBOARD FIRST. At the moment editing begins the keyboard
 * has not appeared yet, so "not visible" is the normal state and acting on it
 * would end editing immediately. Only a disappearance AFTER an appearance means
 * anything.
 *
 * AND IT GIVES UP QUIETLY. Insets are reported from API 30; this project's
 * minSdk is 25, and a device that cannot answer returns false rather than
 * polling forever -- which leaves exactly the old behaviour, where a second
 * BACK press does the job.
 */
private suspend fun awaitKeyboardDismissal(view: android.view.View): Boolean {
    var seen = false
    var waited = 0L
    while (seen || waited < IME_APPEAR_TIMEOUT_MS) {
        delay(IME_POLL_MS)
        waited += IME_POLL_MS
        val up = ViewCompat.getRootWindowInsets(view)
            ?.isVisible(WindowInsetsCompat.Type.ime()) ?: return false
        if (up) seen = true else if (seen) return true
    }
    return false
}

/** One step of the transport buttons. See [SubtitleFixSeqOverlay]. */
private const val FIXSEQ_SEEK_MS = 2_000L

/**
 * One result row, computed entirely off the UI thread.
 *
 * Every field here is a finished string or a finished number: composition never
 * scores, formats or searches anything. The caption ticker already recomposes
 * this tree about twelve times a second, so a row that did work on the way to
 * the screen would do it twelve times a second for nothing.
 */
data class FixSeqRow(
    val cueStartMs: Long,
    /** Original text -- dashes, case and punctuation intact, never the key. */
    val text: String,
    /** The second cue's words, when the matcher glued an adjacent pair. */
    val tailText: String?,
    /** The line before this one, the only real disambiguator for a generic line. */
    val prevText: String?,
    /** Where the cue sits in the subtitle file, as m:ss. */
    val timeLabel: String,
    /** What picking this row would do, in the words the rest of the UI uses. */
    val shiftLabel: String,
    /** How many cues in the file read exactly like this one. */
    val identicalCount: Int,
    /** Implied offset is past the clamp, so this cannot be applied as asked. */
    val tooFar: Boolean,
    /** Normalized query tokens, for bolding what actually matched. */
    val matchTokens: List<String>,
)

/**
 * What the sequence screen is showing.
 *
 * The ACTIVITY owns it, not composition: every transition is a reading of the
 * player clock, and the clock is not something a composable should touch.
 */
sealed interface FixSeqPhase {
    data class Searching(
        val status: CueMatcher.Status,
        val rows: List<FixSeqRow>,
        /** Set when the words typed were too common to decide anything. */
        val repeatHint: String?,
    ) : FixSeqPhase

    data class Applied(val movedBy: Long, val drift: Boolean) : FixSeqPhase
}

/**
 * Fix the subtitle sequence by searching for a line you heard.
 *
 * WHY THIS EXISTS ALONGSIDE THE BY-EAR SCREEN. By-ear names a line that has NOT
 * been spoken yet and asks for a press when it arrives, so it cannot reach
 * further back than its own lead -- about twelve seconds -- and it is generous
 * in one direction and capped in the other. Here the line is identified by its
 * WORDS, so range stops mattering: a two-second error and a ten-minute error are
 * the same amount of work, in either direction. That is the whole point.
 *
 * WHAT IT COSTS IN EXCHANGE: accuracy. By-ear measures against a running clock
 * and a reaction; this measures against wherever the picture was parked when the
 * search box was engaged, which is after the line was spoken by however long it
 * took to reach the remote. The transport buttons under the box are the answer
 * to that, and they are why they exist.
 *
 * NO SCRIM, unlike the menu: the picture and the captions stay visible, because
 * here the viewer is comparing text against text.
 */
@Composable
fun SubtitleFixSeqOverlay(
    phase: FixSeqPhase,
    query: String,
    isPlaying: Boolean,
    onEngage: () -> Unit,
    /** BACK out of the box: the undo of [onEngage], so the picture runs again. */
    onDisengage: () -> Unit,
    onQuery: (String) -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onPick: (FixSeqRow) -> Unit,
    onUndo: () -> Unit,
    /** Done on the Applied screen: close AND let the picture run. */
    onAccept: () -> Unit,
    /** BACK, and Close: leave playback exactly as it was found. */
    onDone: () -> Unit,
) {
    val boxFR = remember { FocusRequester() }
    val fieldFR = remember { FocusRequester() }
    val rewFR = remember { FocusRequester() }
    val playFR = remember { FocusRequester() }
    val ffFR = remember { FocusRequester() }
    val doneFR = remember { FocusRequester() }
    val undoFR = remember { FocusRequester() }
    val rowFRs = remember { List(4) { FocusRequester() } }

    val keyboard = LocalSoftwareKeyboardController.current
    val localView = LocalView.current

    // TYPING IS A MODE, and the wrapper below owns it rather than the field.
    // A text field that can take D-pad focus directly is the classic TV trap:
    // focus lands in it on the way past, the keyboard appears unbidden, and the
    // viewer cannot get out. The field is gated with canFocus so Compose's
    // geometric search physically cannot reach it.
    var isEditing by remember { mutableStateOf(false) }

    /** Whether the field has actually held focus yet. See its onFocusChanged. */
    var fieldHadFocus by remember { mutableStateOf(false) }

    // FOCUS AND EDITING ARE DIFFERENT STATES and the box has to show both. The
    // first version lit up only while editing, so a box that merely held D-pad
    // focus looked exactly like one that did not -- there was no way to tell
    // the remote was pointing at it before pressing OK.
    //
    // hasFocus, not isFocused: while typing, focus belongs to the field INSIDE
    // this box, and the glow has to stay lit through that.
    var boxFocused by remember { mutableStateOf(false) }

    var navArmed by remember { mutableStateOf(false) }
    val navSound: () -> Unit = {
        if (navArmed) BlueHiveApplication.playNavClickSound() else navArmed = true
    }

    BackOutHandler { onDone() }

    val rows = (phase as? FixSeqPhase.Searching)?.rows.orEmpty()
    val applied = phase as? FixSeqPhase.Applied

    // TWO FRAMES, as everywhere else in this app that requests focus on arrival,
    // and runCatching because a requester not yet attached throws. Under an
    // AndroidView-hosted PlayerView the attach ordering is worse, not better.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.android.awaitFrame()
        kotlinx.coroutines.android.awaitFrame()
        runCatching { boxFR.requestFocus() }
    }

    // KEYED ON THE PHASE'S KIND, never on the phase itself: Searching is a new
    // instance on every keystroke, and re-requesting focus there would yank it
    // out from under the viewer mid-word.
    val isApplied = applied != null

    /** True once Applied has been shown, so LEAVING it is distinguishable. */
    var hasShownApplied by remember { mutableStateOf(false) }

    /** Set on the way out of Applied; cleared when the new rows take focus. */
    var wantRowFocus by remember { mutableStateOf(false) }

    /**
     * Whether the box itself saw the KeyDown of the centre press now ending.
     *
     * A KEY PRESS IS TWO EVENTS AND THEY CAN LAND ON DIFFERENT CONTROLS.
     * ModularButton activates on KeyDown, so pressing "Not that one" runs the
     * undo, swaps Applied out for the search phase, and lets the box take focus
     * — all before the finger comes off the button. The KeyUp then arrives at
     * the box, which read it as "engage me", raised the keyboard and wiped the
     * query. That was the flash.
     *
     * Pairing the two events fixes it without any timing guess: the box acts on
     * a KeyUp only when it was there for the KeyDown.
     */
    var sawSelectDown by remember { mutableStateOf(false) }

    LaunchedEffect(isApplied) {
        if (isApplied) {
            hasShownApplied = true
            keyboard?.hide()
            isEditing = false
            kotlinx.coroutines.android.awaitFrame()
            runCatching { doneFR.requestFocus() }
        } else if (hasShownApplied) {
            // LEAVING APPLIED ORPHANS THE FOCUS. Done and "Not that one" are the
            // only focusables that phase has, and pressing either takes them out
            // of composition -- with the focus they were holding. Nothing else
            // claims it, so the panel came back lit and completely dead: no row
            // highlighted, no edge to travel from, and the D-pad doing nothing.
            //
            // The box is claimed FIRST because it is the one focusable this
            // phase always has. The rows are re-ranked asynchronously and may
            // not exist for another frame or two.
            //
            // TWO FRAMES, NOT ONE, and that was the bug. The box is NOT
            // composed during Applied -- that branch renders Done and "Not that
            // one" and nothing else -- so arriving here attaches it for the
            // first time rather than re-focusing something already on screen.
            // One frame left the node unattached, requestFocus threw, the
            // runCatching swallowed it, and nothing held focus at all. Every
            // other arrival request in this file already waits two; this was
            // the one that did not.
            kotlinx.coroutines.android.awaitFrame()
            kotlinx.coroutines.android.awaitFrame()
            runCatching { boxFR.requestFocus() }
            wantRowFocus = true
        }
    }

    // ...then onto the rows the moment they arrive. "Not that one" means the
    // viewer wants a DIFFERENT row, so the list is where they are going -- and
    // landing on the box instead would cost a press and, worse, re-open the
    // keyboard if they pressed OK out of habit.
    LaunchedEffect(wantRowFocus, rows.size) {
        if (!wantRowFocus || rows.isEmpty()) return@LaunchedEffect
        // RETRIED RATHER THAN ATTEMPTED ONCE. requestFocus throws while the
        // node is unattached, runCatching hides that, and the flag used to be
        // cleared whether or not the request landed -- so one frame of bad
        // timing orphaned the panel for good: no row lit, no edge to travel
        // from, the d-pad doing nothing.
        //
        // Cheap to repeat: each pass is one frame, and it stops at the first
        // one that does not throw.
        repeat(5) {
            kotlinx.coroutines.android.awaitFrame()
            if (runCatching { rowFRs[0].requestFocus() }.isSuccess) {
                wantRowFocus = false
                return@LaunchedEffect
            }
        }
        // THE FLOOR. The box is composed for the whole of this phase, so if the
        // rows will not take focus something still holds it. A lit search bar
        // is a worse landing than a lit row; a dead panel is worse than both.
        runCatching { boxFR.requestFocus() }
        wantRowFocus = false
    }

    // SHOWN EXPLICITLY. Elsewhere in the app the keyboard arrives as a side
    // effect of focusing the field, but this field sits over a PlayerView with
    // Compose/View focus interop in play, and that implicit raise is not
    // something to bet the screen on.
    // Whether the box has ever been engaged. Without it the effect below would
    // fire its leaving branch on first composition, resuming a film the viewer
    // had not started typing over yet.
    var hasEdited by remember { mutableStateOf(false) }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            hasEdited = true
            // A FRAME BEFORE THE REQUEST, and this is the whole bug.
            //
            // The field carries focusProperties { canFocus = isEditing }, and a
            // modifier only takes its new value once the recomposition that
            // isEditing triggered has landed. beginEditing used to set the flag
            // and call requestFocus in the same breath, so the request was
            // refused by a gate that had not reopened yet -- the field never
            // took focus, and a keyboard raised for a field that is not focused
            // is a keyboard that does not appear.
            //
            // Then the field's own stale-flag guard saw an unfocused field while
            // isEditing was true, set it back to false, and this effect re-ran
            // and called hide(). Two mechanisms, each correct alone, cancelling
            // each other exactly.
            kotlinx.coroutines.android.awaitFrame()
            runCatching { fieldFR.requestFocus() }
            kotlinx.coroutines.android.awaitFrame()
            keyboard?.show()
            // The keyboard going away is the viewer leaving the box, and it is
            // the only form of that the app can actually observe -- see
            // awaitKeyboardDismissal. Ending editing here is what makes ONE back
            // press resume the picture instead of two.
            if (awaitKeyboardDismissal(localView)) {
                Log.e("scraper-vidapi", "subs: keyboard dismissed, leaving the box")
                isEditing = false
            }
        } else {
            keyboard?.hide()
            // Focus would otherwise be stranded on a field whose canFocus has
            // just gone false.
            if (hasEdited) runCatching { boxFR.requestFocus() }
            // ONLY WHEN NOTHING WAS TYPED, and the distinction is the whole
            // point. Leaving an EMPTY box means the search was abandoned --
            // opened by mistake, or thought better of -- and the picture should
            // come back. Leaving a box with a query in it means the opposite:
            // the keyboard went away because the typing is DONE, four results
            // are on screen, and the next thing to happen is reading them and
            // picking one. Resuming there starts the film under someone who is
            // mid-task, which is what the first version of this did.
            //
            // The anchor is unaffected either way -- it was captured when the
            // box was engaged and never re-read -- so this is about what the
            // viewer is looking at, not about the measurement.
            // RESUME ON LEAVING THE BOX, whatever took the viewer out of it.
            //
            // The first attempt hung this on the field's own BACK branch, which
            // cannot work: the soft keyboard consumes the first BACK to dismiss
            // itself and the app never receives that key. Measured on the box --
            // three "engaged" lines in the log with no disengage between them,
            // and the panel's own handler getting the press instead of the
            // field's. A resume hung on an event that never arrives is a resume
            // that never happens.
            //
            // isEditing going false IS observable, by every route out: the key
            // the app does see, focus moving to the transport, the IME's search
            // action, or focus leaving the panel entirely. One rule covers them
            // all -- the box stops the picture, leaving the box starts it -- and
            // it is a rule a viewer can hold in their head, which the previous
            // one was not.
            if (hasEdited && query.isBlank()) onDisengage()
        }
    }

    fun beginEditing() {
        if (isEditing) return
        // Log.e, not Log.d: R8 strips d/i/v/w from release builds, and release
        // is the only build this device will run.
        Log.e("scraper-vidapi", "subs: search box engaged")
        isEditing = true
        onEngage()
        // Focus is requested by the effect above, once canFocus is true.
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(top = 34.dp)
                // Wider than the by-ear panel's 0.54f: each row can carry three
                // lines of dialogue plus a right-hand column.
                .fillMaxWidth(0.62f)
                .background(PanelBg, RoundedCornerShape(14.dp))
                .border(1.dp, BlueHiveBlue.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(horizontal = 26.dp, vertical = 14.dp)
        ) {
            Text(
                "FIX SUBTITLE SPEED",
                color = BlueHiveBlue.copy(alpha = 0.65f),
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 3.2.sp,
            )
            Spacer(Modifier.height(12.dp))

            if (applied != null) {
                Text(
                    if (applied.movedBy == 0L) "Already lined up" else "Lined up",
                    color = Color.White,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        applied.movedBy == 0L ->
                            "That was already where it should be \u2014 nothing moved."
                        applied.movedBy > 0 ->
                            "Subtitles moved ${fmtSecs(applied.movedBy)}s later."
                        else ->
                            "Subtitles moved ${fmtSecs(applied.movedBy)}s sooner."
                    },
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (applied.drift)
                        "This subtitle drifts \u2014 the rest of the film is corrected too."
                    else
                        "Rewound half a second so you can see it land.",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.focusGroup(),
                ) {
                    // NOT onDone. Backing out of this screen leaves the picture
                    // as it was found; pressing Done means "I am finished, show
                    // me" -- and the line above has just promised a rewind so
                    // the correction can be seen landing, which a frozen frame
                    // makes untrue.
                    PanelButton("Done", doneFR, navSound, onAccept, rightTo = undoFR)
                    // UNDO MATTERS MORE HERE THAN ON THE BY-EAR SCREEN, where a
                    // wrong press was bounded by the lead. A wrong row here can
                    // move the subtitles by minutes, and the viewer finds out by
                    // watching -- so the way back has to be on this screen, not
                    // three menus away under Reset.
                    PanelButton("Not that one", undoFR, navSound, onUndo, leftTo = doneFR)
                }
            } else {
                val searching = phase as FixSeqPhase.Searching

                // WHAT IT ACHIEVES, not what it wants from you. Someone who has
                // never seen this screen needs the payoff first, or "type what
                // you heard" is an instruction with no visible purpose.
                Text(
                    "Search for a line you heard. The subtitles move to fit it.",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))

                // ── the search box ────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(boxFR)
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = playFR
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }
                        // The substitute for an IME-visibility watchdog, which
                        // this activity cannot have: it never calls
                        // setDecorFitsSystemWindows(false), so Compose sees no
                        // IME insets here, and adding that would change the
                        // fullscreen video layout.
                        .onFocusChanged {
                            boxFocused = it.hasFocus || it.isFocused
                            if (!it.hasFocus && !it.isFocused) {
                                isEditing = false
                                // A half-seen press must not outlive the focus
                                // it was aimed at. Without this, a KeyDown here
                                // followed by focus moving away would leave the
                                // flag armed, and the NEXT unrelated KeyUp to
                                // reach the box would open the keyboard.
                                sawSelectDown = false
                            }
                        }
                        .focusable()
                        .onKeyEvent { e ->
                            if (isEditing) return@onKeyEvent false
                            val isSelect = e.key == Key.DirectionCenter ||
                                    e.key == Key.Enter || e.key == Key.NumPadEnter

                            // ACT ON KeyUp, BUT ONLY OUR OWN. Still KeyUp,
                            // because the press that OPENS this overlay comes
                            // from a menu row and its KeyDown would otherwise
                            // land here and raise the keyboard by itself. The
                            // flag adds the other half: "Not that one" is a
                            // ModularButton, which fires on KeyDown, so its
                            // KeyUp arrives after the box has already replaced
                            // it and taken focus. Without the pairing the box
                            // treated a press meant for a button it never saw
                            // as an instruction to start editing.
                            //
                            // Both events are left unconsumed on the way down:
                            // noting one costs nothing and claiming it would
                            // take the key away from anything else that wants
                            // it.
                            if (e.type == KeyEventType.KeyDown) {
                                if (isSelect) sawSelectDown = true
                                return@onKeyEvent false
                            }
                            if (e.type != KeyEventType.KeyUp) return@onKeyEvent false

                            when {
                                isSelect -> {
                                    val ours = sawSelectDown
                                    sawSelectDown = false
                                    // Not ours: the KeyDown belonged to a
                                    // control that is no longer on screen.
                                    if (!ours) false
                                    else { beginEditing(); true }
                                }
                                e.key == Key.Back || e.key == Key.DirectionUp ||
                                        e.key == Key.DirectionDown ||
                                        e.key == Key.DirectionLeft ||
                                        e.key == Key.DirectionRight -> false
                                else -> {
                                    // A real keyboard's first letter must not be
                                    // swallowed, so this returns FALSE and lets
                                    // the character through to the field.
                                    beginEditing(); false
                                }
                            }
                        }
                        .background(
                            if (isEditing) BlueHiveBlue.copy(alpha = 0.22f)
                            else Color.White.copy(alpha = 0.06f),
                            RoundedCornerShape(8.dp)
                        )
                        // THE BORDER CARRIES FOCUS ON ITS OWN. A bloom behind a
                        // box this wide reads as the box being lit rather than
                        // outlined -- the asset is a soft blob sized for a
                        // 104dp button, and stretched across the panel it
                        // washes the field out instead of pointing at it.
                        // Doubling the weight and going to full white is
                        // legible from across a room and changes nothing else.
                        .border(
                            if (boxFocused) 2.dp else 1.dp,
                            if (boxFocused) Color.White else Color.White.copy(alpha = 0.18f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            isEditing = false
                            runCatching { boxFR.requestFocus() }
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(fieldFR)
                            // THE GATE. Without it Compose's geometric focus
                            // search can land here on the way past and the
                            // viewer is stuck in a text field with the keyboard
                            // up -- a bug that is in this repo today on two
                            // other screens.
                            .focusProperties { canFocus = isEditing }
                            // COMPARED BEFORE ASSIGNING. onFocusChanged fires on
                            // losses and on same-state recompositions alike, so
                            // "not focused while editing" is also what the single
                            // frame between raising the flag and the field taking
                            // focus looks like -- and acting on it there cancelled
                            // the mode before it began. Only a field that HAD
                            // focus can lose it.
                            .onFocusChanged { st ->
                                if (st.isFocused) {
                                    Log.e("scraper-vidapi", "subs: search field focused")
                                    fieldHadFocus = true
                                } else if (fieldHadFocus) {
                                    Log.e("scraper-vidapi", "subs: search field lost focus")
                                    fieldHadFocus = false
                                    isEditing = false
                                }
                            }
                            .onKeyEvent { e ->
                                if (e.type != KeyEventType.KeyUp) return@onKeyEvent false
                                when (e.key) {
                                    Key.Back -> {
                                        // Consumed here, so BackOutHandler never
                                        // sees it -- which is why the sound has
                                        // to be played by hand. Backing out of
                                        // typing is still backing out of
                                        // something, and staying silent here
                                        // would make the one BACK press that
                                        // does not close the panel also the one
                                        // that gives no feedback at all.
                                        BlueHiveApplication.playBackOutSound()
                                        keyboard?.hide()
                                        isEditing = false
                                        // The resume is NOT here. It hangs off
                                        // isEditing going false, which this
                                        // assignment causes -- see the effect
                                        // above for why a key branch was the
                                        // wrong place for it.
                                        runCatching { boxFR.requestFocus() }
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        keyboard?.hide()
                                        isEditing = false
                                        runCatching { playFR.requestFocus() }
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        keyboard?.hide()
                                        isEditing = false
                                        runCatching { boxFR.requestFocus() }
                                        true
                                    }
                                    else -> false
                                }
                            },
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text(
                                    // Says what the thing IS, not only what to do
                                    // with it. On a TV a bordered strip is not
                                    // obviously a search bar, and nothing else on
                                    // this panel takes typing at all. Set smaller
                                    // than the typed text so the whole sentence
                                    // fits on one line at panel width.
                                    "This is a search bar, click it and type the voice lines you hear\u2026",
                                    color = Color.White.copy(alpha = 0.38f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        },
                    )
                }

                Spacer(Modifier.height(10.dp))

                // ── transport ─────────────────────────────────────────────────
                // TWO SECONDS, not the player's own ten. The job here is to park
                // on the sentence just heard; ten seconds overshoots a whole
                // exchange and makes the thing it is meant to make precise worse.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.focusGroup(),
                ) {
                    val firstRow = rowFRs.getOrNull(0).takeIf { rows.isNotEmpty() }
                    PanelButton(
                        "\u25C0\u25C0 2s", rewFR, navSound, { onSeek(-FIXSEQ_SEEK_MS) },
                        rightTo = playFR, upTo = boxFR, downTo = firstRow,
                    )
                    PanelButton(
                        // THE DRAWABLE, not a glyph. Two vertical bars in this
                        // font render as a pig-nose at this size; pause.webp is
                        // the artwork the timing screen used for the same reason.
                        if (isPlaying) "Pause" else "\u25B6",
                        playFR, navSound, onTogglePlay,
                        leftTo = rewFR, rightTo = ffFR, upTo = boxFR, downTo = firstRow,
                        dimensions = PlayDimensions, glow = PlayGlow,
                        iconRes = if (isPlaying) R.drawable.pause else null,
                        iconSize = 10.dp,
                    )
                    PanelButton(
                        "2s \u25B6\u25B6", ffFR, navSound, { onSeek(FIXSEQ_SEEK_MS) },
                        leftTo = playFR, upTo = boxFR, downTo = firstRow,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ── results ───────────────────────────────────────────────────
                when {
                    searching.status == CueMatcher.Status.TOO_SHORT -> {
                        if (query.isEmpty()) {
                            // THE WHOLE TUTORIAL, and it erases itself. Three
                            // lines shown only before anything is typed, gone the
                            // moment results exist -- so a first-timer is told
                            // what to do and everyone else never sees it again.
                            // A separate help screen nobody opens would teach
                            // nobody; a permanent block of text would nag the
                            // person who already knows.
                            Column(modifier = Modifier.fillMaxWidth()) {
                                FixSeqStep(
                                    "Step 1:",
                                    "Click on the search bar, and enter the last word you " +
                                            "heard a character spoke.",
                                )
                                // The second step is stepped DOWN AND ACROSS, with
                                // the arrow drawing the move. Two lines of equal
                                // weight read as a list of things to do in no
                                // particular order; one that visibly comes out of
                                // the other reads as a sequence, which is what
                                // this is.
                                Row(verticalAlignment = Alignment.Top) {
                                    StepElbow()
                                    FixSeqStep(
                                        "Step 2:",
                                        "Pick it from the list. Each one shows how far " +
                                                "it would move the subtitles.",
                                        modifier = Modifier.padding(top = 7.dp),
                                    )
                                }
                            }
                        } else {
                            Text(
                                "Keep typing \u2014 three letters or more.",
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    searching.status == CueMatcher.Status.NO_MATCH -> {
                        Text(
                            "Nothing in this subtitle comes close. Check you are on the right " +
                            "subtitle \u2014 a file for another episode will never match.",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 15.sp,
                        )
                    }
                    else -> {
                        searching.repeatHint?.let { hint ->
                            Text(
                                hint,
                                color = Color(0xFFFFD230).copy(alpha = 0.85f),
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 14.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth().focusGroup(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rows.forEachIndexed { i, row ->
                                FixSeqResultRow(
                                    row = row,
                                    onClick = { onPick(row) },
                                    modifier = Modifier
                                        .focusRequester(rowFRs[i])
                                        .focusProperties {
                                            up = if (i == 0) playFR else rowFRs[i - 1]
                                            down = if (i == rows.lastIndex) FocusRequester.Cancel
                                                   else rowFRs[i + 1]
                                            left = FocusRequester.Cancel
                                            right = FocusRequester.Cancel
                                        },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Press back to close",
                color = Color.White.copy(alpha = 0.38f),
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * How big the captions are drawn, as a fraction of the picture's height.
 *
 * A FRACTION, NOT AN sp VALUE, and that is the whole reason these exist. The
 * player previously called setUserDefaultTextSize, which takes the system font
 * scale -- so the captions came out a different size on every box, and nothing
 * the app did could predict it. A fraction of viewport height is the same
 * proportion of the picture at any resolution.
 *
 * Android's own default is 0.0533, which Medium sits a touch above. SMALL is
 * this app's default: every size above it is legible, and the one that reads
 * best is not always the one you want covering the picture.
 */
enum class SubtitleSizeChoice(val label: String, val fraction: Float) {
    Small("Small", 0.046f),
    Medium("Medium", 0.058f),
    Large("Large", 0.070f),
    Huge("Very large", 0.085f),
}

/**
 * The typeface.
 *
 * FIVE, AND NOT THE OTHER FOUR THE APP SHIPS. Lalezar, Passion One and Pattaya
 * are display faces -- they are in the project for headings, and set as a wall
 * of dialogue at the bottom of a moving picture they are unreadable. Dongle is
 * light and condensed, which is the opposite of what a caption needs. Offering
 * every font on hand would be offering four ways to make this worse.
 *
 * Serif and Monospace earn their places by being genuinely different rather
 * than merely available: serifs hold up well on a large panel at distance, and
 * monospace is the one people with certain reading difficulties ask for.
 */
enum class SubtitleFontChoice(val label: String, val family: FontFamily) {
    InterSemiBold("Inter SemiBold", AppTypography.interSemiBold),
    InterBold("Inter Bold", AppTypography.interBold),
    Sans("Sans serif", FontFamily.SansSerif),
    Serif("Serif", FontFamily.Serif),
    Mono("Monospace", FontFamily.Monospace),
}

/**
 * What keeps white text legible against the picture.
 *
 * THE ONE THAT ACTUALLY DECIDES whether captions are readable, which is why it
 * is here next to size and font rather than left as a constant.
 *
 * DROP SHADOW IS THE DEFAULT, on looks. It is also the one with a weakness worth
 * knowing: a shadow separates text from what is behind it only when that is
 * darker, so a bright frame is exactly where it gives least. Outline has no such
 * failure and is one press away. Black box is what most TV boxes default to and
 * what this screen exists to escape, kept because some people genuinely prefer
 * it over busy footage.
 */
enum class SubtitleEdgeChoice(val label: String) {
    Outline("Outline"),
    Shadow("Drop shadow"),
    Box("Black box"),
    None("None"),
}

/** The three choices, as the menu row states them. */
fun subtitleStyleSummary(size: SubtitleSizeChoice, font: SubtitleFontChoice): String =
    "${size.label} / ${font.label}"

/**
 * Size, typeface and edge, applied to the real captions as they are changed.
 *
 * NO MOCK PREVIEW PANEL. The captions are already on screen behind this one --
 * the panel is top-anchored and they sit at the bottom -- and every change here
 * reaches them immediately. A preview would be a second renderer to keep honest,
 * and the moment it disagreed with the real thing it would be worse than
 * nothing. The sample line below is for the case where no cue happens to be
 * showing, and it says so.
 *
 * ONE FOCUSABLE PER SETTING, stepped with left and right. A row of chips per
 * setting would be fifteen focus targets on a screen with three decisions in it,
 * and every one of them an edge to wire.
 */
@Composable
fun SubtitleStyleOverlay(
    size: SubtitleSizeChoice,
    font: SubtitleFontChoice,
    edge: SubtitleEdgeChoice,
    onSize: (SubtitleSizeChoice) -> Unit,
    onFont: (SubtitleFontChoice) -> Unit,
    onEdge: (SubtitleEdgeChoice) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    val sizeFR = remember { FocusRequester() }
    val fontFR = remember { FocusRequester() }
    val edgeFR = remember { FocusRequester() }
    val resetFR = remember { FocusRequester() }

    var navArmed by remember { mutableStateOf(false) }
    val navSound: () -> Unit = {
        if (navArmed) BlueHiveApplication.playNavClickSound() else navArmed = true
    }

    BackOutHandler { onDone() }
    LaunchedEffect(Unit) { runCatching { sizeFR.requestFocus() } }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(top = 34.dp)
                .fillMaxWidth(0.52f)
                .background(PanelBg, RoundedCornerShape(14.dp))
                .border(1.dp, BlueHiveBlue.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(horizontal = 26.dp, vertical = 14.dp)
        ) {
            Text(
                "SUBTITLE CUSTOMIZATION",
                color = BlueHiveBlue.copy(alpha = 0.65f),
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 3.2.sp,
            )
            Spacer(Modifier.height(12.dp))

            // The honest preview is the picture behind this panel; this is only
            // for when nothing happens to be on screen.
            Text(
                "The quick brown fox",
                color = Color.White,
                fontFamily = font.family,
                fontSize = (17 * (size.fraction / SubtitleSizeChoice.Medium.fraction)).sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Changes apply to the subtitles behind this panel as you make them.",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier.fillMaxWidth().focusGroup(),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                StyleRow(
                    label = "Text size",
                    value = size.label,
                    onPrev = { onSize(size.stepped(-1)) },
                    onNext = { onSize(size.stepped(1)) },
                    onFocusGained = navSound,
                    modifier = Modifier
                        .focusRequester(sizeFR)
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = fontFR
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        },
                )
                StyleRow(
                    label = "Font",
                    value = font.label,
                    onPrev = { onFont(font.stepped(-1)) },
                    onNext = { onFont(font.stepped(1)) },
                    onFocusGained = navSound,
                    modifier = Modifier
                        .focusRequester(fontFR)
                        .focusProperties {
                            up = sizeFR
                            down = edgeFR
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        },
                )
                StyleRow(
                    label = "Edge",
                    value = edge.label,
                    onPrev = { onEdge(edge.stepped(-1)) },
                    onNext = { onEdge(edge.stepped(1)) },
                    onFocusGained = navSound,
                    modifier = Modifier
                        .focusRequester(edgeFR)
                        .focusProperties {
                            up = fontFR
                            down = resetFR
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        },
                )
            }

            Spacer(Modifier.height(12.dp))
            PanelButton(
                "Reset", resetFR, navSound, onReset,
                upTo = edgeFR,
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "Left and right to change \u2014 press back to close",
                color = Color.White.copy(alpha = 0.38f),
                fontSize = 10.sp,
            )
        }
    }
}

/** Next/previous member, wrapping. Keeps the three rows stepping identically. */
private inline fun <reified T : Enum<T>> T.stepped(delta: Int): T {
    val all = enumValues<T>()
    return all[((ordinal + delta) % all.size + all.size) % all.size]
}

/**
 * One setting: what it is on the left, what it is set to on the right.
 *
 * LEFT AND RIGHT ARE CONSUMED HERE rather than wired as focus moves, so the
 * arrows change the VALUE instead of leaving the row -- which is what the
 * triangles either side of the value promise. Up and down still travel, so
 * nothing traps the viewer.
 */
@Composable
private fun StyleRow(
    label: String,
    value: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFocusGained: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocusGained() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (focused) BlueHiveBlue.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f),
                RoundedCornerShape(8.dp)
            )
            .then(
                if (focused) Modifier.border(1.dp, BlueHiveBlue, RoundedCornerShape(8.dp))
                else Modifier
            )
            .focusable(interactionSource = interaction)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onPrev(); true }
                    Key.DirectionRight -> { onNext(); true }
                    else -> false
                }
            }
            .padding(horizontal = 13.dp, vertical = 9.dp)
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            "\u25C0",
            color = if (focused) Color.White else Color.White.copy(alpha = 0.25f),
            fontSize = 9.sp,
        )
        Text(
            value,
            color = Color.White.copy(alpha = if (focused) 1f else 0.60f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(118.dp),
        )
        Text(
            "\u25B6",
            color = if (focused) Color.White else Color.White.copy(alpha = 0.25f),
            fontSize = 9.sp,
        )
    }
}

/**
 * BACK, with the sound the rest of the app makes when a screen closes.
 *
 * Every panel in this file goes through this rather than calling BackHandler
 * directly, because the subtitle screens were the one corner of the app where
 * backing out said nothing -- the profile, catalog, search and sidebar screens
 * have all played it for a long time. A helper rather than four copies of the
 * same two lines, so the next panel added here cannot quietly forget it.
 *
 * ENABLED ALWAYS, like the handlers it replaces: the activity hands BACK to the
 * view hierarchy, and a panel that does not consume it lets the default fire and
 * end playback.
 */
@Composable
private fun BackOutHandler(onBack: () -> Unit) {
    BackHandler(enabled = true) {
        BlueHiveApplication.playBackOutSound()
        onBack()
    }
}

/** One line of the first-run explanation. See [SubtitleFixSeqOverlay]. */
@Composable
private fun FixSeqStep(label: String, text: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.Top, modifier = modifier) {
        Text(
            label,
            color = BlueHiveBlue,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

/**
 * The turn from step one into step two: down, round a corner, and across.
 *
 * DRAWN RATHER THAN TYPED. A unicode arrow sits on the text baseline and points
 * along the line it is already on, which is the one direction this is not
 * describing. The move is downward THEN sideways, and a path that actually turns
 * says that where a glyph cannot.
 *
 * The vertical leg lines up under the "S" of the label above it, so the elbow
 * reads as coming out of step one rather than sitting beside it.
 */
@Composable
private fun StepElbow() {
    Canvas(
        modifier = Modifier
            .padding(start = 6.dp, end = 7.dp)
            .width(28.dp)
            .height(22.dp)
    ) {
        val line = 1.25.dp.toPx()
        val x = 3.dp.toPx()
        val y = size.height - 6.dp.toPx()
        val radius = 7.dp.toPx()
        val tip = size.width - line
        val head = 4.dp.toPx()

        // ── THE STEM FADES IN ────────────────────────────────────────────
        // It used to begin at full strength on the canvas's very first pixel,
        // which is what made it read as hooked ONTO step 2 rather than coming
        // OUT of step 1: a line with a hard start has a start, and the eye
        // looks for what it is attached to. Fading the first few dp gives it
        // an origin in the paragraph above instead of an endpoint.
        drawPath(
            Path().apply {
                moveTo(x, 0f)
                lineTo(x, y - radius)
                quadraticBezierTo(x, y, x + radius, y)
                lineTo(tip - head * 0.9f, y)
            },
            brush = Brush.verticalGradient(
                0f to BlueHiveBlue.copy(alpha = 0f),
                0.5f to BlueHiveBlue,
            ),
            style = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // ── A CHEVRON, NOT A FILLED TRIANGLE ─────────────────────────────
        // The solid wedge was the other half of the hand-drawn look: a blunt
        // 5.4dp-wide shape welded to a 1.4dp line, two weights in one mark.
        // Stroking the head at the stem's own width makes it one continuous
        // stroke, which is what every drawn-well arrow in a UI actually is.
        drawPath(
            Path().apply {
                moveTo(tip - head, y - head * 0.78f)
                lineTo(tip, y)
                lineTo(tip - head, y + head * 0.78f)
            },
            color = BlueHiveBlue,
            style = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * One candidate line.
 *
 * THE RIGHT-HAND COLUMN IS THE POINT. Four rows reading "Yeah." are
 * indistinguishable as text and perfectly distinguishable as consequences: one
 * says "moves 3.4s later" and one says "moves 14m sooner". It is the only thing
 * on this screen that lets a wrong pick be rejected BEFORE it is committed,
 * which is the advantage this screen has over pressing a button by ear.
 */
@Composable
private fun FixSeqResultRow(
    row: FixSeqRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val dim = if (row.tooFar) 0.40f else 1f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (focused) BlueHiveBlue.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f),
                RoundedCornerShape(8.dp)
            )
            .then(
                if (focused) Modifier.border(1.dp, BlueHiveBlue, RoundedCornerShape(8.dp))
                else Modifier
            )
            .focusable(interactionSource = interaction)
            .onSelectKey(onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // THE LINE BEFORE, and it is the primary disambiguator rather than a
            // nicety: for a generic line nobody remembers the words, they
            // remember the exchange. "Yeah." after "Then why did you take the
            // car?" is a visibly different moment from "Yeah." after "Get in."
            row.prevText?.let {
                Text(
                    "\u2191 $it",
                    color = Color.White.copy(alpha = 0.45f * dim),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                boldMatches(row.text, row.matchTokens),
                color = Color.White.copy(alpha = dim),
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp,
            )
            row.tailText?.let {
                Text(
                    "+ $it",
                    color = Color.White.copy(alpha = 0.45f * dim),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.identicalCount > 1 || row.tooFar) {
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (row.identicalCount > 1) {
                        Badge("\u00D7${row.identicalCount}", BlueHiveBlue)
                    }
                    if (row.tooFar) Badge("TOO FAR", Color(0xFFBDBDBD))
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                row.timeLabel,
                color = Color.White.copy(alpha = 0.50f * dim),
                fontSize = 11.sp,
            )
            Text(
                row.shiftLabel,
                color = (if (row.tooFar) Color(0xFFBDBDBD) else BlueHiveBlue).copy(alpha = dim),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * The matched words in bold.
 *
 * Two jobs, and the second is the one that matters: it is the only honest
 * account of WHY a row is in the list, and it separates a row from its
 * near-twins. A row lit up on "you already" alone is visibly the wrong row,
 * where the same row in plain text looks perfectly plausible.
 */
private fun boldMatches(text: String, tokens: List<String>): AnnotatedString =
    buildAnnotatedString {
        if (tokens.isEmpty()) { append(text); return@buildAnnotatedString }
        var i = 0
        while (i < text.length) {
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            val word = text.substring(start, i)
            if (word.isNotEmpty()) {
                val norm = CueMatcher.normalize(word)
                val hit = norm.isNotEmpty() && tokens.any { t ->
                    norm == t || (t.length >= 3 && norm.startsWith(t))
                }
                if (hit) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(word) }
                else append(word)
            }
            while (i < text.length && text[i].isWhitespace()) { append(text[i]); i++ }
        }
    }

/** A menu row: what it does on the left, what it is currently set to on the right. */
@Composable
private fun MenuRow(
    label: String,
    detail: String,
    onClick: () -> Unit,
    onFocusGained: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // KEYED ON THE STATE, not an onFocusChanged callback: that one also fires on
    // losses and on same-state recompositions, so detecting a GAIN with it means
    // remembering the previous value by hand. LaunchedEffect already only runs
    // when the key changes, so this fires exactly on false -> true.
    LaunchedEffect(focused) { if (focused) onFocusGained() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (focused) BlueHiveBlue.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f),
                RoundedCornerShape(8.dp)
            )
            .then(
                if (focused) Modifier.border(1.dp, BlueHiveBlue, RoundedCornerShape(8.dp))
                else Modifier
            )
            .focusable(interactionSource = interaction)
            .onSelectKey(onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (detail.isNotEmpty()) {
            Text(detail, color = Color.White.copy(alpha = 0.50f), fontSize = 11.sp)
        }
    }
}
