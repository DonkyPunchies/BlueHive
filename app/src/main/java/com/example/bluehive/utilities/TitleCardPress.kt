package com.example.bluehive.utilities

import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * THE PRESS ANIMATION FOR EVERY TITLE CARD IN THE APP.
 *
 * The card shrinks, springs back to full size, and only THEN opens the details
 * screen.
 *
 * WHY THE ORDER MATTERS. Three of the four card lists already animated —
 * TvWideLazyRow, MediaCatalog and SidebarFavorites each shrank to 0.80 over a
 * 250ms tween — but every one of them called its navigate function on the SAME
 * FRAME as setting pressed = true:
 *
 *     isPressed = true
 *     BlueHiveApplication.playClickSound()
 *     onItemClick(mediaItem)          // details screen covers the card instantly
 *
 * so the animation existed and was never seen. What reached the viewer was the
 * first frame or two of a dip before the next screen drew over it, which reads
 * as a flicker rather than as a press. RecommendedMediaSection had no animation
 * at all, making the recommendations feel unlike every other row.
 *
 * Sequencing it here rather than at four call sites is what stops those four
 * drifting apart again — they had already drifted into three different states.
 *
 * The click sound stays at the call site and stays IMMEDIATE. Feedback has to
 * land on the press even when the navigation does not, or the card feels
 * unresponsive and gets pressed twice.
 */
object TitleCardPress {

    /** How small the card gets at the bottom of the dip. */
    const val SCALE = 0.80f

    /** Time to shrink. */
    const val SHRINK_MS = 200

    /**
     * Time to spring back — and therefore how long after the press the details
     * screen appears (SHRINK_MS + REGROW_MS = 400ms).
     *
     * Kept brisk on purpose. Unlike the profile picker, which is entered once a
     * session, this fires on every card a viewer opens while browsing, so the
     * animation has to be complete without becoming something to sit through.
     */
    const val REGROW_MS = 200
}

/**
 * Run the shrink/regrow, then the action.
 *
 * `pressed` drives the caller's own animateFloatAsState — the animation stays
 * where it is declared so each list keeps control of how the scale is applied
 * (some carry other graphicsLayer work on the same node). This owns only the
 * timing.
 *
 * `busy` makes a second press a no-op while one is in flight. Without it an
 * impatient double-tap on a remote opens the details screen twice and restarts
 * the animation mid-flight.
 *
 * Cancelled automatically if the row leaves composition, since the scope
 * belongs to the caller: a card scrolled off mid-press simply never navigates,
 * which is the correct outcome.
 */
fun CoroutineScope.runTitleCardPress(
    pressed: MutableState<Boolean>,
    busy: MutableState<Boolean>,
    onComplete: () -> Unit,
): Job? {
    if (busy.value) return null
    busy.value = true
    pressed.value = true
    return launch {
        delay(TitleCardPress.SHRINK_MS.toLong())
        pressed.value = false
        delay(TitleCardPress.REGROW_MS.toLong())
        busy.value = false
        onComplete()
    }
}
