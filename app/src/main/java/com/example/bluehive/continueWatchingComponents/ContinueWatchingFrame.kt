package com.example.bluehive.continueWatchingComponents

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.R
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.WatchHistoryResponse
import com.example.bluehive.utilities.AppTypography
import android.content.Intent
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.bluehive.MoviesDetailsScreenCompose
import com.example.bluehive.TVShowsDetailsScreenCompose
import com.example.bluehive.repository.RecommendationsRepository
import com.example.bluehive.repository.EpisodesRepository
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner




// ✅ Three-state pattern (matches LatestReleasedFrameState)
sealed class ContinueWatchingFrameState {
    data object UnFocused : ContinueWatchingFrameState()
    data object Focused : ContinueWatchingFrameState()
    data object Selected : ContinueWatchingFrameState()
}

// ── Item-level constants ──────────────────────────────────────────────────────
private val CW_ITEM_HEIGHT      = 33.25.dp
private val CW_ITEM_SPACING     = 1.5.dp
private val CW_THUMB_WIDTH      = 58.dp
private val CW_ROW_SHAPE        = RoundedCornerShape(0.dp)
private val CW_THUMB_SHAPE      = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp)
private val CW_BG_COLOR         = Color(0xFF131313) // 0xFF121314
private val CW_BORDER_UNFOCUSED = Color(0xFF262525)
private val CW_BORDER_FOCUSED   = Color.White
private val CW_TITLE_FOCUSED    = Color.White
private val CW_TITLE_DEFAULT    = Color(0xFFBBBBBB)
private val CW_SUBTITLE_COLOR   = Color(0xFF777777)
private const val CW_MAX_ITEMS  = 15

// ── Single item row ───────────────────────────────────────────────────────────
@Composable
private fun ContinueWatchingItem(
    item: WatchHistoryResponse,
    focusRequester: FocusRequester,
    isFirst: Boolean,
    isLast: Boolean,
    frameFocusRequester: FocusRequester,
    downNeighbor: FocusRequester?,
    onItemClick: () -> Unit,
    onFocused: () -> Unit,
) {
    val context = LocalContext.current
    var isFocused by remember { mutableStateOf(false) }

    val subtitle = remember(item) {
        if (item.media_type == "tv" && item.season_number != null && item.episode_number != null)
            "Season: ${item.season_number}, Episode: ${item.episode_number}"
        else ""
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CW_ITEM_HEIGHT)
            .clip(CW_ROW_SHAPE)
            .background(CW_BG_COLOR)
            .border(
                width = if (isFocused) 0.8.dp else 1.dp,
                color = if (isFocused) CW_BORDER_FOCUSED else CW_BORDER_UNFOCUSED,
                shape = CW_ROW_SHAPE,
            )
            .focusRequester(focusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusProperties {
                if (isFirst) up = FocusRequester.Cancel  // blocks UP traversal at the boundary
                if (isLast) down = downNeighbor ?: FocusRequester.Cancel
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            BlueHiveApplication.playClickSound()
                            onItemClick()
                            true
                        }
                        Key.Back -> {
                            BlueHiveApplication.playBackOutSound()
                            frameFocusRequester.requestFocus()
                            true
                        }
                        Key.DirectionUp -> {
                            if (!isFirst) BlueHiveApplication.playHoverSound()
                            false
                        }
                        Key.DirectionDown -> {
                            if (!isLast) BlueHiveApplication.playHoverSound()
                            false
                        }
                        Key.DirectionLeft, Key.DirectionRight -> {
                            true // consume — no sideways escape from inside the list
                        }
                        else -> false
                    }
                } else false
            }
            .focusable()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            // ── Thumbnail ─────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .width(CW_THUMB_WIDTH)
                    .fillMaxHeight()
                    .clip(CW_THUMB_SHAPE)
            ) {
                if (!item.image_url.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(item.image_url)
                            .size(166, 94)
                            .allowRgb565(true)
                            .allowHardware(true)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .memoryCacheKey("cw_thumb_${item.media_tmdb_id}_${item.media_type}")
                            .crossfade(150)
                            .build(),
                        contentDescription = item.media_title,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize(),
                    )
                } else {
                    // Fallback when no image available
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1A1A2E))
                    )
                }
            }

            // ── Title + subtitle ──────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .padding(start = 6.dp, end = 4.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text       = item.media_title ?: "",
                    color      = if (isFocused) CW_TITLE_FOCUSED else CW_TITLE_DEFAULT,
                    fontSize   = 10.sp,
                    fontFamily = AppTypography.passionRegular,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text       = subtitle,
                        color      = CW_SUBTITLE_COLOR,
                        fontSize   = 7.5.sp,
                        fontFamily = AppTypography.passionRegular,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

