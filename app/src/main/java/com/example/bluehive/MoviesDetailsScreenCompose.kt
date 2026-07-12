package com.example.bluehive

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluehive.webview.MainWebViewer
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.example.bluehive.utilities.*
import coil.compose.AsyncImage
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.input.key.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.focus.focusProperties
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.FavoriteRequest
import com.example.bluehive.api.WatchHistoryRequest
import com.example.bluehive.utilities.AppShapes
import androidx.compose.ui.text.style.TextAlign
import com.example.bluehive.sidebarComponents.FavoritesScreenActivity
import com.example.bluehive.sidebarComponents.HistoryScreenActivity
import com.example.bluehive.searchBarComponent.SearchBarActivity
import com.example.bluehive.api.isJapaneseAnimation
import com.example.bluehive.api.buildJapaneseAnimationSources
import com.example.bluehive.webview.miruro.MiruroPlayerActivity
import java.net.URLEncoder
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.bluehive.webview.miruro.MiruroExtractorActivity
import com.example.bluehive.webview.BeeLogo
import com.example.bluehive.webview.ExtractionLoadingOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.android.awaitFrame
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.bluehive.webview.vidapi.VidApiExtractorActivity
import com.example.bluehive.webview.vidapi.VidApiPlayerActivity



class MoviesDetailsScreenCompose : ComponentActivity() {
    private var mediaType: String = "movie"
    private var mediaId: Int = 0
    private var voteAverage: Double = 0.0
    private var mediaTitle: String = ""
    private var posterUrl: String? = null
    private var backdropUrl: String? = null
    private var trailerUrl: String? = null
    private var overview: String? = null
    private var contentRating: String? = null
    private var releaseDate: String? = null
    private var budget: Long = 0
    private var revenue: Long = 0
    private var originalLanguage: String? = null
    private var logoUrl: String? = null
    private var genres: String? = null
    internal var profileId: Int = -1
    private var sourceScreen: String? = null
    private var searchQuery:  String? = null


    @Immutable
    data class StreamingSource(
        val coverName: String,
        val name: String,
        val url: String,
        // true → play via the headless m3u8 extractor (ExoPlayer); false → the
        // WebView player. A property of the source, not its display name, so
        // renaming the button can't route it to the wrong path.
        val useExtractor: Boolean = false
    )

