package com.example.bluehive

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluehive.webview.MainWebViewer
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import com.example.bluehive.utilities.*
import coil.compose.AsyncImage
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.bluehive.models.MediaItem
import com.example.bluehive.repository.RecommendationsRepository
import kotlinx.coroutines.launch
import com.example.bluehive.utilities.DetailsTrailerPlayer
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import com.example.bluehive.repository.EpisodesRepository
import com.example.bluehive.models.Episode
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.focus.focusProperties
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.FavoriteRequest
import com.example.bluehive.sidebarComponents.FavoritesScreenActivity
import com.example.bluehive.sidebarComponents.HistoryScreenActivity
import kotlinx.coroutines.async
import com.example.bluehive.searchBarComponent.SearchBarActivity
import com.example.bluehive.api.isJapaneseAnimation
import com.example.bluehive.api.buildJapaneseAnimationSources
import com.example.bluehive.api.WatchHistoryRequest
import com.example.bluehive.webview.BeeLogo
import com.example.bluehive.webview.ExtractionLoadingOverlay
import com.example.bluehive.webview.miruro.MiruroPlayerActivity
import com.example.bluehive.webview.miruro.MiruroExtractorActivity
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.withContext


class TVShowsDetailsScreenCompose: ComponentActivity() {


    internal var profileId: Int = -1
    private var mediaType: String = "tv"
    private var mediaId: Int = 0
    internal var currentSeasonForWatch: Int = 1
    internal var currentEpisodeForWatch: Int = 1
    internal var currentEpisodeNameForWatch: String? = null
    private var voteAverage: Double = 0.0
    private var mediaTitle: String = ""
    private var posterUrl: String? = null
    private var backdropUrl: String? = null
    private var trailerUrl: String? = null
    private var overview: String? = null
    private var contentRating: String? = null
    private var releaseDate: String? = null
    private var originalLanguage: String? = null
    private var logoUrl: String? = null
    private var genres: String? = null
    private var numberOfSeasons: Int = 0
    private var status: String? = null
    private var sourceScreen: String? = null
    private var searchQuery:  String? = null


    @Immutable
    data class StreamingSource(
        val coverName: String,
        val name: String,
        val url: String
    )

    companion object {
        private const val TAG = "TVShowsDetailsScreenCompose"
    }

    fun openMediaDetails(media: MediaItem) {
        val app = application as BlueHiveApplication
        app.pushDetailsHistory(MediaItem(
            tmdbId = mediaId,
            mediaId = mediaId,
            title = mediaTitle,
            mediaType = mediaType,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            logoUrl = logoUrl,
            overview = overview,
            releaseDate = releaseDate,
            status = status,
            voteAverage = voteAverage,
            voteCount = null,
            popularity = null,
            popularityRank = null,
            originalLanguage = originalLanguage,
            numberOfSeasons = numberOfSeasons,
            numberOfEpisodes = null,
            contentRating = contentRating,
            runtime = null,
            budget = null,
            revenue = null,
            trailerUrl = trailerUrl,
            genres = genres?.split(", ")?.map { it.trim() },
            similarItems = null,
            whereToWatch = null
        ))
        Log.d(
            "DetailsHistory",
            "Details -> openMediaDetails: pushed '${media.title}', size=${app.historySize()}"
        )

        val intent = Intent(this, TVShowsDetailsScreenCompose::class.java).apply {
            putExtra("media_type", media.mediaType)
            putExtra("media_id",          media.tmdbId)                     // Int (non-null)
            putExtra("media_title", media.title)
            putExtra("poster_url",        media.posterUrl ?: "")
            putExtra("backdrop_url",      media.backdropUrl ?: "")
            putExtra("youtube_trailer_url", media.trailerUrl ?: "")
            putExtra("overview",          media.overview ?: "")
            putExtra("vote_average",      media.voteAverage ?: 0.0)         // Double, NOT Double?
            putExtra("contentRating",     media.contentRating ?: "N/A")
            putExtra("original_language", media.originalLanguage ?: "N/A")
            putExtra("release_date",      media.releaseDate ?: "N/A")
            putExtra("logo_url",          media.logoUrl ?: "")
            putExtra("number_of_seasons", media.numberOfSeasons ?: 0)
            putExtra("status",            media.status ?: "N/A")

            val genresString = media.genres?.joinToString(", ")
            putExtra("genres", genresString ?: "N/A")
            putExtra("PROFILE_ID", profileId)
            sourceScreen?.let { putExtra("SOURCE_SCREEN", it) }
            searchQuery?.let  { putExtra("SEARCH_QUERY",  it) }
        }

        startActivity(intent)

        // ✅ Only one Details Activity alive at a time
        finish()
    }



    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        (application as BlueHiveApplication).setAppState(
            BlueHiveApplication.AppState.DETAILS_VIEWING
        )

        // ✅ Handle system back with sound + history stack
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                BlueHiveApplication.playBackOutSound()

                val app = application as BlueHiveApplication
                val previous = app.popPreviousDetails()

                if (previous == null) {
                    when (sourceScreen) {
                        SourceScreen.HISTORY -> {
                            Log.d("DetailsHistory", "Back: returning to History")
                            startActivity(
                                Intent(this@TVShowsDetailsScreenCompose, HistoryScreenActivity::class.java)
                                    .putExtra("PROFILE_ID", profileId)
                            )
                            finish()
                            return
                        }
                        SourceScreen.FAVORITES -> {
                            Log.d("DetailsHistory", "Back: returning to Favorites")
                            startActivity(
                                Intent(this@TVShowsDetailsScreenCompose, FavoritesScreenActivity::class.java)
                                    .putExtra("PROFILE_ID", profileId)
                            )
                            finish()
                            return
                        }
                        SourceScreen.SEARCH -> {
                            Log.d("DetailsHistory", "Back: returning to Search")
                            startActivity(
                                Intent(this@TVShowsDetailsScreenCompose, SearchBarActivity::class.java)
                                    .putExtra("PROFILE_ID",    profileId)
                                    .putExtra("INITIAL_QUERY", searchQuery ?: "")
                            )
                            finish()
                            return
                        }
                        else -> {
                            Log.d("DetailsHistory", "Back: no previous details, finishing to Home")
                            finish()
                            return
                        }
                    }
                }

                Log.d("DetailsHistory", "Back: navigating to previous '${previous.title}'")

                val intent = Intent(this@TVShowsDetailsScreenCompose, TVShowsDetailsScreenCompose::class.java).apply {
                    putExtra("media_type", previous.mediaType)
                    putExtra("media_id",          previous.tmdbId)
                    putExtra("media_title", previous.title)
                    putExtra("poster_url",        previous.posterUrl ?: "")
                    putExtra("backdrop_url",      previous.backdropUrl ?: "")
                    putExtra("youtube_trailer_url", previous.trailerUrl ?: "")
                    putExtra("overview",          previous.overview ?: "")
                    putExtra("vote_average",      previous.voteAverage ?: 0.0)
                    putExtra("contentRating",     previous.contentRating ?: "N/A")
                    putExtra("original_language", previous.originalLanguage ?: "N/A")
                    putExtra("release_date",      previous.releaseDate ?: "N/A")
                    putExtra("budget",            previous.budget ?: 0L)
                    putExtra("revenue",           previous.revenue ?: 0L)
                    putExtra("logo_url",          previous.logoUrl ?: "")
                    putExtra("genres",            previous.genres?.joinToString(", ") ?: "N/A")
                    putExtra("number_of_seasons", previous.numberOfSeasons ?: 0)
                    putExtra("status",            previous.status ?: "N/A")
                    putExtra("start_in_extras", true)
                    putExtra("PROFILE_ID",         profileId)
                    sourceScreen?.let { putExtra("SOURCE_SCREEN", it) }
                    searchQuery?.let  { putExtra("SEARCH_QUERY",  it) }
                }
                startActivity(intent)
                finish()
            }
        })


        // Extract media data from intent
        mediaType = intent.getStringExtra("media_type") ?: "tv"
        contentRating = intent.getStringExtra("contentRating")
        originalLanguage = intent.getStringExtra("original_language")
        mediaId = intent.getIntExtra("media_id", 0)
        voteAverage = intent.getDoubleExtra("vote_average", 0.0)
        mediaTitle = intent.getStringExtra("media_title") ?: ""
        posterUrl = intent.getStringExtra("poster_url")
        backdropUrl = intent.getStringExtra("backdrop_url")
        trailerUrl = intent.getStringExtra("youtube_trailer_url")
        overview = intent.getStringExtra("overview")
        logoUrl = intent.getStringExtra("logo_url")
        releaseDate = intent.getStringExtra("release_date")
        genres = intent.getStringExtra("genres")
        numberOfSeasons = intent.getIntExtra("number_of_seasons", 0)
        status = intent.getStringExtra("status")
        profileId = intent.getIntExtra("PROFILE_ID", -1)
        sourceScreen = intent.getStringExtra("SOURCE_SCREEN")
        searchQuery  = intent.getStringExtra("SEARCH_QUERY")

        val startInExtras  = intent.getBooleanExtra("start_in_extras", false)
        val lastSeason     = intent.getIntExtra("last_season",  1)
        val lastEpisode    = intent.getIntExtra("last_episode", 1)
        // Resume entries (Continue Watching) explicitly attach these extras
        // even for S1E1; fresh opens (search/trending/recommendations) never
        // do. Presence — not "episode > 1" — is what should drive episode focus.
        val resumeToEpisode = intent.hasExtra("last_season") || intent.hasExtra("last_episode")
        val recommendationsRepository = RecommendationsRepository()
        val episodesRepository = EpisodesRepository()


        setContent {
            TVShowsDetailsScreenContent(
                mediaTitle = mediaTitle,
                mediaType = mediaType,
                mediaId = mediaId,
                backdropUrl = backdropUrl,
                trailerUrl = trailerUrl,
                logoUrl = logoUrl,
                overview = overview,
                contentRating = contentRating,
                originalLanguage = originalLanguage,
                voteAverage = voteAverage,
                releaseDate = releaseDate,
                numberOfSeasons = numberOfSeasons,
                status = status,
                genres = genres,
                tmdbId = mediaId,
                startInExtras = startInExtras,
                lastSeason    = lastSeason,
                lastEpisode   = lastEpisode,
                resumeToEpisode = resumeToEpisode,
                recommendationsRepository = recommendationsRepository,
                episodesRepository = episodesRepository,
                onStreamingSourceSelected = { source ->
                    handleStreamingSourceSelected(source)
                },
                onEpisodeSelected = { season, episode, epName ->
                    currentSeasonForWatch       = season
                    currentEpisodeForWatch      = episode
                    currentEpisodeNameForWatch  = epName
                },
                onMediaClick = { media ->
                    openMediaDetails(media)
                }
            )
        }
    }


    override fun onResume() {
        super.onResume()
        if (!isFinishing) {
            (application as BlueHiveApplication).setAppState(
                BlueHiveApplication.AppState.DETAILS_VIEWING
            )
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            (application as BlueHiveApplication).setAppState(
                BlueHiveApplication.AppState.HOME_ACTIVE
            )
        }
        super.onDestroy()
    }

// TVShowsDetailsScreenCompose
    private fun handleStreamingSourceSelected(source: StreamingSource) {
        Log.d(TAG, "Selected streaming source: ${source.name} - URL: ${source.url}")

        (application as BlueHiveApplication).apply {
            Log.d(
                "DetailsHistory",
                "Streaming clicked for '$mediaTitle' – clearing details history for GeckoView"
            )
            clearDetailsHistory()
            setAppState(BlueHiveApplication.AppState.VIDEO_PLAYING)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this@TVShowsDetailsScreenCompose, MainWebViewer::class.java).apply {
                putExtra("PROFILE_ID",      profileId)
                putExtra("SEASON_NUMBER",   currentSeasonForWatch)
                putExtra("EPISODE_NUMBER",  currentEpisodeForWatch)
                putExtra("EPISODE_NAME",    currentEpisodeNameForWatch)
                putExtra("MEDIA_ID",        mediaId.toString())
                putExtra("MEDIA_TYPE",      mediaType)
                putExtra("MEDIA_TITLE",     mediaTitle)
                putExtra("MEDIA_TYPE", mediaType)
                putExtra("vote_average", voteAverage)
                putExtra("SOURCE_NAME", source.name)
                putExtra("SOURCE_URL", source.url)
                putExtra("backdrop_url", backdropUrl)
                putExtra("trailer_url", trailerUrl)
                putExtra("media_title", mediaTitle)
                putExtra("poster_url", posterUrl)
                putExtra("overview", overview)
                putExtra("contentRating", contentRating)
                putExtra("originalLanguage", originalLanguage)
                putExtra("releaseDate", releaseDate)
                putExtra("logo_url", logoUrl)
            }
            startActivity(intent)
        }, 200)
    }

}


