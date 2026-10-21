package com.example.bluehive.webview.hls

/**
 * Transport policy for an extracted stream: which requests to make, and which
 * failures are worth another attempt.
 *
 * Its own file, with no Android in it, because these answers are worth stating
 * once and testing — not left as conditions buried in a 1700-line Activity where
 * the next person reads `>= 400` and "simplifies" it.
 */

/**
 * Could routing this request through our own server plausibly succeed?
 *
 * THE RELAY FIXES A BLOCKED TRANSPORT, NOT A DEAD ORIGIN.
 *
 * The ISP filter severs the connection outright: nothing completes and there is
 * no status at all. Going out through the VPS genuinely fixes that, because the
 * block is on this line and not on the far end.
 *
 * An HTTP status means the origin was reached and gave an answer. Whether the
 * relay helps then depends entirely on WHAT it said:
 *
 *   404, 410   the object is not there. No egress makes it exist.
 *   5xx        the origin is broken or unreachable. 522 is Cloudflare saying it
 *              could not reach the origin either, so neither will we.
 *   403, 429   the origin objects to WHO is asking. A different source IP and a
 *              correct referer are exactly what change that — the relay's best
 *              case, and the reason this is not simply `status >= 400`.
 *   -1 / 0     nothing completed. The filter's own signature. Relay.
 *
 * MEASURED 2026-10-09, Carrie s1e1 on VidNest: segments 404 and the CDN host
 * answering 522. The player relayed anyway, stalled 25s, re-extracted, relayed
 * again — about 78 seconds a round, four rounds, all of it asking a host that
 * was down whether it had come back.
 */
internal fun relayCanHelp(status: Int): Boolean =
    !(status == 404 || status == 410 || status >= 500)

/**
 * Is this playback error the SOURCE's fault, such that another source is worth
 * trying?
 *
 * The answer decides whether the player reports a dead source and the detail
 * screen walks the chain, or closes silently because the next source would fail
 * identically. Getting it wrong in the quiet direction is invisible: the screen
 * just goes away.
 *
 *   2001 connection failed    the bytes never arrived
 *   2002 connection timeout   they stopped arriving
 *   2003 invalid content type the far end sent the wrong kind of thing
 *   2004 bad HTTP status      the origin refused
 *   3001 container malformed  a media chunk came back that is not media
 *   3002 manifest malformed   something came back that is not a playlist
 *
 * 3002 IS THE ONE THAT WAS MISSING, and it was the least obvious of the five,
 * because the request succeeded -- the response was 200 and simply was not a
 * manifest. MEASURED 2026-10-12, device report 778, South Pacific s1e2 on
 * VidNest: "Input does not start with the #EXTM3U header", position 0. Fetching
 * that identical url from the backend returned a valid #EXTM3U, so the content
 * differed by who asked -- an injected page on the viewer's line, not a bad
 * extraction. The player closed without telling anyone, while MoviesAPI had
 * played the same episode eight minutes earlier in 2.2 seconds.
 *
 * 3001 IS THE SAME FAULT ONE LEVEL DOWN: a segment instead of the playlist.
 * MEASURED 2026-10-19, device report 818, In the Beginning on VidNest: a 10 s
 * skip at 6:44 asked for a segment, and the worker answered 200 with a 146-byte
 * nginx "403 Forbidden" page under a made-up content type (png, pdf, woff2 --
 * a different one per request). The TS parser that had been reading the stream
 * found no sync byte, and the player closed 0.1 s later with no retry and no
 * notice. Every segment past 1:30 was that page, from the viewer's line and
 * from the backend alike, so this was the source failing, not this device.
 *
 * DELIBERATELY EXCLUDED, and the reason the set is not "everything":
 *
 *   ERROR_CODE_DECODING_*              this device cannot play it; nor will it
 *                                      play the next source's copy
 *   ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003)
 *                                      handled above by one retry with the
 *                                      other container reading, which fixes it
 *                                      without changing source. NOT the same as
 *                                      3001: there no parser recognised the
 *                                      bytes; in 3001 the right parser was
 *                                      already reading and the bytes went bad.
 */
