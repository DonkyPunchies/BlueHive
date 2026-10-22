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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.bluehive.latestTrailersComponents.trailerViewer.DownloaderImpl
import com.example.bluehive.latestTrailersComponents.trailerViewer.NewPipeExtractorViewer
import com.example.bluehive.webview.exoplayer_resources.ExoPlayerConfig
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
    // Told after every Click, with whether the trailer is now playing, so the
    // panel can re-show its "Click to pause / to play" hint with the right word.
    onPlayPauseToggled: (Boolean) -> Unit = {},
    // Told when the first picture is on screen, or that none is coming. The
    // panel holds its hint while "Loading trailer..." shows and only starts the
    // fade from here -- timed from the click, it was gone before the trailer
    // ever appeared (measured on the onn box, 2026-10-22).
    onStarted: () -> Unit = {},
    onFailed: () -> Unit = {},
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
                // No embedded text track ever gets drawn — see the helper.
                ExoPlayerConfig.disableTextTracks(this)
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

    val startedCallback = rememberUpdatedState(onStarted)
    val failedCallback  = rememberUpdatedState(onFailed)
    val toggledCallback = rememberUpdatedState(onPlayPauseToggled)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { startedCallback.value() }

            // ERRORS AFTER prepare() ARRIVE HERE, NOT IN THE try/catch BELOW,
            // which only sees what throws synchronously. A stream that resolves
            // and is then refused (a 403 from the video host, a decoder that
            // gives up) used to leave a black picture with no message, and the
            // panel's hint held "Click to pause" over it forever.
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error after start: ${error.errorCodeName}", error)
                uiState = PlaybackUiState.Error("Trailer failed to play")
                failedCallback.value()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // PAUSE WHEN THE APP GOES TO THE BACKGROUND. Nothing did, and ExoPlayer here
    // takes no audio focus, so pressing Home mid-trailer left its sound playing
    // behind the launcher. Paused rather than closed, so coming back finds the
    // trailer where it was, with the panel's "Click to play" hint up.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && player.playWhenReady) {
                player.pause()
                toggledCallback.value(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                        // KEY REPEATS ARE IGNORED. Holding Click -- including
                        // the press that started this trailer, whose repeats
                        // land here once focus arrives -- flipped play/pause
                        // every ~50 ms and restarted the panel's hint each time.
                        if (event.nativeKeyEvent.repeatCount > 0) return@onKeyEvent true
                        // Nothing to pause in a trailer that failed to load.
                        if (uiState is PlaybackUiState.Error) return@onKeyEvent true
                        // A trailer that has run to its end plays again from the
                        // start; play() alone does nothing once it has ended.
                        if (player.playbackState == Player.STATE_ENDED) {
                            player.seekTo(0)
                            player.play()
                            onPlayPauseToggled(true)
                            return@onKeyEvent true
                        }
                        // playWhenReady, NOT isPlaying: isPlaying is false while
                        // the stream resolves or buffers, so a Click then called
                        // play() -- a no-op -- and the trailer could not be paused
                        // until its first frame was on screen.
                        val nowPlaying = !player.playWhenReady
                        if (nowPlaying) player.play() else player.pause()
                        onPlayPauseToggled(nowPlaying)
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
            failedCallback.value()
            return@LaunchedEffect
        }

        try {
            player.setMediaItem(MediaItem.fromUri(streamUrl))
            // NO play() AFTER prepare(). playWhenReady is already true from the
            // builder, so the trailer starts by itself once ready -- and a Click
            // that paused it while the link was still resolving stays paused. An
            // explicit play() here overrode that pause.
            player.prepare()
            uiState = PlaybackUiState.Playing
            Log.d(TAG, "Playing stream URL: $streamUrl")
        } catch (e: Exception) {
            uiState = PlaybackUiState.Error("Playback error")
            Log.e(TAG, "Playback error: ${e.message}", e)
            failedCallback.value()
        }
    }
}
