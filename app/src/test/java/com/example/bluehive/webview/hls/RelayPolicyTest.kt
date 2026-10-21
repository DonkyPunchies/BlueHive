package com.example.bluehive.webview.hls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the boundaries of [relayCanHelp].
 *
 * The shape of this policy is not obvious, and the obvious simplification —
 * `status >= 400` — is wrong in the one case the relay is best at. These tests
 * exist mainly to make that change fail loudly.
 */
class RelayPolicyTest {

    // ── the relay is the remedy ─────────────────────────────────────────────

    @Test
    fun `no status at all means the transport was severed - relay`() {
        // The ISP filter's own signature: nothing completed, so there is no
        // answer from the origin to respect.
        assertTrue(relayCanHelp(-1))
        assertTrue(relayCanHelp(0))
    }

    @Test
    fun `403 is the origin objecting to who is asking - relay`() {
        // THE RELAY'S BEST CASE. A different source IP and the right referer are
        // exactly what change a 403, which is why this is not `status >= 400`.
        assertTrue(relayCanHelp(403))
    }

    @Test
    fun `429 is rate limiting - relay`() {
        assertTrue(relayCanHelp(429))
    }

    @Test
    fun `a successful status is never a reason to refuse the relay`() {
        assertTrue(relayCanHelp(200))
        assertTrue(relayCanHelp(206))
        assertTrue(relayCanHelp(302))
    }

    @Test
    fun `other 4xx still relay`() {
        assertTrue(relayCanHelp(400))
        assertTrue(relayCanHelp(401))
        assertTrue(relayCanHelp(451))
    }

    // ── the relay cannot help ───────────────────────────────────────────────

    @Test
    fun `404 means the object is not there - no egress makes it exist`() {
        assertFalse(relayCanHelp(404))
    }

    @Test
    fun `410 is gone for good`() {
        assertFalse(relayCanHelp(410))
    }

    @Test
    fun `every 5xx is the origin being broken, including Cloudflare's own`() {
        // 522 is the measured case: Cloudflare saying it could not reach the
        // origin either, so our server will not manage it from a different IP.
        assertFalse(relayCanHelp(500))
        assertFalse(relayCanHelp(502))
        assertFalse(relayCanHelp(503))
        assertFalse(relayCanHelp(521))
        assertFalse(relayCanHelp(522))
        assertFalse(relayCanHelp(524))
    }

    // ── the exact edges ─────────────────────────────────────────────────────

    @Test
    fun `the 5xx boundary is at 500, not 499`() {
        assertTrue(relayCanHelp(499))
        assertFalse(relayCanHelp(500))
    }

    @Test
    fun `404 is isolated - its neighbours still relay`() {
        assertTrue(relayCanHelp(403))
        assertFalse(relayCanHelp(404))
        assertTrue(relayCanHelp(405))
    }
}
