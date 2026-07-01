// api/ApiService.kt
package com.example.bluehive.api

import com.example.bluehive.models.GenreDto
import com.example.bluehive.models.LatestTrailer
import com.example.bluehive.models.TrailerResponse
import com.example.bluehive.models.MediaBrowseResponse
import com.example.bluehive.models.RecommendationResponse
import com.example.bluehive.models.SeasonEpisodesResponse
import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query


// ══════════════════════════════════════════════════════════════════════════════
// TMDB / Media API  (hits TMDB_Api_Server — X-API-Key auth, no user tokens)
// ══════════════════════════════════════════════════════════════════════════════

interface ApiService {

    @GET("/api/latest-trailers")
    suspend fun getLatestTrailers(
        @Query("limit")      limit:     Int     = 50,
        @Query("offset")     offset:    Int     = 0,
        @Query("source_tab") sourceTab: String? = null
    ): TrailerResponse
    @GET("/api/trailer/{dataId}")
    suspend fun getTrailerById(
        @Path("dataId") dataId: String
    ): LatestTrailer

    @GET("api/media/browse")
    suspend fun browseMedia(
        @Query("media_type")        mediaType:        String?  = null,
        @Query("list_type")         listType:         String?  = null,
        @Query("genre_id")          genreId:          Int?     = null,
        @Query("year")              year:             Int?     = null,
        @Query("limit")             limit:            Int,
        @Query("offset")            offset:           Int,
        @Query("sort_by")           sortBy:           String,
        @Query("watch_provider")    watchProvider:    Int?     = null,
        @Query("original_language") originalLanguage: String?  = null,
        @Query("released_after")    releasedAfter:    String?  = null
    ): MediaBrowseResponse

    @GET("/api/provider-groups/{groupSlug}/media")
    suspend fun getMediaByProviderGroup(
        @Path("groupSlug")   groupSlug: String,
        @Query("media_type") mediaType: String? = null,
        @Query("limit")      limit:     Int     = 20,
        @Query("offset")     offset:    Int     = 0,
        @Query("sort_by")    sortBy:    String? = null,
        @Query("profile_id") profileId: Int?    = null
    ): MediaBrowseResponse

    @GET("/api/media/{tmdb_id}/recommendations")
    suspend fun getRecommendations(
        @Path("tmdb_id")     tmdbId:    Int,
        @Query("media_type") mediaType: String,
        @Query("limit")      limit:     Int = 20
    ): RecommendationResponse

    @GET("/api/tv/{tmdb_id}/seasons/{season_number}/episodes")
    suspend fun getSeasonEpisodes(
        @Path("tmdb_id")       tmdbId:       Int,
        @Path("season_number") seasonNumber: Int
    ): SeasonEpisodesResponse

    @GET("/api/stats")
    suspend fun getStats(): Map<String, Any>

    @GET("/health")
    suspend fun checkHealth(): Map<String, String>

    @GET("/api/media/{tmdb_id}")
    suspend fun getMediaDetails(
        @Path("tmdb_id")     tmdbId:    Int,
        @Query("media_type") mediaType: String,
    ): MediaDetailResponse

    @GET("/api/media/search")
    suspend fun searchMedia(
        @Query("q")          query:     String,
        @Query("media_type") mediaType: String,
        @Query("limit")      limit:     Int = 20
    ): MediaBrowseResponse


    @GET("/api/trending")
    suspend fun getTrending(
        @Query("trend_type")  trendType:  String = "day",
        @Query("media_type")  mediaType:  String? = null,
        @Query("limit")       limit:      Int = 40
    ): TrendingResponse


    @GET("/api/genres")
    suspend fun getGenres(): List<GenreDto>


    @GET("/api/anime/resolve")
    suspend fun resolveAnime(
        @Query("tmdb_id")    tmdbId:    Int,
        @Query("media_type") mediaType: String,
        @Query("season")     season:    Int? = null,
        @Query("episode")    episode:   Int? = null,
    ): AnimeResolveResponse

}

// ══════════════════════════════════════════════════════════════════════════════
// Platform API  (hits platform_accounts FastAPI — Bearer token auth)
// All endpoints here require a valid access token.
// The TokenAuthenticator in ApiClient handles silent refresh on 401.
// ══════════════════════════════════════════════════════════════════════════════

// ── Request bodies ────────────────────────────────────────────────────────────

data class AndroidRefreshRequest(
    val refresh_token:      String,
    val device_fingerprint: String,
)

data class DeviceRenameRequest(
    val device_name: String,
)

