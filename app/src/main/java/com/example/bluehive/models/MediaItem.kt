// MediaItem.kt
package com.example.bluehive.models

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

@Immutable
data class MediaItem(
    @SerializedName("tmdb_id")
    val tmdbId: Int,

    @SerializedName("media_id")
    val mediaId: Int,

    val title: String,

    @SerializedName("media_type")
    val mediaType: String,

    @SerializedName("poster_path")
    val posterUrl: String?,

    @SerializedName("backdrop_path")
    val backdropUrl: String?,

    @SerializedName("logos")
    val logoUrl: String?,

    @SerializedName("overview")
    val overview: String?,

    @SerializedName("release_date")
    val releaseDate: String?,

    @SerializedName("status")
    val status: String?,

    @SerializedName("vote_average")
    val voteAverage: Double?,

    @SerializedName("vote_count")
    val voteCount: Int?,

    val popularity: Double?,

    @SerializedName("popularity_rank")
    val popularityRank: Int?,

    @SerializedName("original_language")
    val originalLanguage: String?,

    @SerializedName("number_of_seasons")
    val numberOfSeasons: Int?,

    @SerializedName("number_of_episodes")
    val numberOfEpisodes: Int?,

    @SerializedName("content_rating")
    val contentRating: String?,

    val runtime: Int?,

    val budget: Long?,

    val revenue: Long?,

    @SerializedName("youtube_trailer_url")
    val trailerUrl: String?,

    val genres: List<String>?,

    @SerializedName("similar_items")
    val similarItems: List<MediaItem>?,

    @SerializedName("where_to_watch")
    val whereToWatch: List<WatchProvider>?
)

data class GenreDto(
    val genre_id: Int,
    val name: String,
)

data class WatchProvider(
    val name: String,
    @SerializedName("logo_url")
    val logoUrl: String,
    val url: String
)

data class MediaBrowseResponse(
    @SerializedName("group_slug")
    val groupSlug: String?,

    @SerializedName("group_name")
    val groupName: String?,

    @SerializedName("media_type")
    val mediaType: String?,

    @SerializedName("total_count")
    val totalCount: Int,

    val count: Int,
    val limit: Int,
    val offset: Int,
    val items: List<MediaItem>
)


data class RecommendationResponse(
    val source: SourceMedia,
    val count: Int,
    val recommendations: List<MediaItem>
)

data class SourceMedia(
    val tmdb_id: Int,
    val title: String,
    val media_type: String
)

/**
 * Models for: GET /api/tv/{tmdb_id}/seasons/{season_number}/episodes
 * (Matches the TMDB_Api_Server episode payload)
 */
data class Episode(
    @SerializedName("episode_id")
    val episodeId: Int,

    @SerializedName("tmdb_episode_id")
    val tmdbEpisodeId: Int,

    @SerializedName("episode_number")
    val episodeNumber: Int,

    @SerializedName("episode_title")
    val title: String,

    @SerializedName("episode_overview")
    val overview: String?,

    @SerializedName("air_date")
    val airDate: String?,

    val runtime: Int?,

    @SerializedName("episode_still_image")
    val stillImageUrl: String?,    // full URL from your API

    @SerializedName("episode_rating")
    val rating: Double?,

)

data class SeasonEpisodesResponse(
    @SerializedName("tmdb_id")
    val tmdbId: Int,

    @SerializedName("show_title")
    val showTitle: String,

    @SerializedName("season_number")
    val seasonNumber: Int,

    @SerializedName("season_name")
    val seasonName: String?,

    @SerializedName("season_overview")
    val seasonOverview: String?,

    @SerializedName("season_poster")
    val seasonPosterUrl: String?,

    @SerializedName("season_air_date")
    val seasonAirDate: String?,

    @SerializedName("total_episodes")
    val totalEpisodes: Int,

    @SerializedName("episode_count")
    val episodeCount: Int,

    val episodes: List<Episode>
)