internal fun isSourceFailure(errorCode: Int): Boolean = errorCode in setOf(
    2001,  // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    2002,  // ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
    2003,  // ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
    2004,  // ERROR_CODE_IO_BAD_HTTP_STATUS
    3001,  // ERROR_CODE_PARSING_CONTAINER_MALFORMED
    3002,  // ERROR_CODE_PARSING_MANIFEST_MALFORMED
)

/**
 * Does this host REQUIRE an Origin header before it will serve anything?
 *
 * VidNest streams through a Cloudflare Worker, and that worker answers 404 to
 * every request arriving without an Origin. Not a cold start, not rate
 * limiting — a hard precondition.
 *
 * THIS IS WHY A HEALTHY STREAM LOOKED DEAD. The backend's own gate derives an
 * Origin from the Referer (_stream_headers in extract.py), so the server
 * measured these manifests at 1.0 segment health — every sampled segment 200 —
 * while the player, which sent no Origin, got 404 on the identical URL and spent
 * the whole recovery ladder re-extracting its way to the same answer.
 * MEASURED 2026-10-09 on Carrie s1e1, divine-block-4f3d.streamed-4.workers.dev.
 *
 * NARROW ON PURPOSE. An Origin 403s vidfast segments and the entire moviesapi
 * family — see the measured per-level table in ExoPlayerConfig.streamHeaders —
 * so it must not be sent generally. No other source is served from a worker, so
 * the host suffix is exactly the set that needs it and nothing more.
 *
 * Matches the registrable suffix only: a host that merely CONTAINS the string,
 * such as `workers.dev.evil.test`, is not Cloudflare's.
 */
internal fun requiresOriginHeader(host: String?): Boolean {
    val h = host?.trim()?.lowercase()?.removeSuffix(".") ?: return false
    return h == "workers.dev" || h.endsWith(".workers.dev")
}

/**
 * The Origin a browser sends alongside [referer]: scheme, host and any
 * non-default port -- never a path or query. [fallback] when the referer is
 * missing, blank, or not an absolute http(s) URL.
 *
 * WHY MIRURO NEEDS THIS. MiruroPlayerActivity hard-coded
 * `Origin: https://www.miruro.tv` on the reasoning that Miruro's CDN pins it.
 * That stopped being true when Miruro moved its player into an iframe on
 * strm.cx: the sun provider's CDN (vault-*.keeply.top) now serves ONLY
 * `Origin: https://strm.cx` -- master, variant and every segment 200 with it,
 * 403 with miruro.tv's or none, Referer irrelevant. MEASURED 2026-10-12 on My
 * Hero Academia S7E2 dub, from the VPS and from a home line. The backend now
 * reports the referer of the frame that actually fetched each stream, so
 * deriving Origin from it sends exactly what the website's own player sent,
 * provider by provider: strm.cx for sun and kiwi, megaplay.buzz for bunembed,
 * miruro.tv where the main frame made the request.
 *
 * A payload from before that change still carries `https://www.miruro.tv/`,
 * which derives to the very value that used to be hard-coded -- so an old or
 * cached payload behaves exactly as before.
 *
 * NOT requiresOriginHeader's job. That decides WHETHER an Origin is sent at
 * all, for HlsPlayerActivity's non-anime sources, where vidfast and the
 * moviesapi family 403 on one. Miruro's player has always sent an Origin; this
 * only decides which one.
 */
internal fun originFromReferer(referer: String?, fallback: String): String {
    val r = referer?.trim().orEmpty()
    if (r.isEmpty()) return fallback
    return runCatching {
        val u = java.net.URI(r)
        val scheme = u.scheme?.lowercase()
        val host = u.host?.lowercase()
        if ((scheme == "http" || scheme == "https") && !host.isNullOrBlank()) {
            // A browser omits the scheme's default port from an Origin, so an
            // explicit :443 must not make this differ from the one it sent.
            val port = u.port
            val isDefault = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
            if (port == -1 || isDefault) "$scheme://$host" else "$scheme://$host:$port"
        } else null
    }.getOrNull() ?: fallback
}