    companion object {
        private const val TAG = "MoviesDetailsScreenCompose"
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
            status = null,
            voteAverage = voteAverage,
            voteCount = null,
            popularity = null,
            popularityRank = null,
            originalLanguage = originalLanguage,
            numberOfSeasons = null,
            numberOfEpisodes = null,
            contentRating = contentRating,
            runtime = null,
            budget = budget,
            revenue = revenue,
            trailerUrl = trailerUrl,
            genres = genres?.split(", ")?.map { it.trim() },
            similarItems = null,
            whereToWatch = null
        ))
        Log.d(
            "DetailsHistory",
            "Details -> openMediaDetails: pushed '${media.title}', size=${app.historySize()}"
        )

        val intent = Intent(this, MoviesDetailsScreenCompose::class.java).apply {
            putExtra("media_type",        media.mediaType ?: "movie")
            putExtra("media_id",          media.tmdbId)                     // Int (non-null)
            putExtra("media_title",       media.title ?: "")
            putExtra("poster_url",        media.posterUrl ?: "")
            putExtra("backdrop_url",      media.backdropUrl ?: "")
            putExtra("youtube_trailer_url", media.trailerUrl ?: "")
            putExtra("overview",          media.overview ?: "")
            putExtra("vote_average",      media.voteAverage ?: 0.0)         // Double, NOT Double?
            putExtra("contentRating",     media.contentRating ?: "N/A")
            putExtra("original_language", media.originalLanguage ?: "N/A")
            putExtra("release_date",      media.releaseDate ?: "N/A")
            putExtra("budget",            media.budget ?: 0L)               // Long, NOT Long?
            putExtra("revenue",           media.revenue ?: 0L)              // Long
            putExtra("logo_url",          media.logoUrl ?: "")
            putExtra("genres",            media.genres?.joinToString(", ") ?: "N/A")
            putExtra("PROFILE_ID",        profileId)
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
                                Intent(this@MoviesDetailsScreenCompose, HistoryScreenActivity::class.java)
                                    .putExtra("PROFILE_ID", profileId)
                            )
                            finish()
                            return
                        }
                        SourceScreen.FAVORITES -> {
                            Log.d("DetailsHistory", "Back: returning to Favorites")
                            startActivity(
                                Intent(this@MoviesDetailsScreenCompose, FavoritesScreenActivity::class.java)
                                    .putExtra("PROFILE_ID", profileId)
                            )
                            finish()
                            return
                        }
                        SourceScreen.SEARCH -> {
                            Log.d("DetailsHistory", "Back: returning to Search")
                            startActivity(
                                Intent(this@MoviesDetailsScreenCompose, SearchBarActivity::class.java)
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

                val intent = Intent(this@MoviesDetailsScreenCompose, MoviesDetailsScreenCompose::class.java).apply {
                    putExtra("media_type",        previous.mediaType ?: "movie")
                    putExtra("media_id",          previous.tmdbId)
                    putExtra("media_title",       previous.title ?: "")
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
        mediaType = intent.getStringExtra("media_type") ?: "movie"
        contentRating = intent.getStringExtra("contentRating")
        originalLanguage = intent.getStringExtra("original_language")
        mediaId = intent.getIntExtra("media_id", 0)
        voteAverage = intent.getDoubleExtra("vote_average", 0.0)
        mediaTitle = intent.getStringExtra("media_title") ?: ""
        posterUrl = intent.getStringExtra("poster_url")
        backdropUrl = intent.getStringExtra("backdrop_url")
        trailerUrl = intent.getStringExtra("youtube_trailer_url")
        overview = intent.getStringExtra("overview")
        budget = intent.getLongExtra("budget", 0L)
        revenue = intent.getLongExtra("revenue", 0L)
        logoUrl = intent.getStringExtra("logo_url")
        releaseDate = intent.getStringExtra("release_date")
        genres    = intent.getStringExtra("genres")
        profileId = intent.getIntExtra("PROFILE_ID", -1)
        sourceScreen = intent.getStringExtra("SOURCE_SCREEN")
        searchQuery  = intent.getStringExtra("SEARCH_QUERY")

        val startInExtras = intent.getBooleanExtra("start_in_extras", false)

        val recommendationsRepository = RecommendationsRepository()


        // ✅ Debug logging to see what we received
        Log.d(TAG, "📝 Intent extras received:")
        Log.d(TAG, "  mediaTitle: $mediaTitle")
        Log.d(TAG, "  mediaType: $mediaType")
        Log.d(TAG, "  mediaId: $mediaId")
        Log.d(TAG, "  backdropUrl: $backdropUrl")
        Log.d(TAG, "  trailerUrl: $trailerUrl")
        Log.d(TAG, "  logoUrl (raw): '$logoUrl'")
        Log.d(TAG, "  logoUrl isEmpty: ${logoUrl.isNullOrEmpty()}")
        Log.d(TAG, "  logoUrl isBlank: ${logoUrl.isNullOrBlank()}")

        Log.d(TAG, "Received media: $mediaTitle (ID: $mediaId, Type: $mediaType)")

        Log.d(TAG, "🔍 Intent extras:")
        intent.extras?.keySet()?.forEach { key ->
            val value = intent.extras?.get(key)
            Log.d(TAG, "  $key: $value (type: ${value?.javaClass?.simpleName})")
        }

        setContent {
            MoviesDetailsScreenContent(
                mediaTitle = mediaTitle,
                mediaType = mediaType,
                mediaId = mediaId,
                backdropUrl = backdropUrl,
                trailerUrl = trailerUrl,
                logoUrl = logoUrl,
                overview = overview,
                revenue = revenue,
                budget = budget,
                contentRating = contentRating,
                originalLanguage = originalLanguage,
                voteAverage = voteAverage,
                releaseDate = releaseDate,
                genres = genres,
                tmdbId = mediaId,
                startInExtras = startInExtras,
                recommendationsRepository = recommendationsRepository,
                onStreamingSourceSelected = { source ->
                    handleStreamingSourceSelected(source)
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
            val intent = Intent(this@MoviesDetailsScreenCompose, MainWebViewer::class.java).apply {
                putExtra("PROFILE_ID",  profileId)
                putExtra("MEDIA_ID",    mediaId.toString())
                putExtra("MEDIA_TYPE",  mediaType)
                putExtra("MEDIA_TITLE", mediaTitle)
                putExtra("vote_average", voteAverage)
                putExtra("SOURCE_NAME", source.name)
                putExtra("SOURCE_URL", source.url)
                putExtra("backdrop_url", backdropUrl)
                putExtra("trailer_url", trailerUrl)
                putExtra("media_title", mediaTitle)
                putExtra("poster_url", posterUrl)
                putExtra("overview", overview)
                putExtra("budget", budget)
                putExtra("revenue", revenue)
                putExtra("contentRating", contentRating)
                putExtra("originalLanguage", originalLanguage)
                putExtra("releaseDate", releaseDate)
                putExtra("logo_url", logoUrl)
            }
            startActivity(intent)
        }, 300)
    }


}


private fun getStreamingSources(mediaType: String, mediaId: Int): List<MoviesDetailsScreenCompose.StreamingSource> {
    return when(mediaType) {
        "movie" -> listOf(
            MoviesDetailsScreenCompose.StreamingSource(
                "Purple Stream",
                "VidSpark",
                    "https://ww2.moviesapi.to/movie/$mediaId"
            ),
            MoviesDetailsScreenCompose.StreamingSource(
                "Queen Stream",
                "VidFast",
                "https://vidfast.pro/movie/$mediaId?autoPlay=true"
            ),
            MoviesDetailsScreenCompose.StreamingSource(
                "Royal Jelly",
                "VidEasy",
                "https://player.videasy.net/movie/$mediaId"
            ),
            MoviesDetailsScreenCompose.StreamingSource(
                "Swarm Stream",
                "VidLink",
                "https://vidlink.pro/movie/$mediaId"
            ),
            MoviesDetailsScreenCompose.StreamingSource(
                "Colony Stream",
                "VidSuper",
                "https://vidsuper.net/movie/$mediaId"
            ),
            MoviesDetailsScreenCompose.StreamingSource(
                "Honeycomb",
                "VidApi",
                "https://vaplayer.ru/embed/movie/$mediaId",
                useExtractor = true   // vaplayer embed → headless m3u8 extraction
            )
        )
        else -> listOf()
    }
}







@UnstableApi
@Composable
fun MoviesDetailsScreenContent(
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
    revenue: Long,
    budget: Long,
    genres: String?,
    tmdbId: Int,
    startInExtras: Boolean = false,
    recommendationsRepository: RecommendationsRepository,
    onStreamingSourceSelected: (MoviesDetailsScreenCompose.StreamingSource) -> Unit,
    onMediaClick: (MediaItem) -> Unit
) {
    // Load custom fonts
    val passionFont = AppTypography.passionRegular
    val dongleRegular = AppTypography.dongleRegular
    val context = LocalContext.current

    // Decode the loading-overlay bee logo now, while the screen is first
    // composing and memory is comparatively free, and cache it process-wide so
    // the Dub/Sub overlay never decodes it under pressure (the 2 GB crash).
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { BeeLogo.prime(context) }
    }

    // ✅ Add state for extras toggle and recommendations
    var isExtrasToggled by remember(startInExtras) { mutableStateOf(startInExtras) }
    var recommendations by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()
    var blockHeaderInitialFocus by remember(startInExtras) { mutableStateOf(startInExtras) }

    var isTrailerPlaying by remember { mutableStateOf(false) }
    var hasTrailerBeenPlayed by remember { mutableStateOf(false) }
    var shouldExtractTrailer by remember { mutableStateOf(false) }

    // Focus requesters
    val favoriteFocusRequester = remember { FocusRequester() }
    val extrasFocusRequester = remember { FocusRequester() }
    val trailerFocusRequester = remember { FocusRequester() }

    var isExtractingTrailer by remember { mutableStateOf(false) }
    var isExtracting   by remember { mutableStateOf(false) }
    var extractStage   by remember { mutableIntStateOf(0) }
    var extractPending by remember { mutableStateOf(false) }   // guards the 1.5s pre-launch window
    var showNoDub      by remember { mutableStateOf(false) }   // transient "No dub available" toast

    // ── Anime server-selection flow ─────────────────────────────────────────
    var showDefaultPrompt by remember { mutableStateOf(false) } // instant "default server?" Yes/No
    var isCheckingServers by remember { mutableStateOf(false) } // phase 1: enumerating servers
    var showServerPicker  by remember { mutableStateOf(false) } // phase 1 result: the picker
    var showServerError   by remember { mutableStateOf(false) } // transient failure toast
    var pendingUrl        by remember { mutableStateOf<String?>(null) }
    var pendingDub        by remember { mutableStateOf(false) }
    var serverOptions     by remember { mutableStateOf<List<ServerOption>>(emptyList()) }
    var serverDefaultIdx  by remember { mutableIntStateOf(0) }

    // Server lists captured during a DEFAULT extraction, cached for the lifetime
    // of this detail screen (cleared when the user navigates away). Keyed by
    // url + audio so "Choose a server" can skip the enumerate webview pass when
    // the list is already known. Value = (options, defaultIndex). Plain map — only
    // read/written from callbacks, never during composition, so no snapshot state.
    val serverListCache = remember { mutableMapOf<String, Pair<List<ServerOption>, Int>>() }
    fun serverCacheKey(url: String, dub: Boolean) = "$url|${if (dub) "dub" else "sub"}"

    // Named requesters for DuB/Sub so we know exactly where to send focus back.
    val animeDubFocusRequester = remember { FocusRequester() }
    val animeSubFocusRequester = remember { FocusRequester() }
    // Whichever button the user pressed last — restored when any overlay closes.
    var lastStreamFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }

    // Extractor m3u8-extraction error reason (shown as a toast; auto-dismisses).
    var vidApiError by remember { mutableStateOf<String?>(null) }

    // Back closes whichever picker/prompt is open instead of leaving the screen.
    BackHandler(enabled = showDefaultPrompt || showServerPicker) {
        when {
            showServerPicker -> { showServerPicker = false; showDefaultPrompt = true }
            showDefaultPrompt -> {
                showDefaultPrompt = false
                // Return the cursor to whichever stream button opened the prompt.
                coroutineScope.launch {
                    awaitFrame()
                    try { lastStreamFocusRequester?.requestFocus() } catch (_: Exception) {}
                }
            }
        }
    }

    var isFavorited by remember { mutableStateOf(false) }
    val activity = LocalActivity.current as? MoviesDetailsScreenCompose
    val favoriteProfileId = activity?.profileId ?: -1



    // Receives the m3u8 back from the isolated :extractor process, then launches
    // the player here in the main process — keeps back-navigation identical.
    // Phase 2 result: receives the m3u8 (default OR chosen server) and plays it.
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

                // ── Record the watch ────────────────────────────────────────
                // The DuB/SuB path goes straight to MiruroPlayerActivity and never
                // touches MainWebViewer, which is the only place watch history was
                // ever logged — so anime watched this way was invisible to the
                // backend. Log it here with the SAME endpoint the normal player
                // path uses. This populates BOTH the Watch History screen and the
                // Continue Watching row (both read this one endpoint).
                if (favoriteProfileId != -1 && tmdbId != 0) {
                    val sourceLabel = if (pendingDub) "Miruro (Dub)" else "Miruro (Sub)"
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        try {
                            ApiClient.bluehiveApi.logWatchHistory(
                                WatchHistoryRequest(
                                    profile_id     = favoriteProfileId,
                                    media_tmdb_id  = tmdbId,
                                    media_type     = mediaType,
                                    source_name    = sourceLabel,
                                    media_title    = mediaTitle,
                                    season_number  = null,   // anime movies on this screen
                                    episode_number = null,
                                    episode_name   = null,
                                )
                            )
                            Log.d("scraper", "✅ Watch history logged: anime tmdbId=$tmdbId ($sourceLabel)")
                        } catch (e: Exception) {
                            Log.w("scraper", "⚠️ Anime watch-history log failed (non-fatal): ${e.message}")
                        }
                    }
                }

                // ── Cache any server list captured in this same pass ─────────
                // A default extraction (captureServers=true) also carries the
                // provider list on the RESULT_SERVER_* extras. Save it so a later
                // "Choose a server" skips its own enumerate webview pass while we
                // stay on this page.
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
                else   -> { Log.w("scraper", "M Extract: user cancelled") /* stay silent */ }
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
                else   -> { Log.w("scraper", "M Enumerate: user cancelled") /* stay silent */ }
            }
        }
    }



    // ── Extractor-source m3u8 extraction (movies only) ───────────────────────
    // Reuses the isExtracting/extractStage overlay. Separate launcher from the
    // Miruro one so the anime flow is untouched. The source name + embed URL are
    // captured at launch so the result callback can label watch-history correctly
    // and pass EMBED_URL for CDN failover (token refresh on a mid-play error).
    var vidApiSourceName by remember { mutableStateOf("VidApi") }
    var vidApiEmbedUrl   by remember { mutableStateOf<String?>(null) }
    // English subtitle fetch, kicked off in parallel with extraction when a VidApi
    // source is played.
    val vidApiExtractorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isExtracting   = false
        extractPending = false
        if (result.resultCode == Activity.RESULT_OK) {
            val data     = result.data
            val m3u8     = data?.getStringExtra(VidApiExtractorActivity.RESULT_M3U8)
            val referer  = data?.getStringExtra(VidApiExtractorActivity.RESULT_REFERER)
            val ua       = data?.getStringExtra(VidApiExtractorActivity.RESULT_UA)
            if (!m3u8.isNullOrBlank()) {
                // Direct CDN play. Captions are fetched ON DEMAND in the player when
                // the CC button is pressed, so we just pass the tmdb id along (the
                // extractor's own RESULT_SUBTITLE — vaplayer's thumbnail track — is
                // intentionally unused).
                Log.d("scraper-vidapi", "▶️ direct CDN play: $m3u8")
                context.startActivity(
                    Intent(context, VidApiPlayerActivity::class.java)
                        .putExtra("M3U8_URL", m3u8)
                        .putExtra("REFERER", referer)
                        .putExtra("UA", ua)
                        .putExtra("TMDB_ID", tmdbId)         // for on-demand caption fetch
                        // EMBED_URL lets the player re-extract a fresh token on a
                        // mid-play CDN error (important for token'd embeds).
                        .putExtra("EMBED_URL", vidApiEmbedUrl)
                )
                if (favoriteProfileId != -1 && tmdbId != 0) {
                    val histSource = vidApiSourceName
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        try {
                            ApiClient.bluehiveApi.logWatchHistory(
                                WatchHistoryRequest(
                                    profile_id     = favoriteProfileId,
                                    media_tmdb_id  = tmdbId,
                                    media_type     = mediaType,
                                    source_name    = histSource,
                                    media_title    = mediaTitle,
                                    season_number  = null,
                                    episode_number = null,
                                    episode_name   = null,
                                )
                            )
                            Log.d("scraper-vidapi", "✅ watch history logged tmdbId=$tmdbId ($histSource)")
                        } catch (e: Exception) {
                            Log.w("scraper-vidapi", "⚠️ history log failed (non-fatal): ${e.message}")
                        }
                    }
                }
            } else {
                Log.e("scraper-vidapi", "OK result but no m3u8")
                vidApiError = "Couldn't find a playable stream — try another source."
            }
        } else {
            val data   = result.data
            val failed = data?.getBooleanExtra(VidApiExtractorActivity.RESULT_FAILED, false) == true
            val reason = data?.getStringExtra(VidApiExtractorActivity.RESULT_REASON)
            when {
                failed -> { Log.w("scraper-vidapi", "failed: $reason"); vidApiError = reason ?: "Extraction failed — try another source." }
                else   -> { Log.w("scraper-vidapi", "user cancelled") /* silent */ }
            }
        }
    }

    val launchVidApiExtract: (String, String) -> Unit = { name, url ->
        Log.d("scraper-vidapi", "$name Extract → $url")
        if (!isExtracting && !extractPending && !isCheckingServers) {
            extractPending = true
            vidApiSourceName = name          // label watch-history + failover with this
            vidApiEmbedUrl   = url           // embed URL for CDN failover re-extraction
            coroutineScope.launch {
                delay(200)
                isExtracting = true
                try { BlueHiveApplication.coilImageLoader.memoryCache?.clear() } catch (_: Exception) {}
                vidApiExtractorLauncher.launch(
                    Intent(context, VidApiExtractorActivity::class.java)
                        .putExtra(VidApiExtractorActivity.EXTRA_URL, url)
                )
            }
        }
    }



    // Launch a real extraction (default server when server == null).
    // extractPending is set immediately to block double-fire during the animation
    // window. The loading overlay itself is delayed 300ms so the button press
    // animation fully completes before the scrim appears.
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

    // Launch the server-list check (phase 1). Same 300ms delay before the
    // "Checking…" overlay appears so the "Choose a server" animation finishes.
    val launchEnumerate: (String, Boolean) -> Unit = { url, dub ->
        Log.d("scraper", "M Enumerate (dub=$dub) → $url")
        if (!isExtracting && !extractPending && !isCheckingServers) {
            extractPending = true      // immediate guard for the 300ms window
            coroutineScope.launch {
                delay(200)
                extractPending = false // hand off guard duty to isCheckingServers
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


    LaunchedEffect(vidApiError) {
        if (vidApiError != null) { delay(3500); vidApiError = null }
    }

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


    // Get streaming sources based on media type.
    // Japanese animation (anime) → dedicated DuB / SuB set.
    // Everything else keeps the standard 6 buttons.
    val isAnime = remember(genres, originalLanguage) {
        val result = isJapaneseAnimation(genres, originalLanguage)
        Log.d("AnimeDetect", "genres='$genres' | originalLanguage='$originalLanguage' | isAnime=$result")
        result
    }
    val sources by produceState(
        initialValue = if (isAnime) emptyList()
        else getStreamingSources(mediaType, mediaId),
        mediaType, mediaId, isAnime
    ) {
        if (isAnime) {
            value = buildJapaneseAnimationSources(
                mediaType     = mediaType,
                tmdbId        = mediaId,
                seasonNumber  = null,
                episodeNumber = null,
            ) { c, n, u -> MoviesDetailsScreenCompose.StreamingSource(c, n, u) }
        }
        // non-anime: initialValue already holds the 5 sources; nothing to do.
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
        if (showNoDub) {
            delay(2500)
            showNoDub = false
        }
    }

    // 30s on the picker → close it and send the user back to the Yes/No prompt.
    LaunchedEffect(showServerPicker) {
        if (showServerPicker) {
            delay(30_000)
            if (showServerPicker) {          // still up → no pick was made
                showServerPicker = false
                showDefaultPrompt = true
            }
        }
    }

    LaunchedEffect(showServerError) {
        if (showServerError) {
            delay(2500)
            showServerError = false
        }
    }

    LaunchedEffect(sources) {
        Log.d(
            "scraper",
            "Movie sources resolved (${sources.size}): " +
                    sources.mapIndexed { index, source ->
                        "[$index] ${source.name} -> ${source.url}"
                    }.joinToString(" | ")
        )
    }


    // ✅ Log cache stats when screen loads — deferred + off the main thread.
    // gatherStats() does THREE full directory walks (disk cache + cacheDir +
    // filesDir); running it inline on entry was a main-thread stall. Let the
    // screen paint first, then walk on IO.
    LaunchedEffect(Unit) {
        delay(1500)
        withContext(Dispatchers.IO) {
            CacheMonitor.logCacheStats(
                context = context,
                screenName = "MoviesDetailsScreen",
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
                    screenName = "MoviesDetailsScreen",
                    imageCount = totalImages
                )
            }
        }
    }


    // ✅ If we were asked to start in Extras (when returning from history),
    // preload recommendations and keep Extras toggled on.
    LaunchedEffect(startInExtras, tmdbId, mediaType) {
        if (startInExtras && recommendations.isEmpty()) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val result = recommendationsRepository.getRecommendations(tmdbId, mediaType)
                    withContext(Dispatchers.Main) {
                        recommendations = result
                        isExtrasToggled = true
                        Log.d(
                            "Recommendations",
                            "Preloaded ${recommendations.size} recommendations for $tmdbId ($mediaType)"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(
                        "Recommendations",
                        "Error preloading recommendations for $tmdbId ($mediaType)",
                        e
                    )
                }
            }
        }
    }

    LaunchedEffect(startInExtras, recommendations) {
        if (startInExtras && recommendations.isNotEmpty()) {
            blockHeaderInitialFocus = false
        }
    }

    var displayBudget by remember { mutableLongStateOf(budget) }
    var displayRevenue by remember { mutableLongStateOf(revenue) }

    LaunchedEffect(tmdbId, mediaType) {
        try {
            val detail = ApiClient.trailerApi.getMediaDetails(tmdbId, mediaType)
            detail.budget?.let { if (it > 0) displayBudget = it }
            detail.revenue?.let { if (it > 0) displayRevenue = it }
        } catch (_: Exception) {}
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
                text = if (!contentRating.isNullOrBlank() && contentRating != "N/A") {
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
                contentDescription = "Movie backdrop",
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


        var trailerPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

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
        if (displayRevenue > 0) {
            Text(
                text = "Revenue:          $${"%,d".format(displayRevenue)}.00",
                color = Color(0xFF8B8B8B),
                fontSize = 17.sp,
                fontFamily = dongleRegular,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 258.25.dp, top = 34.dp)
            )
        }
        // ========================================================================================================================================= >


        // ========================================================================================================================================= > // Budget
        if (displayBudget > 0) {
            Text(
                text = "Budget:            $${"%,d".format(displayBudget)}.00",
                color = Color(0xFF8B8B8B),
                fontSize = 17.sp,
                fontFamily = dongleRegular,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 258.25.dp, top = 48.dp)
            )
        }
        // ========================================================================================================================================= >



        // ========================================================================================================================================= > // release_date
        Text(
            text = if (!releaseDate.isNullOrBlank() && releaseDate != "N/A") {
                val dateParts = releaseDate.split("-")
                if (dateParts.size == 3) {
                    "Release Date:      ${dateParts[1]}/${dateParts[2]}/${dateParts[0]}"  // Format as MM/DD/YYYY
                } else {
                    releaseDate
                }
            } else {
                "Release Date:      N/A"
            },
            color = Color(0xFF8B8B8B),
            fontSize = 16.sp,
            fontFamily = dongleRegular,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 258.25.dp, top = 63.dp)  // Adjust position as needed
        )
        // ========================================================================================================================================= >


        // ========================================================================================================================================= > // original language
        // ✅ Always show language, fall back to N/A
        Text(
            text = "Language:         " + (
                    if (!originalLanguage.isNullOrBlank() && originalLanguage != "N/A")
                        originalLanguage
                    else
                        "N/A"
                    ),
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
        MoviesFavoriteButton(
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

        MoviesExtrasButton(
            fontFamily = passionFont,
            focusRequester = extrasFocusRequester,
            isToggled = isExtrasToggled,           // 🔹 <-- NEW: bind visual state
            enableFocus = !blockHeaderInitialFocus,
            onClick = {
                isExtrasToggled = !isExtrasToggled
                if (isExtrasToggled && recommendations.isEmpty()) {   // Load recommendations when toggled on
                    coroutineScope.launch(Dispatchers.IO) {   // 👈 run on background thread
                        val result = recommendationsRepository.getRecommendations(tmdbId, mediaType)

                        withContext(Dispatchers.Main) {       // 👈 switch back to UI to update state
                            recommendations = result
                            Log.d("Recommendations", "Loaded ${recommendations.size} recommendations")
                        }
                    }
                }
            }
        )



        MoviesTrailerButton(
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


        MoviesRewindButton(
            fontFamily = passionFont,
            focusRequester = remember { FocusRequester() },
            enableFocus = !blockHeaderInitialFocus,
            onClick = {
                if (isTrailerPlaying) {
                    seekBackward(trailerPlayer)
                }
            }
        )

        MoviesFastForwardButton(
            fontFamily = passionFont,
            focusRequester = remember { FocusRequester() },
            enableFocus = !blockHeaderInitialFocus,
            onClick = {
                if (isTrailerPlaying) {
                    seekForward(trailerPlayer)
                }
            }
        )

        // ✅ Conditionally show StreamerButtons OR RecommendedMediaSection

        if (!isExtrasToggled) {
            if (isAnime) {
                // ── Anime: DuB / SuB ────────────────────────────────────
                AnimeDubButton(
                    fontFamily = passionFont,
                    focusRequester = animeDubFocusRequester,
                    offsetX = 27.75.dp, offsetY = 332.5.dp,
                    onClick = {
                        isTrailerPlaying = false
                        hasTrailerBeenPlayed = false
                        if (sources.size > 2) {
                            Log.d("scraper", "DuB pressed → prompt: ${sources[2].url}")
                            lastStreamFocusRequester = animeDubFocusRequester
                            pendingUrl = sources[2].url
                            pendingDub = true
                            showDefaultPrompt = true
                        }
                    }
                )
                AnimeSubButton(
                    fontFamily = passionFont,
                    focusRequester = animeSubFocusRequester,
                    offsetX = 228.dp, offsetY = 332.5.dp,
                    onClick = {
                        isTrailerPlaying = false
                        hasTrailerBeenPlayed = false
                        if (sources.size > 2) {
                            Log.d("scraper", "SuB pressed → prompt: ${sources[2].url}")
                            lastStreamFocusRequester = animeSubFocusRequester
                            pendingUrl = sources[2].url
                            pendingDub = false
                            showDefaultPrompt = true
                        }
                    }
                )
            } else {
                // ── Non-anime: 2-column grid laid out FROM the sources list ──
                // Adding/removing an entry in getStreamingSources() now "just
                // works" — no per-index block to keep in sync (that mismatch is
                // what silently dropped the 6th button). Column X and the first
                // rows' Y are the exact legacy offsets, so nothing moves on screen;
                // a 7th+ source simply continues the grid downward.
                val colX = listOf(27.75.dp, 228.dp)          // [left column, right column]
                val rowY = listOf(332.5.dp, 388.dp, 443.dp)  // exact legacy row offsets
                val streamerFocus = remember(sources.size) {
                    List(sources.size) { FocusRequester() }
                }
                sources.forEachIndexed { i, source ->
                    val col = i % 2
                    val row = i / 2
                    MoviesStreamerButton(
                        source = source,
                        fontFamily = passionFont,
                        focusRequester = streamerFocus[i],
                        offsetX = colX[col],
                        offsetY = if (row < rowY.size) rowY[row]
                                  else rowY.last() + 55.dp * (row - rowY.lastIndex),
                        disableLeftSound  = col == 0,               // left column → mute left edge
                        disableRightSound = col == 1,               // right column → mute right edge
                        disableDownSound  = i + 2 >= sources.size,  // no button directly below → mute down
                        onClick = {
                            isTrailerPlaying = false; hasTrailerBeenPlayed = false
                            // Extractor sources → headless m3u8 extraction + ExoPlayer
                            // (overlay shows progress); everything else keeps the WebView
                            // path. Keyed off the source flag, not its name/slot.
                            if (source.useExtractor) launchVidApiExtract(source.name, source.url)
                            else onStreamingSourceSelected(source)
                        }
                    )
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
                        onMediaClick(media)
                    },
                    onNavigateUp = {
                        BlueHiveApplication.playHoverSound()
                        extrasFocusRequester.requestFocus()
                        extrasFocusRequester.requestFocus()
                        Log.d("SeekDebug", "R.drawable.button_focus_narrow_nonGlow should be the extra button's focus glow")
                    }
                )
            }
        }


        // Instant extraction overlay — last in Box so it renders on top of everything
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

        // "No dub available" toast
        if (showNoDub) {
            Text(
                "No dub available for this title",
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

        // ── "Checking servers" overlay (No path, phase 1) ───────────────────
        if (isCheckingServers) {
            ExtractionLoadingOverlay(
                status = "Checking the site for available servers…",
                stage  = 2
            )
        }

        // ── Instant "default server?" Yes/No prompt ─────────────────────────
        if (showDefaultPrompt) {
            DefaultServerPrompt(
                onDefault = {
                    showDefaultPrompt = false
                    // Snap focus back to the DuB/Sub button immediately so it
                    // glows during the 300ms animation window before the loading
                    // overlay appears.
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

        // ── Server picker (No path, phase 1 result) ─────────────────────────
        if (showServerPicker && serverOptions.isNotEmpty()) {
            ServerPickerOverlay(
                servers      = serverOptions,
                defaultIndex = serverDefaultIdx,
                audioLabel   = if (pendingDub) "Dub" else "Sub",
                onSelect     = { serverName ->
                    showServerPicker = false
                    // Same: light the original button back up during the 300ms
                    // window before the loading overlay takes over.
                    coroutineScope.launch {
                        awaitFrame()
                        try { lastStreamFocusRequester?.requestFocus() } catch (_: Exception) {}
                    }
                    pendingUrl?.let { launchExtract(it, pendingDub, serverName) }
                }
            )
        }

        // ── Transient "couldn't load" toast ─────────────────────────────────
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

        // ── Extractor error toast ───────────────────────────────────────────
        if (vidApiError != null) {
            Text(
                vidApiError!!,
                color = Color.White,
                fontSize = 18.sp,
                fontFamily = dongleRegular,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 70.dp)
                    .background(Color(0xE6000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 26.dp, vertical = 12.dp)
            )
        }

    }   // ← Box closes here
}   // ← MoviesDetailsScreenContent closes here


@Composable
fun MoviesFavoriteButton(
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
            toggledText = "- Remove from Favorites",
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
            durationMillis = 180,
            bounceBackDelayMillis = 140
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
fun MoviesExtrasButton(
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
            durationMillis = 180,
            bounceBackDelayMillis = 140
        ),
        externalToggled = isToggled,     // 🔹 NEW: drive visual state from outside
        onClick = {
            BlueHiveApplication.playClickSound()
            onClick()
        }
    )
}




@Composable
fun MoviesTrailerButton(
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
            durationMillis = 180,
            bounceBackDelayMillis = 140
        ),
        onClick = {
            BlueHiveApplication.playClickSound()
            onClick()
        }
    )
}


@Composable
fun MoviesRewindButton(
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
            durationMillis = 180,
            bounceBackDelayMillis = 140
        ),
        onClick = {
            BlueHiveApplication.playClickSound()
            onClick()
        }
    )
}


