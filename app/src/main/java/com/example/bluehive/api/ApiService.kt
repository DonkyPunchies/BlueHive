// api/ApiService.kt
package com.example.bluehive.api

import com.example.bluehive.models.HostEntitlementRequest
import com.example.bluehive.models.BhAuthResponse
import com.example.bluehive.models.BhLoginRequest
import com.example.bluehive.models.BhRefreshRequest
import com.example.bluehive.models.BhHostProvisionRequest
import com.example.bluehive.models.BhSignupRequest
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

    /**
     * Details for many titles in ONE request.
     *
     * `ids` is a comma-separated list of `<tmdb_id>:<media_type>` pairs.
     *
     * WHY THIS EXISTS. Favourites fetched getMediaDetails once per entry and
     * awaited them all. MEASURED 2026-09-04 with 131 favourites: the server
     * answered each in ~3.5ms, but OkHttp caps concurrent requests to one host
     * at 5 (this app sets no Dispatcher, so the default applies), making ~27
     * sequential round trips to render one screen. The same 131 titles come
     * back from this endpoint in a single call in ~20ms.
     *
     * NEITHER ORDER NOR COMPLETENESS IS GUARANTEED. Match results back by
     * (tmdb_id, media_type); an id the server knows nothing about is simply
     * absent, exactly as it would have 404'd individually.
     *
     * The server caps a single call at 300 pairs — see MEDIA_BATCH_MAX. Callers
     * with more must chunk.
     */
    @GET("/api/media/batch")
    suspend fun getMediaBatch(
        @Query("ids") ids: String,
    ): List<MediaDetailResponse>

    // Type-ahead for the honeycomb search, called as each key is pressed. Unlike
    // /api/media/search it answers PARTIAL words ("bla" -> Black Clover), folds
    // accents ("pokemon" -> Pokémon), and covers movies and TV in one request.
    @GET("/api/media/suggest")
    suspend fun suggestMedia(
        @Query("q")     query: String,
        @Query("limit") limit: Int = 40
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

/**
 * Playback position in whole seconds, for the progress heartbeat and for closing
 * a watch session. Seconds, not millis — this feeds `progress_seconds`, and the
 * server clamps it to a sane session length.
 */
data class WatchProgressRequest(
    val position_seconds: Int,
    // Length of what is playing, so a TV locked out by this one can say when it
    // ends. Null (left out of the JSON) when the player does not know it.
    val duration_seconds: Int? = null,
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

/**
 * Reply to POST /api/android/heartbeat.
 *
 * intervalSeconds is the SERVER'S choice of cadence, echoed on every beat so it
 * can be retuned from the backend .env without shipping an app release. Nullable
 * because an older backend answers a bare {"ok": true} — the client keeps its
 * own default in that case rather than treating a missing field as zero.
 */
data class HeartbeatResponse(
    val ok: Boolean,
    @SerializedName("interval_seconds") val intervalSeconds: Int? = null,
)

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
    // The SUBSCRIPTION seat of the account that owns this profile: "owner",
    // "member", or null when that account has no seat (a sideload) or has not
    // reported one. Only /profiles/household fills it in. Drives the frame
    // colour on the picker, never what the profile may be used for.
    val seat:                 String? = null,
    // Which ACCOUNT on the subscription made this profile: 0 is the owner,
    // members follow in the order they joined. The same number on every TV in
    // the household, so the same person's profiles get the same colour dot
    // everywhere. Null outside /profiles/household.
    val account_key:          Int?    = null,
)

data class DeletedProfileResponse(
    val id:           Int,
    val display_name: String,
    val avatar_url:   String?,
    val deleted_at:   String,
)

/**
 * Whether this TV may create another profile, and which limit stops it.
 *
 * TWO LIMITS APPLY AND THEY ARE NOT THE SAME NUMBER. `device_limit` is how many
 * profiles THIS paired device may create; `account_limit` is the ceiling across
 * every device on the account. An admin seat pairs up to four devices, a member
 * seat one, so the same per-device number yields a different account total for
 * each — which is exactly why the server sends both instead of the client
 * hardcoding either.
 *
 * `can_create` already combines them. Branch on it; use the counts only to
 * explain WHY, because the remedy differs: an exhausted account needs a profile
 * deleted, an exhausted device needs a different TV.
 */
/**
 * Every profile on the subscription, split by what this account may do with it.
 *
 * TWO LISTS, NOT ONE FLAGGED LIST. `own` can be entered, renamed, re-avatared
 * and deleted; `others` belong to another account on the same licence and can
 * only be renamed. Keeping them apart means the UI cannot forget to check a
 * boolean and offer the wrong thing — the affordances differ, so the shapes do.
 */
data class HouseholdProfilesResponse(
    val own:    List<ProfileResponse>,
    val others: List<ProfileResponse>,
)

data class ProfileQuotaResponse(
    val account_limit: Int,
    val account_used:  Int,
    val device_limit:  Int,
    val device_used:   Int,
    val can_create:    Boolean,
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
data class CdnHostsResponse(
    val hosts: List<String> = emptyList(),
    val count: Int = 0,
    /**
     * Hosts the backend has PROVEN cannot speak h3, and which must therefore
     * never be given a QUIC hint.
     *
     * Distinct from simple absence, and that distinction is why this field
     * exists: mergeQuicHints merges and never removes, deliberately, so a device
     * keeps hosts the backend has not met. Dropping a host from `hosts` alone
     * would therefore evict nothing. This is positive evidence, so it evicts.
     */
    @SerializedName("h2_only") val h2Only: List<String> = emptyList(),
)

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
    /**
     * Every host this playlist points at, manifest and segments alike.
     *
     * The client cannot pre-warm DNS/ECH for a host it has never heard of, and
     * /api/cdn-hosts is fetched only at app start. A segment host first seen
     * during THIS extraction is therefore unknown to the player about to use it,
     * so it resolves inside the playback critical path, loses the race for its
     * ECH config, goes out in cleartext and gets severed — one relay.
     *
     * MEASURED 2026-08-30 on "Men": paperorbit.top failed exactly that way while
     * cedarforge.top, proudotter.top and moon.peakstorm.top — all pre-warmed at
     * startup — reached h3 200 in the same playback.
     *
     * Null or empty on older backends and on cache entries written before the
     * field existed, which simply means no warm.
     */
    /**
     * Is this manifest a PLAYLIST (HLS) or a whole file?
     *
     * The player otherwise guesses from the URL, which fails on any source whose
     * media URL carries no extension. MEASURED 2026-08-30: VidLove serves HLS
     * from https://a2.shows.st/api?d=... — no ".m3u8" anywhere — so the guess
     * picked progressive, the MP4 extractors were handed a playlist, and playback
     * died with UnrecognizedInputFormatException / NoDeclaredBrand.
     *
     * Nullable on purpose: it is a HINT, not a mandate. Where it is absent (an
     * on-device extractor, a mid-play re-extraction) the player falls back to the
     * URL heuristic exactly as before.
     */
    @SerializedName("is_hls") val isHls: Boolean? = null,
    @SerializedName("cdn_hosts") val cdnHosts: List<String>? = null,
    val m3u8:         String,
    val referer:      String?,
    val user_agent:   String?,
    val subtitle_url: String?,
    val page_url:     String?,
    val proxy_url:    String?,
    val cached:       Boolean = false,
    val expires_in:   Int     = 0,
    /**
     * The track this extraction ACTUALLY produced — "sub" | "dub".
     *
     * Without it a dub request that the site quietly served as sub was
     * undetectable: the response carried a perfectly good manifest and the app
     * played it, labelled it "Miruro (Dub)" in watch history, and the user got
     * Japanese audio with nothing anywhere saying why. The on-device extractor
     * has always reported this (RESULT_DUB_FALLBACK) and fails over on it; the
     * backend path — which now serves EVERY anime play — had no equivalent, so
     * that failover was dead code.
     *
     * Nullable ON PURPOSE. A backend that predates this field deserialises to
     * null, which every caller reads as "can't tell" and treats exactly as it
     * did before. Nothing regresses while the two sides are out of step.
     */
    val audio:        String? = null,
    /**
     * The provider list as it stood AFTER selection, so the entry flagged
     * is_default is the one actually serving this stream.
     *
     * The backend has always sent this; the client simply had no field for it
     * and Gson dropped it. It is what lets the player name the server rather
     * than the audio track — "(Miruro bee)" instead of "(Miruro Dub)", which
     * told the viewer something the voices already had.
     *
     * Nullable: an older backend, or a cache entry written before the field
     * existed, just means the server cannot be named.
     */
    val servers:      List<ExtractServerOption>? = null,
)

/**
 * What one backend extract-and-play attempt produced.
 *
 * A plain Boolean could not express the case that matters: an extraction that
 * SUCCEEDED but returned the wrong audio track. That reads as success to a
 * caller checking for a manifest, which is precisely how sub audio reached a
 * dub request unnoticed.
 */
/**
 * One poll of a backend extraction job.
 *
 * [result] carries exactly the same payload [ExtractResponse] does on the
 * synchronous path — the server rebuilds it per request, so proxy_url points at
 * whatever host this client actually reached.
 */
data class ExtractJobResponse(
    @SerializedName("job_id") val jobId:  String?,
    val state:  String,
    val result: ExtractResponse? = null,
    val error:  ExtractJobError? = null,
    val ms:     Long? = null,
)

data class ExtractJobError(
    val reason: String? = null,
    val detail: String? = null,
)

enum class ExtractOutcome {
    /** Manifest in hand, player launched. */
    PLAYED,
    /** No manifest — backend down, blocked, or beaten by a site change. */
    MISS,
    /** Manifest in hand, but it is the other track. Caller decides what to do. */
    WRONG_TRACK,
}



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


/**
 * One English subtitle the backend can serve, WITHOUT its text.
 *
 * The cue count and timings are the whole reason this is a separate call from
 * the .vtt itself. The picker shows "1666 lines · 1:33 … 2:01:55" so a viewer
 * can spot the one cut for a different release, and those numbers can only be
 * known by parsing the file — which is exactly what the client used to do to
 * all eight of them, at a peak of 806MB on a 2GB box. Measured server-side
 * once per title, it is six integers.
 */
data class SubtitleOption(
    val id:           String = "",
    val index:        Int    = 0,
    val label:        String = "",
    val cue_count:    Int    = 0,
    val first_cue_ms: Long   = 0,
    val last_cue_ms:  Long   = 0,
)

data class SubtitleListResponse(
    val tmdb_id:    Int?    = null,
    val media_type: String? = null,
    val imdb_id:    String? = null,
    val season:     Int?    = null,
    val episode:    Int?    = null,
    val options:    List<SubtitleOption> = emptyList(),
)


/**
 * Ask the backend to time this subtitle against this source's audio.
 *
 * WHY THE CLIENT SENDS THE STREAM URL. The backend handed it to us moments ago
 * and it expires in 300s, so the player's copy is the freshest one in
 * existence. Having the server re-extract would spend a browser run to
 * rediscover it, and could easily come back with a different encode — these
 * sources rotate — which would measure one video and store the answer against
 * another.
 *
 * The source is part of the key for the same reason: Terminator 2 is 2:17:08 on
 * VidFast and 2:35:56 on VidNest, and an offset measured against one is
 * meaningless for the other.
 */
data class SubtitleSyncRequest(
    val tmdb_id:     Int,
    val id:          String,
    val source:      String,
    val media_type:  String = "movie",
    val season:      Int    = 0,
    val episode:     Int    = 0,
    val stream_url:  String = "",
    val referer:     String = "",
    val user_agent:  String = "",
    val duration_ms: Long   = 0,
)

data class SubtitleSyncResponse(
    val ok:            Boolean = false,
    val cached:        Boolean = false,
    /** + shows the cue later; the player's own nudge buttons feed the same field. */
    val offset_ms:     Long    = 0,
    /** cue * rate + offset. 1.0 means no drift was found. */
    val rate:          Double  = 1.0,
    val median_err_ms: Int     = 0,
    val reason:        String? = null,
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

    // ── BlueHive's OWN accounts ──────────────────────────────────────────────
    // These three are the reason the host model is going away. bluehive-api now
    // issues its own identities, so nothing here involves platform-api, an AIDL
    // bind, or a token injected by another app.
    //
    // UNAUTHENTICATED by definition — they are how a caller GETS a token. That
    // means they must not go through any interceptor that attaches a stale
    // Authorization header or tries to refresh on 401, or a failed login turns
    // into a refresh loop.
    @POST("/api/bluehive/auth/signup")
    suspend fun bhSignup(
        @Body body: BhSignupRequest,
    ): Response<BhAuthResponse>

    @POST("/api/bluehive/auth/login")
    suspend fun bhLogin(
        @Body body: BhLoginRequest,
    ): Response<BhAuthResponse>

    // Rotates: the old refresh token is revoked server-side and a new one
    // returned with a fresh 30-day window, so a TV in regular use never has to
    // sign in again. Replaces HostTokenProvider.fetchFreshToken().
    /**
     * Sign in using the host's signed pass instead of an email.
     *
     * Creates the account on first use, and returns the SAME account every time
     * after — including on a different TV, which is what makes profiles follow the
     * person rather than the television.
     *
     * UNAUTHENTICATED, like the two above, and for the same reason: it is how a
     * caller gets a token in the first place.
     *
     * Failure modes worth handling at the call site rather than swallowing:
     *   401 assertion_expired  — the host needs to mint a fresh pass; retrying
     *                            later can work, retrying immediately cannot
     *   401 assertion_invalid  — not a real pass; fall back to the email screen
     *   409 multiple_accounts  — several accounts share this host, so the server
     *                            refuses to guess. The email screen is the answer.
     */
    @POST("/api/bluehive/auth/host-provision")
    suspend fun bhHostProvision(
        @Body body: BhHostProvisionRequest,
    ): Response<BhAuthResponse>

    @POST("/api/bluehive/auth/refresh")
    suspend fun bhRefresh(
        @Body body: BhRefreshRequest,
    ): Response<BhAuthResponse>

    // ── Streaming-session / device-slot (PHASE 3, moved off PlatformApiService) ──
    // These ride bluehiveHttpClient, which sends the Bearer token and refreshes
    // it via hostTokenAuthenticator — which despite its name goes to bluehive-api
    // over HTTP, not to the host over IPC — exactly the auth shape
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
     * Client liveness. See SessionHeartbeat for why this exists at all — briefly:
     * it is the only signal a FROZEN app cannot produce, and every other signal
     * the backend had was authored by the backend itself.
     */
    @POST("/api/android/heartbeat")
    suspend fun heartbeat(): Response<HeartbeatResponse>

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
    /**
     * Which sources to show, and what url shape each uses.
     *
     * NO API KEY: this is what goes on a button, not a secret, and a client
     * that cannot authenticate still needs to show something. Templates come
     * back rather than finished urls, so one fetch serves every title.
     */
    @GET("/api/sources")
    suspend fun getSources(
        @Query("type") type: String,                     // "tv" | "movie"
    ): retrofit2.Response<com.example.bluehive.api.SourceCatalog.Response>

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
     * Begin an extraction and get a job id to poll — the same work as
     * [extractStream], with the waiting moved off the request.
     *
     * WHY THIS EXISTS. [extractStream] answers on one request, so the server's
     * entire budget has to fit inside this client's 30s readTimeout. It does not:
     * the extractor is allowed 45s and the API retries once, so a cold extraction
     * can legitimately need 60-90s. MEASURED 2026-08-30 on Jeepers Creepers and
     * Poltergeist — two SocketTimeoutExceptions 61s apart, then the very next
     * press played instantly, because the extraction the client had abandoned
     * finished server-side and filled the cache. The work was never the problem;
     * throwing it away was.
     *
     * The backend has had this endpoint for a while and no client ever called it.
     *
     * A CACHE HIT STILL ANSWERS INLINE, state="done" with the result attached, so
     * the common path costs exactly one request as before — polling is entered
     * only when there is genuinely something to wait for.
     */
    @GET("/api/extract/start")
    suspend fun extractStart(
        @Query("type")     type:     String,
        @Query("tmdb_id")  tmdbId:   Int,
        @Query("season")   season:   Int? = null,
        @Query("episode")  episode:  Int? = null,
        @Query("source")   source:   String = "vidfast",
        @Query("refresh")  refresh:  Int    = 0,
        @Query("mtype")    mtype:    String? = null,
        @Query("title")    title:    String? = null,
        @Query("profile")  profile:  Int?    = null,
        @Query("device")   device:   String? = null,
        @Query("audio")    audio:    String? = null,
        @Query("server")   server:   String? = null,
    ): Response<ExtractJobResponse>

    /**
     * Poll one job. Terminal states are "done" and "failed"; "expired" means the
     * job outlived its TTL and a fresh start is needed — deliberately distinct
     * from a 404, which would mean the id was never real.
     */
    @GET("/api/extract/job/{job_id}")
    suspend fun extractJob(
        @retrofit2.http.Path("job_id") jobId: String,
    ): Response<ExtractJobResponse>

    /**
     * Provider list for one title+track — the backend equivalent of the old
     * on-device ENUMERATE pass.
     *
     * AUDIO IS REQUIRED, not optional. Miruro's provider list is audio-scoped
     * (sub offers `kiwidlh-sub`/`allydlh-sub`/…, dub offers `kiwidl`/`allydl`/…),
     * so a list fetched under the wrong track names servers that do not exist on
     * the one about to play.
     */
    /**
     * CDN hostnames this backend has extracted from, for QUIC hinting.
     *
     * A client cannot discover these on its own without failing first: an
     * SNI-filtering ISP severs the TCP handshake before any alt-svc header can
     * arrive, so Chromium never learns the host speaks QUIC and can only be told
     * at engine-build time. Left alone, every device pays one relayed playback
     * per host — and these CDNs rotate constantly, so it never amortises.
     * The backend meets every host first, so it publishes the list.
     */
    @GET("/api/cdn-hosts")
    suspend fun cdnHosts(): Response<CdnHostsResponse>

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

    // Subtitles, listed and converted server-side. The imdb resolution above is
    // now done inside these two on the backend, so nothing here needs it first.
    @GET("/api/subtitles")
    suspend fun listSubtitles(
        @Query("tmdb_id")    tmdbId:    Int,
        @Query("media_type") mediaType: String,
        @Query("season")     season:    Int = 0,
        @Query("episode")    episode:   Int = 0,
    ): SubtitleListResponse

    /**
     * One converted WebVTT, as text/vtt.
     *
     * ResponseBody rather than String because the converter on this client is
     * JSON — handed a String it would try to parse a subtitle file as JSON and
     * fail on the first cue. ResponseBody is passed through untouched.
     */
    @GET("/api/subtitles/vtt")
    suspend fun getSubtitleVtt(
        @Query("tmdb_id")    tmdbId:    Int,
        @Query("id")         id:        String,
        @Query("media_type") mediaType: String,
        @Query("season")     season:    Int = 0,
        @Query("episode")    episode:   Int = 0,
    ): okhttp3.ResponseBody

    /**
     * Measure this subtitle against this source's own audio. Slow on a miss.
     *
     * Called in the BACKGROUND once captions are already on screen, never in
     * front of them: a miss costs ten to twenty seconds while the backend pulls
     * three windows of audio and correlates them, and nobody should wait for
     * that to read a line of dialogue. A hit is a single indexed row.
     */
    @POST("/api/subtitles/sync")
    suspend fun syncSubtitle(@Body body: SubtitleSyncRequest): SubtitleSyncResponse

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

    /**
     * Heartbeat the playback position (NEW 2026-08-17).
     *
     * progress_seconds used to be filled in server-side as
     * (stopped_at - watched_at) — the row's wall-clock age, not a position. A
     * 97-minute film left open 3h41m recorded 13258. Nothing ever sent the real
     * position because the client discarded the row id and had nothing to send it
     * against.
     */
    @POST("/api/bluehive/watch-history/{watchId}/progress")
    suspend fun updateWatchProgress(
        @Path("watchId") watchId: Int,
        @Body body: WatchProgressRequest,
    ): WatchHistoryResponse

    /**
     * Close the session, carrying the final position.
     *
     * The body is optional server-side, so a torn-down player that cannot supply
     * one still closes the row cleanly — it just keeps the last heartbeat instead.
     */
    @POST("/api/bluehive/watch-history/{watchId}/stop")
    suspend fun stopWatchHistory(
        @Path("watchId") watchId: Int,
        @Body body: WatchProgressRequest,
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

    /** Own profiles plus everyone else's on the same subscription licence.
     *  Separate from [listProfiles], which returns only what this account may
     *  ENTER — a distinction the picker depends on. 404s against a backend that
     *  predates it, so callers fall back to [listProfiles]. */
    @GET("/api/bluehive/profiles/household")
    suspend fun getHousehold(): HouseholdProfilesResponse

    /** Asked alongside [listProfiles] — the profile list alone cannot tell the
     *  screen whether THIS device still has room, only how full the account is. */
    @GET("/api/bluehive/profiles/quota")
    suspend fun getProfileQuota(): ProfileQuotaResponse

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
    /**
     * Report the account-standing assertion the host app handed us at launch.
     *
     * Fire-and-forget telemetry: BlueHive is free and this gates nothing. The
     * body is an opaque signed token the client never reads - the backend
     * verifies it with a public key and records the standing.
     */
    @POST("/api/bluehive/host-entitlement")
    suspend fun submitHostEntitlement(
        @Body body: HostEntitlementRequest,
    ): Response<com.example.bluehive.models.HostEntitlementResponse>

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
