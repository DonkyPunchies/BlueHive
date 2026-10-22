package com.example.bluehive.api

import com.example.bluehive.auth.ActiveProfile
import com.example.bluehive.auth.HostEntitlementRelay
import android.util.Log
import com.example.bluehive.BuildConfig
import com.example.bluehive.auth.SessionManager
import com.example.bluehive.net.pinToLan
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

/**
 * Retrofit REQUIRES a base URL ending in "/" and throws IllegalArgumentException
 * at construction if it does not — which, from a `val` initialised at class load,
 * surfaces as an ExceptionInInitializerError on first API use rather than
 * anything that names the URL.
 *
 * This is not hypothetical tidiness. BuildConfig.PLATFORM_BASE_URL is built from
 * BLUEHIVE_DEV_PLATFORM_URL, whose default in app/build.gradle.kts is
 * "http://192.168.1.136:9000" — NO trailing slash — while the hardcoded constant
 * this replaced had one. Wiring the two together without normalising would have
 * traded a wrong-address bug for a crash on launch.
 *
 * Applied to every base URL so a future value set from a Gradle property, an
 * environment variable or a CI secret cannot reintroduce it.
 */
private fun String.asBaseUrl(): String = if (endsWith("/")) this else "$this/"

// ── Backend addresses ────────────────────────────────────────────────────────
// ALL THREE now come from BuildConfig, and that is the point.
//
// Two of them used to be hardcoded here while only trailerApi read the build
// config. On the LAN that was invisible: every address pointed at the same
// machine, so it looked like one setting. It is really a TRIPWIRE for the move
// to a VPS — changing the Gradle property would have relocated media/extraction
// while login, profiles and watch history kept dialling 192.168.1.136. The
// failure mode is not "wrong address" either: it is the 30-second blank launch
// and "Set up BlueHive" toast, which reads as an unreachable backend and sends
// you hunting for a server problem that does not exist.
//
// Defaults in build.gradle.kts are unchanged, so behaviour today is identical.
// Moving to a VPS is now three Gradle properties and no code edit:
//   BLUEHIVE_DEV_BASE_URL, BLUEHIVE_DEV_PLATFORM_URL, BLUEHIVE_DEV_UPDATE_URL

// Platform backend (port 9000): login, pairing, account, device sessions.
private val PLATFORM_API_BASE_URL = BuildConfig.PLATFORM_BASE_URL.asBaseUrl()