@Composable
fun MoviesFastForwardButton(
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
            durationMillis = 180,
            bounceBackDelayMillis = 140
        ),
        onClick = {
            BlueHiveApplication.playClickSound()
            onClick()
        }
    )
}


@Composable
fun MoviesStreamerButton(
    source: MoviesDetailsScreenCompose.StreamingSource,
    fontFamily: FontFamily,
    focusRequester: FocusRequester,
    offsetX: Dp,
    offsetY: Dp,
    disableLeftSound: Boolean = false,
    disableRightSound: Boolean = false,
    disableUpSound: Boolean = false,
    disableDownSound: Boolean = false,
    onClick: () -> Unit
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(
            text = if (source.name == "M Extract") "M Extract" else "Watch on ${source.name}",
            fontSize = 14f
        ),
        isToggleable = false,
        fontFamily = fontFamily,
        focusRequester = focusRequester,
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (disableLeftSound) {
                                // edge: no sound, no navigation
                                true
                            } else {
                                BlueHiveApplication.playHoverSound()
                                false   // let focus move normally
                            }
                        }
                        Key.DirectionRight -> {
                            if (disableRightSound) {
                                // edge: no sound, no navigation (fixes jump to Rewind)
                                true
                            } else {
                                BlueHiveApplication.playHoverSound()
                                false
                            }
                        }
                        Key.DirectionUp -> {
                            if (disableUpSound) {
                                true
                            } else {
                                BlueHiveApplication.playHoverSound()
                                false
                            }
                        }
                        Key.DirectionDown -> {
                            if (disableDownSound) {
                                // bottom-row buttons: no sound, but allow navigation
                                false
                            } else {
                                BlueHiveApplication.playHoverSound()
                                false
                            }
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
        dimensions = ModularButtonDimensions(
            mainWidth = 162.dp,
            mainHeight = 33.dp,
            mainYOffset = 0.7.dp,
            secondWidth = 162.dp,
            secondHeight = 22.dp,
            secondYOffset = 18.dp,
            shadowHeight = (12).dp,
            glowWidth = 185.8.dp,
            glowHeight = 55.8.dp,
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
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX = (-11.8).dp,
            offsetY = (-2.5).dp
        ),
        animationConfig = ModularButtonAnimationConfig(
            pressOffset = 2.5.dp,
            textOffsetDefault = 1.dp,
            textOffsetPressed = 2.4.dp,
            durationMillis = 180,
            bounceBackDelayMillis = 140
        ),
        onClick = {
            BlueHiveApplication.playClickSound()
            onClick()
        }
    )
}