private fun getStreamingSources(
    mediaType: String,
    mediaId: Int,
    seasonNumber: Int,
    episodeNumber: Int
): List<TVShowsDetailsScreenCompose.StreamingSource> {
    return when (mediaType) {
        "tv" -> listOf(
            TVShowsDetailsScreenCompose.StreamingSource(
                "Purple Stream",
                "VidSpark",
                "https://ww2.moviesapi.to/tv/$mediaId/$seasonNumber/$episodeNumber"
            ),
            TVShowsDetailsScreenCompose.StreamingSource(
                "Queen Stream",
                "VidFast",
                 "https://vidfast.pro/tv/$mediaId/$seasonNumber/$episodeNumber?autoPlay=true"
            ),
            TVShowsDetailsScreenCompose.StreamingSource(
                "Royal Jelly",
                "VidEasy",
                "https://player.videasy.net/tv/$mediaId/$seasonNumber/$episodeNumber"
            ),
            TVShowsDetailsScreenCompose.StreamingSource(
                "Swarm Stream",
                "VidLink",
                "https://vidlink.pro/tv/$mediaId/$seasonNumber/$episodeNumber"
            ),
            TVShowsDetailsScreenCompose.StreamingSource(
                "Colony Stream",
                "VidSuper",
                "https://vidsuper.net/tv/$mediaId/$seasonNumber/$episodeNumber"
            ),
            TVShowsDetailsScreenCompose.StreamingSource(
                "Honeycomb",
                "VidApi",
                "https://vaplayer.ru/embed/tv/$mediaId?s=$seasonNumber&e=$episodeNumber"
            )
        )
        else -> emptyList()
    }
}