// ── Main composable ───────────────────────────────────────────────────────────
@Composable
fun HomeScreenContinueWatchingCompose(
    profileId: Int,
    focusRequester: FocusRequester,
    upNeighbor: FocusRequester,
    downNeighbor: FocusRequester? = null,
    onNavigateLeft: () -> Unit = {},
    onNavigateDown: () -> Unit = {},
) {
    // ── Data loading ──────────────────────────────────────────────────────────
    var historyItems by remember { mutableStateOf<List<WatchHistoryResponse>>(emptyList()) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isNavigating by remember { mutableStateOf(false) }

    // Bump a counter every ON_RESUME so the fetch re-fires when the user
    // returns to home from media details / web viewer / wherever.
    var refreshKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                Log.d("CONTINUE_WATCHING", "🔄 ON_RESUME → refreshKey++")
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(profileId, refreshKey) {
        if (profileId == -1) return@LaunchedEffect

        // Item 5 — only the FIRST load (refreshKey == 0) can use the warm
        // prefetch, and only when it was warmed for THIS profile. Every
        // ON_RESUME refresh after that always fetches live so returning from
        // a details screen shows up-to-date progress.
        val app = context.applicationContext as? BlueHiveApplication
        val prefetched = if (refreshKey == 0) app?.consumeContinueWatchingPrefetch(profileId) else null

        if (prefetched != null) {
            Log.d("CONTINUE_WATCHING", "⚡ Using ${prefetched.size} prefetched items for profile $profileId")
            historyItems = prefetched
            return@LaunchedEffect
        }

        try {
            val fetched = ApiClient.bluehiveApi.getWatchHistory(
                profileId = profileId,
                limit     = CW_MAX_ITEMS,
                offset    = 0,
            )
            Log.d("CONTINUE_WATCHING", "✅ Fetched ${fetched.size} items (key=$refreshKey)")
            historyItems = fetched
        } catch (e: Exception) {
            Log.e("CONTINUE_WATCHING", "Failed to load: ${e.message}")
        }
    }

    // ── Focus tracking for the outer frame ───────────────────────────────────
    val frameInteraction = remember { MutableInteractionSource() }
    val isFrameFocused by frameInteraction.collectIsFocusedAsState()

    // ✅ Single source of truth state
    var frameState by remember { mutableStateOf<ContinueWatchingFrameState>(ContinueWatchingFrameState.UnFocused) }

    val itemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    fun requesterFor(id: Int): FocusRequester =
        itemFocusRequesters.getOrPut(id) { FocusRequester() }

    // Evict stale entries whenever historyItems changes so the map only
    // contains IDs that are currently rendered in the LazyColumn.
    LaunchedEffect(historyItems) {
        val currentIds = historyItems.map { it.id }.toSet()
        itemFocusRequesters.keys.retainAll(currentIds)
    }

    var focusedItemId by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()

    // Sync state with actual focus (but don't auto-exit Selected unless focus truly leaves)
    LaunchedEffect(isFrameFocused) {
        frameState = if (!isFrameFocused) {
            ContinueWatchingFrameState.UnFocused
        } else {
            ContinueWatchingFrameState.Focused  // always Focused when frame regains focus
        }
    }

    // When the activity pauses (user opens details / web viewer), demote
    // Selected → UnFocused. Otherwise frameState desyncs from Android focus
    // on return: state says we're inside the list, but no list item has focus,
    // so the navigation guards block re-entry.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE &&
                frameState is ContinueWatchingFrameState.Selected
            ) {
                Log.d("CONTINUE_WATCHING", "⏸️ ON_PAUSE while Selected → demoting to UnFocused")
                frameState = ContinueWatchingFrameState.UnFocused
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }


    // When entering Selected → hand focus to the target list row.
    //
    // requestFocus() silently no-ops (logging "FocusRequester is not
    // initialized") when the target row's node isn't attached/placed yet.
    // That happens right after an ON_RESUME refetch or when returning from
    // another Activity (search / details from history), because the LazyColumn
    // re-attaches its per-row requesters on a *later* frame than the one the
    // CENTER/click lands on. A single awaitFrame() wasn't enough, so focus was
    // dropped and the Selected nav-lock then trapped all D-pad input.
    //
    // Fix: scroll the target into view, then retry requestFocus across several
    // frames, stopping as soon as focus actually leaves the frame (a row took
    // it). If it never lands, fall back to Focused so the user is never stuck.
    LaunchedEffect(frameState) {
        if (frameState is ContinueWatchingFrameState.Selected && historyItems.isNotEmpty()) {
            val targetId = focusedItemId
                ?.takeIf { id -> historyItems.any { it.id == id } }
                ?: historyItems.first().id
            val targetIndex = historyItems
                .indexOfFirst { it.id == targetId }
                .coerceAtLeast(0)

            // Guarantee the target row is composed & placed before focusing it.
            listState.scrollToItem(targetIndex)

            var attempts = 0
            while (attempts < 12 && frameState is ContinueWatchingFrameState.Selected) {
                kotlinx.coroutines.android.awaitFrame()
                requesterFor(targetId).requestFocus()
                kotlinx.coroutines.android.awaitFrame()
                if (!isFrameFocused) break  // a row took focus — done
                attempts++
            }

            // Never leave the user trapped in the nav-lock with nothing focused.
            if (attempts >= 12 && frameState is ContinueWatchingFrameState.Selected) {
                Log.w("CONTINUE_WATCHING", "⚠️ Could not focus a row after retries — reverting to Focused")
                frameState = ContinueWatchingFrameState.Focused
                focusRequester.requestFocus()
            }
        }
    }

    // Back exits Selected -> Focused (shadow returns, focus back to frame)
    BackHandler(enabled = frameState is ContinueWatchingFrameState.Selected) {
        Log.d("CONTINUE_WATCHING", "⬅️ Back pressed - exiting Selected")
        frameState = ContinueWatchingFrameState.Focused
        focusRequester.requestFocus()
    }

    // ── Layer specs ───────────────────────────────────────────────────────────
    val baseW = 212.75.dp
    val baseH = 226.dp
    val baseX = 29.dp
    val baseY = (-22).dp
    val baseZ = 2f // Front layer

    val focusedW = 245.6.dp
    val focusedH = 238.dp
    val focusedX = 13.dp
    val focusedY = (-18).dp
    val focusedZ = 0f // Behind shadow

    val shadowW = 243.6.dp
    val shadowH = 229.dp
    val shadowX = 14.dp
    val shadowY = (-13).dp
    val shadowZ = 1f // Between focused and base

    // List positioning — sits inside the base frame area with a few dp inner padding
    // Tweak listOffsetX / listOffsetY if your frame PNG has a thicker inner border
    val listOffsetX = 43.5.dp
    val listOffsetY = (15).dp
    val listWidth   = 183.75.dp
    val listHeight  = 169.dp

    Box(
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (frameState !is ContinueWatchingFrameState.Selected) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft, Key.DirectionRight -> true
                Key.DirectionUp -> {
                    focusedItemId == historyItems.firstOrNull()?.id
                }
                else -> false
            }
        }
    ) {
        // ✅ FOCUSED OVERLAY: visible when Focused OR Selected
        Image(
            painter = painterResource(id = R.drawable.continue_watching_frame_focused),
            contentDescription = "Continue Watching Focused",
            modifier = Modifier
                .offset(x = focusedX, y = focusedY)
                .width(focusedW)
                .height(focusedH)
                .zIndex(focusedZ)
                .graphicsLayer(alpha = if (frameState is ContinueWatchingFrameState.Focused) 1f else 0f),
            contentScale = ContentScale.FillBounds
        )

        // ✅ SHADOW: visible ONLY when UnFocused OR Focused (disappears when Selected)
        Image(
            painter = painterResource(id = R.drawable.continue_watching_frame_shadow),
            contentDescription = "Continue Watching Shadow",
            modifier = Modifier
                .offset(x = shadowX, y = shadowY)
                .width(shadowW)
                .height(shadowH)
                .zIndex(shadowZ)
                .graphicsLayer(alpha = if (frameState is ContinueWatchingFrameState.Selected) 0f else 1f),
            contentScale = ContentScale.FillBounds
        )

        // ✅ BASE FRAME: always visible (front layer, focusable + clickable target)
        Image(
            painter = painterResource(id = R.drawable.continue_watching_frame),
            contentDescription = "Continue Watching",
            modifier = Modifier
                .offset(x = baseX, y = baseY)
                .width(baseW)
                .height(baseH)
                .zIndex(baseZ)
                .focusRequester(focusRequester)
                .focusProperties {
                    up = upNeighbor
                    downNeighbor?.let { down = it }
                }

                // 🔒 Navigation lock: while Selected, consume D-pad so focus stays in list
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp -> {
                                if (frameState is ContinueWatchingFrameState.Selected) {
                                    Log.d("CONTINUE_WATCHING", "🚫 UP blocked (Selected)")
                                    true // Consume
                                } else {
                                    BlueHiveApplication.playHoverSound()
                                    Log.d("CONTINUE_WATCHING", "⬆️ UP - navigating to Latest Released")
                                    false // Allow navigation
                                }
                            }

                            Key.DirectionDown -> {
                                if (frameState is ContinueWatchingFrameState.Selected) {
                                    Log.d("CONTINUE_WATCHING", "🚫 DOWN blocked (Selected)")
                                    true // Consume
                                } else {
                                    Log.d("CONTINUE_WATCHING", "⬇️ DOWN - triggering shelf swap")
                                    onNavigateDown()
                                    true // Consume after handling
                                }
                            }

                            Key.DirectionLeft -> {
                                if (frameState is ContinueWatchingFrameState.Selected) {
                                    Log.d("CONTINUE_WATCHING", "🚫 LEFT blocked (Selected)")
                                    true // Consume
                                } else {
                                    Log.d("CONTINUE_WATCHING", "LEFT navigated to (Focused)")
                                    BlueHiveApplication.playHoverSound()
                                    onNavigateLeft()
                                    true // Consume
                                }
                            }

                            Key.DirectionRight -> {
                                if (frameState is ContinueWatchingFrameState.Selected) {
                                    Log.d("CONTINUE_WATCHING", "🚫 LEFT/RIGHT blocked (Selected)")
                                    true // Consume
                                } else {
                                    Log.d("CONTINUE_WATCHING", "🚫 LEFT/RIGHT blocked (Focused)")
                                    true // Consume
                                }
                            }

                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                BlueHiveApplication.playClickSound()
                                frameState = ContinueWatchingFrameState.Selected
                                Log.d("CONTINUE_WATCHING", "⚪ CENTER pressed - entering Selected")
                                true
                            }

                            else -> false
                        }
                    } else {
                        false
                    }
                }

                // Click selects it (shadow disappears + nav locks)
                .clickable(
                    interactionSource = frameInteraction,
                    indication = null
                ) {
                    frameState = ContinueWatchingFrameState.Selected
                    Log.d("CONTINUE_WATCHING", "🖱️ Clicked - entering Selected")
                }

                // Focus system hookup (also feeds collectIsFocusedAsState)
                .focusable(interactionSource = frameInteraction),
            contentScale = ContentScale.FillBounds
        )

        // ✅ ITEM LIST — always rendered inside the frame bounds
        // zIndex(3f) keeps it above all three frame image layers
        if (historyItems.isNotEmpty()) {
            LazyColumn(
                state               = listState,
                modifier            = Modifier
                    .offset(x = listOffsetX, y = listOffsetY)
                    .width(listWidth)
                    .height(listHeight)
                    .clip(RoundedCornerShape(0.dp))
                    .zIndex(3f),
                verticalArrangement = Arrangement.spacedBy(CW_ITEM_SPACING),
                contentPadding = PaddingValues(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 3.dp),
            ) {
                itemsIndexed(historyItems, key = { _, item -> item.id }) { index, item ->
                    ContinueWatchingItem(
                        item                = item,
                        focusRequester      = requesterFor(item.id),
                        isFirst             = index == 0,
                        isLast              = index == historyItems.lastIndex,
                        frameFocusRequester = focusRequester,
                        downNeighbor        = downNeighbor,
                        onItemClick = {
                            if (!isNavigating) {
                                isNavigating = true
                                coroutineScope.launch {
                                    try {
                                        val detailsDeferred = async {
                                            ApiClient.trailerApi.getMediaDetails(
                                                tmdbId    = item.media_tmdb_id,
                                                mediaType = item.media_type,
                                            )
                                        }

                                        val episodesDeferred = if (item.media_type == "tv") {
                                            async {
                                                try {
                                                    EpisodesRepository().getSeasonEpisodes(
                                                        tmdbId       = item.media_tmdb_id,
                                                        seasonNumber = item.season_number ?: 1,
                                                    ).episodes
                                                } catch (_: Exception) { null }
                                            }
                                        } else null

                                        val recsDeferred = async {
                                            try {
                                                RecommendationsRepository().getRecommendations(
                                                    item.media_tmdb_id,
                                                    item.media_type,
                                                )
                                            } catch (_: Exception) { emptyList() }
                                        }

                                        delay(1000)

                                        val details = detailsDeferred.await()

                                        val app = context.applicationContext as? BlueHiveApplication
                                        app?.storePrefetch(
                                            BlueHiveApplication.MediaPrefetchData(
                                                tmdbId          = item.media_tmdb_id,
                                                mediaType       = item.media_type,
                                                episodes        = episodesDeferred?.await(),
                                                recommendations = recsDeferred.await(),
                                            )
                                        )

                                        val genresString = try {
                                            val raw = details.genres as? String ?: ""
                                            val arr = org.json.JSONArray(raw)
                                            (0 until arr.length())
                                                .mapNotNull { arr.getJSONObject(it).optString("name", null) }
                                                .joinToString(", ")
                                                .takeIf { it.isNotBlank() } ?: "N/A"
                                        } catch (_: Exception) { "N/A" }

                                        val logoUrl = (details.logos as? String)?.takeIf { it.isNotBlank() } ?: ""

                                        val intent = if (item.media_type == "movie") {
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
                                                item.season_number?.let  { putExtra("last_season",  it) }
                                                item.episode_number?.let { putExtra("last_episode", it) }
                                            }
                                        }

                                        isNavigating = false
                                        context.startActivity(intent)

                                    } catch (e: Exception) {
                                        Log.e("CONTINUE_WATCHING", "Failed to fetch details: ${e.message}")
                                        isNavigating = false
                                    }
                                }
                            }
                        },
                        onFocused           = { focusedItemId = item.id },
                    )
                }
            }
        }
    }
}