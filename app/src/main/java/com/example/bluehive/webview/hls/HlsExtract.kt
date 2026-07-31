package com.example.bluehive.webview.hls

/**
 * The contract shared by every m3u8-extractor host (VidApi, VidFast, …) and by
 * [HlsPlayerActivity].
 *
 * WHY THIS EXISTS
 * ---------------
 * These constants used to live on VidApiExtractorActivity, which meant the player
 * — generic, used by several sources — had to import a source-specific class just
 * to read its own results. When VidFast arrived that got backwards: a `vidfast`
 * package importing from `vidapi`, with `vidapi` accidentally the owner of shared
 * infrastructure.
 *
 * Now the dependency runs one way: source packages depend on `hls`, never on each
 * other, and `hls` depends on neither. Adding a third source means adding one thin
 * host activity, not touching the other two.
 *
 * The string VALUES are unchanged, so extractor hosts declaring their own copies
 * still interoperate — they're the same keys in the same Intent extras.
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
