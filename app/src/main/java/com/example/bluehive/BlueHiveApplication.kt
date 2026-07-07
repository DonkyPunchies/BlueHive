package com.example.bluehive

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.bluehive.api.LockoutBus
import com.example.bluehive.api.SessionExpiredBus
import com.example.bluehive.models.MediaItem
import com.example.bluehive.webview.BeeLogo
import com.example.bluehive.webview.GeckoWebViewManager
import com.example.bluehive.auth.DeviceEventStream
import com.example.bluehive.auth.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi


/**
 * BlueHiveApplication - Compose-Optimized
 *
 * Memory Strategy:
 * - Disk Cache: 300MB
 * - Memory Cache: 25MB hard cap
 * - Single unified ImageLoader instance
 */
class BlueHiveApplication : Application(), ImageLoaderFactory {


    private lateinit var sessionExpiredCallback: () -> Unit

    // Tracks the current foreground Activity so a background-posted session-
    // expiry event can tear BlueHive down (finishAffinity → drops back to the
    // host) without needing an Activity reference at the call site. PHASE 2:
    // BlueHive owns no pairing screen, so "session expired" means "close and
    // return to OGD," not "launch LoginScreenActivity."
    private var foregroundActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

    companion object {
        private const val TAG = "BlueHiveApplication"

        // Image cache configuration
        private const val DISK_CACHE_SIZE_MB     = 300L
        // Memory cache is PERCENT-of-heap, not a fixed cap. The old 25 MB hard
        // cap couldn't hold even the visible home screen imagery, so Coil was
        // constantly evicting + re-decoding (thrash = jank). Hardware bitmaps
        // live in graphics memory, not the Java heap, so a bigger cache does
        // NOT cause GC churn. Low-RAM boxes (2 GB onn / Firestick report
        // isLowRamDevice) get the smaller share.
        private const val MEMORY_CACHE_PERCENT         = 0.25
        private const val MEMORY_CACHE_PERCENT_LOW_RAM = 0.15

        lateinit var coilImageLoader: ImageLoader
        private var soundPool: SoundPool? = null
        private var navSoundId: Int = 0
        private var hoverSoundId: Int = 0
        private var popSoundId: Int = 0
        private var backOutSoundId: Int = 0
        private var lastPopPlayTime: Long = 0L
        private var webViewerClickSoundId: Int = 0

        private const val POP_DEBOUNCE_MS = 120L

        fun initSoundPool(context: Context) {
            if (soundPool == null) {
                soundPool = SoundPool.Builder()
                    .setMaxStreams(4)
                    .build()

                navSoundId          = soundPool!!.load(context, R.raw.medianavigation, 1)
                hoverSoundId        = soundPool!!.load(context, R.raw.blue_pop, 1)
                popSoundId          = soundPool!!.load(context, R.raw.pop, 1)
                backOutSoundId      = soundPool!!.load(context, R.raw.backedout, 1)
                webViewerClickSoundId = soundPool!!.load(context, R.raw.webviewerclick, 1)
            }
        }

        fun playWebViewerClickSound() {
            soundPool?.play(webViewerClickSoundId, 0.35f, 0.35f, 0, 0, 1f)
        }

        fun playTitleCardNavigation() {
            soundPool?.play(navSoundId, 0.7f, 0.7f, 0, 0, 1f)
        }

        fun playHoverSound() {
            soundPool?.play(hoverSoundId, 0.1f, 0.1f, 0, 0, 1f)
        }

        fun playClickSound() {
            val now = SystemClock.uptimeMillis()
            if (now - lastPopPlayTime < POP_DEBOUNCE_MS) return
            lastPopPlayTime = now
            soundPool?.play(popSoundId, 0.3f, 0.3f, 0, 0, 1f)
        }

        fun playBackOutSound() {
            soundPool?.play(backOutSoundId, 0.35f, 0.35f, 0, 0, 1f)
        }

        fun releaseSoundPool() {
            soundPool?.release()
            soundPool = null
        }
    }

    // ── Details navigation history (max 8 items) ───────────────────────────────
    private val detailsHistory: ArrayDeque<MediaItem> = ArrayDeque()
    private val MAX_DETAILS_HISTORY = 8
    private val ESTIMATED_MEDIAITEM_BYTES = 12 * 1024

