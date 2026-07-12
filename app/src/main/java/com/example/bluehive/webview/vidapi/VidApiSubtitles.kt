package com.example.bluehive.webview.vidapi

import android.content.Context
import android.util.Log
import com.example.bluehive.api.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Fetches an English subtitle for a movie by tmdb id and returns it as a local
 * WebVTT file that ExoPlayer can sideload. vaplayer ships NO captions, so we
 * source them ourselves:
 *
 *   1. Our backend resolves the title's imdb_id from TMDB external_ids (keeps the
 *      TMDB key server-side): GET /api/media/{tmdb}/imdb.
 *   2. The keyless Stremio OpenSubtitles v3 addon lists English subs for that
 *      imdb_id (opensubtitles-v3.strem.io); we take the first English entry.
 *   3. Download the SRT (plain UTF-8), strip promo cues, convert SRT→VTT, cache it.
 *
 * Everything is best-effort: any failure returns null (no captions) and never
 * throws to the caller. Blocking — call from Dispatchers.IO.
 *
 * NOTE: we used OpenSubtitles' legacy REST first — it started 302-redirecting to a
 * broken host ("_") for everyone (they killed it), hence the Stremio addon.
 */
object VidApiSubtitles {

    private const val TAG = "scraper-vidapi"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Best-effort English subtitle for [tmdbId]. Returns the absolute path of a
     * cached .vtt, or null if none could be found/built. Blocking (IO).
     */
    fun fetchEnglishVtt(context: Context, tmdbId: Int): String? {
        // Reuse this session's cached VTT — avoids re-hitting OpenSubtitles (which
        // is IP rate-limited) when the same movie is replayed.
        val cached = File(context.cacheDir, "vidapi_sub_${tmdbId}_en.vtt")
        if (cached.exists() && cached.length() > 32) {
            Log.d(TAG, "subs: cache hit → ${cached.absolutePath}")
            return cached.absolutePath
        }
        return try {
            val imdb = resolveImdbId(tmdbId)
            if (imdb == null) { Log.w(TAG, "subs: no imdb for tmdb=$tmdbId"); return null }

            val subUrl = findEnglishSubtitleUrl(imdb)
            if (subUrl == null) { Log.w(TAG, "subs: no English sub for $imdb"); return null }

            val srt = downloadSubtitle(subUrl)
            if (srt.isNullOrBlank()) { Log.w(TAG, "subs: subtitle download failed"); return null }

            val vtt = srtToVtt(srt)
            val file = File(context.cacheDir, "vidapi_sub_${tmdbId}_en.vtt")
            file.writeText(vtt, Charsets.UTF_8)
            Log.d(TAG, "subs: cached English VTT (${vtt.length} chars) → ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "subs: fetch failed: ${e.message}")
            null
        }
    }

    /** imdb_id ("tt1979376") for a tmdb id, resolved by our backend (TMDB
     *  external_ids server-side). Goes through the SAME Retrofit client that
     *  fetches media details, so it reuses the app's working base URL + auth
     *  rather than a hand-built URL. runBlocking is fine — we're on Dispatchers.IO. */
    private fun resolveImdbId(tmdbId: Int): String? = try {
        val imdb = runBlocking {
            ApiClient.bluehiveApi.getMediaImdb(tmdbId, "movie").imdb_id
        }?.trim()
        Log.d(TAG, "subs: imdb for tmdb=$tmdbId → ${imdb ?: "none"}")
        imdb?.takeIf { it.startsWith("tt") }
    } catch (e: Exception) {
        Log.w(TAG, "subs: imdb resolve failed for tmdb=$tmdbId — ${e.message}")
        null
    }

    /** First English subtitle URL from the Stremio OpenSubtitles v3 addon (keyless,
     *  keyed by full imdb id incl. leading zeros). Returns a direct SRT URL. */
    private fun findEnglishSubtitleUrl(imdb: String): String? {
        val body = get("https://opensubtitles-v3.strem.io/subtitles/movie/$imdb.json", BROWSER_UA, null)
        if (body == null) {
            Log.w(TAG, "subs: subtitle listing failed (no body) for $imdb")
            return null
        }
        val arr = try { JSONObject(body).optJSONArray("subtitles") } catch (e: Exception) {
            Log.w(TAG, "subs: subtitle listing not parseable for $imdb (${e.message}); starts: ${body.take(120)}")
            return null
        }
        if (arr == null || arr.length() == 0) {
            Log.w(TAG, "subs: no subtitles listed for $imdb")
            return null
        }
        var englishUrl: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lang = o.optString("lang").lowercase()
            if (lang == "eng" || lang == "en" || lang == "english") {
                val u = o.optString("url")
                if (u.isNotBlank()) { englishUrl = u; break }
            }
        }
        Log.d(TAG, "subs: $imdb → ${arr.length()} subs listed, english=${englishUrl != null}")
        if (englishUrl == null) Log.w(TAG, "subs: no English subtitle among ${arr.length()} for $imdb")
        return englishUrl
    }

    /** Download a subtitle. Stremio serves plain UTF-8 SRT, but we stay defensive
     *  about a gzipped body (magic bytes 1f 8b) just in case a source ever gzips. */
    private fun downloadSubtitle(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "subs: subtitle download http=${resp.code} for ${url.take(90)}")
                return null
            }
            val raw = resp.body?.bytes()
            if (raw == null || raw.isEmpty()) { Log.w(TAG, "subs: subtitle download had empty body"); return null }
            val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                try { GZIPInputStream(raw.inputStream()).readBytes() }
                catch (e: Exception) { Log.w(TAG, "subs: gunzip failed: ${e.message}"); return null }
            } else raw
            Log.d(TAG, "subs: subtitle downloaded ${bytes.size} bytes")
            return String(bytes, Charsets.UTF_8)
        }
    }

    private fun get(url: String, ua: String, referer: String?): String? {
        val b = Request.Builder().url(url).header("User-Agent", ua)
        if (referer != null) b.header("Referer", referer)
        http.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "subs: GET http=${resp.code} for ${url.take(90)}")
                return null
            }
            return resp.body?.string()
        }
    }

    /** Minimal SRT→WebVTT: fix timestamp separators, drop the OpenSubtitles promo cues. */
    private fun srtToVtt(srt: String): String {
        val cleaned = srt.replace("﻿", "")                 // strip BOM
            .replace("\r\n", "\n").replace("\r", "\n")
        // SRT "00:00:06,000" → VTT "00:00:06.000"
        val timed = Regex("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})")
            .replace(cleaned) { "${it.groupValues[1]}.${it.groupValues[2]}" }
        val blocks = timed.split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("opensubtitles", ignoreCase = true) }
        return "WEBVTT\n\n" + blocks.joinToString("\n\n") + "\n"
    }
}
