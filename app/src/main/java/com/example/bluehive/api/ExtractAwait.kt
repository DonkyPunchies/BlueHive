package com.example.bluehive.api

import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * Extraction that outlives the request clock.
 *
 * THE BUG THIS FIXES, measured 2026-08-30. Jeepers Creepers and Poltergeist both
 * reported "extraction failed"; pressing play again a minute later played
 * instantly. The log shows two SocketTimeoutExceptions 61s apart and no server
 * error at all — because there was none. This client's OkHttp readTimeout is 30s,
 * while the server is allowed far longer:
 *
 *     extractor per attempt          45s   (timeout_s in the extract body)
 *     API -> extractor read timeout  75s
 *     API attempts                    2    (EXTRACT_ATTEMPTS)
 *     -------------------------------------------------------------
 *     server worst case             ~90s   vs   client patience  30s
 *
 * So on any cold, slow title the client hangs up at 30s while the extraction
 * keeps running, succeeds, and fills the cache. The user sees a failure, presses
 * play again, and gets an instant hit off the work their first press paid for.
 * Nothing was broken except our willingness to wait.
 *
 * Raising the timeout is the wrong fix: it holds a request open for a minute and
 * a half and still guesses. The backend already exposes /api/extract/start and
 * /api/extract/job/{id} — built for exactly this, and never called by any client.
 * Start returns immediately, the extraction runs detached, and we poll. Each HTTP
 * call is short, so the 30s timeout stops being a ceiling on how long a source
 * may take.
 *
 * RETURNS THE SAME TYPE as [BluehiveApiService.extractStream] on purpose. Seven
 * call sites consume Response<ExtractResponse> and read failures through
 * parseExtractReason(), so matching the shape keeps this to a one-line change per
 * site instead of seven rewrites of working error handling.
 */
private const val TAG = "scraper-vidfast"

/** Total time we will wait for a job before giving up. */
private const val DEADLINE_MS = 120_000L

/**
 * Gap between polls. Extractions run tens of seconds, so sub-second polling buys
 * nothing and just adds requests; a second is invisible against a 45s job.
 */
private const val POLL_MS = 1_000L

/** Error body in the shape parseExtractReason() already understands. */
private fun errorBody(reason: String, detail: String) =
    """{"detail":{"reason":"$reason","detail":"${detail.replace("\"", "'")}"}}"""
        .toResponseBody("application/json".toMediaTypeOrNull())

/**
 * Run one extraction to completion, however long it legitimately takes.
 *
 * FALLS BACK TO THE SYNCHRONOUS ENDPOINT if the backend does not know the job
 * API (404/405). A client that hard-required /api/extract/start would break every
 * install pointed at an older server, and this is exactly the kind of change that
 * ships ahead of a deploy.
 */
