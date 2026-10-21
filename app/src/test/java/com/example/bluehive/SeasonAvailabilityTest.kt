package com.example.bluehive

import com.example.bluehive.models.Episode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Locks which seasons the season column lists ([watchableSeasonCount]).
 *
 * Its two mistakes are not equally bad. Listing a season with nothing aired is
 * the old behaviour: an empty grid. Leaving off a season that has episodes
 * makes them unreachable. So, like [isEpisodeUnaired], it fails open, and most
 * of these tests exist to keep it that way.
 */
class SeasonAvailabilityTest {

    // 16 October 2026 -- Invincible's Season 5 was announced, with no episodes.
    private val today = 20261016

    private fun ep(airDate: String?) = Episode(
        episodeId = 0, tmdbEpisodeId = 0, episodeNumber = 1, title = "", overview = null,
        airDate = airDate, runtime = null, stillImageUrl = null, rating = null,
    )

    private val aired = listOf(ep("2025-02-06"))

    /**
     * Runs the check against a fake backend. A season missing from [seasons]
     * has aired; a null value is a request that failed. Returns how many
     * seasons are listed, and which seasons were asked about, in order.
     */
    private fun check(
        seasonCount: Int,
        seasons: Map<Int, List<Episode>?> = emptyMap(),
        today: Int = this.today,
        maxChecks: Int = MAX_SEASON_CHECKS,
    ): Pair<Int, List<Int>> {
        val asked = mutableListOf<Int>()
        val listed = runBlocking {
            watchableSeasonCount(seasonCount, today, maxChecks) { season ->
                asked += season
                if (seasons.containsKey(season)) seasons[season] else aired
            }
        }
        return listed to asked
    }

    // ── the thing it is for ─────────────────────────────────────────────────

    @Test
    fun `an announced season with no episodes is left off`() {
        assertEquals(4 to listOf(5, 4), check(5, mapOf(5 to emptyList())))
    }

    @Test
    fun `a season whose episodes are all still to come is left off`() {
        val upcoming = listOf(ep("2026-11-05"), ep("2026-11-12"))
        assertEquals(4, check(5, mapOf(5 to upcoming)).first)
    }

    @Test
    fun `it is listed again the day its first episode airs`() {
        val upcoming = listOf(ep("2026-11-05"), ep("2026-11-12"))
        assertEquals(4, check(5, mapOf(5 to upcoming), today = 20261104).first)
        assertEquals(5, check(5, mapOf(5 to upcoming), today = 20261105).first)
    }

    @Test
    fun `one aired episode is enough to list the season`() {
        val airing = listOf(ep("2026-10-09"), ep("2026-10-23"), ep("2026-10-30"))
        assertEquals(5, check(5, mapOf(5 to airing)).first)
    }

    @Test
    fun `two seasons with nothing aired are both left off`() {
        val upcoming = listOf(ep("2027-03-01"))
        assertEquals(4 to listOf(6, 5, 4), check(6, mapOf(6 to emptyList(), 5 to upcoming)))
    }

    // ── fails open ──────────────────────────────────────────────────────────

    @Test
    fun `an episode airing today counts as aired`() {
        assertEquals(5, check(5, mapOf(5 to listOf(ep("2026-10-16")))).first)
    }

    @Test
    fun `an episode with no date counts as aired`() {
        assertEquals(5, check(5, mapOf(5 to listOf(ep(null)))).first)
    }

    @Test
    fun `a season that could not be checked is listed, and the check stops there`() {
        assertEquals(5 to listOf(5), check(5, mapOf(5 to null)))
    }

    @Test
    fun `season 1 is always listed, and never asked about`() {
        val none = mapOf<Int, List<Episode>?>(2 to emptyList(), 1 to emptyList())
        assertEquals(1 to listOf(2), check(2, none))
    }

    @Test
    fun `a one-season show is never checked`() {
        assertEquals(1 to emptyList<Int>(), check(1, mapOf(1 to emptyList())))
    }

    @Test
    fun `past the cap the remaining seasons are listed unchecked`() {
        val empty = (4..8).associateWith { emptyList<Episode>() }
        assertEquals(5 to listOf(8, 7, 6), check(8, empty, maxChecks = 3))
    }

    // ── only the end of the list ────────────────────────────────────────────

    @Test
    fun `a show whose newest season has aired costs one request`() {
        assertEquals(5 to listOf(5), check(5))
    }

    @Test
    fun `an empty season in the middle is not this check's business`() {
        assertEquals(5 to listOf(5), check(5, mapOf(3 to emptyList())))
    }

    @Test
    fun `an empty season has nothing to watch`() {
        assertFalse(seasonHasAiredEpisode(emptyList(), today))
    }
}