    private fun logHistorySnapshot(source: String) {
        if (detailsHistory.isEmpty()) {
            Log.d("DetailsHistory", "📂 Stack [$source]: (empty)")
            Log.d("DetailsHistory", "💾 Estimated history memory ≈ 0 KB")
            return
        }
        val titles       = detailsHistory.joinToString(" → ") { it.title }
        val items        = detailsHistory.size
        val estimatedKb  = (items * ESTIMATED_MEDIAITEM_BYTES) / 1024.0
        Log.d("DetailsHistory", "📂 Stack [$source]: $titles")
        Log.d("DetailsHistory",
            "💾 Estimated history memory ≈ %.1f KB (%d items @ ~%d bytes each)".format(
                estimatedKb, items, ESTIMATED_MEDIAITEM_BYTES
            )
        )
    }

    fun pushDetailsHistory(media: MediaItem) {
        if (detailsHistory.lastOrNull()?.tmdbId == media.tmdbId) {
            Log.d("DetailsHistory",
                "⚠️ skip duplicate '${media.title}', size=${detailsHistory.size}/${MAX_DETAILS_HISTORY}")
            logHistorySnapshot("push-skip-duplicate")
            return
        }
        detailsHistory.addLast(media)
        if (detailsHistory.size > MAX_DETAILS_HISTORY) {
            val removed = detailsHistory.removeFirst()
            Log.d("DetailsHistory",
                "🧹 '${removed.title}' erased from history (kept ${detailsHistory.size}/${MAX_DETAILS_HISTORY})")
        }
        Log.d("DetailsHistory",
            "➡️ '${media.title}' navigated to (${detailsHistory.size}/${MAX_DETAILS_HISTORY})")
        logHistorySnapshot("push")
    }

    fun popPreviousDetails(): MediaItem? {
        if (detailsHistory.isEmpty()) {
            Log.d("DetailsHistory", "pop: empty, nothing to pop")
            logHistorySnapshot("pop-empty")
            return null
        }
        val previous = detailsHistory.removeLast()
        Log.d("DetailsHistory", "⬅️ Back → navigating to '${previous.title}' (${detailsHistory.size} remaining)")
        logHistorySnapshot("pop")
        return previous
    }

    fun clearDetailsHistory() {
        detailsHistory.clear()
        Log.d("DetailsHistory", "🧼 history wiped, size=${detailsHistory.size}")
        logHistorySnapshot("clear")
    }

    fun historySize(): Int = detailsHistory.size

    // ── Media prefetch cache ───────────────────────────────────────────────────
    data class MediaPrefetchData(
        val tmdbId:          Int,
        val mediaType:       String,
        val episodes:        List<com.example.bluehive.models.Episode>? = null,
        val recommendations: List<MediaItem> = emptyList(),
    )

    private var prefetchCache: MediaPrefetchData? = null

    fun storePrefetch(data: MediaPrefetchData) {
        prefetchCache = data
        Log.d(TAG, "📦 Prefetch stored for tmdbId=${data.tmdbId} (${data.mediaType}), eps=${data.episodes?.size}, recs=${data.recommendations.size}")
    }

    fun consumePrefetch(tmdbId: Int): MediaPrefetchData? {
        val cached = prefetchCache
        prefetchCache = null
        return if (cached?.tmdbId == tmdbId) cached else null
    }

    // ── Home screen warm-up prefetch (populated by AppWarmup during splash) ─────
    //
    // Each slice is consumed once and nulled, so a recomposition can't re-read
    // stale data. The TTL (item 2) is the second guard: if the user lingered on
    // the profile picker past the window, freshOrNull() drops the whole payload
    // and every consumer falls back to a normal live fetch.
    data class HomeScreenPrefetch(
        val capturedAt:        Long,
        val profileId:         Int,
        var trending:          List<com.example.bluehive.api.TrendingItem>?      = null,
        var trailers:          List<com.example.bluehive.models.LatestTrailer>?  = null,
        var continueWatching:  List<com.example.bluehive.api.WatchHistoryResponse>? = null,
        var moviesPopular:     com.example.bluehive.models.MediaBrowseResponse?  = null,
        // Netflix personalized rows — profile-specific, consumed only on a
        // profileId match (see consume methods below).
        var netflixMovies:     com.example.bluehive.models.MediaBrowseResponse?  = null,
        var netflixTvShows:    com.example.bluehive.models.MediaBrowseResponse?  = null,
    )

