package com.example.bluehive.utilities

import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

// ─────────────────────────────────────────────────────────────────────────────
// Coroutines that belong to a page, and the one signal they must not swallow.
//
// THE CRASH (v350, Fire TV Stick, 2026-10-22 00:26). An anime episode was picked
// on a TV show's page and the page was left before the backend answered. Leaving
// cancels the page's rememberCoroutineScope and unregisters its result launchers,
// in the same step. But the backend call sat inside runCatching, which catches
// Throwable -- CancellationException included -- so the cancelled lookup read as
// an ordinary "backend miss", carried on to the on-device fallback, and launched
// MiruroExtractorActivity through a launcher that no longer existed. Android
// threw IllegalStateException ("unregistered ActivityResultLauncher") and the app
// died. The same shape sat in front of all six launcher calls on the TV show and
// movie pages: player, on-device extractor, and server list on each.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * [runCatching] minus the one exception a page's coroutine must not catch.
 *
 * CancellationException is how Compose tells a closed page's work to stop.
 * Caught, it becomes a value -- "the backend missed" -- and the work goes on
 * against a page that is gone. Everything else is still caught exactly as
 * runCatching would, so the fallbacks behind these calls keep working.
 *
 * Inline, so the block may call suspend functions from a suspend caller.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/**
 * Launch through this launcher only while the calling page is still open.
 *
 * A page's launchers and its coroutine scope end together, so "is this coroutine
 * still active" is exactly "is this launcher still registered". When it is not,
 * ensureActive() throws CancellationException, which ends the coroutine quietly
 * -- nothing opens, and nothing crashes -- instead of Android's
 * IllegalStateException taking the whole app down.
 */
suspend fun <I> ActivityResultLauncher<I>.launchIfActive(input: I) {
    currentCoroutineContext().ensureActive()
    launch(input)
}