@Composable
fun EpisodeBadge(
    current: Int,
    total: Int,
    isFocused: Boolean,
    modifier: Modifier = Modifier
) {
    val badgeText = remember(current, total) { "$current/$total" }

    // ✅ Same "exposure" scale idea used for stills
    val darkScale = 0.40f

    fun darken(color: Color): Color {
        return color.copy(
            red = color.red * darkScale,
            green = color.green * darkScale,
            blue = color.blue * darkScale
        )
    }

    val baseBg = Color(0xFF262629)
    val baseText = Color.White

    val bgColor = if (isFocused) baseBg else darken(baseBg)
    val textColor = if (isFocused) baseText else darken(baseText)

    Box(
        modifier = modifier
            // ⬇️ slightly wider than RatingBadge, same height
            .height(16.5.dp)
            .width(25.dp) // you can tweak if you want more breathing room
            .clip(AppShapes.ratingBadge)
            .border(width = 1.8.dp, color = Color(0xB0000000), shape = AppShapes.ratingBadge)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = badgeText,
            fontSize = 7.sp,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}




@SuppressLint("DefaultLocale")
@UnstableApi
@Composable
fun TVShowsDetailsScreenContent(
    mediaTitle: String,
    mediaType: String,
    mediaId: Int,
    logoUrl: String?,
    backdropUrl: String?,
    trailerUrl: String?,
    overview: String?,
    voteAverage: Double,
    contentRating: String?,
    originalLanguage: String?,
    releaseDate: String?,
    numberOfSeasons: Int,
    status: String?,
    genres: String?,
    tmdbId: Int,
    startInExtras: Boolean = false,
    lastSeason:    Int     = 1,
    lastEpisode:   Int     = 1,
    resumeToEpisode: Boolean = false,
    recommendationsRepository: RecommendationsRepository,
    episodesRepository: EpisodesRepository,
    onStreamingSourceSelected: (TVShowsDetailsScreenCompose.StreamingSource) -> Unit,
    onEpisodeSelected: (season: Int, episode: Int, episodeName: String?) -> Unit = { _, _, _ -> },
    onMediaClick: (MediaItem) -> Unit
) {
    // Load custom fonts
    val passionFont = AppTypography.passionRegular
    val dongleRegular = AppTypography.dongleRegular
    val context = LocalContext.current
    val app = context.applicationContext as? BlueHiveApplication
    val prefetch = remember(tmdbId) { app?.consumePrefetch(tmdbId) }

    // Decode the loading-overlay bee logo now, while the screen is first
    // composing and memory is comparatively free, and cache it process-wide so
    // the Dub/Sub overlay never decodes it under pressure (the 2 GB crash).
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { BeeLogo.prime(context) }
    }


    // 🔹 Precomputed display strings (recomputed only when inputs change)
    val seasonsLabel = remember(numberOfSeasons) {
        "Seasons:          " + if (numberOfSeasons > 0) numberOfSeasons.toString() else "N/A"
    }

    val statusLabel = remember(status) {
        "Status:            " + if (!status.isNullOrBlank() && status != "N/A") status else "N/A"
    }

    val languageLabel = remember(originalLanguage) {
        "Language:        " + (
                if (!originalLanguage.isNullOrBlank() && originalLanguage != "N/A")
                    originalLanguage
                else
                    "N/A"
                )
    }

    val releaseDateLabel = remember(releaseDate) {
        if (!releaseDate.isNullOrBlank() && releaseDate != "N/A") {
            val dateParts = releaseDate.split("-")
            if (dateParts.size == 3) {
                // Format as MM/DD/YYYY
                "Release Date:     ${dateParts[1]}/${dateParts[2]}/${dateParts[0]}"
            } else {
                "Release Date:     $releaseDate"
            }
        } else {
            "Release Date:      N/A"
        }
    }




    // ✅ Add state for extras toggle and recommendations
    var isExtrasToggled by remember(startInExtras) { mutableStateOf(startInExtras) }
    var recommendations by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()
    var blockHeaderInitialFocus by remember(startInExtras) {
        mutableStateOf(startInExtras || resumeToEpisode)
    }
    var isInitialEpisodeLoad by remember { mutableStateOf(resumeToEpisode) }

    // ✅ When returning from history and asked to start in Extras,
    // preload recommendations and keep Extras toggled on.
    LaunchedEffect(startInExtras, tmdbId, mediaType) {
        val prefetchedRecs = prefetch?.recommendations
        if (!prefetchedRecs.isNullOrEmpty()) {
            recommendations = prefetchedRecs
            isExtrasToggled = startInExtras
            Log.d("Prefetch", "✅ Recommendations loaded from prefetch cache (${prefetchedRecs.size} items)")
        } else if (startInExtras && recommendations.isEmpty()) {
            try {
                val result = recommendationsRepository.getRecommendations(tmdbId, mediaType)
                recommendations = result
                isExtrasToggled = true
            } catch (e: Exception) {
                Log.e("TVRecommendations", "Error preloading recommendations", e)
            }
        }
    }



    LaunchedEffect(startInExtras, recommendations) {
        if (startInExtras && recommendations.isNotEmpty()) {
            blockHeaderInitialFocus = false
        }
    }


    var selectedSeason by remember { mutableIntStateOf(1) }  // default to Season 1
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    // 🔹 Focus requesters for episode tiles (rebuilds safely when episodes change)
    val episodeFocusRequesters = remember(episodes) {
        List(episodes.size) { FocusRequester() }
    }

    val seasonFocusRequesters = remember(numberOfSeasons) {
        if (numberOfSeasons > 0) {
            List(numberOfSeasons) { FocusRequester() }
        } else {
            emptyList()
        }
    }



    var lastFocusedEpisodeIndex by remember { mutableIntStateOf(0) }  // ✅ Remember the last focused episode so RIGHT from seasons always restores correctly
    val episodesRowState = rememberLazyListState() // ✅ Give LazyRow a state so we can scroll before restoring focus

    // Hoisted season-column scroll state (the LazyColumn below now uses this
    // instead of its own local rememberLazyListState) so the episode row's Back
    // handler can scroll a season button into view before focusing it.
    val seasonsListState = rememberLazyListState()

    // Reliable focus for a season button. A season button lives in a LazyColumn,
    // so its FocusRequester only attaches once the row is composed AND placed.
    // Presence in visibleItemsInfo == laid out == requester attached. We scroll
    // it in, wait for that, then requestFocus — with retries so a busy main
    // thread (entering from Continue Watching) can't make Back silently no-op.
    suspend fun focusSeasonReliably(targetIndex: Int) {
        if (targetIndex < 0 || targetIndex >= seasonFocusRequesters.size) return
        seasonsListState.scrollToItem(targetIndex)
        var attempts = 0
        while (attempts < 40) {
            awaitFrame()
            val laidOut = seasonsListState.layoutInfo
                .visibleItemsInfo.any { it.index == targetIndex }
            if (laidOut) {
                seasonFocusRequesters.getOrNull(targetIndex)?.requestFocus()
                return
            }
            attempts++
        }
        // Last-ditch: try anyway so focus is never stranded on the episode tile.
        seasonFocusRequesters.getOrNull(targetIndex)?.requestFocus()
    }




    var selectedEpisodeOverlayIndex by remember { mutableIntStateOf(-1) }

    // ✅ Focus requesters for overlay buttons.
    // Pool sized to the largest provider set we might show — the tv source list
    // (the anime overlay shows fewer). Deriving the size from getStreamingSources()
    // means adding a source there also grows this pool, so the inline grid can
    // never index past it. coerceAtLeast(2) keeps the anime DuB/SuB pair safe.
    val providerFocusRequesters = remember {
        List(getStreamingSources(mediaType, mediaId, 1, 1).size.coerceAtLeast(2)) { FocusRequester() }
    }

    // ✅ Position of the selected episode still in root coordinates
    val density = LocalDensity.current
    var overlayAnchor by remember { mutableStateOf<Offset?>(null) }


    var isTrailerPlaying by remember { mutableStateOf(false) }
    var hasTrailerBeenPlayed by remember { mutableStateOf(false) }
    var shouldExtractTrailer by remember { mutableStateOf(false) }

    // Focus requesters
    // Focus requesters
    val favoriteFocusRequester = remember { FocusRequester() }
    val extrasFocusRequester = remember { FocusRequester() }
    val trailerFocusRequester = remember { FocusRequester() }

    // Set true by the focused episode tile's onFocusChanged (see Edit 3).
    // Lets the initial-focus effect below confirm focus actually landed
    // instead of assuming a fire-and-forget requestFocus() succeeded.
    var episodeFocusLanded by remember { mutableStateOf(false) }

    // ✅ Episode row scroll + initial focus (moved here from above so the
    //    fallback can reach the header requesters).
    //
    //    Why this is a retry loop instead of delay(150) + requestFocus():
    //    the target tile lives in a LazyRow, so its FocusRequester is only
    //    attached once the tile is composed AND placed. On entry from
    //    Continue Watching the main thread is busy (heavy frames + Coil cache
    //    thrashing), so a fixed delay elapses before layout finishes and
    //    requestFocus() no-ops ("FocusRequester is not initialized"). Because
    //    the header is focus-blocked in this mode, that leaves the whole
    //    screen with nothing focused = stuck. So: scroll, wait until the tile
    //    is actually laid out (presence in visibleItemsInfo => node attached),
    //    request focus, confirm it landed, and only then release the gates.
    LaunchedEffect(episodes) {
        if (episodes.isEmpty()) return@LaunchedEffect

        if (isInitialEpisodeLoad) {
            val targetIndex = (lastEpisode - 1).coerceIn(0, episodes.lastIndex)
            lastFocusedEpisodeIndex = targetIndex
            episodesRowState.scrollToItem(targetIndex)

            episodeFocusLanded = false
            var landed = false
            var attempts = 0
            while (attempts < 40 && !landed) {
                awaitFrame()
                val laidOut = episodesRowState.layoutInfo
                    .visibleItemsInfo.any { it.index == targetIndex }
                if (laidOut) {
                    episodeFocusRequesters.getOrNull(targetIndex)?.requestFocus()
                    awaitFrame()
                    if (episodeFocusLanded) landed = true
                }
                attempts++
            }

            // Never strand the user with no focus: if the episode tile truly
            // never took focus, un-block the header and focus Favorites.
            if (!landed) {
                Log.w("TVDetails", "⚠️ Episode focus never landed — falling back to header")
                blockHeaderInitialFocus = false
                awaitFrame()
                favoriteFocusRequester.requestFocus()
            }

            blockHeaderInitialFocus = false
            isInitialEpisodeLoad = false
        } else {
            lastFocusedEpisodeIndex = 0
            episodesRowState.scrollToItem(0)
        }
    }

    var isExtractingTrailer by remember { mutableStateOf(false) }


    var isFavorited by remember { mutableStateOf(false) }
    val activity = LocalActivity.current as? TVShowsDetailsScreenCompose
    val favoriteProfileId = activity?.profileId ?: -1

    // ════════════════════════════════════════════════════════════════════════
    //  Anime DuB / SuB extraction flow (ported from MoviesDetailsScreenCompose).
    //  The Miruro source URL already carries ?ep=N, so the extractor clicks the
    //  right episode itself — we just feed it the URL like the movie screen does.
    // ════════════════════════════════════════════════════════════════════════
    var isExtracting   by remember { mutableStateOf(false) }
    var extractStage   by remember { mutableIntStateOf(0) }
    var extractPending by remember { mutableStateOf(false) }
    var showNoDub      by remember { mutableStateOf(false) }

    var showDefaultPrompt by remember { mutableStateOf(false) }
    var isCheckingServers by remember { mutableStateOf(false) }
    var showServerPicker  by remember { mutableStateOf(false) }
    var showServerError   by remember { mutableStateOf(false) }
    var pendingUrl         by remember { mutableStateOf<String?>(null) }
    var pendingDub         by remember { mutableStateOf(false) }
    var pendingSeason      by remember { mutableIntStateOf(1) }
    var pendingEpisode     by remember { mutableIntStateOf(1) }
    var pendingEpisodeName by remember { mutableStateOf<String?>(null) }
    var serverOptions      by remember { mutableStateOf<List<ServerOption>>(emptyList()) }
    var serverDefaultIdx   by remember { mutableIntStateOf(0) }
    var lastStreamFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }

    // Server lists captured during a DEFAULT extraction, cached for the lifetime
    // of this detail screen. Keyed by url + audio (url carries ?ep=, so each
    // episode caches its own list) so "Choose a server" can skip the enumerate
    // webview pass when the list is already known. Value = (options, defaultIndex).
    val serverListCache = remember { mutableMapOf<String, Pair<List<ServerOption>, Int>>() }
    fun serverCacheKey(url: String, dub: Boolean) = "$url|${if (dub) "dub" else "sub"}"

    // Back closes whichever prompt/picker is open instead of leaving the screen.
    BackHandler(enabled = showDefaultPrompt || showServerPicker) {
        when {
            showServerPicker -> { showServerPicker = false; showDefaultPrompt = true }
            showDefaultPrompt -> {
                showDefaultPrompt = false
                coroutineScope.launch {
                    awaitFrame()
                    try { lastStreamFocusRequester?.requestFocus() } catch (_: Exception) {}
                }
            }
        }
    }

    // Phase 2 result: receives the m3u8 (default OR chosen server) and plays it,
    // then logs the episode to watch history.
    val extractorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isExtracting   = false
        extractPending = false
        if (result.resultCode == Activity.RESULT_OK) {
            val data    = result.data
            val m3u8    = data?.getStringExtra(MiruroExtractorActivity.RESULT_M3U8)
            val referer = data?.getStringExtra(MiruroExtractorActivity.RESULT_REFERER)
            val ua      = data?.getStringExtra(MiruroExtractorActivity.RESULT_UA)
            if (!m3u8.isNullOrBlank()) {
                val proxied = BuildConfig.API_BASE_URL.trimEnd('/') +
                        "/api/hls/proxy?url=" + URLEncoder.encode(m3u8, "UTF-8")
                Log.d("scraper", "M Extract → relaying via $proxied")
                context.startActivity(
                    Intent(context, MiruroPlayerActivity::class.java)
                        .putExtra("M3U8_URL", proxied)
                        .putExtra("REFERER", referer)
                        .putExtra("UA", ua)
                )
                // Same endpoint MainWebViewer uses — lands in BOTH Watch History
                // and Continue Watching, this time with season + episode.
                if (favoriteProfileId != -1 && tmdbId != 0) {
                    val sourceLabel = if (pendingDub) "Miruro (Dub)" else "Miruro (Sub)"
                    val logSeason  = pendingSeason
                    val logEpisode = pendingEpisode
                    val logEpName  = pendingEpisodeName
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        try {
                            ApiClient.bluehiveApi.logWatchHistory(
                                WatchHistoryRequest(
                                    profile_id     = favoriteProfileId,
                                    media_tmdb_id  = tmdbId,
                                    media_type     = mediaType,
                                    source_name    = sourceLabel,
                                    media_title    = mediaTitle,
                                    season_number  = logSeason,
                                    episode_number = logEpisode,
                                    episode_name   = logEpName,
                                )
                            )
                            Log.d("scraper", "✅ Watch history logged: anime tv tmdbId=$tmdbId S$logSeason E$logEpisode ($sourceLabel)")
                        } catch (e: Exception) {
                            Log.w("scraper", "⚠️ Anime watch-history log failed (non-fatal): ${e.message}")
                        }
                    }
                }

                // ── Cache any server list captured in this same pass ─────────
                // A default extraction (captureServers=true) also carries the
                // provider list on the RESULT_SERVER_* extras. Save it (keyed by
                // this episode's url + audio) so "Choose a server" is instant.
                val capNames = data?.getStringArrayListExtra(MiruroExtractorActivity.RESULT_SERVER_NAMES)
                val capTags  = data?.getStringArrayListExtra(MiruroExtractorActivity.RESULT_SERVER_TAGS)
                val capDef   = data?.getIntExtra(MiruroExtractorActivity.RESULT_SERVER_DEFAULT_INDEX, 0) ?: 0
                val capUrl   = pendingUrl
                if (!capNames.isNullOrEmpty() && capUrl != null) {
                    val opts = capNames.mapIndexed { i, name ->
                        val tagStr = capTags?.getOrNull(i) ?: ""
                        val tagList = if (tagStr.isBlank()) emptyList()
                            else tagStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        ServerOption(name = name, tags = tagList)
                    }
                    serverListCache[serverCacheKey(capUrl, pendingDub)] =
                        opts to capDef.coerceIn(0, opts.size - 1)
                    Log.d("scraper", "🗂 cached ${opts.size} servers from default extract")
                }
            } else {
                Log.e("scraper", "M Extract: OK result but no m3u8")
                showServerError = true
            }
        } else {
            val data   = result.data
            val noDub  = data?.getBooleanExtra(MiruroExtractorActivity.RESULT_NO_DUB, false) == true
            val failed = data?.getBooleanExtra(MiruroExtractorActivity.RESULT_FAILED, false) == true
            when {
                noDub  -> { Log.w("scraper", "M Extract: no dub available"); showNoDub = true }
                failed -> { Log.w("scraper", "M Extract: failed");            showServerError = true }
                else   -> { Log.w("scraper", "M Extract: user cancelled") }
            }
        }
    }

    // Phase 1 result: receives the server list, then shows the picker.
    val enumerateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isCheckingServers = false
        if (result.resultCode == Activity.RESULT_OK) {
            val data   = result.data
            val names  = data?.getStringArrayListExtra(MiruroExtractorActivity.RESULT_SERVER_NAMES)
            val tags   = data?.getStringArrayListExtra(MiruroExtractorActivity.RESULT_SERVER_TAGS)
            val defIdx = data?.getIntExtra(MiruroExtractorActivity.RESULT_SERVER_DEFAULT_INDEX, 0) ?: 0
            if (names != null && names.isNotEmpty()) {
                val options = names.mapIndexed { i, name ->
                    val tagStr = tags?.getOrNull(i) ?: ""
                    val tagList = if (tagStr.isBlank()) emptyList()
                    else tagStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    ServerOption(name = name, tags = tagList)
                }
                serverOptions    = options
                serverDefaultIdx = defIdx.coerceIn(0, options.size - 1)
                showServerPicker = true
                // Cache so re-opening the picker for this same episode+audio is
                // instant and a later default play won't re-read the list either.
                pendingUrl?.let { u ->
                    serverListCache[serverCacheKey(u, pendingDub)] =
                        options to defIdx.coerceIn(0, options.size - 1)
                }
            } else {
                Log.w("scraper", "M Enumerate: OK but empty server list")
                showServerError = true
            }
        } else {
            val data   = result.data
            val noDub  = data?.getBooleanExtra(MiruroExtractorActivity.RESULT_NO_DUB, false) == true
            val failed = data?.getBooleanExtra(MiruroExtractorActivity.RESULT_FAILED, false) == true
            when {
                noDub  -> { Log.w("scraper", "M Enumerate: no dub available"); showNoDub = true }
                failed -> { Log.w("scraper", "M Enumerate: failed");           showServerError = true }
                else   -> { Log.w("scraper", "M Enumerate: user cancelled") }
            }
        }
    }

    // Default-server extract (server == null). The 200ms delay lets the button
    // press animation finish before the loading overlay appears.
    val launchExtract: (String, Boolean, String?) -> Unit = { url, dub, server ->
        Log.d("scraper", "M Extract (dub=$dub, server=${server ?: "default"}) → $url")
        if (!isExtracting && !extractPending && !isCheckingServers) {
            extractPending = true
            coroutineScope.launch {
                delay(200)
                isExtracting = true
                try { BlueHiveApplication.coilImageLoader.memoryCache?.clear() } catch (_: Exception) {}
                val intent = Intent(context, MiruroExtractorActivity::class.java)
                    .putExtra(MiruroExtractorActivity.EXTRA_URL, url)
                    .putExtra(MiruroExtractorActivity.EXTRA_DUB, dub)
                    .putExtra(MiruroExtractorActivity.EXTRA_MODE, MiruroExtractorActivity.MODE_EXTRACT)
                if (!server.isNullOrBlank()) {
                    intent.putExtra(MiruroExtractorActivity.EXTRA_SERVER_NAME, server)
                } else if (serverListCache[serverCacheKey(url, dub)] == null) {
                    // Default server, list not cached yet → grab the provider list
                    // in this same pass so "Choose a server" is instant afterward.
                    intent.putExtra(MiruroExtractorActivity.EXTRA_CAPTURE_SERVERS, true)
                }
                extractorLauncher.launch(intent)
            }
        }
    }

    val launchEnumerate: (String, Boolean) -> Unit = { url, dub ->
        Log.d("scraper", "M Enumerate (dub=$dub) → $url")
        if (!isExtracting && !extractPending && !isCheckingServers) {
            extractPending = true
            coroutineScope.launch {
                delay(200)
                extractPending = false
                isCheckingServers = true
                try { BlueHiveApplication.coilImageLoader.memoryCache?.clear() } catch (_: Exception) {}
                enumerateLauncher.launch(
                    Intent(context, MiruroExtractorActivity::class.java)
                        .putExtra(MiruroExtractorActivity.EXTRA_URL, url)
                        .putExtra(MiruroExtractorActivity.EXTRA_DUB, dub)
                        .putExtra(MiruroExtractorActivity.EXTRA_MODE, MiruroExtractorActivity.MODE_ENUMERATE)
                )
            }
        }
    }

    LaunchedEffect(isExtracting) {
        if (isExtracting) {
            extractStage = 1
            delay(900);  extractStage = 2
            delay(2500); extractStage = 3
            delay(3500); extractStage = 4
        } else {
            extractStage = 0
        }
    }
    LaunchedEffect(showNoDub) {
        if (showNoDub) { delay(2500); showNoDub = false }
    }
    LaunchedEffect(showServerPicker) {
        if (showServerPicker) {
            delay(30_000)
            if (showServerPicker) { showServerPicker = false; showDefaultPrompt = true }
        }
    }
    LaunchedEffect(showServerError) {
        if (showServerError) { delay(2500); showServerError = false }
    }

    // Check favorite status on load
    LaunchedEffect(tmdbId, mediaType, favoriteProfileId) {
        if (favoriteProfileId != -1) {
            try {
                val status = ApiClient.bluehiveApi.checkFavorite(
                    profileId   = favoriteProfileId,
                    mediaTmdbId = tmdbId,
                    mediaType   = mediaType,
                )
                isFavorited = status.is_favorited
            } catch (_: Exception) {}
        }
    }



    // 🔹 Helper to load episodes for a given season
    fun loadSeasonEpisodes(seasonNumber: Int) {
        coroutineScope.launch {
            isLoadingEpisodes = true
            try {
                val response = episodesRepository.getSeasonEpisodes(
                    tmdbId = tmdbId,
                    seasonNumber = seasonNumber
                )
                episodes = response.episodes
            } catch (e: Exception) {
                Log.e("TVEpisodes", "Error loading episodes for S$seasonNumber", e)
                episodes = emptyList()
            } finally {
                isLoadingEpisodes = false
            }
        }
    }

    // 🔹 On first entry, auto-load Season 1 if we have seasons
    LaunchedEffect(tmdbId, numberOfSeasons) {
        if (numberOfSeasons > 0) {
            selectedSeason = lastSeason.coerceIn(1, numberOfSeasons)
            val prefetchedEpisodes = prefetch?.episodes
            if (prefetchedEpisodes != null && lastSeason == 1) {
                episodes = prefetchedEpisodes
                isLoadingEpisodes = false
                Log.d("Prefetch", "✅ Episodes loaded from prefetch cache (${prefetchedEpisodes.size} eps)")
            } else {
                loadSeasonEpisodes(selectedSeason)
            }
        }
    }

    // ✅ Log cache stats when screen loads — deferred + off the main thread.
    // gatherStats() does THREE full directory walks; running it inline on entry
    // was a main-thread stall. Paint first, then walk on IO.
    LaunchedEffect(Unit) {
        delay(1500)
        withContext(Dispatchers.IO) {
            CacheMonitor.logCacheStats(
                context = context,
                screenName = "TVShowsDetailsScreenCompose",
                imageCount = 0  // Initial load
            )
        }
    }

    // ✅ Log again when recommendations load — also off the main thread.
    LaunchedEffect(recommendations) {
        if (recommendations.isNotEmpty()) {
            // 1 backdrop + 1 logo + 20 recommendations + 6 provider buttons
            val totalImages = 1 + 1 + recommendations.size + 6

            withContext(Dispatchers.IO) {
                CacheMonitor.logCacheStats(
                    context = context,
                    screenName = "TVShowsDetailsScreenCompose",
                    imageCount = totalImages
                )
            }
        }
    }




    // Main container with background
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Background image layer
        Image(
            painter = painterResource(id = R.drawable.backdrop_details),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )


        // ========================================================================================================================================= > // Content Rating
        if (!contentRating.isNullOrBlank() && contentRating != "N/A") {
            Text(
                text = if (contentRating.isNotBlank()) {
                    "Rated: $contentRating"
                } else {
                    "Rated: N/A"
                },  // Changed this line
                color = Color(0xFFFFFFFF),
                fontSize = 16.sp,
                fontFamily = dongleRegular,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 258.8.dp, top = 16.75.dp)
            )
        }
        // ========================================================================================================================================= >




        // ========================================================================================================================================= > // vote_average
        Image(
            painter = painterResource(id = R.drawable.star),
            contentDescription = "Rating star",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 337.dp, top = 21.dp)   // ⬅️ star position (edit this independently)
                .size(11.dp)
        )

        Text(
            text = "${String.format("%.1f", voteAverage)}/10",  // Format to 1 decimal place
            color = Color(0xFFFFFFFF),
            fontSize = 16.sp,
            fontFamily = dongleRegular,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 352.dp, top = 16.75.dp)  // Adjust position as needed
        )
        // ========================================================================================================================================= >


        // ========================================================================================================================================= > // BACKDROP OVERLAY - Top-right corner with custom rounded bottom-left corner
        if (!backdropUrl.isNullOrBlank() && backdropUrl != "N/A" && !hasTrailerBeenPlayed) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = "TVShowsDetailsScreenCompose backdrop",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(519.dp)
                    .height(292.dp)
                    .clip(AppShapes.bottomLeftRoundedShape),
                contentScale = ContentScale.Crop,
                onLoading = { Log.d("BackdropDebug", "⏳ Backdrop loading...") },
                onSuccess = { Log.d("BackdropDebug", "✅ Backdrop loaded!") },
                onError = { e -> Log.e("BackdropDebug", "❌ Failed: ${e.result.throwable.message}") }
            )
        }


        // Keep this near the top of the Box:
        var trailerPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
        val currentTrailerPlayer by rememberUpdatedState(trailerPlayer)

        // ✅ Make sure we release ExoPlayer when this composable leaves the tree
        DisposableEffect(Unit) {
            onDispose {
                currentTrailerPlayer?.release()
            }
        }

        // This renders BEHIND the gradient overlay due to Box ordering
        if (!trailerUrl.isNullOrBlank()) {
            DetailsTrailerPlayer(
                trailerUrl = trailerUrl,
                isPlaying = isTrailerPlaying,
                showPlayerView = hasTrailerBeenPlayed,
                shouldExtract = shouldExtractTrailer,
                onPlaybackStateChanged = { playing ->
                    isTrailerPlaying = playing
                },
                onPlayerReady = { player ->
                    trailerPlayer = player
                    isExtractingTrailer = false  // ✅ Extraction complete
                },
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }



        if (!backdropUrl.isNullOrBlank() && backdropUrl != "N/A") {
            // GRADIENT OVERLAY - Add this right after the backdrop
            Image(
                painter = painterResource(id = R.drawable.backdrop_details_gradient),
                contentDescription = "Gradient overlay",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(569.dp)
                    .height(362.dp),
                contentScale = ContentScale.FillBounds
            )
        }
        // ========================================================================================================================================= >


        // ========================================================================================================================================= > logo parsing - it's already a full URL
        val fullLogoUrl = remember(logoUrl) {
            when {
                logoUrl.isNullOrBlank() -> {
                    Log.d("LogoDebug", "❌ Logo URL is null or blank")
                    null
                }
                logoUrl == "N/A" -> {
                    Log.d("LogoDebug", "❌ Logo URL is N/A")
                    null
                }
                else -> {
                    Log.d("LogoDebug", "✅ Logo URL: $logoUrl")
                    logoUrl // It's already a complete URL!
                }
            }
        }

        if (fullLogoUrl != null) {
            Log.d("LogoDebug", "🖼️ Rendering logo: $fullLogoUrl")
            AsyncImage(
                model = fullLogoUrl,
                contentDescription = "Logo",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 25.dp, start = 40.dp)
                    .size(width = 180.dp, height = 100.dp),
                contentScale = ContentScale.Fit,
                onLoading = {
                    Log.d("LogoDebug", "⏳ Logo loading...")
                },
                onSuccess = {
                    Log.d("LogoDebug", "✅ Logo loaded successfully!")
                },
                onError = { error ->
                    Log.e("LogoDebug", "❌ Logo failed to load: ${error.result.throwable.message}")
                }
            )
        } else {
            Log.d("LogoDebug", "❌ No logo to display — rendering title instead")
            val titleFontSize = remember(mediaTitle) {
                when {
                    mediaTitle.length <= 20 -> 41.sp
                    mediaTitle.length <= 23 -> 35.sp
                    mediaTitle.length <= 33 -> 32.sp
                    mediaTitle.length <= 40 -> 30.sp
                    else -> 25.sp
                }
            }
            val wrappedTitle = remember(mediaTitle) {
                val words = mediaTitle.split(" ")
                val lines = mutableListOf<String>()
                var currentLine = ""
                for (word in words) {
                    val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (candidate.length <= 10) {
                        currentLine = candidate
                    } else {
                        if (currentLine.isNotEmpty()) lines.add(currentLine)
                        currentLine = word
                    }
                }
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                lines.joinToString("\n")
            }

            val lineCount = remember(wrappedTitle) { wrappedTitle.count { it == '\n' } + 1 }
            val titleTopPadding = remember(lineCount) {
                when (lineCount) {
                    1 -> 40.dp
                    2 -> 25.dp
                    else -> 8.dp
                }
            }

            Text(
                text = wrappedTitle,
                color = Color.White.copy(alpha = 0.88f),
                fontSize = titleFontSize,
                lineHeight = titleFontSize * 0.87f,
                fontFamily = AppTypography.lalezarRegular,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = titleTopPadding, start = 30.dp)
                    .width(200.dp),
                textAlign = TextAlign.Center,
                maxLines = 6,
                overflow = TextOverflow.Clip
            )
        }
        // ========================================================================================================================================= >



        // ========================================================================================================================================= > // Revenue
        Text(
            text = seasonsLabel,
            color = Color(0xFF8B8B8B),
            fontSize = 17.sp,
            fontFamily = dongleRegular,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 258.25.dp, top = 34.dp)
        )
        // ========================================================================================================================================= >


        // ========================================================================================================================================= > // Budget
        Text(
            text = statusLabel,
            color = Color(0xFF8B8B8B),
            fontSize = 17.sp,
            fontFamily = dongleRegular,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 258.25.dp, top = 48.dp)
        )
        // ========================================================================================================================================= >


        // ========================================================================================================================================= > // release_date
        Text(
            text = releaseDateLabel,
            color = Color(0xFF8B8B8B),
            fontSize = 16.sp,
            fontFamily = dongleRegular,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 258.25.dp, top = 63.dp)
        )

        // ========================================================================================================================================= >


        // ========================================================================================================================================= > // original language
        Text(
            text = languageLabel,
            color = Color(0xFF8B8B8B),
            fontSize = 17.sp,
            fontFamily = dongleRegular,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 258.25.dp, top = 75.dp)
        )
        // ========================================================================================================================================= >


        // ========================================================================================================================================= > // Genres
        if (!genres.isNullOrBlank() && genres != "N/A") {
            Text(
                text = genres,
                color = Color(0xFF8B8B8B),
                fontSize = 16.sp,
                fontFamily = dongleRegular,
                lineHeight = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 258.25.dp, top = 96.dp)
                    .width(160.dp)  // Add width constraint so it wraps nicely
            )
        }else {
            Log.d("GenresDebug", "❌ Not rendering genres - value: '$genres'")
        }
        // ========================================================================================================================================= >



        // ========================================================================================================================================= > Overview text display with dynamic font sizing
        if (!overview.isNullOrBlank()) {
            var fontSize by remember { mutableStateOf(18.sp) }
            var lineHeight by remember { mutableStateOf(14.sp) }

            // Measure text to determine if we need smaller font
            val textMeasurer = rememberTextMeasurer()

            LaunchedEffect(overview) {
                val textLayoutResult = textMeasurer.measure(
                    text = androidx.compose.ui.text.AnnotatedString(overview),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 18.sp,
                        fontFamily = dongleRegular,
                        lineHeight = 14.sp  // Include line height in measurement
                    ),
                    constraints = androidx.compose.ui.unit.Constraints(
                        maxWidth = (400 * 2.75).toInt(), // dp to px approximation
                        maxHeight = (105.75 * 2.75).toInt() // Add height constraint!
                    )
                )

                // Check if text overflows OR doesn't fit in the height
                if (textLayoutResult.lineCount > 5 || textLayoutResult.didOverflowHeight) {
                    fontSize = 16.sp
                    lineHeight = 13.7.sp
                } else {
                    lineHeight = 14.sp
                }
            }

            Text(
                text = overview,
                color = Color(0xFF8B8B8B),
                fontSize = fontSize,
                fontFamily = dongleRegular,
                lineHeight = lineHeight,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 28.25.dp, top = 175.dp)
                    //.background(Color.Red.copy(alpha = 0.5f))  // Debug background
                    .height(108.25.dp)
                    .width(400.dp),
                overflow = TextOverflow.Ellipsis
            )
        }
        // ========================================================================================================================================= >



        // Favorite Button - Uses internal offset for positioning
        TVShowsFavoriteButton(
            fontFamily = passionFont,
            focusRequester = favoriteFocusRequester,
            enableFocus = !blockHeaderInitialFocus,
            isFavorited = isFavorited,
            onToggle = {
                if (favoriteProfileId != -1) {
                    coroutineScope.launch {
                        try {
                            val result = ApiClient.bluehiveApi.toggleFavorite(
                                FavoriteRequest(
                                    profile_id = favoriteProfileId,
                                    media_tmdb_id = tmdbId,
                                    media_type = mediaType,
                                    media_title = mediaTitle,
                                )
                            )
                            isFavorited = result.is_favorited
                        } catch (e: Exception) {
                            Log.e("Favorites", "Toggle failed: ${e.message}")
                        }
                    }
                }
            }
        )

        TVShowsExtrasButton(
            fontFamily = passionFont,
            focusRequester = extrasFocusRequester,
            isToggled = isExtrasToggled,          // 🔹 NEW
            enableFocus = !blockHeaderInitialFocus,
            onClick = {
                isExtrasToggled = !isExtrasToggled

                // Load recommendations when toggled on
                if (isExtrasToggled && recommendations.isEmpty()) {
                    coroutineScope.launch {                            // runs on Main
                        val result = recommendationsRepository.getRecommendations(tmdbId, mediaType)
                        recommendations = result                       // safe: returns on Main
                    }
                }
            }
        )



        TVShowsTrailerButton(
            fontFamily = passionFont,
            focusRequester = trailerFocusRequester,
            isPlaying = isTrailerPlaying,
            trailerAvailable = !trailerUrl.isNullOrBlank(),
            enableFocus = !blockHeaderInitialFocus,
            onClick = {
                if (!trailerUrl.isNullOrBlank()) {
                    if (!hasTrailerBeenPlayed) {
                        shouldExtractTrailer = true
                        isExtractingTrailer = true  // ✅ Show loading state
                        hasTrailerBeenPlayed = true
                    }
                    isTrailerPlaying = !isTrailerPlaying
                }
            }
        )

        // Optional: Show loading indicator
        if (isExtractingTrailer) {
            Text(
                text = "Loading trailer...",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 150.dp, end = 250.dp)
            )
        }


        TVShowsRewindButton(
            fontFamily = passionFont,
            focusRequester = remember { FocusRequester() },
            enableFocus = !blockHeaderInitialFocus,
            onClick = {
                if (isTrailerPlaying) {
                    seekBackward(trailerPlayer)
                }
            }
        )

        TVShowsFastForwardButton(
            fontFamily = passionFont,
            focusRequester = remember { FocusRequester() },
            enableFocus = !blockHeaderInitialFocus,
            onClick = {
                if (isTrailerPlaying) {
                    seekForward(trailerPlayer)
                }
            }
        )


        val app = context.applicationContext as? BlueHiveApplication

        // ✅ Conditionally show StreamerButtons OR RecommendedMediaSection
        if (!isExtrasToggled) {
            Image(
                painter = painterResource(id = R.drawable.top_lower_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(width = 132.dp, height = 7.75.dp)
                    .offset(x = 32.7.dp, y = 321.6.dp),
                contentScale = ContentScale.Fit
            )

            Image(
                painter = painterResource(id = R.drawable.bottom_lower_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(width = 132.dp, height = 7.75.dp)
                    .offset(x = 32.7.dp, y = 499.25.dp),
                contentScale = ContentScale.Fit
            )

                    // 🔴 Inline Seasons LazyRow (no separate TVSeasonsRow composable)
                    if (numberOfSeasons > 0) {
                        val seasons = remember(numberOfSeasons) { (1..numberOfSeasons).toList() }
                        val listState = seasonsListState

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(x = 28.5.dp, y = 313.25.dp)   // tweak these to line up visually
                                .width(180.25.dp)
                                .height(201.5.dp)
                                //.background(Color.Red)            // debug; remove later
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement  = Arrangement.spacedBy((-6).dp),   // ① between items
                                contentPadding = PaddingValues(horizontal = 11.5.dp, vertical = 19.5.dp)      // ② padding at edges and top margin
                            ) {

                                itemsIndexed(seasons) { seasonIndex, seasonNumber ->
                                    SeasonButton(
                                        label = "Season $seasonNumber",
                                        isSelected = (seasonNumber == selectedSeason),
                                        fontFamily = passionFont,
                                        focusRequester = seasonFocusRequesters[seasonIndex],
                                                modifier = Modifier.onKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown) {
                                                when (event.key) {
                                                    Key.DirectionDown, Key.DirectionUp -> {
                                                        BlueHiveApplication.playTitleCardNavigation()
                                                        false
                                                    }
                                                    Key.DirectionRight -> {
                                                        if (episodes.isNotEmpty() && episodeFocusRequesters.isNotEmpty()) {
                                                            BlueHiveApplication.playHoverSound()

                                                            val targetIndex = lastFocusedEpisodeIndex
                                                                .coerceIn(0, episodeFocusRequesters.lastIndex)

                                                            coroutineScope.launch {
                                                                episodesRowState.scrollToItem(targetIndex)
                                                                episodeFocusRequesters
                                                                    .getOrNull(targetIndex)
                                                                    ?.requestFocus()
                                                                    ?: episodeFocusRequesters.first().requestFocus()
                                                            }

                                                            true
                                                        } else {
                                                            false
                                                        }
                                                    }
                                                    else -> false
                                                }
                                            } else {
                                                false
                                            }
                                        },
                                        onClick = {
                                            if (selectedSeason != seasonNumber) {
                                                selectedSeason = seasonNumber
                                                loadSeasonEpisodes(seasonNumber)
                                            }
                                        },
                                    )
                                }

                            }
                        }
                    }

            Image(
                painter = painterResource(id = R.drawable.top_upper_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(width = 140.25.dp, height = 30.5.dp)
                    .offset(x = 28.25.dp, y = 299.dp),
                contentScale = ContentScale.Fit
            )

            Image(
                painter = painterResource(id = R.drawable.bottom_upper_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(width = 140.25.dp, height = 30.5.dp)
                    .offset(x = 28.25.dp, y = 499.25.dp),
                contentScale = ContentScale.Fit
            )




            // ===============================================================================> episode lAZY ROW DECORATIONS
            Image(
                painter = painterResource(id = R.drawable.mini_left_lower_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(width = 7.75.dp, height = 115.dp)
                    .offset(x = 239.7.dp, y = 302.75.dp),
                //.padding(start = 240.75.dp, top = 299.dp),
                contentScale = ContentScale.Fit
            )

            Image(
                painter = painterResource(id = R.drawable.mini_right_lower_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(width = 7.75.dp, height = 115.dp)
                    .offset(x = 902.75.dp, y = 302.75.dp),
                //.padding(start = 240.75.dp, top = 299.dp),
                contentScale = ContentScale.Fit
            )

            // ================================================================> episode lazy row
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 236.dp, y = 299.5.dp) // adjust to sit between side decorations
                    .width(683.75.dp)
                    .height(260.dp) //122.25
                //.background(Color.Red)
            ) {
                when {
                    isLoadingEpisodes -> {
                        // Simple loading state (optional)
                        Text(
                            text = "Loading episodes...",
                            color = Color(0xFFAAAAAA),
                            fontFamily = dongleRegular,
                            fontSize = 23.sp,
                            modifier = Modifier.align(Alignment.TopCenter)
                                .offset(y = 45.dp)
                        )
                    }

                    episodes.isEmpty() -> {
                        // Optional empty state
                        Text(
                            text = "No episodes found for Season $selectedSeason",
                            color = Color(0xFFAAAAAA),
                            fontFamily = dongleRegular,
                            fontSize = 23.sp,
                            modifier = Modifier.align(Alignment.TopCenter)
                                .offset(y = 45.dp)
                        )
                    }

                    else -> {
                        val totalEpisodes = episodes.size

                        // ✅ "Exposure" style darkening using a color matrix (no alpha usage)
                        val darkenFilter = remember {
                            ColorFilter.colorMatrix(
                                ColorMatrix().apply {
                                    // Lower RGB scale = darker image
                                    setToScale(0.40f, 0.40f, 0.40f, 1f)
                                }
                            )
                        }

                        // The border diapers whenever the still path is focused
                        val episodeShape = remember { RoundedCornerShape(7.5.dp) }

                        val baseStillModifier = remember(episodeShape) {
                            Modifier
                                .width(174.27.dp)
                                .height(97.5.dp)
                                .clip(episodeShape)
                        }


                        LazyRow(
                            state = episodesRowState,
                            userScrollEnabled = selectedEpisodeOverlayIndex == -1,
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(
                                start = 18.dp,   // same as before
                                end = 40.dp,     // ⬅ bump this up until the last card clears the decor nicely
                                top = 12.dp,
                                bottom = 12.dp
                            )
                        ) {

                            itemsIndexed(
                                items = episodes,
                                key = { _, ep -> ep.tmdbEpisodeId }
                            ) { index, episode ->

                                var isEpisodeFocused by remember { mutableStateOf(false) }

                                val isEpisodeSelectOverlay = selectedEpisodeOverlayIndex == index

                                // Try the episode still first, fallback to show's backdropUrl
                                val stillUrl = episode.stillImageUrl ?: backdropUrl

                                val titleText = episode.title

                                // ✅ If your Episode model uses a different field name, change this line.
                                val episodeOverviewText = episode.overview ?: ""


                                // 👇 true only when the whole item is within the viewport
                                val isFullyVisible by remember(episodesRowState, index) {
                                    derivedStateOf {
                                        val layoutInfo = episodesRowState.layoutInfo
                                        val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                                            ?: return@derivedStateOf false

                                        val itemStart = itemInfo.offset
                                        val itemEnd   = itemInfo.offset + itemInfo.size

                                        val viewportStart = layoutInfo.viewportStartOffset
                                        val viewportEnd   = layoutInfo.viewportEndOffset

                                        itemStart >= viewportStart && itemEnd <= viewportEnd
                                    }
                                }

                                // ✅ This outer column is the "focus target"
                                //    It gives the title real space UNDER the still image.
                                Column(
                                    modifier = Modifier
                                        // keep item layout sized to the still image (prevents spacing weirdness)
                                        .width(174.27.dp)
                                        .focusRequester(episodeFocusRequesters[index])
                                        .onFocusChanged { state ->
                                            // hasFocus stays true when any child (like provider buttons) is focused
                                            isEpisodeFocused = state.hasFocus
                                            if (state.hasFocus) {
                                                lastFocusedEpisodeIndex = index
                                                episodeFocusLanded = true  // confirms initial focus actually landed
                                            }
                                        }

                                        .onKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown) {
                                                when (event.key) {
                                                    Key.Back -> {
                                                        when {
                                                            selectedEpisodeOverlayIndex == index -> {
                                                                selectedEpisodeOverlayIndex = -1
                                                                true
                                                            }
                                                            numberOfSeasons > 1 && seasonFocusRequesters.isNotEmpty() -> {
                                                                val seasonIdx = (selectedSeason - 1)
                                                                    .coerceIn(0, seasonFocusRequesters.lastIndex)
                                                                BlueHiveApplication.playHoverSound()
                                                                // Don't assume the season button is attached. On the
                                                                // Continue Watching path the episode tile is focused
                                                                // programmatically under load, before the season
                                                                // LazyColumn has placed its buttons — a direct
                                                                // requestFocus() no-ops and Back appears dead. Scroll
                                                                // the season in, wait for layout, then focus.
                                                                coroutineScope.launch {
                                                                    focusSeasonReliably(seasonIdx)
                                                                }
                                                                true
                                                            }
                                                            else -> false
                                                        }
                                                    }


                                                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                                                        BlueHiveApplication.playClickSound()
                                                        selectedEpisodeOverlayIndex = index
                                                        true
                                                    }


                                                    Key.DirectionDown -> {
                                                        if (selectedEpisodeOverlayIndex == index) {
                                                            // move focus into the provider grid
                                                            providerFocusRequesters.firstOrNull()?.requestFocus()
                                                            true
                                                        } else {
                                                            // still block DOWN when overlay isn't open
                                                            true
                                                        }
                                                    }
                                                    Key.DirectionLeft, Key.DirectionRight -> {
                                                        if (selectedEpisodeOverlayIndex == index) {
                                                            true
                                                        } else {
                                                            BlueHiveApplication.playTitleCardNavigation()
                                                            false
                                                        }
                                                    }
                                                    else -> false
                                                }
                                            } else {
                                                false
                                            }
                                        }
                                        .focusable()
                                ) {

                                    // ✅ Image/BADGE/GLOW stack stays inside this Box
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .width(174.27.dp)
                                            .height(97.5.dp)
                                            .onGloballyPositioned { coords ->
                                                // Only care about the box for the *selected* episode’s overlay
                                                if (isEpisodeSelectOverlay) {
                                                    overlayAnchor = coords.positionInRoot()
                                                }
                                            }
                                    ) {

                                        // ✅ Glow behind image when focused
                                        if (isEpisodeFocused && !isEpisodeSelectOverlay) {
                                            Image(
                                                painter = painterResource(id = R.drawable.episode_focus_glow),
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .offset(x = (-0.25).dp, y = (-0.3).dp) // offset the position of the glow
                                                    .wrapContentSize(unbounded = true) // allow the glow to exceed the layout bounds
                                                    // force exact glow size even if parent is smaller
                                                    .requiredSize(width = 206.3.dp, height = 129.dp),  // length and width of the focus glow
                                                contentScale = ContentScale.FillBounds
                                            )
                                        }

                                        // ✅ Still image centered over glow
                                        if (!stillUrl.isNullOrBlank()) {

                                            val stillModifier = baseStillModifier.then(
                                                if (!isEpisodeFocused || isEpisodeSelectOverlay) {
                                                    Modifier.border(
                                                        width = 1.1.dp,
                                                        color = Color(0xFF505050), // border color
                                                        shape = episodeShape
                                                    )
                                                } else {
                                                    Modifier
                                                }
                                            )

                                            AsyncImage(
                                                model = stillUrl,
                                                contentDescription = episode.title,
                                                modifier = stillModifier,
                                                contentScale = ContentScale.Crop,
                                                colorFilter = if (!isEpisodeFocused) darkenFilter else null
                                            )

                                            // ✅ Episode badge – bottom-left overlay, on top of still
                                            EpisodeBadge(
                                                current = episode.episodeNumber,
                                                total = totalEpisodes,
                                                isFocused = isEpisodeFocused,
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(start = 4.dp, bottom = 76.5.dp)
                                            )
                                        }


                                        if (isEpisodeSelectOverlay) {  // overlay for when a episode is selected
                                            Box(
                                                modifier = Modifier
                                                    .matchParentSize()
                                                    .clip(episodeShape)
                                                    .background(Color.Black)
                                                    // ✅ re-add border ON TOP of the overlay
                                                    .border(
                                                        width = 1.1.dp,
                                                        color = Color(0xFF505050),
                                                        shape = episodeShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "SELECT A SITE BELOW TO WATCH EPISODE ON",
                                                    fontFamily = passionFont,
                                                    fontSize = 20.sp,
                                                    color = Color.White,
                                                    maxLines = 3,
                                                    overflow = TextOverflow.Ellipsis,
                                                    textAlign = TextAlign.Center,
                                                    // ✅ add breathing room left/right like your Figma
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 14.dp)
                                                )
                                            }
                                        }
                                    }



                                    // ✅ Episode title under each respective image
                                    // Darken state whenever the episode isn't focused
                                    val titleYOffset by animateDpAsState(
                                        targetValue = if (isEpisodeFocused) 1.5.dp else (-1.5).dp,
                                        label = "episodeTitleYOffset"
                                    )

                                    if (!isEpisodeSelectOverlay && isFullyVisible) {
                                        Text(
                                            text = titleText,
                                            fontFamily = dongleRegular,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (!isEpisodeFocused) Color(0xFF737373) else Color.White,
                                            modifier = Modifier
                                                .offset(x = 4.dp, y = titleYOffset)
                                        )
                                    }

                                    // ✅ NEW: Overview appears only when focused, with 1.5s delayed fade-in
                                    var showOverview by remember(index) { mutableStateOf(false) }

                                    LaunchedEffect(isEpisodeFocused, episodeOverviewText, isEpisodeSelectOverlay) {
                                        if (!isEpisodeSelectOverlay && isEpisodeFocused && episodeOverviewText.isNotBlank()) {
                                            showOverview = false
                                            delay(1500)
                                            if (isEpisodeFocused) {
                                                showOverview = true
                                            }
                                        } else {
                                            showOverview = false
                                        }
                                    }

                                    AnimatedVisibility(
                                        visible = showOverview && !isEpisodeSelectOverlay,
                                        enter = fadeIn(animationSpec = tween(durationMillis = 280)),
                                        exit = fadeOut(animationSpec = tween(durationMillis = 160))
                                    ) {
                                        val overviewAlpha by animateFloatAsState(
                                            targetValue = if (showOverview) 1f else 0f,
                                            animationSpec = tween(
                                                durationMillis = if (showOverview) 420 else 260,
                                                easing = LinearOutSlowInEasing
                                            ),
                                            label = "overviewAlpha"
                                        )

                                        Box(
                                            modifier = Modifier
                                                .padding(start = 20.dp, top = 6.dp)
                                                .offset(x = (-9.7).dp, y = (-2).dp) // position of the overview background
                                                .wrapContentSize(unbounded = true)
                                                .graphicsLayer { alpha = overviewAlpha }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .requiredWidth(196.dp)
                                                    .heightIn(max = 98.5.dp)
                                                    .background(
                                                        color = Color(0xFF949494).copy(alpha = 0.20f), // opacity of the overview background
                                                        shape = RoundedCornerShape(6.5.dp)
                                                    )
                                                    .padding(start = 6.dp, top = 2.dp, end = 5.dp, bottom = 0.dp)
                                            ) {
                                                Text(
                                                    text = episodeOverviewText,
                                                    fontFamily = dongleRegular,
                                                    lineHeight = 11.sp,
                                                    fontSize = 13.sp,
                                                    color = Color(0xFFE7E3E3),
                                                    maxLines = 8,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }

                                }
                            }

                        }
                    }
                }
            }
            // ================================================================> episode lazy row



            Image(
                painter = painterResource(id = R.drawable.mini_left_upper_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(width = 30.5.dp, height = 122.25.dp)
                    .offset(x = 216.75.dp, y = 299.dp),
                //.padding(start = 240.75.dp, top = 299.dp),
                contentScale = ContentScale.Fit
            )

            Image(
                painter = painterResource(id = R.drawable.mini_right_upper_body),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(width = 30.5.dp, height = 122.25.dp)
                    .offset(x = 902.75.dp, y = 299.dp),
                //.padding(start = 240.75.dp, top = 299.dp),
                contentScale = ContentScale.Fit
            )
            // ===============================================================================> episode lAZY ROW DECORATIONS


            if (
                !isExtrasToggled &&
                selectedEpisodeOverlayIndex != -1 &&
                overlayAnchor != null &&
                selectedEpisodeOverlayIndex in episodes.indices
            ) {
                val anchor = overlayAnchor!!

                val selectedEpisode = episodes[selectedEpisodeOverlayIndex]

                // fall back to index+1 if episodeNumber is null
                val episodeNumber = selectedEpisode.episodeNumber

                // Build sources for this episode.
                // Japanese animation → 2 buttons (Miruro + VidFast); else the standard set.
                val episodeSources by produceState(
                    initialValue = emptyList(),
                    mediaType, mediaId, selectedSeason, episodeNumber, genres, originalLanguage
                ) {
                    value = if (isJapaneseAnimation(genres, originalLanguage)) {
                        buildJapaneseAnimationSources(
                            mediaType     = mediaType,
                            tmdbId        = mediaId,
                            seasonNumber  = selectedSeason,
                            episodeNumber = episodeNumber,
                        ) { c, n, u -> TVShowsDetailsScreenCompose.StreamingSource(c, n, u) }
                    } else {
                        getStreamingSources(
                            mediaType = mediaType,
                            mediaId = mediaId,
                            seasonNumber = selectedSeason,
                            episodeNumber = episodeNumber
                        )
                    }
                }

                val gridOffsetX = with(density) { anchor.x.toDp() }
                val gridOffsetY = with(density) {
                    (anchor.y + 97.5.dp.toPx() + 12.dp.toPx()).toDp()
                }

                Box(
                    modifier = Modifier
                        .offset(x = gridOffsetX, y = gridOffsetY)
                ) {
                    if (isJapaneseAnimation(genres, originalLanguage)) {
                        // Anime: exactly DuB / SuB. DuB+SuB run the Miruro flow
                        // (the URL already carries ?ep=, so the extractor clicks
                        // the right episode).
                        val miruroUrl = episodeSources.firstOrNull { it.name.equals("Miruro", true) }?.url
                        AnimeEpisodeProviderButtons(
                            passionFont     = passionFont,
                            miruroUrl       = miruroUrl,
                            focusRequesters = providerFocusRequesters,
                            onDub = { url ->
                                val epName = selectedEpisode.title
                                onEpisodeSelected(selectedSeason, episodeNumber, epName)
                                lastStreamFocusRequester = providerFocusRequesters.getOrNull(0)
                                pendingUrl         = url
                                pendingDub         = true
                                pendingSeason      = selectedSeason
                                pendingEpisode     = episodeNumber
                                pendingEpisodeName = epName
                                showDefaultPrompt  = true
                            },
                            onSub = { url ->
                                val epName = selectedEpisode.title
                                onEpisodeSelected(selectedSeason, episodeNumber, epName)
                                lastStreamFocusRequester = providerFocusRequesters.getOrNull(1)
                                pendingUrl         = url
                                pendingDub         = false
                                pendingSeason      = selectedSeason
                                pendingEpisode     = episodeNumber
                                pendingEpisodeName = epName
                                showDefaultPrompt  = true
                            },
                            onBack = {
                                selectedEpisodeOverlayIndex = -1
                                val targetIndex = lastFocusedEpisodeIndex
                                    .coerceIn(0, episodeFocusRequesters.lastIndex)
                                episodeFocusRequesters[targetIndex].requestFocus()
                            }
                        )
                    } else {
                        EpisodeProviderInlineButtons(
                            passionFont = passionFont,
                            sources = episodeSources,          // ✅ now episode-specific
                            focusRequesters = providerFocusRequesters,
                            onSourceSelected = { source ->
                                val epName = selectedEpisode.title
                                onEpisodeSelected(selectedSeason, episodeNumber, epName)
                                onStreamingSourceSelected(source)
                            },
                            onBack = {
                                // still close overlay + return focus on BACK
                                selectedEpisodeOverlayIndex = -1

                                val targetIndex = lastFocusedEpisodeIndex
                                    .coerceIn(0, episodeFocusRequesters.lastIndex)

                                episodeFocusRequesters[targetIndex].requestFocus()
                            }
                        )
                    }
                }
            }



        } else {
            // ✅ Show recommended media section
            if (recommendations.isNotEmpty()) {
                RecommendedMediaSection(
                    recommendations = recommendations,

                        onMediaClick = { media ->
                    isTrailerPlaying = false
                    hasTrailerBeenPlayed = false
                    coroutineScope.launch {
                        // Fire both requests concurrently during the button animation delay
                        val episodesDeferred = if (media.mediaType == "tv") {
                            async {
                                try { episodesRepository.getSeasonEpisodes(media.tmdbId, 1).episodes }
                                catch (_: Exception) { null }
                            }
                        } else null

                        val recsDeferred = async {
                            try { recommendationsRepository.getRecommendations(media.tmdbId,
                                media.mediaType
                            ) }
                            catch (_: Exception) { emptyList() }
                        }

                        delay(1000) // Let button animation complete + give requests time to resolve

                        app?.storePrefetch(
                            BlueHiveApplication.MediaPrefetchData(
                                tmdbId          = media.tmdbId,
                                mediaType       = media.mediaType,
                                episodes        = episodesDeferred?.await(),
                                recommendations = recsDeferred.await(),
                            )
                        )

                        onMediaClick(media)
                    }
                },
                    onNavigateUp = {
                        BlueHiveApplication.playHoverSound()
                        extrasFocusRequester.requestFocus()
                        Log.d("SeekDebug", "R.drawable.button_focus_narrow_nonglow should be the extra button's focus glow")
                    }
                )
            }
        }


        // ════════════════════════════════════════════════════════════════════
        //  Anime DuB/SuB overlays — last so they render on top of everything
        // ════════════════════════════════════════════════════════════════════
        if (isExtracting) {
            ExtractionLoadingOverlay(
                status = when (extractStage) {
                    0, 1 -> "Locating stream source…"
                    2    -> "Loading stream source…"
                    3    -> "Preparing video player…"
                    else -> "Almost there…"
                },
                stage = extractStage
            )
        }

        if (showNoDub) {
            Text(
                "No dub available for this episode",
                color = Color.White,
                fontSize = 18.sp,
                fontFamily = dongleRegular,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 70.dp)
                    .background(Color(0xE6000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 26.dp, vertical = 12.dp)
            )
        }

        if (isCheckingServers) {
            ExtractionLoadingOverlay(
                status = "Checking the site for available servers…",
                stage  = 2
            )
        }

        if (showDefaultPrompt) {
            DefaultServerPrompt(
                onDefault = {
                    showDefaultPrompt = false
                    coroutineScope.launch {
                        awaitFrame()
                        try { lastStreamFocusRequester?.requestFocus() } catch (_: Exception) {}
                    }
                    pendingUrl?.let { launchExtract(it, pendingDub, null) }
                },
                onChoose = {
                    showDefaultPrompt = false
                    coroutineScope.launch {
                        awaitFrame()
                        try { lastStreamFocusRequester?.requestFocus() } catch (_: Exception) {}
                    }
                    // If the default extraction already captured this episode's
                    // server list, skip the enumerate webview pass and open the
                    // picker instantly. Otherwise fall back to enumerating.
                    val cached = pendingUrl?.let { serverListCache[serverCacheKey(it, pendingDub)] }
                    if (cached != null) {
                        Log.d("scraper", "🗂 server picker from cache (${cached.first.size} servers)")
                        serverOptions    = cached.first
                        serverDefaultIdx = cached.second
                        showServerPicker = true
                    } else {
                        pendingUrl?.let { launchEnumerate(it, pendingDub) }
                    }
                }
            )
        }

        if (showServerPicker && serverOptions.isNotEmpty()) {
            ServerPickerOverlay(
                servers      = serverOptions,
                defaultIndex = serverDefaultIdx,
                audioLabel   = if (pendingDub) "Dub" else "Sub",
                onSelect     = { serverName ->
                    showServerPicker = false
                    coroutineScope.launch {
                        awaitFrame()
                        try { lastStreamFocusRequester?.requestFocus() } catch (_: Exception) {}
                    }
                    pendingUrl?.let { launchExtract(it, pendingDub, serverName) }
                }
            )
        }

        if (showServerError) {
            Text(
                "Couldn't reach the site — please try again",
                color = Color.White,
                fontSize = 18.sp,
                fontFamily = dongleRegular,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 70.dp)
                    .background(Color(0xE6000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 26.dp, vertical = 12.dp)
            )
        }

    }
}






