package com.example.bluehive.repository

import com.example.bluehive.api.ApiClient
import com.example.bluehive.models.LatestTrailer
import com.example.bluehive.models.TrailerResponse
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

class TrailerRepository {
    private var currentJob: Job? = null

    fun cancelAllRequests() {
        currentJob?.cancel()
        currentJob = null
    }
    companion object {
        private const val TAG = "TrailerRepository"
        private const val DEFAULT_PAGE_SIZE = 50
    }

    private val api = ApiClient.trailerApi

    suspend fun getLatestTrailers(
        limit: Int = 100,
        offset: Int = 0,
        sourceTab: String? = null
    ): List<LatestTrailer> = withContext(Dispatchers.IO) {
        try {
            val response: TrailerResponse = api.getLatestTrailers(limit, offset, sourceTab)
            Log.d(TAG, "Retrieved ${response.trailers.size} of ${response.totalCount} trailers")
            response.trailers
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching trailers from API", e)
            emptyList()
        }
    }



}