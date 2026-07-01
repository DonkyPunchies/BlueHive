// repository/RecommendationsRepository.kt
package com.example.bluehive.repository

import android.util.Log
import com.example.bluehive.api.ApiClient
import com.example.bluehive.models.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class RecommendationsRepository {
    companion object {
        private const val TAG = "RecommendationsRepository"
    }

    private val api = ApiClient.trailerApi

    /**
     * Get recommendations for a specific TMDB id *and* media type.
     *
     * @param tmdbId TMDB id of the source media.
     * @param mediaType "movie" or "tv" – MUST match what the backend expects.
     */
    suspend fun getRecommendations(
        tmdbId: Int,
        mediaType: String
    ): List<MediaItem> = withContext(Dispatchers.IO) {



        // ✅ NORMAL BUILD: hit your backend API
        try {
            val response = api.getRecommendations(
                tmdbId = tmdbId,
                mediaType = mediaType,
                limit = 20
            )
            Log.d(
                TAG,
                "✅ Retrieved ${response.recommendations.size} recommendations for tmdb_id: $tmdbId ($mediaType)"
            )
            response.recommendations
        } catch (e: Exception) {
            Log.e(
                TAG,
                "❌ Error loading recommendations for $tmdbId ($mediaType): ${e.message}",
                e
            )
            emptyList()
        }
    }
}
