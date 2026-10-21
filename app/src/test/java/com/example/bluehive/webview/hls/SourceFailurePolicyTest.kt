package com.example.bluehive.webview.hls

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks [isSourceFailure].
 *
 * This predicate decides whether a dead source is REPORTED or swallowed, and
 * the swallowed direction is silent by construction — the player just closes
 * and the detail screen is told nothing. A gap here costs a viewer the episode
 * with no error, no toast and no failover, which is exactly how 3002 went
 * unnoticed until a device report was read by hand.
 */
class SourceFailurePolicyTest {

    // ── the numbers are the contract ────────────────────────────────────────
    //
    // isSourceFailure takes raw Ints so RelayPolicy.kt stays free of Android,
    // which means the literals in it are only correct as long as something
    // checks them against media3. That is this test's first job: if a constant
    // ever moves, this fails rather than the predicate quietly matching nothing.

    @Test
    fun `the literals match media3's constants`() {
        assertEquals(2001, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(2002, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals(2003, PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE)
        assertEquals(2004, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        assertEquals(3001, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        assertEquals(3002, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)
    }

    // ── the source is at fault ──────────────────────────────────────────────

    @Test
    fun `a malformed manifest is the source's fault`() {
        // Device report 778, South Pacific s1e2 on VidNest: the response was 200
        // and simply was not a playlist. The request SUCCEEDING is what makes
        // this one easy to leave out, and leaving it out closed the player
        // silently while another source played the same episode in 2.2s.
        assertTrue(isSourceFailure(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED))
    }

    @Test
    fun `a malformed media chunk is the source's fault`() {
        // Device report 818, In the Beginning on VidNest: after a skip the
        // segment came back 200 with an nginx 403 page under a fake content
        // type. The parser already reading the stream found no sync byte, and
        // leaving 3001 out closed the player in 0.1s with no retry or notice.
        assertTrue(isSourceFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
    }

    @Test
    fun `a bad http status is the source's fault`() {
        assertTrue(isSourceFailure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
    }

    @Test
    fun `a failed connection is the source's fault`() {
        assertTrue(isSourceFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
    }

    @Test
    fun `a connection timeout is the source's fault`() {
        assertTrue(isSourceFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
    }

    @Test
    fun `an invalid content type is the source's fault`() {
        assertTrue(isSourceFailure(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE))
    }

    // ── the source is NOT at fault, and the set must stay narrow ────────────

    @Test
    fun `a reclaimed decoder is not the source's fault`() {
        // The system took a codec from this device. The next source's copy
        // needs a decoder too, so walking the chain changes nothing.
        assertFalse(isSourceFailure(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED))
    }

    @Test
    fun `an unsupported container is not the source's fault`() {
        // Handled upstream by one retry with the other container reading —
        // hls vs progressive — which fixes it without changing source. Kept
        // apart from 3001 above: here no parser recognised the bytes at all.
        assertFalse(isSourceFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
    }

    @Test
    fun `a decoder init failure is not the source's fault`() {
        assertFalse(isSourceFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
    }

    @Test
    fun `an unspecified error is not reported as a dead source`() {
        // Guards against anyone "simplifying" this to a range check. 2000 sits
        // between the IO codes and would be swept in by `in 2000..2004`.
        assertFalse(isSourceFailure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
    }

    @Test
    fun `an unknown code is not reported as a dead source`() {
        assertFalse(isSourceFailure(0))
        assertFalse(isSourceFailure(-1))
        assertFalse(isSourceFailure(9999))
    }
}
