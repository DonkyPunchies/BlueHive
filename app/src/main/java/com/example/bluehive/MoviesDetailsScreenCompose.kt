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
import androidx.compose.ui.focus.focusRequester
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.SourceCatalog
import com.example.bluehive.api.ExtractOutcome
import com.example.bluehive.api.FavoriteRequest
import com.example.bluehive.utilities.AppShapes
import androidx.compose.ui.text.style.TextAlign
import com.example.bluehive.sidebarComponents.FavoritesScreenActivity
import com.example.bluehive.sidebarComponents.HistoryScreenActivity
import com.example.bluehive.searchBarComponent.SearchBarActivity
import com.example.bluehive.api.isJapaneseAnimation
import com.example.bluehive.api.buildJapaneseAnimationSources
import com.example.bluehive.webview.miruro.MiruroPlayerActivity
import com.example.bluehive.auth.SessionManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.bluehive.webview.miruro.MiruroExtractorActivity
import com.example.bluehive.webview.BeeLogo
import com.example.bluehive.webview.ExtractionFocusTrap
import com.example.bluehive.webview.ExtractionLoadingOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.android.awaitFrame
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.bluehive.webview.hls.HlsExtract
import com.example.bluehive.webview.hls.HlsPlayerActivity
import com.example.bluehive.api.extractStreamAwait



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


/**
 * The source buttons for this film. Backend-owned — see the twin on the TV
 * screen for the full note, and SourceCatalog for the fallback chain.
 */
