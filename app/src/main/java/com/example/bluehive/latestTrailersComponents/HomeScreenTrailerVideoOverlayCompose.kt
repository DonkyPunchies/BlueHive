package com.example.bluehive.latestTrailersComponents

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.bluehive.latestTrailersComponents.trailerViewer.DownloaderImpl
import com.example.bluehive.latestTrailersComponents.trailerViewer.NewPipeExtractorViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "TrailerVideoOverlay"

private sealed class PlaybackUiState {
    data object Loading : PlaybackUiState()
    data object Playing : PlaybackUiState()
    data class Error(val msg: String) : PlaybackUiState()
}

@OptIn(UnstableApi::class)
@Composable
fun HomeScreenTrailerVideoOverlayCompose(
    youtubeUrl: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    if (!visible) return

    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    var uiState by remember { mutableStateOf<PlaybackUiState>(PlaybackUiState.Loading) }

    fun buildPlaybackDataSourceFactory(): DataSource.Factory {
        val defaultHeaders = mapOf(
            "User-Agent" to DownloaderImpl.USER_AGENT,
            "Accept-Language" to "en-US,en;q=0.9",
        )

        return OkHttpDataSource.Factory(NewPipeExtractorViewer.getDownloader().okHttpClient())
            .setDefaultRequestProperties(defaultHeaders)
    }

    val player = remember {
        val dataSourceFactory = buildPlaybackDataSourceFactory()
        val mediaSourceFactory =
            DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                player.stop()
                player.release()
            } catch (_: Exception) {}
        }
    }

    BackHandler(enabled = true) { onClose() }

    Box(
        modifier = modifier
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Back -> {
                        onClose()
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        if (player.isPlaying) player.pause() else player.play()
                        true
                    }
                    Key.DirectionLeft,
                    Key.DirectionRight,
                    Key.DirectionUp,
                    Key.DirectionDown -> true
                    else -> false // ✅ don't swallow everything
                }
            }
            .focusable()
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(it).apply {
                    useController = false
                    this.player = player
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    keepScreenOn = true
                }
            },
            update = { view -> view.player = player }
        )

        when (val s = uiState) {
            is PlaybackUiState.Loading -> Text(
                text = "Loading trailer...",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
            is PlaybackUiState.Error -> Text(
                text = s.msg,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
            else -> Unit
        }
    }

    LaunchedEffect(youtubeUrl) {
        uiState = PlaybackUiState.Loading
        focusRequester.requestFocus()

        val streamUrl = withContext(Dispatchers.IO) {
            NewPipeExtractorViewer.resolveStreamUrl(youtubeUrl)
        }

        if (streamUrl.isNullOrBlank()) {
            uiState = PlaybackUiState.Error("Trailer failed to load")
            Log.e(TAG, "No playable stream URL resolved for: $youtubeUrl")
            return@LaunchedEffect
        }

        try {
            player.setMediaItem(MediaItem.fromUri(streamUrl))
            player.prepare()
            player.play()
            uiState = PlaybackUiState.Playing
            Log.d(TAG, "Playing stream URL: $streamUrl")
        } catch (e: Exception) {
            uiState = PlaybackUiState.Error("Playback error")
            Log.e(TAG, "Playback error: ${e.message}", e)
        }
    }
}
