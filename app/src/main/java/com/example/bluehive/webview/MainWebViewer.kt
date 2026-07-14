package com.example.bluehive.webview

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.bluehive.R
import org.mozilla.geckoview.*
import android.os.Build
import androidx.compose.ui.layout.onSizeChanged
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.api.LockoutBus
import com.example.bluehive.diagnostics.CrashReporter
import kotlinx.coroutines.launch

class MainWebViewer : AppCompatActivity() {

    companion object {
        private const val TAG = "MainWebViewer"
        private const val SESSION_ID = "main_webviewer"
    }

    private var geckoSession: GeckoSession? = null
    private var isFullscreen = false
    private var hasRetried = false
    private var isSessionAttached = false
    private var shutdownInitiated = false
    private var contentGoneReported = false   // #3 report a content-process death only once

    private var sourceUrl: String? = null
    private var sourceName: String? = null
    private var profileId: Int = -1
    private var mediaId: Int = 0
    private var mediaType: String = "movie"
    // The watch_history row id returned by logWatchHistory (session start).
    // Held so initiateGracefulShutdown() can close the session via /stop.
    @Volatile private var activeWatchId: Int? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Shut down GeckoView immediately when the workspace locks out or a
    // session is revoked. MainWebViewer runs in its own task
    // (taskAffinity="com.bluehive.tv.video") so FLAG_ACTIVITY_CLEAR_TASK
    // on the main task never reaches it — this listener is the only
    // reliable way to close it from outside.
    private val lockoutListener: (String?) -> Unit = { reason ->
        Log.d(TAG, "🔒 Lockout received (reason=$reason) — shutting down GeckoView")
        initiateGracefulShutdown()
    }
    private var webViewController: WebViewController? = null
    private var geckoViewRef: GeckoView? = null
    private var seasonNumber:  Int? = null
    private var episodeNumber: Int? = null
    private var mediaTitle:   String? = null
    private var episodeName:  String? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sourceName = intent.getStringExtra("SOURCE_NAME")
        sourceUrl  = intent.getStringExtra("SOURCE_URL")
        profileId      = intent.getIntExtra("PROFILE_ID", -1)
        mediaId        = intent.getStringExtra("MEDIA_ID")?.toIntOrNull() ?: 0
        mediaType      = intent.getStringExtra("MEDIA_TYPE") ?: "movie"
        seasonNumber   = intent.getIntExtra("SEASON_NUMBER", 0).takeIf { it > 0 }
        episodeNumber  = intent.getIntExtra("EPISODE_NUMBER", 0).takeIf { it > 0 }
        mediaTitle    = intent.getStringExtra("MEDIA_TITLE")
        episodeName   = intent.getStringExtra("EPISODE_NAME")


        // ── Pre-launch memory cleanup ─────────────────────────────────
        // Clear Coil caches before GeckoView spawns its child process.
        // This frees ~25-50MB of decoded bitmaps and disk cache that
        // the details/search screens accumulated.
        try {
            BlueHiveApplication.coilImageLoader.memoryCache?.clear()
            Log.d(TAG, "🧹 Cleared Coil memory cache before stream launch")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Cache clear failed (non-fatal): ${e.message}")
        }
        System.gc()


