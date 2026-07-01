package com.example.bluehive.searchBarComponent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.SourceScreen
import com.example.bluehive.MoviesDetailsScreenCompose
import com.example.bluehive.R
import com.example.bluehive.TVShowsDetailsScreenCompose
import com.example.bluehive.api.ApiClient
import com.example.bluehive.models.MediaItem
import com.example.bluehive.utilities.AppTypography
import com.example.bluehive.utilities.TvWideLazyRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


private const val TAG = "SearchBar"

// ─────────────────────────────────────────────────────────────────────────────
//  Launch helper
// ─────────────────────────────────────────────────────────────────────────────

fun openSearchScreen(context: Context, profileId: Int = -1, initialQuery: String = "") {
    context.startActivity(
        Intent(context, SearchBarActivity::class.java)
            .putExtra("PROFILE_ID", profileId)
            .putExtra("INITIAL_QUERY", initialQuery)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Activity
// ─────────────────────────────────────────────────────────────────────────────

class SearchBarActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                BlueHiveApplication.playBackOutSound()
                finish()
            }
        })

        val profileId    = intent.getIntExtra("PROFILE_ID", -1)
        val initialQuery = intent.getStringExtra("INITIAL_QUERY") ?: ""

        setContent {
            SearchBarScreen(
                profileId    = profileId,
                initialQuery = initialQuery,
                onMediaClick = { media, query -> openDetailsScreen(media, profileId, query) }
            )
        }
    }

    private fun openDetailsScreen(media: MediaItem, profileId: Int, searchQuery: String = "") {
        val mediaType = media.mediaType
        Log.d(TAG, "Opening ${mediaType.uppercase()} details for: ${media.title}")

        val intent = when (mediaType.lowercase()) {
            "tv" -> Intent(this, TVShowsDetailsScreenCompose::class.java)
            else -> Intent(this, MoviesDetailsScreenCompose::class.java)
        }.apply {
            putExtra("PROFILE_ID",          profileId)
            putExtra("media_type",          mediaType)
            putExtra("media_id",            media.tmdbId)
            putExtra("media_title", media.title)
            putExtra("poster_url",          media.posterUrl ?: "")
            putExtra("backdrop_url",        media.backdropUrl ?: "")
            putExtra("youtube_trailer_url", media.trailerUrl ?: "")
            putExtra("overview",            media.overview ?: "")
            putExtra("vote_average",        media.voteAverage ?: 0.0)
            putExtra("contentRating",       media.contentRating ?: "N/A")
            putExtra("original_language",   media.originalLanguage ?: "N/A")
            putExtra("release_date",        media.releaseDate ?: "N/A")
            putExtra("logo_url",            media.logoUrl ?: "")
            putExtra("genres",              media.genres?.joinToString(", ") ?: "N/A")
            if (mediaType.lowercase() == "tv") {
                putExtra("number_of_seasons", media.numberOfSeasons ?: 0)
                putExtra("status",            media.status ?: "N/A")
            } else {
                putExtra("budget",  media.budget ?: 0L)
                putExtra("revenue", media.revenue ?: 0L)
            }
            putExtra("SOURCE_SCREEN", SourceScreen.SEARCH)
            putExtra("SEARCH_QUERY",  searchQuery)
        }
        startActivity(intent)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  UI State
// ─────────────────────────────────────────────────────────────────────────────

private sealed class SearchUiState {
    object Idle    : SearchUiState()
    object Loading : SearchUiState()
    data class Results(val movies: List<MediaItem>, val tvShows: List<MediaItem>) : SearchUiState()
    data class Empty(val query: String) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}

// ─────────────────────────────────────────────────────────────────────────────
//  Screen
// ─────────────────────────────────────────────────────────────────────────────

// ── Layout tuning ─────────────────────────────────────────────────────────────
// All key positions exposed here so you can tweak without hunting through the tree.
private val SEARCH_FIELD_OFFSET_Y  = 20.dp
private val SEARCH_FIELD_WIDTH     = 400.dp
private val SEARCH_FIELD_HEIGHT    = 38.dp
private val FIRST_ROW_OFFSET_Y     = 105.dp       // movies row top (or solo TV row top)
private val ROW_SECTION_HEIGHT     = 240.dp      // passed to TvWideLazyRow sectionHeight
private val ROW_VIEWPORT_HEIGHT    = 208.dp      // passed to TvWideLazyRow viewportHeight
private val ROW_GAP                = 0.dp        // vertical gap between the two rows
// Derived: second row starts immediately after first row + gap
private val SECOND_ROW_OFFSET_Y    = FIRST_ROW_OFFSET_Y + ROW_SECTION_HEIGHT + ROW_GAP


@Composable
fun SearchBarScreen(
    profileId:    Int    = -1,
    initialQuery: String = "",
    onMediaClick: (MediaItem, String) -> Unit = { _, _ -> }
) {
    // ── State ──────────────────────────────────────────────────────────────────
    var query           by remember { mutableStateOf(initialQuery) }
    var uiState         by remember {
        mutableStateOf(
            if (initialQuery.length >= 2) SearchUiState.Loading else SearchUiState.Idle
        )
    }
    var isNavigating    by remember { mutableStateOf(false) }
    var isSearchFocused by remember { mutableStateOf(false) }
    var voiceInputDetected by remember { mutableStateOf(initialQuery.length >= 2) }
    var lastSearchedQuery by remember { mutableStateOf<String?>(null) }
    val coroutineScope  = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Release search result bitmaps and reset state when the composable
    // leaves the tree — mirrors the DisposableEffect pattern in History/Favorites.
    DisposableEffect(Unit) {
        onDispose {
            uiState           = SearchUiState.Idle
            isNavigating      = false
            isSearchFocused   = false
            voiceInputDetected = false
            lastSearchedQuery = null
        }
    }

    // ── Focus requesters ───────────────────────────────────────────────────────
    // moviesRowFR / tvRowFR are passed as firstItemFocusRequester to TvWideLazyRow,
    // so calling .requestFocus() on them lands on the first card of that row.
    val searchFieldFR     = remember { FocusRequester() }
    val moviesRowFR       = remember { FocusRequester() }   // row-level (on focusGroup)
    val tvRowFR           = remember { FocusRequester() }   // row-level (on focusGroup)

    // ── Initial focus ──────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        awaitFrame()
        awaitFrame()
        searchFieldFR.requestFocus()
    }

    // ── Keyboard visibility ───────────────────────────────────────────────────
    // Keyboard opens on explicit press (Enter/Center), NOT on focus arrival.
    // This prevents the keyboard from popping up when D-padding back to the search bar.

    // ── Debounced search ───────────────────────────────────────────────────────
    // Only runs when voiceInputDetected is true — i.e. a multi-character
    // chunk arrived via the speech recognizer, or the screen launched with
    // an initial query. Manual typing leaves the flag false and the search
    // will instead be triggered explicitly via the IME search button below.
    // Extracted search runner — can be called from the debounced effect
// OR from an explicit submit (IME search button). Both paths end in
// the same API calls and state update.
    suspend fun runSearch(q: String) {
        if (q.length < 2) {
            uiState = SearchUiState.Idle
            return
        }
        // Dedupe: skip if we just ran this exact (normalized) query. Catches
        // the voice recognizer's release-final, which often differs from the
        // last partial only in trailing punctuation / casing / whitespace.
        val normalized = q.trim().lowercase().trimEnd('.', ',', '!', '?')
        val lastNormalized = lastSearchedQuery?.trim()?.lowercase()?.trimEnd('.', ',', '!', '?')
        if (lastNormalized != null && normalized == lastNormalized) {
            Log.d(TAG, "runSearch skipped — duplicate of last search: '$q'")
            return
        }
        lastSearchedQuery = q
        uiState = SearchUiState.Loading
        try {
            val results = coroutineScope {
                listOf(
                    async { ApiClient.trailerApi.searchMedia(q, "movie") },
                    async { ApiClient.trailerApi.searchMedia(q, "tv")    }
                ).awaitAll()
            }
            val movies  = results[0].items
            val tvShows = results[1].items
            Log.d(TAG, "Search '$q' → ${movies.size} movies, ${tvShows.size} TV shows")
            uiState = if (movies.isEmpty() && tvShows.isEmpty()) {
                SearchUiState.Empty(q)
            } else {
                SearchUiState.Results(movies, tvShows)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for '$q': ${e.message}")
            uiState = SearchUiState.Error(e.message ?: "Search failed. Check your connection.")
        }
    }

    // Debounced search (voice path) — only runs when voiceInputDetected is true.
    // Manual typing leaves the flag false; typed users must press the IME search
    // button, which calls runSearch directly.
    LaunchedEffect(query) {
        if (query.length < 2) {
            if (uiState !is SearchUiState.Results) uiState = SearchUiState.Idle
            return@LaunchedEffect
        }
        if (!voiceInputDetected) return@LaunchedEffect
        delay(600)
        runSearch(query)
    }

    // ── Root layout ───────────────────────────────────────────────────────────
    SearchBarContent(
        uiState             = uiState,
        query               = query,
        isSearchFocused     = isSearchFocused,
        searchFieldFR       = searchFieldFR,
        moviesRowFR         = moviesRowFR,
        tvRowFR             = tvRowFR,
        coroutineScope      = coroutineScope,
        isNavigating        = isNavigating,
        onNavigatingChanged = { isNavigating = it },
        onQueryChanged      = { newText ->
            val delta = newText.length - query.length
            Log.d(TAG, "onQueryChanged: old='$query' new='$newText' delta=$delta voice=$voiceInputDetected")
            when {
                delta >= 4 -> voiceInputDetected = true
                delta == 1 || delta == -1 -> {
                    voiceInputDetected = false
                    lastSearchedQuery = null  // new search in progress
                }
            }
            // Full clears (Select-press) also reset the dedupe so a legitimate
            // re-search of the same term after reopening the keyboard still works.
            if (newText.isEmpty()) lastSearchedQuery = null
            query = newText
        },
        onFocusChanged      = { isSearchFocused = it },
        onSubmitTyped       = {
            val q = query.trim()
            if (q.length >= 2) {
                coroutineScope.launch { runSearch(q) }
            }
        },
        onMediaClick        = { media -> onMediaClick(media, query) },
        keyboardController  = keyboardController
    )
}


// ─────────────────────────────────────────────────────────────────────────────
//  SearchBarContent — pure UI, no API calls, previewable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SearchBarContent(
    uiState:             SearchUiState,
    query:               String,
    isSearchFocused:     Boolean,
    searchFieldFR:       FocusRequester,
    moviesRowFR:         FocusRequester,
    tvRowFR:             FocusRequester,
    coroutineScope:      CoroutineScope,
    isNavigating:        Boolean,
    onNavigatingChanged: (Boolean) -> Unit,
    onQueryChanged:      (String) -> Unit,
    onFocusChanged:      (Boolean) -> Unit,
    onSubmitTyped:       () -> Unit = {},
    onMediaClick:        (MediaItem) -> Unit,
    keyboardController: SoftwareKeyboardController?,
) {
    Box(Modifier.fillMaxSize()) {

        Image(
            painter            = painterResource(R.drawable.home_screen),
            contentDescription = null,
            modifier           = Modifier.fillMaxSize(),
            contentScale       = ContentScale.Crop
        )
        Box(Modifier.fillMaxSize().background(Color(0xAA000000)))  // 0xAA000000

        // ── Search field ───────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter).offset(x = 18.dp)
                .offset( y = SEARCH_FIELD_OFFSET_Y)
        ) {
            Image(
                painter            = painterResource(R.drawable.button_focus_wide_glow),
                contentDescription = null,
                modifier           = Modifier
                    .offset(x = (-22.5).dp, y = (-12.6).dp)
                    .width(SEARCH_FIELD_WIDTH + 45.dp)
                    .size(width = SEARCH_FIELD_WIDTH + 36.dp, height = SEARCH_FIELD_HEIGHT + 25.dp)
                    .graphicsLayer(alpha = if (isSearchFocused) 1f else 0f),
                contentScale = ContentScale.FillBounds
            )

            Box(modifier = Modifier.size(width = SEARCH_FIELD_WIDTH, height = SEARCH_FIELD_HEIGHT)) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            color = if (isSearchFocused) Color(0xFF2A2A3A) else Color(0xFF1A1A24),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                        )
                        .border(
                            width = if (isSearchFocused) 2.5.dp else 1.dp,
                            color = if (isSearchFocused) Color.White else Color(0xFF2A2A3A),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                        )
                )
                Image(
                    painter            = painterResource(R.drawable.magnifying_glass_raw_image),
                    contentDescription = null,
                    modifier           = Modifier
                        .size(20.dp)
                        .offset(x = 10.dp, y = 9.dp)
                        .graphicsLayer(alpha = 0.5f)
                )

                val downTarget: FocusRequester? = when (val s = uiState) {
                    is SearchUiState.Results ->
                        if (s.movies.isNotEmpty()) moviesRowFR else tvRowFR
                    else -> null
                }

                // Inner text field FR — only focused on Enter/Center press to trigger keyboard
                val textFieldFR = remember { FocusRequester() }
                var isTextFieldActive by remember { mutableStateOf(false) }

                // Invisible focusable box: receives D-pad focus WITHOUT opening keyboard
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 42.dp)
                        .width(SEARCH_FIELD_WIDTH - 60.dp)
                        .focusRequester(searchFieldFR)
                        .onFocusChanged { fs ->
                            // If the wrapper loses focus entirely (not to the text field),
                            // deactivate the text field so keyboard doesn't reappear on re-entry
                            if (!fs.hasFocus && !fs.isFocused) {
                                isTextFieldActive = false
                            }
                            onFocusChanged(fs.isFocused || fs.hasFocus)
                        }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    keyboardController?.hide()
                                    isTextFieldActive = false
                                    val target = downTarget
                                    if (target != null) {
                                        BlueHiveApplication.playHoverSound()
                                        target.requestFocus()
                                        true
                                    } else false
                                }
                                Key.DirectionUp -> true
                                Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                                    if (!isTextFieldActive) {
                                        // First press: activate text field → opens keyboard.
                                        // Clear any existing query so voice input fills a blank
                                        // field (prevents "star wars" + voice → "star warsthe...").
                                        if (query.isNotEmpty()) onQueryChanged("")
                                        isTextFieldActive = true
                                        textFieldFR.requestFocus()
                                    }
                                    true
                                }
                                Key.Back -> {
                                    if (isTextFieldActive) {
                                        // Back while typing: close keyboard, return to wrapper
                                        keyboardController?.hide()
                                        isTextFieldActive = false
                                        searchFieldFR.requestFocus()
                                        true
                                    } else false  // let it bubble to activity back handler
                                }
                                else -> {
                                    // Any other key (letter keys from physical/virtual keyboard)
                                    // while wrapper is focused — activate text field
                                    if (!isTextFieldActive) {
                                        isTextFieldActive = true
                                        textFieldFR.requestFocus()
                                    }
                                    false  // let the key pass through to the text field
                                }
                            }
                        }
                ) {
                    BasicTextField(
                        value         = query,
                        onValueChange = onQueryChanged,
                        singleLine    = true,
                        cursorBrush   = SolidColor(Color.White),
                        textStyle     = TextStyle(
                            color      = Color.White,
                            fontSize   = 30.sp,
                            fontFamily = AppTypography.dongleRegular
                        ),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = {
                                // User explicitly pressed the IME search button — fire the
                                // search immediately by arming the voice flag, which unblocks
                                // the debounced LaunchedEffect(query).
                                onSubmitTyped()
                                keyboardController?.hide()
                                isTextFieldActive = false
                                searchFieldFR.requestFocus()
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(textFieldFR)
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        keyboardController?.hide()
                                        isTextFieldActive = false
                                        val target = downTarget
                                        if (target != null) {
                                            BlueHiveApplication.playHoverSound()
                                            target.requestFocus()
                                            true
                                        } else false
                                    }
                                    Key.DirectionUp -> {
                                        // Close keyboard and go back to wrapper
                                        keyboardController?.hide()
                                        isTextFieldActive = false
                                        searchFieldFR.requestFocus()
                                        true
                                    }
                                    Key.Back -> {
                                        keyboardController?.hide()
                                        isTextFieldActive = false
                                        searchFieldFR.requestFocus()
                                        true
                                    }
                                    else -> false
                                }
                            }
                    )
                }

                if (query.isEmpty() && !isSearchFocused) {
                    Text(
                        text       = "Search movies, shows, cartoons or anime...",
                        color      = Color(0xFF656565),
                        fontSize   = 25.sp,
                        fontFamily = AppTypography.dongleRegular,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 47.dp, top = 3.dp)
                    )
                }
            }
        }
        // ── /Search field ──────────────────────────────────────────────────────


        // ── Content ────────────────────────────────────────────────────────────
        when (uiState) {
            is SearchUiState.Loading -> {
                CircularProgressIndicator(
                    modifier    = Modifier.align(Alignment.Center).offset(y = 40.dp).size(32.dp),
                    color       = Color(0xFF4C63AC),
                    strokeWidth = 3.dp
                )
                Text(
                    text       = "Searching…",
                    color      = Color(0xFFAAAAAA),
                    fontSize   = 14.sp,
                    fontFamily = AppTypography.dongleRegular,
                    modifier   = Modifier.align(Alignment.Center).offset(y = 85.dp)
                )
            }

            is SearchUiState.Results -> {
                val hasMovies = uiState.movies.isNotEmpty()
                val hasTv     = uiState.tvShows.isNotEmpty()

                // ── MOVIES ROW ─────────────────────────────────────────────────
                // The Box wrapper is the key pattern here:
                //   TvWideLazyRow never consumes DirectionUp / DirectionDown
                //   (its card onKeyEvent falls through to `else -> false`).
                //   Those events therefore bubble up to this Box's onKeyEvent,
                //   where we explicitly route to the correct neighbor.
                //   This is reliable regardless of screen layout method (Column,
                //   Box+offset, etc.) — no dependence on spatial focus traversal.
                if (hasMovies) {
                    Box(
                        contentAlignment = Alignment.TopCenter,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter).offset(x = 18.dp)
                            .offset(y = FIRST_ROW_OFFSET_Y)
                    ) {
                        TvWideLazyRow(
                            title                  = "MOVIES",
                            items                  = uiState.movies,
                            onItemClick            = { media ->
                                if (!isNavigating) {
                                    onNavigatingChanged(true)
                                    coroutineScope.launch {
                                        delay(300)
                                        onNavigatingChanged(false)
                                        onMediaClick(media)
                                    }
                                }
                            },
                            rowFocusRequester       = moviesRowFR,
                            sectionHeight          = ROW_SECTION_HEIGHT,
                            viewportHeight         = ROW_VIEWPORT_HEIGHT,
                            onNavigateUp           = {
                                keyboardController?.hide()
                                searchFieldFR.requestFocus()
                            },
                            onNavigateDown         = if (hasTv) {
                                { tvRowFR.requestFocus() }
                            } else null,
                        )
                    }
                }

                // ── TV SHOWS ROW ───────────────────────────────────────────────
                if (hasTv) {
                    val tvOffsetY = if (hasMovies) SECOND_ROW_OFFSET_Y else FIRST_ROW_OFFSET_Y

                    Box(
                        contentAlignment = Alignment.TopCenter,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter).offset(x = 18.dp)
                            .offset(y = tvOffsetY)
                    ) {
                        TvWideLazyRow(
                            title                  = "TV SHOWS",
                            items                  = uiState.tvShows,
                            onItemClick            = { media ->
                                if (!isNavigating) {
                                    onNavigatingChanged(true)
                                    coroutineScope.launch {
                                        delay(300)
                                        onNavigatingChanged(false)
                                        onMediaClick(media)
                                    }
                                }
                            },
                            rowFocusRequester       = tvRowFR,
                            sectionHeight          = ROW_SECTION_HEIGHT,
                            viewportHeight         = ROW_VIEWPORT_HEIGHT,
                            onNavigateUp           = {
                                if (hasMovies) moviesRowFR.requestFocus()
                                else {
                                    keyboardController?.hide()
                                    searchFieldFR.requestFocus()
                                }
                            },
                            onNavigateDown         = null,
                        )
                    }
                }
            }

            is SearchUiState.Empty -> {
                Column(
                    modifier            = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(
                        text       = "No results found for \"${uiState.query}\"",
                        color      = Color.White,
                        fontSize   = 40.sp,
                        fontFamily = AppTypography.lalezarRegular,
                        textAlign  = TextAlign.Center
                    )
                    Text(
                        text       = "This title isn't in our library.\nTry a different name or check your spelling.",
                        color      = Color(0xFFAAAAAA),
                        fontSize   = 30.sp,
                        fontFamily = AppTypography.dongleRegular,
                        textAlign  = TextAlign.Center,
                        lineHeight = 26.sp
                    )
                }
            }

            is SearchUiState.Error -> {
                Column(
                    modifier            = Modifier.align(Alignment.Center).offset(y = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text       = "Something went wrong",
                        color      = Color(0xFFFF6B6B),
                        fontSize   = 18.sp,
                        fontFamily = AppTypography.lalezarRegular
                    )
                    Text(
                        text       = uiState.message,
                        color      = Color(0xFF888888),
                        fontSize   = 13.sp,
                        fontFamily = AppTypography.dongleRegular,
                        textAlign  = TextAlign.Center
                    )
                }
            }

            else -> {
                Text(
                    text       = "Enter the name of the content that\nyou wish to search for",
                    color      = Color.White,
                    fontSize   = 40.sp,
                    fontFamily = AppTypography.lalezarRegular,
                    modifier   = Modifier.align(Alignment.Center),
                    textAlign  = TextAlign.Center,
                    lineHeight = 38.sp
                )
            }
        }
    }
}










