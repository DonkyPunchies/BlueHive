package com.example.bluehive.searchBarComponent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Locks the honeycomb's D-pad rules.
 *
 * The rows are offset by half a key, so every key has two neighbours above it
 * and two below. The keyboard picks by a remembered STICKY column; these tests
 * exist mostly to stop that being "simplified" into nearest-to-current, which
 * drifts half a key sideways on every UP and DOWN.
 */
class HiveKeyboardTest {

    private val kb = HiveKeyboard(numbers = false, hasVoice = true)

    private fun spotOf(c: Char): HiveSpot {
        kb.rows.forEachIndexed { r, row ->
            val i = row.indexOfFirst { it.action == HiveAction.CHAR && it.char == c }
            if (i >= 0) return HiveSpot(r, i)
        }
        error("no key $c")
    }

    private fun charAt(s: HiveSpot) = kb.key(s).char

    /** Where the key that does [action] sits, or null if this layout has none. */
    private fun HiveKeyboard.find(action: HiveAction): HiveSpot? {
        rows.forEachIndexed { r, row ->
            val c = row.indexOfFirst { it.action == action }
            if (c >= 0) return HiveSpot(r, c)
        }
        return null
    }

    private fun comb(k: HiveKeyboard) = k.combRows.map { k.rows[it] }

    private fun actions(k: HiveKeyboard) = k.rows[k.actionRow].map { it.action }

    private fun up(from: Char) = kb.key(kb.move(spotOf(from), HiveDir.UP, kb.centre(spotOf(from)))!!)

    // ── layout ──────────────────────────────────────────────────────────────

    @Test
    fun `four interlocking rows of 7 6 7 6, every letter once`() {
        assertEquals(listOf(7, 6, 7, 6), comb(kb).map { it.size })
        val letters = comb(kb).flatten().map { it.char }.sorted().joinToString("")
        assertEquals("abcdefghijklmnopqrstuvwxyz", letters)
    }

    @Test
    fun `voice and search sit over the comb, 123 space delete under it`() {
        assertEquals(0, kb.actionRow)
        assertEquals(listOf(HiveAction.VOICE, HiveAction.SEARCH), actions(kb))
        assertEquals(
            listOf(HiveAction.MODE, HiveAction.SPACE, HiveAction.DELETE),
            kb.rows.last().map { it.action },
        )
        // Space is the wide one in the middle.
        assertEquals(listOf(1f, 2f, 1f), kb.pillWeights)
    }

    @Test
    fun `no speech service means no voice key, and search is still there`() {
        val quiet = HiveKeyboard(numbers = false, hasVoice = false)
        assertNull(quiet.find(HiveAction.VOICE))
        assertEquals(listOf(HiveAction.SEARCH), actions(quiet))
        // Every way up from the top row reaches it.
        val g = HiveSpot(quiet.combRows.first, 6)
        val a = HiveSpot(quiet.combRows.first, 0)
        assertEquals(HiveAction.SEARCH, quiet.key(quiet.move(a, HiveDir.UP, quiet.centre(a))!!).action)
        assertEquals(HiveAction.SEARCH, quiet.key(quiet.move(g, HiveDir.UP, quiet.centre(g))!!).action)
        // On its own it sits in the middle, over d.
        val search = quiet.find(HiveAction.SEARCH)!!
        assertEquals('d', quiet.key(quiet.move(search, HiveDir.DOWN, quiet.centre(search))!!).char)
    }

    @Test
    fun `123 swaps the comb for 26 different characters, same shape`() {
        val num = HiveKeyboard(numbers = true, hasVoice = true)
        assertEquals(listOf(7, 6, 7, 6), comb(num).map { it.size })
        val chars = comb(num).flatten().map { it.char }
        assertEquals(26, chars.toSet().size)
        assertTrue(chars.containsAll("1234567890".toList()))
        assertEquals("abc", num.rows.last().first { it.action == HiveAction.MODE }.label)
    }

    // ── names ───────────────────────────────────────────────────────────────

    @Test
    fun `a name keyboard has shift and done, never voice or search`() {
        val names = HiveKeyboard(numbers = false, hasVoice = true, forNames = true)
        assertEquals(listOf(HiveAction.SHIFT, HiveAction.DONE), actions(names))
        assertEquals(kb.rows.last(), names.rows.last())
        // A name starts on the first letter, not on Shift.
        assertEquals('a', names.key(names.firstKey).char)
    }

    @Test
    fun `shift turns the letters into capitals, and leaves 123 alone`() {
        val upper = HiveKeyboard(numbers = false, hasVoice = false, forNames = true, upper = true)
        val letters = comb(upper).flatten().map { it.char }.sorted().joinToString("")
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ", letters)
        assertEquals("A", upper.key(upper.firstKey).label)

        val digits = HiveKeyboard(numbers = true, hasVoice = false, forNames = true, upper = true)
        assertEquals(HiveKeyboard(numbers = true, hasVoice = false, forNames = true).rows, digits.rows)
    }

