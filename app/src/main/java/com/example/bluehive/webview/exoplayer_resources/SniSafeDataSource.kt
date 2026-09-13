package com.example.bluehive.webview.exoplayer_resources

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/**
 * Every HTTP fetch ExoPlayer makes, with a second chance for connections the
 * NETWORK refuses.
 *
 * WHY
 * ---
 * Measured 2026-07-31: Charter's CUJO filter blocks by reading the server name
 * (SNI) out of the TLS ClientHello — the very first packet, before any HTTP
 * exists. Proven by controlled test: same destination IP, SNI `vidfast.vc` →
 * handshake severed; SNI `example.com` → 200 OK. DNS was clean (1.1.1.1 and
 * 8.8.8.8 both returned the real Cloudflare addresses), so DNS-over-HTTPS does
 * not help here — the name is being read off the wire, not out of a lookup.
 *
 * GeckoView sails through the same block because Firefox implements Encrypted
 * Client Hello, which puts the real hostname inside an encrypted extension.
 * Android's TLS stack does not expose ECH, so OkHttp — and therefore ExoPlayer —
 * announces the hostname in the clear and gets dropped.
 *
 * All nine of our sources publish an ECH config (checked via their DNS HTTPS
 * records), so an ECH-capable fetcher would cover the entire catalogue.
 *
 * WHAT THIS CLASS IS
 * ------------------
 * The seam that fetcher will plug into, built and provable FIRST. The policy is
 * try-direct-then-retry rather than a list of blocked hosts, because:
 *
 *   • it costs nothing when nothing is blocked — the overwhelming case;
 *   • it needs no configuration and no list to maintain;
 *   • it adapts by itself when a source rotates domains (which these do
 *     constantly — vidfast.pro → vidfast.vc happened mid-development) or when
 *     an ISP adds a new entry.
 *
 * With no fetcher installed it is a pass-through that logs the exact moment a
 * fetcher WOULD have been used. That log line is the acceptance test for the
 * wiring: hit a blocked source and it must appear, naming the right host, at
 * the right moment — before a single line of the native side exists.
 */

/** Fetches a URL in a way the network can't identify by hostname. */
interface SniSafeFetcher {
    /**
     * @return an open stream, or null if this fetcher can't serve the request
     *         (caller then rethrows the original failure).
     */
    @UnstableApi
    fun open(dataSpec: DataSpec, headers: Map<String, List<String>>): SniSafeStream?
}

interface SniSafeStream {
    /** Bytes available, or [C.LENGTH_UNSET] if the server didn't say. */
    val contentLength: Long
    /** Standard [java.io.InputStream] semantics: -1 at end of stream. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun close()
}

/** The single slot the native ECH fetcher will occupy. Null = pass-through. */
object SniSafe {
    private const val TAG = "scraper-vidapi"

    @Volatile
    var fetcher: SniSafeFetcher? = null
        set(value) {
            field = value
            Log.d(TAG, "🔐 SNI-safe fetcher ${if (value == null) "cleared" else "installed: $value"}")
        }

    /**
     * Is this failure the network refusing the handshake, rather than the
     * server being down or the request being wrong?
     *
     * Deliberately narrow. An ordinary 404 or a dead CDN must NOT come through
     * here — retrying those over ECH would waste time and mask real faults.
     * A severed handshake is what a name-based filter produces and is otherwise
     * rare against a healthy CDN.
     */
    fun isHandshakeRefusal(t: Throwable?): Boolean {
        var c = t
        var depth = 0
        while (c != null && depth++ < 8) {
            if (c is SSLException) return true
            // CRONET NEVER THROWS SSLException, WHICH MADE THIS DEAD CODE.
            // Verified 2026-08-28 against media3 1.9.0: CronetDataSource.onFailed
            // stores the raw CronetException and remaps only
            // ERROR_HOSTNAME_NOT_RESOLVED. The hierarchy is
            //   NetworkException -> CronetException -> IOException
            // so an SSLException check can never match on the transport that
            // actually carries our video — and both players pass `context`, so
            // that transport is Cronet for every stream.
            //
            // The consequence was worse than a missing log line: the documented
            // acceptance test for this whole file ("🔐 handshake refused for
            // <host>") was unreachable in every shipping build, so "the seam never
            // fired" was read as "the filter never severed a handshake" when it
            // only ever meant "we were looking for the wrong exception type".
            //
            // Matched on the net:: error name rather than a Cronet class so this
            // needs no new import and survives a provider swap between the
            // embedded build and Play Services.
            val m = c.message
            if (m != null && (m.contains("ERR_SSL_PROTOCOL_ERROR") ||
                              m.contains("ERR_CONNECTION_RESET") ||
                              m.contains("ERR_CONNECTION_CLOSED") ||
                              m.contains("ERR_QUIC_HANDSHAKE_FAILED"))) return true
            c = c.cause
        }
        return false
    }

