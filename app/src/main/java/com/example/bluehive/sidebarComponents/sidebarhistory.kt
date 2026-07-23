package com.example.bluehive.sidebarComponents

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.bluehive.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import com.example.bluehive.utilities.AppTypography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.example.bluehive.utilities.ModularButton
import com.example.bluehive.utilities.ModularButtonAnimationConfig
import com.example.bluehive.utilities.ModularButtonColors
import com.example.bluehive.utilities.ModularButtonDimensions
import com.example.bluehive.utilities.ModularButtonGlowConfig
import com.example.bluehive.utilities.ModularButtonTextConfig
import androidx.compose.ui.focus.focusProperties
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.WatchHistoryResponse
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.example.bluehive.MoviesDetailsScreenCompose
import com.example.bluehive.TVShowsDetailsScreenCompose
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import com.example.bluehive.repository.RecommendationsRepository
import com.example.bluehive.repository.EpisodesRepository
import androidx.compose.runtime.snapshotFlow
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.bluehive.SourceScreen


// ─────────────────────────────────────────────────────────────────────────────
//  History screen — launched from the sidebar History menu item
// ─────────────────────────────────────────────────────────────────────────────

fun openHistoryScreen(context: Context, profileId: Int = -1) {
    context.startActivity(
        Intent(context, HistoryScreenActivity::class.java)
            .putExtra("PROFILE_ID", profileId)
    )
}

class HistoryScreenActivity : ComponentActivity() {

    private var refreshTrigger = mutableIntStateOf(0)

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
            HistoryScreen(profileId = profileId, refreshTrigger = refreshTrigger.intValue)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTrigger.intValue++
    }
}