@Composable
fun TVShowsFavoriteButton(
    fontFamily: FontFamily,
    focusRequester: FocusRequester,
    offsetX: Dp = 27.75.dp,
    offsetY: Dp = 139.dp,
    enableFocus: Boolean = true,
    isFavorited: Boolean = false,
    onToggle: () -> Unit = {}
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(
            text = "+ Add to Favorites",
            toggledText = "- Remove from Favorites"
        ),
        isToggleable = true,
        fontFamily = fontFamily,
        focusRequester = focusRequester,
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .focusProperties { canFocus = enableFocus }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionLeft ||
                            event.key == Key.DirectionRight ||
                            event.key == Key.DirectionDown)
                ) {
                    // 🔇 No sound when pressing LEFT on the leftmost button
                    if (event.key != Key.DirectionLeft) {
                        BlueHiveApplication.playHoverSound()
                    }
                }
                false // let focus system handle movement
            },
        dimensions = ModularButtonDimensions(
            mainWidth = 146.dp,
            mainHeight = 24.dp,
            mainYOffset = 0.5.dp,
            secondWidth = 146.dp,
            secondHeight = 22.dp,
            secondYOffset = 7.dp,
            shadowHeight = 6.dp,
            glowWidth = 168.dp,
            glowHeight = 44.dp,
            mainCornerRadius = 7f,
            secondCornerRadius = 8f,
        ),
        colors = ModularButtonColors(
            mainDefault = Color(0xFF6C6C6C),
            mainToggled = Color(0xFF535C67),
            secondDefault = Color(0xFF2E2C37),
            secondFocused = Color(0xFF52505A),
            shadowColor = Color(0x50000000),
            textFocused = Color.White,
            textUnfocused = Color(0xFFBEBEBE)
        ),
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX = (146.dp - 168.dp) / 2,
            offsetY = (-4).dp
        ),
        animationConfig = ModularButtonAnimationConfig(
            pressOffset = 2.5.dp,
            textOffsetDefault = 1.dp,
            textOffsetPressed = 2.4.dp,
            durationMillis = 110,
            bounceBackDelayMillis = 80
        ),
        // 🔇 Disable internal pop sound (we'll use SoundPool instead)
        externalToggled = isFavorited,
        onClick = {
            BlueHiveApplication.playClickSound()
            onToggle()
        }
    )
}




