package com.example.bluehive

// ─────────────────────────────────────────────────────────────────────────────
// AppWarmup.kt
//
// Runs during the LoadingScreen's display window. Warms the app so the home
// screen has data ready instead of fetching everything cold on arrival.
//
// Sequence (this ORDER matters):
//   1. Token prime — one cheap authenticated call (getMe). If the stored
//      access token is stale, the TokenAuthenticator in ApiClient silently
//      refreshes it DURING this call. Every parallel call after it then uses
//      the fresh token. Firing data calls before this risks a wave of 401s.
//   2. Config / force-update hook — placeholder, no-op for now. When the
//      backend endpoint exists, this is where maintenance mode / mandatory
//      update checks slot in.
//   3. Parallel fan-out — profile list, trending, trailers all at once.
//      Once the profile list resolves, the most-recently-used profile
//      (highest last_login_at) drives the profile-specific calls:
//      continue watching + movies "popular" shelf page 1.
//   4. Carousel image prefetch — trending + trailers are PROFILE-AGNOSTIC and
//      static until the backend refreshes them, so the ENTIRE image set for
//      both carousels is pulled here, during the bee-logo splash. First-paint
//      images stay memory-resident; the rest fill the DISK cache only
//      (READ_ONLY memory policy) so a 2 GB box's memory cache isn't churned
//      by images that won't show for minutes. At runtime the carousels'
//      ±CAROUSEL_PRELOAD_RADIUS window promotes each image disk→memory just
//      before it's shown — network never touches a carousel tick again.
//
// Everything is best-effort. Any failure leaves that slice null and the home
// screen falls back to its normal fetch. Nothing here can block navigation —
// the LoadingScreen navigates on a hard timer regardless of this work.
// ─────────────────────────────────────────────────────────────────────────────

