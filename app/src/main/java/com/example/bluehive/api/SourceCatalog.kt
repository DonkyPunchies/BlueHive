package com.example.bluehive.api

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

/**
 * Which streaming sources this app shows, decided by the backend.
 *
 * WHY THE BACKEND OWNS THIS: a source dies and the fix used to be a release —
 * build, sign, install on every box. Now it is one word in
 * `/opt/bluehive/config/sources.conf` and the next title anyone opens has the
 * new list. That is the whole point; see GET /api/sources.
 *
 * READS ARE SYNCHRONOUS ON PURPOSE. [sources] never performs IO: it answers
 * from memory, from disk, or from the built-ins below, in that order, and
 * always immediately. The network fetch is [warm], which runs separately and
 * only updates the cache. That split is what let this replace the old
 * hardcoded list without turning every call site into a coroutine — including
 * the focus-requester pool, which is sized during composition and cannot wait.
 *
 * THREE LAYERS, AND THE LAST ONE CANNOT FAIL:
 *   1. memory   — set by the last successful [warm] this process
 *   2. disk     — the last successful fetch on this device, ever
 *   3. built-in — compiled in, identical to what the app shipped with
 * A backend that is down, unreachable or serving nonsense therefore changes
 * nothing about what the user sees. The feature can only ever ADD control; it
 * can never be the reason playback has no buttons.
 */
object SourceCatalog {

    private const val TAG = "SourceCatalog"
    private const val PREFS = "bh_source_catalog"
    private const val KEY_TV = "tv"
    private const val KEY_MOVIE = "movie"

    data class Source(
        val name: String,      // routing name — "VidFast". Must match the backend key.
        val label: String,     // what the button says — "Queen Stream"
        val url: String,       // template with {tmdb_id} / {season} / {episode}
        // May the failover chain land here automatically? A source with this
        // false still gets a button; it is just never reached on its own.
        val failover: Boolean = true,
        /**
         * Can the BACKEND extract this source?
         *
         * false means the button falls through to the embedded browser, which
         * costs the relay and keeps a browser engine resident for the whole
         * title. It is the expensive path, not a parallel one.
         *
         * Comes from the backend, which reads it off the one list that decides
         * it. Defaults true only because every source this build ships with is
         * extractable; an unknown name resolves through BUILTIN_EXTRACTABLE
         * rather than through this default.
         */
        val backendExtract: Boolean = true,
    )

    // ── Wire format ──────────────────────────────────────────────────────────
    data class Dto(
        @SerializedName("name")     val name: String?,
        @SerializedName("label")    val label: String?,
        @SerializedName("url")      val url: String?,
        @SerializedName("failover") val failover: Boolean? = null,
        // NULLABLE AND MEANINGFUL. Absent is not false: a backend that predates
        // this field, or one whose lookup failed, omits it, and the client then
        // falls back to what it shipped with instead of routing everything to
        // the embed.
        @SerializedName("backend_extract") val backendExtract: Boolean? = null,
    )

    data class FailoverDto(
        @SerializedName("enabled")      val enabled: Boolean? = null,
        @SerializedName("max_attempts") val maxAttempts: Int? = null,
    )

