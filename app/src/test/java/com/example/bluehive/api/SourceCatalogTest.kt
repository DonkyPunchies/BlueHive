package com.example.bluehive.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the three pure helpers in [SourceCatalog] — the whole of the decision
 * that used to be a hardcoded `usesBackendExtraction` allowlist, plus the
 * failover chain walk it feeds.
 *
 * NO CONTEXT, NO NETWORK, NO DISK. The helpers were split out of their Android
 * callers precisely so this file can exist: everything below is a plain JVM
 * test, so it runs in milliseconds and cannot pass for the wrong reason because
 * some stub returned a default.
 */
class SourceCatalogTest {

    private fun src(
        name: String,
        failover: Boolean = true,
        backendExtract: Boolean = true,
    ) = SourceCatalog.Source(
        name = name,
        label = name,
        url = "https://example.test/$name",
        failover = failover,
        backendExtract = backendExtract,
    )

    // ── decideBackendExtract ────────────────────────────────────────────────

    @Test
    fun `backend answer is taken as given when true`() {
        assertTrue(SourceCatalog.decideBackendExtract("AnythingNew", true))
    }

    @Test
    fun `backend answer is taken as given when false`() {
        // Even for a name the build ships as extractable: the server is the
        // authority, which is the entire point of the field.
        assertTrue(SourceCatalog.BUILTIN_EXTRACTABLE.contains("vidfast"))
        assertFalse(SourceCatalog.decideBackendExtract("VidFast", false))
    }

    @Test
    fun `no backend answer falls back to what the build shipped`() {
        assertTrue(SourceCatalog.decideBackendExtract("VidFast", null))
        assertTrue(SourceCatalog.decideBackendExtract("MoviesAPI", null))
    }

    @Test
    fun `an unknown name with no backend answer is not extractable`() {
        // Safe direction: the embed is expensive but works, whereas sending an
        // unextractable source to /api/extract is a 400 and no playback at all.
        assertFalse(SourceCatalog.decideBackendExtract("BrandNewSource", null))
    }

    @Test
    fun `builtin matching ignores case and surrounding space`() {
        assertTrue(SourceCatalog.decideBackendExtract("  vIdNeSt  ", null))
    }

    // ── extractableIn ───────────────────────────────────────────────────────

    @Test
    fun `THE REGRESSION - a source the build never heard of is honoured`() {
        // The bug this change exists to kill. Before it, a source added to
        // sources.conf that the client did not recognise fell through to the
        // raw embed no matter what the backend said.
        val catalog = listOf(src("VidPlay", backendExtract = true))
        assertFalse("precondition", SourceCatalog.BUILTIN_EXTRACTABLE.contains("vidplay"))
        assertTrue(SourceCatalog.extractableIn(listOf(catalog), "VidPlay"))
    }

    @Test
    fun `catalog flag overrides the builtin set`() {
        val catalog = listOf(src("VidFast", backendExtract = false))
        assertFalse(SourceCatalog.extractableIn(listOf(catalog), "VidFast"))
    }

    @Test
    fun `both lists are searched`() {
        val tv = listOf(src("OnlyOnTv", backendExtract = true))
        val movie = listOf(src("OnlyOnMovies", backendExtract = true))
        assertTrue(SourceCatalog.extractableIn(listOf(tv, movie), "OnlyOnMovies"))
        assertTrue(SourceCatalog.extractableIn(listOf(tv, movie), "OnlyOnTv"))
    }

    @Test
    fun `an unloaded list is skipped rather than treated as empty`() {
        val movie = listOf(src("VidPlay", backendExtract = true))
        assertTrue(SourceCatalog.extractableIn(listOf(null, movie), "VidPlay"))
    }

    @Test
    fun `a name in no list falls back to the builtin set`() {
        val catalog = listOf(src("Something", backendExtract = false))
        assertTrue(SourceCatalog.extractableIn(listOf(catalog), "VidNest"))
        assertFalse(SourceCatalog.extractableIn(listOf(catalog), "NeverHeardOfIt"))
    }

    @Test
    fun `catalog lookup ignores case and surrounding space`() {
        val catalog = listOf(src("VidPlay", backendExtract = true))
        assertTrue(SourceCatalog.extractableIn(listOf(catalog), " vidplay "))
    }

