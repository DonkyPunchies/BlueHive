package com.example.bluehive.singleShelfComponents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/**
 * THE single source of truth for both kinds of focus in the shelf stack:
 *
 *   1. which SHELF (lazy row) is in the focused position  — vertical nav
 *   2. which CARD (titlecard) is focused inside each shelf — horizontal nav
 *
 * Replaces the old split ShelfFocusTracker + ShelfCardFocusTracker.  Those
 * two coordinated through a multi-step "parking dance" with many separate
 * focus hops, and the card-focus side could fire on the WRONG shelf when a
 * row re-entered the window — producing the -1 / 0 / +1 card-vs-row drift.
 *
 * This tracker drives ONE deterministic move per navigation:
 *
 *     old card  ──►  the target card in the newly-focused shelf
 *
 * No parking node.  No restoration lock.  Vertical DPad is intercepted by
 * ShelfStack before it can reach a card, so focus physically cannot escape
 * the stack during a transition — which is the entire reason the parking
 * node existed.  Remove the cause, remove the machinery.
 *
 * ──────────────────────────────────────────────────────────────────────────
 *  HOW A NAVIGATION FLOWS
 * ──────────────────────────────────────────────────────────────────────────
 *  Key handler (synchronous):
 *      requestNavigation(targetIndex)           // records intent, consumes key
 *
 *  ShelfStack's LaunchedEffect(pendingTarget) (async, awaits frames):
 *      applyFocusedShelf(target)                // row slides into place;
 *                                               //   old card keeps focus
 *      awaitFrame() x2                          // new shelf composes/lays out
 *      requestCardFocus(shelves[target])        // bump the target shelf's
 *                                               //   card-focus epoch
 *      completeNavigation()                     // clear pendingTarget
 *
 *  The target shelf's TvWideLazyRow watches its OWN epoch and, ONLY IF it is
 *  the focused shelf, moves focus to its saved (or first) card.  A peek row
 *  that re-enters the window with a stale epoch sees isFocusedShelf == false
 *  and does nothing — the drift is structurally impossible.
 */
class UnifiedShelfFocusTracker {

    // ──────────────────────────────────────────────────────────────────────
    //  SHELF-to-shelf navigation intent
    // ──────────────────────────────────────────────────────────────────────
    //
    // Non-null from the moment a DPad UP/DOWN is accepted until the handoff
    // LaunchedEffect finishes.  ShelfStack watches this to run the handoff,
    // and canNavigate() reads it to block a second nav mid-flight.
    //
    // NOTE: the visual focusedShelfIndex itself stays in ShelfStack as a
    // rememberSaveable (so it survives rotation).  The tracker only carries
    // the *intent*; ShelfStack applies it.

    var pendingTarget: Int? by mutableStateOf(null)
        private set

    val isNavigating: Boolean get() = pendingTarget != null

    // ── Parking node ────────────────────────────────────────────────────────
    //
    // An invisible, stack-internal focusable.  During a transition it HOLDS
    // focus so Compose can't relocate focus to the search bar when the
    // outgoing card's node churns mid-swap.  DPad interception stops the key
    // EVENT, but it does NOT stop Compose's focus-relocation when the focused
    // node momentarily detaches — that's the gap the parking node closes.
    //
    // canFocus is gated to parkingActive so the node is NEVER an unwanted
    // focus target between navigations; it only becomes focusable for the
    // few frames of a transition, and we explicitly requestFocus on it.
    val parkingRequester = FocusRequester()

    var parkingActive: Boolean by mutableStateOf(false)
        private set

    /** Called synchronously in the key handler. */
    fun requestNavigation(target: Int) {
        // Make the parking node focusable BEFORE the handoff runs so the
        // very first requestFocus on it (next frame) succeeds.
        parkingActive = true
        pendingTarget = target
    }

    /**
     * Called by the handoff effect AFTER the target card has had frames to
     * claim focus.  Clearing parkingActive here is safe because by this
     * point focus already lives on the card, not the parking node.
     */
    fun completeNavigation() {
        pendingTarget = null
        parkingActive = false
    }

    // ──────────────────────────────────────────────────────────────────────
    //  CARD focus — per-shelf memory
    // ──────────────────────────────────────────────────────────────────────

    data class CardRecord(val tmdbId: Int, val listIndex: Int)

    private val cardRecords = mutableStateMapOf<ContentShelf, CardRecord>()

    /**
     * Record the card the user last focused in this shelf.  Always safe to
     * call: it's only ever invoked from a card's onFocusChanged, which fires
     * exactly once when that card genuinely gains focus.  Because there is
     * only ONE focus move per navigation (old card → target card), the value
     * written here is always the card focus actually landed on — never a
     * phantom intermediate.  Hence: no lock needed.
     */
    fun recordCard(shelf: ContentShelf, tmdbId: Int, listIndex: Int) {
        cardRecords[shelf] = CardRecord(tmdbId, listIndex)
    }

    fun cardTmdbId(shelf: ContentShelf): Int = cardRecords[shelf]?.tmdbId ?: -1

    fun cardListIndex(shelf: ContentShelf): Int = cardRecords[shelf]?.listIndex ?: -1

    // ──────────────────────────────────────────────────────────────────────
    //  CARD-focus request channel (epoch per shelf)
    // ──────────────────────────────────────────────────────────────────────
    //
    // Bumping a shelf's epoch tells exactly that shelf's TvWideLazyRow:
    // "move focus to your saved card now".  Each row takes a LaunchedEffect
    // keyed on its own epoch.  The row guards on isFocusedShelf, so a stale
    // epoch re-firing on a re-entering peek row is a no-op.

    private val cardFocusEpochs = mutableStateMapOf<ContentShelf, Int>()

    fun requestCardFocus(shelf: ContentShelf) {
        cardFocusEpochs[shelf] = (cardFocusEpochs[shelf] ?: 0) + 1
    }

    fun cardFocusEpoch(shelf: ContentShelf): Int = cardFocusEpochs[shelf] ?: 0
}