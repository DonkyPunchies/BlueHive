package com.example.bluehive.webview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Shown when extraction fails for a reason the user can act on, instead of the
 * screen silently returning to the source buttons.
 *
 * Built for the network-filter case: a blocked domain used to look exactly like
 * a dead source — two 60s timeouts and no explanation — when the actual cause was
 * an ISP content filter that no amount of retrying could get past. Naming it is
 * the whole point, so the message is passed through verbatim.
 *
 * Same visual language as ExtractionLoadingOverlay and SubtitlePickerOverlay:
 * one dark scrim, one bordered panel, the BlueHive wordmark.
 *
 * IT IS A TRUE MODAL. Only OK and BACK do anything; every other key is swallowed
 * so the details screen behind can't be navigated while this is up. Getting there
 * took three separate guards — see below — because a Compose overlay drawn inside
 * another screen's tree does NOT get focus just by being on top of it.
 */
@Composable
fun ExtractionErrorOverlay(message: String, onDismiss: () -> Unit) {
    val ok = remember { FocusRequester() }

    // GUARD 1: win the focus race. This overlay appears on an Activity resume —
    // the extractor closing is what both dismisses that activity and raises this
    // — and the details screen re-asserts its own focus on that same resume. One
    // request loses; keep asking for a few frames, then once more after it all
    // settles. (The caller also stands its restoration down while we're up.)
    LaunchedEffect(Unit) {
        repeat(FOCUS_ATTEMPTS) {
            if (takeFocus(ok)) return@LaunchedEffect
            withFrameNanos { }
        }
        delay(FOCUS_SETTLE_MS)
        takeFocus(ok)
    }

    BackHandler(enabled = true) { onDismiss() }

    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            // GUARD 3: swallow everything. Even if focus somehow sits inside this
            // subtree on something other than OK, no key may reach the screen
            // behind. BACK is deliberately let through — it is delivered to the
            // Activity, and the BackHandler above only runs if we don't eat it.
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back || e.key == Key.Escape) return@onPreviewKeyEvent false
                val select = e.key == Key.Enter || e.key == Key.DirectionCenter ||
                        e.key == Key.NumPadEnter
                if (select && e.type == KeyEventType.KeyUp) onDismiss()
                true
            }
            .background(Color(0xD9000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth(0.50f)
                .background(Color(0xF2070B18), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFFFFA726).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .padding(horizontal = 26.dp, vertical = 22.dp)
        ) {
            Text(
                "B L U E H I V E",
                color = Color(0xFF4477FF).copy(alpha = 0.65f),
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 3.2.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Can't play this source",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "OK",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .focusRequester(ok)
                    // GUARD 2: nowhere to go. Compose looks for the nearest
                    // focusable in the pressed direction and would happily hand
                    // focus to the details screen behind the scrim; Cancel stops
                    // the search instead of letting it escape.
                    .focusProperties {
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    }
                    .background(
                        if (focused) Color(0xFF4477FF).copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f),
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        1.dp,
                        if (focused) Color(0xFF4477FF) else Color.White.copy(alpha = 0.10f),
                        RoundedCornerShape(8.dp)
                    )
                    .focusable(interactionSource = interaction)
                    .padding(horizontal = 34.dp, vertical = 10.dp)
            )
        }
    }
}

/** Frames to keep re-asserting focus for while the screen behind settles. */
private const val FOCUS_ATTEMPTS = 6
private const val FOCUS_SETTLE_MS = 150L

/** requestFocus() returns Unit and does NOT throw when the target is detached —
 *  it just no-ops — so use the overload that reports whether focus actually moved. */
@OptIn(ExperimentalComposeUiApi::class)
private fun takeFocus(requester: FocusRequester): Boolean =
    runCatching { requester.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