    /**
     * Is this failure a 403 the CDN is serving out of a CACHE rather than
     * deciding fresh?
     *
     * MEASURED 2026-08-26, Mushoku Tensei S1E2 on the `bee` provider. The
     * manifest fetched 200 and segment #1 came back:
     *
     *   HTTP/1.1 403 Forbidden
     *   X-Cache-Status: HIT              (origin nginx)
     *   cf-cache-status: HIT             (Cloudflare edge)
     *   Age: 17509
     *   Cache-Control: public, max-age=31536000, immutable
     *   {"code":1004,"error":"domain forbidden"}
     *
     * Segments 2-6 of the same playlist were fine; only #1 was poisoned, and
     * ExoPlayer starts at #1, so nothing ever played. The origin gates on the
     * `Origin` header — `https://miruro.tv` or none is fine, a foreign or
     * `null` origin is refused — so a browser-context fetch (the extractor's
     * headless page) drew the 403, and both caches then stored it with a
     * one-year immutable lifetime and NO `Vary`. From that moment the URL is
     * dead for every client regardless of headers.
     *
     * Re-extracting cannot route around it: the provider's manifest URL is
     * deterministic per episode — a fresh refresh=1 extraction returned the
     * byte-identical URL from a four-hour-old logcat.
     *
     * The same URL with any unused query parameter returns 200 and the real
     * bytes, so the recovery is a cache miss, not a header fix.
     *
     * Deliberately NOT narrowed to responses that LOOK cached. Cache-status
     * headers differ per CDN (`cf-cache-status`, `x-cache`, `x-cache-status`,
     * `age`, …) and sniffing them would rot silently the first time a source
     * moves. A genuine, uncached 403 costs one extra request on a path that
     * was already failing, and [SniSafeDataSource.openCacheBusted] rethrows
     * the ORIGINAL error if the retry does not help — so a wrong guess here
     * lands exactly where we are today.
     */
    fun isCacheableForbidden(t: Throwable?): Boolean {
        var c = t
        var depth = 0
        while (c != null && depth++ < 8) {
            if (c is HttpDataSource.InvalidResponseCodeException && c.responseCode == 403) return true
            c = c.cause
        }
        return false
    }

    /** Query parameter the cache-buster retry adds. Unused by any source we play. */
    const val CACHE_BUSTER_PARAM = "_bh"

    private val cacheBusterSeq  = AtomicInteger(0)
    private val cacheBusterWins = AtomicInteger(0)

    fun nextCacheBuster(): String =
        java.lang.Long.toHexString(
            (System.nanoTime() shl 8) xor cacheBusterSeq.incrementAndGet().toLong()
        )

    /**
     * Report a recovered request, but only the first few.
     *
     * Log.e is the one level release builds keep (see proguard-rules.pro), so
     * this is visible in the field — which also means an entirely poisoned
     * stream would write a line per segment for a whole episode. The first few
     * say everything the diagnosis needs; the rest is noise in a log we ship.
     */
    fun noteCacheBusterWin(host: String) {
        when (val n = cacheBusterWins.incrementAndGet()) {
            in 1..5 -> Log.e(TAG, "🧹 cached 403 bypassed via cache-buster on $host (#$n)")
            6       -> Log.e(TAG, "🧹 cache-buster still carrying $host — silencing further hits")
        }
    }
}

@UnstableApi
class SniSafeDataSource(private val direct: DataSource) : DataSource {

