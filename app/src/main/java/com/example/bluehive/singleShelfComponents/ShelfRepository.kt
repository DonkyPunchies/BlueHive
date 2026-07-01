package com.example.bluehive.singleShelfComponents

import android.util.Log
import com.example.bluehive.models.MediaBrowseResponse
import com.example.bluehive.repository.MediaRecyclerViewData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Centralized data loading for all media types and shelves
 */
class ShelfRepository(
    private val repo: MediaRecyclerViewData = MediaRecyclerViewData()
) {

    companion object {
        private const val TAG = "HomeSingleShelfRepository"
    }

    /**
     * Load a page of content for any media type and shelf combination
     */
    suspend fun loadPage(
        mediaType: MediaType,
        shelf: ContentShelf,
        page: Int,
        pageSize: Int,
        profileId: Int = -1
    ): MediaBrowseResponse = withContext(Dispatchers.IO) {
        Log.d(TAG, "📡 Loading: $mediaType / $shelf / page $page")

        when (mediaType) {
            MediaType.MOVIES   -> loadMoviesPage(shelf, page, pageSize, profileId)
            MediaType.TV_SHOWS -> loadTVShowsPage(shelf, page, pageSize, profileId)
            MediaType.ANIME    -> loadAnimePage(shelf, page, pageSize)
        }
    }

    /**
     * Load movies for a specific shelf
     */
    private suspend fun loadMoviesPage(
        shelf: ContentShelf,
        page: Int,
        pageSize: Int,
        profileId: Int = -1
    ): MediaBrowseResponse {
        // Convert -1 sentinel to null so the API omits the query param entirely.
        val resolvedProfileId = profileId.takeIf { it > 0 }
        return when (shelf) {
            ContentShelf.POPULAR     -> repo.loadPopularMovies(page, pageSize)
            ContentShelf.TOP_RATED   -> repo.loadTopRatedMovies(page, pageSize)
            ContentShelf.NETFLIX     -> repo.loadNetflixMovies(page, pageSize, resolvedProfileId)
            ContentShelf.PRIME_VIDEO -> repo.loadPrimeVideoMovies(page, pageSize)
            ContentShelf.DISNEY_PLUS -> repo.loadDisneyPlusMovies(page, pageSize)
            ContentShelf.HULU        -> repo.loadHuluMovies(page, pageSize)
            ContentShelf.PARAMOUNT_PLUS -> repo.loadParamountPlusMovies(page, pageSize)
            ContentShelf.APPLE_TV_PLUS  -> repo.loadAppleTVPlusMovies(page, pageSize)
            ContentShelf.HBO_MAX     -> repo.loadHboMaxMovies(page, pageSize)
            ContentShelf.PEACOCK     -> repo.loadPeacockMovies(page, pageSize)
            ContentShelf.ANIME_SERIES,
            ContentShelf.ANIME_MOVIES,
            ContentShelf.ANIME_NEW_SEASON -> MediaBrowseResponse(
                groupSlug = null, groupName = null, mediaType = "movie",
                totalCount = 0, count = 0, limit = pageSize,
                offset = page * pageSize, items = emptyList()
            )
        }
    }

    /**
     * Load TV shows for a specific shelf
     */
    private suspend fun loadTVShowsPage(
        shelf: ContentShelf,
        page: Int,
        pageSize: Int,
        profileId: Int = -1
    ): MediaBrowseResponse {
        val resolvedProfileId = profileId.takeIf { it > 0 }
        return when (shelf) {
            ContentShelf.POPULAR     -> repo.loadPopularTVShows(page, pageSize)
            ContentShelf.TOP_RATED   -> repo.loadTopRatedTVShows(page, pageSize)
            ContentShelf.NETFLIX     -> repo.loadNetflixTVShows(page, pageSize, resolvedProfileId)
            ContentShelf.PRIME_VIDEO -> repo.loadPrimeVideoTVShows(page, pageSize)
            ContentShelf.DISNEY_PLUS -> repo.loadDisneyPlusTVShows(page, pageSize)
            ContentShelf.HULU        -> repo.loadHuluTVShows(page, pageSize)
            ContentShelf.PARAMOUNT_PLUS -> repo.loadParamountPlusTVShows(page, pageSize)
            ContentShelf.APPLE_TV_PLUS  -> repo.loadAppleTVPlusTVShows(page, pageSize)
            ContentShelf.HBO_MAX     -> repo.loadHBOMaxTVShows(page, pageSize)
            ContentShelf.PEACOCK     -> repo.loadPeacockTVShows(page, pageSize)
            ContentShelf.ANIME_SERIES,
            ContentShelf.ANIME_MOVIES,
            ContentShelf.ANIME_NEW_SEASON -> MediaBrowseResponse(
                groupSlug = null, groupName = null, mediaType = "tv",
                totalCount = 0, count = 0, limit = pageSize,
                offset = page * pageSize, items = emptyList()
            )
        }
    }

    /**
     * Load anime for a specific shelf.
     * ANIME_SERIES → popular animated TV series (Animation genre, English language).
     * ANIME_MOVIES → popular animated movies  (Animation genre, English language).
     */
    private suspend fun loadAnimePage(
        shelf: ContentShelf,
        page: Int,
        pageSize: Int
    ): MediaBrowseResponse {
        return when (shelf) {
            ContentShelf.ANIME_SERIES -> repo.loadPopularAnimeSeries(page, pageSize)
            ContentShelf.ANIME_MOVIES -> repo.loadPopularAnimeMovies(page, pageSize)
            ContentShelf.ANIME_NEW_SEASON -> repo.loadNewThisSeasonAnime(page, pageSize)
            else -> {
                Log.w(TAG, "⚠️ Unknown anime shelf $shelf, returning empty")
                MediaBrowseResponse(
                    groupSlug = null,
                    groupName = null,
                    mediaType = "anime",
                    totalCount = 0,
                    count = 0,
                    limit = pageSize,
                    offset = page * pageSize,
                    items = emptyList()
                )
            }
        }
    }



    /**
     * Get the display title for a shelf based on media type
     */
    fun getShelfTitle(mediaType: MediaType, shelf: ContentShelf): String {
        // Special cases where title differs by media type
        if (shelf == ContentShelf.POPULAR) {
            return when (mediaType) {
                MediaType.MOVIES -> "What's Popular"
                MediaType.TV_SHOWS -> "Popular TV Shows"
                MediaType.ANIME -> "Popular Anime"
            }
        }

        if (shelf == ContentShelf.TOP_RATED) {
            return when (mediaType) {
                MediaType.MOVIES -> "Top Rated"
                MediaType.TV_SHOWS -> "Top Rated TV"
                MediaType.ANIME -> "Top Rated Anime"
            }
        }

        // Streaming services use same name for all media types
        return when (shelf) {
            ContentShelf.NETFLIX -> "Netflix"
            ContentShelf.PRIME_VIDEO -> "Prime Video"
            ContentShelf.DISNEY_PLUS -> "Disney+"
            ContentShelf.HULU -> "Hulu"
            ContentShelf.PARAMOUNT_PLUS -> "Paramount+"
            ContentShelf.APPLE_TV_PLUS -> "Apple TV+"
            ContentShelf.HBO_MAX -> "HBO Max"
            ContentShelf.PEACOCK -> "Peacock"
            ContentShelf.ANIME_SERIES -> "Popular Series"
            ContentShelf.ANIME_MOVIES -> "Popular Movies"
            ContentShelf.ANIME_NEW_SEASON -> "New This Season"
            else -> shelf.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}