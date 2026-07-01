package com.example.bluehive.catalog

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.MoviesDetailsScreenCompose
import com.example.bluehive.R
import com.example.bluehive.TVShowsDetailsScreenCompose
import com.example.bluehive.api.ApiClient
import com.example.bluehive.models.MediaItem
import com.example.bluehive.repository.EpisodesRepository
import com.example.bluehive.repository.RecommendationsRepository
import com.example.bluehive.sidebarComponents.HistoryDropdown
import com.example.bluehive.searchBarComponent.openSearchScreen
import com.example.bluehive.utilities.AppTypography
import com.example.bluehive.utilities.MediaCard
import com.example.bluehive.utilities.ModularButton
import com.example.bluehive.utilities.ModularButtonAnimationConfig
import com.example.bluehive.utilities.ModularButtonColors
import com.example.bluehive.utilities.ModularButtonDimensions
import com.example.bluehive.utilities.ModularButtonGlowConfig
import com.example.bluehive.utilities.ModularButtonTextConfig
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.wrapContentHeight
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


// ─────────────────────────────────────────────────────────────────────────────
//  Entry point
// ─────────────────────────────────────────────────────────────────────────────

fun openMediaCatalogScreen(context: Context, profileId: Int = -1) {
    context.startActivity(
        Intent(context, MediaCatalogActivity::class.java)
            .putExtra("PROFILE_ID", profileId)
    )
}

class MediaCatalogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                BlueHiveApplication.playBackOutSound()
                // Aggressive cleanup BEFORE finish so references are gone
                // by the time the home screen starts composing.
                releaseHeavyResources()
                finish()
            }
        })

        val profileId = intent.getIntExtra("PROFILE_ID", -1)
        setContent {
            MediaCatalogScreen(profileId = profileId)
        }
    }

    /**
     * Drop everything heavy: Coil's in-memory bitmap cache, then nudge ART
     * to actually run a GC pass so the released memory is reflected in the
     * heap immediately rather than at some unspecified later time.
     *
     * System.gc() is a hint, not a guarantee, but ART on modern Android
     * (API 28+) honors it reliably for explicit user-driven transitions
     * like this. The 50-100ms cost is acceptable because it happens during
     * a screen transition where the user already expects a brief pause.
     *
     * NOTE: Coil's disk cache is intentionally NOT cleared here. The disk
     * cache is shared across the whole app and clearing it forces every
     * subsequent screen to re-download posters from TMDB.
     */
    private fun releaseHeavyResources() {
        try {
            BlueHiveApplication.coilImageLoader.memoryCache?.clear()
            android.util.Log.d("CACHE", "🧹 Cleared Coil memory cache on catalog back-press")
        } catch (e: Exception) {
            android.util.Log.w("CACHE", "Failed to clear Coil cache: ${e.message}")
        }

        // Suggest a GC. ART will typically run one within ~1 frame.
        // We call it twice because the first pass marks objects as eligible
        // and the second pass actually compacts. This is a known idiom for
        // user-driven cleanup points.
        System.gc()
        System.runFinalization()
        System.gc()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Belt-and-suspenders: if the activity is destroyed via some path
        // other than back-press (e.g. system kill, low memory), still try
        // to clean up. releaseHeavyResources is idempotent.
        releaseHeavyResources()
    }
}


// ─────────────────────────────────────────────────────────────────────────────
//  Constants
// ─────────────────────────────────────────────────────────────────────────────

private const val ASSORTED_OPTION = "Assorted"
private const val ANIME_OPTION    = "Anime"    // cross-type: Animation genre + Japanese language
private const val PAGE_SIZE      = 56          // 8 rows × 7 cards
private const val WINDOW_PAGES   = 7           // pages kept in memory around focus (centered)
private const val PREFETCH_AHEAD = 3           // pages to fetch ahead of focus
private const val PREFETCH_BEHIND = 3          // pages to fetch behind focus

private val GENRE_OPTIONS = listOf(
    ASSORTED_OPTION,
    "Action", ANIME_OPTION, "Adventure", "Animation", "Comedy", "Crime",
    "Documentary", "Drama", "Family", "Fantasy", "History",
    "Horror", "Music", "Mystery", "Romance", "Science Fiction",
    "TV Movie", "Thriller", "War", "Western",
    "Action & Adventure", "Kids", "News", "Reality",
    "Sci-Fi & Fantasy", "Soap", "Talk", "War & Politics",
)

private val SORT_OPTIONS = listOf("Most Popular", "By Release Date", "A–Z", "Z–A", "Highest Rated", "Lowest Rated")
private val YEAR_OPTIONS: List<String> = listOf("Assorted") + (2026 downTo 1999).map { it.toString() }

// ── Control bar layout (shared by screen + preview) ───────────────────────────
// AFTER
private val CONTROLS_ROW_Y      = 70.dp
private val SEARCH_BAR_X        = 55.dp
private val FILTER_DROPDOWN_X   = 125.dp + 180.dp
private val SORT_DROPDOWN_X     = 125.dp + 180.dp + 170.dp
private val YEAR_DROPDOWN_X     = 125.dp + 180.dp + 170.dp + 150.dp

private val MEDIA_TYPE_BUTTON_X = 815.dp
private val MEDIA_TYPE_BUTTON_Y = 18.dp
private val MEDIA_TYPE_BUTTON_W = 90.dp
private val MEDIA_TYPE_SPACING_Y = 40.dp


// ─────────────────────────────────────────────────────────────────────────────
//  Main composable
// ─────────────────────────────────────────────────────────────────────────────