    private var homePrefetch: HomeScreenPrefetch? = null

    // Item 2 — 90-second freshness window. Past this, prefetched data is
    // considered stale and discarded.
    private val HOME_PREFETCH_TTL_MS = 90_000L

    private fun freshHomePrefetchOrNull(): HomeScreenPrefetch? {
        val p = homePrefetch ?: return null
        if (System.currentTimeMillis() - p.capturedAt > HOME_PREFETCH_TTL_MS) {
            Log.d(TAG, "⏰ Home prefetch expired (> ${HOME_PREFETCH_TTL_MS}ms) — discarding")
            homePrefetch = null
            return null
        }
        return p
    }

    fun storeHomePrefetch(data: HomeScreenPrefetch) {
        homePrefetch = data
    }

    /** Trending is profile-agnostic — TTL is the only gate. One-shot. */
    fun consumeTrendingPrefetch(): List<com.example.bluehive.api.TrendingItem>? {
        val p = freshHomePrefetchOrNull() ?: return null
        val v = p.trending
        p.trending = null
        return v
    }

    /** Trailers are profile-agnostic — TTL is the only gate. One-shot. */
    fun consumeTrailersPrefetch(): List<com.example.bluehive.models.LatestTrailer>? {
        val p = freshHomePrefetchOrNull() ?: return null
        val v = p.trailers
        p.trailers = null
        return v
    }

    /** Continue watching is profile-specific — must match the active profile. One-shot. */
    fun consumeContinueWatchingPrefetch(profileId: Int): List<com.example.bluehive.api.WatchHistoryResponse>? {
        val p = freshHomePrefetchOrNull() ?: return null
        if (p.profileId != profileId) return null   // wrong profile → fall back to live fetch
        val v = p.continueWatching
        p.continueWatching = null
        return v
    }

    /** Movies "popular" shelf page 1 — profile-agnostic (popular is the same for all). One-shot. */
    fun consumeMoviesPopularPrefetch(): com.example.bluehive.models.MediaBrowseResponse? {
        val p = freshHomePrefetchOrNull() ?: return null
        val v = p.moviesPopular
        p.moviesPopular = null
        return v
    }

    /** Netflix movies row — personalized, so must match the active profile. One-shot. */
    fun consumeNetflixMoviesPrefetch(profileId: Int): com.example.bluehive.models.MediaBrowseResponse? {
        val p = freshHomePrefetchOrNull() ?: return null
        if (p.profileId != profileId) return null   // wrong profile → live fetch
        val v = p.netflixMovies
        p.netflixMovies = null
        return v
    }

    /** Netflix TV row — personalized, so must match the active profile. One-shot. */
    fun consumeNetflixTvShowsPrefetch(profileId: Int): com.example.bluehive.models.MediaBrowseResponse? {
        val p = freshHomePrefetchOrNull() ?: return null
        if (p.profileId != profileId) return null   // wrong profile → live fetch
        val v = p.netflixTvShows
        p.netflixTvShows = null
        return v
    }

    // ── App-wide state management ──────────────────────────────────────────────
    enum class AppState {
        HOME_ACTIVE,
        VIDEO_PLAYING,
        DETAILS_VIEWING,
        TRAILER_PLAYING
    }

    private var currentState = AppState.HOME_ACTIVE
    private val stateListeners = mutableListOf<StateListener>()
    private val stateHandler = Handler(Looper.getMainLooper())

    interface StateListener {
        fun onStateChanged(newState: AppState, previousState: AppState)
    }

    fun getCurrentState(): AppState = currentState

    fun registerStateListener(listener: StateListener) {
        synchronized(stateListeners) {
            if (!stateListeners.contains(listener)) {
                stateListeners.add(listener)
                Log.d(TAG, "Registered: ${listener.javaClass.simpleName}")
            }
        }
    }

    fun unregisterStateListener(listener: StateListener) {
        synchronized(stateListeners) {
            stateListeners.remove(listener)
            Log.d(TAG, "Unregistered: ${listener.javaClass.simpleName}")
        }
    }

