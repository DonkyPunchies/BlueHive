package com.example.bluehive.webview

import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluehive.R
import kotlin.math.*




/**
 * Process-wide cache for the loading-overlay bee logo.
 *
 * blue_bee.png is decoded through BitmapFactory. On 2 GB hardware the overlay
 * appears at a memory-pressure peak (heavy details screen + trailer teardown +
 * extractor spinning up), where the decode can return null → Compose casts null
 * to BitmapDrawable → NPE → the whole app crashes back to the home screen.
 *
 * prime() is called from the details screens as they first compose (memory is
 * comparatively free then) and decodes the bitmap ONCE, off the main thread. It
 * only caches on success, so a failed attempt simply retries on the next entry.
 */
object BeeLogo {
    @Volatile
    var bitmap: ImageBitmap? = null
        private set

    fun prime(context: Context) {
        if (bitmap != null) return
        bitmap = try {
            ImageBitmap.imageResource(context.resources, R.drawable.blue_bee)
        } catch (t: Throwable) {
            Log.w("scraper", "BeeLogo prime failed (low memory): ${t.message}")
            null
        }
    }
}

@Composable
fun ExtractionLoadingOverlay(status: String, stage: Int) {
    val inf = rememberInfiniteTransition(label = "loader")

    // These are read ONLY inside graphicsLayer{}/draw lambdas below — i.e. in the
    // draw/render phase — so changing them NEVER triggers recomposition. The whole
    // overlay composes once; only the RenderThread keeps spinning/pulsing.
    val outerRing by inf.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "outerRing"
    )
    val innerRing by inf.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "innerRing"
    )
    val glow by inf.animateFloat(
        0.45f, 1f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )
    val orbit1 by inf.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "o1"
    )
    val orbit2 by inf.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "o2"
    )
    val orbit3 by inf.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "o3"
    )

    // progress/stage/status only change at stage transitions, not per-frame — fine to read in composition.
    val progress by animateFloatAsState(
        targetValue = stage / 5f,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "progress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xBF000000)),
        contentAlignment = Alignment.Center
    ) {
        HoneycombBackground()

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "B L U E H I V E",
                color = Color(0xFF4477FF).copy(alpha = 0.65f),
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 4.sp,
            )

            Spacer(Modifier.height(30.dp))

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {

                // ── Glows + hexagon ───────────────────────────────────────────────
                // Drawn ONCE at full intensity (drawWithCache, no state reads inside).
                // The pulse is graphicsLayer.alpha = glow → RenderThread, no redraw.
                Spacer(
                    modifier = Modifier
                        // requiredSize (not size) so the layer can exceed the 200dp
                        // parent Box. graphicsLayer{alpha} renders to an offscreen
                        // buffer the node's size, and the pulsing glow discs draw to
                        // radius 120dp — 20dp past a 200dp box — so they were being
                        // clipped flat on all four sides. 260dp gives the discs room;
                        // the larger inset keeps outerR at 88dp so every visual element
                        // stays exactly the same size, just uncropped.
                        .requiredSize(260.dp)
                        .graphicsLayer { alpha = glow }
                        .drawWithCache {
                            val cx     = size.width  / 2f
                            val cy     = size.height / 2f
                            val center = Offset(cx, cy)
                            val outerR = size.width  / 2f - 42.dp.toPx()
                            val hexR   = outerR * 0.30f
                            val hexStroke = Stroke(1.5.dp.toPx())

                            val hexPath = Path().apply {
                                for (i in 0 until 6) {
                                    val a = Math.toRadians((60.0 * i) - 30.0)
                                    val x = cx + hexR * cos(a).toFloat()
                                    val y = cy + hexR * sin(a).toFloat()
                                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                                }
                                close()
                            }
                            val hexBrush = Brush.radialGradient(
                                listOf(
                                    Color(0xFF4466FF),
                                    Color(0xFF6622CC).copy(alpha = 0.85f),
                                    Color(0xFF080420).copy(alpha = 0.95f)
                                ),
                                center = center,
                                radius = hexR
                            )

                            onDrawBehind {
                                repeat(4) { i ->
                                    drawCircle(
                                        color  = Color(0xFF2244FF).copy(alpha = 0.055f * (4 - i)),
                                        radius = outerR + (i + 1) * 8.dp.toPx(),
                                        center = center
                                    )
                                }
                                drawPath(hexPath, brush = hexBrush)
                                drawPath(
                                    hexPath,
                                    color = Color(0xFF88AAFF).copy(alpha = 0.9f),
                                    style = hexStroke
                                )
                            }
                        }
                )

                // ── Outer arc ─────────────────────────────────────────────────────
                // Arc drawn ONCE; graphicsLayer.rotationZ spins it on the RenderThread.
                Spacer(
                    modifier = Modifier
                        .size(200.dp)
                        .graphicsLayer { rotationZ = outerRing }
                        .drawWithCache {
                            val center  = Offset(size.width / 2f, size.height / 2f)
                            val stroke  = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                            val brush   = Brush.sweepGradient(
                                listOf(
                                    Color.Transparent,
                                    Color(0xFF0044FF),
                                    Color(0xFF9922FF),
                                    Color(0xFF0044FF),
                                    Color.Transparent
                                ),
                                center = center
                            )
                            val topLeft = Offset(12.dp.toPx(), 12.dp.toPx())
                            val arcSize = Size(size.width - 24.dp.toPx(), size.height - 24.dp.toPx())
                            onDrawBehind {
                                drawArc(brush, 0f, 260f, false, topLeft, arcSize, style = stroke)
                            }
                        }
                )

                // ── Inner counter-rotating arc ────────────────────────────────────
                Spacer(
                    modifier = Modifier
                        .size(200.dp)
                        .graphicsLayer { rotationZ = -innerRing }
                        .drawWithCache {
                            val outerR  = size.width / 2f - 12.dp.toPx()
                            val innerR  = outerR * 0.76f
                            val m       = 12.dp.toPx() + (outerR - innerR)
                            val center  = Offset(size.width / 2f, size.height / 2f)
                            val stroke  = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                            val brush   = Brush.sweepGradient(
                                listOf(
                                    Color.Transparent,
                                    Color(0xFF6622FF).copy(0.9f),
                                    Color(0xFF0066FF).copy(0.9f),
                                    Color.Transparent
                                ),
                                center = center
                            )
                            val topLeft = Offset(m, m)
                            val arcSize = Size(innerR * 2f, innerR * 2f)
                            onDrawBehind {
                                drawArc(brush, 0f, 150f, false, topLeft, arcSize, style = stroke)
                            }
                        }
                )

                // ── Orbiting dots ─────────────────────────────────────────────────
                // Each dot is a static circle in its own RenderThread-rotated layer.
                // alpha = glow pulses them; rotationZ orbits them. Zero redraw, zero
                // recomposition — pure compositor transforms.
                OrbitDot(angleProvider = { orbit1 },          glowProvider = { glow }, color = Color(0xFF55AAFF), dotDp = 5f)
                OrbitDot(angleProvider = { orbit2 + 120f },   glowProvider = { glow }, color = Color(0xFFAA44FF), dotDp = 4f)
                OrbitDot(angleProvider = { orbit3 + 240f },   glowProvider = { glow }, color = Color(0xFF33DDFF), dotDp = 3.5f)

                // Use the cache primed on details-screen entry. If it somehow
                // isn't primed yet, fall back to a guarded live decode so the
                // overlay can never crash — worst case the logo is briefly absent.
                val ctx = LocalContext.current
                val beeBitmap = remember {
                    BeeLogo.bitmap ?: runCatching {
                        ImageBitmap.imageResource(ctx.resources, R.drawable.blue_bee)
                    }.getOrNull()
                }
                beeBitmap?.let { bmp ->
                    Image(
                        bitmap             = bmp,
                        contentDescription = "BlueHive",
                        contentScale       = ContentScale.Fit,
                        modifier           = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(Modifier.height(38.dp))

            AnimatedContent(
                targetState = status,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                label = "status"
            ) { s ->
                Text(
                    s,
                    color         = Color.White.copy(0.88f),
                    fontSize      = 17.sp,
                    fontWeight    = FontWeight.Light,
                    textAlign     = TextAlign.Center,
                    letterSpacing = 0.3.sp,
                    modifier      = Modifier.widthIn(max = 300.dp)
                )
            }

            Spacer(Modifier.height(26.dp))

            Box(
                Modifier
                    .width(220.dp)
                    .height(2.dp)
                    .background(Color.White.copy(0.08f))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF0044FF), Color(0xFFAA33FF))
                            )
                        )
                )
            }

            Spacer(Modifier.height(18.dp))

            // Stage dots — colors come from `stage` (not animated). The active dot
            // pulses via graphicsLayer.alpha = glow, so glow is NEVER read in
            // composition. THIS is what was forcing 60fps recomposition before.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(5) { i ->
                    val active = i == stage - 1
                    val filled = i < stage
                    val dotColor = when {
                        active -> Color(0xFF88BBFF)
                        filled -> Color(0xFF4466FF)
                        else   -> Color.White.copy(0.12f)
                    }
                    Box(
                        Modifier
                            .size(if (active) 7.dp else 5.dp)
                            .graphicsLayer { if (active) alpha = glow }
                            .background(dotColor, CircleShape)
                    )
                }
            }
        }
    }
}

