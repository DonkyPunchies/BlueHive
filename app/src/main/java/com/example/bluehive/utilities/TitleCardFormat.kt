package com.example.bluehive.utilities

/**
 * THE ONE PLACE TITLE-CARD TEXT IS FORMATTED, for every row in the app.
 *
 * WHY THIS EXISTS. The same formatting was written out four separate times —
 * MediaCatalog, SidebarFavorites, TvWideLazyRow and RecommendedMediaSection —
 * and the copies had already drifted apart:
 *
 *     MediaCatalog            TV -> "S: 3"    movie -> "2h 15m"
 *     SidebarFavorites        TV -> "S: 3"    movie -> "2h 15m"
 *     TvWideLazyRow           TV -> nothing   movie -> "2h 15m"
 *     RecommendedMediaSection TV -> nothing   movie -> "2h 15m"
 *
 * The last two never looked at numberOfSeasons at all, so a TV show on the home
 * rows or in the recommendations carried no badge whatsoever while the same show
 * in Favourites did. Changing the wording in one place would have deepened that
 * split rather than fixed it.
 *
 * Everything a card prints in that corner now comes from here, so a change to
 * the wording, the separator or the runtime format lands everywhere at once.
 */
object TitleCardFormat {

    /**
     * The word before a season count.
     *
     * Was "S", which is compact but tells a first-time viewer nothing — "S: 3"
     * reads as a code. The badge sits at 6.5sp in the card's bottom-right, and
     * the full word still clears the rating badge at the opposite corner on the
     * 112dp card, so the extra characters cost nothing but legibility gained.
     */
    const val SEASONS_LABEL = "Seasons"

    /** Between the label and its number. */
    const val LABEL_SEPARATOR = ": "

    /** "Seasons: 3", or null when the count is unknown or zero. */
    fun seasons(count: Int?): String? =
        count?.takeIf { it > 0 }?.let { "$SEASONS_LABEL$LABEL_SEPARATOR$it" }

    /** "2h 15m" / "2h" / "45m", or null when the runtime is unknown or zero. */
    fun runtime(minutes: Int?): String? {
        val m = minutes ?: return null
        if (m <= 0) return null
        val hours = m / 60
        val mins  = m % 60
        return when {
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0             -> "${hours}h"
            else                  -> "${mins}m"
        }
    }

    /**
     * The badge for a card: season count for TV, runtime for anything else.
     *
     * One function rather than a choice at each call site, because the choice
     * itself is what the four copies disagreed about.
     */
    fun meta(mediaType: String?, runtime: Int?, numberOfSeasons: Int?): String? =
        if (mediaType == "tv") seasons(numberOfSeasons) else runtime(runtime)

    /**
     * "2012-04-16" -> "04/16/2012". Returns the input unchanged if it is not in
     * that shape, and null for blanks and the literal "N/A" the API sends.
     */
    fun releaseDate(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() && it != "N/A" }
            ?.let {
                val parts = it.split("-")
                if (parts.size == 3) "${parts[1]}/${parts[2]}/${parts[0]}" else it
            }
}