import android.util.Log
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.TrendingItem
import com.example.bluehive.homeScreenSectionRules.TRAILER_THUMB_PX_H
import com.example.bluehive.homeScreenSectionRules.TRAILER_THUMB_PX_W
import com.example.bluehive.homeScreenSectionRules.TRENDING_BACKDROP_PX_H
import com.example.bluehive.homeScreenSectionRules.TRENDING_BACKDROP_PX_W
import com.example.bluehive.homeScreenSectionRules.trailerThumbMemoryKey
import com.example.bluehive.homeScreenSectionRules.trailerThumbUrl
import com.example.bluehive.homeScreenSectionRules.trendingBackdropMemoryKey
import com.example.bluehive.homeScreenSectionRules.trendingBackdropUrl
import com.example.bluehive.models.LatestTrailer
import com.example.bluehive.repository.TrailerRepository
import com.example.bluehive.singleShelfComponents.ContentShelf
import com.example.bluehive.singleShelfComponents.MediaType
import com.example.bluehive.singleShelfComponents.ShelfRepository
import com.example.bluehive.trendingComponents.TrendingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AppWarmup {

    private const val TAG = "AppWarmup"

    // First-paint memory residents — what's on screen the instant home mounts.
    // Everything past these counts is disk-only during the splash.
    private const val TRENDING_MEMORY_WARM  = 6   // hero backdrop + first few ticks
    private const val TRAILER_MEMORY_WARM   = 4
    private const val MOVIE_POSTERS_TO_WARM = 8   // first visible shelf row

    private const val SHELF_PAGE_SIZE = 20   // MUST match ShelfRowContent's pageSize

    // Gentle spacing between enqueues — the ImageLoader's capped fetcher (4) /
    // decoder (2) dispatchers do the real throttling; this just avoids dumping
    // 140 requests into Coil's queue in one frame. Budget matters: the spacing
    // alone puts a (items × spacing) floor on the splash, so 10ms keeps the
    // whole enqueue pass (~1.4s for 140 images) inside LOADING_MIN_DISPLAY_MS —
    // zero perceived cost on a warm cache. 25ms was adding ~3.5s to EVERY cold
    // start, downloads or not.
    private const val PREFETCH_SPACING_MS = 10L

    // The carousel prefetch runs HERE, not in the caller's scope: the
    // LoadingScreen cancels run() at its hard cap, but this scope survives, so
    // on a slow network the remaining images keep trickling into the disk
    // cache behind the profile picker instead of being abandoned.
    private val imagePrefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Runs the full warm-up. Suspends until all best-effort work finishes OR
     * the caller's coroutine scope is cancelled (LoadingScreen cancels this
     * the instant its hard timer fires). Never throws — every step is guarded.
     */
    suspend fun run(app: BlueHiveApplication) {
        try {
            // ── Step 1: token prime — REMOVED (Phase 2) ────────────────────
            // Obsolete under the host model. BlueHive no longer rotates its own
            // tokens: HostEntryActivity injects a fresh access token at launch,
            // and bluehiveApi/DeviceEventStream pull fresh ones from the host on
            // 401. There is nothing to "prime." The old getMe() call hit
            // platformApi's /api/me, which does NOT accept a host-injected token
            // (it expects BlueHive's own platform identity, which no longer
            // exists), so it 401'd every launch — harmless only because it was
            // best-effort. Removed entirely.

            // ── Step 2: config / force-update hook ──────────────────────────
            // HOOK: when the backend ships GET /api/android/app-config, call it
            // here and short-circuit into maintenance / force-update UI. For
            // now this is intentionally a no-op so there's no backend dependency.
            checkAppConfigPlaceholder()

            // ── Step 3: parallel fan-out ────────────────────────────────────
            coroutineScope {
                // Locally-remembered profile from the last session. Lets the
                // expensive personalized Netflix prefetch start NOW, in parallel
                // with the profiles-list fetch, instead of waiting for it to
                // resolve. -1 means "no remembered profile" → skip Netflix warm.
                val warmProfileId = com.example.bluehive.auth.SessionManager.get().lastProfileId

                val netflixMoviesDeferred = if (warmProfileId != -1) async {
                    runCatching {
                        ShelfRepository().loadPage(
                            mediaType = MediaType.MOVIES,
                            shelf     = ContentShelf.NETFLIX,
                            page      = 0,
                            pageSize  = SHELF_PAGE_SIZE,
                            profileId = warmProfileId,
                        )
                    }.getOrNull()
                } else null

                val netflixTvDeferred = if (warmProfileId != -1) async {
                    runCatching {
                        ShelfRepository().loadPage(
                            mediaType = MediaType.TV_SHOWS,
                            shelf     = ContentShelf.NETFLIX,
                            page      = 0,
                            pageSize  = SHELF_PAGE_SIZE,
                            profileId = warmProfileId,
                        )
                    }.getOrNull()
                } else null

                val trendingDeferred = async {
                    runCatching { TrendingRepository().getTrending(trendType = "day", limit = 40) }
                        .getOrDefault(emptyList())
                }
                val trailersDeferred = async {
                    runCatching { TrailerRepository().getLatestTrailers(limit = 100, offset = 0) }
                        .getOrDefault(emptyList())
                }
                val profilesDeferred = async {
                    runCatching { ApiClient.bluehiveApi.listProfiles() }.getOrDefault(emptyList())
                }

                // Resolve the most-recently-used profile from the list. ISO-8601
                // timestamps sort correctly as plain strings, so max-by-string
                // gives the latest login. Falls back to the first profile.
                val profiles      = profilesDeferred.await()
                val lastProfileId = profiles
                    .filter { it.last_login_at != null }
                    .maxByOrNull { it.last_login_at!! }
                    ?.id
                    ?: profiles.firstOrNull()?.id
                    ?: -1

                Log.d(TAG, "📋 ${profiles.size} profiles, last-used id=$lastProfileId")

                // Profile-specific calls only fire when we actually have a profile.
                val cwDeferred = if (lastProfileId != -1) async {
                    runCatching {
                        ApiClient.bluehiveApi.getWatchHistory(profileId = lastProfileId, limit = 15, offset = 0)
                    }.getOrNull()
                } else null

                val moviesDeferred = if (lastProfileId != -1) async {
                    runCatching {
                        ShelfRepository().loadPage(
                            mediaType = MediaType.MOVIES,
                            shelf     = ContentShelf.POPULAR,
                            page      = 0,
                            pageSize  = SHELF_PAGE_SIZE,
                        )
                    }.getOrNull()
                } else null



                val trending = trendingDeferred.await()
                val trailers = trailersDeferred.await()
                val cw       = cwDeferred?.await()
                val movies   = moviesDeferred?.await()

                // The Netflix prefetch was fired against warmProfileId (the
                // locally-remembered guess). Only keep it if that guess matches
                // the authoritative profile we resolved from the API — otherwise
                // it's for the wrong profile and the home screen would reject it
                // anyway. Mismatch → null → home fetches Netflix live.
                val guessMatched   = (warmProfileId != -1 && warmProfileId == lastProfileId)
                val netflixMovies  = if (guessMatched) netflixMoviesDeferred?.await() else null
                val netflixTvShows = if (guessMatched) netflixTvDeferred?.await() else null

                // ── Store everything with a capture timestamp for TTL ───────
                app.storeHomePrefetch(
                    BlueHiveApplication.HomeScreenPrefetch(
                        capturedAt       = System.currentTimeMillis(),
                        profileId        = lastProfileId,
                        trending         = trending.ifEmpty { null },
                        trailers         = trailers.ifEmpty { null },
                        continueWatching = cw,
                        moviesPopular    = movies,
                        netflixMovies    = netflixMovies,
                        netflixTvShows   = netflixTvShows,
                    )
                )
                Log.d(TAG, "📦 Home prefetch stored (profile=$lastProfileId, guessMatched=$guessMatched, trending=${trending.size}, trailers=${trailers.size}, cw=${cw?.size}, movies=${movies?.items?.size}, nflxMovies=${netflixMovies?.items?.size}, nflxTv=${netflixTvShows?.items?.size})")

                // ── Step 4: carousel image prefetch (FULL set) ──────────────
                // Launched in imagePrefetchScope so the splash's timeout can't
                // kill the tail; join() lets a normal network finish while the
                // bee logo is still on screen.
                val imageJob = imagePrefetchScope.launch {
                    prefetchCarouselImages(
                        app          = app,
                        trending     = trending,
                        trailers     = trailers,
                        moviePosters = movies?.items?.mapNotNull { it.posterUrl } ?: emptyList(),
                    )
                }
                imageJob.join()
            }
        } catch (e: Exception) {
            // Catch-all: warm-up is never allowed to crash the splash.
            Log.w(TAG, "⚠️ Warm-up aborted: ${e.message}")
        }
    }

    /**
     * Pulls the COMPLETE trending + trailer image sets during the splash.
     *
     * Every request is built with the shared spec helpers from
     * homeScreenSectionRules, so the bytes land under the exact disk/memory
     * keys the carousels ask for. Memory policy is tiered for 2 GB boxes:
     * the first-paint window is memory-ENABLED (instant first frame); the
     * rest are READ_ONLY — they fill the disk cache without evicting what's
     * on screen. Suspends until every request reaches a terminal state, so
     * the LoadingScreen's join() genuinely means "the carousels are local."
     */
    private suspend fun prefetchCarouselImages(
        app: BlueHiveApplication,
        trending: List<TrendingItem>,
        trailers: List<LatestTrailer>,
        moviePosters: List<String>,
    ) {
        val loader = BlueHiveApplication.coilImageLoader
        val jobs = mutableListOf<Deferred<*>>()

        // Trending backdrops — the hero panel, so it goes first.
        trending.forEachIndexed { i, item ->
            val url = item.backdropPath ?: return@forEachIndexed
            jobs += loader.enqueue(
                ImageRequest.Builder(app)
                    .data(trendingBackdropUrl(url))
                    .size(TRENDING_BACKDROP_PX_W, TRENDING_BACKDROP_PX_H)
                    .memoryCacheKey(trendingBackdropMemoryKey(item.trendingId))
                    .memoryCachePolicy(if (i < TRENDING_MEMORY_WARM) CachePolicy.ENABLED else CachePolicy.READ_ONLY)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .allowRgb565(true)
                    .allowHardware(true)
                    .build()
            ).job
            delay(PREFETCH_SPACING_MS)
        }

        // Trailer thumbs — full set, same tiering.
        trailers.forEachIndexed { i, t ->
            jobs += loader.enqueue(
                ImageRequest.Builder(app)
                    .data(trailerThumbUrl(t.imgSrc))
                    .size(TRAILER_THUMB_PX_W, TRAILER_THUMB_PX_H)
                    .memoryCacheKey(trailerThumbMemoryKey(t.id))
                    .memoryCachePolicy(if (i < TRAILER_MEMORY_WARM) CachePolicy.ENABLED else CachePolicy.READ_ONLY)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .allowRgb565(true)
                    .allowHardware(true)
                    .build()
            ).job
            delay(PREFETCH_SPACING_MS)
        }

        // First shelf row posters — memory-resident, same as the old warm.
        moviePosters.take(MOVIE_POSTERS_TO_WARM).forEach { url ->
            jobs += loader.enqueue(
                ImageRequest.Builder(app)
                    .data(url)
                    .build()
            ).job
        }

        val results = jobs.awaitAll()
        val ok = results.count { it is SuccessResult }
        Log.d(TAG, "🖼️ Carousel prefetch done: $ok/${jobs.size} images local " +
                "(${trending.size} backdrops, ${trailers.size} trailer thumbs, " +
                "${minOf(moviePosters.size, MOVIE_POSTERS_TO_WARM)} posters)")
    }

    /**
     * HOOK for item 7 (config / force-update). Intentionally empty.
     *
     * When the backend ships its config endpoint, implement it like:
     *
     *   val cfg = runCatching { ApiClient.platformApi.getAppConfig() }.getOrNull()
     *   if (cfg?.maintenanceMode == true)  → throw/signal maintenance state
     *   if (cfg?.minSupportedVersion > current) → throw/signal force-update
     *
     * Returning normally = "all clear, proceed with warm-up."
     */
    private fun checkAppConfigPlaceholder() {
        // No-op until the backend endpoint exists.
    }
}