// ─────────────────────────────────────────────────────────────────────────────
//  Preview mock data
// ─────────────────────────────────────────────────────────────────────────────

private fun mockMovie(id: Int, title: String) = MediaItem(
    tmdbId           = id,
    mediaId          = id,
    title            = title,
    mediaType        = "movie",
    posterUrl        = null,
    backdropUrl      = null,
    logoUrl          = null,
    overview         = "A great film.",
    releaseDate      = "2024-06-15",
    status           = "Released",
    voteAverage      = 7.8,
    voteCount        = 1200,
    popularity       = 95.0,
    popularityRank   = null,
    originalLanguage = "en",
    numberOfSeasons  = null,
    numberOfEpisodes = null,
    contentRating    = "PG-13",
    runtime          = 112,
    budget           = 50_000_000L,
    revenue          = 180_000_000L,
    trailerUrl       = null,
    genres           = listOf("Action", "Adventure"),
    similarItems     = null,
    whereToWatch     = null,
)

private fun mockTvShow(id: Int, title: String) = MediaItem(
    tmdbId           = id,
    mediaId          = id,
    title            = title,
    mediaType        = "tv",
    posterUrl        = null,
    backdropUrl      = null,
    logoUrl          = null,
    overview         = "A gripping series.",
    releaseDate      = "2022-09-01",
    status           = "Returning Series",
    voteAverage      = 8.3,
    voteCount        = 3400,
    popularity       = 120.0,
    popularityRank   = null,
    originalLanguage = "en",
    numberOfSeasons  = 3,
    numberOfEpisodes = 30,
    contentRating    = "TV-14",
    runtime          = null,
    budget           = null,
    revenue          = null,
    trailerUrl       = null,
    genres           = listOf("Drama", "Mystery"),
    similarItems     = null,
    whereToWatch     = null,
)

