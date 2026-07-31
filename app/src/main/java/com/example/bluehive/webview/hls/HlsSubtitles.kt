package com.example.bluehive.webview.hls

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
object HlsSubtitles {

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
    fun fetchEnglishVtt(
        context: Context,
        tmdbId: Int,
        mediaType: String = "movie",
        season: Int = 0,
        episode: Int = 0,
    ): String? {
        val isSeries = mediaType.equals("tv", true) && season > 0 && episode > 0
        // Cache key MUST include season/episode. It used to be tmdb-only, and for a
        // series the tmdb id is the SHOW — so the first episode you ever watched
        // cached its subtitle and every later episode was served that same file.
        val key = if (isSeries) "${tmdbId}_s${season}e${episode}" else "$tmdbId"
        // Reuse this session's cached VTT — avoids re-hitting OpenSubtitles (which
        // is IP rate-limited) when the same title is replayed.
        val cached = File(context.cacheDir, "vidapi_sub_${key}_en.vtt")
        if (cached.exists() && cached.length() > 32) {
            Log.d(TAG, "subs: cache hit → ${cached.absolutePath}")
            return cached.absolutePath
        }
        return try {
            val imdb = resolveImdbId(tmdbId, if (isSeries) "tv" else "movie")
            if (imdb == null) { Log.w(TAG, "subs: no imdb for tmdb=$tmdbId"); return null }

            val subUrl = findEnglishSubtitleUrl(imdb, isSeries, season, episode)
            if (subUrl == null) { Log.w(TAG, "subs: no English sub for $imdb"); return null }

            val srt = downloadSubtitle(subUrl)
            if (srt.isNullOrBlank()) { Log.w(TAG, "subs: subtitle download failed"); return null }

            val vtt = srtToVtt(srt)
            val file = File(context.cacheDir, "vidapi_sub_${key}_en.vtt")
            file.writeText(vtt, Charsets.UTF_8)
            Log.d(TAG, "subs: cached English VTT (${vtt.length} chars) → ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "subs: fetch failed: ${e.message}")
            null
        }
    }

    /**
     * Cache a subtitle the EXTRACTOR captured from the streaming site itself.
     *
     * Preferred over [fetchEnglishVtt]: it's the caption the site ships for this
     * exact release, so it's already in sync, and it needs no imdb lookup and no
     * OpenSubtitles round-trip (which is IP rate-limited and, for episodes, often
     * has nothing at all).
     *
     * Handles either format — VidFast serves WebVTT, but a site could hand us SRT,
     * so we sniff the body rather than trusting the extension. Returns the absolute
     * path of a cached .vtt, or null. Blocking (IO).
     *
     * @param key a stable per-title cache key (tmdb id + season/episode).
     */
    fun cacheStreamSubtitle(context: Context, url: String, key: String): String? {
        val file = File(context.cacheDir, "stream_sub_${key}_en.vtt")
        if (file.exists() && file.length() > 32) {
            Log.d(TAG, "subs: stream-sub cache hit → ${file.absolutePath}")
            return file.absolutePath
        }
        return try {
            val body = downloadSubtitle(url)
            if (body.isNullOrBlank()) { Log.w(TAG, "subs: stream subtitle download failed"); return null }
            // WEBVTT header ⇒ already VTT; anything else we treat as SRT.
            val vtt = if (body.trimStart().startsWith("WEBVTT")) body else srtToVtt(body)
            file.writeText(vtt, Charsets.UTF_8)
            Log.d(TAG, "subs: cached STREAM VTT (${vtt.length} chars) → ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "subs: stream subtitle failed: ${e.message}")
            null
        }
    }

    /** imdb_id ("tt1979376") for a tmdb id, resolved by our backend (TMDB
     *  external_ids server-side). Goes through the SAME Retrofit client that
     *  fetches media details, so it reuses the app's working base URL + auth
     *  rather than a hand-built URL. runBlocking is fine — we're on Dispatchers.IO. */
    private fun resolveImdbId(tmdbId: Int, mediaType: String): String? = try {
        // Was hardcoded to "movie". Asking TMDB for a MOVIE with a SERIES tmdb id
        // returns someone else's title entirely — that is how a TV episode ended up
        // looking up tt0030257, a 1938 film, and finding no subtitles.
        val imdb = runBlocking {
            ApiClient.bluehiveApi.getMediaImdb(tmdbId, mediaType).imdb_id
        }?.trim()
        Log.d(TAG, "subs: imdb for tmdb=$tmdbId → ${imdb ?: "none"}")
        imdb?.takeIf { it.startsWith("tt") }
    } catch (e: Exception) {
        Log.w(TAG, "subs: imdb resolve failed for tmdb=$tmdbId — ${e.message}")
        null
    }

    // ── Choosing between several English subtitles ───────────────────────────
    //
    // OpenSubtitles lists MULTIPLE English subtitles per title (5 for a typical
    // episode), each authored against a different rip. Only one of them matches
    // the release the site is streaming; the others are out of sync in one of two
    // ways, neither of which we can detect from the listing:
    //
    //   • constant offset — the rip they timed against includes a recap or a
    //     distributor sting that ours doesn't. Fixable with the ◀ / ▶ buttons.
    //   • progressive drift — timed against a 25fps PAL rip while we're playing
    //     23.976fps. In sync at the start, over a minute out by the end. NOT
    //     fixable with an offset; the only fix is picking a different subtitle.
    //
    // The addon gives us no release name to choose by — an entry is just
    // {id, url, lang, SubEncoding} — so we download the candidates and let the
    // user pick from what they actually contain (cue count and time span, which
    // is enough to spot a mistimed one against the episode's real runtime).

    /** One English subtitle as listed by the addon, before it's been downloaded. */
    data class EnglishOption(
        /** 1-based position in the English list — the only stable label available. */
        val index: Int,
        val id: String,
        val url: String,
    )

    /** An [EnglishOption] downloaded, converted to VTT and measured. */
    data class SubtitleChoice(
        val option: EnglishOption,
        val label: String,
        val path: String,
        val cueCount: Int,
        val firstCueMs: Long,
        val lastCueMs: Long,
    )

    /**
     * The caption the STREAMING SITE ships for this exact release, offered as
     * index 0 so it sorts above the OpenSubtitles entries. It's the only one
     * guaranteed to match the video frame-for-frame, so when a site gives us one
     * it should always be the default pick. Blocking (IO).
     */
    fun streamChoice(context: Context, url: String, key: String): SubtitleChoice? {
        val path = cacheStreamSubtitle(context, url, key) ?: return null
        val (count, first, last) = measureVtt(File(path).readText(Charsets.UTF_8))
        if (count == 0) return null
        return SubtitleChoice(
            option = EnglishOption(0, "stream", url),
            label = "From this stream",
            path = path,
            cueCount = count,
            firstCueMs = first,
            lastCueMs = last,
        )
    }

    /** Every English subtitle listed for this title. Empty on any failure. Blocking (IO). */
    fun listEnglishOptions(
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int,
    ): List<EnglishOption> {
        val isSeries = mediaType.equals("tv", true) && season > 0 && episode > 0
        val imdb = resolveImdbId(tmdbId, if (isSeries) "tv" else "movie") ?: return emptyList()
        val arr = listSubtitlesJson(imdb, isSeries, season, episode) ?: return emptyList()
        val out = mutableListOf<EnglishOption>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("lang").lowercase() !in setOf("eng", "en", "english")) continue
            val u = o.optString("url")
            if (u.isNotBlank()) out += EnglishOption(out.size + 1, o.optString("id"), u)
        }
        // Cap it. An episode lists ~5 English subtitles, but a popular film can
        // list dozens, and the picker downloads every option it's given — that
        // would be a burst of concurrent requests at a service that rate-limits
        // by IP, to fill a list nobody scrolls to the bottom of.
        val capped = out.take(MAX_OPTIONS)
        Log.d(
            TAG,
            "subs: ${arr.length()} listed for $imdb → ${out.size} English option(s)" +
                if (out.size > capped.size) ", showing first ${capped.size}" else ""
        )
        return capped
    }

