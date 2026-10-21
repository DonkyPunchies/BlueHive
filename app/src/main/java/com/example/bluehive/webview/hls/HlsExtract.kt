package com.example.bluehive.webview.hls

/**
 * The Intent-extra contract between an m3u8-extractor host and
 * [HlsPlayerActivity].
 *
 * WHY THIS EXISTS
 * ---------------
 * These keys used to live on the extractor activity itself, so the player --
 * generic, shared by several sources -- had to import a source-specific class
 * just to read its own results. Moving them here made the dependency run one
 * way: a source package depends on `hls`, and `hls` depends on no source.
 *
 * ONLY MIRURO HOSTS AN ON-DEVICE EXTRACTOR NOW; every other source is extracted
 * server-side. These keys are kept because the player still reads them, and
 * because the next on-device host (if there is one) should not have to
 * rediscover the contract.
 */
object HlsExtract {

    /** Embed URL to extract from. */
    const val EXTRA_URL = "EXTRACT_URL"

    const val RESULT_M3U8     = "RESULT_M3U8"
    const val RESULT_REFERER  = "RESULT_REFERER"
    const val RESULT_UA       = "RESULT_UA"
    const val RESULT_SUBTITLE = "RESULT_SUBTITLE"

    const val RESULT_FAILED = "RESULT_FAILED"   // real error (not a user cancel)
    const val RESULT_REASON = "RESULT_REASON"   // human-readable failure reason

    /**
     * Fully-qualified class name of the extractor host to re-run when a CDN token
     * dies mid-playback, e.g.
     * `com.example.bluehive.webview.vidfast.VidFastExtractorActivity`.
     *
     * Passed by whoever launches the player, because only they know which source
     * this stream came from. Absent = no mid-play recovery (the player stops
     * rather than guessing at an extractor).
     */
    const val EXTRA_EXTRACTOR_CLASS = "EXTRACTOR_CLASS"

    /** Extractor hosts also accept their own EXTRA_URL key; kept for clarity. */
    const val EXTRA_EMBED_URL = "EMBED_URL"
}
