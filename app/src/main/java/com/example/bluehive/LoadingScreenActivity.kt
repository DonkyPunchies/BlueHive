package com.example.bluehive

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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
import androidx.lifecycle.lifecycleScope
import com.example.bluehive.update.UpdateGate
import kotlinx.coroutines.launch
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

/**
 * Total wall-clock the update answer gets, measured from onCreate — NOT from the
 * moment the warm-up finishes.
 *
 * WHY THIS REPLACED A 5s WAIT. The old constant was 5s spent AFTER AppWarmup, and
 * it silently lost the race on the launch that matters most. UpdateManager's
 * manifest client is deliberately allowed 20s, because a cold DNS lookup plus a
 * TLS handshake to a remote host does not reliably fit in less. The loading
 * screen was giving that 20s job as little as 6.5s: AppWarmup is entirely
 * best-effort and swallows every network failure, so on a launch where the
 * network has not settled yet it returns almost immediately and the screen ends
 * at its LOADING_MIN_DISPLAY_MS floor.
 *
 * That is exactly the first launch after an install — slowest possible fetch,
 * fastest possible warm-up — so the update was missed precisely when it was most
 * likely to exist, and only the home-screen fallback caught it, one profile pick
 * later. The next launch found it immediately because DNS and TLS were warm,
 * which is what makes the bug look like luck.
 *
 * MEASURED FROM onCreate SO THE TWO BUDGETS DO NOT ADD UP. The fetch starts at
 * onCreate, so any time AppWarmup spends is time the fetch already had. Waiting
 * this long again afterwards would make a broken network cost AppWarmup's 20s
 * cap PLUS this one. Subtracting the elapsed time keeps the whole launch inside
 * one ceiling instead of the sum of two.
 *
 * SLIGHTLY ABOVE THE FETCH'S OWN 20s, on purpose. The intent is that a fetch
 * which completes is never cut off, so this stops being a policy about how long
 * an update check may take and becomes only a backstop against one that hangs
 * past its own timeout. It exists so the launch path can never wait forever, not
 * to decide anything.
 *
 * In the normal case none of this is spent: the answer is already in hand when
 * the warm-up ends and the await returns instantly. It only elapses against a
 * black-holed network — one that accepts a connection and then says nothing.
 * Airplane mode and an ordinary outage both fail in milliseconds.
 */
private const val GATE_TOTAL_BUDGET_MS = 22_000L

class LoadingScreenActivity : ComponentActivity() {

    /**
     * When the manifest fetch was started, so the wait for it can be charged
     * against the time the warm-up already spent rather than added to it.
     *
     * elapsedRealtime, not wall clock: these boxes sync their time shortly after
     * boot, and a clock that steps backwards mid-launch would inflate the
     * remaining budget instead of shrinking it.
     */
    private var prefetchStartedAt = 0L

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

        // FETCH THE MANIFEST NOW, IN PARALLEL WITH THE WARM-UP.
        //
        // The loading screen is time the user already spends, and the manifest is
        // a few kilobytes, so the answer is normally back well before this screen
        // is finished — at no cost to how long the launch takes. An earlier
        // version of this check blocked the launch behind a 4s timeout and
        // skipped the update on 4 of 6 cold starts; starting it here instead
        // means the wait later is only ever for what has not arrived yet.
        //
        // The timestamp is taken FIRST so the fetch is credited with every
        // millisecond it actually gets — see GATE_TOTAL_BUDGET_MS.
        prefetchStartedAt = SystemClock.elapsedRealtime()
        UpdateGate.prefetch()

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
                        // THE UPDATE GATE, WHERE IT COSTS THE USER NOTHING.
                        //
                        // A required update belongs here rather than after the
                        // profile picker. This is already a routing decision, so
                        // it is a branch and not a timer: the user is sent to the
                        // updater instead of the profile screen, rather than
                        // reaching one and having it pulled away a few seconds
                        // later — which is what the home-screen check did, and it
                        // read as the app failing rather than updating.
                        //
                        // Returns false when the prefetch found no update OR has
                        // not answered yet. Both mean the same thing here: carry
                        // on. HomeScreenCompose still runs its own check, so a
                        // slow answer costs a later prompt, never a missed one.
                        // AWAITED, not sampled. The check was started in
                        // onCreate and has normally been done for seconds by the
                        // time the animation ends, so this returns instantly.
                        //
                        // WHAT IS LEFT OF THE BUDGET, not a fresh timer. The
                        // fetch has been running since onCreate, so the warm-up's
                        // elapsed time is time it already had; charging it again
                        // here is what let a 20s-capable fetch be abandoned after
                        // 6.5s. Subtracting instead keeps the launch inside one
                        // ceiling rather than the sum of two, and coerceAtLeast
                        // keeps a warm-up that overran the whole budget from
                        // passing a negative timeout.
                        val gateWaitMs = (GATE_TOTAL_BUDGET_MS -
                            (SystemClock.elapsedRealtime() - prefetchStartedAt))
                            .coerceAtLeast(0L)
                        lifecycleScope.launch {
                            if (UpdateGate.awaitAndRoute(this@LoadingScreenActivity, gateWaitMs)) {
                                Log.d("SessionCheck", "→ Update required — routing to the updater")
                            } else {
                                Log.d("SessionCheck", "→ Routing to ProfileScreenActivity")
                                startActivity(Intent(this@LoadingScreenActivity, ProfileScreenActivity::class.java))
                            }
                            finish()
                        }
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
            // LOOP. The clip is 3.0s (90 frames at 30fps) but this screen stays
            // up until AppWarmup finishes -- MIN_DISPLAY alone is 1.5s and a cold
            // start is routinely well past 3s -- so with REPEAT_MODE_OFF the
            // animation ran once and then held its final frame for the rest of
            // the wait. Most of what anyone actually saw was a still logo.
            //
            // Safe to loop: the first and last frames are the same image (mean
            // difference 0.2/255), so the wrap is invisible rather than a visible
            // snap back to the start.
            repeatMode = Player.REPEAT_MODE_ALL
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