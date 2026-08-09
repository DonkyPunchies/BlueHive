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


/**
 * Result of a server-side extraction — see [BluehiveApiService.extractStream].
 *
 * `referer` is the EMBED PAGE origin (https://vidfast.vc/), not the manifest's
 * own host. The CDN validates it, and sending the manifest host instead 403s
 * every segment while extraction itself looks fine — the exact failure seen on
 * the first on-device test.
 *
 * `proxy_url` is the RELAY fallback, not the normal path. The player tries the
 * CDN directly and only switches to this after a confirmed IO failure, because
 * relaying pushes every video byte through the backend (~1 GB per episode each
 * way). It is what recovers an ISP-filtered line, where re-extraction cannot:
 * the source pins one CDN host per episode and hands back the same one.
 */
/**
 * One provider from [BluehiveApiService.extractServers].
 *
 * Names are only meaningful alongside the audio track they were read under —
 * see the endpoint doc.
 */
data class ExtractServerOption(
    val name:       String,
    val is_default: Boolean = false,
)

data class ExtractServersResponse(
    val audio:    String,
    val servers:  List<ExtractServerOption>,
    val page_url: String? = null,
    val cached:   Boolean = false,
)

data class ExtractResponse(
    val m3u8:         String,
    val referer:      String?,
    val user_agent:   String?,
    val subtitle_url: String?,
    val page_url:     String?,
    val proxy_url:    String?,
    val cached:       Boolean = false,
    val expires_in:   Int     = 0,
)



/**
 * Ask the backend to authorise ONE relayed playback.
 *
 * The relay refuses a bare X-API-Key: that key is a plaintext constant in this
 * APK, so on its own it made the proxy usable by anyone who unzipped the app.
 * A grant is signed server-side from the VERIFIED user token, pinned to this
 * device and to the stream's host, and expires.
 *
 * It rides in the URL rather than a header because the player builds its data
 * source once and then fetches segments for a whole episode — an access token
 * would expire long before the credits.
 */
data class RelayGrantRequest(
    val relay_url: String,
)

data class RelayGrantResponse(
    val relay_url:        String,
    val expires_in:       Int  = 0,
    val daily_used_bytes: Long = 0,
    val daily_cap_bytes:  Long = 0,
)