        // Fire-and-forget — log this watch event immediately on stream start
        if (profileId != -1 && mediaId != 0) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val started = com.example.bluehive.api.ApiClient.bluehiveApi.logWatchHistory(
                        com.example.bluehive.api.WatchHistoryRequest(
                            profile_id      = profileId,
                            media_tmdb_id   = mediaId,
                            media_type      = mediaType,
                            source_name     = sourceName ?: "unknown",
                            media_title     = mediaTitle,
                            season_number   = seasonNumber,
                            episode_number  = episodeNumber,
                            episode_name    = episodeName,
                        )
                    )
                    activeWatchId = started.id
                    Log.d(TAG, "✅ Watch history logged: id=${started.id} profileId=$profileId mediaId=$mediaId")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Watch history log failed (non-fatal): ${e.message}")
                }
            }
        }

        setContent {
            MainWebViewerScreen(
                sourceName = sourceName,
                isFullscreen = isFullscreen,
                onReady = { geckoView, controller ->
                    geckoViewRef = geckoView
                    webViewController = controller
                    mainHandler.postDelayed({
                        if (!isFinishing && !shutdownInitiated && GeckoWebViewManager.isReady()) {
                            setupNewSession(geckoView)
                        }
                    }, 350)
                }
            )
        }

        LockoutBus.register(lockoutListener)

        setupFullscreen()
        setupBackNavigation()
    }

    private fun setupNewSession(geckoView: GeckoView) {
        if (shutdownInitiated || isFinishing) return

        geckoSession = GeckoWebViewManager.getNewSession(SESSION_ID, this)
        if (geckoSession == null) {
            Log.e(TAG, "❌ Failed to get session!")
            finish()
            return
        }

        try {
            geckoView.setSession(geckoSession!!)
            webViewController?.setSession(geckoSession)
            geckoSession?.setActive(true)
            isSessionAttached = true
            Log.d(TAG, "✅ Session attached + active")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error attaching session", e)
            finish()
            return
        }

        setupDelegates()

        mainHandler.postDelayed({
            if (!shutdownInitiated && !isFinishing && isSessionAttached) {
                loadStreamingUrl()
            }
        }, 250)
    }

    private fun loadStreamingUrl() {
        sourceUrl?.let { url ->
            Log.d(TAG, "🌐 Loading: $url")
            geckoSession?.loadUri(url)
        } ?: finish()
    }

    private fun setupDelegates() {
        geckoSession?.apply {
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(
                    session: GeckoSession,
                    url: String?,
                    perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                    hasUserGesture: Boolean
                ) {}

                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoResult<AllowOrDeny> {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }

                override fun onLoadError(
                    session: GeckoSession,
                    uri: String?,
                    error: WebRequestError
                ): GeckoResult<String>? {
                    if (error.category == WebRequestError.ERROR_CATEGORY_NETWORK && !hasRetried) {
                        hasRetried = true
                        mainHandler.postDelayed({ geckoSession?.reload() }, 1000)
                    }
                    return null
                }
            }

            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {}
                override fun onPageStop(session: GeckoSession, success: Boolean) {}
            }

            contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                    if (!isFinishing && !shutdownInitiated) {
                        isFullscreen = fullScreen
                    }
                }

                // #3 The content (child) process CRASHED. The MAIN app process is
                // still alive here, so we can report + recover immediately.
                override fun onCrash(session: GeckoSession) {
                    Log.e(TAG, "💥 Gecko content process CRASHED")
                    handleContentProcessGone(killed = false)
                }

                // #3 The system KILLED the content process — almost always the Low
                // Memory Killer reaping it (the exact event behind the "app just
                // vanished" OOM). This is our ONLY hook to see it: no Kotlin
                // exception is thrown, so the crash reporter is otherwise blind.
                override fun onKill(session: GeckoSession) {
                    Log.e(TAG, "☠️ Gecko content process KILLED by system (likely OOM)")
                    handleContentProcessGone(killed = true)
                }
            }

            permissionDelegate = object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm: GeckoSession.PermissionDelegate.ContentPermission
                ): GeckoResult<Int> {
                    return GeckoResult.fromValue(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    )
                }

                override fun onMediaPermissionRequest(
                    session: GeckoSession,
                    uri: String,
                    video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                    audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                    callback: GeckoSession.PermissionDelegate.MediaCallback
                ) {
                    if (!isFinishing && !shutdownInitiated) {
                        callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                    }
                }
            }
        }
    }

    private fun setupFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    geckoSession?.exitFullScreen()
                } else {
                    initiateGracefulShutdown()
                }
            }
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isFinishing && !shutdownInitiated) {
            val handled = webViewController?.handleKeyEvent(event) == true
            if (handled) return true
        }
        return super.dispatchKeyEvent(event)
    }

    // #3 Content-process death handler (crash or system kill). Two jobs:
    //   (b) REPORT it — the main process is alive, so upload right now (not on
    //       next launch). This is the only field visibility we get into these
    //       OOM/crash events; the crash reporter never sees them (no exception).
    //   (a) RECOVER gracefully — the content is gone, so close the defunct session
    //       and return to the previous screen instead of stranding the user on a
    //       dead webview. Replaying starts a fresh session.
    private fun handleContentProcessGone(killed: Boolean) {
        if (!contentGoneReported) {
            contentGoneReported = true
            val host = runCatching { android.net.Uri.parse(sourceUrl).host }.getOrNull()
            CrashReporter.reportContentProcessGone(
                applicationContext,
                killed = killed,
                extraMeta = mapOf(
                    "source" to "webview_content_process",
                    "stream_host" to (host ?: "unknown"),
                    "was_fullscreen" to isFullscreen,
                ),
            )
        }
        if (!shutdownInitiated) {
            initiateGracefulShutdown()
        }
    }

    private fun initiateGracefulShutdown() {
        if (shutdownInitiated) return
        shutdownInitiated = true

        // Close the watch session server-side: stamps stopped_at and computes
        // progress_seconds. Fire-and-forget on IO so it can't block teardown.
        // Uses NonCancellable + the app scope (not a per-activity scope) so the
        // request still completes even though the activity is finishing.
        activeWatchId?.let { watchId ->
            activeWatchId = null
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    com.example.bluehive.api.ApiClient.bluehiveApi.stopWatchHistory(watchId)
                    Log.d(TAG, "✅ Watch session stopped: id=$watchId")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Watch stop failed (non-fatal): ${e.message}")
                }
            }
        }

        try {
            geckoSession?.close()
            GeckoWebViewManager.closeSession(SESSION_ID, this)
            geckoSession = null

            mainHandler.removeCallbacksAndMessages(null)
            webViewController?.cleanup()
            webViewController = null

            System.gc()
            Log.d(TAG, "✅ Shutdown complete")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Shutdown error", e)
        }

        finish()
    }



    override fun onPause() {
        super.onPause()
        if (!isFinishing && isSessionAttached && !shutdownInitiated) {
            geckoSession?.setActive(false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing && !shutdownInitiated && isSessionAttached) {
            geckoSession?.setActive(true)
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the player — pressing Home, or another activity fully covering
        // us — tears the Gecko session DOWN instead of leaving its content process
        // (hundreds of MB) alive in the background. That lingering process is a
        // top driver of the slow memory compounding that lets the Low Memory
        // Killer reap BlueHive on 2 GB boxes after long uptimes.
        //
        // Guarded so a normal Back-exit (which already ran initiateGracefulShutdown
        // → shutdownInitiated = true) doesn't double-fire. onStop only reaches here
        // when the user genuinely backgrounded the player; returning to BlueHive
        // lands on the previous screen, and replaying starts a fresh session.
        if (!isFinishing && !shutdownInitiated) {
            Log.i(TAG, "▶️ Player backgrounded (onStop) — tearing down session to free memory")
            initiateGracefulShutdown()
        }
    }

    override fun onDestroy() {
        LockoutBus.unregister(lockoutListener)
        if (!shutdownInitiated) {
            initiateGracefulShutdown()
        }
        super.onDestroy()
    }
}

@Composable
fun MainWebViewerScreen(
    sourceName: String?,
    isFullscreen: Boolean,
    onReady: (GeckoView, WebViewController) -> Unit
) {
    var geckoView by remember { mutableStateOf<GeckoView?>(null) }

    // Cursor UI state (PX)
    var cursorX by remember { mutableFloatStateOf(0f) }
    var cursorY by remember { mutableFloatStateOf(0f) }
    var cursorVisible by remember { mutableStateOf(false) }
    var clickPulse by remember { mutableStateOf(false) }

    val cursorAlpha by animateFloatAsState(if (cursorVisible) 1f else 0f, label = "cursorAlpha")
    val scale by animateFloatAsState(if (clickPulse) 0.85f else 1f, label = "cursorScale")

    val density = LocalDensity.current

    val cursorSizeDp = 20.dp
    val cursorSizePx = remember(cursorSizeDp, density) { with(density) { cursorSizeDp.toPx() } }

    // Overlay size in PX (Compose gives PX in onSizeChanged)
    var overlayW by remember { mutableIntStateOf(0) }
    var overlayH by remember { mutableIntStateOf(0) }




    // =================================================================================> control the position of the ui click
    val hotspotOffsetYPx = with(density) { (-5).dp.toPx() }
    val hotspotOffsetXPx = with(density) { -8.dp.toPx() }
    // Create controller once when GeckoView exists
    val controller = remember(geckoView) {
        geckoView?.let { gv ->
            WebViewController(
                geckoView = gv,
                cursorSizePx = cursorSizePx,
                setCursorPosPx = { x, y -> cursorX = x; cursorY = y },
                setCursorVisible = { visible -> cursorVisible = visible },
                pulseClick = {
                    clickPulse = true
                    // quick reset
                    Handler(Looper.getMainLooper()).postDelayed({ clickPulse = false }, 120)
                }
            ).also {
                it.setSourceName(sourceName)
                it.setHotspotOffsetPx(hotspotOffsetXPx, hotspotOffsetYPx) // ✅ right here
            }
        }
    }

    // Inform controller of overlay size
    LaunchedEffect(controller, overlayW, overlayH) {
        controller?.setOverlaySizePx(overlayW, overlayH)
    }

    // Notify Activity when ready
    LaunchedEffect(geckoView, controller) {
        val gv = geckoView
        val c = controller
        if (gv != null && c != null) {
            onReady(gv, c)
        }
    }


    val initialYOffsetPx = with(density) { 10.dp.toPx() } // ====================================== ✅ move down 40dp
    val initialXOffsetPx = 0f

    DisposableEffect(controller, initialXOffsetPx, initialYOffsetPx) {
        controller?.setInitialCursorOffsetPx(initialXOffsetPx, initialYOffsetPx)
        onDispose { }
    }




    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                overlayW = size.width
                overlayH = size.height
            }
    ) {

        // GeckoView
        AndroidView(
            factory = { ctx ->
                GeckoView(ctx).apply {
                    geckoView = this
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Fullscreen overlay placeholder (kept for your future logic)
        if (isFullscreen) {
            Box(modifier = Modifier.fillMaxSize())
        }

        // Cursor overlay (Compose Image) — this is now in the SAME coordinate space as the Box
        Image(
            painter = painterResource(id = R.drawable.target),
            contentDescription = "Cursor",
            modifier = Modifier
                .size(cursorSizeDp)
                .graphicsLayer {
                    translationX = cursorX
                    translationY = cursorY
                    scaleX = scale
                    scaleY = scale
                    alpha = cursorAlpha
                }
                .align(Alignment.TopStart)
        )
    }
}