    fun setAppState(state: AppState) {
        if (currentState == state) {
            Log.d(TAG, "State already: $state")
            return
        }
        val previousState = currentState
        currentState = state
        val stateEmoji = when (state) {
            AppState.HOME_ACTIVE      -> "🏠"
            AppState.VIDEO_PLAYING    -> "🎬"
            AppState.DETAILS_VIEWING  -> "📋"
            AppState.TRAILER_PLAYING  -> "🎞️"
        }
        Log.d(TAG, "$stateEmoji STATE: $previousState → $state")
        val listenersToNotify = synchronized(stateListeners) { stateListeners.toList() }
        stateHandler.post {
            listenersToNotify.forEach { listener ->
                try {
                    listener.onStateChanged(state, previousState)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying listener", e)
                }
            }
        }
    }

    // ── Application entry point ────────────────────────────────────────────────
    @OptIn(ExperimentalCoroutinesApi::class)   // Dispatchers.IO.limitedParallelism
    override fun onCreate() {
        super.onCreate()

        // ── Multi-process guard ──────────────────────────────────────────────
        // GeckoView spawns helper processes (:tab*, :gpu, :crashhelper) that
        // share our Application class. Each one re-runs onCreate(), and without
        // this guard each one would call DeviceEventStream.start() and open
        // its own SSE — leading to N concurrent streams per device and the
        // "stuck is_device_active" bug, since each stream's finally block
        // overwrites the flag set by the others.
        //
        // Only the main process owns the SSE stream, the SessionManager
        // singleton, and the lifecycle observers. Helper processes get the
        // bare minimum (super.onCreate has already run) and bail.
        val processName = currentProcessName()
        val isMainProcess = processName == packageName

        Log.d(TAG, "🚀 Application starting in process: $processName (main=$isMainProcess)")

        if (!isMainProcess) {
            Log.d(TAG, "⏭  Helper process — skipping main-process initialization")
            return
        }

        // Everything below this point runs ONLY in the main process.
        SessionManager.init(this)
        com.example.bluehive.host.HostTokenProvider.init(this)
        Log.d("BlueHiveApplication", "HostTokenProvider initialised")
        Log.d(TAG, "✅ SessionManager initialised")

        // Track the foreground Activity for the SessionExpiredBus listener below.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                foregroundActivity = java.lang.ref.WeakReference(activity)
            }
            override fun onActivityPaused(activity: android.app.Activity) {
                if (foregroundActivity?.get() === activity) foregroundActivity = null
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })

        if (SessionManager.get().isAuthenticated) {
            DeviceEventStream.start()
        }

        // ── Inactivity timeout ─────────────────────────────────────────────────
        // All lifecycle decisions go through InactivityWatcher so a single
        // `adb logcat -s InactivityWatcher BlueHiveApplication DeviceEventStream`
        // filter shows the full sequence of background/foreground transitions
        // and exactly why each one acted (or didn't).
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {

                override fun onStop(owner: LifecycleOwner) {
                    Log.d(TAG, "🌙 ProcessLifecycleOwner.onStop fired — app is fully backgrounded")
                    InactivityWatcher.onAppBackgrounded()
                }

                override fun onStart(owner: LifecycleOwner) {
                    Log.d(TAG, "☀️ ProcessLifecycleOwner.onStart fired — app is foreground")
                    InactivityWatcher.onAppForegrounded()
                    // Resume the SSE stream. startAfterUserReturn() clears the
                    // intentionally-exited flag so a previous back-button close
                    // can't block this reconnection in a still-alive process.
                    DeviceEventStream.startAfterUserReturn()
                }
            }
        )

        // ── Session expired ────────────────────────────────────────────────────
        // PHASE 2: identity loss (host revoked/unpaired) means BlueHive closes
        // and hands control back to the host, which owns re-pairing. We finish
        // the foreground Activity's whole task; the user lands back on OGD.
        // HomeScreenCompose also catches this via its own returnToHost(); this
        // Application-level listener is the catch-all for every other screen.
        SessionExpiredBus.register {
            Log.w(TAG, "🔒 Session expired — returning to host")
            DeviceEventStream.stopAndMarkExited()
            val act = foregroundActivity?.get()
            if (act != null) {
                act.finishAffinity()
            } else {
                Log.w(TAG, "No foreground activity to finish — stream stopped")
            }
        }

        // ── Lockout ────────────────────────────────────────────────────────────
        // PHASE 2: nothing sits beneath the lockout screen anymore. Back-press
        // from LockoutActivity finishAffinity()s back to the host (OGD).
        LockoutBus.register { reason ->
            if (LockoutActivity.isOnTop) {
                Log.d(TAG, "🔒 Lockout posted but already on LockoutActivity — ignoring")
                return@register
            }
            Log.w(TAG, "🔒 Lockout (reason=$reason) — launching lockout screen")
            val intent = Intent(this, LockoutActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(LockoutActivity.EXTRA_REASON, reason)
            }
            startActivity(intent)
        }

        // ── Image loader ───────────────────────────────────────────────────────
        // fetcher/decoder parallelism is capped HARD. Coil's default runs image
        // work on unbounded Dispatchers.IO — on a 4-core 2 GB box a cold home
        // screen fires 30-40 downloads+decodes at once, saturating every core
        // and starving the main thread (the first-launch "everything at once"
        // lag). 4 fetches keeps the network pipe full; 2 decodes always leaves
        // cores free for the UI. Total fill time barely changes — network was
        // the bottleneck — but frames stop dropping.
        val lowRam = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        val imageLoader = ImageLoader.Builder(this)
            .components {
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (lowRam) MEMORY_CACHE_PERCENT_LOW_RAM else MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(DISK_CACHE_SIZE_MB * 1024 * 1024)
                    .build()
            }
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(4))
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(2))
            .respectCacheHeaders(false)
            .allowHardware(true)
            .crossfade(true)
            .build()

        Coil.setImageLoader(imageLoader)
        coilImageLoader = imageLoader

        // Walks the whole 300 MB disk-cache directory — never on the main
        // thread during startup (it was a cold-start hitch all by itself).
        Thread { logCacheStats() }.start()
        initSoundPool(this)

        // Warm the loading-overlay bee logo on a background thread now, at launch,
        // while memory is freshest. Cached process-wide so the Dub/Sub extraction
        // overlay reuses it instead of decoding under memory pressure — which was
        // crashing on 2 GB hardware (BitmapFactory returning null → NPE).
        Thread { BeeLogo.prime(this) }.start()

        // Initialise GeckoView in main process only — not in GeckoView's own
        // service/content subprocesses which also trigger Application.onCreate.
        if (isMainProcess()) {
            initializeGeckoOnMainThread()
        }
    }


    /**
     * Returns the name of the current process. Used to distinguish the main
     * application process from GeckoView/Crashlytics helper processes that
     * share our Application class.
     *
     * API 28+ has Application.getProcessName(). For older versions we fall
     * back to scanning /proc/self/cmdline, which is reliable on every Android
     * version that has ever existed.
     */
    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName()
        }
        return try {
            java.io.File("/proc/self/cmdline")
                .readText()
                .trim('\u0000', ' ', '\n')
        } catch (e: Exception) {
            Log.w(TAG, "Could not read process name, assuming main: ${e.message}")
            packageName
        }
    }



    // ── Cache diagnostics ──────────────────────────────────────────────────────
    private fun logCacheStats() {
        val diskCacheDir = cacheDir.resolve("image_cache")

        if (diskCacheDir.exists()) {
            val sizeBytes   = diskCacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            val sizeMB      = sizeBytes / (1024.0 * 1024.0)
            val percentUsed = (sizeMB / DISK_CACHE_SIZE_MB) * 100
            Log.d(TAG, "═══════════════════════════════════════")
            Log.d(TAG, "📦 DISK CACHE STATS")
            Log.d(TAG, "  Size: %.2f MB / %d MB (%.1f%% used)".format(sizeMB, DISK_CACHE_SIZE_MB.toInt(), percentUsed))
            Log.d(TAG, "  Location: ${diskCacheDir.absolutePath}")
        } else {
            Log.d(TAG, "═══════════════════════════════════════")
            Log.d(TAG, "📦 DISK CACHE STATS")
            Log.d(TAG, "  Size: 0 MB / %d MB (empty)".format(DISK_CACHE_SIZE_MB.toInt()))
        }

        Log.d(TAG, "───────────────────────────────────────")
        Log.d(TAG, "💾 MEMORY CACHE STATS")
        val memMaxMB = (coilImageLoader.memoryCache?.maxSize ?: 0) / (1024.0 * 1024.0)
        Log.d(TAG, "  Allocation: %.0f MB (percent-of-heap)".format(memMaxMB))
        Log.d(TAG, "═══════════════════════════════════════")

        listOf(
            cacheDir.resolve("titlecard_cache"),
            cacheDir.resolve("image_cache_old")
        ).forEach { dir ->
            if (dir.exists()) {
                val oldMB = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum() / (1024.0 * 1024.0)
                if (oldMB > 0) Log.w(TAG, "⚠️ Found old cache: ${dir.name} (%.2f MB) - Consider clearing".format(oldMB))
            }
        }
    }

    fun cleanupOldCaches() {
        listOf(cacheDir.resolve("titlecard_cache")).forEach { dir ->
            if (dir.exists() && dir.deleteRecursively()) {
                Log.d(TAG, "🧹 Cleaned up old cache: ${dir.name}")
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private fun isMainProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            try {
                val pid     = android.os.Process.myPid()
                val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                manager.runningAppProcesses?.find { it.pid == pid }?.processName
            } catch (_: Exception) {
                null
            }
        }
        return processName == packageName
    }

    // Deferred 8s: Gecko init spawns child processes + loads native libs — a
    // huge CPU spike that used to land exactly while the splash's carousel
    // prefetch and first paint were fighting for the same 4 cores. 8s lands it
    // on the (static) profile picker. Playback can't be reached that fast from
    // a cold start, and getNewSession() fails soft if it ever were.
    private val GECKO_INIT_DELAY_MS = 8_000L

    private fun initializeGeckoOnMainThread() {
        Handler(Looper.getMainLooper()).postDelayed({
            // GeckoView ships only armeabi-v7a native libs in this build. On a device
            // whose ABI can't load them (e.g. the x86_64 Baseline Profile generation
            // emulator), GeckoThread crashes the ENTIRE process from its own internal
            // thread while loading libmozglue — an uncaught exception we cannot try/catch.
            // So skip Gecko entirely on unsupported ABIs. The onn box is armeabi-v7a and
            // initializes normally.
            if (Build.SUPPORTED_ABIS.none { it == "armeabi-v7a" }) {
                Log.w(TAG, "Skipping GeckoWebView init — no armeabi-v7a ABI here (${Build.SUPPORTED_ABIS.joinToString()})")
                return@postDelayed
            }
            try {
                Log.d(TAG, "Initializing GeckoWebViewManager (deferred ${GECKO_INIT_DELAY_MS}ms)")
                GeckoWebViewManager.initialize(this)
                Log.d(TAG, "GeckoWebViewManager initialized")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize GeckoWebView (continuing without it)", e)
            }
        }, GECKO_INIT_DELAY_MS)
    }

    override fun newImageLoader(): ImageLoader = coilImageLoader

    // ── Memory pressure ────────────────────────────────────────────────────────
    // When RAM runs low, Android first ASKS every app to give memory back
    // (this callback); if that doesn't free enough, the Low Memory Killer
    // starts executing processes — biggest consumers first. On a 2 GB box
    // running Gecko, BlueHive is a prime target. Our most replaceable holding
    // is Coil's MEMORY cache: every image in it also lives in the 300 MB disk
    // cache, so clearing it costs a ~30ms re-decode per poster on the
    // throttled background threads — versus the process dying mid-movie.
    //
    // Levels: RUNNING_LOW/CRITICAL = foreground pressure (we're visible and
    // the system is struggling). BACKGROUND+ = we're backgrounded and on the
    // cull list. UI_HIDDEN fires on every Home press with NO actual pressure —
    // clearing there would wipe the cache on every resume-in-place, so skip it.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!isMainProcess()) return   // helper/Gecko processes never init the image loader

        val underPressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (underPressure) {
            Log.w(TAG, "🧹 onTrimMemory(level=$level) — clearing image memory cache to dodge the LMK")
            coilImageLoader.memoryCache?.clear()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        releaseSoundPool()
        Log.d(TAG, "Application terminated")
    }
}