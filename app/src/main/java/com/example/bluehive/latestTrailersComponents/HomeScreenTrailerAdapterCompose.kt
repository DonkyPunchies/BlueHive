package com.example.bluehive.latestTrailersComponents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.bluehive.homeScreenSectionRules.TRAILER_THUMB_PX_H
import com.example.bluehive.homeScreenSectionRules.TRAILER_THUMB_PX_W
import com.example.bluehive.homeScreenSectionRules.trailerThumbMemoryKey
import com.example.bluehive.homeScreenSectionRules.trailerThumbUrl
import com.example.bluehive.models.LatestTrailer
import com.example.bluehive.utilities.AppTypography
import androidx.compose.ui.platform.LocalContext

// Add at file level — zero per-recompose allocation
private val TRAILER_SCRIM = Brush.verticalGradient(
    colors = listOf(Color.Transparent, Color(0x99000000))
)
private val BADGE_SHAPE = RoundedCornerShape(3.dp)

// Decode dimensions + URL transform + cache key all come from the SHARED spec
// in homeScreenSectionRules — the window preloader and AppWarmup's splash
// prefetch build byte-identical requests, so this render is always a cache hit.
private val BADGE_COUNT_BG = Color(0xCC000000)





@Composable
fun HomeScreenTrailerAdapterCompose(
    trailer: LatestTrailer,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
    titleFont: FontFamily = AppTypography.pattayaRegular,
    metaFont: FontFamily = AppTypography.passionRegular
) {
    val context = LocalContext.current

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(trailerThumbUrl(trailer.imgSrc))
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .allowRgb565(true)
                .allowHardware(true)
                .size(TRAILER_THUMB_PX_W, TRAILER_THUMB_PX_H)
                .crossfade(150)
                .memoryCacheKey(trailerThumbMemoryKey(trailer.id))
                .build(),
            contentDescription = trailer.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Gradient overlay (Compose-native; avoids the crash you hit with XML shape drawables)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TRAILER_SCRIM)
        )

        // Bottom-left text
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 8.dp, end = 8.dp)
                .widthIn(max = 170.dp)
        ) {
            Text(
                text = trailer.title,
                color = Color.White,
                fontFamily = titleFont,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 13.sp
            )
            Text(
                text = trailer.dataTitle,
                color = Color(0xFFD0D0D0),
                fontFamily = metaFont,
                fontSize = 8.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 9.sp
            )
        }

        // Badges (top-left)
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 6.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            trailer.mediaType?.let { type ->
                TrailerPillBadge(
                    text = type.uppercase(),
                    bg = when (type.lowercase()) {
                        "movie" -> Color(0xFF08CB00)
                        "tv" -> Color(0xFF2196F3)
                        else -> Color(0xFF757575)
                    }
                )
            }

            TrailerPillBadge(
                text = "${index + 1}/$total",
                bg = BADGE_COUNT_BG,
                modifier = Modifier.padding(start = if (trailer.mediaType == null) 0.dp else 6.dp)
            )
        }
    }
}

@Composable
private fun TrailerPillBadge(
    text: String,
    bg: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = Color.White,
        fontFamily = AppTypography.passionRegular,
        fontSize = 7.sp,
        modifier = modifier
            .clip(BADGE_SHAPE)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        maxLines = 1,
        overflow = TextOverflow.Clip
    )
}
