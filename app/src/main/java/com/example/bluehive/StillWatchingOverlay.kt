package com.example.bluehive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * "Still watching?" — shown over a player that has been rolling with nobody
 * touching the remote.
 *
 * DELIBERATELY NOT INTERACTIVE, and that is the whole trick. There is no button to
 * find and no focus to steal, because the window-callback hook in
 * BlueHiveApplication already routes EVERY key and touch event on every activity
 * through [IdleWatcher.noteInteraction] — so any remote press at all dismisses
 * this, including the one the user was about to make anyway. A focusable
 * "Continue" control would look more familiar and would do strictly less: it can
 * only be activated by a press that has already dismissed the overlay on its way
 * through dispatch.
 *
 * It also means this composable can be dropped into any player without touching
 * that player's focus handling, D-pad routing, or back-press behaviour — none of
 * which are simple in [com.example.bluehive.webview.hls.HlsPlayerActivity].
 *
 * NO COUNTDOWN SHOWN. The grace is deliberately short and a visible timer over
 * somebody's film reads as a threat rather than a check-in. The copy says what
 * happens instead.
 *
 * Overlay ONLY — draw it on top of the player, never in place of it. Playback
 * carries on underneath while this is up; [IdleWatcher] pauses it only if the
 * grace actually expires.
 */
@Composable
fun StillWatchingOverlay(modifier: Modifier = Modifier) {
    // collectAsState, not collectAsStateWithLifecycle: lifecycle-runtime-compose is
    // not a dependency of this module, and adding one for a single Boolean would be
    // a poor trade. The difference is that collection continues while the activity
    // is stopped — for a flow that changes at most twice an hour, that is nothing.
    val visible by IdleWatcher.stillWatching.collectAsState()
    if (!visible) return

    val codeFont = FontFamily.Monospace

    Box(
        modifier = modifier
            .fillMaxSize()
            // Scrim rather than an opaque fill: the viewer should be able to see
            // that their film is still there and still playing, which is most of
            // the reassurance this screen has to offer.
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0C0C0C))
                .padding(horizontal = 56.dp, vertical = 40.dp),
        ) {
            Text(
                text          = "Still watching?",
                color         = Color.White,
                fontSize      = 24.sp,
                fontWeight    = FontWeight.Bold,
                fontFamily    = codeFont,
                letterSpacing = 1.sp,
                textAlign     = TextAlign.Center,
            )

            Spacer(Modifier.height(18.dp))

            Text(
                text       = "Press any button to keep watching.\n\n" +
                             "Otherwise this will pause shortly and\n" +
                             "release the streaming slot for others\n" +
                             "on your account.",
                color      = Color.White.copy(alpha = 0.70f),
                fontSize   = 14.sp,
                fontFamily = codeFont,
                fontWeight = FontWeight.Light,
                textAlign  = TextAlign.Center,
            )
        }
    }
}
