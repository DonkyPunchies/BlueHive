package com.example.bluehive.sidebarComponents

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.MoviesDetailsScreenCompose
import com.example.bluehive.R
import com.example.bluehive.SourceScreen
import com.example.bluehive.TVShowsDetailsScreenCompose
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.MediaDetailResponse
import com.example.bluehive.models.MediaItem
import com.example.bluehive.repository.EpisodesRepository
import com.example.bluehive.repository.RecommendationsRepository
import com.example.bluehive.utilities.AppTypography
import com.example.bluehive.utilities.MediaCard
import com.example.bluehive.utilities.ModularButton
import com.example.bluehive.utilities.ModularButtonAnimationConfig
import com.example.bluehive.utilities.ModularButtonColors
import com.example.bluehive.utilities.ModularButtonDimensions
import com.example.bluehive.utilities.ModularButtonGlowConfig
import com.example.bluehive.utilities.ModularButtonTextConfig
import com.exjunk.lib.thanos.vanish.VanishContainer
import com.exjunk.lib.thanos.vanish.VanishEffect.vanishable
import com.exjunk.lib.thanos.vanish.VanishGLSurfaceView
import com.exjunk.lib.thanos.vanish.rememberVanishController
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch



// ─────────────────────────────────────────────────────────────────────────────
//  Internal model — merges FavoriteResponse + TMDB details into one object
// ─────────────────────────────────────────────────────────────────────────────

private data class FavoriteEntry(
    val favoriteId:      Int,
    val tmdbId:          Int,
    val mediaType:       String,
    val addedAt:         String,
    val title:           String        = "",
    val posterPath:      String?       = null,
    val backdropPath:    String?       = null,
    val releaseDate:     String?       = null,
    val voteAverage:     Double?       = null,
    val overview:        String?       = null,
    val contentRating:   String?       = null,
    val originalLanguage: String?      = null,
    val numberOfSeasons: Int?          = null,
    val runtime:         Int?          = null,
    val status:          String?       = null,
    val trailerUrl:      String?       = null,
    val logoUrl:         String?       = null,
    val genresList:      List<String>  = emptyList(),
    val genresString:    String        = "N/A",
)


// ─────────────────────────────────────────────────────────────────────────────
//  Entry point
// ─────────────────────────────────────────────────────────────────────────────

fun openFavoritesScreen(context: Context, profileId: Int = -1) {
    context.startActivity(
        Intent(context, FavoritesScreenActivity::class.java)
            .putExtra("PROFILE_ID", profileId)
    )
}

class FavoritesScreenActivity : ComponentActivity() {

    private val refreshTrigger = mutableIntStateOf(0)

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
                finish()
            }
        })

        val profileId = intent.getIntExtra("PROFILE_ID", -1)
        setContent {
            FavoritesScreen(profileId = profileId, refreshTrigger = refreshTrigger.intValue)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTrigger.intValue++
    }

    override fun onPause() {
        super.onPause()
        // glSurfaceView lifecycle handled by VanishContainer internally
    }
}


