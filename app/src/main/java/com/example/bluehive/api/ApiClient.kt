package com.example.bluehive.api

import android.util.Log
import com.example.bluehive.BuildConfig
import com.example.bluehive.auth.SessionManager
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

private const val TAG = "ApiClient"

private const val PLATFORM_API_BASE_URL = "http://192.168.1.136:9000/"

// BlueHive streaming backend (port 8000). This is the SAME host that serves
// trailerApi (BuildConfig.API_BASE_URL), but profile/watch-history/favorites
// now live here too. These endpoints need Bearer auth + silent refresh (like
// platformApi) AND the X-API-Key (like trailerApi), so they get their own
// Retrofit instance below — bluehiveApi — that combines both.
private const val BLUEHIVE_API_BASE_URL = "http://192.168.1.136:8000/"



/**
 * ApiClient
 *
 * Two separate Retrofit instances:
 *
 *  1. [trailerApi]  — TMDB_Api_Server (media/trailer data)
 *                     Auth: X-API-Key static header from BuildConfig.
 *                     No user tokens involved.
 *
 *  2. [platformApi] — platform_accounts FastAPI (auth, user, device endpoints)
 *                     Auth: Authorization: Bearer <access_token>
 *                          X-Refresh-Token: <refresh_token>  (for device_id resolution)
 *                     On 401 → TokenAuthenticator silently refreshes and retries.
 *                     On definitive 401/403 from refresh → SessionExpiredBus notifies UI.
 *                     On network error / 5xx → session is NEVER cleared, request fails
 *                     gracefully so the user stays logged in during server restarts.
 */
object ApiClient {