@Composable
fun TVShowsExtrasButton(
    fontFamily: FontFamily,
    focusRequester: FocusRequester,
    isToggled: Boolean,                 // 🔹 NEW PARAM
    offsetX: Dp = 182.dp,
    offsetY: Dp = 139.dp,
    enableFocus: Boolean = true,
    onClick: () -> Unit = {},
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(
            text = "Extras",
            toggledText = "Providers"
        ),
        isToggleable = true,
        fontFamily = fontFamily,
        focusRequester = focusRequester,
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .focusProperties { canFocus = enableFocus }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionLeft ||
                            event.key == Key.DirectionRight ||
                            event.key == Key.DirectionDown)
                ) {
                    BlueHiveApplication.playHoverSound()
                }
                false
            },
        dimensions = ModularButtonDimensions(
            mainWidth = 49.5.dp,
            mainHeight = 24.dp,
            mainYOffset = 0.5.dp,
            secondWidth = 49.5.dp,
            secondHeight = 22.dp,
            secondYOffset = 7.dp,
            shadowHeight = 6.dp,
            glowWidth = 71.6.dp,
            glowHeight = 44.dp,
            mainCornerRadius = 7f,
            secondCornerRadius = 6f,
        ),
        colors = ModularButtonColors(
            mainDefault = Color(0xFF6C6C6C),
            mainToggled = Color(0xFF535C67),
            secondDefault = Color(0xFF2E2C37),
            secondFocused = Color(0xFF52505A),
            shadowColor = Color(0x50000000),
            textFocused = Color.White,
            textUnfocused = Color(0xFFBEBEBE)
        ),
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_narrow_glow,
            toggledRes = R.drawable.button_focus_narrow_nonglow,
            offsetX = (49.5.dp - 71.6.dp) / 2,
            offsetY = (-4).dp
        ),
        animationConfig = ModularButtonAnimationConfig(
            pressOffset = 2.5.dp,
            textOffsetDefault = 1.dp,
            textOffsetPressed = 2.4.dp,
            durationMillis = 110,
            bounceBackDelayMillis = 80
        ),
        externalToggled = isToggled,     // 🔹 NEW: hook into shared state
        onClick = {
            BlueHiveApplication.playClickSound()
            onClick()
        }
    )
}