    data class Response(
        @SerializedName("sources")  val sources: List<Dto>?,
        @SerializedName("failover") val failover: FailoverDto? = null,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // BUILT-IN FALLBACK
    //
    // The exact list this build shipped with. Deliberately a copy rather than a
    // shorter "safe" subset: if the backend is unreachable the app should behave
    // exactly as it did before this feature existed, not subtly differently.
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * What THIS BUILD knows the backend could extract when it was compiled.
     *
     * Used only when the backend did not say -- an older server, or a response
     * that omitted the field. It is deliberately the full historical set rather
     * than the four currently enabled, so an older build pointed at a newer
     * config behaves as it always did.
     *
     * A NAME ABSENT FROM HERE AND UNANSWERED BY THE BACKEND resolves to false
     * and takes the embed. That is the safe direction: the embed always works,
     * it is merely expensive, whereas routing an unextractable source at
     * /api/extract gets a 400 and no playback at all.
     */
    internal val BUILTIN_EXTRACTABLE = setOf(
        "vidfast", "vidlove", "videasy", "vidspark",
        "moviesapi", "vidapi", "vidsrc", "vidnest",
    )

    /**
     * Whether a source is backend-extractable, given what the backend said.
     *
     * Pure, and separated from the lookup so the decision can be tested without
     * a Context, a network or a disk.
     */
    internal fun decideBackendExtract(name: String, fromApi: Boolean?): Boolean =
        fromApi ?: BUILTIN_EXTRACTABLE.contains(name.trim().lowercase())

    /**
     * Find [name] in whichever cached lists are loaded and report its flag,
     * falling back to the shipped set when no list holds it.
     *
     * BOTH LISTS, because the caller is a screen that knows a source name and
     * not which media type produced it, and a source carries the same answer on
     * either. Pure for the same reason as above.
     */
    internal fun extractableIn(lists: List<List<Source>?>, name: String): Boolean {
        val n = name.trim().lowercase()
        for (l in lists) {
            val hit = l?.firstOrNull { it.name.trim().lowercase() == n }
            if (hit != null) return hit.backendExtract
        }
        return decideBackendExtract(name, null)
    }

    /**
     * THE ONE ANSWER TO "should this button use the backend?".
     *
     * Reads the catalog the backend sent, so a source added to sources.conf
     * routes correctly without a client release. Falls back to the shipped set
     * when the catalog has not loaded yet.
     */
    fun canBackendExtract(name: String): Boolean =
        extractableIn(listOf(memTv, memMovie), name)

    private val BUILTIN_TV = listOf(
        Source("VidNest",   "Royal Jelly",   "https://vidnest.fun/tv/{tmdb_id}/{season}/{episode}"),
        Source("VidLove",   "Nectar Stream", "https://player.vidlove.cc/embed/tv/{tmdb_id}/{season}/{episode}"),
        Source("VidFast",   "Queen Stream",  "https://vidfast.pro/tv/{tmdb_id}/{season}/{episode}?autoPlay=true"),
        Source("MoviesAPI", "Purple Stream", "https://moviesapi.to/tv/{tmdb_id}/{season}/{episode}"),
    )
    private val BUILTIN_MOVIE = listOf(
        Source("VidNest",   "Royal Jelly",   "https://vidnest.fun/movie/{tmdb_id}"),
        Source("VidLove",   "Nectar Stream", "https://player.vidlove.cc/embed/movie/{tmdb_id}"),
        Source("VidFast",   "Queen Stream",  "https://vidfast.pro/movie/{tmdb_id}?autoPlay=true"),
        Source("MoviesAPI", "Purple Stream", "https://moviesapi.to/movie/{tmdb_id}"),
    )

    /**
     * Ceiling on how many sources a screen will render.
     *
     * The episode overlay allocates its focus-requester pool during composition,
     * before any list is known, and indexing past that pool is a crash. A
     * backend that suddenly enabled nine sources would do exactly that, so the
     * list is clamped here instead of trusted. Twelve is three times what ships
     * today and larger than every source the backend knows about.
     */
    const val MAX_SOURCES = 12

    @Volatile private var memTv: List<Source>? = null
    @Volatile private var memMovie: List<Source>? = null

    /**
     * Floor between fetches.
     *
     * THE THROTTLE LIVES HERE, NOT AT THE CALL SITES, because the callers
     * cannot be trusted to be rare: ProcessLifecycleOwner.onStart fires several
     * times for one user-visible return (the heartbeat beside it carries the
     * same warning), and the details screens refresh on every title opened.
     * Guarding each of those separately is three places to get it wrong; one
     * floor here makes calling it liberally the correct thing to do.
     *
     * Stamped on ATTEMPT rather than on success, so a backend that is down gets
     * asked once a minute instead of on every screen.
     */
    private const val MIN_REFRESH_MS = 60_000L

    @Volatile private var lastWarmAt = 0L

    /**
     * Chain settings, last told to us by the server.
     *
     * DEFAULTS ARE THE SAFE READING, not the useful one: failover OFF until the
     * backend says otherwise. A client that cannot reach the server should
     * behave as it did before this feature, not start silently trying sources
     * nobody chose.
     */
    @Volatile var failoverEnabled: Boolean = false
        private set
    @Volatile var maxAttempts: Int = 1
        private set

    private val gson = Gson()

    /**
     * The list to show, substituted and ready. Never blocks, never throws.
     *
     * `season` and `episode` are ignored for movies, so one signature serves
     * both screens.
     */
    fun sources(
        context: Context?,
        mediaType: String,
        tmdbId: Int,
        season: Int = 0,
        episode: Int = 0,
    ): List<Source> {
        val isMovie = mediaType.equals("movie", true)
        // A DISK HIT IS PROMOTED TO MEMORY, not just returned. canBackendExtract
        // answers from the memory lists alone -- it has no Context to read disk
        // with -- so without this a source the backend added would be on disk,
        // rendered as a button, and still misrouted to the embed on every cold
        // start until warm() came back. It also stops re-reading the file on
        // every episode.
        val cached = if (isMovie) memMovie else memTv
        val raw = cached
            ?: context?.let { readDisk(it, isMovie) }?.also {
                if (isMovie) memMovie = it else memTv = it
            }
            ?: (if (isMovie) BUILTIN_MOVIE else BUILTIN_TV)

        return raw.take(MAX_SOURCES).map { s ->
            s.copy(
                url = s.url
                    .replace("{tmdb_id}", tmdbId.toString())
                    .replace("{season}", season.toString())
                    .replace("{episode}", episode.toString())
            )
        }
    }

    /**
     * Fetch both lists and cache them. Safe to call often; safe to fail.
     *
     * BOTH TYPES IN ONE GO because a viewer who opens a film will open an
     * episode later and the second request would otherwise land while they are
     * waiting on it. Two small responses at launch costs nothing.
     *
     * An empty list from the server is treated as a FAILURE, not as an
     * instruction. A config that disables everything is indistinguishable from
     * a backend fault, and of those two readings only one is safe.
     */
    suspend fun warm(context: Context, force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastWarmAt != 0L && (now - lastWarmAt) < MIN_REFRESH_MS) return
        lastWarmAt = now
        for (isMovie in listOf(false, true)) {
            val type = if (isMovie) "movie" else "tv"
            try {
                val resp = ApiClient.bluehiveApi.getSources(type)
                if (!resp.isSuccessful) {
                    Log.w(TAG, "warm($type) http ${resp.code()} — keeping cache")
                    continue
                }
                val parsed = (resp.body()?.sources ?: emptyList()).mapNotNull { d ->
                    val n = d.name?.trim().orEmpty()
                    val u = d.url?.trim().orEmpty()
                    if (n.isEmpty() || u.isEmpty()) null
                    else Source(n, d.label?.trim().orEmpty().ifEmpty { n }, u,
                                failover = d.failover != false,
                                backendExtract = decideBackendExtract(n, d.backendExtract))
                }
                if (parsed.isEmpty()) {
                    Log.w(TAG, "warm($type) returned no usable sources — keeping cache")
                    continue
                }
                resp.body()?.failover?.let { f ->
                    failoverEnabled = f.enabled == true
                    maxAttempts = (f.maxAttempts ?: 1).coerceIn(1, 6)
                }
                if (isMovie) memMovie = parsed else memTv = parsed
                writeDisk(context, isMovie, parsed)
                Log.e(TAG, "sources($type): ${parsed.joinToString(",") { it.name }}")
            } catch (e: Exception) {
                Log.w(TAG, "warm($type) failed (${e.message}) — keeping cache")
            }
        }
    }