    // ── Logging ───────────────────────────────────────────────────────────────

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.NONE
    }

    // ── 1. TMDB / Media API  (X-API-Key, no user tokens) ─────────────────────

    private val tmdbHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
            val key = BuildConfig.API_KEY
            if (key.isNotBlank()) req.addHeader("X-API-Key", key)
            chain.proceed(req.build())
        }
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val tmdbRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(tmdbHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    /** All media / trailer endpoints — getLatestTrailers, browseMedia, etc. */
    val trailerApi: ApiService = tmdbRetrofit.create(ApiService::class.java)





    /**
     * Host-backed authenticator for bluehiveApi. On a 401, instead of self-
     * refreshing (which would 403 — wrong device fingerprint), it asks the HOST
     * for a fresh access token over IPC, writes it into SessionManager, and
     * retries. If the host returns null (logout/unpair — the pull-path signal),
     * the session is dropped.
     */
    private val hostTokenAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            Log.d(TAG, "bluehiveApi 401 on ${response.request.url.encodedPath} — asking host for fresh token")

            if (response.request.header("X-Host-Retry") != null) {
                Log.w(TAG, "Already retried with host token, still 401 — giving up")
                return null
            }

            val result = com.example.bluehive.host.HostTokenProvider.fetchFreshToken()

            return when (result) {
                is com.example.bluehive.host.HostToken.Fresh -> {
                    SessionManager.get().setHostAccessToken(result.token)
                    Log.i(TAG, "Got fresh host token — retrying request")

                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${result.token}")
                        .header("X-Host-Retry", "1")
                        .build()
                }

                com.example.bluehive.host.HostToken.Revoked -> {
                    Log.w(TAG, "Host reports revoked — dropping session")
                    SessionManager.get().clearSession()
                    SessionExpiredBus.post()
                    null
                }

                com.example.bluehive.host.HostToken.Unavailable -> {
                    Log.w(TAG, "Host unavailable transiently — failing this request, keeping session")
                    null
                }

                else -> {
                    Log.w(TAG, "Unknown HostToken result for bluehiveApi — failing request safely")
                    null
                }
            }
        }
    }



    /**
     * Host-backed authenticator for platformApi.
     *
     * PHASE 2: BlueHive holds no host-valid refresh token. The legacy
     * tokenAuthenticator self-refreshed against /api/android/refresh using
     * BlueHive's OWN device fingerprint, which the backend rejects with 403
     * (the refresh token is bound to the HOST's device). That 403 then triggered
     * clearSession() + SessionExpiredBus — turning a harmless expected failure
     * into a full logout that bounced the user to the pairing screen.
     *
     * This replacement pulls a fresh token from the HOST over IPC, exactly like
     * bluehiveApi. The KEY difference from hostTokenAuthenticator: platformApi's
     * callers are all background/lifecycle pings (foreground/background/getMe/
     * checkSlot/endSession). A transient host failure on one of those must NOT
     * destroy the session. So on a null token we give up on THIS request only —
     * we never clearSession() or post SessionExpiredBus here. Genuine identity
     * loss is surfaced by bluehiveApi (real data calls) and DeviceEventStream
     * (the SSE revoke/unpair path), not by a lifecycle ping.
     */
    private val platformHostAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            Log.d(TAG, "platformApi 401 on ${response.request.url.encodedPath} — asking host for fresh token")

            if (response.request.header("X-Host-Retry") != null) {
                Log.w(TAG, "platformApi already retried with host token, still 401 — giving up on this request")
                return null
            }

            val result = com.example.bluehive.host.HostTokenProvider.fetchFreshToken()

            return when (result) {
                is com.example.bluehive.host.HostToken.Fresh -> {
                    SessionManager.get().setHostAccessToken(result.token)
                    Log.i(TAG, "Got fresh host token for platformApi — retrying request")

                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${result.token}")
                        .header("X-Host-Retry", "1")
                        .build()
                }

                com.example.bluehive.host.HostToken.Revoked,
                com.example.bluehive.host.HostToken.Unavailable -> {
                    Log.w(TAG, "Host gave no usable token for platformApi — failing this request only, keeping session")
                    null
                }

                else -> {
                    Log.w(TAG, "Unknown HostToken result for platformApi — failing request only, keeping session")
                    null
                }
            }
        }
    }




    // PHASE 2: bluehive-api reads the device from the JWT (require_user) and
    // IGNORES X-Refresh-Token. The platform refresh endpoint is fingerprint-bound
    // to the HOST's device, so BlueHive must NEVER self-refresh — it would 403 on
    // device mismatch. Instead: send only the host-supplied Bearer token (+ API
    // key), and on 401 pull a fresh token from the HOST over IPC. No refresh token
    // is attached, and the host-backed authenticator replaces tokenAuthenticator.
    private val bluehiveHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val session = SessionManager.get()
            val req = chain.request().newBuilder()
            // Host-supplied access token only. NO X-Refresh-Token — bluehive-api
            // ignores it and BlueHive holds no host-valid refresh token anyway.
            session.accessToken?.let { req.header("Authorization", "Bearer $it") }
            val key = BuildConfig.API_KEY
            if (key.isNotBlank()) req.header("X-API-Key", key)
            // Rule 9: X-BlueHive-Version flows on every bluehive-api call from day
            // one, even though the server ignores it for now. This keeps the future
            // hard version gate (426 Upgrade Required at bluehive-api) a backend-only
            // change with zero client update. Sent ONLY here — never on platform or
            // TMDB clients. versionCode is the integer the update manifest compares.
            req.header("X-BlueHive-Version", BuildConfig.VERSION_CODE.toString())
            chain.proceed(req.build())
        }
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 429) {
                val reason = try {
                    val peeked = response.peekBody(1024).string()
                    JSONObject(peeked).optJSONObject("detail")
                        ?.optString("reason")?.takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not parse 429 detail reason: ${e.message}")
                    null
                }
                Log.w(TAG, "Got 429 from ${chain.request().url.encodedPath} " +
                        "(reason=$reason) — posting lockout")
                LockoutBus.post(reason)
            }
            response
        }
        .addInterceptor(loggingInterceptor)
        .authenticator(hostTokenAuthenticator)   // host-backed refresh, NOT self-refresh
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val bluehiveRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BLUEHIVE_API_BASE_URL)
        .client(bluehiveHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    /** BlueHive user endpoints — profiles, watch-history, favorites. */
    val bluehiveApi: BluehiveApiService = bluehiveRetrofit.create(BluehiveApiService::class.java)



    private val platformHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            // Attach both tokens on every outgoing platform request:
            //   Authorization: Bearer <access_token>  — for authentication
            //   X-Refresh-Token: <refresh_token>      — so the server can resolve device_id
            val session = SessionManager.get()
            val req = chain.request().newBuilder()
            session.accessToken?.let  { req.header("Authorization",  "Bearer $it") }
            session.refreshToken?.let { req.header("X-Refresh-Token", it) }
            chain.proceed(req.build())
        }
        // ── Workspace-lockout interceptor ────────────────────────────────────
        // 429 from any platform endpoint means the workspace's 6-active gate
        // rejected this TV. Post to LockoutBus so whatever Activity is on top
        // can route the user to the lockout screen. The response is still
        // returned to the caller so the original request site can also handle
        // it (e.g. HeartbeatManager logs the rejection).
        //
        // Lives BEFORE the logging interceptor so it sees 429s before they're
        // logged out at INFO level — easier to spot during debugging.
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 429) {
                // Peek the body (up to 1KB) so we can read `detail.reason`
                // without consuming the stream the route handler will read.
                // The server returns: { "detail": { "reason": "...", "message": "..." } }
                val reason = try {
                    val peeked = response.peekBody(1024).string()
                    val detail = JSONObject(peeked).optJSONObject("detail")
                    detail?.optString("reason")?.takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    // Malformed/non-JSON body — fall through with null reason.
                    // LockoutActivity has a generic fallback copy for this case.
                    Log.w(TAG, "Could not parse 429 detail reason: ${e.message}")
                    null
                }
                Log.w(TAG, "Got 429 from ${chain.request().url.encodedPath} " +
                        "(reason=$reason) — posting lockout")
                LockoutBus.post(reason)
            }
            response
        }
        .addInterceptor(loggingInterceptor)
        .authenticator(platformHostAuthenticator)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // connectionPool: deliberately omitted to use OkHttp's defaults
        // (5 idle connections, 5 minute keepalive). The previous config of
        // ConnectionPool(0, 1, MILLISECONDS) disabled pooling entirely, forcing
        // every API call to pay a full TCP handshake. retryOnConnectionFailure
        // above already handles stale connections, so pooling is pure win here.
        .build()

    private val platformRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(PLATFORM_API_BASE_URL)
        .client(platformHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    /** All platform endpoints — /api/me, /api/me/devices, /api/android/refresh, etc. */
    val platformApi: PlatformApiService = platformRetrofit.create(PlatformApiService::class.java)
}