@Composable
fun HistoryDropdown(
    label:            String,
    options:          List<String>,
    selectedOption:   String,
    onOptionSelected: (String) -> Unit,
    modifier:         Modifier = Modifier,
    buttonWidth:      Dp = 140.dp,
    dropdownMaxHeight: Dp = 200.dp,
    externalFocusRequester: FocusRequester? = null,
    suppressInitialFocusSound: Boolean = false,
    onExpandedChanged: (Boolean) -> Unit = {},
) {
    var isExpanded   by remember { mutableStateOf(false) }
    var isFocused    by remember { mutableStateOf(false) }
    var hasReceivedFirstFocus by remember { mutableStateOf(!suppressInitialFocusSound) }
    val optionFocusRequesters = remember(options) { List(options.size) { FocusRequester() } }
    val buttonFocusRequester  = externalFocusRequester ?: remember { FocusRequester() }
    var focusedOptionIndex by remember { mutableIntStateOf(0) }

    val arrowRotation by animateFloatAsState(
        targetValue   = if (isExpanded) 180f else 0f,
        animationSpec = androidx.compose.animation.core.tween(150),
        label         = "arrowRotation",
    )

    Column(modifier = modifier) {

        // ── Main pill button ──────────────────────────────────────────────────
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .width(buttonWidth)
                .height(26.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    color = if (isFocused) Color(0xFF2A2A3A) else Color(0xFF1A1A24),
                )
                .border(
                    width = if (isFocused) 1.8.dp else 1.dp,
                    color = if (isFocused) Color.White else Color(0xFF2A2A3A),
                    shape = RoundedCornerShape(3.dp),
                )
                .focusRequester(buttonFocusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) {
                        if (hasReceivedFirstFocus) BlueHiveApplication.playHoverSound()
                        else hasReceivedFirstFocus = true
                    }
                }
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                isExpanded = !isExpanded
                                onExpandedChanged(isExpanded)
                                if (isExpanded) {
                                    focusedOptionIndex =
                                        options.indexOf(selectedOption).coerceAtLeast(0)
                                }
                                BlueHiveApplication.playClickSound()
                                true
                            }

                            Key.DirectionDown -> {
                                BlueHiveApplication.playHoverSound()
                                false
                            }

                            Key.Back -> {
                                if (isExpanded) {
                                    isExpanded = false; onExpandedChanged(false); true
                                } else false
                            }

                            Key.DirectionUp -> {
                                !isExpanded
                            }

                            else -> false
                        }
                    } else false
                }
                .padding(horizontal = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize(),
            ) {
                Text(
                    text       = "$label: $selectedOption",
                    color      = Color(0xFFB4B4B4),
                    fontSize   = 9.sp,
                    fontFamily = AppTypography.interBold,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.weight(1f),
                )
                Text(
                    text     = "▼",
                    color    = Color(0xFF888888),
                    fontSize = 11.sp,
                    modifier = Modifier.rotate(arrowRotation),
                )
            }
        }

        // ── Dropdown list ─────────────────────────────────────────────────────
        if (isExpanded) {
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .width(buttonWidth)
                    .heightIn(max = dropdownMaxHeight)
                    .clip(RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
                    .background(Color(0xFF1A1A24))
                    .border(
                        width = 1.dp,
                        color = Color(0xFF2A2A3A),
                        shape = RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp),
                    )
                    .verticalScroll(scrollState)
                    .padding(vertical = 4.dp),
            ) {
                options.forEachIndexed { index, option ->
                    val optFocus    = optionFocusRequesters[index]
                    var optFocused by remember { mutableStateOf(false) }
                    val isSelected  = option == selectedOption

                    LaunchedEffect(isExpanded) {
                        if (isExpanded && index == focusedOptionIndex) {
                            kotlinx.coroutines.android.awaitFrame()
                            try { optFocus.requestFocus() } catch (_: Exception) {}
                        }
                    }

                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .background(
                                when {
                                    optFocused -> Color(0xFF2644A6)
                                    isSelected -> Color(0xFF1E2854)
                                    else -> Color.Transparent
                                }
                            )
                            .focusRequester(optFocus)
                            .onFocusChanged {
                                optFocused = it.isFocused
                                if (it.isFocused) BlueHiveApplication.playHoverSound()
                            }
                            .focusable()
                            .focusProperties {
                                up =
                                    if (index > 0) optionFocusRequesters[index - 1] else buttonFocusRequester
                                down =
                                    if (index < options.size - 1) optionFocusRequesters[index + 1] else FocusRequester.Cancel
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                            onOptionSelected(option)
                                            isExpanded = false
                                            onExpandedChanged(false)
                                            buttonFocusRequester.requestFocus()
                                            true
                                        }

                                        Key.Back -> {
                                            isExpanded = false
                                            onExpandedChanged(false)
                                            buttonFocusRequester.requestFocus()
                                            true
                                        }

                                        Key.DirectionLeft, Key.DirectionRight -> {
                                            isExpanded = false
                                            onExpandedChanged(false)
                                            buttonFocusRequester.requestFocus()
                                            false
                                        }

                                        Key.DirectionUp -> {
                                            if (index == 0) {
                                                isExpanded = false
                                                onExpandedChanged(false)
                                                buttonFocusRequester.requestFocus()
                                                true
                                            } else false
                                        }

                                        else -> false
                                    }
                                } else false
                            }
                            .padding(horizontal = 12.dp),
                    ) {
                        Text(
                            text       = option,
                            color      = if (optFocused || isSelected) Color.White else Color(0xFF888888),
                            fontSize   = 11.sp,
                            fontFamily = AppTypography.interBold,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}



private val CALENDAR_ICON_RES = R.drawable.calendar
private val CALENDAR_ICON_SIZE = 14.dp
private val CALENDAR_ICON_END_PADDING = 4.dp
private val CALENDAR_BLOCK_END_PADDING = 14.dp
private val CALENDAR_ICON_COLOR_FOCUSED = Color(0xFF9999BB)
private val CALENDAR_ICON_COLOR_UNFOCUSED = Color(0xFF666688)
private val CALENDAR_TEXT_COLOR_FOCUSED = Color(0xFF9999BB)
private val CALENDAR_TEXT_COLOR_UNFOCUSED = Color(0xFF666688)
private val CALENDAR_TEXT_SIZE = 9.sp





private const val HISTORY_CONTINUE_HINT_TEXT = "Click to continue watching"
private val HISTORY_CONTINUE_HINT_OFFSET_X = 120.dp
private val HISTORY_CONTINUE_HINT_OFFSET_Y = 0.dp
private const val HISTORY_CONTINUE_HINT_ALPHA = 0.8f

private const val HISTORY_CONTINUE_HINT_PULSE_DURATION_MS = 950
private const val HISTORY_CONTINUE_HINT_MIN_SCALE = 0.97f
private const val HISTORY_CONTINUE_HINT_MAX_SCALE = 1.05f

private val HISTORY_CONTINUE_ICON_RES = R.drawable.history_play
private val HISTORY_CONTINUE_ICON_SIZE = 21.dp
private val HISTORY_CONTINUE_ICON_TINT = Color(0xFFF3F6FF)
private val HISTORY_CONTINUE_ICON_END_PADDING = 7.dp

private val HISTORY_CONTINUE_TEXT_FONT = AppTypography.interBold
private val HISTORY_CONTINUE_TEXT_SIZE = 11.sp
private val HISTORY_CONTINUE_TEXT_COLOR = Color(0xFFF3F6FF)
private val HISTORY_CONTINUE_TEXT_START_PADDING = 0.dp



private val HISTORY_ROW_GLOW_WIDTH = 846.dp
private val HISTORY_ROW_GLOW_HEIGHT = 66.dp
private val HISTORY_ROW_GLOW_OFFSET_X = 0.dp
private val HISTORY_ROW_GLOW_OFFSET_Y = 0.dp
private val HISTORY_ROW_GLOW_BLUR = 24.dp
private val HISTORY_ROW_GLOW_CORNER_RADIUS = 10.dp

private val HISTORY_ROW_GLOW_COLOR = Color(0xFF4A7CFF)
private const val HISTORY_ROW_GLOW_ALPHA_FOCUSED = 0.85f
private const val HISTORY_ROW_GLOW_ALPHA_UNFOCUSED = 0f

private const val HISTORY_ROW_GLOW_FADE_DURATION_MS = 200

private val HISTORY_ROW_NEON_BORDER_COLOR = Color(0xFF5B8AFF)
private val HISTORY_ROW_NEON_BORDER_WIDTH_FOCUSED = 1.3.dp
private val HISTORY_ROW_NEON_BORDER_WIDTH_UNFOCUSED = 1.dp
private val HISTORY_ROW_INNER_GLOW_COLOR = Color(0xFF3D6BFF)
private const val HISTORY_ROW_INNER_GLOW_ALPHA_FOCUSED = 0.35f
private val IMAGE_BORDER_COLOR = Color(0xFFE0E0E0).copy(alpha = 0.25f)
private val ROW_SHAPE   = RoundedCornerShape(6.dp)
private val IMAGE_SHAPE = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 0.dp, bottomEnd = 0.dp)
private val DATE_FORMATTER = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
private val GLOW_OUTER_SHAPE = RoundedCornerShape(HISTORY_ROW_GLOW_CORNER_RADIUS)
private val GLOW_INNER_SHAPE = RoundedCornerShape(8.dp)