// ─────────────────────────────────────────────────────────────────────────────
//  Main composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FavoritesScreen(profileId: Int = -1, refreshTrigger: Int = 0) {

    // ── Grid layout config ────────────────────────────────────────────────────
    val cardsPerRow           = 7          // max cards in a single row
    val cardHorizontalSpacing = 8.dp       // horizontal gap between cards
    val rowSpacing            = 12.dp      // vertical gap between rows
    val gridOffsetY           = 118.dp     // top of grid, below the control bar
    val gridHeight            = 430.dp     // visible scroll window height
    val navigationCooldownMs  = 80L       // originally 120

    // ── Header config ─────────────────────────────────────────────────────────
    val headerText     = "Favorite Content"
    val headerFontSize = 29f
    val headerOpacity  = 0.95f
    val headerOffsetX  = 55.dp
    val headerOffsetY  = 25.dp

    // ── Dropdown config ───────────────────────────────────────────────────────
    val dropdownOffsetX = 55.dp
    val dropdownOffsetY = 70.dp
    val dropdownSpacing = 170.dp

    // ── Filter tab config ─────────────────────────────────────────────────────
    val filterButtonOffsetX = 618.dp
    val filterButtonOffsetY = 60.dp
    val filterButtonWidth   = 90.dp
    val filterButtonSpacing = filterButtonWidth + 9.dp

    // ── Data state ────────────────────────────────────────────────────────────
    val favEntries   = remember { mutableStateListOf<FavoriteEntry>() }
    var isLoading    by remember { mutableStateOf(true) }
    var isNavigating by remember { mutableStateOf(false) }

    val context        = LocalContext.current
    val coroutineScope = rememberCoroutineScope()



    // ── Filter / sort state ───────────────────────────────────────────────────
    val genres = listOf(
        "All Content",
        "Action", "Adventure", "Animation", "Comedy", "Crime",
        "Documentary", "Drama", "Family", "Fantasy", "History",
        "Horror", "Music", "Mystery", "Romance", "Science Fiction",
        "TV Movie", "Thriller", "War", "Western",
        "Action & Adventure", "Kids", "News", "Reality",
        "Sci-Fi & Fantasy", "Soap", "Talk", "War & Politics",
    )
    val sortOptions = listOf("Newest", "Oldest", "A–Z", "Z–A", "By Release Date")

    var selectedGenre         by remember { mutableStateOf("All Content") }
    var selectedSort          by remember { mutableStateOf("Newest") }
    var isAnyDropdownExpanded by remember { mutableStateOf(false) }
    var selectedFilter        by remember { mutableStateOf("all") }
    var suppressNextHover     by remember { mutableStateOf(false) }
    var removeMode            by remember { mutableStateOf(false) }
    var showDeleteConfirm     by remember { mutableStateOf(false) }
    val deletedEntries        = remember { mutableStateListOf<FavoriteEntry>() }

    // ── Focus requesters — control bar ────────────────────────────────────────
    val filterDropdownFocus = remember { FocusRequester() }
    val sortDropdownFocus   = remember { FocusRequester() }
    val allMediaFocus       = remember { FocusRequester() }
    val moviesFocus         = remember { FocusRequester() }
    val tvSeriesFocus       = remember { FocusRequester() }
    val deleteButtonFocus   = remember { FocusRequester() }
    val acceptChangesFocus  = remember { FocusRequester() }
    val undoChangesFocus    = remember { FocusRequester() }

    // ── Grid focus state ──────────────────────────────────────────────────────
    // Mirrors TvWideLazyRow exactly:
    //   hasGridFocus  = whether ANY card in the grid currently has focus
    //   focusedGridIndex = which index is focused (-1 = none yet)
    // isItemFocused = hasGridFocus && focusedGridIndex == index
    // This is the ONLY way MediaCard.isFocused gets set — never individual per-card vars.
    var hasGridFocus     by remember { mutableStateOf(false) }
    var focusedGridIndex by remember { mutableIntStateOf(-1) }
    var lastNavTime      by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            favEntries.clear()
            deletedEntries.clear()

            isLoading = true
            isNavigating = false

            selectedGenre = "All Content"
            selectedSort = "Newest"
            selectedFilter = "all"
            isAnyDropdownExpanded = false
            suppressNextHover = false
            removeMode = false
            showDeleteConfirm = false

            hasGridFocus = false
            focusedGridIndex = -1
            lastNavTime = 0L
        }
    }



    // ── Button colors (shared) ────────────────────────────────────────────────
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

    // ── MediaCard painters — loaded once, reused for every card ──────────────
    val backgroundPainter = painterResource(id = R.drawable.titlecard_base)
    val focusedPainter    = painterResource(id = R.drawable.titlecard_focused)
    val shadowPainter     = painterResource(id = R.drawable.titlecard_shadow)
    var glSurfaceView by remember { mutableStateOf<VanishGLSurfaceView?>(null) }

    // ── Data fetch ────────────────────────────────────────────────────────────
    LaunchedEffect(profileId, refreshTrigger) {
        if (profileId == -1) { isLoading = false; return@LaunchedEffect }
        // First load shows the loading state. Subsequent resumes (refreshTrigger > 1)
        // re-fetch silently in the background so existing cards stay visible — no flash, no jerk.
        val silent = favEntries.isNotEmpty()
        if (!silent) isLoading = true
        try {
            val rawList = ApiClient.bluehiveApi.getFavorites(profileId)
            val detailed = rawList.map { fav ->
                async {
                    try {
                        ApiClient.trailerApi.getMediaDetails(
                            tmdbId    = fav.media_tmdb_id,
                            mediaType = fav.media_type,
                        ).toFavoriteEntry(favoriteId = fav.id, addedAt = fav.added_at)
                    } catch (_: Exception) {
                        FavoriteEntry(
                            favoriteId = fav.id,
                            tmdbId     = fav.media_tmdb_id,
                            mediaType  = fav.media_type,
                            addedAt    = fav.added_at,
                            title      = fav.media_title ?: "Unknown",
                        )
                    }
                }
            }.awaitAll()
            favEntries.clear()
            favEntries.addAll(detailed)
        } catch (e: Exception) {
            android.util.Log.e("FavoritesScreen", "Failed to load favorites: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    // ── Filtered + sorted list ────────────────────────────────────────────────
    val filteredEntries by remember(selectedFilter, selectedGenre, selectedSort) {
        derivedStateOf {
            var items: List<FavoriteEntry> = when (selectedFilter) {
                "movies" -> favEntries.filter { it.mediaType == "movie" }
                "tv"     -> favEntries.filter { it.mediaType == "tv" }
                else     -> favEntries.toList()
            }
            if (selectedGenre != "All Content") {
                items = items.filter { entry ->
                    entry.genresList.any { it.equals(selectedGenre, ignoreCase = true) }
                }
            }
            items = when (selectedSort) {
                "Oldest"          -> items.sortedBy { it.favoriteId }
                "A–Z"             -> items.sortedBy { it.title.lowercase() }
                "Z–A"             -> items.sortedByDescending { it.title.lowercase() }
                "By Release Date" -> items.sortedByDescending { it.releaseDate ?: "" }
                else              -> items.sortedByDescending { it.favoriteId }  // Newest
            }
            items
        }
    }

    val showFilteredEmptyState by remember(isLoading, favEntries.size, filteredEntries.size) {
        derivedStateOf {
            !isLoading && favEntries.isNotEmpty() && filteredEntries.isEmpty()
        }
    }

    // ── Per-item FocusRequesters keyed to list size — same pattern as TvWideLazyRow ──
    val gridFocusRequesters = remember { List(200) { FocusRequester() } }

    // ── Grid scroll state ─────────────────────────────────────────────────────
    val gridState = rememberLazyGridState()

    // ── Reset focus + scroll when filter/sort changes ─────────────────────────
    LaunchedEffect(selectedFilter, selectedGenre, selectedSort) {
        focusedGridIndex = -1
        gridState.scrollToItem(0)
    }

    // ── Auto-land on first item when grid receives focus with nothing selected ─
    // Mirrors TvWideLazyRow: LaunchedEffect(items.size, hasRowFocus)
    LaunchedEffect(filteredEntries.size, hasGridFocus) {
        if (hasGridFocus && filteredEntries.isNotEmpty() && focusedGridIndex == -1) {
            focusedGridIndex = 0
            gridFocusRequesters[0].requestFocus()
        }
    }

    // ── On page enter: auto-focus first card as soon as data is ready ─────────
    // Fires once when isLoading flips to false and there is at least one item.
    // awaitFrame gives the grid one frame to compose its items before we
    // request focus — without it the FocusRequester may not be attached yet.
    LaunchedEffect(isLoading) {
        if (!isLoading && filteredEntries.isNotEmpty()) {
            kotlinx.coroutines.android.awaitFrame()
            val targetIndex = focusedGridIndex.coerceIn(0, filteredEntries.lastIndex)
            focusedGridIndex = targetIndex
            gridState.scrollToItem(targetIndex)
            kotlinx.coroutines.android.awaitFrame()
            gridFocusRequesters.getOrNull(targetIndex)?.requestFocus()
        }
    }


    // ── Restore focus to next card after removal in Remove Mode ───────────────
    LaunchedEffect(filteredEntries.size) {
        if (removeMode && filteredEntries.isNotEmpty()) {
            kotlinx.coroutines.android.awaitFrame()
            kotlinx.coroutines.android.awaitFrame()
            val nextIndex = focusedGridIndex.coerceIn(0, filteredEntries.lastIndex)
            focusedGridIndex = nextIndex
            gridFocusRequesters.getOrNull(nextIndex)?.requestFocus()
        }
    }


    // ── focusGridItem — mirrors TvWideLazyRow.focusAndScrollTo ───────────────
    fun focusGridItem(index: Int, scroll: Boolean = true) {
        if (index !in filteredEntries.indices) return
        focusedGridIndex = index
        gridFocusRequesters[index].requestFocus()
        if (scroll) {
            coroutineScope.launch {
                val layoutInfo = gridState.layoutInfo
                val viewportTop = layoutInfo.viewportStartOffset
                val viewportBottom = layoutInfo.viewportEndOffset
                val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

                if (targetItem != null) {
                    // Item is composed but might be partially clipped
                    val scrollMargin = 60  // px — breathing room so cards don't hug the edge
                    val itemTop = targetItem.offset.y
                    val itemBottom = itemTop + targetItem.size.height

                    when {
                        // Card is clipped at the bottom — nudge down just enough
                        itemBottom + scrollMargin > viewportBottom -> {
                            val scrollBy = (itemBottom + scrollMargin - viewportBottom).toFloat()
                            gridState.animateScrollBy(scrollBy)
                        }
                        // Card is clipped at the top — nudge up just enough
                        itemTop - scrollMargin < viewportTop -> {
                            val scrollBy = (itemTop - scrollMargin - viewportTop).toFloat()
                            gridState.animateScrollBy(scrollBy)
                        }
                        // Fully visible — don't scroll at all
                    }
                } else {
                    // Item isn't composed yet (far away) — fall back to item-based scroll
                    // but target one row above so it doesn't slam to the top edge
                    val rowAbove = (index - cardsPerRow).coerceAtLeast(0)
                    gridState.animateScrollToItem(rowAbove)
                }
            }
        }
    }

    // ── Navigate to details — press-and-prefetch pattern ─────────────────────
    fun navigateToDetails(entry: FavoriteEntry) {
        if (isNavigating) return
        isNavigating = true
        coroutineScope.launch {
            try {
                val detailsDeferred = async {
                    ApiClient.trailerApi.getMediaDetails(
                        tmdbId    = entry.tmdbId,
                        mediaType = entry.mediaType,
                    )
                }
                val episodesDeferred = if (entry.mediaType == "tv") {
                    async {
                        try {
                            EpisodesRepository().getSeasonEpisodes(
                                tmdbId       = entry.tmdbId,
                                seasonNumber = 1,
                            ).episodes
                        } catch (_: Exception) { null }
                    }
                } else null
                val recsDeferred = async {
                    try {
                        RecommendationsRepository().getRecommendations(
                            entry.tmdbId, entry.mediaType,
                        )
                    } catch (_: Exception) { emptyList() }
                }

                delay(800)

                val details = detailsDeferred.await()
                val app = context.applicationContext as? BlueHiveApplication
                app?.storePrefetch(
                    BlueHiveApplication.MediaPrefetchData(
                        tmdbId          = entry.tmdbId,
                        mediaType       = entry.mediaType,
                        episodes        = episodesDeferred?.await(),
                        recommendations = recsDeferred.await(),
                    )
                )

                val logoUrl      = (details.logos as? String)?.takeIf { it.isNotBlank() } ?: ""
                val genresString = entry.genresString

                val intent = if (entry.mediaType == "movie") {
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
                        putExtra("SOURCE_SCREEN",       SourceScreen.FAVORITES)
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
                        putExtra("SOURCE_SCREEN",       SourceScreen.FAVORITES)
                    }
                }

                isNavigating = false
                context.startActivity(intent)

            } catch (e: Exception) {
                android.util.Log.e("FavoritesScreen", "Navigation failed: ${e.message}")
                isNavigating = false
            }
        }
    }

    // ── Auto-focus confirm overlay when it appears ────────────────────────────
    val showExitRemoveOverlay by remember(
        showDeleteConfirm,
        removeMode,
        deletedEntries.size,
        favEntries.size
    ) {
        derivedStateOf {
            showDeleteConfirm || (removeMode && deletedEntries.isNotEmpty() && favEntries.isEmpty())
        }
    }

    LaunchedEffect(showExitRemoveOverlay) {
        if (showExitRemoveOverlay) {
            kotlinx.coroutines.android.awaitFrame()
            acceptChangesFocus.requestFocus()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI
    // ─────────────────────────────────────────────────────────────────────────

    Box(modifier = Modifier.fillMaxSize()) {

        // Background
        Image(
            painter            = painterResource(id = R.drawable.home_screen),
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )

        // Dark scrim — turns red at low opacity when Remove Mode is active
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (removeMode) Color.Red.copy(alpha = 0.15f)
                    else Color.Black.copy(alpha = 0.45f)
                )
        )

        // ── Empty state ───────────────────────────────────────────────────────
        if (!isLoading && favEntries.isEmpty() && !showExitRemoveOverlay) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier         = Modifier.fillMaxSize(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color(0xFF121213), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF3A3737), RoundedCornerShape(12.dp))
                        .padding(horizontal = 40.dp, vertical = 32.dp)
                        .height(130.dp)
                        .width(480.dp),
                ) {
                    Text(
                        text       = "No favorite content added yet",
                        color      = Color.White,
                        fontFamily = AppTypography.interBold,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 20.sp,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                    )
                    Text(
                        text       = "Open any movie or TV show and press the favorite button to see it here.",
                        color      = Color(0xFFAAAAAA),
                        fontFamily = AppTypography.interSemiBold,
                        fontWeight = FontWeight.Normal,
                        fontSize   = 13.sp,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier
                            .fillMaxWidth()
                            .offset(y = 8.dp),
                    )
                    Text(
                        text       = "To leave just press the back button",
                        color      = Color(0xFFA01D1D),
                        fontFamily = AppTypography.interBold,
                        fontWeight = FontWeight.Normal,
                        fontSize   = 15.sp,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier
                            .fillMaxWidth()
                            .offset(y = 36.dp, x = 2.dp),
                    )
                }
            }

        } else if (!isLoading) {

            // ── Header — switches to remove mode instruction when active ──────
            Text(
                text       = if (removeMode) "Click on any media you want to remove" else headerText,
                fontSize   = headerFontSize.sp,
                fontFamily = AppTypography.lalezarRegular,
                color      = Color.White.copy(alpha = headerOpacity),
                modifier   = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = headerOffsetX, y = headerOffsetY),
            )

            // ── Dropdowns ─────────────────────────────────────────────────────
            HistoryDropdown(
                label                    = "Filter",
                options                  = genres,
                selectedOption           = selectedGenre,
                onOptionSelected         = { selectedGenre = it },
                buttonWidth              = 160.dp,
                dropdownMaxHeight        = 237.dp,
                externalFocusRequester   = filterDropdownFocus,
                suppressInitialFocusSound = true,
                onExpandedChanged        = { isAnyDropdownExpanded = it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = dropdownOffsetX, y = dropdownOffsetY)
                    .zIndex(1f),
            )

            HistoryDropdown(
                label                  = "Sort",
                options                = sortOptions,
                selectedOption         = selectedSort,
                onOptionSelected       = { selectedSort = it },
                buttonWidth            = 140.dp,
                externalFocusRequester = sortDropdownFocus,
                onExpandedChanged      = { isAnyDropdownExpanded = it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = dropdownOffsetX + dropdownSpacing, y = dropdownOffsetY)
                    .zIndex(1f),
            )

            // ── Remove Media button ───────────────────────────────────────────
            ModularButton(
                textConfig      = ModularButtonTextConfig(text = "Remove Media", fontSize = 9f),
                isToggleable    = true,
                externalToggled = removeMode,
                isFocusable     = true,
                fontFamily      = AppTypography.interBold,
                focusRequester  = deleteButtonFocus,
                onClick = {
                    removeMode = !removeMode
                    if (removeMode) {
                        // Entering remove mode — jump focus to first card
                        coroutineScope.launch {
                            focusedGridIndex = 0
                            gridState.scrollToItem(0)
                            kotlinx.coroutines.android.awaitFrame()
                            gridFocusRequesters.getOrNull(0)?.requestFocus()
                        }
                    } else {
                        // Manually toggling off — treat same as Accept, commit to backend
                        coroutineScope.launch {
                            deletedEntries.forEach { entry ->
                                try {
                                    ApiClient.bluehiveApi.toggleFavorite(
                                        com.example.bluehive.api.FavoriteRequest(
                                            profile_id    = profileId,
                                            media_tmdb_id = entry.tmdbId,
                                            media_type    = entry.mediaType,
                                            media_title   = entry.title,
                                        )
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.e("FavoritesScreen", "Delete failed: ${e.message}")
                                }
                            }
                            deletedEntries.clear()
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = dropdownOffsetX + dropdownSpacing + 150.dp, y = filterButtonOffsetY)
                    .zIndex(1f)
                    .onFocusChanged {
                        if (it.isFocused) BlueHiveApplication.playHoverSound()
                    },
                dimensions = ModularButtonDimensions(
                    mainWidth          = 80.dp,
                    mainHeight         = 27.dp,
                    mainYOffset        = 7.3.dp,
                    secondWidth        = 80.dp,
                    secondHeight       = 12.dp,
                    secondYOffset      = 26.dp,
                    mainCornerRadius   = 7f,
                    secondCornerRadius = 8f,
                    shadowHeight       = 12.dp,
                    glowWidth          = 80.dp + 14.2.dp,
                    glowHeight         = 41.dp,
                ),
                colors = ModularButtonColors(
                    mainDefault          = Color(0xFF6D2121),
                    mainToggled          = Color(0xFF280D0D),
                    mainFocused          = Color(0xFFAD2626),
                    secondDefault        = Color(0xFF3A1515),
                    secondToggled        = Color(0xFF341B1B),
                    secondFocused        = Color(0xFF783535),
                    textFocused          = Color(0xFFDADADA),
                    textUnfocused        = Color(0xFF848383),
                    textUnfocusedToggled = Color(0xFF343434),
                    textToggledFocus     = Color(0xFFDADADA),
                ),
                glowConfig = filterButtonGlowConfig(),
                animationConfig = filterButtonAnimationConfig(),
            )

            // ── All Media button ──────────────────────────────────────────────
            ModularButton(
                textConfig      = ModularButtonTextConfig(text = "All Media", fontSize = 10f),
                isToggleable    = true,
                externalToggled = selectedFilter == "all",
                isFocusable     = selectedFilter != "all",
                fontFamily      = AppTypography.interBold,
                focusRequester  = allMediaFocus,
                onClick = {
                    selectedFilter = "all"
                    suppressNextHover = true
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        moviesFocus.requestFocus()
                    }, 50)
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = filterButtonOffsetX, y = filterButtonOffsetY)
                    .onFocusChanged {
                        if (it.isFocused) {
                            if (!suppressNextHover) BlueHiveApplication.playHoverSound()
                            else suppressNextHover = false
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            BlueHiveApplication.playHoverSound()
                        }
                        false
                    },
                dimensions      = filterButtonDimensions(filterButtonWidth),
                colors          = filterButtonColors(mainDefault, mainFocused, mainToggled, secondDefault, secondFocused, secondToggled, textFocused, textUnfocused, textUnfocusedToggled, textToggledFocus),
                glowConfig      = filterButtonGlowConfig(),
                animationConfig = filterButtonAnimationConfig(),
            )

            // ── Movies button ─────────────────────────────────────────────────
            ModularButton(
                textConfig      = ModularButtonTextConfig(text = "Movies", fontSize = 10f),
                isToggleable    = true,
                externalToggled = selectedFilter == "movies",
                isFocusable     = selectedFilter != "movies",
                fontFamily      = AppTypography.interBold,
                focusRequester  = moviesFocus,
                onClick = {
                    selectedFilter = "movies"
                    suppressNextHover = true
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        tvSeriesFocus.requestFocus()
                    }, 50)
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = filterButtonOffsetX + filterButtonSpacing, y = filterButtonOffsetY)
                    .onFocusChanged {
                        if (it.isFocused) {
                            if (!suppressNextHover) BlueHiveApplication.playHoverSound()
                            else suppressNextHover = false
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            BlueHiveApplication.playHoverSound()
                        }
                        false
                    },
                dimensions      = filterButtonDimensions(filterButtonWidth),
                colors          = filterButtonColors(mainDefault, mainFocused, mainToggled, secondDefault, secondFocused, secondToggled, textFocused, textUnfocused, textUnfocusedToggled, textToggledFocus),
                glowConfig      = filterButtonGlowConfig(),
                animationConfig = filterButtonAnimationConfig(),
            )

            // ── TV Shows button ───────────────────────────────────────────────
            ModularButton(
                textConfig      = ModularButtonTextConfig(text = "TV Shows", fontSize = 10f),
                isToggleable    = true,
                externalToggled = selectedFilter == "tv",
                isFocusable     = selectedFilter != "tv",
                fontFamily      = AppTypography.interBold,
                focusRequester  = tvSeriesFocus,
                onClick = {
                    selectedFilter = "tv"
                    suppressNextHover = true
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        moviesFocus.requestFocus()
                    }, 50)
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x = filterButtonOffsetX + (filterButtonSpacing * 2),
                        y = filterButtonOffsetY
                    )
                    .onFocusChanged {
                        if (it.isFocused) {
                            if (!suppressNextHover) BlueHiveApplication.playHoverSound()
                            else suppressNextHover = false
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            BlueHiveApplication.playHoverSound()
                        }
                        false
                    },
                dimensions      = filterButtonDimensions(filterButtonWidth),
                colors          = filterButtonColors(mainDefault, mainFocused, mainToggled, secondDefault, secondFocused, secondToggled, textFocused, textUnfocused, textUnfocusedToggled, textToggledFocus),
                glowConfig      = filterButtonGlowConfig(),
                animationConfig = filterButtonAnimationConfig(),
            )

            // ── Media card grid ───────────────────────────────────────────────
            // VanishContainer provides the OpenGL surface layer that the
            // particle disintegration effect renders onto when a card is removed.
                VanishContainer(
                    modifier = Modifier.fillMaxSize(),
                    onGLSurfaceViewCreated = {
                        glSurfaceView = it
                        it.setAnimationConfig(
                            duration = 2500f,
                            particleSize = 2f
                        )
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = gridOffsetY)
                            .width(860.dp)
                            .height(gridHeight)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                                    if (removeMode) {
                                        showDeleteConfirm = true
                                    } else {
                                        filterDropdownFocus.requestFocus()
                                    }
                                    true
                                } else false
                            },
                    ) {
                        LazyVerticalGrid(
                            columns           = GridCells.Fixed(cardsPerRow),
                            state             = gridState,
                            userScrollEnabled = false,
                            modifier          = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = if (isAnyDropdownExpanded) 0.08f else 1f }
                                .focusGroup()
                                .onFocusChanged { fs -> hasGridFocus = fs.hasFocus },
                            contentPadding    = PaddingValues(bottom = 50.dp),
                            horizontalArrangement = Arrangement.spacedBy(cardHorizontalSpacing),
                            verticalArrangement   = Arrangement.spacedBy(rowSpacing),
                        ) {

                        itemsIndexed(
                            items = filteredEntries,
                            key   = { _, entry -> entry.favoriteId },
                        ) { index, entry ->

                            // ── Single source of truth for focus ──────────────────
                            // Mirrors TvWideLazyRow: val isItemFocused = hasRowFocus && (focusedIndex == index)
                            val isItemFocused = hasGridFocus && focusedGridIndex == index

                            // Each card gets its own VanishController so only the tapped
                            // card plays the disintegration effect, not the whole grid.
                            val vanishController = rememberVanishController(glSurfaceView)

                            var isPressed by remember { mutableStateOf(false) }
                            val cardScale by animateFloatAsState(
                                targetValue   = if (isPressed) 0.80f else 1f,
                                animationSpec = androidx.compose.animation.core.tween(250),  // higher number is slower animation
                                label         = "cardScale",
                            )

                            val mediaItem = remember(entry) {
                                MediaItem(
                                    tmdbId           = entry.tmdbId,
                                    mediaId          = entry.tmdbId,
                                    title            = entry.title,
                                    mediaType        = entry.mediaType,
                                    posterUrl        = entry.posterPath,
                                    backdropUrl      = entry.backdropPath,
                                    logoUrl          = entry.logoUrl,
                                    overview         = entry.overview,
                                    releaseDate      = entry.releaseDate,
                                    status           = entry.status,
                                    voteAverage      = entry.voteAverage,
                                    voteCount        = null,
                                    popularity       = null,
                                    popularityRank   = null,
                                    originalLanguage = entry.originalLanguage,
                                    numberOfSeasons  = entry.numberOfSeasons,
                                    numberOfEpisodes = null,
                                    contentRating    = entry.contentRating,
                                    runtime          = null,
                                    budget           = null,
                                    revenue          = null,
                                    trailerUrl       = entry.trailerUrl,
                                    genres           = entry.genresList.ifEmpty { null },
                                    similarItems     = null,
                                    whereToWatch     = null,
                                )
                            }

                            val releaseDateText = remember(entry.releaseDate) {
                                entry.releaseDate
                                    ?.takeIf { it.isNotBlank() && it != "N/A" }
                                    ?.let { raw ->
                                        val parts = raw.split("-")
                                        if (parts.size == 3) "${parts[1]}/${parts[2]}/${parts[0]}" else raw
                                    }
                            }

                            // TV: show season count. Movies: show runtime if available.
                            val runTimeText = remember(entry.runtime, entry.numberOfSeasons, entry.mediaType) {
                                if (entry.mediaType == "tv") {
                                    entry.numberOfSeasons?.takeIf { it > 0 }?.let { "S: $it" }
                                } else {
                                    val minutes = entry.runtime ?: return@remember null
                                    if (minutes <= 0) null else {
                                        val h = minutes / 60
                                        val m = minutes % 60
                                        when {
                                            h > 0 && m > 0 -> "${h}h ${m}m"
                                            h > 0          -> "${h}h"
                                            else           -> "${m}m"
                                        }
                                    }
                                }
                            }



                            // ── Cell structure — mirrors TvWideLazyRow's Column { MediaCard } ──
                            // .vanishable() registers this composable with the VanishController
                            // so the library can capture a bitmap of it when vanish() is called.
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(112.dp)
                                    .graphicsLayer {
                                        scaleX = cardScale
                                        scaleY = cardScale
                                    }
                                    .then(
                                        if (removeMode) Modifier.vanishable(
                                            vanishController,
                                            backgroundColor = Color.Transparent
                                        )
                                        else Modifier
                                    ),
                            ) {
                                MediaCard(
                                    mediaItem         = mediaItem,
                                    isFocused         = isItemFocused,
                                    backgroundPainter = backgroundPainter,
                                    focusedPainter    = focusedPainter,
                                    shadowPainter     = shadowPainter,
                                    releaseDateText   = releaseDateText,
                                    runTimeText       = runTimeText,
                                    rating            = entry.voteAverage?.takeIf { it > 0.0 },
                                    allowHardwareBitmaps = false,
                                    modifier          = Modifier
                                        .focusable()
                                        // unbounded = titlecard_focused halo (137dp) can overflow
                                        // the 112dp column without clipping — same as TvWideLazyRow
                                        .wrapContentSize(unbounded = true)
                                        .focusRequester(gridFocusRequesters[index])
                                        .onFocusChanged { fs ->
                                            if (fs.isFocused) {
                                                focusedGridIndex = index
                                                BlueHiveApplication.playHoverSound()
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
                                                    if (focusedGridIndex > 0) {
                                                        focusGridItem(focusedGridIndex - 1)  // removed scroll = false
                                                    }
                                                    true
                                                }

                                                Key.DirectionRight -> {
                                                    if (now - lastNavTime < navigationCooldownMs) return@onKeyEvent true
                                                    lastNavTime = now
                                                    if (focusedGridIndex < filteredEntries.lastIndex) {
                                                        focusGridItem(focusedGridIndex + 1)  // removed scroll = false
                                                    }
                                                    true
                                                }


                                                Key.DirectionUp -> {
                                                    if (now - lastNavTime < navigationCooldownMs) return@onKeyEvent true
                                                    lastNavTime = now
                                                    val target = focusedGridIndex - cardsPerRow
                                                    if (target >= 0) {
                                                        focusGridItem(target)
                                                    } else {
                                                        // First row → send focus back up to control bar
                                                        filterDropdownFocus.requestFocus()
                                                        BlueHiveApplication.playHoverSound()
                                                    }
                                                    true
                                                }

                                                Key.DirectionDown -> {
                                                    if (now - lastNavTime < navigationCooldownMs) return@onKeyEvent true
                                                    lastNavTime = now
                                                    val target = focusedGridIndex + cardsPerRow
                                                    if (target <= filteredEntries.lastIndex) {
                                                        focusGridItem(target)
                                                        BlueHiveApplication.playTitleCardNavigation()
                                                    } else {
                                                        // Target overshoots — check if there are items on the last row below current index
                                                        val lastRowStart =
                                                            (filteredEntries.lastIndex / cardsPerRow) * cardsPerRow
                                                        if (focusedGridIndex < lastRowStart) {
                                                            // We're not yet on the last row — land on the last item
                                                            focusGridItem(filteredEntries.lastIndex)
                                                            BlueHiveApplication.playTitleCardNavigation()
                                                        }
                                                        // Already on the last row — do nothing, no sound
                                                    }
                                                    true
                                                }

                                                Key.DirectionCenter,
                                                Key.Enter,
                                                Key.NumPadEnter -> {
                                                    isPressed = true
                                                    BlueHiveApplication.playClickSound()
                                                    if (removeMode) {
                                                        vanishController.vanish()
                                                        coroutineScope.launch {
                                                            delay(150)
                                                            // Pre-move focus to the neighboring card BEFORE
                                                            // removing the entry — prevents Compose from
                                                            // auto-searching up to the control bar.
                                                            val removeIdx = index
                                                            val nextIdx = when {
                                                                removeIdx < filteredEntries.lastIndex -> removeIdx + 1
                                                                removeIdx > 0 -> removeIdx - 1
                                                                else -> -1
                                                            }
                                                            if (nextIdx >= 0) {
                                                                gridFocusRequesters.getOrNull(nextIdx)?.requestFocus()
                                                                // After removal, items shift: if we moved forward
                                                                // the next item slides into removeIdx's slot
                                                                focusedGridIndex = if (nextIdx > removeIdx) removeIdx else nextIdx
                                                            }
                                                            kotlinx.coroutines.android.awaitFrame()
                                                            favEntries.remove(entry)
                                                            deletedEntries.add(entry)
                                                        }
                                                    } else {
                                                        navigateToDetails(entry)
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
                    }   // closes inner Box
                }       // closes VanishContainer

            if (showFilteredEmptyState) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = gridOffsetY)
                        .width(860.dp)
                        .height(gridHeight)
                ) {

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
                                text       = "No content found for this filter",
                                color      = Color.White,
                                fontFamily = AppTypography.interBold,
                                fontWeight = FontWeight.Bold,
                                fontSize   = 20.sp,
                                textAlign  = TextAlign.Center,
                                modifier   = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                            )

                            Text(
                                text       = "There are no favorites matching the current genre and media type selection.",
                                color      = Color(0xFFAAAAAA),
                                fontFamily = AppTypography.interSemiBold,
                                fontWeight = FontWeight.Normal,
                                fontSize   = 13.sp,
                                textAlign  = TextAlign.Center,
                                modifier   = Modifier
                                    .fillMaxWidth()
                                    .offset(y = 8.dp),
                            )

                            Text(
                                text       = "Try changing the filter or switching between Movies and TV Shows",
                                color      = Color(0xFFA01D1D),
                                fontFamily = AppTypography.interBold,
                                fontWeight = FontWeight.Normal,
                                fontSize   = 15.sp,
                                textAlign  = TextAlign.Center,
                                modifier   = Modifier
                                    .fillMaxWidth()
                                    .offset(y = 36.dp, x = 2.dp),
                            )
                        }
                    }
                }
            }

            // ── Bottom fade scrim — signals more content below ────────────────
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(65.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.80f))
                        )
                    )
            )

        }   // closes else if (!isLoading)

        // ── Remove mode confirmation overlay ──────────────────────────────────
        if (showExitRemoveOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .zIndex(2f)
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .onPreviewKeyEvent { event ->
                        // Trap all back presses while overlay is open
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                            true
                        } else false
                    },
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color(0xFF121213), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF3A3737), RoundedCornerShape(12.dp))
                        .padding(horizontal = 40.dp, vertical = 28.dp)
                        .width(520.dp),
                ) {
                    Text(
                        text       = "Exit Remove Mode",
                        color      = Color.White,
                        fontFamily = AppTypography.interBold,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 20.sp,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                    )
                    Text(
                        text       = "${deletedEntries.size} item${if (deletedEntries.size == 1) "" else "s"} removed. Do you wish to keep these changes?",
                        color      = Color(0xFFAAAAAA),
                        fontFamily = AppTypography.interSemiBold,
                        fontWeight = FontWeight.Normal,
                        fontSize   = 13.sp,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                    )

                    // ── Buttons row ───────────────────────────────────────
                    val overlayButtonWidth = 200.dp

                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(
                            16.dp, Alignment.CenterHorizontally
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // Accept Changes — blue 3D button
                        ModularButton(
                            textConfig      = ModularButtonTextConfig(text = "Accept Changes", fontSize = 10f),
                            isToggleable    = false,
                            isFocusable     = true,
                            fontFamily      = AppTypography.interBold,
                            focusRequester  = acceptChangesFocus,
                            onClick = {
                                BlueHiveApplication.playClickSound()
                                coroutineScope.launch {
                                    deletedEntries.forEach { entry ->
                                        try {
                                            ApiClient.bluehiveApi.toggleFavorite(
                                                com.example.bluehive.api.FavoriteRequest(
                                                    profile_id    = profileId,
                                                    media_tmdb_id = entry.tmdbId,
                                                    media_type    = entry.mediaType,
                                                    media_title   = entry.title,
                                                )
                                            )
                                        } catch (e: Exception) {
                                            android.util.Log.e("FavoritesScreen", "Remove commit failed: ${e.message}")
                                        }
                                    }
                                    deletedEntries.clear()
                                }
                                removeMode = false
                                coroutineScope.launch {
                                    kotlinx.coroutines.android.awaitFrame()
                                    kotlinx.coroutines.android.awaitFrame()
                                    if (filteredEntries.isNotEmpty()) {
                                        val target = focusedGridIndex.coerceIn(0, filteredEntries.lastIndex)
                                        focusedGridIndex = target
                                        gridState.scrollToItem(target)
                                        kotlinx.coroutines.android.awaitFrame()
                                        gridFocusRequesters.getOrNull(target)?.requestFocus()
                                    }
                                    showDeleteConfirm = false
                                }
                            },
                            modifier = Modifier
                                .onFocusChanged {
                                    if (it.isFocused) BlueHiveApplication.playHoverSound()
                                }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.DirectionRight -> {
                                                undoChangesFocus.requestFocus()
                                                true
                                            }
                                            Key.DirectionLeft, Key.DirectionUp, Key.DirectionDown -> true
                                            else -> false
                                        }
                                    } else false
                                },
                            dimensions = ModularButtonDimensions(
                                mainWidth          = overlayButtonWidth,
                                mainHeight         = 30.dp,
                                mainYOffset        = 7.dp,
                                secondWidth        = overlayButtonWidth,
                                secondHeight       = 12.dp,
                                secondYOffset      = 29.dp,
                                mainCornerRadius   = 8f,
                                secondCornerRadius = 8f,
                                shadowHeight       = 12.dp,
                                glowWidth          = overlayButtonWidth + 14.2.dp,
                                glowHeight         = 44.dp,
                            ),
                            colors = ModularButtonColors(
                                mainDefault          = Color(0xFF192C6F),
                                mainFocused          = Color(0xFF2A4BAA),
                                mainToggled          = Color(0xFF192C6F),
                                secondDefault        = Color(0xFF151C3A),
                                secondFocused        = Color(0xFF2E325F),
                                secondToggled        = Color(0xFF151C3A),
                                textFocused          = Color(0xFFDADADA),
                                textUnfocused        = Color(0xFFAAAAAA),
                                textUnfocusedToggled = Color(0xFFAAAAAA),
                                textToggledFocus     = Color(0xFFDADADA),
                            ),
                            glowConfig      = filterButtonGlowConfig(),
                            animationConfig = filterButtonAnimationConfig(),
                        )

                        // Undo Changes — red 3D button
                        ModularButton(
                            textConfig      = ModularButtonTextConfig(text = "Undo Changes", fontSize = 10f),
                            isToggleable    = false,
                            isFocusable     = true,
                            fontFamily      = AppTypography.interBold,
                            focusRequester  = undoChangesFocus,
                            onClick = {
                                BlueHiveApplication.playClickSound()
                                favEntries.addAll(deletedEntries)
                                deletedEntries.clear()
                                removeMode = false
                                coroutineScope.launch {
                                    kotlinx.coroutines.android.awaitFrame()
                                    kotlinx.coroutines.android.awaitFrame()
                                    if (filteredEntries.isNotEmpty()) {
                                        val target = focusedGridIndex.coerceIn(0, filteredEntries.lastIndex)
                                        focusedGridIndex = target
                                        gridState.scrollToItem(target)
                                        kotlinx.coroutines.android.awaitFrame()
                                        gridFocusRequesters.getOrNull(target)?.requestFocus()
                                    }
                                    showDeleteConfirm = false
                                }
                            },
                            modifier = Modifier
                                .onFocusChanged {
                                    if (it.isFocused) BlueHiveApplication.playHoverSound()
                                }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.DirectionLeft -> {
                                                acceptChangesFocus.requestFocus()
                                                true
                                            }
                                            Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> true
                                            else -> false
                                        }
                                    } else false
                                },
                            dimensions = ModularButtonDimensions(
                                mainWidth          = overlayButtonWidth,
                                mainHeight         = 30.dp,
                                mainYOffset        = 7.dp,
                                secondWidth        = overlayButtonWidth,
                                secondHeight       = 12.dp,
                                secondYOffset      = 29.dp,
                                mainCornerRadius   = 8f,
                                secondCornerRadius = 8f,
                                shadowHeight       = 12.dp,
                                glowWidth          = overlayButtonWidth + 14.2.dp,
                                glowHeight         = 44.dp,
                            ),
                            colors = ModularButtonColors(
                                mainDefault          = Color(0xFF6D2121),
                                mainFocused          = Color(0xFFAA2A2A),
                                mainToggled          = Color(0xFF6D2121),
                                secondDefault        = Color(0xFF3A1515),
                                secondFocused        = Color(0xFF783535),
                                secondToggled        = Color(0xFF3A1515),
                                textFocused          = Color(0xFFDADADA),
                                textUnfocused        = Color(0xFFAAAAAA),
                                textUnfocusedToggled = Color(0xFFAAAAAA),
                                textToggledFocus     = Color(0xFFDADADA),
                            ),
                            glowConfig      = filterButtonGlowConfig(),
                            animationConfig = filterButtonAnimationConfig(),
                        )
                    }
                }
            }
        }

    }   // closes main Box fillMaxSize

}   // closes FavoritesScreen


