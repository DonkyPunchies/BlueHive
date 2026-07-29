package com.example.bluehive.utilities

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ✅ Watched marker — the triangular "bookmark" flag with a tick that marks
// something you've already watched. Lives here (not in a screen file) because it
// is rendered on BOTH the episode grid and poster cards; sharing one implementation
// means the two can't drift apart when a knob is tweaked.
//
// Sits in the TOP-RIGHT so it never collides with the episode-number badge, which
// is bottom/top-left.
val WATCHED_FOLD_SIZE         = 34.dp               // leg length on the episode grid
val WATCHED_FOLD_COLOR        = Color(0xFF34D3C4)   // teal — deliberately NOT the blue focus glow
val WATCHED_FOLD_TICK_COLOR   = Color(0xFF06342F)   // dark teal, reads against the fold
val WATCHED_FOLD_TICK_STROKE  = 2.4.dp              // thickness of the tick strokes
// ↕️ Overall size of the tick (its widest span), INDEPENDENT of the fold size —
// shrink this to get a smaller check without touching the triangle. The tick is
// drawn outward from a fixed centre, so smaller values always stay inside the fold.
val WATCHED_FOLD_TICK_SIZE    = 8.dp

/**
 * The corner fold plus its tick, drawn in ONE Canvas (so it needs no
 * Material-Icons dependency).
 *
 * Caller supplies alignment/clipping; this just draws at [size].
 */
@Composable
fun WatchedCornerFold(
    modifier: Modifier = Modifier,
    size: Dp = WATCHED_FOLD_SIZE,
    tickSize: Dp = WATCHED_FOLD_TICK_SIZE,
    tickStroke: Dp = WATCHED_FOLD_TICK_STROKE,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.width

        // The fold: top-right triangle (hypotenuse runs corner-to-corner).
        drawPath(
            path = Path().apply {
                moveTo(0f, 0f)
                lineTo(s, 0f)
                lineTo(s, s)
                close()
            },
            color = WATCHED_FOLD_COLOR
        )

        // The tick. Points are unit offsets from a fixed centre inside the triangle,
        // scaled by tickSize — that decoupling is what lets the tick shrink while
        // the fold stays put.
        val centreX = s * 0.65f
        val centreY = s * 0.325f
        val tick    = tickSize.toPx()
        fun at(dx: Float, dy: Float) = Offset(centreX + dx * tick, centreY + dy * tick)

        val elbow = at(-0.167f, 0.417f)
        drawLine(
            color = WATCHED_FOLD_TICK_COLOR,
            start = at(-0.500f, 0.050f),
            end   = elbow,
            strokeWidth = tickStroke.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = WATCHED_FOLD_TICK_COLOR,
            start = elbow,
            end   = at(0.500f, -0.417f),
            strokeWidth = tickStroke.toPx(),
            cap = StrokeCap.Round
        )
    }
}