private val FADE_TOP_BRUSH = Brush.verticalGradient(
    colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
)
private val FADE_BOTTOM_BRUSH = Brush.verticalGradient(
    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
)

private val GENRES_LIST = listOf(
    "All Content",
    "Action", "Adventure", "Animation", "Comedy", "Crime",
    "Documentary", "Drama", "Family", "Fantasy", "History",
    "Horror", "Music", "Mystery", "Romance", "Science Fiction",
    "TV Movie", "Thriller", "War", "Western",
    "Action & Adventure", "Kids", "News", "Reality",
    "Sci-Fi & Fantasy", "Soap", "Talk", "War & Politics",
)
private val SORT_OPTIONS = listOf("Newest", "Oldest", "A–Z", "Z–A", "By Release Date")

private val BTN_MAIN_DEFAULT   = Color(0xFF192C6F)
private val BTN_MAIN_FOCUSED   = Color(0xFF4C63AC)
private val BTN_MAIN_TOGGLED   = Color(0xFF101628)
private val BTN_SECOND_DEFAULT = Color(0xFF151C3A)
private val BTN_SECOND_FOCUSED = Color(0xFF2E325F)
private val BTN_SECOND_TOGGLED = Color(0xFF1B1C34)
private val BTN_TEXT_UNFOCUSED = Color(0xFF868686)
private val BTN_TEXT_FOCUSED   = Color(0xFFDADADA)
private val BTN_TEXT_UNFOCUSED_TOGGLED = Color(0xFF343434)
private val BTN_TEXT_TOGGLED_FOCUS     = Color(0xFFDADADA)


@Composable
private fun BoxScope.HistoryFocusedContinueHint() {
    val pulseTransition = rememberInfiniteTransition(label = "historyContinueHintPulse")

    val pulseScale by pulseTransition.animateFloat(
        initialValue = HISTORY_CONTINUE_HINT_MIN_SCALE,
        targetValue = HISTORY_CONTINUE_HINT_MAX_SCALE,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = HISTORY_CONTINUE_HINT_PULSE_DURATION_MS,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "historyContinueHintScale",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .align(Alignment.Center)
            .offset(
                x = HISTORY_CONTINUE_HINT_OFFSET_X,
                y = HISTORY_CONTINUE_HINT_OFFSET_Y
            )
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
                alpha = HISTORY_CONTINUE_HINT_ALPHA
            }
    ) {
        Icon(
            painter = painterResource(id = HISTORY_CONTINUE_ICON_RES),
            contentDescription = "Continue watching",
            tint = HISTORY_CONTINUE_ICON_TINT,
            modifier = Modifier
                .size(HISTORY_CONTINUE_ICON_SIZE)
                .padding(end = HISTORY_CONTINUE_ICON_END_PADDING)
        )

        Text(
            text = HISTORY_CONTINUE_HINT_TEXT,
            color = HISTORY_CONTINUE_TEXT_COLOR,
            fontSize = HISTORY_CONTINUE_TEXT_SIZE,
            fontFamily = HISTORY_CONTINUE_TEXT_FONT,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = HISTORY_CONTINUE_TEXT_START_PADDING)
        )
    }
}

