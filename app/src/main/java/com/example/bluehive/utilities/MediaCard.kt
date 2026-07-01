package com.example.bluehive.utilities

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.example.bluehive.BuildConfig
import com.example.bluehive.models.MediaItem
import java.util.Locale


@Composable
fun MediaCard(
    modifier: Modifier = Modifier,
    mediaItem: MediaItem,
    isFocused: Boolean,
    backgroundPainter: Painter,
    focusedPainter: Painter,
    shadowPainter: Painter,
    releaseDateText: String? = null,
    runTimeText: String? = null,
    seasonsText: String? = null,
    rating: Double? = null,
    allowHardwareBitmaps: Boolean = true,

) {

    val context = LocalContext.current
    val density = LocalDensity.current
    val dongleBold = AppTypography.dongleBold

    // ✅ OPTIMIZATION 1: Calculate exact pixel dimensions for Coil
    val posterWidthDp = 90.25.dp
    val posterHeightDp = 130.dp
    val posterWidthPx = remember(density) { with(density) { posterWidthDp.roundToPx() } }
    val posterHeightPx = remember(density) { with(density) { posterHeightDp.roundToPx() } }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .wrapContentSize(unbounded = true)  // ✅ This allows overflow!
            .width(112.dp)
            .height(180.dp)
        //.background(color = androidx.compose.ui.graphics.Color.Red)
    ) {
        val haloPainter = if (isFocused) focusedPainter else shadowPainter
        val haloOffsetY = if (isFocused) 0.7.dp else 2.5.dp
        val widthValue = if (isFocused) 135.3.dp else 133.dp
        val heightValue = if (isFocused) 195.dp else 195.dp

        // ✅ FOCUS GLOW - Render first but with HIGH zIndex
        Image(
            painter = haloPainter,
            contentDescription = null,
            modifier = modifier
                .width(widthValue)
                .height(heightValue)
                .offset(y = haloOffsetY) // larger numbers push the focus element down more
                .offset(x = (-0.25).dp) // smaller the number the more to the left the focus moves
                .align(Alignment.Center),
            contentScale = ContentScale.FillBounds
        )

        // Background - normal z-index (0f by default)
        Image(
            painter = backgroundPainter,
            contentDescription = null,
            modifier = modifier
                .width(102.dp)
                .height(161.25.dp),
            contentScale = ContentScale.FillBounds
        )

        // Poster - normal z-index
        Box(
            modifier = modifier
                .padding(bottom = 21.dp)
                .size(width = posterWidthDp, height = posterHeightDp)
                .clip(AppShapes.card)
                .align(Alignment.Center)
        ) {
            val imageRequest = remember(mediaItem.posterUrl) {
                ImageRequest.Builder(context)
                    .data(mediaItem.posterUrl)
                    .crossfade(200)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .size(posterWidthPx, posterHeightPx)
                    .allowHardware(allowHardwareBitmaps)
                    .allowRgb565(true)
                    .precision(Precision.INEXACT)
                    .apply {
                        if (BuildConfig.DEBUG) {
                            listener(
                                onSuccess = { _, _ -> Log.d("ImageLoad", "${mediaItem.title} loaded successfully") },
                                onError   = { _, r -> Log.e("ImageLoad", "${mediaItem.title} failed: ${r.throwable.message}") }
                            )
                        }
                    }
                    .build()
            }

            AsyncImage(
                model = imageRequest,
                contentDescription = mediaItem.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }


        // 🔹 Rating badge – bottom-left
        if (rating != null && rating > 0.0) {
            RatingBadge(
                rating = rating,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.1.dp, bottom = 15.3.dp)
            )
        }

        // 🔹 Release date – own positioning
        if (!releaseDateText.isNullOrBlank()) {
            Text(
                text = releaseDateText,
                color = Color(0xFFBBBBBB),
                fontSize = 12.sp,
                fontFamily = dongleBold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    //.padding(start = 5.dp, end = (-40).dp, bottom = 18.5.dp)
                    .padding(end = 12.dp, bottom = 18.8.dp)
            )
        }

        // 🔹 Runtime – *separate* positioning
        if (!runTimeText.isNullOrBlank()) {
            Text(
                text = runTimeText,
                color = Color(0xFFBBBBBB),
                fontSize = 6.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 13.dp, bottom = 15.3.dp)
            )
        }

        // 🔹 Seasons badge – bottom-right (TV shows only, replaces runtime spot)
        if (!seasonsText.isNullOrBlank()) {
            Text(
                text = seasonsText,
                color = Color(0xFFBBBBBB),
                fontSize = 6.5.sp,
                fontFamily = dongleBold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 13.dp, bottom = 15.3.dp)
            )
        }

    }
}


@Composable
fun RatingBadge(
    rating: Double,
    modifier: Modifier = Modifier
) {
    val ratingText = remember(rating) {
        String.format(Locale.US, "%.1f", rating)
    }

    Box(
        modifier = modifier
            // ⬇️ 16x16dp, rounded, solid #262629, stroke 1.5dp #B0000000
            .size(16.5.dp)
            .clip(AppShapes.ratingBadge)
            .border(width = 1.8.dp, color = Color(0xB0000000), shape = AppShapes.ratingBadge)
            .background(Color(0xFF262629)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = ratingText,
            fontSize = 7.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}
