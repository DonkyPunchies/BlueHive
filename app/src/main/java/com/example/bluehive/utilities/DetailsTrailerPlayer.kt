package com.example.bluehive.utilities

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.bluehive.latestTrailersComponents.trailerViewer.NewPipeExtractorViewer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

// Seek amount in milliseconds
private const val SEEK_INCREMENT_MS = 10_000L  // 10 seconds

@UnstableApi
@Composable
fun DetailsTrailerPlayer(
    trailerUrl: String?,
    isPlaying: Boolean,
    showPlayerView: Boolean,                 // 👈 NEW: controls visibility of PlayerView
    shouldExtract: Boolean,
    onPlaybackStateChanged: (Boolean) -> Unit,
    onPlayerReady: (ExoPlayer?) -> Unit = {},  // Expose player to parent
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Keep player in remember so it survives recomposition
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var playerReady by remember { mutableStateOf(false) }




    // Extract stream URL when trailer URL is provided (only once)
    LaunchedEffect(trailerUrl, shouldExtract) {
        if (trailerUrl != null && streamUrl == null && !isLoading && shouldExtract) {
            isLoading = true
            Log.d("DetailsTrailerPlayer", "🚀 Starting extraction (user requested)")

            scope.launch(Dispatchers.IO) {
                val videoId = extractVideoId(trailerUrl)
                if (videoId.isEmpty()) {
                    Log.e("DetailsTrailerPlayer", "Could not extract video ID from: $trailerUrl")
                    isLoading = false
                    return@launch
                }

                Log.d("DetailsTrailerPlayer", "Extracting stream for video ID: $videoId")

                var url = tryInvidiousExtraction(videoId)

                if (url == null) {
                    Log.d("DetailsTrailerPlayer", "Invidious failed, trying NewPipe...")
                    // Lazily init NewPipe only when the fallback is actually needed.
                    // Moved off LaunchedEffect(Unit) so simply composing this player
                    // (which happens on every details entry) no longer spins up
                    // NewPipe + its cookie jar during the screen-load frame storm.
                    try {
                        NewPipe.init(NewPipeExtractorViewer.getDownloader())
                        Log.d("DetailsTrailerPlayer", "NewPipe initialized (lazy)")
                    } catch (e: Exception) {
                        Log.e("DetailsTrailerPlayer", "NewPipe already initialized or failed", e)
                    }
                    url = tryNewPipeExtraction(trailerUrl)
                }

                withContext(Dispatchers.Main) {
                    if (url != null) {
                        Log.d("DetailsTrailerPlayer", "Stream URL obtained: $url")
                        streamUrl = url
                    } else {
                        Log.e("DetailsTrailerPlayer", "Failed to extract stream URL")
                    }
                    isLoading = false
                }
            }
        }
    }

    // Create ExoPlayer when streamUrl becomes available
    LaunchedEffect(streamUrl) {
        if (streamUrl != null && exoPlayer == null) {
            Log.d("DetailsTrailerPlayer", "Creating ExoPlayer with stream: $streamUrl")

            val player = ExoPlayer.Builder(ctx).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 1f

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                Log.d("DetailsTrailerPlayer", "Player ready")
                                playerReady = true
                            }
                            Player.STATE_BUFFERING -> Log.d("DetailsTrailerPlayer", "Buffering...")
                            Player.STATE_ENDED -> Log.d("DetailsTrailerPlayer", "Playback ended")
                            Player.STATE_IDLE -> Log.d("DetailsTrailerPlayer", "Player idle")
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d("DetailsTrailerPlayer", "isPlaying changed in player: $isPlaying")
                        // 🚫 Don't call onPlaybackStateChanged(isPlaying) anymore
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("DetailsTrailerPlayer", "Playback error: ${error.message}", error)
                    }

                    override fun onRenderedFirstFrame() {
                        Log.d("DetailsTrailerPlayer", "First frame rendered!")
                    }
                })

                setMediaItem(MediaItem.fromUri(streamUrl!!))
                prepare()
                playWhenReady = false  // 👈 don't autostart
            }
            exoPlayer = player
            onPlayerReady(player)
        }
    }

    // Handle play/pause based on isPlaying state
    LaunchedEffect(isPlaying, playerReady) {
        if (playerReady && exoPlayer != null) {
            if (isPlaying) {
                Log.d("DetailsTrailerPlayer", "▶️ Playing")
                exoPlayer?.play()
            } else {
                Log.d("DetailsTrailerPlayer", "⏸️ Pausing")
                exoPlayer?.pause()
            }
        }
    }

    // Cleanup when composable leaves composition
    DisposableEffect(Unit) {
        onDispose {
            Log.d("DetailsTrailerPlayer", "Disposing player")
            exoPlayer?.stop()
            exoPlayer?.release()
            exoPlayer = null
        }
    }

    // 👇 ONLY show PlayerView if:
    // - we actually have a player
    // - AND the parent says it should be visible (hasTrailerBeenPlayed)
    if (exoPlayer != null && showPlayerView) {
        Box(
            modifier = modifier
                .width(519.dp)
                .height(292.dp)
                .clip(AppShapes.bottomLeftRoundedShape)
        ) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        keepScreenOn = true
                    }
                },
                update = { playerView ->
                    playerView.player = exoPlayer
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// SEEK HELPERS
fun seekForward(player: ExoPlayer?) {
    if (player == null) return

    val newPosition = (player.currentPosition + SEEK_INCREMENT_MS)
        .coerceAtMost(player.duration)
    player.seekTo(newPosition)
    Log.d("DetailsTrailerPlayer", "⏩ Seek forward to ${newPosition / 1000}s")
}


fun seekBackward(player: ExoPlayer?) {
    if (player == null) return

    val newPosition = (player.currentPosition - SEEK_INCREMENT_MS)
        .coerceAtLeast(0)
    player.seekTo(newPosition)
    Log.d("DetailsTrailerPlayer", "⏪ Seek backward to ${newPosition / 1000}s")
}


// --- helpers (unchanged) ---
private fun extractVideoId(url: String): String {
    return when {
        url.contains("watch?v=") -> url.substringAfter("watch?v=").substringBefore("&")
        url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
        url.contains("/embed/") -> url.substringAfter("/embed/").substringBefore("?")
        else -> ""
    }
}

private suspend fun tryInvidiousExtraction(videoId: String): String? {
    return withContext(Dispatchers.IO) {
        val invidiousInstances = listOf(
            "https://inv.bp.projectsegfau.lt",
            "https://invidious.fdn.fr",
            "https://invidious.privacyredirect.com"
        )

        for (instance in invidiousInstances) {
            try {
                val url = "$instance/api/v1/videos/$videoId"
                Log.d("DetailsTrailerPlayer", "Trying Invidious: $url")

                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val jsonString = response.body?.string() ?: continue
                    val json = JSONObject(jsonString)

                    val formatStreams = json.optJSONArray("formatStreams")
                    if (formatStreams != null && formatStreams.length() > 0) {
                        var selectedUrl: String? = null
                        var selectedQuality = ""

                        for (i in 0 until formatStreams.length()) {
                            val stream = formatStreams.getJSONObject(i)
                            val quality = stream.optString("qualityLabel", "")
                            val streamUrl = stream.optString("url", "")

                            if (streamUrl.isNotEmpty()) {
                                when {
                                    quality.contains("720p") && selectedQuality != "720p" -> {
                                        selectedUrl = streamUrl
                                        selectedQuality = "720p"
                                    }
                                    quality.contains("480p") && selectedQuality != "720p" && selectedQuality != "480p" -> {
                                        selectedUrl = streamUrl
                                        selectedQuality = "480p"
                                    }
                                    quality.contains("360p") && selectedUrl == null -> {
                                        selectedUrl = streamUrl
                                        selectedQuality = "360p"
                                    }
                                }
                            }
                        }

                        if (selectedUrl != null) {
                            Log.d("DetailsTrailerPlayer", "Found stream: $selectedQuality")
                            return@withContext selectedUrl
                        }
                    }

                    val adaptiveFormats = json.optJSONArray("adaptiveFormats")
                    if (adaptiveFormats != null) {
                        for (i in 0 until adaptiveFormats.length()) {
                            val stream = adaptiveFormats.getJSONObject(i)
                            val mimeType = stream.optString("type", "")
                            if (mimeType.contains("video/mp4") && stream.has("url")) {
                                return@withContext stream.getString("url")
                            }
                        }
                    }
                }
                response.close()
            } catch (e: Exception) {
                Log.w("DetailsTrailerPlayer", "Invidious $instance failed: ${e.message}")
            }
        }
        null
    }
}

private suspend fun tryNewPipeExtraction(youtubeUrl: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            Log.d("DetailsTrailerPlayer", "Starting NewPipe extraction...")
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, youtubeUrl)

            val videoStreams = streamInfo.videoStreams
            if (videoStreams.isNullOrEmpty()) {
                Log.e("DetailsTrailerPlayer", "No video streams found")
                return@withContext null
            }

            val selectedStream = videoStreams.firstOrNull { stream ->
                stream.getResolution().contains("720p") && stream.content != null
            } ?: videoStreams.firstOrNull { stream ->
                stream.getResolution().contains("480p") && stream.content != null
            } ?: videoStreams.firstOrNull { stream ->
                stream.content != null
            }

            selectedStream?.content
        } catch (e: Exception) {
            Log.e("DetailsTrailerPlayer", "NewPipe extraction failed: ${e.message}")
            null
        }
    }
}