@Composable
fun HistoryRow(item: WatchHistoryResponse, modifier: Modifier = Modifier, isFirst: Boolean = false, isLast: Boolean = false, onItemClick: () -> Unit = {}) {
    var isFocused by remember { mutableStateOf(false) }
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "rowScale",
    )

    val glowAlpha by animateFloatAsState(
        targetValue = if (isFocused) {
            HISTORY_ROW_GLOW_ALPHA_FOCUSED
        } else {
            HISTORY_ROW_GLOW_ALPHA_UNFOCUSED
        },
        animationSpec = androidx.compose.animation.core.tween(HISTORY_ROW_GLOW_FADE_DURATION_MS),
        label = "rowGlowAlpha",
    )

    val formattedDate = remember(item.watched_at) {
        try {
            val odt = OffsetDateTime.parse(item.watched_at)
            val local = odt.atZoneSameInstant(ZoneId.systemDefault())
            local.format(DATE_FORMATTER)
        } catch (_: Exception) {
            ""
        }
    }

    val subtitle = remember(item) {
        if (item.media_type == "tv" && item.season_number != null && item.episode_number != null) {
            val base = "S${item.season_number}:E${item.episode_number}"
            if (!item.episode_name.isNullOrBlank()) "$base - ${item.episode_name}" else base
        } else ""
    }






    Box(
        modifier = modifier
            .width(816.5.dp)
            .height(48.25.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown &&
                            (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        isPressed = true
                        BlueHiveApplication.playClickSound()
                        onItemClick()
                        false
                    }

                    event.type == KeyEventType.KeyUp &&
                            (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        isPressed = false
                        false
                    }

                    event.type == KeyEventType.KeyDown &&
                            (event.key == Key.DirectionUp || event.key == Key.DirectionDown) -> {
                        if (!(isFirst && event.key == Key.DirectionUp) &&
                            !(isLast && event.key == Key.DirectionDown)
                        ) {
                            BlueHiveApplication.playHoverSound()
                        }
                        false
                    }

                    else -> false
                }
            }
            .focusable(),
    ) {
        // outer neon bloom — wide soft glow
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = HISTORY_ROW_GLOW_OFFSET_X, y = HISTORY_ROW_GLOW_OFFSET_Y)
                .width(HISTORY_ROW_GLOW_WIDTH)
                .height(HISTORY_ROW_GLOW_HEIGHT)
                .background(
                    color = HISTORY_ROW_GLOW_COLOR,
                    shape = GLOW_OUTER_SHAPE
                )
                .blur(HISTORY_ROW_GLOW_BLUR)
                .graphicsLayer { alpha = glowAlpha }
        )

        // inner neon haze — tighter glow hugging the border
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = HISTORY_ROW_GLOW_OFFSET_X, y = HISTORY_ROW_GLOW_OFFSET_Y)
                .width(816.5.dp + 4.dp)
                .height(48.25.dp + 4.dp)
                .background(
                    color = HISTORY_ROW_INNER_GLOW_COLOR,
                    shape = GLOW_INNER_SHAPE
                )
                .blur(8.dp)
                .graphicsLayer {
                    alpha = if (isFocused) HISTORY_ROW_INNER_GLOW_ALPHA_FOCUSED else 0f
                }
        )

        // actual row surface
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(ROW_SHAPE)
                .background(if (isFocused) Color(0xFF1A2755) else Color(0xFF111120))
                .border(
                    if (isFocused) HISTORY_ROW_NEON_BORDER_WIDTH_FOCUSED else HISTORY_ROW_NEON_BORDER_WIDTH_UNFOCUSED,
                    if (isFocused) HISTORY_ROW_NEON_BORDER_COLOR else Color(0xFF2A2A3A),
                    ROW_SHAPE
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize(),
            ) {
                // thumbnail
                Box(
                    modifier = Modifier
                        .width(83.dp)
                        .height(46.75.dp)
                        .clip(IMAGE_SHAPE)
                        .border(1.dp, IMAGE_BORDER_COLOR, IMAGE_SHAPE)
                ) {
                    if (!item.image_url.isNullOrBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(item.image_url)
                                .size(166, 94)
                                .allowRgb565(true)
                                .allowHardware(true)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                // Key per EPISODE, not per show. Keying by
                                // media_tmdb_id alone made every episode of a
                                // series collide on one cache entry, so all rows
                                // showed whichever episode's still loaded first.
                                .memoryCacheKey("history_thumb_${item.media_tmdb_id}_${item.media_type}_${item.season_number}_${item.episode_number}")
                                .crossfade(150)
                                .build(),
                            contentDescription = item.media_title,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.matchParentSize(),
                        )
                    } else {
                        Box(modifier = Modifier
                            .matchParentSize()
                            .background(Color(0xFF1A1A2E)))
                    }
                }

                // title + subtitle
                Column(
                    modifier = Modifier
                        .padding(start = 5.dp, end = 12.dp)
                        .weight(1f),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Text(
                        text = item.media_title ?: "",
                        color = Color(0xFFE0E0E0),
                        fontSize = 11.sp,
                        fontFamily = AppTypography.interBold,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            color = Color(0xFF888888),
                            fontSize = 9.sp,
                            fontFamily = AppTypography.interBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                // date + icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = CALENDAR_BLOCK_END_PADDING),
                ) {
                    Icon(
                        painter = painterResource(id = CALENDAR_ICON_RES),
                        contentDescription = "Watch date",
                        tint = if (isFocused) {
                            CALENDAR_ICON_COLOR_FOCUSED
                        } else {
                            CALENDAR_ICON_COLOR_UNFOCUSED
                        },
                        modifier = Modifier
                            .size(CALENDAR_ICON_SIZE)
                            .padding(end = CALENDAR_ICON_END_PADDING)
                    )

                    Text(
                        text = formattedDate,
                        color = if (isFocused) {
                            CALENDAR_TEXT_COLOR_FOCUSED
                        } else {
                            CALENDAR_TEXT_COLOR_UNFOCUSED
                        },
                        fontSize = CALENDAR_TEXT_SIZE,
                        fontFamily = AppTypography.interBold,
                    )
                }
            }

            if (isFocused) {
                HistoryFocusedContinueHint()
            }
        }
    }
}