@Composable
fun AnimeDubButton(
    fontFamily: FontFamily,
    focusRequester: FocusRequester,
    offsetX: Dp = 27.75.dp,
    offsetY: Dp = 332.5.dp,
    onClick: () -> Unit
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(text = "Watch in DuB", fontSize = 14f),
        isToggleable = false,
        fontFamily = fontFamily,
        focusRequester = focusRequester,
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> true   // left edge — block + no sound
                        Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                            BlueHiveApplication.playHoverSound(); false
                        }
                        else -> false
                    }
                } else false
            },
        dimensions = ModularButtonDimensions(
            mainWidth = 162.dp, mainHeight = 33.dp, mainYOffset = 0.7.dp,
            secondWidth = 162.dp, secondHeight = 22.dp, secondYOffset = 18.dp,
            shadowHeight = 12.dp, glowWidth = 185.8.dp, glowHeight = 55.8.dp,
            mainCornerRadius = 7f, secondCornerRadius = 8f,
        ),
        colors = ModularButtonColors(
            mainDefault   = Color(0xFFCC0000),
            mainToggled   = Color(0xFF7A0000),
            secondDefault = Color(0xFF7A0000),
            secondFocused = Color(0xFF8B1400),
            shadowColor   = Color(0x60000000),
            textFocused   = Color.White,
            textUnfocused = Color(0xFFD6D6D6)
        ),
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX = (-11.8).dp, offsetY = (-2.5).dp
        ),
        animationConfig = ModularButtonAnimationConfig(
            pressOffset = 2.5.dp, textOffsetDefault = 1.dp,
            textOffsetPressed = 2.4.dp, durationMillis = 180, bounceBackDelayMillis = 140
        ),
        onClick = { BlueHiveApplication.playClickSound(); onClick() }
    )
}