    // ── nextInChainFrom ─────────────────────────────────────────────────────

    private val chain = listOf(
        src("VidNest"),
        src("VidFast"),
        src("VidLove"),
        src("MoviesAPI"),
    )

    @Test
    fun `returns the next entry below the one that failed`() {
        assertEquals("VidFast", SourceCatalog.nextInChainFrom(chain, "VidNest") { true }?.name)
    }

    @Test
    fun `skips an entry marked failover false`() {
        val c = listOf(src("A"), src("B", failover = false), src("C"))
        assertEquals("C", SourceCatalog.nextInChainFrom(c, "A") { true }?.name)
    }

    @Test
    fun `skips an entry the backend cannot extract`() {
        assertEquals(
            "VidLove",
            SourceCatalog.nextInChainFrom(chain, "VidNest") { it != "VidFast" }?.name,
        )
    }

    @Test
    fun `never wraps past the end`() {
        // The order is a preference list, so someone who picked the last entry
        // has already rejected everything above it.
        assertNull(SourceCatalog.nextInChainFrom(chain, "MoviesAPI") { true })
    }

    @Test
    fun `a name no longer in the chain stops rather than restarting it`() {
        // indexOfFirst returns -1 here; treating that as "before the first
        // entry" would silently replay the whole chain from the top.
        assertNull(SourceCatalog.nextInChainFrom(chain, "RetiredSource") { true })
    }

    @Test
    fun `the failed name is matched case-insensitively`() {
        assertEquals("VidFast", SourceCatalog.nextInChainFrom(chain, "vidnest") { true }?.name)
    }

    @Test
    fun `an empty chain stops`() {
        assertNull(SourceCatalog.nextInChainFrom(emptyList(), "VidNest") { true })
    }

    @Test
    fun `returns null when nothing below is eligible`() {
        assertNull(SourceCatalog.nextInChainFrom(chain, "VidNest") { false })
    }

    // ── plannedAttemptsFrom ─────────────────────────────────────────────────

    @Test
    fun `planned counts the pressed source and every eligible one below`() {
        assertEquals(4, SourceCatalog.plannedAttemptsFrom(chain, "VidNest", true, 6) { true })
        assertEquals(3, SourceCatalog.plannedAttemptsFrom(chain, "VidFast", true, 6) { true })
    }

    @Test
    fun `planned is capped by the backend's max attempts`() {
        assertEquals(3, SourceCatalog.plannedAttemptsFrom(chain, "VidNest", true, 3) { true })
    }

    @Test
    fun `planned is one when failover is off`() {
        assertEquals(1, SourceCatalog.plannedAttemptsFrom(chain, "VidNest", false, 6) { true })
    }

    @Test
    fun `planned skips what the chain walk would skip`() {
        val c = listOf(src("A"), src("B", failover = false), src("C"), src("D"))
        assertEquals(3, SourceCatalog.plannedAttemptsFrom(c, "A", true, 6) { true })
        assertEquals(2, SourceCatalog.plannedAttemptsFrom(chain, "VidNest", true, 6) { it == "MoviesAPI" })
    }

    @Test
    fun `planned matches the walk - the count is how many sources it visits`() {
        // Walk the chain exactly as the screens do and count the visits; the
        // total must be what plannedAttemptsFrom promised, or "trying k of N"
        // would stop short of N or run past it.
        val canExtract: (String) -> Boolean = { it != "VidLove" }
        var visited = 1
        var at: String? = "VidNest"
        while (visited < 6) {
            at = SourceCatalog.nextInChainFrom(chain, at!!, canExtract)?.name ?: break
            visited++
        }
        assertEquals(visited, SourceCatalog.plannedAttemptsFrom(chain, "VidNest", true, 6, canExtract))
    }

    @Test
    fun `planned is one for the last source or a retired one`() {
        assertEquals(1, SourceCatalog.plannedAttemptsFrom(chain, "MoviesAPI", true, 6) { true })
        assertEquals(1, SourceCatalog.plannedAttemptsFrom(chain, "RetiredSource", true, 6) { true })
    }
}