suspend fun BluehiveApiService.extractStreamAwait(
    type:    String,
    tmdbId:  Int,
    season:  Int? = null,
    episode: Int? = null,
    source:  String = "vidfast",
    refresh: Int = 0,
    mtype:   String? = null,
    title:   String? = null,
    profile: Int? = null,
    device:  String? = null,
    audio:   String? = null,
    server:  String? = null,
): Response<ExtractResponse> {

    val started = System.currentTimeMillis()

    val startResp = extractStart(
        type = type, tmdbId = tmdbId, season = season, episode = episode,
        source = source, refresh = refresh, mtype = mtype, title = title,
        profile = profile, device = device, audio = audio, server = server,
    )

    // Older backend: no job API. Use the one-shot path so behaviour is unchanged
    // rather than broken.
    if (startResp.code() == 404 || startResp.code() == 405) {
        Log.w(TAG, "job API absent (http=${startResp.code()}) — falling back to /api/extract")
        return extractStream(
            type = type, tmdbId = tmdbId, season = season, episode = episode,
            source = source, refresh = refresh, mtype = mtype, title = title,
            profile = profile, device = device, audio = audio, server = server,
        )
    }

    // Rate limits and auth failures are surfaced verbatim — the caller's error
    // handling already distinguishes them and a 429 in particular must not be
    // retried here.
    if (!startResp.isSuccessful) {
        return Response.error(
            startResp.code(),
            startResp.errorBody() ?: errorBody("unknown", "extract start failed"),
        )
    }

    val start = startResp.body()
        ?: return Response.error(502, errorBody("unknown", "empty start response"))

    // Cache hit: answered inline, no polling, same cost as the old path.
    if (start.state == "done" && start.result != null) {
        warmReportedHosts(start.result)
        return Response.success(start.result)
    }

    val jobId = start.jobId
        ?: return Response.error(
            502, errorBody("unknown", "no job id and no inline result (state=${start.state})"),
        )

    while (System.currentTimeMillis() - started < DEADLINE_MS) {
        delay(POLL_MS)

        val pollResp = runCatching { extractJob(jobId) }.getOrNull()
        // A single failed poll is not a failed extraction — the job keeps running
        // server-side, so keep asking until the deadline.
        if (pollResp == null || !pollResp.isSuccessful) continue
        val poll = pollResp.body() ?: continue

        when (poll.state) {
            "done" -> {
                val r = poll.result
                    ?: return Response.error(502, errorBody("unknown", "job done with no result"))
                Log.d(TAG, "job $jobId done in ${System.currentTimeMillis() - started}ms " +
                        "(server ${poll.ms ?: 0}ms)")
                warmReportedHosts(r)
                return Response.success(r)
            }
            "failed" -> {
                val reason = poll.error?.reason ?: "unknown"
                val detail = poll.error?.detail ?: "extraction failed"
                Log.e(TAG, "job $jobId failed: $reason — $detail")

                // AN UNCLASSIFIED FAILURE IS A SERVER CRASH, NOT A VERDICT ABOUT
                // THE TITLE, so fall back to the synchronous path rather than
                // telling the user the source is unavailable.
                //
                // MEASURED 2026-08-30, and the reason this guard exists: the
                // first client ever to call /api/extract/start hit
                // "NameError: name 'meta' is not defined" on every job, for every
                // source. The endpoint had shipped untested because nothing
                // called it. Extraction broke completely, and the app blamed the
                // sources — which were all fine.
                //
                // ONLY "unknown" falls through. Classified reasons (blocked,
                // timeout, no_manifest, rate_limited) are real answers about this
                // title and must reach the caller untouched, or a genuinely dead
                // source would be extracted twice on every attempt.
                if (reason == "unknown") {
                    Log.w(TAG, "unclassified job failure — retrying on /api/extract")
                    return extractStream(
                        type = type, tmdbId = tmdbId, season = season, episode = episode,
                        source = source, refresh = refresh, mtype = mtype, title = title,
                        profile = profile, device = device, audio = audio, server = server,
                    )
                }
                return Response.error(502, errorBody(reason, detail))
            }
            // The job outlived its TTL. Distinct from an unknown id, and worth
            // saying so rather than reporting a generic failure.
            "expired" -> {
                Log.e(TAG, "job $jobId expired before it finished")
                return Response.error(504, errorBody("timeout", "extraction job expired"))
            }
            // pending / running — keep waiting.
        }
    }

    Log.e(TAG, "job $jobId still unfinished after ${DEADLINE_MS}ms — giving up")
    return Response.error(504, errorBody("timeout", "extraction exceeded ${DEADLINE_MS / 1000}s"))
}

/**
 * Pre-warm DNS/ECH for the hosts this manifest will actually use.
 *
 * Called the instant a manifest is in hand, so the warm overlaps the player's
 * own setup instead of competing with its first segment request. Never throws
 * and never blocks: a failed warm leaves behaviour exactly as it is today.
 */
private suspend fun warmReportedHosts(r: ExtractResponse) {
    val hosts = r.cdnHosts ?: return
    // AWAITED, not fired and forgotten. MEASURED 2026-08-30: a fire-and-forget
    // warm gave polarcandy.top a 538ms head start over its own ECH lookup and
    // lost, costing a relay. Waiting here is cheap because it self-tunes — an
    // already-warm host answers in tens of milliseconds — and it happens while
    // the player is still being constructed, so most of it is free.
    runCatching {
        com.example.bluehive.webview.exoplayer_resources.ExoPlayerConfig
            .warmHostsAwait(hosts)
    }
}
