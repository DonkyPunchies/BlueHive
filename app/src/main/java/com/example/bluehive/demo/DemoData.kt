package com.example.bluehive.demo

import com.example.bluehive.models.MediaItem
import com.example.bluehive.models.WatchProvider

object DemoData {

    // TMDB image base urls (HTTPS)
    private const val POSTER_500 = "https://image.tmdb.org/t/p/w500"
    private const val BACKDROP_1280 = "https://image.tmdb.org/t/p/w1280"

    fun demoMovies(): List<MediaItem> = listOf(
        media(
            tmdbId = 603,
            mediaType = "movie",
            title = "The Matrix",
            posterPath = "/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg",
            backdropPath = "/8uO0gUM8aNqYLs1OsTBQiXu0fEv.jpg",
            overview = "A hacker learns the world is a simulation and joins the rebellion.",
            releaseDate = "1999-03-31",
            voteAverage = 8.2,
            voteCount = 25000,
            originalLanguage = "English",
            contentRating = "R",
            runtime = 136,
            genres = listOf("Action", "Sci-Fi"),
            whereToWatch = listOf(
                WatchProvider(
                    name = "Netflix",
                    logoUrl = "https://upload.wikimedia.org/wikipedia/commons/0/08/Netflix_2015_logo.svg",
                    url = "https://www.netflix.com/"
                )
            )
        ),
        media(
            tmdbId = 155,
            mediaType = "movie",
            title = "The Dark Knight",
            posterPath = "/qJ2tW6WMUDux911r6m7haRef0WH.jpg",
            backdropPath = "/hqkIcbrOHL86UncnHIsHVcVmzue.jpg",
            overview = "Batman faces the Joker in Gotham’s darkest hour.",
            releaseDate = "2008-07-18",
            voteAverage = 8.5,
            voteCount = 30000,
            originalLanguage = "English",
            contentRating = "PG-13",
            runtime = 152,
            genres = listOf("Action", "Crime", "Drama"),
            whereToWatch = listOf(
                WatchProvider(
                    name = "Prime Video",
                    logoUrl = "https://upload.wikimedia.org/wikipedia/commons/f/f1/Prime_Video.png",
                    url = "https://www.primevideo.com/"
                )
            )
        ),
        media(
            tmdbId = 299536,
            mediaType = "movie",
            title = "Avengers: Infinity War",
            posterPath = "/7WsyChQLEftFiDOVTGkv3hFpyyt.jpg",
            backdropPath = "/lmZFxXgJE3vgrciwuDib0N8CfQo.jpg",
            overview = "The Avengers battle Thanos to stop universal annihilation.",
            releaseDate = "2018-04-27",
            voteAverage = 8.3,
            voteCount = 29000,
            originalLanguage = "English",
            contentRating = "PG-13",
            runtime = 149,
            genres = listOf("Action", "Adventure"),
            whereToWatch = listOf(
                WatchProvider(
                    name = "Disney+",
                    logoUrl = "https://upload.wikimedia.org/wikipedia/commons/3/3e/Disney%2B_logo.svg",
                    url = "https://www.disneyplus.com/"
                )
            )
        )
    )

    fun demoTvShows(): List<MediaItem> = listOf(
        media(
            tmdbId = 1399,
            mediaType = "tv",
            title = "Game of Thrones",
            posterPath = "/7WUHnWGx5OO145IRxPDUkQSh4C7.jpg",
            backdropPath = "/suopoADq0k8YZr4dQXcU6pToj6s.jpg",
            overview = "Noble families fight for control of the Iron Throne.",
            releaseDate = "2011-04-17",
            voteAverage = 8.4,
            voteCount = 22000,
            originalLanguage = "English",
            contentRating = "TV-MA",
            numberOfSeasons = 8,
            numberOfEpisodes = 73,
            genres = listOf("Drama", "Fantasy"),
            whereToWatch = listOf(
                WatchProvider(
                    name = "Max",
                    logoUrl = "https://upload.wikimedia.org/wikipedia/commons/1/17/HBO_Max_Logo.svg",
                    url = "https://www.max.com/"
                )
            )
        ),
        media(
            tmdbId = 66732,
            mediaType = "tv",
            title = "Stranger Things",
            posterPath = "/49WJfeN0moxb9IPfGn8AIqMGskD.jpg",
            backdropPath = "/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
            overview = "A group of kids uncover a government secret and a terrifying alternate world.",
            releaseDate = "2016-07-15",
            voteAverage = 8.6,
            voteCount = 17000,
            originalLanguage = "English",
            contentRating = "TV-14",
            numberOfSeasons = 4,
            numberOfEpisodes = 34,
            genres = listOf("Drama", "Mystery", "Sci-Fi"),
            whereToWatch = listOf(
                WatchProvider(
                    name = "Netflix",
                    logoUrl = "https://upload.wikimedia.org/wikipedia/commons/0/08/Netflix_2015_logo.svg",
                    url = "https://www.netflix.com/"
                )
            )
        )
    )

    fun demoRecommendationsFor(sourceTmdbId: Int): List<MediaItem> {
        val pool = demoMovies() + demoTvShows()
        return pool.shuffled().take(12)
    }

    private fun media(
        tmdbId: Int,
        mediaType: String,
        title: String,
        posterPath: String?,
        backdropPath: String?,
        overview: String?,
        releaseDate: String?,
        voteAverage: Double?,
        voteCount: Int?,
        originalLanguage: String?,
        contentRating: String?,
        runtime: Int? = null,
        numberOfSeasons: Int? = null,
        numberOfEpisodes: Int? = null,
        genres: List<String>? = null,
        whereToWatch: List<WatchProvider>? = null
    ): MediaItem {
        return MediaItem(
            tmdbId = tmdbId,
            mediaId = tmdbId, // demo-safe
            title = title,
            mediaType = mediaType,
            posterUrl = posterPath?.let { "$POSTER_500$it" },
            backdropUrl = backdropPath?.let { "$BACKDROP_1280$it" },
            logoUrl = null,
            overview = overview,
            releaseDate = releaseDate,
            status = "Released",
            voteAverage = voteAverage,
            voteCount = voteCount,
            popularity = 9999.0,
            popularityRank = 1,
            originalLanguage = originalLanguage,
            numberOfSeasons = numberOfSeasons,
            numberOfEpisodes = numberOfEpisodes,
            contentRating = contentRating,
            runtime = runtime,
            budget = null,
            revenue = null,
            trailerUrl = null,
            genres = genres,
            similarItems = null,
            whereToWatch = whereToWatch
        )
    }
}
