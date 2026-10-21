// api/AnimeSources.kt
package com.example.bluehive.api

import android.util.Log
import com.google.gson.annotations.SerializedName

/**
 * Japanese-animation streaming sources.
 *
 * For any animated title in Japanese (original_language == "ja" AND genres
 * contain "Animation") we replace the usual 5-button source list with exactly
 * two buttons:
 *
 *   1. Miruro  — AniList-keyed. Needs the AniList id (+ remapped episode for TV),
 *                which we get from the backend /api/anime/resolve endpoint that
 *                reads the anibridge mappings.
 *   2. VidFast — TMDB-keyed, so it works straight from the TMDB id/season/episode
 *                with no resolve step.
 *
 * If the AniList resolve fails (no mapping for that title yet), the Miruro button
 * is simply omitted and the user still gets the working VidFast button.
 */

private const val TAG = "AnimeSources"

// ──────────────────────────────────────────────────────────────────────────────
// Response model for GET /api/anime/resolve
// (declared here in the api package so ApiService sees it without an import)
// ──────────────────────────────────────────────────────────────────────────────
data class AnimeResolveResponse(
    @SerializedName("tmdb_id")          val tmdbId: Int,
    @SerializedName("media_type")       val mediaType: String,
    val season: Int?,
    val episode: Int?,
    @SerializedName("anilist_id")       val anilistId: Int,
    @SerializedName("anilist_episode")  val anilistEpisode: Int?,
    // What Miruro addresses this entry by. It used to BE the AniList id, and in
    // October 2026 became an opaque 32-character key — /watch/1575?ep=4 now
    // 404s, and it 404s as a normal-looking page, so nothing announced itself
    // as broken. Nullable: the backend resolves it best-effort, and null just
    // means Miruro does not carry the title.
    @SerializedName("miruro_id")        val miruroId: String? = null,
    val span: Int,                  // >1 => one TMDB ep merges multiple AniList eps
    val exact: Boolean,
)

// ──────────────────────────────────────────────────────────────────────────────
// Detection
// ──────────────────────────────────────────────────────────────────────────────
/**
 * True for animated titles whose original language is Japanese.
 * `genres` is the comma-joined string the details screens already hold
 * (e.g. "Animation, Action"); `originalLanguage` is the TMDB code ("ja").
 */
fun isJapaneseAnimation(genres: String?, originalLanguage: String?): Boolean {
    val isAnimation = genres?.contains("Animation", ignoreCase = true) == true
    val lang = originalLanguage?.trim()
    val isJapanese = lang.equals("ja", ignoreCase = true) ||
            lang.equals("jpn", ignoreCase = true) ||
            lang.equals("japanese", ignoreCase = true)
    return isAnimation && isJapanese
}

// ──────────────────────────────────────────────────────────────────────────────
// Source builder
// ──────────────────────────────────────────────────────────────────────────────
/**
 * Builds the two Japanese-animation sources.
 *
 * Generic over the screen's StreamingSource type — pass the matching constructor
 * as [make] so this works for both MoviesDetailsScreenCompose.StreamingSource and
 * TVShowsDetailsScreenCompose.StreamingSource without duplicating the logic.
 *
 * @param mediaType     "movie" or "tv"
 * @param tmdbId        TMDB id (used directly by VidFast)
 * @param seasonNumber  TMDB season (null/ignored for movies)
 * @param episodeNumber TMDB episode within that season (null/ignored for movies)
 * @param make          (coverName, providerName, url) -> StreamingSource
 *
 * Must be called from a coroutine (it performs a network resolve). The order
 * returned is Miruro first, then VidFast.
 */
suspend fun <T> buildJapaneseAnimationSources(
    mediaType: String,
    tmdbId: Int,
    seasonNumber: Int?,
    episodeNumber: Int?,
    make: (coverName: String, name: String, url: String) -> T,
): List<T> {
    val out = mutableListOf<T>()

    val resolved: AnimeResolveResponse? = try {
        ApiClient.trailerApi.resolveAnime(
            tmdbId = tmdbId,
            mediaType = mediaType,
            season = if (mediaType == "movie") null else seasonNumber,
            episode = if (mediaType == "movie") null else episodeNumber,
        )
    } catch (e: Exception) {
        Log.w(TAG, "AniList resolve failed for tmdb=$tmdbId: ${e.message}")
        null
    }

    // Built from miruroId, NOT anilistId. The slug the real site appends is
    // left off on purpose: /watch/<id>?ep=N redirects to the slugged form and
    // keeps the query, so the slug stays the site's business.
    //
    // No URL at all when the id is missing, rather than one built from the
    // AniList id as a fallback — that URL is a guaranteed 404, and offering it
    // would put a source in the list that cannot work.
    val miruroUrl = resolved?.miruroId?.let { mid ->
        if (mediaType == "movie") "https://www.miruro.tv/watch/${'$'}mid"
        else "https://www.miruro.tv/watch/${'$'}mid?ep=${'$'}{resolved.anilistEpisode ?: 1}"
    }

    // 1) Miruro
    if (miruroUrl != null) out += make("Worker Stream", "Miruro", miruroUrl)

    // 2) VidFast (TMDB-keyed, always available)
    val vidFastUrl = if (mediaType == "movie")
        "https://vidfast.pro/movie/$tmdbId?autoPlay=true&server=Kirito"
    else
        "https://vidfast.pro/tv/$tmdbId/$seasonNumber/$episodeNumber?autoPlay=true&server=Kirito"
    out += make("Queen Stream", "VidFast", vidFastUrl)

    // 3) M Extract — anime movies only for now; carries the Miruro URL to scrape
    if (miruroUrl != null && mediaType == "movie") {
        out += make("Drone Stream", "M Extract", miruroUrl)
    }
    return out
}