    // ── sticky column ───────────────────────────────────────────────────────

    @Test
    fun `up then down comes back to the same key`() {
        val k = spotOf('k')
        val up = kb.move(k, HiveDir.UP, kb.centre(k))!!
        assertEquals(k, kb.move(up, HiveDir.DOWN, kb.centre(k)))
    }

    @Test
    fun `three ups go straight up instead of drifting sideways`() {
        var s = spotOf('x')
        val sticky = kb.centre(s)
        repeat(3) { s = kb.move(s, HiveDir.UP, sticky)!! }
        assertEquals(kb.combRows.first, s.row)
        assertTrue("drifted to ${charAt(s)}", abs(kb.centre(s) - sticky) <= 0.5f)
    }

    @Test
    fun `a tie between the two keys above goes to the left one`() {
        // k sits exactly between d and e.
        assertEquals('d', up('k').char)
    }

    @Test
    fun `up off the comb reaches voice from the left half, search from the right`() {
        for (c in "abcd") assertEquals("up from $c", HiveAction.VOICE, up(c).action)
        for (c in "efg") assertEquals("up from $c", HiveAction.SEARCH, up(c).action)
    }

    @Test
    fun `voice and search sit side by side in the middle`() {
        val (voice, search) = kb.spans[kb.actionRow]
        assertEquals(voice.endInclusive, search.start)
        assertEquals(HiveKeyboard.LONG_ROW / 2f, voice.endInclusive)
        assertEquals(voice.start, HiveKeyboard.LONG_ROW - search.endInclusive)
    }

    @Test
    fun `down from voice is b, down from search is e`() {
        val voice = kb.find(HiveAction.VOICE)!!
        val search = kb.find(HiveAction.SEARCH)!!
        assertEquals('b', charAt(kb.move(voice, HiveDir.DOWN, kb.centre(voice))!!))
        assertEquals('e', charAt(kb.move(search, HiveDir.DOWN, kb.centre(search))!!))
        // Came up from c: going back down returns to c.
        assertEquals('c', charAt(kb.move(voice, HiveDir.DOWN, kb.centre(spotOf('c')))!!))
    }

    @Test
    fun `down off the comb lands on the pill under the column`() {
        fun down(c: Char) = kb.key(kb.move(spotOf(c), HiveDir.DOWN, kb.centre(spotOf(c)))!!).action
        assertEquals(HiveAction.MODE, down('u'))
        // The space bar is under v and y too, though 123 and delete have the
        // nearer centres -- a wide key takes every column over it.
        for (c in "vwxy") assertEquals("down from $c", HiveAction.SPACE, down(c))
        assertEquals(HiveAction.DELETE, down('z'))
    }

    @Test
    fun `up from space, reached sideways, lands over its middle`() {
        val space = kb.find(HiveAction.SPACE)!!
        assertEquals('w', charAt(kb.move(space, HiveDir.UP, kb.centre(space))!!))
    }

    // ── edges ───────────────────────────────────────────────────────────────

    @Test
    fun `the edges are dead ends`() {
        assertNull(kb.move(spotOf('a'), HiveDir.LEFT, 0f))
        val voice = kb.find(HiveAction.VOICE)!!
        assertNull(kb.move(voice, HiveDir.UP, 0f))
        assertNull(kb.move(voice, HiveDir.LEFT, 0f))
        assertNull(kb.move(kb.find(HiveAction.SEARCH)!!, HiveDir.RIGHT, 0f))
        val space = kb.find(HiveAction.SPACE)!!
        assertNull(kb.move(space, HiveDir.DOWN, 0f))
        assertNull(kb.move(kb.find(HiveAction.MODE)!!, HiveDir.LEFT, 0f))
        assertNull(kb.move(kb.find(HiveAction.DELETE)!!, HiveDir.RIGHT, 0f))
        val g = spotOf('g')
        assertNull(kb.move(g, HiveDir.RIGHT, kb.centre(g)))
    }

    @Test
    fun `every layout has the same row sizes, so a spot survives any switch`() {
        // The card keeps its focus spot across 123/abc and Shift without
        // checking it; that is only safe while every variant is the same shape.
        val shapes = listOf(
            HiveKeyboard(numbers = false, hasVoice = true),
            HiveKeyboard(numbers = true, hasVoice = true),
        ).map { k -> k.rows.map { it.size } }.toSet()
        assertEquals(1, shapes.size)
        val names = listOf(false, true).flatMap { n -> listOf(false, true).map { u ->
            HiveKeyboard(numbers = n, hasVoice = false, forNames = true, upper = u)
        } }.map { k -> k.rows.map { it.size } }.toSet()
        assertEquals(1, names.size)
    }
}