@Composable
fun HistoryScreen(profileId: Int = -1, refreshTrigger: Int = 0) {

    val historyItems = remember { mutableStateListOf<WatchHistoryResponse>() }
    var isLoading    by remember { mutableStateOf(true) }

    // ── Pagination state ─────────────────────────────────────────────────
    val pageSize = 50
    var currentOffset by remember { mutableIntStateOf(0) }
    var hasMore       by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isNavigating by remember { mutableStateOf(false) }
    var lastFocusedIndex by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            historyItems.clear()
            isLoading = true
            isNavigating = false
            lastFocusedIndex = 0
            currentOffset = 0
            hasMore = true
            isLoadingMore = false
        }
    }

    LaunchedEffect(profileId, refreshTrigger) {
        if (profileId == -1) { isLoading = false; return@LaunchedEffect }
        // reset pagination on initial load / refresh
        historyItems.clear()
        currentOffset = 0
        hasMore = true
        isLoading = true
        try {
            val fetched = ApiClient.bluehiveApi.getWatchHistory(
                profileId = profileId,
                limit     = pageSize,
                offset    = 0,
            )
            historyItems.addAll(fetched)
            currentOffset = fetched.size
            hasMore = fetched.size == pageSize
        } catch (e: Exception) {
            android.util.Log.e("HistoryScreen", "Failed to load history: ${e.message}")
        } finally {
            isLoading = false
        }
    }


    Box(modifier = Modifier.fillMaxSize()) {

        // background screen
        Image(
            painter            = painterResource(id = R.drawable.home_screen),
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )

        // dark scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
        )


        if (!isLoading && historyItems.isEmpty()) {
            // ══════════════════════════════════════════════════════════════════
            //  EMPTY STATE — no header, no buttons, just the overlay
            // ══════════════════════════════════════════════════════════════════

            // extra dark blurry scrim behind the overlay card
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
            )

            val exitFocus = remember { FocusRequester() }

            LaunchedEffect(Unit) {
                kotlinx.coroutines.android.awaitFrame()
                exitFocus.requestFocus()
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(
                            color = Color(0xFF121213),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = Color(0xFF3A3737),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 40.dp, vertical = 32.dp)
                        .height(130.dp)
                        .width(480.dp),
                ) {
                    // ── Title — bigger, separated ─────────────────────────────
                    Text(
                        text = "No previous watched content available to view",
                        color = Color.White,
                        fontFamily = AppTypography.interBold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                    )

                    // ── Body — smaller, softer ────────────────────────────────
                    Text(
                        text = "To change this, just watch any piece of media and it will be automatically recorded for your profile.",
                        color = Color(0xFFAAAAAA),
                        fontFamily = AppTypography.interSemiBold,
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = 8.dp)
                    )


                    Text(
                        text = "To leave just press the back button",
                        color = Color(0xFFA01D1D),
                        fontFamily = AppTypography.interBold,
                        fontWeight = FontWeight.Normal,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = 36.dp)
                            .padding(bottom = 28.dp),
                    )


                }
            }

        } else if (!isLoading) {

            val headerText = "Watch History"
            val headerFontSize    = 29f
            val headerOpacity     = 0.95f
            val headerOffsetX     = 75.dp
            val headerOffsetY     = 25.dp

            val dropdownOffsetX   = 75.dp
            val dropdownOffsetY   = 70.dp
            val dropdownSpacing   = 170.dp




            var selectedGenre by remember { mutableStateOf("All Content") }
            var selectedSort  by remember { mutableStateOf("Newest") }
            var isAnyDropdownExpanded by remember { mutableStateOf(false) }

            val filterButtonOffsetX  = 600.dp
            val filterButtonOffsetY  = 60.dp
            val filterButtonWidth    = 90.dp
            val filterButtonSpacing  = filterButtonWidth + 9.dp

            val allMediaFocus  = remember { FocusRequester() }
            val moviesFocus    = remember { FocusRequester() }
            val tvSeriesFocus  = remember { FocusRequester() }
            val sortDropdownFocus = remember { FocusRequester() }
            val filterDropdownFocus = remember { FocusRequester() }

            var selectedFilter by remember { mutableStateOf("all") }
            var suppressNextHover by remember { mutableStateOf(false) }





            // header
            Text(
                text       = headerText,
                fontSize   = headerFontSize.sp,
                fontFamily = AppTypography.lalezarRegular,
                color      = Color.White.copy(alpha = headerOpacity),
                modifier   = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = headerOffsetX, y = headerOffsetY),
            )


            // ── Watch history list ────────────────────────────────────────
            val filteredItems by remember(selectedFilter, selectedGenre, selectedSort) {
                derivedStateOf {
                    var items = when (selectedFilter) {
                        "movies" -> historyItems.filter { it.media_type == "movie" }
                        "tv"     -> historyItems.filter { it.media_type == "tv" }
                        else     -> historyItems.toList()
                    }

                    if (selectedGenre != "All Content") {
                        items = items.filter { item ->
                            val genresJson = item.genres ?: return@derivedStateOf emptyList()
                            try {
                                val arr = org.json.JSONArray(genresJson)
                                (0 until arr.length()).any { i ->
                                    arr.getJSONObject(i).optString("name", "")
                                        .equals(selectedGenre, ignoreCase = true)
                                }
                            } catch (_: Exception) {
                                false
                            }
                        }
                    }

                    val sorted = when (selectedSort) {
                        "Newest"          -> items.sortedByDescending { it.watched_at }
                        "Oldest"          -> items.sortedBy { it.watched_at }
                        "A–Z"             -> items.sortedBy { (it.media_title ?: "").lowercase() }
                        "Z–A"             -> items.sortedByDescending { (it.media_title ?: "").lowercase() }
                        "By Release Date" -> items.sortedByDescending { it.media_release_date ?: "" }
                        else              -> items
                    }

                   sorted
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 0.dp, y = 130.dp)
                    .fillMaxWidth()
                    .height(430.dp)
                    .graphicsLayer { alpha = if (isAnyDropdownExpanded) 0.1f else 1f }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                            filterDropdownFocus.requestFocus()
                            true
                        } else false
                    },
            ) {
                val rowFocusRequesters = remember(filteredItems.size) {
                    List(filteredItems.size) { FocusRequester() }
                }

                LaunchedEffect(Unit) {
                    kotlinx.coroutines.android.awaitFrame()
                    rowFocusRequesters.getOrNull(lastFocusedIndex)?.requestFocus()
                }

                val listState = rememberLazyListState()

                LaunchedEffect(selectedSort, selectedGenre, selectedFilter) {
                    lastFocusedIndex = 0
                    listState.scrollToItem(0)
                }

                LaunchedEffect(listState, hasMore, isLoadingMore) {
                    snapshotFlow {
                        val layoutInfo = listState.layoutInfo
                        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        val total = layoutInfo.totalItemsCount
                        Triple(lastVisible, total, hasMore && !isLoadingMore)
                    }.collect { (lastVisible, total, canLoad) ->
                        if (canLoad && total > 0 && lastVisible >= total - 10) {
                            isLoadingMore = true
                            try {
                                val next = ApiClient.bluehiveApi.getWatchHistory(
                                    profileId = profileId,
                                    limit     = pageSize,
                                    offset    = currentOffset,
                                )
                                historyItems.addAll(next)
                                currentOffset += next.size
                                hasMore = next.size == pageSize
                            } catch (e: Exception) {
                                android.util.Log.e("HistoryScreen", "Failed to load more: ${e.message}")
                            } finally {
                                isLoadingMore = false
                            }
                        }
                    }
                }

                LazyColumn(
                    state   = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 10.dp, top = 0.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    itemsIndexed(filteredItems, key = { _, item -> item.id }) { index, item ->
                        HistoryRow(
                            item    = item,
                            isFirst = index == 0,
                            isLast  = index == filteredItems.lastIndex,
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
                                                    .mapNotNull { arr.getJSONObject(it).optString("name") }
                                                    .joinToString(", ")
                                                    .takeIf { it.isNotBlank() } ?: "N/A"
                                            } catch (_: Exception) { "N/A" }

                                            val logoUrl = (details.logos as? String)?.takeIf { it.isNotBlank() } ?: ""

                                            // AFTER — add SOURCE_SCREEN to both branches
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
                                                    putExtra("SOURCE_SCREEN",       SourceScreen.HISTORY)
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
                                                    putExtra("SOURCE_SCREEN",       SourceScreen.HISTORY)
                                                    item.season_number?.let  { putExtra("last_season",  it) }
                                                    item.episode_number?.let { putExtra("last_episode", it) }
                                                }
                                            }

                                            isNavigating = false
                                            context.startActivity(intent)

                                        } catch (e: Exception) {
                                            android.util.Log.e("HistoryScreen", "Failed to fetch media details: ${e.message}")
                                            isNavigating = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .padding(start = 71.6.dp)
                                .focusRequester(rowFocusRequesters[index])
                                .onFocusChanged { fs -> if (fs.isFocused) lastFocusedIndex = index }
                                .then(
                                    if (index == 0) Modifier.focusProperties {
                                        up = filterDropdownFocus
                                    }
                                    else Modifier
                                ),
                        )
                    }
                }



                // ── Empty filter result overlay ──────────────────────────────
                if (filteredItems.isEmpty() && historyItems.isNotEmpty()) {
                    val emptyFilterFocus = remember { FocusRequester() }

                    LaunchedEffect(selectedGenre, selectedFilter) {
                        kotlinx.coroutines.android.awaitFrame()
                        try { emptyFilterFocus.requestFocus() } catch (_: Exception) {}
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .align(Alignment.TopCenter)
                            .offset(x = 35.dp, y = 0.dp)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.DirectionUp || event.key == Key.Back)
                                ) {
                                    filterDropdownFocus.requestFocus()
                                    true
                                } else false
                            }
                            .focusRequester(emptyFilterFocus)
                            .focusable(),
                    ) {
                        // AFTER
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .background(
                                    color = Color(0xFF121213),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = Color(0xFF3A3737),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .padding(horizontal = 56.dp, vertical = 44.dp)
                                .height(108.dp)
                                .width(640.dp),
                        ) {
                            Text(
                                text = "No previous watched content in that genre was recorded",
                                color = Color.White,
                                fontFamily = AppTypography.interBold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                            )

                            Text(
                                text = "Choose another genre to view existing content",
                                color = Color(0xFFA01D1D),
                                fontFamily = AppTypography.interSemiBold,
                                fontWeight = FontWeight.Normal,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset(y = 7.dp),
                            )
                        }
                    }
                }


                // AFTER
                // top fade — hidden when filter-empty overlay is showing
                if (!(filteredItems.isEmpty() && historyItems.isNotEmpty())) {
                    Box(
                        modifier = Modifier
                            .offset(y = (0).dp)
                            .width(816.5.dp)
                            .height(50.dp)
                            .align(Alignment.TopCenter)
                            .clip(RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp))
                            .background(
                                FADE_TOP_BRUSH
                            )
                    )
                }

                // bottom fade
                Box(
                    modifier = Modifier
                        .offset(y = (0).dp)
                        .fillMaxWidth()
                        .height(50.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            FADE_BOTTOM_BRUSH
                        )
                )
            }


            // ── Filter dropdown ───────────────────────────────────────────
            HistoryDropdown(
                label            = "Filter",
                options          = GENRES_LIST,
                selectedOption   = selectedGenre,
                onOptionSelected = { selectedGenre = it },
                buttonWidth      = 160.dp,
                dropdownMaxHeight = 237.dp,
                externalFocusRequester = filterDropdownFocus,
                suppressInitialFocusSound = true,
                onExpandedChanged = { isAnyDropdownExpanded = it },
                modifier         = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = dropdownOffsetX, y = dropdownOffsetY),
            )

            HistoryDropdown(
                label            = "Sort",
                options          = SORT_OPTIONS,
                selectedOption   = selectedSort,
                onOptionSelected = { selectedSort = it },
                buttonWidth      = 140.dp,
                externalFocusRequester = sortDropdownFocus,
                onExpandedChanged = { isAnyDropdownExpanded = it },
                modifier         = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = dropdownOffsetX + dropdownSpacing, y = dropdownOffsetY),
            )


            // ── Filter radio buttons ──────────────────────────────────────
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
                    .focusProperties {
                        right = if (selectedFilter == "movies") tvSeriesFocus else moviesFocus
                    }
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
                dimensions      = ModularButtonDimensions(
                    mainWidth          = filterButtonWidth,
                    mainHeight         = 27.dp,
                    mainYOffset        = 6.5.dp,
                    secondWidth        = filterButtonWidth,
                    secondHeight       = 12.dp,
                    secondYOffset      = 26.dp,
                    mainCornerRadius   = 7f,
                    secondCornerRadius = 8f,
                    shadowHeight       = 12.dp,
                    glowWidth          = filterButtonWidth + 14.2.dp,
                    glowHeight         = 41.dp,
                ),
                colors          = ModularButtonColors(
                    mainDefault          = BTN_MAIN_DEFAULT,
                    mainToggled          = BTN_MAIN_TOGGLED,
                    mainFocused          = BTN_MAIN_FOCUSED,
                    secondDefault        = BTN_SECOND_DEFAULT,
                    secondToggled        = BTN_SECOND_TOGGLED,
                    secondFocused        = BTN_SECOND_FOCUSED,
                    textFocused          = BTN_TEXT_FOCUSED,
                    textUnfocused        = BTN_TEXT_UNFOCUSED,
                    textUnfocusedToggled = BTN_TEXT_UNFOCUSED_TOGGLED,
                    textToggledFocus     = BTN_TEXT_TOGGLED_FOCUS,
                ),
                glowConfig      = ModularButtonGlowConfig(
                    enabled               = true,
                    defaultRes            = R.drawable.button_focus_wide_glow,
                    offsetX               = (-6.85).dp,
                    offsetY               = 7.dp,
                    cornerRadius          = 100.dp,
                    fadeOutDurationMillis = 200,
                    fadeInDurationMillis  = 400,
                ),
                animationConfig = ModularButtonAnimationConfig(
                    pressOffset           = 3.5.dp,
                    textOffsetDefault     = 7.9.dp,
                    textOffsetPressed     = 9.9.dp,
                    durationMillis        = 110,
                    bounceBackDelayMillis = 200,
                ),
            )

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
                    .focusProperties {
                        left =
                            if (selectedFilter == "all") sortDropdownFocus else if (selectedFilter == "tv") allMediaFocus else FocusRequester.Cancel
                        right = when (selectedFilter) {
                            "tv" -> FocusRequester.Cancel
                            "all" -> tvSeriesFocus
                            else -> FocusRequester.Cancel
                        }
                    }
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
                dimensions      = ModularButtonDimensions(
                    mainWidth          = filterButtonWidth,
                    mainHeight         = 27.dp,
                    mainYOffset        = 6.5.dp,
                    secondWidth        = filterButtonWidth,
                    secondHeight       = 12.dp,
                    secondYOffset      = 26.dp,
                    mainCornerRadius   = 7f,
                    secondCornerRadius = 8f,
                    shadowHeight       = 12.dp,
                    glowWidth          = filterButtonWidth + 14.2.dp,
                    glowHeight         = 41.dp,
                ),
                colors          = ModularButtonColors(
                    mainDefault          = BTN_MAIN_DEFAULT,
                    mainToggled          = BTN_MAIN_TOGGLED,
                    mainFocused          = BTN_MAIN_FOCUSED,
                    secondDefault        = BTN_SECOND_DEFAULT,
                    secondFocused        = BTN_SECOND_FOCUSED,
                    secondToggled        = BTN_SECOND_TOGGLED,
                    textFocused          = BTN_TEXT_FOCUSED,
                    textUnfocused        = BTN_TEXT_UNFOCUSED,
                    textUnfocusedToggled = BTN_TEXT_UNFOCUSED_TOGGLED,
                    textToggledFocus     = BTN_TEXT_TOGGLED_FOCUS,
                ),
                glowConfig      = ModularButtonGlowConfig(
                    enabled               = true,
                    defaultRes            = R.drawable.button_focus_wide_glow,
                    offsetX               = (-6.85).dp,
                    offsetY               = 7.dp,
                    cornerRadius          = 100.dp,
                    fadeOutDurationMillis = 200,
                    fadeInDurationMillis  = 400,
                ),
                animationConfig = ModularButtonAnimationConfig(
                    pressOffset           = 3.5.dp,
                    textOffsetDefault     = 7.9.dp,
                    textOffsetPressed     = 9.9.dp,
                    durationMillis        = 110,
                    bounceBackDelayMillis = 200,
                ),
            )

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
                    .focusProperties {
                        left = if (selectedFilter == "movies") allMediaFocus else moviesFocus
                    }
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
                dimensions      = ModularButtonDimensions(
                    mainWidth          = filterButtonWidth,
                    mainHeight         = 27.dp,
                    mainYOffset        = 6.5.dp,
                    secondWidth        = filterButtonWidth,
                    secondHeight       = 12.dp,
                    secondYOffset      = 26.dp,
                    mainCornerRadius   = 7f,
                    secondCornerRadius = 8f,
                    shadowHeight       = 12.dp,
                    glowWidth          = filterButtonWidth + 14.2.dp,
                    glowHeight         = 41.dp,
                ),
                colors          = ModularButtonColors(
                    mainDefault          = BTN_MAIN_DEFAULT,
                    mainToggled          = BTN_MAIN_TOGGLED,
                    mainFocused          = BTN_MAIN_FOCUSED,
                    secondDefault        = BTN_SECOND_DEFAULT,
                    secondFocused        = BTN_SECOND_FOCUSED,
                    secondToggled        = BTN_SECOND_TOGGLED,
                    textFocused          = BTN_TEXT_FOCUSED,
                    textUnfocused        = BTN_TEXT_UNFOCUSED,
                    textUnfocusedToggled = BTN_TEXT_UNFOCUSED_TOGGLED,
                    textToggledFocus     = BTN_TEXT_TOGGLED_FOCUS,
                ),
                glowConfig      = ModularButtonGlowConfig(
                    enabled               = true,
                    defaultRes            = R.drawable.button_focus_wide_glow,
                    offsetX               = (-6.85).dp,
                    offsetY               = 7.dp,
                    cornerRadius          = 100.dp,
                    fadeOutDurationMillis = 200,
                    fadeInDurationMillis  = 400,
                ),
                animationConfig = ModularButtonAnimationConfig(
                    pressOffset           = 3.5.dp,
                    textOffsetDefault     = 7.9.dp,
                    textOffsetPressed     = 9.9.dp,
                    durationMillis        = 110,
                    bounceBackDelayMillis = 200,
                ),
            )
        }
    }
}




// =========================================================== DO NOT DELETE ===============================================================
//@androidx.compose.ui.tooling.preview.Preview(
//    showBackground = true,
//    widthDp = 960,
//    heightDp = 540,
//)
//@Composable
//fun HistoryScreenPreview() {
//    HistoryScreen()
//}
// =========================================================== DO NOT DELETE ===============================================================