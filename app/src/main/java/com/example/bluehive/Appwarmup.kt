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
//   4. Image warming — decode ONLY first-paint images into Coil's memory
//      cache (first few trending backdrops + first row of movie posters).
//      Capped low on purpose: warming everything causes GC churn on cheap
//      TV boxes the moment the home screen mounts.
//
// Everything is best-effort. Any failure leaves that slice null and the home
// screen falls back to its normal fetch. Nothing here can block navigation —
// the LoadingScreen navigates on a hard timer regardless of this work.
// ─────────────────────────────────────────────────────────────────────────────

import android.util.Log
import coil.request.ImageRequest
import com.example.bluehive.api.ApiClient
import com.example.bluehive.repository.TrailerRepository
import com.example.bluehive.singleShelfComponents.ContentShelf
import com.example.bluehive.singleShelfComponents.MediaType
import com.example.bluehive.singleShelfComponents.ShelfRepository
import com.example.bluehive.trendingComponents.TrendingRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object AppWarmup {

    private const val TAG = "AppWarmup"

    // Item 4 — first-paint only. Keep these small.
    private const val TRENDING_IMAGES_TO_WARM = 3   // carousel shows one at a time
    private const val MOVIE_POSTERS_TO_WARM   = 8   // first visible shelf row

    private const val SHELF_PAGE_SIZE = 20   // MUST match ShelfRowContent's pageSize

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

                // ── Step 4: image warming (first-paint only) ────────────────
                warmFirstPaintImages(app, trending.mapNotNull { it.backdropPath }, movies?.items?.mapNotNull { it.posterUrl } ?: emptyList())
            }
        } catch (e: Exception) {
            // Catch-all: warm-up is never allowed to crash the splash.
            Log.w(TAG, "⚠️ Warm-up aborted: ${e.message}")
        }
    }

    /**
     * Decodes a small number of first-paint images into Coil's memory cache so
     * the home screen's first row renders instantly instead of popping in.
     * Capped deliberately (item 4) to avoid GC churn on memory-limited boxes.
     */
    private fun warmFirstPaintImages(
        app: BlueHiveApplication,
        trendingBackdrops: List<String>,
        moviePosters: List<String>,
    ) {
        val loader = BlueHiveApplication.coilImageLoader

        trendingBackdrops.take(TRENDING_IMAGES_TO_WARM).forEach { url ->
            loader.enqueue(
                ImageRequest.Builder(app)
                    // Match the transform the trending carousel applies so the
                    // warmed cache key lines up with what the UI requests.
                    .data(url.replace("/w1280/", "/w780/"))
                    .build()
            )
        }

        moviePosters.take(MOVIE_POSTERS_TO_WARM).forEach { url ->
            loader.enqueue(
                ImageRequest.Builder(app)
                    .data(url)
                    .build()
            )
        }

        Log.d(TAG, "🖼️ Warming ${minOf(trendingBackdrops.size, TRENDING_IMAGES_TO_WARM)} backdrops + ${minOf(moviePosters.size, MOVIE_POSTERS_TO_WARM)} posters")
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