// ─────────────────────────────────────────────────────────────────────────────
//  Extension — MediaDetailResponse → FavoriteEntry
// ─────────────────────────────────────────────────────────────────────────────

private fun MediaDetailResponse.toFavoriteEntry(
    favoriteId: Int,
    addedAt:    String,
): FavoriteEntry {
    val parsedGenres: List<String> = try {
        val raw = genres as? String ?: ""
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("name", null) }
    } catch (_: Exception) { emptyList() }

    val genresStr = parsedGenres.joinToString(", ").ifBlank { "N/A" }
    val logoStr   = (logos as? String)?.takeIf { it.isNotBlank() } ?: ""

    return FavoriteEntry(
        favoriteId       = favoriteId,
        tmdbId           = tmdb_id,
        mediaType        = media_type,
        addedAt          = addedAt,
        title            = title ?: "Unknown",
        posterPath       = poster_path,
        backdropPath     = backdrop_path,
        releaseDate      = release_date,
        voteAverage      = vote_average,
        overview         = overview,
        contentRating    = content_rating,
        originalLanguage = original_language,
        numberOfSeasons  = number_of_seasons,
        runtime          = runtime,
        status           = status,
        trailerUrl       = youtube_trailer_url,
        logoUrl          = logoStr,
        genresList       = parsedGenres,
        genresString     = genresStr,
    )
}


// ─────────────────────────────────────────────────────────────────────────────
//  Shared button config helpers — keeps ModularButton call sites lean
// ─────────────────────────────────────────────────────────────────────────────

private fun filterButtonDimensions(buttonWidth: androidx.compose.ui.unit.Dp) =
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

private fun filterButtonColors(
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

private fun filterButtonGlowConfig() =
    ModularButtonGlowConfig(
        enabled               = true,
        defaultRes            = R.drawable.button_focus_wide_glow,
        offsetX               = (-6.85).dp,
        offsetY               = 7.dp,
        cornerRadius          = 100.dp,
        fadeOutDurationMillis = 200,
        fadeInDurationMillis  = 400,
    )

private fun filterButtonAnimationConfig() =
    ModularButtonAnimationConfig(
        pressOffset           = 3.5.dp,
        textOffsetDefault     = 7.9.dp,
        textOffsetPressed     = 9.9.dp,
        durationMillis        = 110,
        bounceBackDelayMillis = 200,
    )


// =========================================================== DO NOT DELETE ===============================================================
@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    widthDp = 960,
    heightDp = 540,
)
@Composable
fun FavoritesScreenPreview() {
    FavoritesScreen()
}
// =========================================================== DO NOT DELETE ===============================================================

