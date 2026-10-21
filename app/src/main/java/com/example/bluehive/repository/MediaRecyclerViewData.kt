package com.example.bluehive.repository

import android.util.Log
import com.example.bluehive.api.ApiClient
import com.example.bluehive.models.MediaBrowseResponse
import com.example.bluehive.models.MediaItem
import com.example.bluehive.models.WatchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRecyclerViewData {
    companion object {
        private const val TAG = "MediaRecyclerViewData"
        private const val DEFAULT_PAGE_SIZE = 20

        // FIXED: Match your actual database slugs
        const val NETFLIX_GROUP = "netflix"
        const val PRIME_VIDEO_GROUP = "prime_video"
        const val DISNEY_PLUS_GROUP = "disney"
        const val HULU_GROUP = "hulu"
        const val HBO_MAX_GROUP = "hbo"
        const val APPLE_TV_PLUS_GROUP = "apple"
        const val PARAMOUNT_PLUS_GROUP = "paramount"
        const val PEACOCK_GROUP = "peacock"
    }





    suspend fun loadProviderGroupMedia(
        groupSlug: String,
        mediaType: String,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        sortBy: String? = null,
        profileId: Int? = null
    ): MediaBrowseResponse = withContext(Dispatchers.IO) {
        val offset = page * pageSize



        // Production mode
        try {
            val response = api.getMediaByProviderGroup(
                groupSlug = groupSlug,
                mediaType = mediaType,
                limit = pageSize,
                offset = offset,
                sortBy = sortBy,
                profileId = profileId
            )

            Log.d(TAG, "✅ ProviderGroup '$groupSlug' returned ${response.items.size} $mediaType items (page=$page)")

            // ✅ Return the response directly - it already has all fields
            response

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading provider-group $groupSlug: ${e.message}", e)
            MediaBrowseResponse(
                groupSlug = groupSlug,
                groupName = getGroupDisplayName(groupSlug),  // ✅ Fallback display name
                mediaType = mediaType,
                totalCount = 0,
                count = 0,
                limit = pageSize,
                offset = offset,
                items = emptyList()
            )
        }
    }

    // ✅ NEW: Helper function for display names
    private fun getGroupDisplayName(groupSlug: String): String {
        return when (groupSlug) {
            NETFLIX_GROUP -> "Netflix"
            PRIME_VIDEO_GROUP -> "Prime Video"
            DISNEY_PLUS_GROUP -> "Disney+"
            HULU_GROUP -> "Hulu"
            HBO_MAX_GROUP -> "HBO Max"
            APPLE_TV_PLUS_GROUP -> "Apple TV+"
            PARAMOUNT_PLUS_GROUP -> "Paramount+"
            PEACOCK_GROUP -> "Peacock"
            else -> groupSlug.split("-").joinToString(" ") {
                it.replaceFirstChar { c -> c.uppercase() }
            }
        }
    }







    private val api = ApiClient.trailerApi

    suspend fun browseMedia(
        mediaType: String = "movie",
        listType: String = "popular",
        offset: Int = 0,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        watchProvider: String? = null
    ): MediaBrowseResponse = withContext(Dispatchers.IO) {



        // ✅ NORMAL BUILD: hit your backend API
        try {
            val response = api.browseMedia(
                mediaType = mediaType,
                listType = listType,
                limit = pageSize,
                offset = offset,
                // "rating", NOT "vote_average". The backend's sort map never had a
                // "vote_average" key, so this silently fell through to its default
                // and the Top Rated shelf was ordered by POPULARITY. It looked
                // right - the titles were good films - which is why it survived.
                //
                // The backend now accepts both, so shipping this is not urgent for
                // correctness; it is here so the wire value matches the documented
                // one and the alias can eventually be retired.
                sortBy = if (listType == "top_rated") "rating" else "popularity",
                watchProvider = watchProvider?.toIntOrNull()
            )
            Log.d(
                TAG,
                "✅ Retrieved ${response.items.size} '${listType}' $mediaType items (offset=$offset, provider=$watchProvider)"
            )
            response
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading $listType media: ${e.message}", e)

            // ✅ FIX: All parameters explicitly named
            MediaBrowseResponse(
                groupSlug = watchProvider,
                groupName = watchProvider?.let { getGroupDisplayName(it) },
                mediaType = mediaType,
                totalCount = 0,
                count = 0,
                limit = pageSize,
                offset = offset,
                items = emptyList()
            )
        }
    }







    // =================================================================================================> Movies
    suspend fun loadPopularMovies(
        page: Int,
        moviesPerPage: Int = DEFAULT_PAGE_SIZE
    ): MediaBrowseResponse {
        val offset = page * moviesPerPage
        return browseMedia(
            mediaType = "movie",
            listType = "popular",
            offset = offset,
            pageSize = moviesPerPage
        )
    }

    suspend fun loadTopRatedMovies(
        page: Int,
        moviesPerPage: Int = DEFAULT_PAGE_SIZE
    ): MediaBrowseResponse {
        val offset = page * moviesPerPage
        return browseMedia(
            mediaType = "movie",
            listType = "top_rated",
            offset = offset,
            pageSize = moviesPerPage
        )
    }

    suspend fun loadNetflixMovies(
        page: Int,
        moviesPerPage: Int = DEFAULT_PAGE_SIZE,
        profileId: Int? = null
    ): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug   = NETFLIX_GROUP,
            mediaType   = "movie",
            page        = page,
            pageSize    = moviesPerPage,
            sortBy      = "personalized",
            profileId   = profileId
        )
    }


    suspend fun loadPrimeVideoMovies(page: Int, moviesPerPage: Int = DEFAULT_PAGE_SIZE): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug = PRIME_VIDEO_GROUP,
            mediaType = "movie",
            page = page,
            pageSize = moviesPerPage
        )
    }


    suspend fun loadDisneyPlusMovies(page: Int, moviesPerPage: Int = DEFAULT_PAGE_SIZE): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug = DISNEY_PLUS_GROUP,
            mediaType = "movie",
            page = page,
            pageSize = moviesPerPage
        )
    }


    suspend fun loadHuluMovies(page: Int, moviesPerPage: Int = DEFAULT_PAGE_SIZE): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug = HULU_GROUP,
            mediaType = "movie",
            page = page,
            pageSize = moviesPerPage
        )
    }

    suspend fun loadHboMaxMovies(page: Int, moviesPerPage: Int = DEFAULT_PAGE_SIZE): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug = HBO_MAX_GROUP,
            mediaType = "movie",
            page = page,
            pageSize = moviesPerPage
        )
    }

    suspend fun loadAppleTVPlusMovies(page: Int, moviesPerPage: Int = DEFAULT_PAGE_SIZE): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug = APPLE_TV_PLUS_GROUP,
            mediaType = "movie",
            page = page,
            pageSize = moviesPerPage
        )
    }

    suspend fun loadParamountPlusMovies(page: Int, moviesPerPage: Int = DEFAULT_PAGE_SIZE): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug = PARAMOUNT_PLUS_GROUP,
            mediaType = "movie",
            page = page,
            pageSize = moviesPerPage
        )
    }

    suspend fun loadPeacockMovies(page: Int, moviesPerPage: Int = DEFAULT_PAGE_SIZE): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug = PEACOCK_GROUP,
            mediaType = "movie",
            page = page,
            pageSize = moviesPerPage
        )
    }

    // =================================================================================================> TV

    suspend fun loadPopularTVShows(
        page: Int,
        tvShowsPerPage: Int = DEFAULT_PAGE_SIZE
    ): MediaBrowseResponse {
        val offset = page * tvShowsPerPage
        return browseMedia(
            mediaType = "tv",
            listType = "popular",
            offset = offset,
            pageSize = tvShowsPerPage
        )
    }

    suspend fun loadTopRatedTVShows(
        page: Int,
        tvShowsPerPage: Int = DEFAULT_PAGE_SIZE
    ): MediaBrowseResponse {
        val offset = page * tvShowsPerPage
        return browseMedia(
            mediaType = "tv",
            listType = "top_rated",
            offset = offset,
            pageSize = tvShowsPerPage
        )
    }

    suspend fun loadNetflixTVShows(
        page: Int,
        moviesPerPage: Int = DEFAULT_PAGE_SIZE,
        profileId: Int? = null
    ): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug   = NETFLIX_GROUP,
            mediaType   = "tv",
            page        = page,
            pageSize    = moviesPerPage,
            sortBy      = "personalized",
            profileId   = profileId
        )
    }

    suspend fun loadPrimeVideoTVShows(page: Int, moviesPerPage: Int = DEFAULT_PAGE_SIZE): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug = PRIME_VIDEO_GROUP,
            mediaType = "tv",
            page = page,
            pageSize = moviesPerPage
        )
    }

    suspend fun loadDisneyPlusTVShows(page: Int, moviesPerPage: Int = DEFAULT_PAGE_SIZE): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug = DISNEY_PLUS_GROUP,
            mediaType = "tv",
            page = page,
            pageSize = moviesPerPage
        )
    }

    suspend fun loadHuluTVShows(page: Int, moviesPerPage: Int = DEFAULT_PAGE_SIZE): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug = HULU_GROUP,
            mediaType = "tv",
            page = page,
            pageSize = moviesPerPage
        )
    }

    suspend fun loadHBOMaxTVShows(page: Int, moviesPerPage: Int = DEFAULT_PAGE_SIZE): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug = HBO_MAX_GROUP,
            mediaType = "tv",
            page = page,
            pageSize = moviesPerPage
        )
    }

    suspend fun loadAppleTVPlusTVShows(page: Int, moviesPerPage: Int = DEFAULT_PAGE_SIZE): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug = APPLE_TV_PLUS_GROUP,
            mediaType = "tv",
            page = page,
            pageSize = moviesPerPage
        )
    }

    suspend fun loadParamountPlusTVShows(page: Int, moviesPerPage: Int = DEFAULT_PAGE_SIZE): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug = PARAMOUNT_PLUS_GROUP,
            mediaType = "tv",
            page = page,
            pageSize = moviesPerPage
        )
    }

    suspend fun loadPeacockTVShows(page: Int, moviesPerPage: Int = DEFAULT_PAGE_SIZE): MediaBrowseResponse {
        return loadProviderGroupMedia(
            groupSlug = PEACOCK_GROUP,
            mediaType = "tv",
            page = page,
            pageSize = moviesPerPage
        )
    }

    // =================================================================================================> Anime

    /**
     * Animation genre (TMDB genre_id = 16) + English original language.
     * Covers English-language animated series.
     */
    suspend fun loadPopularAnimeSeries(
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): MediaBrowseResponse {
        val offset = page * pageSize
        return browseAnimeMedia(
            mediaType = "tv",
            offset    = offset,
            pageSize  = pageSize
        )
    }

    /**
     * Animation genre (TMDB genre_id = 16) + English original language.
     * Covers English-language animated movies.
     */
    suspend fun loadPopularAnimeMovies(
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): MediaBrowseResponse {
        val offset = page * pageSize
        return browseAnimeMedia(
            mediaType = "movie",
            offset    = offset,
            pageSize  = pageSize
        )
    }



    /**
     * "New This Season" — anime (both tv AND movies, mixed) whose most recent
     * release falls within the last 4 months, NEWEST EPISODE FIRST.
     *
     * Was sorted by popularity, which made the name a lie: a show whose last
     * episode aired in June sat above one that aired the same morning, purely
     * on popularity, and the order barely moved from week to week. latest_release
     * orders by the same date the window filters on, so the row finally answers
     * the question it is titled after. The backend breaks ties on rating and
     * drops anything not yet aired.
     *
     * media_type is omitted so the backend returns both types in one result.
     */
    suspend fun loadNewThisSeasonAnime(
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): MediaBrowseResponse = withContext(Dispatchers.IO) {
        val fourMonthsAgo = java.time.LocalDate.now().minusMonths(4).toString() // YYYY-MM-DD
        val offset = page * pageSize
        Log.d(TAG, "🔍 ANIME NEW SEASON → genre=16 lang=Japanese after=$fourMonthsAgo offset=$offset")
        try {
            val response = api.browseMedia(
                mediaType        = null,          // both tv + movies, mixed
                listType         = null,
                genreId          = 16,
                originalLanguage = "Japanese",
                releasedAfter    = fourMonthsAgo,
                limit            = pageSize,
                offset           = offset,
                // Must stay a key the browse endpoint's order_map knows.
                // resolve_order falls back to popularity for anything it does
                // not recognise, silently -- that is how the Top Rated shelf
                // spent months sorted by the wrong thing.
                sortBy           = "latest_release"
            )
            Log.d(TAG, "✅ Anime new-season returned ${response.items.size} items (offset=$offset)")
            response
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading new-season anime: ${e.message}", e)
            MediaBrowseResponse(
                groupSlug = null, groupName = null, mediaType = null,
                totalCount = 0, count = 0, limit = pageSize,
                offset = offset, items = emptyList()
            )
        }
    }



    /**
     * Internal helper — calls /api/media/browse with the two anime filters:
     *   genre_id=16 (Animation) + original_language=en (English)
     * media_type is passed in so the same logic serves both series and movies.
     */
    private suspend fun browseAnimeMedia(
        mediaType: String,
        offset: Int,
        pageSize: Int
    ): MediaBrowseResponse = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 ANIME REQUEST → type=$mediaType genre=16 lang=Japanese offset=$offset")
        try {
            val response = api.browseMedia(
                mediaType        = mediaType,
                listType         = null,
                genreId          = 16,
                originalLanguage = "Japanese",
                limit            = pageSize,
                offset           = offset,
                sortBy           = "popularity"
            )
            Log.d(TAG, "✅ Anime ($mediaType) returned ${response.items.size} items (offset=$offset)")
            response
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading anime ($mediaType): ${e.message}", e)
            MediaBrowseResponse(
                groupSlug  = null,
                groupName  = null,
                mediaType  = mediaType,
                totalCount = 0,
                count      = 0,
                limit      = pageSize,
                offset     = offset,
                items      = emptyList()
            )
        }
    }
}