    /**
     * The next source to try after [afterName], or null to stop.
     *
     * WALKS DOWN ONLY, never wrapping. The order is a preference list, so a
     * viewer who deliberately picked the last entry has already rejected
     * everything above it — looping back to the top would override that choice
     * rather than help with it.
     *
     * Skips anything marked failover=false, and anything the app cannot extract
     * through the backend, so a source added to the config that needs client
     * code is shown but never auto-selected.
     */
    fun nextInChain(
        context: Context?,
        mediaType: String,
        tmdbId: Int,
        season: Int,
        episode: Int,
        afterName: String,
        canExtract: (String) -> Boolean,
    ): Source? {
        val chain = sources(context, mediaType, tmdbId, season, episode)
        return nextInChainFrom(chain, afterName, canExtract)
    }

    /**
     * The chain walk itself, with no Context and no IO, so it can be tested.
     *
     * Returns null when [afterName] is not in the chain at all -- a source the
     * catalog has since dropped must not silently restart the chain from the
     * top, which is what indexOfFirst returning -1 would do if it were treated
     * as "before the first entry".
     */
    internal fun nextInChainFrom(
        chain: List<Source>,
        afterName: String,
        canExtract: (String) -> Boolean,
    ): Source? {
        val i = chain.indexOfFirst { it.name.equals(afterName, true) }
        if (i < 0) return null
        return chain.drop(i + 1).firstOrNull { it.failover && canExtract(it.name) }
    }

    // ── Disk, so the device keeps a real list across restarts and offline ────
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readDisk(c: Context, isMovie: Boolean): List<Source>? = try {
        prefs(c).getString(if (isMovie) KEY_MOVIE else KEY_TV, null)?.let { json ->
            val t = object : TypeToken<List<Source>>() {}.type
            gson.fromJson<List<Source>>(json, t)?.takeIf { it.isNotEmpty() }
                ?.also { if (isMovie) memMovie = it else memTv = it }
        }
    } catch (e: Exception) {
        Log.w(TAG, "cached catalog unreadable (${e.message}) — using built-ins")
        null
    }

    private fun writeDisk(c: Context, isMovie: Boolean, list: List<Source>) {
        try {
            prefs(c).edit()
                .putString(if (isMovie) KEY_MOVIE else KEY_TV, gson.toJson(list))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "could not persist catalog: ${e.message}")
        }
    }
}
