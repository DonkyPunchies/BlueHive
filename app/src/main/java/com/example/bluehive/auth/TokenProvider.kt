package com.example.bluehive.auth

import android.util.Log
import com.example.bluehive.api.ApiClient
import com.example.bluehive.models.BhRefreshRequest
import kotlinx.coroutines.runBlocking

/**
 * Fresh access tokens, from BlueHive's own backend.
 *
 * REPLACES HostTokenProvider. That object bound to Off-Grid Drive over AIDL and
 * asked it for a token, which made BlueHive unable to start without a host. This
 * asks bluehive-api instead, using a refresh token BlueHive owns.
 *
 * THE RESULT TYPE IS DELIBERATELY UNCHANGED. [TokenResult] mirrors the old
 * HostToken sealed class — Fresh / Revoked / Unavailable — because four call
 * sites in ApiClient and DeviceEventStream already branch on exactly those three
 * cases and handle them correctly: retry, drop the session, or back off. Keeping
 * the shape means those sites change an import and nothing else, so the risky
 * part of this migration stays in one file instead of four.
 *
 * WHY THE DISTINCTION BETWEEN Revoked AND Unavailable STILL MATTERS
 * ----------------------------------------------------------------
 * They look similar and must not be merged. Revoked means the backend
 * affirmatively rejected the refresh token — the session is over and the app
 * should return to sign-in. Unavailable means we could not ask: no network, the
 * server is down, a timeout. Treating Unavailable as Revoked would sign users
 * out every time their router rebooted.
 */
sealed class TokenResult {
    /** A usable access token. Caller should retry with it. */
    data class Fresh(val token: String) : TokenResult()

    /** The backend refused the refresh token. The session is genuinely over. */
    object Revoked : TokenResult()

    /** We could not reach the backend. Say nothing about the session's validity. */
    object Unavailable : TokenResult()
}

object TokenProvider {

    private const val TAG = "TokenProvider"

    /** Serialises refreshes — see fetchFreshToken. */
    private val refreshLock = Any()

    /**
     * Exchange the stored refresh token for a new access token, persisting both.
     *
     * BLOCKING, because the OkHttp Authenticator that calls it is blocking — it
     * runs on OkHttp's own dispatcher thread, never the main thread.
     *
     * SINGLE-FLIGHT, and that is not optional. The backend ROTATES: every
     * successful refresh revokes the token it was handed. So two threads
     * refreshing with the same token means one succeeds and the other presents an
     * already-revoked token, gets 401, reports Revoked, and clears a perfectly
     * good session.
     *
     * MEASURED 2026-08-16: a single screen load produced four concurrent 401s,
     * four calls here with the identical stored token, and one 200 with three
     * 401s. The three losers wiped the session and the Continue Watching row went
     * blank.
     *
     * The fix is the snapshot on the line below. It is taken BEFORE the lock, so
     * a thread that waited can compare what it intended to spend against what is
     * stored now — if they differ, someone else already refreshed and its result
     * is reused instead of burning a second rotation. Taking the snapshot inside
     * the lock would read the value the winner just wrote and defeat the check.
     */
    fun fetchFreshToken(): TokenResult {
        val session = SessionManager.get()
        val intended = session.refreshToken          // BEFORE the lock. Deliberate.

        synchronized(refreshLock) {
            if (intended.isNullOrBlank()) {
                // Nothing to spend. Not "revoked" in the sense of the backend
                // refusing us — there is simply no session to refresh.
                Log.e(TAG, "no refresh token stored")
                return TokenResult.Revoked
            }

            // Someone refreshed while we waited. Their access token is current.
            val now = session.refreshToken
            if (now != intended) {
                val access = session.accessToken
                if (!access.isNullOrBlank()) {
                    Log.d(TAG, "another thread refreshed while waiting — reusing its token")
                    return TokenResult.Fresh(access)
                }
            }

            return try {
                runBlocking {
                    val resp = ApiClient.bluehiveApi.bhRefresh(BhRefreshRequest(intended))
                    when {
                        resp.isSuccessful && resp.body() != null -> {
                            val b = resp.body()!!
                            // Persist BOTH: the old refresh token is dead the
                            // moment this call succeeded.
                            session.updateTokens(b.accessToken, b.refreshToken)
                            Log.i(TAG, "refreshed access token")
                            TokenResult.Fresh(b.accessToken)
                        }
                        resp.code() == 401 || resp.code() == 403 -> {
                            // A 401 IS NOT AUTOMATICALLY THE END OF THE SESSION.
                            //
                            // This used to map every 401/403 to Revoked, which
                            // signs the user out and closes the app. But our own
                            // backend is not the only thing that can answer 401 —
                            // a gateway during a deploy, a proxy, a captive portal
                            // all can, and none of them mean the credential is
                            // dead. Someone mid-episode was being dropped out of a
                            // free app because a load balancer hiccuped.
                            //
                            // Only OUR backend sends a `code`. If we can read one,
                            // the answer is authoritative and the session really is
                            // over. If we cannot, something else answered, and the
                            // safe reading is "try again later" — the request fails
                            // but the session survives.
                            val code = runCatching {
                                val raw = resp.errorBody()?.string().orEmpty()
                                val d = org.json.JSONObject(raw).opt("detail")
                                if (d is org.json.JSONObject) d.optString("code") else ""
                            }.getOrDefault("")

                            when (code) {
                                "revoked", "token_expired" -> {
                                    Log.e(TAG, "refresh rejected (code=$code) — session over")
                                    TokenResult.Revoked
                                }
                                else -> {
                                    Log.e(TAG, "refresh got ${resp.code()} with no recognised " +
                                            "code — treating as transient, keeping session")
                                    TokenResult.Unavailable
                                }
                            }
                        }
                        else -> {
                            Log.e(TAG, "refresh failed HTTP ${resp.code()} — transient")
                            TokenResult.Unavailable
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "refresh unreachable (${e.message}) — transient")
                TokenResult.Unavailable
            }
        }
    }
}