// A single orbiting dot: a static halo+core drawn once, orbited and pulsed entirely
// on the RenderThread via graphicsLayer. transformOrigin defaults to center, so
// rotationZ sweeps the dot around the ring. Nothing here recomposes or redraws.
@Composable
private fun OrbitDot(
    angleProvider: () -> Float,
    glowProvider: () -> Float,
    color: Color,
    dotDp: Float,
) {
    Spacer(
        modifier = Modifier
            .size(200.dp)
            .graphicsLayer {
                rotationZ = angleProvider()
                alpha = glowProvider()
            }
            .drawWithCache {
                val cx     = size.width / 2f
                val outerR = size.width / 2f - 12.dp.toPx()
                val orbitR = outerR * 0.62f
                val r      = dotDp.dp.toPx()
                // Dot sits at the top of the ring (12 o'clock); rotation sweeps it.
                val pos = Offset(cx, cx - orbitR)
                onDrawBehind {
                    drawCircle(color.copy(alpha = 0.25f), r * 3f, pos)
                    drawCircle(color, r, pos)
                }
            }
    )
}

@Composable
private fun HoneycombBackground() {
    Canvas(Modifier.fillMaxSize()) {
        val hexSize = 34.dp.toPx()
        val hexW    = hexSize * sqrt(3f)
        val hexH    = hexSize * 2f
        val cols    = (size.width  / hexW).toInt() + 2
        val rows    = (size.height / (hexH * 0.75f)).toInt() + 2

        for (row in -1..rows) {
            for (col in -1..cols) {
                val xOff = if (row % 2 == 0) 0f else hexW / 2f
                val cx   = col * hexW + xOff
                val cy   = row * hexH * 0.75f

                val path = Path().apply {
                    for (i in 0 until 6) {
                        val a = Math.toRadians((60.0 * i) - 30.0)
                        val x = cx + hexSize * cos(a).toFloat()
                        val y = cy + hexSize * sin(a).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(
                    path,
                    Color(0xFF3355FF).copy(alpha = 0.045f),
                    style = Stroke(0.6.dp.toPx())
                )
            }
        }
    }
}