package com.example.bluehive.webview.hls

import android.content.Context
import android.util.Log
import com.example.bluehive.api.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * English subtitles for the sideloaded player. vaplayer ships none, so we source
 * them ourselves — but almost none of that work happens here any more.
 *
 *   1. GET /api/subtitles lists the options WITH their cue count and timings.
 *      A few hundred bytes. The backend resolved the imdb id, called the addon,
 *      downloaded every candidate, converted SRT→VTT and measured the cues —
 *      once, for every device that will ever ask.
 *   2. When the viewer PICKS one, GET /api/subtitles/vtt fetches that single
 *      file. One download, not eight.
 *
 * WHY THIS MOVED. The client used to do all of it, and MEASURED on the onn 4K
 * box the picker peaked at 806MB (three verified trials, 8 options). The box has
 * 2GB and the app idles near 200MB; at that peak the kernel starts killing
 * processes, and on the session that surfaced it BlueHive was the one chosen,
 * mid-film. The fan-out existed because the picker's metadata can only be known
 * by parsing each file — so moving the parsing removes the fan-out by
 * construction rather than by capping it.
 *
 * WHAT STAYED LOCAL, and why: the caption the STREAMING SITE ships. That URL
 * comes out of the extractor per playback session, so no server-side cache can
 * know it in advance. It is one file rather than eight, and it is the only
 * reason srtToVtt/measureVtt/downloadSubtitle still exist below.
 *
 * Everything is best-effort: any failure returns null (no captions) and never
 * throws to the caller. Blocking — call from Dispatchers.IO.
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
    // The addon gives no release name to choose by, so the choice is made on what
    // the files actually CONTAIN — cue count and time span, enough to spot a
    // mistimed one against the episode's real runtime. That measurement is now
    // the backend's job; what arrives here is already three integers per option.

    /** One English subtitle as offered by the backend. */
    data class EnglishOption(
        /** 1-based position in the English list — the only stable label available. */
        val index: Int,
        /** The backend's id for this option; "stream" for the site's own caption. */
        val id: String,
        /** Only set for the stream caption, which we fetch ourselves. */
        val url: String,
    )

    /**
     * An option with its measurements.
     *
     * [path] IS NULL UNTIL THE VIEWER PICKS IT. That is the whole point: a listed
     * option costs nothing but a row, and only the chosen one is ever downloaded.
     * The stream caption is the exception — it arrives already on disk, because
     * fetching it is the only way to know whether it has any cues at all.
     */
    data class SubtitleChoice(
        val option: EnglishOption,
        val label: String,
        val path: String?,
        val cueCount: Int,
        val firstCueMs: Long,
        val lastCueMs: Long,
    )

    /**
     * Every English subtitle the backend can offer, with metadata and no bodies.
     * Empty on any failure. Blocking (IO).
     *
     * runBlocking is fine — callers are already on Dispatchers.IO, same as the
     * imdb lookup this replaced.
     */
    fun listChoices(
        tmdbId: Int,
        mediaType: String,
        season: Int = 0,
        episode: Int = 0,
    ): List<SubtitleChoice> = try {
        val isSeries = mediaType.equals("tv", true) && season > 0 && episode > 0
        val resp = runBlocking {
            ApiClient.bluehiveApi.listSubtitles(
                tmdbId = tmdbId,
                mediaType = if (isSeries) "tv" else "movie",
                season = if (isSeries) season else 0,
                episode = if (isSeries) episode else 0,
            )
        }
        val out = resp.options.map { o ->
            SubtitleChoice(
                option = EnglishOption(index = o.index, id = o.id, url = ""),
                label = o.label.ifBlank { "English ${o.index}" },
                path = null,
                cueCount = o.cue_count,
                firstCueMs = o.first_cue_ms,
                lastCueMs = o.last_cue_ms,
            )
        }
        Log.d(TAG, "subs: backend listed ${out.size} option(s) for tmdb=$tmdbId")
        out
    } catch (e: Exception) {
        Log.w(TAG, "subs: backend listing failed for tmdb=$tmdbId — ${e.message}")
        emptyList()
    }

    /**
     * The local .vtt path for [choice], fetching it from the backend on first use.
     *
     * Called when the viewer picks an option, NOT when the picker opens. Streamed
     * straight to disk rather than read into a String first — the file is small,
     * but holding it in memory is the habit that made the old picker cost 806MB.
     *
     * @param key a stable per-title cache key (tmdb id + season/episode).
     */
    fun ensureVtt(
        context: Context,
        choice: SubtitleChoice,
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int,
        key: String,
    ): String? {
        choice.path?.let { return it }          // stream caption, already on disk

        val safeId = choice.option.id.filter { it.isLetterOrDigit() }.take(40)
        val file = File(context.cacheDir, "sub_${key}_$safeId.vtt")
        if (file.exists() && file.length() > 32) {
            Log.d(TAG, "subs: vtt cache hit → ${file.name}")
            return file.absolutePath
        }

        return try {
            val isSeries = mediaType.equals("tv", true) && season > 0 && episode > 0
            runBlocking {
                ApiClient.bluehiveApi.getSubtitleVtt(
                    tmdbId = tmdbId,
                    id = choice.option.id,
                    mediaType = if (isSeries) "tv" else "movie",
                    season = if (isSeries) season else 0,
                    episode = if (isSeries) episode else 0,
                ).byteStream().use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (file.length() <= 32) {
                Log.w(TAG, "subs: backend vtt was empty for ${choice.option.id}")
                file.delete()
                null
            } else {
                Log.d(TAG, "subs: fetched vtt ${file.length()} bytes → ${file.name}")
                file.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "subs: vtt fetch failed for ${choice.option.id} — ${e.message}")
            runCatching { file.delete() }
            null
        }
    }

    // ── The streaming site's own caption ─────────────────────────────────────
    //
    // Everything below exists ONLY for this one option. It is the caption the
    // site ships for this exact release, so it is already in sync and is always
    // the right default when a site provides one — but its URL is produced by the
    // extractor per session, so the backend cannot have it in advance.

    /**
     * Offered as index 0 so it sorts above the backend's entries. Blocking (IO).
     */
    fun streamChoice(
        context: Context,
        url: String,
        key: String,
        sourceTag: String,
    ): SubtitleChoice? {
        val path = cacheStreamSubtitle(context, url, key, sourceTag) ?: return null
        val text = File(path).readText(Charsets.UTF_8)
        val (count, first, last) = measureVtt(text)
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

    /**
     * Handles either format — VidFast serves WebVTT, but a site could hand us SRT,
     * so we sniff the body rather than trusting the extension. Returns the absolute
     * path of a cached .vtt, or null. Blocking (IO).
     *
     * @param key       a stable per-title cache key (tmdb id + season/episode).
     * @param sourceTag WHICH SITE this caption came from, and it is not optional.
     *
     * The file name used to be "stream_sub_${key}_en.vtt" — per TITLE, with no
     * site in it. Unlike the backend's entries, which are keyed by a provider id
     * and really are the same file whoever you asked, this one is a different
     * file per site: each site hosts its own encode and ships the caption that
     * was cut for it. Sharing one name meant the cache-hit branch below handed
     * VidNest the caption VidFast had already saved — a subtitle timed to a
     * video the viewer was not watching, presented as "From this stream", and
     * sticky until the cache was cleared. First site opened won, forever.
     */
    fun cacheStreamSubtitle(
        context: Context,
        url: String,
        key: String,
        sourceTag: String,
    ): String? {
        val tag = sourceTag.lowercase().filter { it.isLetterOrDigit() }
            .ifBlank { "src" }.take(24)
        val file = File(context.cacheDir, "stream_sub_${key}_$tag.vtt")
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
            // Nothing will ever read the old per-title name again, so drop it as
            // we pass rather than leaving ~100KB of it per title watched. Guarded
            // on the name: a source tagged "en" would otherwise delete the file
            // written on the line above.
            if (tag != "en") {
                runCatching { File(context.cacheDir, "stream_sub_${key}_en.vtt").delete() }
            }
            Log.d(TAG, "subs: cached STREAM VTT (${vtt.length} chars) → ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "subs: stream subtitle failed: ${e.message}")
            null
        }
    }

    // ── NO REGEX BELOW THIS LINE, AND THAT IS THE WHOLE POINT ────────────────
    //
    // MEASURED on the onn 4K box with Debug.getNativeHeapAllocatedSize() read
    // either side of every statement in this path, on a 125KB subtitle:
    //
    //     downloadSubtitle  ........      +0KB
    //     body.bytes()      ........      +0KB
    //     gunzip branch     ........      +0KB
    //     String(bytes)     ........      +1KB
    //     srtToVtt          ........ +103171KB     <-- Regex.replace
    //     measureVtt        ........  +22137KB     <-- Regex.findAll
    //
    // 125MB of native heap to convert and measure 125KB of text. The network was
    // free; the parsing was not.
    //
    // WHY IT WAS NATIVE, which is what made it so hard to find: on Android
    // java.util.regex is a JNI wrapper over ICU4C, so Kotlin's Regex allocates in
    // native ICU buffers rather than on the Java heap. Every earlier hunt through
    // Dalvik, GL mtrack and the codec pools came up empty for exactly that reason
    // — dumpsys showed Native Heap climbing while every Java-side figure sat flat.
    //
    // Both jobs are ordinary character scanning. Neither needs a regex engine,
    // and a subtitle is the worst possible input for one: a six-figure character
    // count with thousands of matches.

    /** (cue count, first cue start, last cue end) — enough to spot a mistimed file. */
    private fun measureVtt(vtt: String): Triple<Int, Long, Long> {
        var count = 0
        var first = Long.MAX_VALUE
        var last = 0L
        for (line in vtt.lineSequence()) {
            val arrow = line.indexOf("-->")
            if (arrow < 0) continue
            val start = parseTimestamp(line, 0, arrow) ?: continue
            val end = parseTimestamp(line, arrow + 3, line.length) ?: continue
            count++
            if (start < first) first = start
            if (end > last) last = end
        }
        return Triple(count, if (first == Long.MAX_VALUE) 0L else first, last)
    }

    /**
     * First "HH:MM:SS.mmm" (or ",mmm") in [from, to), as milliseconds, or null.
     *
     * Validates the separators as well as the digits so a stray number in a line
     * of dialogue cannot be mistaken for a cue time — the old regex was strict
     * about shape, and this has to be too.
     */
    private fun parseTimestamp(s: String, from: Int, to: Int): Long? {
        var i = from
        while (i < to && (s[i] < '0' || s[i] > '9')) i++
        if (i + 12 > to) return null
        if (s[i + 2] != ':' || s[i + 5] != ':') return null
        val sep = s[i + 8]
        if (sep != '.' && sep != ',') return null
        var value = 0L
        for (offset in intArrayOf(0, 1, 3, 4, 6, 7, 9, 10, 11)) {
            val c = s[i + offset]
            if (c < '0' || c > '9') return null
        }
        fun d(k: Int) = (s[i + k] - '0')
        value += (d(0) * 10 + d(1)) * 3_600_000L
        value += (d(3) * 10 + d(4)) * 60_000L
        value += (d(6) * 10 + d(7)) * 1_000L
        value += (d(9) * 100 + d(10) * 10 + d(11)).toLong()
        return value
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
            val s = String(bytes, Charsets.UTF_8)
            return s
        }
    }

    /**
     * Minimal SRT→WebVTT: fix timestamp separators, drop the OpenSubtitles promo
     * cues. Same output as the regex version it replaced — see the note above
     * measureVtt for why that version cost 103MB.
     *
     * The comma→dot swap is applied only to lines containing "-->", which is both
     * cheaper and safer than the old pattern: a line of dialogue can no longer be
     * rewritten just because it happens to contain something timestamp-shaped.
     *
     * String.replace(String, String) and String.split(String) are LITERAL in
     * Kotlin, not regex, so the three below are ordinary scans and stay.
     */
    private fun srtToVtt(srt: String): String {
        val cleaned = srt.replace("﻿", "")            // strip BOM
            .replace("\r\n", "\n").replace("\r", "\n")

        val out = StringBuilder(cleaned.length + 16).append("WEBVTT\n\n")
        var wroteAny = false
        for (rawBlock in cleaned.split("\n\n")) {
            val block = rawBlock.trim()
            if (block.isEmpty()) continue
            if (block.contains("opensubtitles", ignoreCase = true)) continue
            if (wroteAny) out.append("\n\n")
            wroteAny = true
            var firstLine = true
            for (line in block.split('\n')) {
                if (!firstLine) out.append('\n')
                firstLine = false
                if (line.contains("-->")) out.append(line.replace(',', '.')) else out.append(line)
            }
        }
        out.append('\n')
        return out.toString()
    }
}
