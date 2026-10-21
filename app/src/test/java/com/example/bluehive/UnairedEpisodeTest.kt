package com.example.bluehive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the boundaries of [isEpisodeUnaired].
 *
 * This predicate decides whether a viewer is ALLOWED TO PRESS an episode, so
 * both of its mistakes are user-visible and they are not equally bad:
 *
 *   * a false NEGATIVE lets through an episode that cannot play — the old
 *     behaviour, a slow failure;
 *   * a false POSITIVE puts "EPISODE NOT AVAILABLE" over something that would
 *     have played, with no way past it.
 *
 * So the rule fails open, and these tests exist mostly to keep it that way
 * against a future tightening that looks tidier.
 */
class UnairedEpisodeTest {

    // 10 October 2026 — the day this shipped, used as "today" throughout.
    private val today = 20261010

    // ── the thing it is for ─────────────────────────────────────────────────

    @Test
    fun `a date after today has not aired`() {
        assertTrue(isEpisodeUnaired("2026-10-16", today))
    }

    @Test
    fun `tomorrow has not aired`() {
        assertTrue(isEpisodeUnaired("2026-10-11", today))
    }

    @Test
    fun `next year has not aired`() {
        assertTrue(isEpisodeUnaired("2027-01-01", today))
    }

    @Test
    fun `a date before today has aired`() {
        assertFalse(isEpisodeUnaired("2026-10-09", today))
    }

    @Test
    fun `years ago has aired`() {
        assertFalse(isEpisodeUnaired("1999-12-31", today))
    }

    // ── fails open: today ───────────────────────────────────────────────────

    @Test
    fun `an episode airing today is available`() {
        // TMDB dates carry no time of day and the box runs UTC-4. By the time
        // anyone is looking at the row, an episode dated today is usually up —
        // and being wrong in this direction only costs a failed extraction,
        // while the other direction blocks a playable episode outright.
        assertFalse(isEpisodeUnaired("2026-10-10", today))
    }

    // ── fails open: no usable date ──────────────────────────────────────────

    @Test
    fun `a null date is not evidence of anything`() {
        assertFalse(isEpisodeUnaired(null, today))
    }

    @Test
    fun `a blank date is not evidence of anything`() {
        assertFalse(isEpisodeUnaired("   ", today))
    }

    @Test
    fun `a truncated date is not evidence of anything`() {
        assertFalse(isEpisodeUnaired("2026-10", today))
    }

    @Test
    fun `a non-numeric date is not evidence of anything`() {
        assertFalse(isEpisodeUnaired("not-a-date", today))
    }

    @Test
    fun `a date with letters in the day is not evidence of anything`() {
        assertFalse(isEpisodeUnaired("2026-10-1X", today))
    }

    // ── parsing details that have bitten before ─────────────────────────────

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertTrue(isEpisodeUnaired("  2026-12-25  ", today))
    }

    @Test
    fun `a full ISO timestamp still parses by its date part`() {
        // The API sends a plain date today, but it has sent timestamps before
        // and the leading ten characters are the part that matters.
        assertTrue(isEpisodeUnaired("2026-10-16T00:00:00Z", today))
    }

    @Test
    fun `a later month in the same year does not lose to the day digits`() {
        // Guards the yyyyMMdd packing: compared as strings or as a sum rather
        // than place-valued digits, 2026-11-01 could read as earlier than
        // 2026-10-30 because 1 < 30.
        assertTrue(isEpisodeUnaired("2026-11-01", 20261030))
    }

    @Test
    fun `a later year with an earlier month has not aired`() {
        assertTrue(isEpisodeUnaired("2027-01-05", 20261230))
    }

    @Test
    fun `an earlier year with a later month has aired`() {
        assertFalse(isEpisodeUnaired("2025-12-30", 20260105))
    }
}