// BlueHive streaming backend (port 8000). This is the SAME host that serves
// trailerApi (BuildConfig.API_BASE_URL), but profile/watch-history/favorites
// now live here too. These endpoints need Bearer auth + silent refresh (like
// platformApi) AND the X-API-Key (like trailerApi), so they get their own
// Retrofit instance below — bluehiveApi — that combines both.
//
// Deliberately the SAME BuildConfig value as trailerApi rather than a second
// property: they are one server, and two knobs for one address is how they drift
// apart.
private val BLUEHIVE_API_BASE_URL = BuildConfig.API_BASE_URL.asBaseUrl()



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
        // API_BASE_URL resolves to 192.168.1.136:8000 in BOTH debug and release
        // (BLUEHIVE_DEV_BASE_URL), so this is LAN traffic despite the "tmdb" name.
        .pinToLan(BLUEHIVE_API_BASE_URL)
        .build()

    private val tmdbRetrofit: Retrofit = Retrofit.Builder()
        // Normalised like the other two — this one happens to carry a trailing
        // slash in its default today, but relying on that is how the next person
        // to set BLUEHIVE_DEV_BASE_URL from a shell or CI gets a launch crash.
        .baseUrl(BLUEHIVE_API_BASE_URL)
        .client(tmdbHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    /** All media / trailer endpoints — getLatestTrailers, browseMedia, etc. */
    val trailerApi: ApiService = tmdbRetrofit.create(ApiService::class.java)





    /**
     * Refresh-on-401 authenticator for bluehiveApi. It calls
     * [TokenProvider.fetchFreshToken], writes the result into SessionManager, and
     * retries. On [TokenResult.Revoked] the session is dropped; on
     * [TokenResult.Unavailable] it backs off without touching the session.
     *
     * NOT HOST-BACKED ANY MORE, DESPITE THE NAME. This used to bind Off-Grid
     * Drive over AIDL and ask it for a token, because BlueHive held no refresh
     * token of its own and self-refreshing would 403 on a device-fingerprint
     * mismatch. TokenProvider replaced HostTokenProvider: BlueHive now owns a
     * refresh token and spends it against its OWN backend over plain HTTP. There
     * is no IPC anywhere on this path.
     *
     * The identifier is left as `hostTokenAuthenticator` only because renaming it
     * touches three call sites for no behavioural gain; read "host" here as
     * historical, not descriptive.
     */
    private val hostTokenAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            Log.d(TAG, "bluehiveApi 401 on ${response.request.url.encodedPath} — asking host for fresh token")

            if (response.request.header("X-Host-Retry") != null) {
                Log.e(TAG, "Already retried with host token, still 401 — giving up")
                return null
            }

            val result = com.example.bluehive.auth.TokenProvider.fetchFreshToken()

            return when (result) {
                is com.example.bluehive.auth.TokenResult.Fresh -> {
                    SessionManager.get().setHostAccessToken(result.token)
                    Log.i(TAG, "Got fresh host token — retrying request")

                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${result.token}")
                        .header("X-Host-Retry", "1")
                        .build()
                }

                com.example.bluehive.auth.TokenResult.Revoked -> {
                    Log.e(TAG, "Host reports revoked — dropping session")
                    SessionManager.get().clearSession()
                    SessionExpiredBus.post()
                    null
                }

                com.example.bluehive.auth.TokenResult.Unavailable -> {
                    Log.e(TAG, "Host unavailable transiently — failing this request, keeping session")
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
     * This replacement refreshes through TokenProvider against BlueHive's own
     * backend, exactly like bluehiveApi — no IPC, despite what the names suggest.
     * The KEY difference from hostTokenAuthenticator: platformApi's
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

            val result = com.example.bluehive.auth.TokenProvider.fetchFreshToken()

            return when (result) {
                is com.example.bluehive.auth.TokenResult.Fresh -> {
                    SessionManager.get().setHostAccessToken(result.token)
                    Log.i(TAG, "Got fresh host token for platformApi — retrying request")

                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${result.token}")
                        .header("X-Host-Retry", "1")
                        .build()
                }

                com.example.bluehive.auth.TokenResult.Revoked,
                com.example.bluehive.auth.TokenResult.Unavailable -> {
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




    // bluehive-api reads the device from the JWT (require_user) and IGNORES
    // X-Refresh-Token, so no refresh token is attached to these requests. On a
    // 401, hostTokenAuthenticator refreshes through TokenProvider against
    // bluehive-api's own /auth/refresh.
    //
    // THE PHASE 2 RULE ABOVE THIS LINE IS DEAD, and it inverted the truth. It
    // said BlueHive must NEVER self-refresh, because the platform refresh
    // endpoint was fingerprint-bound to the HOST's device and would 403 on a
    // mismatch — true while tokens came from Off-Grid Drive over AIDL. BlueHive
    // owns its own refresh token now and spends it on its own backend, so
    // self-refresh is not merely allowed, it is the only mechanism left.
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
            // The profile this TV is on ("none" on the picker), so the server can
            // tell a locked-out TV who holds each slot. See ActiveProfile.
            req.header("X-Profile-Id", ActiveProfile.headerValue())
            // Session-lifecycle endpoints (check-slot / end-session / background /
            // foreground) are keyed by device fingerprint server-side. Send it on
            // every bluehive-api call so they match the SSE connect's fingerprint.
            // Guarded: deviceFingerprint throws if SessionManager isn't initialised.
            runCatching { session.deviceFingerprint }.getOrNull()?.let {
                req.header("X-Device-Fingerprint", it)
            }
            chain.proceed(req.build())
        }
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 429) {
                val reason = try {
                    // 16 KB, not 1 KB: the workspace_full detail now lists the slot
                    // holders, and a cut-off body parses as nothing at all.
                    val peeked = response.peekBody(16 * 1024L).string()
                    val detail = JSONObject(peeked).optJSONObject("detail")
                    LockoutBus.rememberSlotLimit(detail)   // the server's slot count
                    LockoutBus.rememberHolders(detail)     // ...and who holds them
                    detail?.optString("reason")?.takeIf { it.isNotBlank() }
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
        // ── Server says this account is not entitled ──────────────────────────
        // THE SERVER IS THE AUTHORITY, and this interceptor is what makes the
        // lock real rather than cosmetic. The client's own verdict lives in
        // SharedPreferences, which app data can wipe, and the retry button
        // deliberately fails open when the network is down. Neither is a bypass
        // any more: every gated endpoint answers 403 whatever the client believes
        // about itself, and this turns that answer back into the lock screen.
        //
        // MATCHED ON THE CODE, never on the status alone. 403 is a general "no"
        // that other endpoints use for their own reasons, and treating all of
        // them as an entitlement lock would boot people out over unrelated
        // failures.
        //
        // peekBody, not body(): reading the body here would consume it and the
        // actual caller would get an empty response.
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 403) {
                val code = runCatching {
                    JSONObject(response.peekBody(1024).string())
                        .optJSONObject("detail")?.optString("code")
                }.getOrNull()
                if (code == "entitlement_inactive") {
                    Log.w(TAG, "403 entitlement_inactive from " +
                            "${chain.request().url.encodedPath} — locking")
                    HostEntitlementRelay.markInactiveFromServer()
                }
            }
            response
        }
        .addInterceptor(loggingInterceptor)
        .authenticator(hostTokenAuthenticator)   // host-backed refresh, NOT self-refresh
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // :8000 is on the LAN. Without this a connected VPN routes profiles and
        // watch history into the tunnel, where 192.168.1.136 does not exist.
        .pinToLan(BLUEHIVE_API_BASE_URL)
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
                    // 16 KB, not 1 KB: the workspace_full detail now lists the slot
                    // holders, and a cut-off body parses as nothing at all.
                    val peeked = response.peekBody(16 * 1024L).string()
                    val detail = JSONObject(peeked).optJSONObject("detail")
                    LockoutBus.rememberSlotLimit(detail)   // the server's slot count
                    LockoutBus.rememberHolders(detail)     // ...and who holds them
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
        // :9000 is on the LAN. This is the client whose failure produces the 30s
        // blank launch + "Set up BlueHive" toast — indistinguishable from Docker
        // being down, which is exactly how a VPN made it look.
        .pinToLan(PLATFORM_API_BASE_URL)
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