    private val TAG = "scraper-vidapi"

    private var fallback: SniSafeStream? = null
    private var uri: Uri? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        return try {
            direct.open(dataSpec)
        } catch (e: IOException) {
            when {
                SniSafe.isCacheableForbidden(e) -> openCacheBusted(dataSpec, e)
                SniSafe.isHandshakeRefusal(e)   -> openViaFetcher(dataSpec, e)
                else -> throw e
            }
        }
    }

    /**
     * Re-request the SAME url with one throwaway query parameter, to miss the
     * cache holding a 403 for it. See [SniSafe.isCacheableForbidden] for the
     * measurement this exists for.
     *
     * Rethrows the ORIGINAL failure if the retry does not help, so a 403 that
     * was a real refusal surfaces as the 403 it always was — the player's
     * relay fallback still sees the network error it keys off, and nothing
     * downstream learns a new failure mode.
     *
     * `uri` is updated to the busted url on success because getUri() feeds
     * relative-reference resolution for playlists. A query string plays no
     * part in resolving a relative path, so segment urls come out identical
     * either way; reporting the url we actually read from keeps the two honest.
     */
    private fun openCacheBusted(dataSpec: DataSpec, original: IOException): Long {
        // A 403 is a completed exchange, so the source has already closed the
        // connection — but a DataSource that opened partially before throwing
        // would leak without this, and close() on a closed source is a no-op.
        try { direct.close() } catch (_: Exception) {}

        val busted = dataSpec.uri.buildUpon()
            .appendQueryParameter(SniSafe.CACHE_BUSTER_PARAM, SniSafe.nextCacheBuster())
            .build()
        return try {
            val length = direct.open(dataSpec.withUri(busted))
            uri = busted
            SniSafe.noteCacheBusterWin(dataSpec.uri.host ?: "?")
            length
        } catch (_: IOException) {
            throw original
        }
    }

    /** The SNI-filter path: hand the request to the ECH fetcher, if one exists. */
    private fun openViaFetcher(dataSpec: DataSpec, e: IOException): Long {
        val host = dataSpec.uri.host ?: "?"
        val f = SniSafe.fetcher
        if (f == null) {
            // The seam is live and correctly placed; nothing is plugged in
            // yet. This line IS the wiring test — see the class note.
            Log.e(
                TAG,
                "🔐 TLS handshake refused for $host — this is the SNI-filter " +
                    "signature. No SNI-safe fetcher installed, so failing through. " +
                    "(spec: pos=${dataSpec.position} len=${dataSpec.length})"
            )
            throw e
        }

        Log.w(TAG, "🔐 handshake refused for $host — retrying via ${f.javaClass.simpleName}")
        val stream = f.open(dataSpec, direct.responseHeaders)
        if (stream == null) {
            Log.e(TAG, "🔐 fetcher declined $host — failing through")
            throw e
        }
        fallback = stream
        Log.d(TAG, "🔐 fetcher served $host (${stream.contentLength} bytes)")
        return stream.contentLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val fb = fallback ?: return direct.read(buffer, offset, length)
        val n = fb.read(buffer, offset, length)
        // media3 signals end-of-stream with its own constant, not -1.
        return if (n == -1) C.RESULT_END_OF_INPUT else n
    }

    override fun getUri(): Uri? = fallback?.let { uri } ?: direct.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (fallback != null) emptyMap() else direct.responseHeaders

    override fun close() {
        try {
            fallback?.close()
        } finally {
            fallback = null
            direct.close()
        }
    }

    /** Wraps an existing factory — every DataSource it makes gets the retry. */
    class Factory(private val delegate: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SniSafeDataSource(delegate.createDataSource())
    }
}