private fun getStreamingSources(mediaType: String, mediaId: Int): List<MoviesDetailsScreenCompose.StreamingSource> {
    if (mediaType.equals("movie", true)) {
        return com.example.bluehive.api.SourceCatalog
            .sources(BlueHiveApplication.appContext, "movie", mediaId)
            .map { MoviesDetailsScreenCompose.StreamingSource(it.label, it.name, it.url) }
    }
    return when(mediaType) {
        "movie" -> listOf(
            MoviesDetailsScreenCompose.StreamingSource(
                "Royal Jelly",
                "VidNest",
                "https://vidnest.fun/movie/$mediaId"
            ),
            // VidLove — added 2026-08-30 as the second source, beside VidNest. Backend-extracted:
            // "VidLove" is listed in usesBackendExtraction and keyed as "vidlove"
            // in the backend's _SOURCES, and the URL shape here MUST match that
            // template exactly or the two request different pages.
            //
            // PLACED BESIDE VIDNEST DELIBERATELY. Both pass the ISP filter
            // with cleartext SNI (verified 2026-08-30: a2.shows.st and
            // tiktoks.animanga.fun return certificates, moon.peakstorm.top and
            // finalroom.top are severed), and neither appears in
            // relay_sessions at all — while moon.peakstorm.top alone accounts
            // for 22 of the last 24 hours' 34 relays. Order is the cheapest
            // lever on relay volume, so the two clean sources lead.
            //
            // Verified before listing: both endpoints extract (movie 27205 and
            // tv 1396 S1E1), and its CDNs a2.shows.st / d.shows.st both publish
            // ECH with alpn=h3 — so it is covered by the QUIC/ECH path rather
            // than relying on the relay.
            MoviesDetailsScreenCompose.StreamingSource(
                "Nectar Stream",
                "VidLove",
                "https://player.vidlove.cc/embed/movie/$mediaId"
            ),
            MoviesDetailsScreenCompose.StreamingSource(
                "Queen Stream",
                "VidFast",
                "https://vidfast.pro/movie/$mediaId?autoPlay=true",
            ),
            MoviesDetailsScreenCompose.StreamingSource(
                "Purple Stream",
                "MoviesAPI",
                    "https://moviesapi.to/movie/$mediaId"
            )
            // VidSrc (\"Swarm Stream\") and VidApi ("Honeycomb") were removed on
            // 2026-08-30 at the user's request. Both still work server-side — the
            // 403s that made them look broken were our own Referer/Origin headers,
            // fixed the same day — so this is a menu decision, not a capability
            // one. Re-adding them is just restoring the entries; usesBackendExtraction
            // already matches both names.
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

    // Playback settings are per profile. Re-reading on entry is how a
    // profile switch mid-session is picked up; it no-ops when the profile
    // has not changed, so this costs nothing on a normal navigation.
    LaunchedEffect(Unit) { PlaybackPrefs.load(context) }

    // Decode the loading-overlay bee logo now, while the screen is first
    // composing and memory is comparatively free, and cache it process-wide so
    // the Dub/Sub overlay never decodes it under pressure (the 2 GB crash).
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { BeeLogo.prime(context) }
    }

    // ✅ Add state for extras toggle and recommendations
    var isExtrasToggled by remember(startInExtras) { mutableStateOf(startInExtras) }

    // ── Back collapses Extras before it leaves the page ─────────────────────
    //
    // Pressing back while the recommendations row is showing returns to the
    // normal state; a second press then leaves, which is what "back" means
    // everywhere else — undo the last thing you did, not the whole journey.
    //
    // DELIBERATELY REGISTERED HERE, above the overlay handlers further down.
    // OnBackPressedDispatcher runs the MOST RECENTLY added enabled callback, and
    // Compose adds them in composition order — so anything declared below this
    // (the server picker, the extraction guard) still wins while it is open. Put
    // this last and back would collapse Extras out from under an open overlay.
    //
    // When disabled it does not intercept at all, so back falls through to the
    // Activity's own callback and the page closes exactly as before.
    BackHandler(enabled = isExtrasToggled) {
        BlueHiveApplication.playBackOutSound()
        isExtrasToggled = false
    }

    var recommendations by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()
    var blockHeaderInitialFocus by remember(startInExtras) { mutableStateOf(startInExtras) }

    var isTrailerPlaying by remember { mutableStateOf(false) }
    var hasTrailerBeenPlayed by remember { mutableStateOf(false) }

    /**
     * Silence the backdrop trailer. Called where a playback ACTUALLY starts, and
     * deliberately NOT when a source button merely opens a prompt.
     *
     * WHY THE DISTINCTION. This screen used to clear the flags in each source
     * button's onClick, so the trailer died the instant you pressed VidFast even
     * though the next thing on screen was "Watch with our player, or open the
     * site directly?" — the backdrop went silent and static while you were still
     * choosing. The TV screen never behaved that way: a backend source only sets
     * its choice state and the trailer plays on until something really launches.
     * This matches it.
     *
     * Idempotent, so a path crossing two launch points costs nothing.
     */
    fun stopTrailer() {
        isTrailerPlaying = false
        hasTrailerBeenPlayed = false
    }
    var shouldExtractTrailer by remember { mutableStateOf(false) }

    // Focus requesters
    val favoriteFocusRequester = remember { FocusRequester() }
    val extrasFocusRequester = remember { FocusRequester() }
    val trailerFocusRequester = remember { FocusRequester() }

    var isExtractingTrailer by remember { mutableStateOf(false) }
    var isExtracting   by remember { mutableStateOf(false) }
    // Which attempt of the failover chain is on screen. Exists to re-key the
    // stage timer further down; see the comment there.
    var extractAttempt by remember { mutableIntStateOf(0) }

    // ── BACK during an extraction ───────────────────────────────────────
    //
    // Two pieces, and BOTH are needed. The job handle lets BACK stop the
    // client's polling loop. The flag is what actually prevents the launch:
    // the extraction is wrapped in runCatching, which CATCHES the
    // CancellationException, so a cancelled coroutine still falls through to
    // `body == null` and would raise the failure modal. Cancelling alone
    // fixes the wasted requests and none of the user-visible damage.
    //
    // Without this, pressing BACK hid the overlay but the coroutine ran on —
    // up to ExtractAwait.DEADLINE_MS (120s) — and then launched the player or
    // threw a full-screen error over whatever the user had moved on to.
    var extractJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var extractCancelled by remember { mutableStateOf(false) }
    var extractStage   by remember { mutableIntStateOf(0) }
    var extractPending by remember { mutableStateOf(false) }   // guards the 1.5s pre-launch window
    var showNoDub      by remember { mutableStateOf(false) }   // transient "No dub available" toast
    // Shown when this anime movie has no AniList mapping yet, so there is no Miruro
    // URL to extract from. Without this, DuB/SuB were a SILENT no-op — the guard
    // below just found sources.size <= 2 and did nothing at all.
    var showNotYetAvailable by remember { mutableStateOf(false) }

    // ── Anime server-selection flow ─────────────────────────────────────────
    var showDefaultPrompt by remember { mutableStateOf(false) } // instant "default server?" Yes/No
    var isCheckingServers by remember { mutableStateOf(false) } // phase 1: enumerating servers
    var showServerPicker  by remember { mutableStateOf(false) } // phase 1 result: the picker
    var showServerError   by remember { mutableStateOf(false) } // transient failure toast

    // ── Backend-extracted sources (VidFast / VidEasy / VidSpark) ───────────────
    // Same flow the TV screen already has: picking one raises a two-button choice
    // instead of playing immediately — ExoPlayer (server-side m3u8 extraction,
    // light + resume-capable) or Gecko (the embed, as before).
    var backendChoiceFor by remember { mutableStateOf<MoviesDetailsScreenCompose.StreamingSource?>(null) }

    // The playback-method setting used to be reached from a browser button in
    // this screen's bottom-right corner, mirroring the TV screen. Both now point
    // at Settings > Playback method instead. PlaybackPrefs is still READ below --
    // only the way to change it moved.
    var isBackendExtract by remember { mutableStateOf(false) }
    var backendStatus    by remember { mutableStateOf("") }
    /** Non-null shows ExtractionErrorOverlay — a failure the user can act on. */
    var extractionError  by remember { mutableStateOf<String?>(null) }
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

    // THE RULE: back out of a player and focus returns to the streaming button that
    // launched it, never the Favorites button at the top.
    //
    // ...but YIELD while any modal is up. "Choose a server" on a cache MISS launches
    // MiruroExtractorActivity to enumerate, so this screen genuinely pauses and
    // RESUMES — and the resume that delivers the server list is the same resume this
    // restoration fires on. It won that race, pulling focus onto the DuB/Sub button
    // while the picker sat open on top, so the D-pad drove the details screen behind
    // the overlay.
    //
    // It reads as episode-specific because the SECOND press hits serverListCache and
    // opens the picker inline — no activity launch, no resume, no race.
    //
    // extractionError is in the list for the same reason: that overlay appears on the
    // very resume this fires on. The TV screen already carried both guards; this one
    // carried neither.
    RestoreFocusOnPlayerReturn("MovieDetailsFocus") {
        if (extractionError != null) return@RestoreFocusOnPlayerReturn emptyList()
        if (showServerPicker || isCheckingServers || showDefaultPrompt) {
            return@RestoreFocusOnPlayerReturn emptyList()
        }
        listOfNotNull(lastStreamFocusRequester)
    }

    // Extractor m3u8-extraction error reason (shown as a toast; auto-dismisses).

    // Back closes whichever picker/prompt is open instead of leaving the screen.
    //
    // backendChoiceFor MUST be listed. It is the "Watch on ExoPlayer / Watch on
    // Gecko" prompt, and without it here BACK found no handler, fell through to
    // the Activity, and closed the whole details page — losing the user's place
    // for what should have been a dismissal. The TV screen has always covered its
    // equivalent (vidFastChoiceFor); only this screen was missing it.
    BackHandler(enabled = showDefaultPrompt || showServerPicker || backendChoiceFor != null) {
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
            // Dismiss the player prompt back to the source buttons, which are
            // still on screen underneath it. Focus is restored to the button that
            // opened it, or a D-pad user is left with nothing selected.
            backendChoiceFor != null -> {
                backendChoiceFor = null
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

    // ── Automatic server failover ────────────────────────────────────────────
    // When the default (or chosen) server is down — a hard extract failure, or a
    // dub request that fell back to sub — walk the rest of the provider list until
    // one yields a real stream. Works for any anime movie, dub or sub. triedServers
    // is reset at the start of each play (default attempt); lastExtractServer is the
    // provider of the in-flight extract (null = Miruro's default).
    val triedServers = remember { mutableSetOf<String>() }
    var lastExtractServer by remember { mutableStateOf<String?>(null) }
    // Set by the result handler to re-extract with the next server; a LaunchedEffect
    // (declared after launchExtract) performs the actual re-launch. This state hop
    // breaks the launchExtract ⇄ extractorLauncher reference cycle. (url, dub, server)
    var failoverRequest by remember { mutableStateOf<Triple<String, Boolean, String?>?>(null) }
    fun pickNextServer(url: String, dub: Boolean): String? {
        val list = serverListCache[serverCacheKey(url, dub)]?.first
            ?: serverOptions.takeIf { it.isNotEmpty() }
            ?: return null
        val untried = list.filter { it.name !in triedServers }
        if (untried.isEmpty()) return null
        val want = if (dub) "dub" else "sub"
        // Prefer a provider whose tags advertise the requested track; else any untried.
        return (untried.firstOrNull { o -> o.tags.any { it.equals(want, true) } } ?: untried.first()).name
    }

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
            val dubFallback = data?.getBooleanExtra(MiruroExtractorActivity.RESULT_DUB_FALLBACK, false) == true
            val capUrl  = pendingUrl

            // ── Cache any server list captured in this same pass FIRST, so failover
            //    below can consult it (a default extract carries the provider list). ─
            val capNames = data?.getStringArrayListExtra(MiruroExtractorActivity.RESULT_SERVER_NAMES)
            val capTags  = data?.getStringArrayListExtra(MiruroExtractorActivity.RESULT_SERVER_TAGS)
            val capDef   = data?.getIntExtra(MiruroExtractorActivity.RESULT_SERVER_DEFAULT_INDEX, 0) ?: 0
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
            // Mark the provider we just tried (named failover server, else Miruro's default).
            val triedName = lastExtractServer
                ?: capNames?.let { it.getOrNull(capDef.coerceIn(0, it.size - 1)) }
            triedName?.let { triedServers.add(it) }

            if (m3u8.isNullOrBlank()) {
                // Nothing captured — server likely down. Fail over before erroring.
                val next = if (capUrl != null) pickNextServer(capUrl, pendingDub) else null
                if (next != null && capUrl != null) {
                    Log.d("scraper", "🔁 no stream — auto-failover to server '$next'")
                    triedServers.add(next)
                    failoverRequest = Triple(capUrl, pendingDub, next)
                } else {
                    Log.e("scraper", "M Extract: OK result but no m3u8")
                    showServerError = true
                }
                return@rememberLauncherForActivityResult
            }

            // Dub requested but we got the sub last-resort → try the next server.
            if (pendingDub && dubFallback) {
                val next = if (capUrl != null) pickNextServer(capUrl, true) else null
                if (next != null && capUrl != null) {
                    Log.d("scraper", "🔁 dub server down — auto-failover to server '$next'")
                    triedServers.add(next)
                    failoverRequest = Triple(capUrl, true, next)
                    return@rememberLauncherForActivityResult
                }
                Log.w("scraper", "🔁 dub unavailable on every server — playing sub as last resort")
            }

            // ── Play the captured stream ─────────────────────────────────────
            // DIRECT FIRST, relay only on failure — see MiruroPlayerActivity. The
            // relay works around ISPs that DNS-hijack the CDN, but costs ~1 GB of
            // backend transfer each way per episode, so it is now the fallback
            // rather than the default path.
            val proxied = animeRelayUrl(
                m3u8 = m3u8, tmdbId = tmdbId, mediaType = "anime_movie",
                title = mediaTitle, profileId = favoriteProfileId,
                sourceName = miruroSourceLabel(null, capNames?.getOrNull(capDef)),
            )
            Log.d("scraper", "M Extract → direct=$m3u8  (relay fallback ready)")
            // ── Record the watch ────────────────────────────────────────
            // The DuB/SuB path goes straight to MiruroPlayerActivity and never
            // touches MainWebViewer, which is the only place watch history was
            // otherwise logged — so anime watched this way was invisible to the
            // backend. It IS logged, but by the player itself once video is
            // actually rolling (onIsPlayingChanged), NOT here: firing on a
            // successful extract counted a watch even when the stream never
            // played. Season/episode are omitted — these are movies.
            context.startActivity(
                Intent(context, MiruroPlayerActivity::class.java)
                    .putExtra("M3U8_URL", m3u8)                  // try the CDN directly
                    .putExtra("M3U8_FALLBACK_URL", proxied)      // relay if that's blocked
                    .putExtra("REFERER", referer)
                    .putExtra("UA", ua)
                    .putExtra("PROFILE_ID",  favoriteProfileId)
                    .putExtra("MEDIA_ID",    tmdbId.toString())
                    .putExtra("MEDIA_TYPE",  mediaType)
                    .putExtra("MEDIA_TITLE", mediaTitle)
                    .putExtra("SOURCE_NAME", miruroSourceLabel(null, capNames?.getOrNull(capDef)))
                    // AUDIO EXISTS ONLY FOR MID-PLAYBACK RE-EXTRACTION, and it is not
                    // optional. Miruro signs its segment URLs with a token that dies
                    // after roughly nine minutes — MEASURED 2026-08-27, Mushoku Tensei
                    // s1e6 froze 121 segments in and every request after that was a 403
                    // — so recovering means fetching a FRESH manifest mid-episode.
                    // Asking for one without naming the track hands a dub viewer the
                    // sub, which is worse than the freeze it replaces: it looks like it
                    // worked.
                    //
                    // Derived from the SAME value as SOURCE_NAME, deliberately. If those
                    // two ever disagree, history records one track while the player
                    // refetches the other.
                    .putExtra("AUDIO", if (pendingDub) "dub" else "sub")
                    // SERVER pins the provider for any mid-playback re-extraction, and
                    // HOW it is resolved matters as much as that it is sent. triedName is
                    // what the failover bookkeeping just computed: the manually chosen
                    // provider when there was one, and otherwise the DEFAULT resolved to
                    // its actual name instead of left null.
                    //
                    // Sending null would re-extract against whatever Miruro defaults to at
                    // that moment, which is not necessarily the provider this playback
                    // started on — the list is reordered as servers come and go, so a
                    // recovery could silently move the viewer to a different source
                    // mid-episode.
                    .putExtra("SERVER", triedName)
            )
        } else {
            val data   = result.data
            val noDub  = data?.getBooleanExtra(MiruroExtractorActivity.RESULT_NO_DUB, false) == true
            val failed = data?.getBooleanExtra(MiruroExtractorActivity.RESULT_FAILED, false) == true
            when {
                noDub  -> { Log.w("scraper", "M Extract: no dub available"); showNoDub = true }
                failed -> {
                    // Extraction error — server likely down. Fail over to the next one.
                    val u = pendingUrl
                    val next = if (u != null) pickNextServer(u, pendingDub) else null
                    if (next != null && u != null) {
                        Log.d("scraper", "🔁 extract failed — auto-failover to server '$next'")
                        triedServers.add(next)
                        failoverRequest = Triple(u, pendingDub, next)
                    } else {
                        Log.w("scraper", "M Extract: failed"); showServerError = true
                    }
                }
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

    // Which chain step the player currently on screen came from. Read when it
    // reports a playback failure, so the chain keeps counting rather than
    // restarting -- see the TV screen's twin.
    var chainAttempt by remember { mutableIntStateOf(1) }

    // Set by the launcher below, consumed by the LaunchedEffect after
    // startBuiltInPlayer. A hop through state because the launcher has to exist
    // before extractAndPlayMovie does, and its callback is not a coroutine.
    // (dead source, where it died in ms) -- the next source starts there.
    var playbackFailedSource by remember { mutableStateOf<Pair<String, Long>?>(null) }

    val playerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == HlsPlayerActivity.RESULT_SOURCE_FAILED) {
            val atMs = result.data?.getLongExtra(HlsPlayerActivity.EXTRA_FAILED_AT_MS, 0L) ?: 0L
            result.data?.getStringExtra(HlsPlayerActivity.EXTRA_FAILED_SOURCE)
                ?.takeIf { it.isNotBlank() }
                ?.let { playbackFailedSource = it to atMs }
        }
    }

    /**
     * Server-side extraction for VidFast / VidEasy / VidSpark, then our own player.
     *
     * The movie twin of TVShowsDetailsScreenCompose.extractAndPlay — same endpoint,
     * same contract, minus season/episode. Kept as its own function rather than
     * shared because the two screens hold different state (no pendingSeason here)
     * and the Intent extras differ.
     *
     * WHY SERVER-SIDE: `vidfast.pro` is refused to every client on a Shield-filtered
     * line — the filter reads the hostname out of the TLS ClientHello and severs
     * the handshake. The backend loads the embed where the filter isn't and returns
     * only the URL; the TV still streams from the CDN directly, so no video bytes
     * cross the backend.
     *
     * No retry loop: the server caches per title and deduplicates concurrent
     * requests, so a second client attempt just re-asks for the same answer.
     */
    suspend fun extractAndPlayMovie(
        src: MoviesDetailsScreenCompose.StreamingSource,
        /** 1 for the source the viewer pressed; higher for each chain step. */
        attempt: Int = 1,
        /** The sources already tried and failed, oldest first. */
        tried: List<String> = emptyList(),
        /** Where to start, in ms: 0 for a press, the dead source's spot for a failover. */
        startAtMs: Long = 0L,
    ) {
        extractCancelled = false
        isExtracting     = true
        isBackendExtract = true
        extractPending   = true
        extractStage     = 1
        // EVERY attempt stamps this, not only the failover ones. Setting it only
        // on failover left it holding the previous title's last attempt number,
        // so the first failover of the NEXT title could write the same value,
        // fail to change the key, and freeze the stage dots -- the exact bug
        // this field exists to prevent.
        extractAttempt   = attempt
        backendStatus    = "Finding your stream…"

        // Display name IS the backend key once lowercased — see usesBackendExtraction.
        val sourceKey = src.name.lowercase()

        val startedAt = System.currentTimeMillis()
        val result = runCatching {
            ApiClient.bluehiveApi.extractStreamAwait(
                type   = "movie",
                tmdbId = tmdbId,
                source = sourceKey,
                // Accounting only — see the TV screen's extractAndPlay.
                mtype   = mediaType,
                title   = mediaTitle,
                profile = favoriteProfileId,
                device  = SessionManager.get().deviceFingerprint,
            )
        }
        val tookMs = System.currentTimeMillis() - startedAt

        isExtracting     = false
        isBackendExtract = false
        extractPending   = false
        backendStatus    = ""

        // The user left. Do not raise an error and do not start a player over
        // whatever they are looking at now.
        if (extractCancelled) {
            Log.d("scraper-vidapi", "extraction finished after BACK — discarding result")
            return
        }

        val body = result.getOrNull()?.takeIf { it.isSuccessful }?.body()
        if (body == null || body.m3u8.isBlank()) {
            val resp   = result.getOrNull()
            val code   = resp?.code()
            val reason = parseExtractReason(resp)
            Log.e("scraper-vidfast", "movie extraction failed (http=$code reason=$reason src=${src.name}) " +
                    "${result.exceptionOrNull()?.javaClass?.simpleName ?: ""}")
            // Failover — see the twin on the TV screen for the full note.
            val next = if (SourceCatalog.failoverEnabled && attempt < SourceCatalog.maxAttempts) {
                SourceCatalog.nextInChain(
                    BlueHiveApplication.appContext, "movie", mediaId, 0, 0, src.name,
                ) { usesBackendExtraction(it) }
            } else null

            if (next != null) {
                Log.e("scraper-vidfast",
                      "failover ${attempt}/${SourceCatalog.maxAttempts}: " +
                      "${src.name} failed ($reason) -> trying ${next.name}")
                isExtracting     = true
                isBackendExtract = true
                extractPending   = true
                extractStage     = 1
                backendStatus    = "${src.coverName} didn\u2019t work \u2014 trying ${next.label}\u2026"
                extractAndPlayMovie(
                    MoviesDetailsScreenCompose.StreamingSource(next.label, next.name, next.url),
                    attempt + 1,
                    tried + src.name,
                    startAtMs,
                )
                return
            }

            extractionError =
                extractionErrorMessage(code, reason, src.name, "movie", tried + src.name)
            return
        }

        Log.d("scraper-vidfast", "▶️ movie ${src.name} " +
                "playlist=${runCatching { android.net.Uri.parse(body.m3u8).host }.getOrNull()} " +
                "cached=${body.cached} expires_in=${body.expires_in}s extract=${tookMs}ms")

        // launch(), not startActivity(): the result is how a playback failure
        // gets back here to continue the chain. This was startActivity until
        // 2026-10-19, so a movie that died in the player never failed over.
        chainAttempt = attempt
        playerLauncher.launch(
            Intent(context, HlsPlayerActivity::class.java)
                .putExtra("M3U8_URL", body.m3u8)
                // DIRECT FIRST, relay only after a confirmed failure. Re-extracting
                // cannot fix a blocked CDN — the source pins one host per title and
                // returns the same one — so this is what actually recovers a
                // filtered line. Already tagged with the title by /api/extract, so
                // the fallback lands in bluehive.relay_sessions attributed rather
                // than as an anonymous byte count.
                //
                // The player mints the grant itself on the failure path
                // (HlsPlayerActivity.switchToRelay -> mintRelayGrant); passing the
                // URL is all this screen has to do. Without this extra, relayUrl is
                // null in the player and switchToRelay returns false immediately —
                // which is why movies had no relay at all before.
                .putExtra("M3U8_FALLBACK_URL", body.proxy_url)
                // The EMBED PAGE origin, not the manifest host — the CDN validates
                // it, and sending the manifest's own host 403s every segment while
                // extraction still looks like it worked.
                .putExtra("REFERER", body.referer)
                .putExtra("IS_HLS", body.isHls ?: true)
                .putExtra("UA", body.user_agent)
                .putExtra("TMDB_ID", tmdbId)
                // Blank ON PURPOSE: these sources have no on-device extractor. The
                // player reads it as "ask the backend instead" and calls
                // /api/extract?refresh=1 — see HlsPlayerActivity.reextractViaBackend.
                .putExtra(HlsExtract.EXTRA_EXTRACTOR_CLASS, "")
                .putExtra("EMBED_URL", src.url)
                // Which source to re-ask on a mid-play failure; without it the
                // player would always re-extract VidFast.
                .putExtra("EXTRACT_SOURCE", sourceKey)
                .putExtra("PROFILE_ID",  favoriteProfileId)
                .putExtra("MEDIA_TYPE",  mediaType)
                .putExtra("MEDIA_TITLE", mediaTitle)
                .putExtra("SOURCE_NAME", src.name)
                .putExtra("SUBTITLE_URL", body.subtitle_url)
                .putExtra(HlsPlayerActivity.EXTRA_START_AT_MS, startAtMs)
        )
    }

    /** See the TV screen's twin: one body shared by the prompt's default button
     *  and the skip-the-prompt path, so the two cannot drift apart. */
    fun startBuiltInPlayer(src: MoviesDetailsScreenCompose.StreamingSource) {
        stopTrailer()
        try { BlueHiveApplication.coilImageLoader.memoryCache?.clear() } catch (_: Exception) {}
        extractJob = coroutineScope.launch { extractAndPlayMovie(src) }
    }

    // ── FAILOVER, PLAYBACK HALF ─────────────────────────────────────────────
    // The extraction half lives inside extractAndPlayMovie; this is the case it
    // cannot see -- a source whose url looked fine and then died in the player,
    // which only the player knows and reports through its activity result.
    //
    // MEASURED 2026-10-19, In the Beginning: VidNest died at 1:30, the player
    // reported the dead source, and nothing here was listening -- the viewer was
    // left on the buttons while MoviesAPI had the film the whole time.
    //
    // THE NEXT STEP RUNS IN THE SCREEN'S SCOPE, NOT THIS EFFECT'S. Clearing the
    // key below recomposes and cancels this effect, so an extraction started
    // inside it would die at its first suspension -- and runCatching would read
    // that cancellation as a failed extraction and race down the whole chain.
    // Holding it in extractJob also lets BACK cancel it like any other attempt.
    LaunchedEffect(playbackFailedSource) {
        val (failed, atMs) = playbackFailedSource ?: return@LaunchedEffect
        playbackFailedSource = null

        val next = if (SourceCatalog.failoverEnabled &&
                       chainAttempt < SourceCatalog.maxAttempts) {
            SourceCatalog.nextInChain(
                BlueHiveApplication.appContext, "movie", mediaId, 0, 0, failed,
            ) { usesBackendExtraction(it) }
        } else null

        if (next == null) {
            Log.e("scraper-vidfast",
                  "movie playback failed on $failed — chain exhausted, staying put")
            return@LaunchedEffect
        }
        Log.e("scraper-vidfast",
              "failover ${chainAttempt}/${SourceCatalog.maxAttempts} (playback): " +
              "$failed died in the player at ${atMs}ms -> trying ${next.name}")
        val step = chainAttempt + 1
        extractJob = coroutineScope.launch {
            extractAndPlayMovie(
                MoviesDetailsScreenCompose.StreamingSource(next.label, next.name, next.url),
                step,
                listOf(failed),
                atMs,
            )
        }
    }


    // ── BACK cancels an extraction in progress ───────────────────────
    // The loading overlay covers windows where THIS screen is focused -- the gap
    // between failover attempts, and the moments around a request starting and
    // finishing. BACK there used to fall through and leave the details screen
    // entirely.
    BackHandler(enabled = isExtracting || extractPending) {
        Log.d("scraper-vidapi", "↩️ back during extraction — cancelling")
        extractCancelled      = true
        extractJob?.cancel()
        extractJob            = null
        extractPending        = false
        isExtracting          = false
        extractStage          = 0
        coroutineScope.launch {
            awaitFrame()
            try { lastStreamFocusRequester?.requestFocus() } catch (_: Exception) {}
        }
    }



    // Launch a real extraction (default server when server == null).
    // extractPending is set immediately to block double-fire during the animation
    // window. The loading overlay itself is delayed 300ms so the button press
    // animation fully completes before the scrim appears.
    /**
     * Mirror of the TV screen's ensureServerList — see there for why the
     * "Default server" path has no list to fail over through until this fetches
     * one, and why warming it for the track that just played is now cheap.
     */
    suspend fun ensureServerList(url: String, dub: Boolean) {
        if (serverListCache[serverCacheKey(url, dub)] != null) return
        val list = runCatching {
            ApiClient.bluehiveApi.extractServers(
                type   = "movie",
                tmdbId = tmdbId,
                audio  = if (dub) "dub" else "sub",
                device = SessionManager.get().deviceFingerprint,
            )
        }.getOrNull()?.takeIf { it.isSuccessful }?.body() ?: return
        val opts = list.servers.map { ServerOption(it.name, emptyList()) }
        if (opts.isEmpty()) return
        serverListCache[serverCacheKey(url, dub)] =
            opts to list.servers.indexOfFirst { it.is_default }.coerceAtLeast(0)
    }

    /**
     * Anime MOVIE via the BACKEND extractor.
     *
     * Mirror of the TV screen's extractAnimeViaBackend — see there for the full
     * reasoning. Same restriction: DEFAULT PATH ONLY (sub, default server),
     * because dub needs the Language dropdown and a named server needs the
     * Provider dropdown, and neither is expressible as a page load.
     *
     * No season/episode: /api/anime/resolve maps a movie straight to its AniList
     * id with a null episode, and the miruro movie template omits ?ep= entirely.
     *
     * @param acceptAnyTrack play the wrong track anyway — set only once every
     *        dub server has been tried. See the TV screen for the reasoning.
     */
    suspend fun extractAnimeMovieViaBackend(dub: Boolean = false,
                                            server: String? = null,
                                            acceptAnyTrack: Boolean = false): ExtractOutcome {
        val body = runCatching {
            ApiClient.bluehiveApi.extractStreamAwait(
                type    = "movie",
                tmdbId  = tmdbId,
                source  = "miruro",
                mtype   = "anime_movie",
                title   = mediaTitle,
                profile = favoriteProfileId,
                device  = SessionManager.get().deviceFingerprint,
                // Sent always — "sub" is part of the server's cache key, not a
                // no-op, so omitting it would extract the same stream under a
                // second key.
                audio   = if (dub) "dub" else "sub",
                server  = server,
            )
        }.getOrNull()?.takeIf { it.isSuccessful }?.body()

        if (body == null || body.m3u8.isBlank()) return ExtractOutcome.MISS

        // Dub asked for, sub delivered — see the TV screen for the full note. A
        // null `audio` is an older backend: unknowable, so treat it as before.
        val wrongTrack = dub && body.audio?.equals("dub", ignoreCase = true) == false
        if (wrongTrack && !acceptAnyTrack) {
            Log.e("scraper", "🎧 asked for dub, backend returned '${body.audio}'" +
                    " (server=${server ?: "default"}) — not playing it yet")
            return ExtractOutcome.WRONG_TRACK
        }
        val playingDub = dub && !wrongTrack

        Log.d("scraper", "M Extract (backend movie audio=${if (dub) "dub" else "sub"}" +
                "${server?.let { " server=$it" } ?: ""}) → ${body.m3u8.take(60)} cached=${body.cached}")
        context.startActivity(
            Intent(context, MiruroPlayerActivity::class.java)
                .putExtra("M3U8_URL", body.m3u8)
                // Tagged server-side already, so no animeRelayUrl() here — that
                // helper exists for the on-device path, where the backend never
                // sees the playback and the relay row would be anonymous.
                .putExtra("M3U8_FALLBACK_URL", body.proxy_url)
                .putExtra("REFERER", body.referer)
                .putExtra("IS_HLS", body.isHls ?: true)
                .putExtra("UA", body.user_agent)
                .putExtra("PROFILE_ID",  favoriteProfileId)
                .putExtra("MEDIA_ID",    tmdbId.toString())
                .putExtra("MEDIA_TYPE",  mediaType)
                .putExtra("MEDIA_TITLE", mediaTitle)
                // Reflects the track actually extracted — this string is what
                // watch history and relay_sessions record, so a hardcoded "Sub"
                // would mislabel every dub playback. `playingDub`, not `dub`: a
                // last-resort sub is recorded as the sub it is.
                .putExtra("SOURCE_NAME", miruroSourceLabel(body.servers, server))
                // See the on-device site above for why AUDIO is carried. `playingDub`
                // rather than `dub` for the same reason SOURCE_NAME uses it: a
                // last-resort sub must re-extract as the sub it actually is.
                //
                // SERVER is best-effort and only exists on this path — the backend was
                // told which one to use, so a refetch can ask for the same one instead
                // of redrawing whichever the site defaults to. Null is fine and simply
                // means "no preference".
                .putExtra("AUDIO", if (playingDub) "dub" else "sub")
                // Manual selection wins; failing that, resolve the default to its
                // name so a refetch returns to the SAME provider rather than redrawing
                // whatever the site defaults to now. Null only when the picker never
                // ran and there is nothing to resolve from — the backend then picks
                // its own default, exactly as it did for the original extraction.
                .putExtra("SERVER", server ?: serverOptions.getOrNull(serverDefaultIdx)?.name)
        )
        return ExtractOutcome.PLAYED
    }

    val launchExtract: (String, Boolean, String?) -> Unit = { url, dub, server ->
        Log.d("scraper", "M Extract (dub=$dub, server=${server ?: "default"}) → $url")
        // Inside the launcher rather than at its call sites: this is reached
        // from the DuB/SuB prompt, the SERVER PICKER and the failover retry,
        // and a future caller should not have to remember. Idempotent.
        stopTrailer()
        // Fresh play (default attempt) → reset the failover trail. Track the in-flight
        // provider so its result can mark it tried.
        if (server == null) triedServers.clear()
        lastExtractServer = server
        if (!isExtracting && !extractPending && !isCheckingServers) {
            extractPending = true
            coroutineScope.launch {
                delay(200)
                isExtracting = true
                try { BlueHiveApplication.coilImageLoader.memoryCache?.clear() } catch (_: Exception) {}

                // BACKEND FIRST for the default play (sub, default server). Falls
                // THROUGH on any failure — the on-device extractor below still
                // works, so a backend outage costs a few seconds rather than the
                // film. Dub and named-server skip this: the backend cannot drive
                // those dropdowns.
                // BACKEND FIRST for EVERY anime play now — sub, dub and named
                // servers alike. The backend drives the Language and Provider
                // dropdowns itself, so there is no case left that it cannot serve.
                // Still falls THROUGH on failure: the on-device extractor below is
                // untouched, so a backend outage costs seconds, not the film.
                //
                // Burn the provider here, not in extractorLauncher — that handler
                // only sees ON-DEVICE results, so triedServers never grew and the
                // dub failover below would return the same server forever.
                server?.let { triedServers.add(it) }

                var outcome = runCatching {
                    extractAnimeMovieViaBackend(dub = dub, server = server)
                }.getOrDefault(ExtractOutcome.MISS)

                if (outcome == ExtractOutcome.WRONG_TRACK) {
                    ensureServerList(url, true)
                    // The DUB list specifically, not pickNextServer — that helper
                    // falls back to `serverOptions`, which may still hold the sub
                    // list from the last picker. See the TV screen.
                    val next = serverListCache[serverCacheKey(url, true)]?.first
                        ?.firstOrNull { it.name !in triedServers }?.name
                    if (next != null) {
                        Log.e("scraper", "🔁 dub came back as sub — failing over to server '$next'")
                        triedServers.add(next)
                        isExtracting   = false
                        extractPending = false
                        failoverRequest = Triple(url, true, next)
                        return@launch
                    }
                    Log.e("scraper", "🔁 dub unavailable on every server — playing sub as last resort")
                    outcome = runCatching {
                        extractAnimeMovieViaBackend(dub = dub, server = server, acceptAnyTrack = true)
                    }.getOrDefault(ExtractOutcome.MISS)
                }

                if (outcome == ExtractOutcome.PLAYED) {
                    // Warm the picker for the track that just played — see the
                    // TV screen. Playback has already started and the list is
                    // cached server-side by the extraction, so this is one
                    // round trip and delays nothing.
                    ensureServerList(url, dub)
                    isExtracting   = false
                    extractPending = false
                    return@launch
                }
                Log.w("scraper", "M Extract: backend miss (dub=$dub server=$server) — on-device fallback")

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

                // BACKEND FIRST, which also removes an ACTIVITY HOP. The on-device
                // path launched MiruroExtractorActivity, pausing and resuming this
                // screen — and that resume is what RestoreFocusOnPlayerReturn
                // races, the cause of the picker losing focus. An in-process call
                // never leaves the screen, so the race cannot arise here.
                val list = runCatching {
                    ApiClient.bluehiveApi.extractServers(
                        type   = "movie",
                        tmdbId = tmdbId,
                        audio  = if (dub) "dub" else "sub",
                        device = SessionManager.get().deviceFingerprint,
                    )
                }.getOrNull()?.takeIf { it.isSuccessful }?.body()

                val opts = list?.servers.orEmpty().map { ServerOption(it.name, emptyList()) }
                if (opts.isNotEmpty()) {
                    val defIdx = list!!.servers.indexOfFirst { it.is_default }.coerceAtLeast(0)
                    Log.d("scraper", "M Enumerate (backend) → ${opts.size} servers, default=$defIdx")
                    serverListCache[serverCacheKey(url, dub)] = opts to defIdx
                    serverOptions     = opts
                    serverDefaultIdx  = defIdx
                    isCheckingServers = false
                    showServerPicker  = true
                    return@launch
                }

                Log.w("scraper", "M Enumerate: backend miss — on-device fallback")
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

    // Auto server-failover: the result handler stashes the next attempt here; run it.
    LaunchedEffect(failoverRequest) {
        val req = failoverRequest ?: return@LaunchedEffect
        failoverRequest = null
        launchExtract(req.first, req.second, req.third)
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
        else {
            // initialValue already holds the cached list, so the buttons are
            // already on screen. Refresh and re-read so a source switched off on
            // the server reaches a session that never backgrounds. Throttled
            // inside warm(); usually a no-op.
            com.example.bluehive.api.SourceCatalog
                .warm(BlueHiveApplication.appContext ?: return@produceState)
            value = getStreamingSources(mediaType, mediaId)
        }
    }

    /**
     * A DuB/SuB press made before the AniList resolve landed. true = dub.
     *
     * WHY IT EXISTS: `sources` starts empty and fills asynchronously, so the
     * size check the buttons use could not tell "not resolved yet" from "this
     * title has no Miruro mapping" — both read as too few sources. A press in
     * that window reported the episode unavailable, and the same press a
     * second later worked. Empty means still loading; a resolved list always
     * carries VidFast, so it is never empty.
     */
    var queuedAnimePress by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(queuedAnimePress, sources) {
        val want = queuedAnimePress ?: return@LaunchedEffect
        if (sources.size > 2) {
            queuedAnimePress = null
            lastStreamFocusRequester =
                if (want) animeDubFocusRequester else animeSubFocusRequester
            pendingUrl = sources[2].url
            pendingDub = want
            showDefaultPrompt = true
            return@LaunchedEffect
        }
        if (sources.isNotEmpty()) {          // resolved, and carries no Miruro
            queuedAnimePress = null
            showNotYetAvailable = true
            return@LaunchedEffect
        }
        // Still resolving. Bounded, so a resolve that never completes cannot
        // leave the button looking dead.
        delay(8_000)
        if (queuedAnimePress != null) {
            queuedAnimePress = null
            showNotYetAvailable = true
        }
    }



    // KEYED ON extractAttempt AS WELL AS isExtracting. A failover keeps the
    // overlay up on purpose (no blink between sources), so isExtracting may not
    // visibly transition between one attempt and the next -- and without a second
    // key this effect would not restart, leaving the dots frozen at stage 1 for
    // the whole of the next attempt, which reads as a hang.
    //
    // It used to key on the ON-DEVICE retry counter, which the backend path never
    // incremented, so the dots really did freeze during a backend failover.
    // extractAndPlayMovie stamps extractAttempt on entry, so every attempt --
    // first or nth -- changes the key.
    LaunchedEffect(isExtracting, extractAttempt) {
        if (isExtracting) {
            extractStage = 1
            delay(900);  extractStage = 2
            delay(2500); extractStage = 3
            delay(3500); extractStage = 4
        } else {
            extractStage = 0
        }
    }


    LaunchedEffect(showNotYetAvailable) {
        if (showNotYetAvailable) {
            delay(4000)
            showNotYetAvailable = false
        }
    }

    LaunchedEffect(showNoDub) {
        if (showNoDub) {
            delay(2500)
            showNoDub = false
        }
    }

    // 30s on the picker → close it and send the user back to the Yes/No prompt.
    // NO AUTO-DISMISS. This used to close itself after 30s and drop the viewer
    // back on the default-server prompt. Reading six provider names, deciding,
    // and walking a D-pad there is easily slower than that -- and the dialog
    // vanishing mid-decision looks like the app crashed, then silently undoes
    // the choice they were making. A picker closes when it is picked or backed
    // out of; BackHandler already covers the second.

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
                        // No stopTrailer() here: this only OPENS the DuB/SuB prompt.
                        // The trailer plays on until the prompt is answered, which
                        // is where a launch actually happens.
                        if (sources.size > 2) {
                            Log.d("scraper", "DuB pressed → prompt: ${sources[2].url}")
                            lastStreamFocusRequester = animeDubFocusRequester
                            pendingUrl = sources[2].url
                            pendingDub = true
                            showDefaultPrompt = true
                        } else if (sources.isEmpty()) {
                            // Still resolving — honour the press when it lands.
                            queuedAnimePress = true
                        } else {
                            // Resolved, and still no Miruro/M Extract source: this
                            // title genuinely has no AniList mapping yet. Say so
                            // instead of silently doing nothing.
                            Log.w("scraper", "DuB pressed but no Miruro source (tmdb=$mediaId) — unmapped")
                            showNotYetAvailable = true
                        }
                    }
                )
                AnimeSubButton(
                    fontFamily = passionFont,
                    focusRequester = animeSubFocusRequester,
                    offsetX = 228.dp, offsetY = 332.5.dp,
                    onClick = {
                        // No stopTrailer() here — see the DuB button above.
                        if (sources.size > 2) {
                            Log.d("scraper", "SuB pressed → prompt: ${sources[2].url}")
                            lastStreamFocusRequester = animeSubFocusRequester
                            pendingUrl = sources[2].url
                            pendingDub = false
                            showDefaultPrompt = true
                        } else if (sources.isEmpty()) {
                            queuedAnimePress = false
                        } else {
                            Log.w("scraper", "SuB pressed but no Miruro source (tmdb=$mediaId) — unmapped")
                            showNotYetAvailable = true
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
                    // A trailing odd button (alone on its row) is centered between the two
                    // columns instead of hugging the left column.
                    val aloneInRow = i % 2 == 0 && i == sources.lastIndex
                    MoviesStreamerButton(
                        source = source,
                        fontFamily = passionFont,
                        focusRequester = streamerFocus[i],
                        offsetX = if (aloneInRow) (colX[0] + colX[1]) / 2f else colX[col],
                        offsetY = if (row < rowY.size) rowY[row]
                                  else rowY.last() + 55.dp * (row - rowY.lastIndex),
                        disableLeftSound  = aloneInRow || col == 0, // left column / lone centered → mute left edge
                        disableRightSound = aloneInRow || col == 1, // right column / lone centered → mute right edge
                        disableDownSound  = i + 2 >= sources.size,  // no button directly below → mute down
                        onClick = {
                            // No stopTrailer() here. A backend-extracted source only
                            // raises the ExoPlayer/Gecko prompt below, and the trailer
                            // should survive that choice. The branches that really
                            // launch stop it themselves.
                            // Remember which button launched this so focus comes back
                            // here when the player exits (THE RULE).
                            lastStreamFocusRequester = streamerFocus[i]
                            // Backend-extracted sources -> m3u8 + ExoPlayer (the
                            // overlay shows progress); anything the backend cannot
                            // extract keeps the WebView embed.
                            if (usesBackendExtraction(source.name)) {
                                // Raise the ExoPlayer-vs-Gecko prompt.
                                if (PlaybackPrefs.askPlayerEachTime) {
                                    backendChoiceFor = source
                                } else {
                                    // Prompt turned off in the playback settings
                                    // (Settings > Playback method): take its
                                    // default answer. The setting is global,
                                    // so it has to apply here too -- a viewer who
                                    // turned the prompt off would otherwise still
                                    // meet it on every film.
                                    startBuiltInPlayer(source)
                                }
                            } else {
                                stopTrailer()
                                onStreamingSourceSelected(source)
                            }
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
            // TRAPPED, like the server check below it — see ExtractionFocusTrap.
            // Untrapped, the focused source button behind the scrim swallows the
            // first BACK for its own onBack and the extraction keeps running.
            ExtractionFocusTrap {
                ExtractionLoadingOverlay(
                    status = when (extractStage) {
                        0, 1 -> "Locating stream source…"
                        2    -> "Loading stream source…"
                        3    -> "Preparing video player…"
                        else -> "Almost there…"
                    },
                    stage = extractStage,
                )
            }
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

        // ── "Not in our search engine yet" — no AniList mapping for this movie ──
        if (showNotYetAvailable) {
            Text(
                "This title isn't in our anime search engine yet — try VidFast below",
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

        // ── Backend-extracted sources: which player? ────────────────────────
        // Same component and wording as the TV screen, so the two flows are
        // indistinguishable to the user. ExoPlayer = extract server-side then play
        // in our own player (light, resume-capable); Gecko = the embed as before.
        backendChoiceFor?.let { choiceSrc ->
            DefaultServerPrompt(
                message     = "Watch with our player,\nor open the site directly?",
                defaultText = "Watch on ExoPlayer",
                chooseText  = "Watch on website",
                onDefault   = {
                    backendChoiceFor = null
                    startBuiltInPlayer(choiceSrc)
                },
                onChoose    = {
                    backendChoiceFor = null
                    stopTrailer()
                    onStreamingSourceSelected(choiceSrc)
                }
            )
        }

        // Extraction failure the user can act on ("try another source").
        extractionError?.let { msg ->
            com.example.bluehive.webview.ExtractionErrorOverlay(
                message = msg,
                onDismiss = { extractionError = null }
            )
        }

        // ── "Checking servers" overlay (No path, phase 1) ───────────────────
        if (isCheckingServers) {
            // Focus TRAP, not decoration. ExtractionLoadingOverlay draws a scrim
            // but takes no focus of its own, so the D-pad still reaches the details
            // screen behind it.
            //
            // NOTE ON SCOPE: this only covers the window where this Activity is in
            // FOREGROUND with the overlay up. It does NOT cover the enumerate pass
            // itself — that runs in MiruroExtractorActivity and this screen is
            // paused throughout. The bug people actually hit lives on the RESUME
            // from that activity and is guarded in RestoreFocusOnPlayerReturn above.
            //
            // BACK and Escape pass through so the existing BackHandler can still
            // cancel; swallowing them would leave no way out on a TV remote.
            ExtractionFocusTrap {
                ExtractionLoadingOverlay(
                    status = "Checking the site for available servers…",
                    stage  = 2
                )
            }
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
                    stopTrailer()
                    pendingUrl?.let { launchExtract(it, pendingDub, null) }
                },
                onChoose = {
                    showDefaultPrompt = false
                    // NO focus restore here, unlike onDefault above. onDefault ENDS
                    // the dialog flow, so returning focus to the DuB/Sub button is
                    // right. onChoose CONTINUES into the picker, and handing focus to
                    // a button on the screen underneath is exactly what the picker
                    // then has to fight for it.
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
        // Shared — see FavoriteButtonColors in utilities/ModularButton.kt.
        colors = FavoriteButtonColors,
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
            // NOT a toggle any more — the label is driven from outside, exactly
            // like the trailer button's "Play/Pause Trailer". ModularButton only
            // consults toggledText when isToggleable is true, so setting one here
            // would be dead config; the state has to arrive through `text`.
            text = if (isToggled) "Providers" else "Extras"
        ),
        // false, so this behaves as a plain button: press animation, a brief
        // pressed flash, then back to rest. Previously true, which parked it in a
        // permanently "held down" visual state whenever providers were showing.
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
        // Shared — see ExtrasButtonColors in utilities/ModularButton.kt.
        colors = ExtrasButtonColors,
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
        // Shared — see TrailerControlButtonColors in utilities/ModularButton.kt.
        colors = TrailerControlButtonColors,
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
            // ── A REAL SEEK ICON, NOT A CHEVRON ──────────────────────
            // "<" and ">" are less-than and greater-than: punctuation that
            // happens to point. They read as paging, which is not what these
            // do -- both seek the trailer by exactly 10 seconds.
            //
            // A DOUBLE TRIANGLE, not the player band's circular "10". Both
            // seek by SEEK_INCREMENT_MS (10s), so the numbered asset was
            // accurate -- but at 14dp on a button this size the digits are
            // detail nobody reads, while two triangles carry the direction on
            // their own. The band keeps the numbered version, where it is
            // large enough for the 10 to be worth printing.
            //
            // `text` still carries the label: it is unused for drawing when
            // iconRes is set, and becomes the icon's contentDescription.
            text = "Rewind 10 seconds",
            iconRes = R.drawable.ic_seek_rewind,
            iconSize = 14.dp,
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
        // Shared — see TrailerControlButtonColors in utilities/ModularButton.kt.
        colors = TrailerControlButtonColors,
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
            // ── A REAL SEEK ICON, NOT A CHEVRON ──────────────────────
            // "<" and ">" are less-than and greater-than: punctuation that
            // happens to point. They read as paging, which is not what these
            // do -- both seek the trailer by exactly 10 seconds.
            //
            // A DOUBLE TRIANGLE, not the player band's circular "10". Both
            // seek by SEEK_INCREMENT_MS (10s), so the numbered asset was
            // accurate -- but at 14dp on a button this size the digits are
            // detail nobody reads, while two triangles carry the direction on
            // their own. The band keeps the numbered version, where it is
            // large enough for the 10 to be worth printing.
            //
            // `text` still carries the label: it is unused for drawing when
            // iconRes is set, and becomes the icon's contentDescription.
            text = "Forward 10 seconds",
            iconRes = R.drawable.ic_seek_forward,
            iconSize = 14.dp,
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
        // Shared — see TrailerControlButtonColors in utilities/ModularButton.kt.
        colors = TrailerControlButtonColors,
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
                                // Right edge: a plain dead end, no sound, no move.
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
                                false   // bottom row: nothing below, no sound
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
        // Shared with the TV screen — see StreamSourceButtonColors in
        // utilities/ModularButton.kt. Change the blues there, not here.
        colors = StreamSourceButtonColors,
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX = (-11.8).dp,
            offsetY = (-2.5).dp
        ),
        // Timing shared with the TV screen — see WatchButtonAnimation in
        // utilities/ModularButton.kt. Change the press feel there, not here.
        animationConfig = WatchButtonAnimation,
        // No playClickSound() here: ModularButton already plays it, immediately,
        // and this second call fired a duplicate. Harmless while the two landed
        // on the same frame — audible as a real double-click once onClick became
        // deferred by clickDelayMillis.
        onClick = onClick
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
        // "English" / "Japanese" rather than "DuB" / "SuB". Dub and sub are
        // fan shorthand; the language is what a viewer is actually choosing.
        textConfig = ModularButtonTextConfig(text = "Watch in English", fontSize = 14f),
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
        // Palette and press feel are shared with the TV screen — see
        // AnimeDubButtonColors / WatchButtonAnimation in utilities/ModularButton.kt.
        colors = AnimeDubButtonColors,
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX = (-11.8).dp, offsetY = (-2.5).dp
        ),
        animationConfig = WatchButtonAnimation,
        // ModularButton already plays the click, immediately. This second call
        // was a duplicate, inaudible only because both landed on one frame.
        onClick = onClick
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
        textConfig = ModularButtonTextConfig(text = "Watch in Japanese", fontSize = 14f),
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
        // Palette and press feel are shared with the TV screen — see
        // AnimeSubButtonColors / WatchButtonAnimation in utilities/ModularButton.kt.
        colors = AnimeSubButtonColors,
        glowConfig = ModularButtonGlowConfig(
            enabled = true,
            defaultRes = R.drawable.button_focus_wide_glow,
            toggledRes = R.drawable.button_focus_wide_nonglow,
            offsetX = (-11.8).dp, offsetY = (-2.5).dp
        ),
        animationConfig = WatchButtonAnimation,
        // ModularButton already plays the click, immediately. This second call
        // was a duplicate, inaudible only because both landed on one frame.
        onClick = onClick
    )
}


//0x33000000 = 20% opacity (very light)
//0x50000000 = 31% opacity (your current - too light)
//0x20000000 = 50% opacity (medium)
//0xCC000000 = 80% opacity (recommended)
//0xE6000000 = 90% opacity (strong)
//0xFF000000 = 100% opacity (full black)