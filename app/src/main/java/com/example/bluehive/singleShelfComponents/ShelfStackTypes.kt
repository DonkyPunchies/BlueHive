package com.example.bluehive.singleShelfComponents


import com.example.bluehive.models.MediaItem

/**
 * Defines the type of media content being displayed
 */
enum class MediaType {
    MOVIES,
    TV_SHOWS,
    ANIME,
}

/**
 * Defines the streaming service or category shelf
 * Shared across all media types
 */
enum class ContentShelf {
    POPULAR,
    TOP_RATED,
    NETFLIX,
    PRIME_VIDEO,
    DISNEY_PLUS,
    HULU,
    PARAMOUNT_PLUS,
    APPLE_TV_PLUS,
    HBO_MAX,
    PEACOCK,
    ANIME_SERIES,
    ANIME_MOVIES,
    ANIME_NEW_SEASON,
}

/**
 * Tracks exactly where focus should be when returning to a shelf
 */
data class ShelfFocusMemory(
    var lastFocusedTmdbId: Int = -1,
    var lastListIndex: Int = 0,
    var lastAbsoluteIndex: Int = 0,
    var lastPageNumber: Int = 0,
    var isValid: Boolean = false
) {
    fun save(tmdbId: Int, listIndex: Int, absoluteIndex: Int, pageNumber: Int) {
        lastFocusedTmdbId = tmdbId
        lastListIndex = listIndex
        lastAbsoluteIndex = absoluteIndex
        lastPageNumber = pageNumber
        isValid = true
        android.util.Log.d("FOCUS_MEMORY", "💾 Saved: tmdb=$tmdbId, listIdx=$listIndex, abs=$absoluteIndex, page=$pageNumber")
    }

    fun clear() {
        isValid = false
        android.util.Log.d("FOCUS_MEMORY", "🗑️ Cleared memory")
    }
}

/**
 * Manages a sliding window of pages in memory
 */
data class PageWindow(
    val loadedPages: MutableMap<Int, List<MediaItem>> = mutableMapOf(),
    var windowStartPage: Int = 0,
    var windowEndPage: Int = 0,
    var focusedTmdbId: Int? = null,
    var pageNumberIncrement: Int = 0
) {
    fun getAllItems(): List<MediaItem> {
        val seen = HashSet<Int>()
        val result = mutableListOf<MediaItem>()
        for (page in windowStartPage..windowEndPage) {
            val pageItems = loadedPages[page] ?: continue
            for (item in pageItems) {
                if (seen.add(item.tmdbId)) {   // add() == false means already present → skip
                    result.add(item)
                }
            }
        }
        return result
    }

    fun getRelativeIndex(absoluteIndex: Int, pageSize: Int): Pair<Int, Int> {
        val pageNumber = absoluteIndex / pageSize
        val indexInPage = absoluteIndex % pageSize
        return Pair(pageNumber, indexInPage)
    }

    fun getPageCount(): Int = windowEndPage - windowStartPage + 1
}