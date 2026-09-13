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
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.LockoutBus
import com.example.bluehive.api.SessionExpiredBus
import com.example.bluehive.models.MediaItem
import com.example.bluehive.webview.BeeLogo
import com.example.bluehive.webview.GeckoWebViewManager
import com.example.bluehive.auth.DeviceEventStream
import com.example.bluehive.auth.EntitlementLockBus
import com.example.bluehive.auth.HostEntitlementRelay
import com.example.bluehive.auth.ServiceLockoutActivity
import com.example.bluehive.auth.SessionManager
import com.example.bluehive.update.UpdateGate
import com.example.bluehive.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


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
    // return to the host," not "launch LoginScreenActivity."
    private var foregroundActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

    /**
     * The last Activity that was resumed, kept across pause and stop.
     *
     * SEPARATE FROM [foregroundActivity] BECAUSE THEY ANSWER DIFFERENT QUESTIONS.
     * That one means "an Activity is on screen right now" and is deliberately
     * nulled on pause, which is correct for deciding whether we may legally start
     * another Activity. This one means "an Activity of ours still exists", which
     * is what finishAffinity() needs, and a stopped Activity is perfectly able to
     * finish its own task.
     *
     * Held weakly and dropped on destroy, so it can never keep a dead Activity
     * alive; the idle close simply skips finishAffinity if it has gone.
     */
    private var lastKnownActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

    companion object {
        private const val TAG = "BlueHiveApplication"

        /**
         * Application context, for code that runs between extraction and
         * playback and has no Context of its own — see
         * ExtractAwait.extractStreamAwait, which pre-warms the CDN hosts an
         * extraction just reported. Application context only: it outlives every
         * Activity, so holding it leaks nothing.
         */
        @Volatile
        @JvmStatic
        var appContext: android.content.Context? = null
            private set

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
        private var titleCardNavigationSoundId: Int = 0
        private var buttonNavigationSoundId: Int = 0
        private var buttonClickSoundId: Int = 0
        private var backOutSoundId: Int = 0
        private var lastButtonClickPlayTime: Long = 0L
        private var webViewerClickSoundId: Int = 0
        private var profileSwapSoundId: Int = 0
        private var lastProfileSwapPlayTime: Long = 0L
        // No sample id of its own: the nav sound shares
        // buttonNavigationSoundId (button_navigation.mp3) with playHoverSound.
        // Only the clock is separate — see playNavClickSound.
        private var lastNavClickPlayTime: Long = 0L

        // ── SOUND VOLUMES ────────────────────────────────────────────────
        //
        // Every level in one place. These were inline literals scattered across
        // six play* functions, which meant a sample used by two of them could
        // drift to two different volumes without anyone noticing — and one had.
        //
        // ONE CONSTANT PER SAMPLE, NOT PER FUNCTION. button_navigation.mp3 is
        // played by both playHoverSound and playNavClickSound; they now read the
        // same constant, so the file sounds identical wherever it is used.
        private const val TITLECARD_NAVIGATION_VOLUME = 0.7f
        private const val BUTTON_NAVIGATION_VOLUME    = 0.2f
        private const val BUTTON_CLICK_VOLUME         = 0.3f
        private const val PROFILE_SWAP_VOLUME         = 0.45f
        private const val BACK_OUT_VOLUME             = 0.35f
        private const val WEBVIEWER_CLICK_VOLUME      = 0.35f

        /**
         * Play one sample at one volume — the ONLY place SoundPool.play is called.
         *
         * That API takes left and right volume separately; every call site passed
         * the same value twice, which is two chances to typo one channel quieter
         * than the other. The trailing 0, 0, 1f (priority, loop, rate) were
         * identical everywhere too.
         */
        private fun play(soundId: Int, volume: Float) {
            soundPool?.play(soundId, volume, volume, 0, 0, 1f)
        }

        private const val BUTTON_CLICK_DEBOUNCE_MS = 120L

        // Shared by both focus-navigation sounds, and shorter than
        // BUTTON_CLICK_DEBOUNCE_MS on purpose. A held D-pad repeats roughly every
        // 100-150ms and each of those IS a real move — swallowing them would
        // make a fast scroll go quiet exactly when the feedback is most useful.
        // This only collapses the double-fire of one keypress.
        private const val NAV_SOUND_DEBOUNCE_MS = 70L

        fun initSoundPool(context: Context) {
            if (soundPool == null) {
                soundPool = SoundPool.Builder()
                    .setMaxStreams(4)
                    .build()

                titleCardNavigationSoundId = soundPool!!.load(context, R.raw.titlecard_navigation, 1)
                buttonNavigationSoundId    = soundPool!!.load(context, R.raw.button_navigation, 1)
                buttonClickSoundId         = soundPool!!.load(context, R.raw.button_click, 1)
                backOutSoundId             = soundPool!!.load(context, R.raw.backedout, 1)
                webViewerClickSoundId      = soundPool!!.load(context, R.raw.webviewerclick, 1)
                profileSwapSoundId         = soundPool!!.load(context, R.raw.profile_swap, 1)
            }
        }

        fun playWebViewerClickSound() {
            play(webViewerClickSoundId, WEBVIEWER_CLICK_VOLUME)
        }

        fun playTitleCardNavigation() {
            play(titleCardNavigationSoundId, TITLECARD_NAVIGATION_VOLUME)
        }

        fun playHoverSound() {
            play(buttonNavigationSoundId, BUTTON_NAVIGATION_VOLUME)
        }

        fun playClickSound() {
            val now = SystemClock.uptimeMillis()
            if (now - lastButtonClickPlayTime < BUTTON_CLICK_DEBOUNCE_MS) return
            lastButtonClickPlayTime = now
            play(buttonClickSoundId, BUTTON_CLICK_VOLUME)
        }

        fun playBackOutSound() {
            play(backOutSoundId, BACK_OUT_VOLUME)
        }

        /**
         * Moving between profiles on the profile picker.
         *
         * Deliberately NOT wired to focus-gain in general: it fires only when
         * one profile card hands focus to another, so pressing left on the
         * leftmost profile — where focus does not move — stays silent. See
         * ProfileScreenActivity for the arming that keeps the screen's own
         * opening focus quiet too.
         *
         * PROFILE_SWAP_VOLUME is a STARTING level, not a measured one — between
         * the click and the title-card navigation. Adjust by ear.
         */
        fun playProfileSwapSound() {
            val now = SystemClock.uptimeMillis()
            if (now - lastProfileSwapPlayTime < NAV_SOUND_DEBOUNCE_MS) return
            lastProfileSwapPlayTime = now
            play(profileSwapSoundId, PROFILE_SWAP_VOLUME)
        }

        /**
         * Moving onto one of the profile screen's other controls — Create
         * Profile, Sign Out, Manage Profiles.
         *
         * button_navigation.mp3 at BUTTON_NAVIGATION_VOLUME — the same sample
         * AND the same level playHoverSound uses. Hover used to sit at 0.1f
         * while this sat at 0.2f; one sample playing at two volumes depending on
         * which control you moved onto is a difference the ear reads as
         * inconsistency rather than as meaning.
         *
         * SHARED SAMPLE, SEPARATE CLOCK. The debounce is its own because
         * navigating onto a button and then pressing it are two events ~100ms
         * apart; on one clock the second would be swallowed, and the press is
         * the one the user actually wants to hear.
         */
        fun playNavClickSound() {
            val now = SystemClock.uptimeMillis()
            if (now - lastNavClickPlayTime < NAV_SOUND_DEBOUNCE_MS) return
            lastNavClickPlayTime = now
            play(buttonNavigationSoundId, BUTTON_NAVIGATION_VOLUME)
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

    // ── Continue-Watching prefetch (fired on profile-select) ────────────────────
    // Dedicated, profile-accurate CW prefetch: the moment a profile is picked
    // (ProfileScreenActivity.goHome), we fetch its Continue-Watching list AND warm
    // every episode thumbnail into Coil — so the home screen's CW section renders
    // from cache instead of fetching + loading images on arrival. Its own app-scoped
    // job so it outlives the finishing ProfileScreenActivity.
    private data class CwPrefetch(
        val capturedAt: Long,
        val profileId: Int,
        var items: List<com.example.bluehive.api.WatchHistoryResponse>?,
    )
    private var cwPrefetch: CwPrefetch? = null
    private val cwPrefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    fun storeContinueWatchingPrefetch(profileId: Int, items: List<com.example.bluehive.api.WatchHistoryResponse>) {
        cwPrefetch = CwPrefetch(System.currentTimeMillis(), profileId, items)
    }

    /**
     * Fetch [profileId]'s Continue-Watching list and warm every episode thumbnail into
     * Coil, up front — called from the profile picker on selection, so all of it runs
     * during the "Getting X ready" loading window rather than on the home screen. The
     * thumbnails are warmed with the EXACT request the CW rows use (same data, size and
     * memoryCacheKey), so each row render is a cache hit. Best-effort: any failure just
     * leaves the home screen to fetch/load as before.
     */
    fun prefetchContinueWatching(profileId: Int) {
        if (profileId == -1) return
        cwPrefetchScope.launch {
            try {
                val cw = runCatching {
                    ApiClient.bluehiveApi.getWatchHistory(profileId = profileId, limit = 15, offset = 0)
                }.getOrNull() ?: return@launch
                // Store the list first so the CW section consumes it instead of fetching live.
                storeContinueWatchingPrefetch(profileId, cw)
                var warmed = 0
                cw.forEach { item ->
                    val url = item.image_url
                    if (!url.isNullOrBlank()) {
                        coilImageLoader.enqueue(
                            ImageRequest.Builder(this@BlueHiveApplication)
                                .data(url)
                                .size(166, 94)
                                .memoryCacheKey("cw_thumb_${item.media_tmdb_id}_${item.media_type}_${item.season_number}_${item.episode_number}")
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .allowRgb565(true)
                                .allowHardware(true)
                                .build()
                        )
                        warmed++
                    }
                }
                Log.d(TAG, "🍿 CW prefetch (profile=$profileId): ${cw.size} items, $warmed thumbs warming")
            } catch (e: Exception) {
                Log.w(TAG, "CW prefetch failed (profile=$profileId): ${e.message}")
            }
        }
    }

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
        // Profile-click prefetch wins — it's for the EXACT profile chosen (the splash
        // prefetch only ever guessed the most-recently-used one).
        cwPrefetch?.let { c ->
            if (c.profileId == profileId &&
                System.currentTimeMillis() - c.capturedAt <= HOME_PREFETCH_TTL_MS
            ) {
                val v = c.items
                c.items = null
                return v
            }
        }
        // Fallback: the splash home prefetch.
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
        appContext = applicationContext

        // Deliberately BEFORE the multi-process guard and before any networking.
        // Backend clients ask LanNetwork for a socket at connect time, and until
        // the callback has fired they get the default one — which a VPN swallows.
        // Cheap, idempotent, and per-process, so helper processes running their
        // own ApiClient are covered too.
        com.example.bluehive.net.LanNetwork.init(this)

        // ── Warm the QUIC hint list before anything plays ────────────────────
        //
        // An SNI-filtering ISP severs the TCP handshake before a response exists,
        // so this client can never LEARN from an alt-svc header that a CDN also
        // speaks QUIC — the header would arrive on the connection just killed.
        // Chromium can only open QUIC first for hosts named at engine-build time.
        //
        // Discovering them locally therefore costs one relayed playback each, and
        // these CDNs rotate constantly (moon.peakstorm.top, boldapple.top,
        // polarsable.top, cedarorbit.top, mistynight.top on a single title), so
        // the cost never amortises. The backend meets every host first — it is
        // what extracts them — so it publishes the list and no device pays
        // discovery for a host another device already found.
        //
        // FIRE AND FORGET, and deliberately unguarded: the transport is built
        // lazily on first playback, so a slow or failed fetch simply means this
        // device falls back to learning locally, exactly as it did before. It
        // must never delay startup or fail it.
        // RETRIED, because onCreate is too early. Measured 2026-08-29: the first
        // attempt fails with IOException — LanNetwork.init above has only just
        // been called and its socket-factory callback has not fired, so there is
        // no usable network yet. The backoff costs nothing (playback builds its
        // transport lazily, long after this) and turns a guaranteed first-launch
        // miss into a reliable warm.
        // MAIN PROCESS ONLY — this is a NETWORK call, and it sat above the
        // multi-process guard below where every helper process ran it too.
        //
        // MEASURED 2026-08-31: MiruroExtractorActivity runs in :extractor, which
        // deliberately never calls SessionManager.init (the guard returns first).
        // ApiClient's auth interceptor then threw
        //   IllegalStateException: SessionManager not initialised
        // on an OkHttp dispatcher thread with no handler, killing the whole
        // process. The extractor died mid-extraction, the task unwound, and the
        // user was dumped on the home screen with no error — reproduced on an
        // anime DuB play.
        //
        // LanNetwork.init above must stay before the guard. Networking must not.
        if (isMainProcess()) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                for (delayMs in longArrayOf(3_000, 8_000, 20_000)) {
                    kotlinx.coroutines.delay(delayMs)
                    val ok = runCatching {
                        val r = com.example.bluehive.api.ApiClient.bluehiveApi.cdnHosts()
                        val hosts = r.body()?.hosts
                        if (r.isSuccessful && !hosts.isNullOrEmpty()) {
                            com.example.bluehive.webview.exoplayer_resources
                                .ExoPlayerConfig.mergeQuicHints(
                                    this@BlueHiveApplication,
                                    hosts,
                                    withdraw = r.body()?.h2Only ?: emptyList(),
                                )
                            // Warm DNS/ECH for the freshly merged list right away.
                            // The race that loses ECH happens ~34ms before a
                            // playback's ClientHello; here nothing is waiting, so the
                            // full allowance is actually available.
                            com.example.bluehive.webview.exoplayer_resources
                                .ExoPlayerConfig.prewarmCdnDns(this@BlueHiveApplication)
                            true
                        } else false
                    }.getOrElse { false }
                    if (ok) return@launch
                }
                android.util.Log.e("scraper-vidapi",
                    "🔀 cdn-host list unavailable after 3 attempts — falling back to " +
                    "learning hosts locally (one relayed play per new host)")
            }
        }

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

        // Crash reporting FIRST — anything that crashes during the rest of
        // startup should be captured too. install() only hooks the handler;
        // it needs nothing initialised.
        com.example.bluehive.diagnostics.CrashReporter.install(this)

        // ── Give back the last update's download ─────────────────────────────
        // An installed update leaves its ~170MB APK in cacheDir, because the OS
        // kills this process the instant the replacement lands and no cleanup
        // code of ours gets to run. Until now the only thing that swept it was
        // the NEXT download, so the space stayed spoken for between releases —
        // on the same partition the next install needs headroom on.
        //
        // Fire-and-forget on IO: nothing waits on it, and a failure just leaves
        // the file for the platform to evict as it always did.
        //
        // runCatching is NOT belt-and-braces here. This is a plain CoroutineScope
        // with no SupervisorJob and no exception handler, so anything thrown inside
        // reaches the thread's default handler and kills the process — and this one
        // runs on the startup path, where that reads as the app refusing to open.
        // Reclaiming cache is never worth a launch failure.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { UpdateManager.pruneStaleDownloads(this@BlueHiveApplication) }
                .onFailure { Log.e(TAG, "update-cache prune failed: ${it.message}") }
        }

        SessionManager.init(this)

        // Playback settings are keyed by profile, so this MUST come after
        // SessionManager.init — before it there is no profile id to read and the
        // first details screen of a cold start would show the default instead of
        // the viewer's saved choice.
        com.example.bluehive.utilities.PlaybackPrefs.load(this)
        // HostTokenProvider.init() removed 2026-08-16. It bound a service
        // connection to Off-Grid Drive at startup so tokens could be fetched over
        // AIDL. TokenProvider replaces it and needs no initialisation — it reads
        // the refresh token SessionManager already holds and calls bluehive-api.
        //
        // Leaving the old init in place was what caused sessions to be wiped on
        // restart: signing in stored a BlueHive session, but the first 401 still
        // routed to the HOST, which answered Revoked, and the 401 handler
        // faithfully cleared the session it had no business clearing.
        Log.d(TAG, "✅ SessionManager initialised")

        // Upload any crash reports persisted by a previous run (fire-and-forget,
        // delayed a few seconds so cold-start and token refresh settle first).
        com.example.bluehive.diagnostics.CrashReporter.flushPendingAsync(this)

        // Track the foreground Activity for the SessionExpiredBus listener below.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                foregroundActivity = java.lang.ref.WeakReference(activity)
                // Tracked in parallel and NOT cleared on pause — see the field doc.
                // This is the reference the idle close uses hours later, long after
                // a screensaver has stopped everything.
                lastKnownActivity = java.lang.ref.WeakReference(activity)
                // Arriving on a new screen is interaction — the user pressed
                // something to get here. Without this, a navigation that happens to
                // straddle a tick could be judged against the previous screen's
                // stale input time.
                IdleWatcher.noteInteraction()
            }
            override fun onActivityPaused(activity: android.app.Activity) {
                if (foregroundActivity?.get() === activity) foregroundActivity = null
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                // ── GLOBAL INPUT HOOK ──────────────────────────────────────────
                //
                // Wrapping the window callback catches EVERY key and touch event on
                // EVERY activity — Compose screens, the players, GeckoView — from
                // one place. The obvious alternative is overriding
                // onUserInteraction() per activity, which would mean editing two
                // dozen files and, far worse, silently missing the next activity
                // anyone adds. A screen that forgets to report input is a screen
                // that releases its slot out from under someone who is sitting
                // right there.
                //
                // DELEGATES EVERYTHING. The wrapper observes and forwards; it never
                // consumes, so focus, D-pad routing and back handling behave
                // exactly as they did before.
                activity.window?.let { w -> w.callback = IdleInputCallback(w.callback) }
            }
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {
                // Drop our own reference so a destroyed Activity is never handed to
                // finishAffinity(). Identity-checked, so a newer Activity's entry is
                // never cleared by an older one being torn down.
                if (lastKnownActivity?.get() === activity) lastKnownActivity = null
            }
        })

        if (SessionManager.get().isAuthenticated) {
            DeviceEventStream.start()
        }

        // ── Idle slot release ──────────────────────────────────────────────────
        // Covers the case InactivityWatcher structurally cannot: the app sitting in
        // the FOREGROUND with nobody in front of it. The SSE keepalive is driven by
        // the socket, so a detail screen, a paused film or a finished one holds a
        // household slot for as long as the TV has power. See IdleWatcher.
        IdleWatcher.setForeground(true)
        IdleWatcher.start()

        // ── Inactivity timeout ─────────────────────────────────────────────────
        // All lifecycle decisions go through InactivityWatcher so a single
        // `adb logcat -s InactivityWatcher BlueHiveApplication DeviceEventStream`
        // filter shows the full sequence of background/foreground transitions
        // and exactly why each one acted (or didn't).
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {

                override fun onStop(owner: LifecycleOwner) {
                    Log.d(TAG, "🌙 ProcessLifecycleOwner.onStop fired — app is fully backgrounded")
                    // FIRST, before anything that can block or throw. Stopping the
                    // heartbeat is itself the signal that the slot should start
                    // counting down — /background says the same thing explicitly,
                    // but that POST can be lost and this cannot: Android is about
                    // to freeze this process, and a frozen process sends nothing.
                    SessionHeartbeat.stop()
                    InactivityWatcher.onAppBackgrounded()
                    // Hand the slot back to InactivityWatcher's Redis grace. Two
                    // mechanisms counting down on one slot with no coordination is
                    // how you get a release racing a grace that was already
                    // handling it.
                    IdleWatcher.setForeground(false)
                }

                override fun onStart(owner: LifecycleOwner) {
                    Log.d(TAG, "☀️ ProcessLifecycleOwner.onStart fired — app is foreground")
                    InactivityWatcher.onAppForegrounded()
                    IdleWatcher.setForeground(true)
                    // Resume proving we are alive. Idempotent, so the repeated
                    // onStart Android fires for one user-visible return does not
                    // start a second loop.
                    SessionHeartbeat.start()
                    // Resume the SSE stream. startAfterUserReturn() clears the
                    // intentionally-exited flag so a previous back-button close
                    // can't block this reconnection in a still-alive process.
                    DeviceEventStream.startAfterUserReturn()
                    // NO UPDATE CHECK HERE ANY MORE. This used to run on every
                    // foreground transition, which on a cold start meant a SECOND
                    // checker racing AuthActivity's: both found the update, both
                    // launched the updater with NEW_TASK|CLEAR_TASK, and the second
                    // destroyed the task the first had just rooted. The app drew
                    // nothing and downloaded 163MB behind the TV launcher.
                    //
                    // UpdateGate on HomeScreenCompose.onResume is the only checker
                    // now. Nothing is lost by dropping it here: every return to the
                    // foreground resumes that screen anyway.
                    // Host entitlement is re-checked from HomeScreenCompose.onResume,
                    // not from a timer here. Every way back into the app lands on
                    // the home screen, so that one hook covers returning from the
                    // TV launcher as well as from any internal screen — and it
                    // spends the work only when someone is actually navigating.
                }
            }
        )

        // ── Operator-triggered update re-check ─────────────────────────────────
        // Arrives over the SSE stream when someone runs the admin endpoint. Passes
        // immediate=true, which skips UpdateGate's settle delay deliberately — the
        // whole point is "now", and the operator pressing it is the rate limit.
        //
        // Still just a CHECK. The manifest decides whether an update exists and
        // whether it can be declined; the event only decides when to look.
        ForceUpdateBus.register {
            // THROUGH THE SAME GATE the home screen uses, so the operator push is
            // not a second path with its own rules. immediate=true skips only the
            // settle delay -- the playback guard and the single-flight latch still
            // apply, so a push landing during a film defers instead of taking the
            // screen away mid-scene.
            val host = foregroundActivity?.get()
            if (host != null) {
                UpdateGate.checkNow(host, immediate = true)
            } else {
                // No window to start an activity from, and a background activity
                // launch is exactly what Android 14 drops silently. The next home
                // screen picks it up.
                Log.i(TAG, "operator push with no foreground activity — next home screen catches it")
            }
        }

        // ── Idle close ─────────────────────────────────────────────────────────
        // IdleWatcher has seen no input for four hours with nothing playing. Runs
        // the SAME close path as the Back button rather than a second teardown of
        // its own — see performClose for why that matters.
        //
        // Needs the foreground Activity for finishAffinity(), which IdleWatcher
        // (a process-wide object) does not have; this class already tracks it for
        // SessionExpiredBus, so the news comes here instead.
        IdleCloseBus.register {
            // THIS USED TO SKIP WHEN THERE WAS NO FOREGROUND ACTIVITY, which is the
            // state a four-hour idle close is ALWAYS in. A TV backgrounds this app
            // via its screensaver within minutes, onActivityPaused nulls
            // foregroundActivity, and the close then declined to run in precisely
            // the case it was written for.
            //
            // The old comment refused to kill without an Activity on the grounds
            // that the stream and slot would be left to server-side timeouts and
            // the exit would read as "app died mysteriously". Neither survives
            // scrutiny here. performClose stops the stream and sends the
            // end-session ping itself, whatever the Activity is, so nothing is left
            // to time out; and an app nobody has touched for four hours, behind a
            // screensaver, has no viewer to mystify.
            //
            // lastKnownActivity is preferred when we have it, purely so the task is
            // finished cleanly rather than dying with the process.
            val act = foregroundActivity?.get()
                ?: lastKnownActivity?.get()?.takeIf { !it.isDestroyed }
            Log.e(TAG, "😴 idle close — shutting down (activity=${act != null})")
            com.example.bluehive.utilities.performClose(act, killProcess = true)
        }

        // ── Host reported this account inactive ────────────────────────────────
        // Close the app immediately and raise the lock screen. NOT a sign-out:
        // the session is deliberately left intact, because the standing is
        // expected to flip back and making people re-authenticate after a billing
        // hiccup would be its own bug.
        //
        // The fail-open rule lives in HostEntitlementRelay, not here — by the time
        // this fires, a definite "inactive" has already been confirmed.
        HostEntitlementRelay.init(this)

        EntitlementLockBus.register {
            Log.w(TAG, "🔒 host standing inactive — closing app to the lock screen")
            ServiceLockoutActivity.launch(this@BlueHiveApplication)
        }

        // ── Session expired ────────────────────────────────────────────────────
        // PHASE 2: identity loss (host revoked/unpaired) means BlueHive closes
        // and hands control back to the host, which owns re-pairing. We finish
        // the foreground Activity's whole task; the user lands back on the host.
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
        // from LockoutActivity finishAffinity()s back to the host.
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

    // Only PRELOAD Gecko in the main process on devices with RAM headroom. Below this,
    // a ~200 MB runtime resident in the backgrounded main process makes it a prime
    // low-memory-killer target while the :extractor process (its own Gecko) runs — the
    // OOM we hit on 2 GB TV boxes. Constrained devices init Gecko on demand instead
    // (GeckoWebViewManager.getNewSession), which the anime path never triggers.
    private val GECKO_PRELOAD_MIN_TOTAL_MEM = 2_500_000_000L   // ~2.3 GiB

    private fun initializeGeckoOnMainThread() {
        // Skip the preload on RAM-constrained devices — Gecko then inits lazily on first
        // MainWebViewer use, keeping the main process lean during anime playback.
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        if (am == null || am.isLowRamDevice || memInfo.totalMem < GECKO_PRELOAD_MIN_TOTAL_MEM) {
            Log.d(TAG, "Skipping Gecko preload (totalMem=${memInfo.totalMem / (1024 * 1024)}MB, " +
                    "lowRam=${am?.isLowRamDevice}) — runtime will init on demand")
            return
        }
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
            // Also forward the pressure to Gecko so it drops its OWN native caches.
            // The embedded browser is the DOMINANT memory consumer on 2 GB boxes,
            // and clearing Coil alone doesn't touch a byte of Gecko's memory.
            com.example.bluehive.webview.GeckoWebViewManager.onTrimMemory(level)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        releaseSoundPool()
        Log.d(TAG, "Application terminated")
    }
}