@SuppressLint("RememberInComposition")
@Composable
fun MediaCatalogScreen(profileId: Int = -1) {

    val scrimBrush = remember {
        Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.80f))
        )
    }

    // ── Layout config ─────────────────────────────────────────────────────────
    val cardsPerRow           = 7
    val cardHorizontalSpacing = 8.dp
    val rowSpacing            = 12.dp
    val gridOffsetY           = 118.dp
    val gridHeight            = 430.dp
    val navigationCooldownMs  = 80L

    // ── Header config ─────────────────────────────────────────────────────────
    val headerText     = "Media Catalog"
    val headerFontSize = 29f
    val headerOpacity  = 0.95f
    val headerOffsetX  = 55.dp
    val headerOffsetY  = 23.dp


    // ── Virtualized data state ────────────────────────────────────────────────
    // itemsByIndex holds ONLY the currently-loaded items, keyed by absolute index.
    // The grid sees totalCount slots; missing indices render as placeholders.
    val itemsByIndex    = remember { mutableStateMapOf<Int, MediaItem>() }
    val displayDataByIndex = remember { mutableStateMapOf<Int, CardDisplayData>() }
    var totalCount   by remember { mutableIntStateOf(0) }
    var isLoading    by remember { mutableStateOf(true) }
    var loadError    by remember { mutableStateOf<String?>(null) }
    var isNavigating by remember { mutableStateOf(false) }


    // Pages currently loaded OR in-flight. Prevents duplicate fetches.
    val loadedPages   = remember { mutableMapOf<Int, Boolean>() }
    val inFlightPages = remember { mutableMapOf<Int, Boolean>() }
    val fetchMutex    = remember { Mutex() }

    // Genre name → genre_id
    val genreNameToId = remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    val context        = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // ── Filter / sort state ───────────────────────────────────────────────────
    var selectedGenre by remember { mutableStateOf(ASSORTED_OPTION) }
    var selectedSort by remember { mutableStateOf("Most Popular") }
    var selectedYear      by remember { mutableStateOf("Assorted") }
    var selectedMediaType by remember { mutableStateOf("movie") }

    var isAnyDropdownExpanded by remember { mutableStateOf(false) }
    var suppressNextHover     by remember { mutableStateOf(false) }

    // ── Search state ──────────────────────────────────────────────────────────
    // Search no longer runs in-page. The search bar is a trampoline to
    // SearchBarActivity — we only keep local UI state for the in-bar text field.
    var isSearchEditing      by remember { mutableStateOf(false) }
    var isSearchBarFocused   by remember { mutableStateOf(false) }
    var isSearchFieldFocused by remember { mutableStateOf(false) }
    var searchText           by remember { mutableStateOf("") }
    // Set to true when a multi-character chunk arrives (voice recognizer
    // streams partials 4+ chars at a time). Manual keystrokes are 1 char
    // each, so this stays false and the debounce trampoline is disabled
    // for typed input — typed users must press the IME search button.
    var voiceInputDetected   by remember { mutableStateOf(false) }
    val keyboardController   = LocalSoftwareKeyboardController.current

    // ── Focus requesters — control bar ────────────────────────────────────────
    val filterDropdownFocus = remember { FocusRequester() }
    val sortDropdownFocus   = remember { FocusRequester() }
    val yearDropdownFocus   = remember { FocusRequester() }
    val searchBarFocus      = remember { FocusRequester() }
    val searchFieldFocus    = remember { FocusRequester() }
    val moviesFocus         = remember { FocusRequester() }
    val tvShowsFocus        = remember { FocusRequester() }

    // ── Grid focus state (ABSOLUTE INDEX) ─────────────────────────────────────
    var hasGridFocus     by remember { mutableStateOf(false) }
    var focusedAbsIndex  by remember { mutableIntStateOf(-1) }
    var lastNavTime      by remember { mutableLongStateOf(0L) }

    val gridState = rememberLazyGridState()

    // Sparse map of FocusRequesters keyed by absolute index.
    // Only alive for currently-composed (visible) cards — ~56 at a time.
    val gridFocusRequesters = remember { mutableStateMapOf<Int, FocusRequester>() }


    DisposableEffect(Unit) {
        onDispose {
            itemsByIndex.clear()
            displayDataByIndex.clear()
            loadedPages.clear()
            inFlightPages.clear()
            gridFocusRequesters.clear()
            totalCount = 0
            isLoading = true
            isNavigating = false
            loadError = null
            selectedGenre = ASSORTED_OPTION
            selectedSort = "Most Popular"
            selectedYear = "Assorted"
            selectedMediaType = "movie"
            isAnyDropdownExpanded = false
            suppressNextHover = false
            isSearchEditing = false
            searchText = ""
            voiceInputDetected = false
            hasGridFocus = false
            focusedAbsIndex = -1
            lastNavTime = 0L
        }
    }

    // ── Painters ──────────────────────────────────────────────────────────────
    val backgroundPainter = painterResource(id = R.drawable.titlecard_base)
    val focusedPainter    = painterResource(id = R.drawable.titlecard_focused)
    val shadowPainter     = painterResource(id = R.drawable.titlecard_shadow)

    // ── Genre map: load once ──────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        try {
            val genres = ApiClient.trailerApi.getGenres()
            genreNameToId.value = genres.associate { it.name to it.genre_id }
        } catch (e: Exception) {
            android.util.Log.e("MediaCatalog", "Failed to load genres: ${e.message}")
        }
    }

    // ── Sort key mapper ───────────────────────────────────────────────────────
    fun sortKey(): String = when (selectedSort) {
        "Most Popular"    -> "popularity"
        "By Release Date" -> "release_date"
        "A–Z"             -> "title_asc"
        "Z–A"             -> "title_desc"
        "Highest Rated"   -> "rating"
        "Lowest Rated"    -> "rating"
        else              -> "popularity"
    }

    // ── Core: fetch a single page by page number ─────────────────────────────
    // Returns the fetched items; updates state as a side effect.
    // Safe to call concurrently — uses inFlightPages + mutex to dedupe.
    suspend fun fetchPage(page: Int): Boolean {
        if (page < 0) return false
        if (loadedPages[page] == true) return true
        if (inFlightPages[page] == true) return true

        fetchMutex.withLock {
            // Double-check under lock
            if (loadedPages[page] == true) return true
            if (inFlightPages[page] == true) return true
            inFlightPages[page] = true
        }

        try {
            val isAnime = selectedGenre == ANIME_OPTION

            // "Anime" is a composite filter, not a real TMDB genre: it means
            // the Animation genre AND Japanese language, spanning BOTH movies
            // and TV. So we resolve "Animation" for genre_id, pass null for
            // media_type (backend treats null as "both"), and constrain the
            // language. Every other filter keeps the existing Movies/TV toggle.
            val genreId = when {
                selectedGenre == ASSORTED_OPTION -> null
                isAnime                          -> genreNameToId.value["Animation"]
                else                             -> genreNameToId.value[selectedGenre]
            }

            // Defensive: if the genre map hasn't loaded yet, don't fire a query
            // that would silently drop the Animation constraint and return every
            // Japanese-language title. The reload guard already prevents this in
            // practice, but bail cleanly if we somehow get here.
            if (isAnime && genreId == null) {
                android.util.Log.w("MediaCatalog", "Anime selected but Animation genre id not loaded yet")
                return false
            }

            // The ingester stores original_language as a display name via
            // format_language() ("ja" → "Japanese"), so we must match the
            // stored string, not the ISO code.
            val originalLanguageParam = if (isAnime) "Japanese" else null

            // Anime respects the Movies/TV toggle: it's just an extra constraint
            // (Animation genre + Japanese language) on top of the selected
            // media_type. Press Movies → anime movies, press TV → anime TV.
            val yearInt = selectedYear.toIntOrNull()
            val response = ApiClient.trailerApi.browseMedia(
                mediaType        = selectedMediaType,
                listType         = null,
                genreId          = genreId,
                year             = yearInt,
                limit            = PAGE_SIZE,
                offset           = page * PAGE_SIZE,
                sortBy           = sortKey(),
                watchProvider    = null,
                originalLanguage = originalLanguageParam,
            )
            // Update totalCount only on the first successful fetch of a reset
            if (totalCount == 0) totalCount = response.totalCount

            val baseIndex = page * PAGE_SIZE
            response.items.forEachIndexed { i, item ->
                itemsByIndex[baseIndex + i] = item
                displayDataByIndex[baseIndex + i] = item.toCardDisplayData()
            }
            loadedPages[page] = true
            return true
        } catch (e: Exception) {
            android.util.Log.e("MediaCatalog", "fetchPage($page) failed: ${e.message}")
            loadError = e.message ?: "Failed to load"
            return false
        } finally {
            inFlightPages.remove(page)
        }
    }

    // ── Reset: clear everything and fetch page 0 ─────────────────────────────
    suspend fun resetAndLoadFirstPage() {
        itemsByIndex.clear()
        displayDataByIndex.clear()
        loadedPages.clear()
        inFlightPages.clear()
        gridFocusRequesters.clear()
        totalCount = 0
        focusedAbsIndex = -1
        loadError = null
        isLoading = true
        gridState.scrollToItem(0)

        fetchPage(0)
        isLoading = false
    }

    // ── Window management: load window around focus, evict far-away pages ───
    fun updateWindow(centerAbsIndex: Int) {
        if (totalCount == 0) return
        val totalPages = (totalCount + PAGE_SIZE - 1) / PAGE_SIZE
        val centerPage = (centerAbsIndex / PAGE_SIZE).coerceIn(0, totalPages - 1)

        // Pages we want loaded
        val wantedPages = mutableSetOf<Int>()
        for (offset in -PREFETCH_BEHIND..PREFETCH_AHEAD) {
            val p = centerPage + offset
            if (p in 0 until totalPages) wantedPages.add(p)
        }

        // Fetch missing pages concurrently
        val toFetch = wantedPages.filter { loadedPages[it] != true && inFlightPages[it] != true }
        if (toFetch.isNotEmpty()) {
            coroutineScope.launch {
                toFetch.map { page ->
                    async { fetchPage(page) }
                }.forEach { it.await() }
            }
        }

        // Evict pages outside the window
        val halfWindow = WINDOW_PAGES / 2
        val keepMin = (centerPage - halfWindow).coerceAtLeast(0)
        val keepMax = (centerPage + halfWindow).coerceAtMost(totalPages - 1)
        val toEvict = loadedPages.keys.filter { it < keepMin || it > keepMax }.toList()
        toEvict.forEach { page ->
            val baseIndex = page * PAGE_SIZE
            for (i in 0 until PAGE_SIZE) {
                itemsByIndex.remove(baseIndex + i)
                displayDataByIndex.remove(baseIndex + i)
                gridFocusRequesters.remove(baseIndex + i)
            }
            loadedPages.remove(page)
        }
    }

    // ── Reload on filter/sort/year/mediaType changes ──────────────────────────
    LaunchedEffect(selectedGenre, selectedSort, selectedYear, selectedMediaType, genreNameToId.value) {
        if (genreNameToId.value.isEmpty() && selectedGenre != ASSORTED_OPTION) return@LaunchedEffect
        resetAndLoadFirstPage()
    }

    // ── Auto-land on first card after first load ──────────────────────────────
    LaunchedEffect(isLoading, itemsByIndex.size) {
        if (!isLoading && itemsByIndex.isNotEmpty() && focusedAbsIndex == -1 && hasGridFocus) {
            kotlinx.coroutines.android.awaitFrame()
            focusedAbsIndex = 0
            gridFocusRequesters[0]?.requestFocus()
        }
    }

    // ── Window update trigger (debounced via snapshotFlow) ────────────────────
    LaunchedEffect(Unit) {
        snapshotFlow { focusedAbsIndex }
            .collect { idx ->
                if (idx < 0) return@collect
                updateWindow(idx)
            }
    }

    // ── Keyboard show/hide for search field ───────────────────────────────────
    LaunchedEffect(isSearchFieldFocused) {
        if (isSearchFieldFocused) keyboardController?.show() else keyboardController?.hide()
    }

    LaunchedEffect(isSearchEditing) {
        if (isSearchEditing) {
            kotlinx.coroutines.android.awaitFrame()
            searchFieldFocus.requestFocus()
        }
    }

    // ── Debounced trampoline to SearchBarActivity ────────────────────────────
    // Mirrors SearchScreen.kt's debounce-on-quiet pattern. The Android speech
    // recognizer streams partial results ("res" → "resi" → "resident" →
    // "resident evil") while the user holds the mic button. Each partial
    // restarts this effect, cancelling the previous timer. Only when the user
    // stops talking (or stops typing) and 1000ms of silence passes does the
    // trampoline fire with the FINAL phrase.
    //
    // Manual typing: each keystroke restarts the timer; pause for 1000ms and
    // it fires automatically. The IME "search" button still routes through
    // onSubmit above for instant submission.
    LaunchedEffect(searchText) {
        val q = searchText.trim()
        if (q.length < 2) return@LaunchedEffect
        if (!voiceInputDetected) return@LaunchedEffect  // ← this line must be present
        delay(1000)
        isSearchEditing = false
        keyboardController?.hide()
        searchText = ""
        voiceInputDetected = false
        openSearchScreen(context, profileId, q)
    }

    // ── Helper: get or create a FocusRequester for an absolute index ─────────
    fun requesterFor(absIndex: Int): FocusRequester {
        return gridFocusRequesters.getOrPut(absIndex) { FocusRequester() }
    }

    // ── focusGridItem — now operates on ABSOLUTE index ───────────────────────
    fun focusGridItem(absIndex: Int, scroll: Boolean = true) {
        if (absIndex < 0 || absIndex >= totalCount) return
        focusedAbsIndex = absIndex
        gridFocusRequesters[absIndex]?.requestFocus()
        if (scroll) {
            coroutineScope.launch {
                val scrollSpec = spring<Float>(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness    = Spring.StiffnessHigh
                )
                val layoutInfo = gridState.layoutInfo
                val viewportTop = layoutInfo.viewportStartOffset
                val viewportBottom = layoutInfo.viewportEndOffset
                val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == absIndex }

                if (targetItem != null) {
                    val scrollMargin = 40
                    val itemTop = targetItem.offset.y
                    val itemBottom = itemTop + targetItem.size.height
                    when {
                        itemBottom + scrollMargin > viewportBottom -> {
                            gridState.animateScrollBy(
                                (itemBottom + scrollMargin - viewportBottom).toFloat(),
                                animationSpec = scrollSpec
                            )
                        }
                        itemTop - scrollMargin < viewportTop -> {
                            gridState.animateScrollBy(
                                (itemTop - scrollMargin - viewportTop).toFloat(),
                                animationSpec = scrollSpec
                            )
                        }
                    }
                } else {
                    gridState.scrollToItem(
                        index = absIndex,
                        scrollOffset = -40
                    )
                }

                kotlinx.coroutines.android.awaitFrame()
                gridFocusRequesters[absIndex]?.requestFocus()
            }
        }
    }

    // ── Navigate to details ──────────────────────────────────────────────────
    fun navigateToDetails(item: MediaItem) {
        if (isNavigating) return
        isNavigating = true
        coroutineScope.launch {
            try {
                val tmdbId = item.tmdbId
                val mediaType = item.mediaType

                val detailsDeferred = async {
                    ApiClient.trailerApi.getMediaDetails(tmdbId = tmdbId, mediaType = mediaType)
                }
                val episodesDeferred = if (mediaType == "tv") {
                    async {
                        try { EpisodesRepository().getSeasonEpisodes(tmdbId = tmdbId, seasonNumber = 1).episodes }
                        catch (_: Exception) { null }
                    }
                } else null
                val recsDeferred = async {
                    try { RecommendationsRepository().getRecommendations(tmdbId, mediaType) }
                    catch (_: Exception) { emptyList() }
                }

                delay(600)

                val details = detailsDeferred.await()
                val app = context.applicationContext as? BlueHiveApplication
                app?.storePrefetch(
                    BlueHiveApplication.MediaPrefetchData(
                        tmdbId          = tmdbId,
                        mediaType       = mediaType,
                        episodes        = episodesDeferred?.await(),
                        recommendations = recsDeferred.await(),
                    )
                )

                val logoUrl = (details.logos as? String)?.takeIf { it.isNotBlank() } ?: ""
                val genresString = item.genres?.joinToString(", ")?.ifBlank { "N/A" } ?: "N/A"

                val intent = if (mediaType == "movie") {
                    Intent(context, MoviesDetailsScreenCompose::class.java).apply {
                        putExtra("media_type",          details.media_type)
                        putExtra("media_id",            details.tmdb_id)
                        putExtra("media_title",         details.title ?: "")
                        putExtra("poster_url",          details.poster_path ?: "")
                        putExtra("backdrop_url",        details.backdrop_path ?: "")
                        putExtra("youtube_trailer_url", details.youtube_trailer_url ?: "")
                        putExtra("overview",            details.overview ?: "")
                        putExtra("vote_average",        details.vote_average ?: 0.0)
                        putExtra("contentRating",       details.content_rating ?: "N/A")
                        putExtra("original_language",   details.original_language ?: "N/A")
                        putExtra("release_date",        details.release_date ?: "N/A")
                        putExtra("logo_url",            logoUrl)
                        putExtra("genres",              genresString)
                        putExtra("PROFILE_ID",          profileId)
                    }
                } else {
                    Intent(context, TVShowsDetailsScreenCompose::class.java).apply {
                        putExtra("media_type",          details.media_type)
                        putExtra("media_id",            details.tmdb_id)
                        putExtra("media_title",         details.title ?: "")
                        putExtra("poster_url",          details.poster_path ?: "")
                        putExtra("backdrop_url",        details.backdrop_path ?: "")
                        putExtra("youtube_trailer_url", details.youtube_trailer_url ?: "")
                        putExtra("overview",            details.overview ?: "")
                        putExtra("vote_average",        details.vote_average ?: 0.0)
                        putExtra("contentRating",       details.content_rating ?: "N/A")
                        putExtra("original_language",   details.original_language ?: "N/A")
                        putExtra("release_date",        details.release_date ?: "N/A")
                        putExtra("logo_url",            logoUrl)
                        putExtra("genres",              genresString)
                        putExtra("number_of_seasons",   details.number_of_seasons ?: 0)
                        putExtra("status",              details.status ?: "N/A")
                        putExtra("PROFILE_ID",          profileId)
                    }
                }

                isNavigating = false
                context.startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.e("MediaCatalog", "Navigation failed: ${e.message}")
                isNavigating = false
            }
        }
    }

    // ── Shared button colors ──────────────────────────────────────────────────
    val mainDefault          = Color(0xFF192C6F)
    val mainFocused          = Color(0xFF4C63AC)
    val mainToggled          = Color(0xFF101628)
    val secondDefault        = Color(0xFF151C3A)
    val secondFocused        = Color(0xFF2E325F)
    val secondToggled        = Color(0xFF1B1C34)
    val textUnfocused        = Color(0xFF868686)
    val textFocused          = Color(0xFFDADADA)
    val textUnfocusedToggled = Color(0xFF343434)
    val textToggledFocus     = Color(0xFFDADADA)
    val gridAlpha by remember { derivedStateOf { if (isAnyDropdownExpanded) 0.08f else 1f } }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI
    // ─────────────────────────────────────────────────────────────────────────

    Box(modifier = Modifier.fillMaxSize()) {

        Image(
            painter            = painterResource(id = R.drawable.home_screen),
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
        )

        // ── Header ────────────────────────────────────────────────────────────
        Text(
            text       = headerText,
            fontSize   = headerFontSize.sp,
            fontFamily = AppTypography.lalezarRegular,
            color      = Color.White.copy(alpha = headerOpacity),
            modifier   = Modifier
                .align(Alignment.TopStart)
                .offset(x = headerOffsetX, y = headerOffsetY),
        )



        // ── Control bar — intercepts DirectionDown into grid ─────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .wrapContentHeight(unbounded = true)
                .zIndex(10f)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionDown &&
                        !isAnyDropdownExpanded &&
                        totalCount > 0
                    ) {
                        coroutineScope.launch {
                            val target = if (focusedAbsIndex >= 0) focusedAbsIndex else 0
                            if (target == 0) gridState.scrollToItem(0)
                            repeat(3) { kotlinx.coroutines.android.awaitFrame() }
                            focusedAbsIndex = target
                            gridFocusRequesters[target]?.requestFocus()
                        }
                        true
                    } else false
                }
        ) {

            // ── Filter dropdown ───────────────────────────────────────────────────
            HistoryDropdown(
                label = "Filter",
                options = GENRE_OPTIONS,
                selectedOption = selectedGenre,
                onOptionSelected = { selectedGenre = it },
                buttonWidth = 160.dp,
                dropdownMaxHeight = 237.dp,
                externalFocusRequester = filterDropdownFocus,
                suppressInitialFocusSound = true,
                onExpandedChanged = { isAnyDropdownExpanded = it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = FILTER_DROPDOWN_X, y = CONTROLS_ROW_Y)
                    .zIndex(1f),
            )

            // ── Sort dropdown ─────────────────────────────────────────────────────
            HistoryDropdown(
                label = "Sort",
                options = SORT_OPTIONS,
                selectedOption = selectedSort,
                onOptionSelected = { selectedSort = it },
                buttonWidth = 140.dp,
                externalFocusRequester = sortDropdownFocus,
                onExpandedChanged = { isAnyDropdownExpanded = it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = SORT_DROPDOWN_X, y = CONTROLS_ROW_Y)
                    .zIndex(1f),
            )


            // ── Year dropdown ─────────────────────────────────────────────────────
            HistoryDropdown(
                label = "Year",
                options = YEAR_OPTIONS,
                selectedOption = selectedYear,
                onOptionSelected = { selectedYear = it },
                buttonWidth = 130.dp,
                dropdownMaxHeight = 237.dp,
                externalFocusRequester = yearDropdownFocus,
                onExpandedChanged = { isAnyDropdownExpanded = it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = YEAR_DROPDOWN_X, y = CONTROLS_ROW_Y)
                    .zIndex(1f),
            )


            // ── Search bar ────────────────────────────────────────────────────────
            CatalogSearchBar(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = SEARCH_BAR_X, y = CONTROLS_ROW_Y + 4.dp)
                    .zIndex(1f),
                searchText = searchText,
                onSearchTextChange = { newText ->
                    // Detect voice: a single onValueChange jump of 4+ characters
                    // means the speech recognizer streamed a partial result. Manual
                    // typing arrives one character at a time and never trips this.
                    if (newText.length - searchText.length >= 4) {
                        voiceInputDetected = true
                    }
                    searchText = newText
                },
                isFocused = isSearchBarFocused,
                isEditing = isSearchEditing,
                onFocusChange = { isSearchBarFocused = it },
                onFieldFocusChange = { isSearchFieldFocused = it },
                barFocusRequester = searchBarFocus,
                fieldFocusRequester = searchFieldFocus,
                onStartEditing = { isSearchEditing = true },
                onStopEditing = { isSearchEditing = false },
                onSubmit = { query ->
                    val q = query.trim()
                    isSearchEditing = false
                    keyboardController?.hide()
                    if (q.isNotEmpty()) {
                        searchText = ""
                        openSearchScreen(context, profileId, q)
                    } else {
                        searchBarFocus.requestFocus()
                    }
                },
                onClear = {
                    searchText = ""
                },
            )


            // ── Movies button ─────────────────────────────────────────────────────
            ModularButton(
                textConfig = ModularButtonTextConfig(text = "Movies", fontSize = 10f),
                isToggleable = true,
                externalToggled = selectedMediaType == "movie",
                isFocusable = selectedMediaType != "movie",
                fontFamily = AppTypography.interBold,
                focusRequester = moviesFocus,
                onClick = {
                    selectedMediaType = "movie"
                    suppressNextHover = true
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        tvShowsFocus.requestFocus()
                    }, 50)
                },
                // AFTER
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = MEDIA_TYPE_BUTTON_X, y = MEDIA_TYPE_BUTTON_Y)
                    .onFocusChanged {
                        if (it.isFocused) {
                            if (!suppressNextHover) BlueHiveApplication.playHoverSound()
                            else suppressNextHover = false
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> { yearDropdownFocus.requestFocus(); true }
                            Key.DirectionRight -> true
                            Key.DirectionDown -> {
                                if (selectedMediaType != "tv") { tvShowsFocus.requestFocus() }
                                true
                            }
                            else -> false
                        }
                    },
                dimensions = catalogButtonDimensions(MEDIA_TYPE_BUTTON_W),
                colors = catalogButtonColors(
                    mainDefault,
                    mainFocused,
                    mainToggled,
                    secondDefault,
                    secondFocused,
                    secondToggled,
                    textFocused,
                    textUnfocused,
                    textUnfocusedToggled,
                    textToggledFocus
                ),
                glowConfig = catalogButtonGlowConfig(),
                animationConfig = catalogButtonAnimationConfig(),
            )

            // ── TV Shows button ───────────────────────────────────────────────────
            ModularButton(
                textConfig = ModularButtonTextConfig(text = "TV Shows", fontSize = 10f),
                isToggleable = true,
                externalToggled = selectedMediaType == "tv",
                isFocusable = selectedMediaType != "tv",
                fontFamily = AppTypography.interBold,
                focusRequester = tvShowsFocus,
                onClick = {
                    selectedMediaType = "tv"
                    suppressNextHover = true
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        moviesFocus.requestFocus()
                    }, 50)
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = MEDIA_TYPE_BUTTON_X, y = MEDIA_TYPE_BUTTON_Y + MEDIA_TYPE_SPACING_Y)
                    .onFocusChanged {
                        if (it.isFocused) {
                            if (!suppressNextHover) BlueHiveApplication.playHoverSound()
                            else suppressNextHover = false
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> { yearDropdownFocus.requestFocus(); true }
                            Key.DirectionRight -> true
                            Key.DirectionUp -> {
                                if (selectedMediaType != "movie") { moviesFocus.requestFocus() }
                                true
                            }
                            else -> false
                        }
                    },
                dimensions = catalogButtonDimensions(MEDIA_TYPE_BUTTON_W),
                colors = catalogButtonColors(
                    mainDefault,
                    mainFocused,
                    mainToggled,
                    secondDefault,
                    secondFocused,
                    secondToggled,
                    textFocused,
                    textUnfocused,
                    textUnfocusedToggled,
                    textToggledFocus
                ),
                glowConfig = catalogButtonGlowConfig(),
                animationConfig = catalogButtonAnimationConfig(),
            )
        }



        // ── Grid ──────────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = gridOffsetY)
                .width(860.dp)
                .height(gridHeight)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        searchBarFocus.requestFocus()
                        true
                    } else false
                },
        ) {
            if (!isLoading && totalCount == 0 && loadError == null) {
                CatalogEmptyState(
                    title = "No content found",
                    body  = "Try changing the filter, year, or switching between Movies and TV Shows.",
                )
            } else if (loadError != null && itemsByIndex.isEmpty()) {
                CatalogEmptyState(
                    title = "Failed to load catalog",
                    body  = loadError ?: "",
                )
            } else {
                // Grid has totalCount slots. items() gives us (0 until totalCount) indices.
                LazyVerticalGrid(
                    columns               = GridCells.Fixed(cardsPerRow),
                    state                 = gridState,
                    userScrollEnabled     = false,
                    modifier              = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = gridAlpha }
                        .focusGroup()
                        .onFocusChanged { fs -> hasGridFocus = fs.hasFocus },
                    contentPadding        = PaddingValues(bottom = 50.dp),
                    horizontalArrangement = Arrangement.spacedBy(cardHorizontalSpacing),
                    verticalArrangement   = Arrangement.spacedBy(rowSpacing),
                ) {
                    items(
                        count = totalCount,
                        key   = { absIndex -> "slot-$absIndex" },
                    ) { absIndex ->

                        val item = itemsByIndex[absIndex]
                        val isItemFocused by remember(absIndex) {
                            derivedStateOf { hasGridFocus && focusedAbsIndex == absIndex }
                        }
                        val requester = requesterFor(absIndex)

                        val displayData     = displayDataByIndex[absIndex]
                        val releaseDateText = displayData?.releaseDateText
                        val runTimeText     = displayData?.runTimeText

                        var isPressed by remember { mutableStateOf(false) }
                        val cardScale by animateFloatAsState(
                            targetValue   = if (isPressed) 0.80f else 1f,
                            animationSpec = tween(250),
                            label         = "cardScale",
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(112.dp)
                                .graphicsLayer {
                                    scaleX = cardScale
                                    scaleY = cardScale
                                },
                        ) {
                            // If item is loaded, render real MediaCard.
                            // Otherwise render a placeholder that's still focusable so
                            // D-pad navigation works through unloaded regions.
                            MediaCard(
                                mediaItem         = item ?: PLACEHOLDER_MEDIA,
                                isFocused         = isItemFocused,
                                backgroundPainter = backgroundPainter,
                                focusedPainter    = focusedPainter,
                                shadowPainter     = shadowPainter,
                                releaseDateText   = releaseDateText,
                                runTimeText       = runTimeText,
                                rating            = item?.voteAverage?.takeIf { it > 0.0 },
                                allowHardwareBitmaps = true,
                                modifier          = Modifier
                                    .focusable()
                                    .wrapContentSize(unbounded = true)
                                    .focusRequester(requester)
                                    .onFocusChanged { fs ->
                                        if (fs.isFocused) {
                                            focusedAbsIndex = absIndex
                                            if (item != null) BlueHiveApplication.playHoverSound()
                                        }
                                    }
                                    .onKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyUp) {
                                            if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                                isPressed = false
                                            }
                                            return@onKeyEvent false
                                        }
                                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                        val now = System.currentTimeMillis()

                                        when (event.key) {
                                            Key.DirectionLeft -> {
                                                if (now - lastNavTime < navigationCooldownMs) return@onKeyEvent true
                                                lastNavTime = now
                                                if (focusedAbsIndex > 0) focusGridItem(focusedAbsIndex - 1)
                                                true
                                            }
                                            Key.DirectionRight -> {
                                                if (now - lastNavTime < navigationCooldownMs) return@onKeyEvent true
                                                lastNavTime = now
                                                if (focusedAbsIndex < totalCount - 1) focusGridItem(focusedAbsIndex + 1)
                                                true
                                            }
                                            Key.DirectionUp -> {
                                                if (now - lastNavTime < navigationCooldownMs) return@onKeyEvent true
                                                lastNavTime = now
                                                val target = focusedAbsIndex - cardsPerRow
                                                if (target >= 0) {
                                                    focusGridItem(target)
                                                } else {
                                                    searchBarFocus.requestFocus()
                                                    BlueHiveApplication.playHoverSound()
                                                }
                                                true
                                            }
                                            Key.DirectionDown -> {
                                                if (now - lastNavTime < navigationCooldownMs) return@onKeyEvent true
                                                lastNavTime = now
                                                val target = focusedAbsIndex + cardsPerRow
                                                if (target < totalCount) {
                                                    focusGridItem(target)
                                                    BlueHiveApplication.playTitleCardNavigation()
                                                } else {
                                                    val lastIndex = totalCount - 1
                                                    val lastRowStart = (lastIndex / cardsPerRow) * cardsPerRow
                                                    if (focusedAbsIndex < lastRowStart) {
                                                        focusGridItem(lastIndex)
                                                        BlueHiveApplication.playTitleCardNavigation()
                                                    }
                                                }
                                                true
                                            }
                                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                                if (item != null) {
                                                    isPressed = true
                                                    BlueHiveApplication.playClickSound()
                                                    navigateToDetails(item)
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    },
                            )
                        }
                    }
                }
            }
        }

        // ── Bottom fade scrim ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(65.dp)
                .background(scrimBrush)
        )
    }
}