// ─────────────────────────────────────────────────────────────────────────────
// SessionExpiredBus
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Lightweight event bus for propagating session expiry to the active UI.
 *
 * The TokenAuthenticator runs on a background OkHttp thread and can't touch
 * Activities directly. When it gives up on refreshing, it calls [post] here.
 * Any Activity that cares registers a lambda in onResume and deregisters in onPause.
 *
 * Usage in Activity:
 *
 *     private val expiredListener = { goToLogin() }
 *     override fun onResume()  { SessionExpiredBus.register(expiredListener) }
 *     override fun onPause()   { SessionExpiredBus.unregister(expiredListener) }
 */
object SessionExpiredBus {

    private val listeners = mutableListOf<() -> Unit>()

    // Latches to true on first post and stays there for the process lifetime.
    // Session expiry is a one-way door: once it fires, the app routes to the
    // pairing screen and the user has to re-pair. Any subsequent post would
    // be a duplicate from a race in TokenAuthenticator (multiple concurrent
    // 401s arriving at the same dead refresh token) and listeners should not
    // see those duplicates. Reset to false explicitly is not needed — a
    // successful pairing creates a fresh process state via Application.onCreate.
    @Volatile private var posted = false

    fun register(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun unregister(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    /** Safe to call from any thread — dispatches to main thread before invoking listeners. */
    fun post() {
        if (posted) {
            Log.d("SessionExpiredBus", "Already posted — ignoring duplicate")
            return
        }
        posted = true
        Log.w("SessionExpiredBus", "Posting session expiry to ${listeners.size} listener(s)")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            synchronized(listeners) { listeners.toList() }.forEach { it() }
        }
    }
}