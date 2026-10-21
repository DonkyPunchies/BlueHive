package com.example.bluehive.webview.hls

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks [originFromReferer], which decides the Origin MiruroPlayerActivity
 * sends. Both directions matter: get it wrong one way and Miruro's sun provider
 * 403s every request (MEASURED 2026-10-12, its CDN serves only
 * `Origin: https://strm.cx`); get it wrong the other way and an old payload stops
 * sending the miruro.tv Origin it always has.
 */
class OriginFromRefererTest {

    private val miruro = "https://www.miruro.tv"

    @Test
    fun `the player frame's origin is what miruro's sun cdn accepts`() {
        assertEquals("https://strm.cx", originFromReferer("https://strm.cx/", miruro))
    }

    @Test
    fun `an old payload derives to exactly the value that was hard-coded`() {
        assertEquals("https://www.miruro.tv", originFromReferer("https://www.miruro.tv/", miruro))
    }

    @Test
    fun `path, query and fragment never reach an origin`() {
        assertEquals("https://strm.cx", originFromReferer("https://strm.cx/?embed=1#t=3", miruro))
        assertEquals("https://megaplay.buzz",
            originFromReferer("https://megaplay.buzz/stream/s-2/abc", miruro))
    }

    @Test
    fun `a non-default port is kept and a default one is dropped`() {
        assertEquals("https://cdn.test:8443", originFromReferer("https://cdn.test:8443/x", miruro))
        assertEquals("https://strm.cx", originFromReferer("https://strm.cx:443/", miruro))
        assertEquals("http://lan.test", originFromReferer("http://lan.test:80/", miruro))
    }

    @Test
    fun `scheme and host are lowercased the way a browser serialises them`() {
        assertEquals("https://strm.cx", originFromReferer("HTTPS://Strm.CX/", miruro))
    }

    @Test
    fun `anything unusable falls back instead of sending a broken origin`() {
        assertEquals(miruro, originFromReferer(null, miruro))
        assertEquals(miruro, originFromReferer("", miruro))
        assertEquals(miruro, originFromReferer("   ", miruro))
        assertEquals(miruro, originFromReferer("not a url", miruro))
        assertEquals(miruro, originFromReferer("ftp://strm.cx/", miruro))
        assertEquals(miruro, originFromReferer("//strm.cx/", miruro))
    }
}