// ─────────────────────────────────────────────────────────────────────────────
//  Placeholder media item used for empty (unloaded) grid slots
// ─────────────────────────────────────────────────────────────────────────────

private val PLACEHOLDER_MEDIA = MediaItem(
    tmdbId           = -1,
    mediaId          = -1,
    title            = "",
    mediaType        = "",
    posterUrl        = null,
    backdropUrl      = null,
    logoUrl          = null,
    overview         = null,
    releaseDate      = null,
    status           = null,
    voteAverage      = null,
    voteCount        = null,
    popularity       = null,
    popularityRank   = null,
    originalLanguage = null,
    numberOfSeasons  = null,
    numberOfEpisodes = null,
    contentRating    = null,
    runtime          = null,
    budget           = null,
    revenue          = null,
    trailerUrl       = null,
    genres           = null,
    similarItems     = null,
    whereToWatch     = null,
)


private data class CardDisplayData(
    val releaseDateText: String?,
    val runTimeText: String?,
)

private fun MediaItem.toCardDisplayData(): CardDisplayData {
    val releaseDateText = releaseDate
        ?.takeIf { it.isNotBlank() && it != "N/A" }
        ?.let { raw ->
            val parts = raw.split("-")
            if (parts.size == 3) "${parts[1]}/${parts[2]}/${parts[0]}" else raw
        }
    val runTimeText = if (mediaType == "tv") {
        numberOfSeasons?.takeIf { it > 0 }?.let { "S: $it" }
    } else {
        val minutes = runtime
        if (minutes == null || minutes <= 0) null else {
            val h = minutes / 60
            val m = minutes % 60
            when {
                h > 0 && m > 0 -> "${h}h ${m}m"
                h > 0          -> "${h}h"
                else           -> "${m}m"
            }
        }
    }
    return CardDisplayData(releaseDateText, runTimeText)
}


