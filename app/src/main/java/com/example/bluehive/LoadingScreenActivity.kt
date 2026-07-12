package com.example.bluehive

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import kotlinx.coroutines.withTimeoutOrNull

private const val LOADING_MIN_DISPLAY_MS = 1_500L   // branding video shows at least this long
// Warm-up now includes the FULL trending + trailer image prefetch (both
// sections are profile-agnostic, so the splash is the one place it can happen
// once for everyone). Only a true first install on slow WiFi ever approaches
// this cap — warm launches are all disk hits and exit in a couple of seconds.
// On timeout the prefetch keeps running in AppWarmup's own scope behind the
// profile picker; nothing is abandoned.
private const val LOADING_MAX_WAIT_MS    = 20_000L

class LoadingScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Modern fullscreen: replaces deprecated systemUiVisibility flags
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            LoadingScreen(
                onLoadingComplete = {
                    val session = com.example.bluehive.auth.SessionManager.get()
                    val hasRefresh = session.refreshToken != null
                    val hasAccess  = session.accessToken != null
                    // Host model: a host-provided access token (no refresh token)
                    // counts as authenticated. isAuthenticated already checks
                    // accessToken != null || refreshToken != null.
                    val isAuth     = session.isAuthenticated
                    Log.d("SessionCheck", "=== COLD START SESSION CHECK ===")
                    Log.d("SessionCheck", "isAuthenticated : $isAuth")
                    Log.d("SessionCheck", "hasRefreshToken : $hasRefresh")
                    Log.d("SessionCheck", "hasAccessToken  : $hasAccess")
                    Log.d("SessionCheck", "userId          : ${session.userId}")
                    Log.d("SessionCheck", "================================")
                    if (isAuth) {
                        Log.d("SessionCheck", "→ Routing to ProfileScreenActivity")
                        startActivity(Intent(this, ProfileScreenActivity::class.java))
                        finish()
                    } else {
                        // PHASE 2: BlueHive has no pairing screen of its own —
                        // identity lives in the host. A direct launcher
                        // open with no host-injected token can't do anything
                        // useful, so close and let the user enter via the host.
                        Log.d("SessionCheck", "→ No session and no host token — closing (launch via your host app)")
                        finishAffinity()
                    }
                }
            )
        }


    }
}

@Composable
fun LoadingScreen(onLoadingComplete: () -> Unit) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val videoUri = "android.resource://${context.packageName}/${R.raw.bluehive_loading_4k}".toUri()
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }

    LaunchedEffect(Unit) {
        val app = context.applicationContext as BlueHiveApplication
        val startedAt = System.currentTimeMillis()

        // Wait for the warm-up to actually FINISH — profiles, trending, the
        // Netflix prefetch, and the FULL carousel image prefetch are all done
        // before we leave this screen. No blind timer. Two guards keep it sane:
        //   • MIN_DISPLAY — a fast warm-up can't make the splash flash by.
        //   • MAX_WAIT — genuine dead-network backstop so it can't hang forever;
        //     on timeout the profile screen's own retry banner takes over, and
        //     the image prefetch (which runs in AppWarmup's OWN scope) keeps
        //     trickling into the disk cache — this cancel doesn't kill it.
        val warmupJob = launch { AppWarmup.run(app) }
        withTimeoutOrNull(LOADING_MAX_WAIT_MS) { warmupJob.join() }
        warmupJob.cancel()   // no-op if it finished; cleans up on timeout

        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed < LOADING_MIN_DISPLAY_MS) delay(LOADING_MIN_DISPLAY_MS - elapsed)

        onLoadingComplete()
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // ── Video ──────────────────────────────────────────────────────────
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── "Loading" label + animated dots ───────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Loading",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 3.sp
            )

            AnimatedDots()
        }
    }
}

@Composable
private fun AnimatedDots() {
    val transition = rememberInfiniteTransition(label = "dots")

    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { index ->
            // ✅ Modern keyframes DSL: `using()` replaces the deprecated `with` infix
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1_200
                        0.2f at (index * 200)       using LinearEasing
                        1f   at (index * 200 + 300) using LinearEasing
                        0.2f at (index * 200 + 600) using LinearEasing
                        0.2f at 1_200
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "dot_$index"
            )

            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .alpha(alpha)
                    .background(Color.White)
            )
        }
    }
}