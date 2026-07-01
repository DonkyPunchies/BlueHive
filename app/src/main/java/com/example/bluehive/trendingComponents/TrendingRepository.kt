package com.example.bluehive.trendingComponents

import android.util.Log
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.TrendingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TrendingRepository {

    companion object {
        private const val TAG = "TrendingRepository"
    }

    private val api = ApiClient.trailerApi

    suspend fun getTrending(
        trendType: String = "day",   // "day" or "week"
        mediaType: String? = null,
        limit: Int = 40
    ): List<TrendingItem> = withContext(Dispatchers.IO) {
        try {
            val response = api.getTrending(trendType, mediaType, limit)
            Log.d(TAG, "✅ Retrieved ${response.items.size} trending/$trendType items")
            response.items
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching trending/$trendType", e)
            emptyList()
        }
    }
}