@Composable
fun TVShowsTrailerButton(
    fontFamily: FontFamily,
    focusRequester: FocusRequester,
    isPlaying: Boolean = false,
    trailerAvailable: Boolean = true,
    offsetX: Dp =  256.25.dp,
    offsetY: Dp = 139.dp,
    enableFocus: Boolean = true,
    onClick: () -> Unit = {}
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(
            text = if (isPlaying) "Pause Trailer" else "Play Trailer",
            toggledText = if (isPlaying) "Play Trailer" else "Pause Trailer"
        ),
        isToggleable = false,
        fontFamily = fontFamily,
        focusRequester = focusRequester,
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .alpha(if (trailerAvailable) 1f else 0.5f)
            .focusProperties { canFocus = enableFocus }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionLeft ||
                            event.key == Key.DirectionRight ||
                            event.key == Key.DirectionDown)
                ) {
                    BlueHiveApplication.playHoverSound()
                }
                false
            },
        dimensions = ModularButtonDimensions(
            mainWidth = 100.dp,
            mainHeight = 24.dp,
            mainYOffset = 0.5.dp,
            secondWidth = 100.dp,
            secondHeight = 22.dp,
            secondYOffset = 7.dp,
            shadowHeight = 6.dp,
            glowWidth = 116.5.dp,
            glowHeight = 44.dp,
            mainCornerRadius = 7f,
            secondCornerRadius = 6f,
        ),
        colors = ModularButtonColors(
            mainDefault = Color(0xFF6C6C6C),
            mainToggled = Color(0xFF535C67),
            secondDefault = Color(0xFF2E2C37),
            secondFocused = Color(0xFF52505A),
            shadowColor = Color(0x50000000),
            textFocused = Color.White,
            textUnfocused = Color(0xFFBEBEBE)
        ),
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX = (100.dp - 116.5.dp) / 2,
            offsetY = (-4).dp
        ),
        animationConfig = ModularButtonAnimationConfig(
            pressOffset = 2.5.dp,
            textOffsetDefault = 1.dp,
            textOffsetPressed = 2.4.dp,
            durationMillis = 110,
            bounceBackDelayMillis = 80
        ),
        onClick = {
            BlueHiveApplication.playClickSound()
            onClick()
        }
    )
}