data class ProfileCreateRequest(
    val display_name: String,
    val avatar_url:   String?,
    val profile_type: String = "standard",
)

// ── Response bodies ───────────────────────────────────────────────────────────

data class AndroidRefreshResponse(
    val ok:            Boolean,
    val access_token:  String,
    val refresh_token: String,
)

data class MeResponse(
    val email:          String,
    val full_name:      String,
    val user_id:        String,
    val password_length: Int?,
)

data class DeviceResponse(
    val id:                 Int,
    val slot:               Int,
    val device_name:        String,
    val device_fingerprint: String,
    val paired_at:          String?,
    val last_seen_at:       String?,
)

data class ProfileUpdateRequest(
    val display_name: String?,
    val avatar_url:   String?,
    val profile_type: String? = null,
)

data class CheckSlotResponse(val ok: Boolean)

data class SimpleOkResponse(val ok: Boolean)

data class ProfileResponse(
    val id:                   Int,
    val display_name:         String,
    val avatar_url:           String?,
    val slot:                 Int,
    val has_pin:              Boolean,
    val profile_type:         String = "standard",
    val created_by_device_id: Int?,
    val created_at:           String,
    val updated_at:           String,
    val last_login_at:        String? = null,
)

data class DeletedProfileResponse(
    val id:           Int,
    val display_name: String,
    val avatar_url:   String?,
    val deleted_at:   String,
)


data class WatchHistoryRequest(
    val profile_id:     Int,
    val media_tmdb_id:  Int,
    val media_type:     String,
    val source_name:    String,
    val media_title:    String? = null,
    val season_number:  Int?    = null,
    val episode_number: Int?    = null,
    val episode_name:   String? = null,
)

data class WatchHistoryResponse(
    val id:              Int,
    val profile_id:      Int,
    val media_tmdb_id:   Int,
    val media_type:      String,
    val source_name:     String,
    val media_title:     String?,
    val image_url:       String?,
    val watched_at:      String,
    val season_number:   Int?,
    val episode_number:  Int?,
    val episode_name:    String?,
    val stopped_at:      String? = null,
    val progress_seconds: Int,
    val genres:          String? = null,
    val media_release_date:  String? = null,
)


data class MediaDetailResponse(
    val tmdb_id:            Int,
    val media_type:         String,
    val title:              String?,
    val backdrop_path:      String?,
    val poster_path:        String?,
    val logos:              Any?,
    val overview:           String?,
    val vote_average:       Double?,
    val content_rating:     String?,
    val original_language:  String?,
    val release_date:       String?,
    val genres:             Any?,
    val number_of_seasons:  Int?,
    val runtime:            Int?,
    val status:             String?,
    val youtube_trailer_url: String?,
    val budget:             Long?,
    val revenue:            Long?,
)


data class FavoriteRequest(
    val profile_id:    Int,
    val media_tmdb_id: Int,
    val media_type:    String,
    val media_title:   String? = null,
)

data class FavoriteResponse(
    val id:            Int,
    val profile_id:    Int,
    val media_tmdb_id: Int,
    val media_type:    String,
    val media_title:   String?,
    val added_at:      String,
)


data class FavoriteStatusResponse(
    val is_favorited: Boolean,
)



// ── Interface ─────────────────────────────────────────────────────────────────

interface PlatformApiService {

    @POST("/api/android/refresh")
    suspend fun refreshToken(
        @Body body: AndroidRefreshRequest,
    ): Response<AndroidRefreshResponse>

    @POST("/api/android/check-slot")
    suspend fun checkSlot(): Response<CheckSlotResponse>

    @POST("/api/android/end-session")
    suspend fun endSession(): Response<SimpleOkResponse>

    @POST("/api/android/background")
    suspend fun background(): Response<SimpleOkResponse>

    @POST("/api/android/foreground")
    suspend fun foreground(): Response<SimpleOkResponse>

    @GET("/api/me")
    suspend fun getMe(): MeResponse

    @GET("/api/me/devices")
    suspend fun getDevices(): List<DeviceResponse>

    @PATCH("/api/me/devices/{deviceId}/rename")
    suspend fun renameDevice(
        @Path("deviceId") deviceId: Int,
        @Body body: DeviceRenameRequest,
    ): SimpleOkResponse

    @DELETE("/api/me/devices/{deviceId}")
    suspend fun removeDevice(
        @Path("deviceId") deviceId: Int,
    ): SimpleOkResponse
}