/** One (season, episode) the profile has started — drives the watched marker. */
data class WatchedEpisodeResponse(
    val season_number:  Int,
    val episode_number: Int,
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


data class ImdbIdResponse(
    val tmdb_id:    Int?    = null,
    val media_type: String? = null,
    val imdb_id:    String? = null,
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

    // PHASE 3 (device-table split): check-slot / end-session / background /
    // foreground MOVED to BluehiveApiService. Streaming-session state now lives in
    // bluehive.device_sessions, so those endpoints are served by bluehive-api.
    // They are deliberately NOT left here as dead declarations — this interface is
    // bound to PLATFORM_API_BASE_URL, so any surviving call would have gone on
    // hitting the identity backend, which stopped accepting BlueHive's
    // host-injected token (that is what silently broke is_device_active).
    // refreshToken + getMe stay: those ARE platform identity concerns.

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

// ── Device report payloads (see bluehive-api bluehive_plugin/reports.py) ─────

data class DeviceReportBody(
    val report_type: String,                 // 'crash' | 'diagnostic'
    val occurred_at_ms: Long,                // client clock at capture time
    val app_version_name: String?,
    val app_version_code: Int?,
    val device_model: String?,
    val android_sdk: Int?,
    val device_fingerprint: String?,         // fallback identity; server prefers its own device row
    val exception_class: String? = null,
    val exception_message: String? = null,
    val stack_trace: String? = null,
    val thread_name: String? = null,
    val log_lines: String? = null,
    val meta: Map<String, Any?> = emptyMap(),
)

data class DeviceReportAck(
    val ok: Boolean,
    val report_id: Long,
)



// ══════════════════════════════════════════════════════════════════════════════
// BlueHive User API  (hits bluehive-api on 8000 — Bearer token + X-API-Key)
// Profiles / watch-history / favorites. Moved here from PlatformApiService
// during the backend split. Same paths (/api/bluehive/*), different host.
// Routed through ApiClient.bluehiveApi (Bearer + silent refresh + X-API-Key).
// ══════════════════════════════════════════════════════════════════════════════
interface BluehiveApiService {

    // ── Streaming-session / device-slot (PHASE 3, moved off PlatformApiService) ──
    // These ride bluehiveHttpClient, which sends the host-supplied Bearer and
    // refreshes it over IPC via hostTokenAuthenticator — exactly the auth shape
    // bluehive-api expects. bluehive-api identifies the session from the JWT's
    // `device_id` claim (and scopes the cap by `ws`), so unlike the old platform
    // endpoints it needs no matching device_fingerprint row.
    //
    // NOTE: only checkSlot() currently has a caller (LockoutActivity). PHASE 2
    // removed the background/foreground/end-session pings in favour of the SSE
    // connect/disconnect lifecycle; they are declared here so that lifecycle can be
    // re-driven explicitly if the SSE-only approach proves too coarse.
    @POST("/api/android/check-slot")
    suspend fun checkSlot(): Response<CheckSlotResponse>

    @POST("/api/android/end-session")
    suspend fun endSession(): Response<SimpleOkResponse>

    /**
     * Server-side m3u8 extraction (VidFast).
     *
     * Replaces the on-device extractor, which cannot work on a filtered line:
     * `vidfast.vc` is refused to every client on the device (measured from the TV
     * 2026-08-02, 0/3 with Spectrum Shield on, 3/3 with it off) because the filter
     * reads the hostname from the TLS ClientHello and severs the handshake. The
     * backend loads the embed page where the filter isn't and returns just the
     * URL; the TV still streams from the CDN directly, so no video bytes cross
     * the backend.
     *
     * Safe to hand a manifest extracted elsewhere: CDN tokens are Referer-bound,
     * NOT IP-bound — verified by extracting through a VPN and then playing 105s
     * with the tunnel down and zero load errors.
     *
     * Cached server-side per (source, type, tmdb, season, episode), so every
     * device asking for the same episode gets the same answer without re-running
     * a browser. Pass refresh=1 to force a fresh extraction when a token dies
     * mid-play.
     */
    @GET("/api/extract")
    suspend fun extractStream(
        @Query("type")     type:     String,          // "tv" | "movie"
        @Query("tmdb_id")  tmdbId:   Int,
        @Query("season")   season:   Int? = null,
        @Query("episode")  episode:  Int? = null,
        @Query("source")   source:   String = "vidfast",
        @Query("refresh")  refresh:  Int    = 0,
        // Relay accounting only — these decorate proxy_url so a fallback to the
        // relay lands in bluehive.relay_sessions as a named title rather than an
        // anonymous byte count. They are NOT part of the server's cache key, so
        // omitting them changes nothing about playback.
        @Query("mtype")    mtype:    String? = null,   // anime_tv | anime_movie
        @Query("title")    title:    String? = null,
        @Query("profile")  profile:  Int?    = null,
        @Query("device")   device:   String? = null,
        // ── Miruro only ──────────────────────────────────────────────────────
        // Unlike the accounting params above, these ARE part of the server's
        // cache key: sub and dub are different streams from one URL, as is each
        // provider. Sharing a key would serve whichever was extracted first to
        // everyone — a dub viewer silently getting sub audio, which looks like
        // a working stream rather than a bug.
        @Query("audio")    audio:    String? = null,   // "sub" | "dub"
        @Query("server")   server:   String? = null,   // provider name
    ): Response<ExtractResponse>

    /**
     * Provider list for one title+track — the backend equivalent of the old
     * on-device ENUMERATE pass.
     *
     * AUDIO IS REQUIRED, not optional. Miruro's provider list is audio-scoped
     * (sub offers `kiwidlh-sub`/`allydlh-sub`/…, dub offers `kiwidl`/`allydl`/…),
     * so a list fetched under the wrong track names servers that do not exist on
     * the one about to play.
     */
    @GET("/api/extract/servers")
    suspend fun extractServers(
        @Query("type")     type:     String,          // "tv" | "movie"
        @Query("tmdb_id")  tmdbId:   Int,
        @Query("season")   season:   Int? = null,
        @Query("episode")  episode:  Int? = null,
        @Query("audio")    audio:    String = "sub",
        @Query("source")   source:   String = "miruro",
        @Query("device")   device:   String? = null,
    ): Response<ExtractServersResponse>

    @POST("/api/hls/grant")
    suspend fun mintRelayGrant(
        @Body body: RelayGrantRequest,
    ): Response<RelayGrantResponse>

    @POST("/api/android/background")
    suspend fun background(): Response<SimpleOkResponse>

    @POST("/api/android/foreground")
    suspend fun foreground(): Response<SimpleOkResponse>

    // Media endpoint served by the same backend (TMDB_Api_Server). Lives on this
    // interface so it rides the proven bluehiveApi client (hardcoded base URL +
    // X-API-Key) rather than trailerApi's BuildConfig URL. Resolves a tmdb id to
    // its imdb_id (TMDB external_ids, server-side) for subtitle lookups.
    @GET("/api/media/{tmdb_id}/imdb")
    suspend fun getMediaImdb(
        @Path("tmdb_id")     tmdbId:    Int,
        @Query("media_type") mediaType: String,
    ): ImdbIdResponse

    @GET("/api/bluehive/profiles/{profileId}/watch-history")
    suspend fun getWatchHistory(
        @Path("profileId") profileId: Int,
        @Query("limit")  limit:  Int = 50,
        @Query("offset") offset: Int = 0,
    ): List<WatchHistoryResponse>

    // Watched-episode markers for ONE show. Scoped by tmdb_id so the episode
    // grid gets the COMPLETE set — getWatchHistory above is paged over the whole
    // profile, so filtering it client-side drops episodes past the page size.
    @GET("/api/bluehive/profiles/{profileId}/watched-episodes")
    suspend fun getWatchedEpisodes(
        @Path("profileId") profileId: Int,
        @Query("tmdb_id")  tmdbId:    Int,
    ): List<WatchedEpisodeResponse>

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

    /**
     * Crash / diagnostic report ingest — hits bluehive-api (:8000), which
     * writes bluehive.device_reports. Auth is the bluehiveApi pair (Bearer JWT
     * + X-API-Key); the server reads user_id/device_id as opaque ints from the
     * JWT and takes the device_fingerprint from the body. This lives on
     * bluehiveApi (NOT platformApi) on purpose: BlueHive's telemetry stays in
     * BlueHive's backend, never the shared platform DB. Sent by CrashReporter
     * ('crash', launch after a crash) and Send Logs ('diagnostic').
     */
    @POST("/api/bluehive/reports")
    suspend fun submitDeviceReport(
        @Body body: DeviceReportBody,
    ): Response<DeviceReportAck>
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