@Composable
fun TVShowsRewindButton(
    fontFamily: FontFamily,
    focusRequester: FocusRequester,
    offsetX: Dp = 367.25.dp,
    offsetY: Dp = 139.dp,
    enableFocus: Boolean = true,
    onClick: () -> Unit = {}
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(
            text = "<",
        ),
        isToggleable = false,
        fontFamily = fontFamily,
        focusRequester = focusRequester,
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .focusProperties { canFocus = enableFocus }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionLeft ||
                            event.key == Key.DirectionRight ||
                            event.key == Key.DirectionDown)
                ) {
                    BlueHiveApplication.playHoverSound()
                }
                false
            },
        dimensions = ModularButtonDimensions(
            mainWidth = 26.5.dp,
            mainHeight = 24.dp,
            mainYOffset = 0.5.dp,
            secondWidth = 26.5.dp,
            secondHeight = 22.dp,
            secondYOffset = 7.dp,
            shadowHeight = 6.dp,
            glowWidth = 41.dp,
            glowHeight = 44.dp,
            mainCornerRadius = 7f,
            secondCornerRadius = 6f,
        ),
        colors = ModularButtonColors(
            mainDefault = Color(0xFF6C6C6C),
            mainToggled = Color(0xFF535C67),
            secondDefault = Color(0xFF2E2C37),
            secondFocused = Color(0xFF52505A),
            shadowColor = Color(0x50000000),
            textFocused = Color.White,
            textUnfocused = Color(0xFFBEBEBE)
        ),
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_narrow_glow,
            toggledRes = R.drawable.button_focus_narrow_nonglow,
            offsetX = (-7.5).dp,
            offsetY = (-4).dp
        ),
        animationConfig = ModularButtonAnimationConfig(
            pressOffset = 2.5.dp,
            textOffsetDefault = 1.dp,
            textOffsetPressed = 2.4.dp,
            durationMillis = 110,
            bounceBackDelayMillis = 80
        ),
        onClick = {
            BlueHiveApplication.playClickSound()
            onClick()
        }
    )
}


@Composable
fun TVShowsFastForwardButton(
    fontFamily: FontFamily,
    focusRequester: FocusRequester,
    offsetX: Dp = 400.25.dp,
    offsetY: Dp = 139.dp,
    enableFocus: Boolean = true,
    onClick: () -> Unit = {}
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(
            text = ">",
        ),
        isToggleable = false,
        fontFamily = fontFamily,
        focusRequester = focusRequester,
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .focusProperties { canFocus = enableFocus }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionLeft ||
                            event.key == Key.DirectionRight ||
                            event.key == Key.DirectionDown)
                ) {
                    // 🔇 No sound when pressing RIGHT on the rightmost button
                    if (event.key != Key.DirectionRight) {
                        BlueHiveApplication.playHoverSound()
                    }
                }
                false
            },
        dimensions = ModularButtonDimensions(
            mainWidth = 26.5.dp,
            mainHeight = 24.dp,
            mainYOffset = 0.5.dp,
            secondWidth = 26.5.dp,
            secondHeight = 22.dp,
            secondYOffset = 7.dp,
            shadowHeight = 6.dp,
            glowWidth = 41.dp,
            glowHeight = 44.dp,
            mainCornerRadius = 7f,
            secondCornerRadius = 6f,
        ),
        colors = ModularButtonColors(
            mainDefault = Color(0xFF6C6C6C),
            mainToggled = Color(0xFF535C67),
            secondDefault = Color(0xFF2E2C37),
            secondFocused = Color(0xFF52505A),
            shadowColor = Color(0x50000000),
            textFocused = Color.White,
            textUnfocused = Color(0xFFBEBEBE)
        ),
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_narrow_glow,
            toggledRes = R.drawable.button_focus_narrow_nonglow,
            offsetX = (-7.5).dp,
            offsetY = (-4).dp
        ),
        animationConfig = ModularButtonAnimationConfig(
            pressOffset = 2.5.dp,
            textOffsetDefault = 1.dp,
            textOffsetPressed = 2.4.dp,
            durationMillis = 110,
            bounceBackDelayMillis = 80
        ),
        onClick = {
            BlueHiveApplication.playClickSound()
            onClick()
        }
    )
}




