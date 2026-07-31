package com.example.bluehive.webview.exoplayer_resources

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
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
            c = c.cause
        }
        return false
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
            if (!SniSafe.isHandshakeRefusal(e)) throw e

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
            stream.contentLength
        }
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