// ══════════════════════════════════════════════════════════════════════════════
// BlueHive User API  (hits bluehive-api on 8000 — Bearer token + X-API-Key)
// Profiles / watch-history / favorites. Moved here from PlatformApiService
// during the backend split. Same paths (/api/bluehive/*), different host.
// Routed through ApiClient.bluehiveApi (Bearer + silent refresh + X-API-Key).
// ══════════════════════════════════════════════════════════════════════════════
interface BluehiveApiService {

    @GET("/api/bluehive/profiles/{profileId}/watch-history")
    suspend fun getWatchHistory(
        @Path("profileId") profileId: Int,
        @Query("limit")  limit:  Int = 50,
        @Query("offset") offset: Int = 0,
    ): List<WatchHistoryResponse>

    @POST("/api/bluehive/watch-history")
    suspend fun logWatchHistory(
        @Body body: WatchHistoryRequest,
    ): WatchHistoryResponse

    @POST("/api/bluehive/watch-history/{watchId}/stop")
    suspend fun stopWatchHistory(
        @Path("watchId") watchId: Int,
    ): WatchHistoryResponse

    @POST("/api/bluehive/favorites/toggle")
    suspend fun toggleFavorite(
        @Body body: FavoriteRequest,
    ): FavoriteStatusResponse

    @GET("/api/bluehive/favorites/check")
    suspend fun checkFavorite(
        @Query("profile_id")    profileId:   Int,
        @Query("media_tmdb_id") mediaTmdbId: Int,
        @Query("media_type")    mediaType:   String,
    ): FavoriteStatusResponse

    @GET("/api/bluehive/profiles/{profileId}/favorites")
    suspend fun getFavorites(
        @Path("profileId") profileId: Int,
    ): List<FavoriteResponse>

    @GET("/api/bluehive/profiles")
    suspend fun listProfiles(): List<ProfileResponse>

    @POST("/api/bluehive/profiles")
    suspend fun createProfile(
        @Body body: ProfileCreateRequest,
    ): ProfileResponse

    @POST("/api/bluehive/profiles/{profileId}/select")
    suspend fun selectProfile(
        @Path("profileId") profileId: Int,
    ): ProfileResponse

    @DELETE("/api/bluehive/profiles/{profileId}")
    suspend fun deleteProfile(
        @Path("profileId") profileId: Int,
    ): SimpleOkResponse

    @GET("/api/bluehive/profiles/deleted")
    suspend fun listDeletedProfiles(): List<DeletedProfileResponse>

    @POST("/api/bluehive/profiles/{profileId}/recover")
    suspend fun recoverProfile(
        @Path("profileId") profileId: Int,
    ): ProfileResponse

    @PATCH("/api/bluehive/profiles/{profileId}")
    suspend fun updateProfile(
        @Path("profileId") profileId: Int,
        @Body body: ProfileUpdateRequest,
    ): ProfileResponse
}



data class TrendingItem(
    @SerializedName("trending_id")        val trendingId:       Int,
    @SerializedName("tmdb_id")            val tmdbId:           Int,
    @SerializedName("media_type")         val mediaType:        String,
    @SerializedName("trend_type")         val trendType:        String,
    @SerializedName("trend_rank")         val trendRank:        Int,
    @SerializedName("trend_date")         val trendDate:        String?,
    val title:                                                   String,
    @SerializedName("poster_path")        val posterPath:       String?,
    @SerializedName("backdrop_path")      val backdropPath:     String?,
    val logos:                                                   String?,
    val overview:                                                String?,
    @SerializedName("release_date")       val releaseDate:      String?,
    @SerializedName("vote_average")       val voteAverage:      Double?,
    @SerializedName("vote_count")         val voteCount:        Int?,
    val popularity:                                              Double?,
    val runtime:                                                 Int?,
    val status:                                                  String?,
    @SerializedName("content_rating")     val contentRating:    String?,
    @SerializedName("original_language")  val originalLanguage: String?,
    val budget:                                                  Long?,
    val revenue:                                                 Long?,
    @SerializedName("number_of_seasons")  val numberOfSeasons:  Int?,
    @SerializedName("number_of_episodes") val numberOfEpisodes: Int?,
    @SerializedName("youtube_trailer_url") val trailerUrl:      String?,
    val genres:                                                  List<String>?,
)

data class TrendingResponse(
    @SerializedName("trend_type")  val trendType:  String,
    @SerializedName("media_type")  val mediaType:  String?,
    val count:                                      Int,
    val items:                                      List<TrendingItem>
)