// =====================================================================================================================>  Episode overlay section
@Composable
private fun EpisodeProviderInlineButtons(
    passionFont: FontFamily,
    sources: List<TVShowsDetailsScreenCompose.StreamingSource>,
    focusRequesters: List<FocusRequester>,
    onSourceSelected: (TVShowsDetailsScreenCompose.StreamingSource) -> Unit,
    onBack: () -> Unit,   // ✅ NEW
) {
    val safeSources = remember {
        mutableStateListOf<TVShowsDetailsScreenCompose.StreamingSource>()
    }

    LaunchedEffect(sources) {
        safeSources.clear()
        // Cap to the focus-requester pool so we never index past it; the pool is
        // sized to the source list, so in practice this keeps every source.
        safeSources.addAll(sources.take(focusRequesters.size))
    }

    LaunchedEffect(safeSources.size) {
        if (safeSources.isNotEmpty()) {
            // give Compose a frame to attach focus nodes
            delay(50)
            focusRequesters.firstOrNull()?.requestFocus()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((-6).dp),
        modifier = Modifier
            .wrapContentWidth()
            .offset(x = 0.3.dp) // how far left or right the entire section of button goes!!!
            .focusGroup()
    ) {
        // Two per row, laid out FROM the source list — adding/removing a source
        // now just adds/removes a button (an odd count leaves a lone left button)
        // with no per-index Row block to keep in sync.
        safeSources.chunked(2).forEachIndexed { rowIndex, rowSources ->
            Row(
                horizontalArrangement = Arrangement.spacedBy((-2).dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowSources.forEachIndexed { colIndex, source ->
                    val i = rowIndex * 2 + colIndex
                    EpisodeOverlayStreamerButton(
                        index = i,
                        total = safeSources.size,
                        // right column nudged left the same 1.5.dp as before
                        offsetX = if (colIndex == 0) (-1).dp else (-1.5).dp,
                        source = source,
                        passionFont = passionFont,
                        focusRequesters = focusRequesters,
                        onClick = { onSourceSelected(source) },
                        onBack = onBack,
                    )
                }
            }
        }
    }
}




@Composable
private fun EpisodeOverlayStreamerButton(
    index: Int,
    total: Int,                       // total buttons in the grid — drives edge-aware D-pad nav
    offsetX: Dp = (-1).dp,
    source: TVShowsDetailsScreenCompose.StreamingSource,
    passionFont: FontFamily,
    focusRequesters: List<FocusRequester>,
    onClick: () -> Unit,
    onBack: () -> Unit,
) {
    // 🔹 manual x tweak just for certain names
    val labelOffsetX = when (source.name) {
        "VidEasy" -> (2).dp   // a little left
        "VidRock" -> (2).dp   // same tweak
        else -> 0.dp
    }


    val glowWidth= when (source.name) {
        "VidEasy" -> (96).dp   // a little width
        "VidRock" -> (96).dp   // same tweak
        else -> 96.dp
    }

    ModularButton(
        textConfig = ModularButtonTextConfig(
            text = "Watch on ${source.name}",
            fontSize = 9f,
            offsetX = labelOffsetX
        ),
        isToggleable = false,
        fontFamily = passionFont,
        focusRequester = focusRequesters[index],
        modifier = Modifier
            .offset(x = offsetX)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                // 2-column grid: col = index % 2, row = index / 2. Targets are
                // computed so the nav is correct for ANY button count (adding a
                // source can't leave a stale index mapping behind). -1 = edge → the
                // key is consumed with no move and no sound, exactly as before.
                when (event.key) {
                    Key.DirectionLeft -> {
                        val target = if (index % 2 == 1) index - 1 else -1   // right col → left neighbour
                        if (target >= 0) {
                            focusRequesters.getOrNull(target)?.requestFocus()
                            BlueHiveApplication.playHoverSound()   // 🔊 play on successful move
                        }
                        true
                    }

                    Key.DirectionRight -> {
                        val target = if (index % 2 == 0 && index + 1 < total) index + 1 else -1  // left col → right neighbour
                        if (target >= 0) {
                            focusRequesters.getOrNull(target)?.requestFocus()
                            BlueHiveApplication.playHoverSound()
                        }
                        true
                    }

                    Key.DirectionUp -> {
                        val target = index - 2                               // one row up, same column
                        if (target >= 0) {
                            focusRequesters.getOrNull(target)?.requestFocus()
                            BlueHiveApplication.playHoverSound()
                        }
                        true
                    }

                    Key.DirectionDown -> {
                        val target = index + 2                               // one row down, same column
                        if (target < total) {
                            focusRequesters.getOrNull(target)?.requestFocus()
                            BlueHiveApplication.playHoverSound()
                        }
                        true
                    }

                    Key.Back -> {
                        onBack()
                        true   // consume so Activity back doesn't fire
                    }

                    else -> false
                }
            },
        // just with your narrow width so 2-per-row still fit
        dimensions = ModularButtonDimensions(
            mainWidth = 83.dp,
            mainHeight = 23.62.dp,      // was 23.625.dp
            mainYOffset = 0.7.dp,
            secondWidth = 83.dp,
            secondHeight = 22.5.dp,    // was 21.dp
            secondYOffset = 6.5.dp,   // was 6.5.dp
            shadowHeight = 6.dp,    // was 6.dp
            glowWidth = glowWidth,
            glowHeight = 41.5.dp,
            mainCornerRadius = 7f,
            secondCornerRadius = 8f,
        ),
        colors = ModularButtonColors(
            mainDefault = Color(0xFF0004FF),
            mainToggled = Color(0xFF07052F),
            secondDefault = Color(0xFF471C98),
            secondFocused = Color(0xFF342180),
            shadowColor = Color(0x60000000),
            textFocused = Color.White,
            textUnfocused = Color(0xFFD6D6D6)
        ),
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            // keep using your narrow glow asset
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX = (-6.5).dp,
            offsetY = (-2.5).dp
        ),
        animationConfig = ModularButtonAnimationConfig(
            pressOffset = 2.0.dp,        // was 2.0.dp
            textOffsetDefault = 0.6.dp,    // was 0.6.dp
            textOffsetPressed = 1.6.dp,  // was 1.6.dp
            durationMillis = 110,
            bounceBackDelayMillis = 80
        ),
        onClick = {
            BlueHiveApplication.playClickSound()
            onClick()
        }
    )
}
// =====================================================================================================================> Episode overlay section



@Composable
fun SeasonButton(
    label: String,
    isSelected: Boolean,
    fontFamily: FontFamily,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(
            text = label,
            fontSize = 16f
        ),
        isToggleable = true,
        fontFamily = fontFamily,
        focusRequester = focusRequester,
        modifier = modifier,
        dimensions = ModularButtonDimensions(
            mainWidth = 117.5.dp,
            mainHeight = 30.dp,
            mainYOffset = 0.7.dp,
            secondWidth = 117.5.dp,
            secondHeight = 22.dp,
            secondYOffset = 13.5.dp,
            shadowHeight = 12.dp,
            shadowOffset = (-3).dp,
            glowWidth = 135.6.dp,
            glowHeight = 51.dp,
            mainCornerRadius = 7f,
            secondCornerRadius = 8f,
        ),
        colors = ModularButtonColors(
            mainDefault = Color(0xFF0004FF),
            mainToggled = Color(0xFF07052F),
            secondDefault = Color(0xFF471C98),
            secondFocused = Color(0xFF342180),
            shadowColor = Color(0x60000000),
            textFocused = Color.White,
            textUnfocused = Color(0xFFD6D6D6),
            textToggled = Color(0xFF5D5B6A),
            textToggledFocus = Color(0xFFCDCDCD)
        ),
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX = (-9.25).dp,  // lesser the number the farther it moves left
            offsetY = (-2.5).dp,
            cornerRadius = 10.dp
        ),
        animationConfig = ModularButtonAnimationConfig(
            pressOffset = 2.5.dp,
            textOffsetDefault = 1.dp,
            textOffsetPressed = 2.4.dp,
            durationMillis = 110,
            bounceBackDelayMillis = 80
        ),
        externalToggled = isSelected,
        onClick = {
            BlueHiveApplication.playClickSound()
            onClick()
        }
    )
}



// ====================================================================================================================>  Anime episode overlay (DuB / SuB / VidFast)
@Composable
private fun AnimeEpisodeProviderButtons(
    passionFont: FontFamily,
    miruroUrl: String?,                                            // null if AniList resolve failed → DuB/SuB inert
    focusRequesters: List<FocusRequester>,                         // [0]=DuB, [1]=SuB
    onDub: (String) -> Unit,
    onSub: (String) -> Unit,
    onBack: () -> Unit,
) {
    // Auto-focus DuB when the overlay opens (matches EpisodeProviderInlineButtons).
    LaunchedEffect(Unit) {
        delay(50)
        focusRequesters.firstOrNull()?.requestFocus()
    }

    fun moveTo(idx: Int): Boolean {
        val fr = focusRequesters.getOrNull(idx) ?: return true
        fr.requestFocus()
        BlueHiveApplication.playHoverSound()
        return true
    }

    Column(
        verticalArrangement = Arrangement.spacedBy((-4).dp),
        modifier = Modifier.offset(x = 0.3.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-2).dp)) {
            AnimeOverlayButton(
                text = "Watch in DuB",
                passionFont = passionFont,
                focusRequester = focusRequesters[0],
                mainDefault = Color(0xFFCC0000), mainToggled = Color(0xFF7A0000),
                secondDefault = Color(0xFF7A0000), secondFocused = Color(0xFF8B1400),
                modifier = Modifier.onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionRight -> moveTo(1)
                        Key.DirectionDown  -> true
                        Key.DirectionLeft  -> true
                        Key.DirectionUp    -> true
                        Key.Back           -> { onBack(); true }
                        else               -> false
                    }
                },
                onClick = { miruroUrl?.let(onDub) }
            )
            AnimeOverlayButton(
                text = "Watch in SuB",
                passionFont = passionFont,
                focusRequester = focusRequesters[1],
                mainDefault = Color(0xFFCCA000), mainToggled = Color(0xFF6B5000),
                secondDefault = Color(0xFF7A5500), secondFocused = Color(0xFF8B6500),
                modifier = Modifier.onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft  -> moveTo(0)
                        Key.DirectionDown  -> true
                        Key.DirectionRight -> true
                        Key.DirectionUp    -> true
                        Key.Back           -> { onBack(); true }
                        else               -> false
                    }
                },
                onClick = { miruroUrl?.let(onSub) }
            )
        }
    }
}

@Composable
private fun AnimeOverlayButton(
    text: String,
    passionFont: FontFamily,
    focusRequester: FocusRequester,
    mainDefault: Color,
    mainToggled: Color,
    secondDefault: Color,
    secondFocused: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(text = text, fontSize = 9f),
        isToggleable = false,
        fontFamily = passionFont,
        focusRequester = focusRequester,
        modifier = modifier,
        dimensions = ModularButtonDimensions(
            mainWidth = 83.dp,
            mainHeight = 23.62.dp,
            mainYOffset = 0.7.dp,
            secondWidth = 83.dp,
            secondHeight = 22.5.dp,
            secondYOffset = 6.5.dp,
            shadowHeight = 6.dp,
            glowWidth = 96.dp,
            glowHeight = 41.5.dp,
            mainCornerRadius = 7f,
            secondCornerRadius = 8f,
        ),
        colors = ModularButtonColors(
            mainDefault = mainDefault,
            mainToggled = mainToggled,
            secondDefault = secondDefault,
            secondFocused = secondFocused,
            shadowColor = Color(0x60000000),
            textFocused = Color.White,
            textUnfocused = Color(0xFFD6D6D6)
        ),
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX = (-6.5).dp,
            offsetY = (-2.5).dp
        ),
        animationConfig = ModularButtonAnimationConfig(
            pressOffset = 2.0.dp,
            textOffsetDefault = 0.6.dp,
            textOffsetPressed = 1.6.dp,
            durationMillis = 110,
            bounceBackDelayMillis = 80
        ),
        onClick = {
            BlueHiveApplication.playClickSound()
            onClick()
        }
    )
}



//0x33000000 = 20% opacity (very light)
//0x50000000 = 31% opacity (your current - too light)
//0x80000000 = 50% opacity (medium)
//0xCC000000 = 80% opacity (recommended)
//0xE6000000 = 90% opacity (strong)
//0xFF000000 = 100% opacity (full black)