    private const val MAX_OPTIONS = 8

    /**
     * Download + convert one option and measure it. Cached per option, so
     * re-opening the picker or switching back is instant. Blocking (IO).
     *
     * @param key the same per-title cache key used by [fetchEnglishVtt].
     */
    fun downloadChoice(context: Context, option: EnglishOption, key: String): SubtitleChoice? {
        val file = File(context.cacheDir, "vidapi_sub_${key}_en${option.index}.vtt")
        val vtt = if (file.exists() && file.length() > 32) {
            file.readText(Charsets.UTF_8)
        } else {
            val body = downloadSubtitle(option.url) ?: return null
            val converted = if (body.trimStart().startsWith("WEBVTT")) body else srtToVtt(body)
            file.writeText(converted, Charsets.UTF_8)
            converted
        }
        val (count, first, last) = measureVtt(vtt)
        if (count == 0) { Log.w(TAG, "subs: option ${option.index} had no cues"); return null }
        Log.d(TAG, "subs: option ${option.index} → $count cues, ${first}ms..${last}ms")
        return SubtitleChoice(
            option = option,
            label = "English ${option.index}",
            path = file.absolutePath,
            cueCount = count,
            firstCueMs = first,
            lastCueMs = last,
        )
    }

    private val CUE_TIME = Regex(
        "(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})"
    )

    /** (cue count, first cue start, last cue end) — enough to spot a mistimed file. */
    private fun measureVtt(vtt: String): Triple<Int, Long, Long> {
        var count = 0
        var first = Long.MAX_VALUE
        var last = 0L
        fun ms(h: String, m: String, s: String, milli: String) =
            h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 + milli.toLong()
        CUE_TIME.findAll(vtt).forEach { mr ->
            val g = mr.groupValues
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            count++
            if (start < first) first = start
            if (end > last) last = end
        }
        return Triple(count, if (first == Long.MAX_VALUE) 0L else first, last)
    }

    /** First English subtitle URL from the Stremio OpenSubtitles v3 addon (keyless,
     *  keyed by full imdb id incl. leading zeros). Returns a direct SRT URL. */
    private fun findEnglishSubtitleUrl(
        imdb: String,
        isSeries: Boolean = false,
        season: Int = 0,
        episode: Int = 0,
    ): String? {
        val arr = listSubtitlesJson(imdb, isSeries, season, episode) ?: return null
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

    /** The addon's raw "subtitles" array for a title, or null. */
    private fun listSubtitlesJson(
        imdb: String,
        isSeries: Boolean,
        season: Int,
        episode: Int,
    ): org.json.JSONArray? {
        // The addon addresses episodes as series/{imdb}:{season}:{episode}. Asking
        // the /movie/ endpoint for a series imdb id lists nothing, which is why TV
        // episodes never had captions even when OpenSubtitles has plenty.
        val path = if (isSeries) "series/$imdb:$season:$episode" else "movie/$imdb"
        val body = get("https://opensubtitles-v3.strem.io/subtitles/$path.json", BROWSER_UA, null)
        if (body == null) {
            Log.w(TAG, "subs: subtitle listing failed (no body) for $path")
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
        return arr
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