private val previewMovies = listOf(
    mockMovie(1001, "Scooby-Doo"),
    mockMovie(1002, "Scooby-Doo 2: Monsters Unleashed"),
    mockMovie(1003, "Happy Halloween, Scooby-Doo!"),
    mockMovie(1004, "Scooby-Doo! The Movie"),
    mockMovie(1005, "Aloha, Scooby-Doo!"),
    mockMovie(1006, "Scooby-Doo! and the Alien Invaders"),
)

private val previewTvShows = listOf(
    mockTvShow(2001, "Scooby-Doo, Where Are You!"),
    mockTvShow(2002, "A Pup Named Scooby-Doo"),
    mockTvShow(2003, "What's New, Scooby-Doo?"),
    mockTvShow(2004, "Scooby-Doo Mystery Incorporated"),
    mockTvShow(2005, "Be Cool, Scooby-Doo!"),
    mockTvShow(2006, "The 13 Ghosts of Scooby-Doo"),
)


// ─────────────────────────────────────────────────────────────────────────────
//  Previews
// ─────────────────────────────────────────────────────────────────────────────

//@Preview(
//    showBackground = true,
//    widthDp = 960,
//    heightDp = 540,
//    name = "Results — both rows"
//)
//@Composable
//private fun PreviewResultsBoth() {
//    val fr = remember { FocusRequester() }
//    val keyboardController = null
//    SearchBarContent(
//        uiState             = SearchUiState.Results(previewMovies, previewTvShows),
//        query               = "Scooby",
//        isSearchFocused     = true,
//        searchFieldFR       = fr,
//        moviesRowFR         = fr,
//        tvRowFR             = fr,
//        coroutineScope      = rememberCoroutineScope(),
//        isNavigating        = false,
//        onNavigatingChanged = {},
//        onQueryChanged      = {},
//        onFocusChanged      = {},
//        onMediaClick        = { _, _ -> },
//        keyboardController  = keyboardController,
//    )
//}
//
//
//
//@Preview(
//    showBackground = true,
//    widthDp = 960,
//    heightDp = 540,
//    name = "Empty state"
//)
//@Composable
//private fun PreviewEmpty() {
//    val fr = remember { FocusRequester() }
//    val keyboardController = null
//    SearchBarContent(
//        uiState = SearchUiState.Empty("zzzzzz"),
//        query = "zzzzzz",
//        isSearchFocused = false,
//        searchFieldFR = fr,
//        moviesRowFR = fr,
//        tvRowFR = fr,
//        coroutineScope = rememberCoroutineScope(),
//        isNavigating = false,
//        onNavigatingChanged = {},
//        onQueryChanged = {},
//        onFocusChanged = {},
//        onMediaClick = {},
//        keyboardController = keyboardController,
//    )
//}
//
//@Preview(
//    showBackground = true,
//    widthDp = 960,
//    heightDp = 540,
//    name = "Idle state"
//)
//@Composable
//private fun PreviewIdle() {
//    val fr = remember { FocusRequester() }
//    val keyboardController = null
//    SearchBarContent(
//        uiState = SearchUiState.Idle,
//        query = "",
//        isSearchFocused = true,
//        searchFieldFR = fr,
//        moviesRowFR = fr,
//        tvRowFR = fr,
//        coroutineScope = rememberCoroutineScope(),
//        isNavigating = false,
//        onNavigatingChanged = {},
//        onQueryChanged = {},
//        onFocusChanged = {},
//        onMediaClick = {},
//        keyboardController = keyboardController,
//    )
//}
//
//@Preview(
//    showBackground = true,
//    widthDp = 960,
//    heightDp = 540,
//    name = "Loading state"
//)
//@Composable
//private fun PreviewLoading() {
//    val fr = remember { FocusRequester() }
//    val keyboardController = null
//    SearchBarContent(
//        uiState = SearchUiState.Loading,
//        query = "Scooby",
//        isSearchFocused = true,
//        searchFieldFR = fr,
//        moviesRowFR = fr,
//        tvRowFR = fr,
//        coroutineScope = rememberCoroutineScope(),
//        isNavigating = false,
//        onNavigatingChanged = {},
//        onQueryChanged = {},
//        onFocusChanged = {},
//        onMediaClick = {},
//        keyboardController = keyboardController,
//    )
//}
//
//@Preview(
//    showBackground = true,
//    widthDp = 960,
//    heightDp = 540,
//    name = "Error state"
//)
//@Composable
//private fun PreviewError() {
//    val fr = remember { FocusRequester() }
//    val keyboardController = null
//    SearchBarContent(
//        uiState = SearchUiState.Error("Unable to reach server. Check your connection."),
//        query = "Scooby",
//        isSearchFocused = true,
//        searchFieldFR = fr,
//        moviesRowFR = fr,
//        tvRowFR = fr,
//        coroutineScope = rememberCoroutineScope(),
//        isNavigating = false,
//        onNavigatingChanged = {},
//        onQueryChanged = {},
//        onFocusChanged = {},
//        onMediaClick = {},
//        keyboardController = keyboardController,
//    )
//}