// ─────────────────────────────────────────────────────────────────────────────
//  Search bar (unchanged)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CatalogSearchBar(
    modifier: Modifier = Modifier,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    isFocused: Boolean,
    isEditing: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onFieldFocusChange: (Boolean) -> Unit,
    barFocusRequester: FocusRequester,
    fieldFocusRequester: FocusRequester,
    onStartEditing: () -> Unit,
    onStopEditing: () -> Unit,
    onSubmit: (String) -> Unit,
    onClear: () -> Unit,
) {
    Box(
        modifier = modifier
            .focusRequester(barFocusRequester)
            .onFocusChanged { onFocusChange(it.isFocused) }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (!isEditing) { onStartEditing(); true } else false
                    }
                    Key.Back -> {
                        if (isEditing) {
                            onStopEditing()
                            barFocusRequester.requestFocus()
                            true
                        } else false
                    }
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                        BlueHiveApplication.playHoverSound()
                        false
                    }
                    else -> false
                }
            }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onStartEditing() }
    ) {
        Image(
            painter = painterResource(id = R.drawable.button_focus_wide_glow),
            contentDescription = "Search bar focused",
            modifier = Modifier
                .offset(x = (-14).dp, y = (-17.7).dp)
                .width(252.dp + 36.dp - 60.dp)
                .height(12.dp + 40.2.dp)
                .graphicsLayer(alpha = if (isFocused || isEditing) 1f else 0f),
            contentScale = ContentScale.FillBounds
        )

        // composable background image
        Box(modifier = Modifier.width(200.dp).height(27.dp).offset(y = (-4.6).dp)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        color = if (isFocused) Color(0xFF2A2A3A) else Color(0xFF1A1A24),
                        shape = RoundedCornerShape(3.dp)
                    )
            )

            Image(
                painter = painterResource(R.drawable.magnifying_glass_raw_image),
                contentDescription = "Search",
                modifier = Modifier
                    .size(16.dp)
                    .offset(x = 7.dp, y = 6.dp)
                    .graphicsLayer(alpha = 0.4f)
            )

            BasicTextField(
                value = searchText,
                onValueChange = onSearchTextChange,
                singleLine = true,
                cursorBrush = SolidColor(if (isEditing) Color.White else Color.Transparent),
                textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit(searchText) }),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 33.dp)
                    .width(114.dp)
                    .focusRequester(fieldFocusRequester)
                    .focusProperties { canFocus = isEditing }
                    .onFocusChanged { state ->
                        onFieldFocusChange(state.isFocused)
                        if (!state.isFocused) onStopEditing()
                    }
                    .onKeyEvent { event ->
                        if (
                            event.type == KeyEventType.KeyUp &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter) &&
                            searchText.isNotBlank()
                        ) {
                            onSubmit(searchText)
                            true
                        } else false
                    }
            )

            if (searchText.isEmpty() && !isEditing) {
                Text(
                    text = "Search catalog...",
                    color = Color(0xFF656565),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 28.dp)
                )
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
//  Empty state (unchanged)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CatalogEmptyState(title: String, body: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(Color(0xFF121213), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF3A3737), RoundedCornerShape(12.dp))
                .padding(horizontal = 40.dp, vertical = 32.dp)
                .height(130.dp)
                .width(520.dp),
        ) {
            Text(
                text       = title,
                color      = Color.White,
                fontFamily = AppTypography.interBold,
                fontWeight = FontWeight.Bold,
                fontSize   = 20.sp,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
            Text(
                text       = body,
                color      = Color(0xFFAAAAAA),
                fontFamily = AppTypography.interSemiBold,
                fontWeight = FontWeight.Normal,
                fontSize   = 13.sp,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth().offset(y = 8.dp),
            )
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
//  Shared button helpers (unchanged)
// ─────────────────────────────────────────────────────────────────────────────

private fun catalogButtonDimensions(buttonWidth: androidx.compose.ui.unit.Dp) =
    ModularButtonDimensions(
        mainWidth          = buttonWidth,
        mainHeight         = 27.dp,
        mainYOffset        = 6.5.dp,
        secondWidth        = buttonWidth,
        secondHeight       = 12.dp,
        secondYOffset      = 26.dp,
        mainCornerRadius   = 7f,
        secondCornerRadius = 8f,
        shadowHeight       = 12.dp,
        glowWidth          = buttonWidth + 14.2.dp,
        glowHeight         = 41.dp,
    )

private fun catalogButtonColors(
    mainDefault: Color, mainFocused: Color, mainToggled: Color,
    secondDefault: Color, secondFocused: Color, secondToggled: Color,
    textFocused: Color, textUnfocused: Color,
    textUnfocusedToggled: Color, textToggledFocus: Color,
) = ModularButtonColors(
    mainDefault          = mainDefault,
    mainToggled          = mainToggled,
    mainFocused          = mainFocused,
    secondDefault        = secondDefault,
    secondToggled        = secondToggled,
    secondFocused        = secondFocused,
    textFocused          = textFocused,
    textUnfocused        = textUnfocused,
    textUnfocusedToggled = textUnfocusedToggled,
    textToggledFocus     = textToggledFocus,
)

private fun catalogButtonGlowConfig() =
    ModularButtonGlowConfig(
        enabled               = true,
        defaultRes            = R.drawable.button_focus_wide_glow,
        offsetX               = (-6.85).dp,
        offsetY               = 7.dp,
        cornerRadius          = 100.dp,
        fadeOutDurationMillis = 200,
        fadeInDurationMillis  = 400,
    )

private fun catalogButtonAnimationConfig() =
    ModularButtonAnimationConfig(
        pressOffset           = 3.5.dp,
        textOffsetDefault     = 7.9.dp,
        textOffsetPressed     = 9.9.dp,
        durationMillis        = 110,
        bounceBackDelayMillis = 200,
    )





@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    widthDp = 960,
    heightDp = 540,
    name = "Catalog Control Bar"
)
@Composable
private fun PreviewCatalogControlBar() {
    val mainDefault          = Color(0xFF192C6F)
    val mainFocused          = Color(0xFF4C63AC)
    val mainToggled          = Color(0xFF101628)
    val secondDefault        = Color(0xFF151C3A)
    val secondFocused        = Color(0xFF2E325F)
    val secondToggled        = Color(0xFF1B1C34)
    val textUnfocused        = Color(0xFF868686)
    val textFocused          = Color(0xFFDADADA)
    val textUnfocusedToggled = Color(0xFF343434)
    val textToggledFocus     = Color(0xFFDADADA)

    var selectedGenre        by remember { mutableStateOf(ASSORTED_OPTION) }
    var selectedSort         by remember { mutableStateOf("Most Popular") }
    var selectedYear         by remember { mutableStateOf("Assorted") }
    var selectedMediaType    by remember { mutableStateOf("movie") }
    var searchText           by remember { mutableStateOf("") }
    var isSearchEditing      by remember { mutableStateOf(true) }
    var isSearchBarFocused   by remember { mutableStateOf(true) }

    val filterFR    = remember { FocusRequester() }
    val sortFR      = remember { FocusRequester() }
    val yearFR      = remember { FocusRequester() }
    val searchBarFR = remember { FocusRequester() }
    val searchFldFR = remember { FocusRequester() }
    val moviesFR    = remember { FocusRequester() }
    val tvFR        = remember { FocusRequester() }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E1A))) {

        HistoryDropdown(
            label = "Filter", options = GENRE_OPTIONS,
            selectedOption = selectedGenre, onOptionSelected = { selectedGenre = it },
            buttonWidth = 160.dp, dropdownMaxHeight = 237.dp,
            externalFocusRequester = filterFR,
            modifier = Modifier.align(Alignment.TopStart).offset(x = FILTER_DROPDOWN_X, y = CONTROLS_ROW_Y).zIndex(1f),
        )
        HistoryDropdown(
            label = "Sort", options = SORT_OPTIONS,
            selectedOption = selectedSort, onOptionSelected = { selectedSort = it },
            buttonWidth = 140.dp, dropdownMaxHeight = 437.dp,
            externalFocusRequester = sortFR,
            modifier = Modifier.align(Alignment.TopStart).offset(x = SORT_DROPDOWN_X, y = CONTROLS_ROW_Y).zIndex(1f),
        )
        HistoryDropdown(
            label = "Year", options = YEAR_OPTIONS,
            selectedOption = selectedYear, onOptionSelected = { selectedYear = it },
            buttonWidth = 130.dp, dropdownMaxHeight = 237.dp, externalFocusRequester = yearFR,
            modifier = Modifier.align(Alignment.TopStart).offset(x = YEAR_DROPDOWN_X, y = CONTROLS_ROW_Y).zIndex(1f),
        )
        CatalogSearchBar(
            modifier = Modifier.align(Alignment.TopStart).offset(x = SEARCH_BAR_X, y = CONTROLS_ROW_Y + 4.dp).zIndex(1f),
            searchText = searchText, onSearchTextChange = { searchText = it },
            isFocused = isSearchBarFocused, isEditing = isSearchEditing,
            onFocusChange = { isSearchBarFocused = it }, onFieldFocusChange = {},
            barFocusRequester = searchBarFR, fieldFocusRequester = searchFldFR,
            onStartEditing = { isSearchEditing = true }, onStopEditing = { isSearchEditing = false },
            onSubmit = {}, onClear = { searchText = "" },
        )
        ModularButton(
            textConfig = ModularButtonTextConfig(text = "Movies", fontSize = 10f),
            isToggleable = true, externalToggled = selectedMediaType == "movie",
            isFocusable = selectedMediaType != "movie", fontFamily = AppTypography.interBold,
            focusRequester = moviesFR, onClick = { selectedMediaType = "movie" },
            modifier = Modifier.align(Alignment.TopStart).offset(x = MEDIA_TYPE_BUTTON_X, y = MEDIA_TYPE_BUTTON_Y),
            dimensions = catalogButtonDimensions(MEDIA_TYPE_BUTTON_W),
            colors = catalogButtonColors(mainDefault, mainFocused, mainToggled, secondDefault, secondFocused, secondToggled, textFocused, textUnfocused, textUnfocusedToggled, textToggledFocus),
            glowConfig = catalogButtonGlowConfig(), animationConfig = catalogButtonAnimationConfig(),
        )
        ModularButton(
            textConfig = ModularButtonTextConfig(text = "TV Shows", fontSize = 10f),
            isToggleable = true, externalToggled = selectedMediaType == "tv",
            isFocusable = selectedMediaType != "tv", fontFamily = AppTypography.interBold,
            focusRequester = tvFR, onClick = { selectedMediaType = "tv" },
            modifier = Modifier.align(Alignment.TopStart).offset(x = MEDIA_TYPE_BUTTON_X, y = MEDIA_TYPE_BUTTON_Y + MEDIA_TYPE_SPACING_Y),
            dimensions = catalogButtonDimensions(MEDIA_TYPE_BUTTON_W),
            colors = catalogButtonColors(mainDefault, mainFocused, mainToggled, secondDefault, secondFocused, secondToggled, textFocused, textUnfocused, textUnfocusedToggled, textToggledFocus),
            glowConfig = catalogButtonGlowConfig(), animationConfig = catalogButtonAnimationConfig(),
        )
    }
}