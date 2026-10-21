package com.example.bluehive.utilities

import com.example.bluehive.BlueHiveApplication
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * THE RULE: coming back from a player, focus lands on the streaming button you
 * launched from — never on the header.
 *
 * Leaving a details screen for a player tears down its window focus. On return,
 * Android hands focus to the first focusable thing it finds, which on both details
 * screens is the Favorites button at the very top. So after every episode you were
 * bounced to the header and had to walk all the way back down to press Next.
 *
 * Usage — two halves, both required:
 *   1. Every streaming button's onClick records itself:
 *        lastStreamFocusRequester = <that button's requester>
 *   2. The screen installs this once:
 *        RestoreFocusOnPlayerReturn { listOfNotNull(lastStreamFocusRequester, …) }
 *
 * [targets] is priority-ordered. Return an EMPTY list to mean "leave focus alone" —
 * that's what keeps this from fighting a screen's own initial-focus logic
 * (resume-to-episode, Continue Watching) before anything has been played.
 *
 * Why more than one target: a flow may dismiss the panel holding the streaming
 * buttons before launching, so on return that button no longer exists to focus.
 * The next-best anchor — the episode tile it belonged to — is one press from the
 * same buttons, and crucially is still nowhere near the header.
 *
 * Scope note: this fires on any ON_RESUME once a streaming button has been
 * clicked, not only on a return from the player — there's no reliable "who
 * resumed me" signal, and the behaviour is the same either way (a HOME
 * round-trip also puts you back on the button you last pressed).
 */
@Composable
fun RestoreFocusOnPlayerReturn(
    tag: String = "PlayerReturnFocus",
    targets: () -> List<FocusRequester>,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // The observer outlives recomposition, so it must NOT capture the first
    // `targets` lambda. Screens rebuild their requester lists when data changes
    // (`remember(episodes) { List(episodes.size) { FocusRequester() } }`), and a
    // captured lambda would keep pointing at the previous generation — every one
    // of them detached, so every request silently no-ops.
    val currentTargets by rememberUpdatedState(targets)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver

            // Everything below moves the cursor without the viewer touching the
            // remote, so the navigation tick that normally accompanies a focus
            // change would be a lie. Muted for the whole restore, not just the
            // first pass -- see RESTORE_MUTE_MS.
            BlueHiveApplication.muteHoverSounds(RESTORE_MUTE_MS)

            scope.launch {
                // Insist for a few frames rather than firing once. Two reasons:
                //  • the button may live in a panel that hasn't been re-laid-out
                //    yet, and focus can't be given to an unattached node;
                //  • the system's own "focus the first focusable" pass can land a
                //    frame or two AFTER ON_RESUME and silently steal it back.
                // Re-requesting focus on the node that already has it is a no-op,
                // so over-asking costs nothing. Each pass re-reads the list and
                // walks it in priority order, so a preferred target that attaches
                // late still wins.
                var landed = -1
                repeat(RESTORE_FRAME_ATTEMPTS) {
                    awaitFrame()
                    focusFirstAttached(currentTargets()).let { if (it >= 0) landed = it }
                }
                // One late pass, after the resume has settled.
                delay(RESTORE_SETTLE_MS)
                focusFirstAttached(currentTargets()).let { if (it >= 0) landed = it }

                if (landed < 0) {
                    // Not fatal — the anchors are simply gone (their panel closed),
                    // so whatever the screen focuses normally stands.
                    Log.d(tag, "no attached focus anchor on resume — leaving focus alone")
                } else {
                    Log.d(tag, "focus restored to anchor #$landed")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Focus the first candidate that actually takes it; returns its index, or -1.
 *
 * Uses the Boolean overload deliberately. The plain `requestFocus()` reports
 * NOTHING back: on a detached requester it prints "FocusRequester is not
 * initialized" to stdout and returns normally, so wrapping it in runCatching and
 * treating "didn't throw" as success marks every failure a success — which is
 * exactly how the first version of this silently gave up on candidate #1 and
 * never tried the rest.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun focusFirstAttached(candidates: List<FocusRequester>): Int {
    candidates.forEachIndexed { index, requester ->
        val moved = runCatching { requester.requestFocus(FocusDirection.Enter) }
            .getOrDefault(false)
        if (moved) return index
    }
    return -1
}

/** Frames to keep re-asserting focus for after a resume. */
private const val RESTORE_FRAME_ATTEMPTS = 5

/** Final re-assert, once the resume has settled. */
private const val RESTORE_SETTLE_MS = 120L

/**
 * How long navigation ticks stay muted after a resume.
 *
 * Must outlast the whole restore: RESTORE_FRAME_ATTEMPTS frames (up to ~170ms
 * if the panel is running at 30fps) plus RESTORE_SETTLE_MS plus the late pass
 * after it. 450ms covers that with room spare.
 *
 * The cost of being generous is one swallowed tick for a viewer who presses a
 * direction within half a second of the player closing; the cost of being tight
 * is the chirp coming back intermittently, which is harder to notice and harder
 * to diagnose.
 */
private const val RESTORE_MUTE_MS = 450L
