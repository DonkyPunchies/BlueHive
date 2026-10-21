package com.example.bluehive.webview.hls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks [requiresOriginHeader] to Cloudflare Workers and nothing else.
 *
 * Both directions matter here. Too narrow and VidNest 404s every request —
 * MEASURED 2026-10-09, the same URL answering 200 with an Origin and 404
 * without. Too broad and an Origin reaches vidfast and the moviesapi family,
 * which 403 on it, so widening this breaks sources that work today.
 */
class OriginPolicyTest {

    @Test
    fun `cloudflare worker subdomains need it`() {
        assertTrue(requiresOriginHeader("divine-block-4f3d.streamed-4.workers.dev"))
        assertTrue(requiresOriginHeader("red-brook-6ebb.streamed-850.workers.dev"))
        assertTrue(requiresOriginHeader("zeta.streaming-4.workers.dev"))
    }

    @Test
    fun `the bare suffix counts too`() {
        assertTrue(requiresOriginHeader("workers.dev"))
    }

    @Test
    fun `matching ignores case and a trailing root dot`() {
        assertTrue(requiresOriginHeader("Divine-Block.Streamed-4.Workers.Dev"))
        assertTrue(requiresOriginHeader("divine-block.streamed-4.workers.dev."))
        assertTrue(requiresOriginHeader("  divine-block.streamed-4.workers.dev  "))
    }

    @Test
    fun `THE SOURCES THAT 403 ON IT MUST NOT GET ONE`() {
        // vidfast segments and the whole moviesapi family reject an Origin.
        // Widening the rule to cover these is the regression this test exists
        // to catch.
        assertFalse(requiresOriginHeader("moon.peakstorm.top"))
        assertFalse(requiresOriginHeader("ashencloud.top"))
        assertFalse(requiresOriginHeader("moviesapi.to"))
        assertFalse(requiresOriginHeader("bx.netrocdn.site"))
        assertFalse(requiresOriginHeader("a2.shows.st"))
    }

    @Test
    fun `a lookalike host is not cloudflare's`() {
        // Suffix, not substring: the registrable name has to actually be theirs.
        assertFalse(requiresOriginHeader("workers.dev.evil.test"))
        assertFalse(requiresOriginHeader("notworkers.dev"))
        assertFalse(requiresOriginHeader("myworkers.devious.site"))
    }

    @Test
    fun `no host at all is not a reason to send one`() {
        assertFalse(requiresOriginHeader(null))
        assertFalse(requiresOriginHeader(""))
        assertFalse(requiresOriginHeader("   "))
    }
}
