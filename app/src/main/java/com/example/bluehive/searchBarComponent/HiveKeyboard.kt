package com.example.bluehive.searchBarComponent

import kotlin.math.abs

// ══════════════════════════════════════════════════════════════════════════════
//  HONEYCOMB KEYBOARD — layout and D-pad movement (no UI in this file)
// ══════════════════════════════════════════════════════════════════════════════
//
// Top to bottom: two wide ACTION keys side by side in the middle, over the
// comb -- Voice and Search, or Shift and Done for a profile NAME -- then four
// interlocking rows of hexagons
// (7, 6, 7, 6 keys -- every letter exactly once), then a row of pills: 123,
// space, delete.
//
// UP AND DOWN ARE THE HARD PART. The rows are offset by half a key, so a key
// has TWO neighbours above it and two below. Choosing by "nearest to where I am
// now" makes the focus drift sideways: every press moves half a key, always the
// same way. So the keyboard remembers a STICKY COLUMN -- the x position the
// viewer last chose with LEFT/RIGHT -- and every UP/DOWN lands on the key
// UNDER that column (or the nearest one, if the column falls in a gap).
// Pressing UP three times then zig-zags around one column and ends up straight
// above where it started, instead of three half-keys away.
//
// Positions are in key pitches (one hexagon plus one gap) from the comb's left
// edge, so the same numbers drive the drawing in HoneycombOverlay.kt. Each
// action key covers the three columns under it; arriving on one sideways, or
// starting on it, counts as its middle -- down from Voice is 'b', down from
// Search is 'e' (each middle falls on the line between two keys, and the line
// goes left, as it does everywhere).

internal enum class HiveAction { CHAR, VOICE, SHIFT, SPACE, DELETE, MODE, SEARCH, DONE }

internal data class HiveKey(
    val action: HiveAction,
    val char: Char = ' ',
    val label: String = char.toString(),
)

/** A key's place: its row, and its index along that row. */
internal data class HiveSpot(val row: Int, val col: Int)

internal enum class HiveDir { LEFT, RIGHT, UP, DOWN }

internal class HiveKeyboard(
    val numbers: Boolean,
    val hasVoice: Boolean,
    /** Typing a profile name: Shift instead of Voice, Done instead of Search. */
    val forNames: Boolean = false,
    /** Shift is on: the letters show, and type, as capitals. */
    val upper: Boolean = false,
) {

    /** The action row, the comb's four rows, then the pill row. */
    val rows: List<List<HiveKey>>

    /** Where each key stands for UP/DOWN and the sticky column, in key pitches. */
    val centres: List<List<Float>>

    /** The stretch of the row each key covers, in key pitches. */
    val spans: List<List<ClosedFloatingPointRange<Float>>>

    /** How wide each pill is, as a share of the row. Drawn from the same numbers. */
    val pillWeights: List<Float>

    init {
        val comb = if (numbers) NUMBER_ROWS else LETTER_ROWS
        val combRows = comb.map { line ->
            line.map { HiveKey(HiveAction.CHAR, if (upper) it.uppercaseChar() else it) }
        }

        val actions = buildList {
            when {
                forNames -> add(HiveKey(HiveAction.SHIFT, label = "Shift"))
                hasVoice -> add(HiveKey(HiveAction.VOICE, label = "Voice"))
            }
            add(
                if (forNames) HiveKey(HiveAction.DONE, label = "Done")
                else HiveKey(HiveAction.SEARCH, label = "Search")
            )
        }

        val pills = listOf(
            HiveKey(HiveAction.MODE, label = if (numbers) "abc" else "123") to 1f,
            HiveKey(HiveAction.SPACE, label = "space") to 2f,
            HiveKey(HiveAction.DELETE, label = "delete") to 1f,
        )
        rows = listOf(actions) + combRows + listOf(pills.map { it.first })
        pillWeights = pills.map { it.second }

        // The action keys sit side by side in the middle of the row.
        val wide = ACTION_KEYS.toFloat()
        val start = (LONG_ROW - actions.size * wide) / 2f
        val actionSpans = actions.indices.map { i ->
            (start + i * wide)..(start + (i + 1) * wide)
        }
        val actionCentres = actionSpans.map { (it.start + it.endInclusive) / 2f }

        val combCentres = combRows.map { row ->
            // Short rows sit half a key in, which is what makes it a honeycomb.
            val inset = if (row.size < LONG_ROW) 0.5f else 0f
            row.indices.map { inset + it + 0.5f }
        }
        val combSpans = combCentres.map { row -> row.map { (it - 0.5f)..(it + 0.5f) } }

        // Pills share the comb's full width in proportion to their weights.
        val total = pillWeights.sum()
        var x = 0f
        val pillSpans = pillWeights.map { w ->
            val width = LONG_ROW * w / total
            (x..(x + width)).also { x += width }
        }
        val pillCentres = pillSpans.map { (it.start + it.endInclusive) / 2f }

        centres = listOf(actionCentres) + combCentres + listOf(pillCentres)
        spans = listOf(actionSpans) + combSpans + listOf(pillSpans)
    }

    /** The action keys' row, over the comb. */
    val actionRow: Int get() = 0

    /** The comb's rows, top to bottom. */
    val combRows: IntRange get() = 1 until rows.lastIndex

    val pillRow: Int get() = rows.lastIndex

    /** The comb's first key: where a name starts. */
    val firstKey: HiveSpot get() = HiveSpot(combRows.first, 0)

    fun key(spot: HiveSpot): HiveKey = rows[spot.row][spot.col]

    fun centre(spot: HiveSpot): Float = centres[spot.row][spot.col]

    /**
     * Where [dir] goes from [from]. Null is a dead end: the edge of the
     * keyboard.
     */
    fun move(from: HiveSpot, dir: HiveDir, stickyX: Float): HiveSpot? = when (dir) {
        HiveDir.LEFT -> if (from.col > 0) from.copy(col = from.col - 1) else null
        HiveDir.RIGHT -> if (from.col < rows[from.row].lastIndex) from.copy(col = from.col + 1) else null
        HiveDir.UP -> if (from.row > 0) under(from.row - 1, stickyX) else null
        HiveDir.DOWN -> if (from.row < rows.lastIndex) under(from.row + 1, stickyX) else null
    }

    /**
     * The key in [row] that covers [x] -- on the line between two, the left
     * one. Where nothing covers it (a gap, or past the end of a short row),
     * the key whose centre is closest, again the left one on a tie.
     */
    private fun under(row: Int, x: Float): HiveSpot {
        val covering = spans[row].indexOfFirst { x in it }
        if (covering >= 0) return HiveSpot(row, covering)
        val cs = centres[row]
        var best = 0
        for (i in 1 until cs.size) {
            if (abs(cs[i] - x) < abs(cs[best] - x)) best = i
        }
        return HiveSpot(row, best)
    }

    companion object {
        const val LONG_ROW = 7

        /** How many hexagons wide each action key is. */
        const val ACTION_KEYS = 3

        /** Search and Done: the keys that hand the text on. */
        fun isFinish(action: HiveAction): Boolean =
            action == HiveAction.SEARCH || action == HiveAction.DONE

        private val LETTER_ROWS = listOf("abcdefg", "hijklm", "nopqrst", "uvwxyz")

        // What titles actually contain: digits, then the punctuation names use.
        private val NUMBER_ROWS = listOf("1234567", "890-:'", "&.,!?()", "+/#@%$")
    }
}