@Composable
fun AnimeSubButton(
    fontFamily: FontFamily,
    focusRequester: FocusRequester,
    offsetX: Dp = 228.dp,
    offsetY: Dp = 332.5.dp,
    onClick: () -> Unit
) {
    ModularButton(
        textConfig = ModularButtonTextConfig(text = "Watch in SuB", fontSize = 14f),
        isToggleable = false,
        fontFamily = fontFamily,
        focusRequester = focusRequester,
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionRight -> true  // right edge — block + no sound
                        Key.DirectionLeft, Key.DirectionUp, Key.DirectionDown -> {
                            BlueHiveApplication.playHoverSound(); false
                        }
                        else -> false
                    }
                } else false
            },
        dimensions = ModularButtonDimensions(
            mainWidth = 162.dp, mainHeight = 33.dp, mainYOffset = 0.7.dp,
            secondWidth = 162.dp, secondHeight = 22.dp, secondYOffset = 18.dp,
            shadowHeight = 12.dp, glowWidth = 185.8.dp, glowHeight = 55.8.dp,
            mainCornerRadius = 7f, secondCornerRadius = 8f,
        ),
        colors = ModularButtonColors(
            mainDefault   = Color(0xFFCCA000),
            mainToggled   = Color(0xFF6B5000),
            secondDefault = Color(0xFF7A5500),
            secondFocused = Color(0xFF8B6500),
            shadowColor   = Color(0x60000000),
            textFocused   = Color.White,
            textUnfocused = Color(0xFFD6D6D6)
        ),
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX = (-11.8).dp, offsetY = (-2.5).dp
        ),
        animationConfig = ModularButtonAnimationConfig(
            pressOffset = 2.5.dp, textOffsetDefault = 1.dp,
            textOffsetPressed = 2.4.dp, durationMillis = 180, bounceBackDelayMillis = 140
        ),
        onClick = { BlueHiveApplication.playClickSound(); onClick() }
    )
}


//0x33000000 = 20% opacity (very light)
//0x50000000 = 31% opacity (your current - too light)
//0x20000000 = 50% opacity (medium)
//0xCC000000 = 80% opacity (recommended)
//0xE6000000 = 90% opacity (strong)
//0xFF000000 = 100% opacity (full black)