// repository/EpisodesRepository.kt
package com.example.bluehive.repository

import android.util.Log
import com.example.bluehive.api.ApiClient
import com.example.bluehive.models.SeasonEpisodesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EpisodesRepository {
    companion object {
        private const val TAG = "EpisodesRepository"
    }

    private val api = ApiClient.trailerApi   // or a dedicated tvApi if you have one

    suspend fun getSeasonEpisodes(
        tmdbId: Int,
        seasonNumber: Int
    ): SeasonEpisodesResponse = withContext(Dispatchers.IO) {
        try {
            val response = api.getSeasonEpisodes(tmdbId, seasonNumber)
            Log.d(TAG, "✅ Retrieved ${response.episodes.size} episodes for $tmdbId S$seasonNumber")
            response
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading episodes for $tmdbId S$seasonNumber: ${e.message}", e)
            // decide if you want to throw or return empty
            throw e
        }
    }
}
