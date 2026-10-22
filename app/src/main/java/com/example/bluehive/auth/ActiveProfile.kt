package com.example.bluehive.auth

/**
 * The profile this TV is on right now, in memory only: null on the picker.
 *
 * NOT SessionManager.lastProfileId. That one is persisted so a cold start can
 * prefetch the last profile's rows, which is exactly why it cannot say whether
 * anyone is on a profile now: after a launch it still names the last profile
 * while the picker is on screen.
 *
 * Sent as X-Profile-Id on every bluehive-api call and on the SSE connect, so a
 * TV locked out by this one can show who is on it (device_sessions.py, v360).
 *
 *   set    ProfileScreenActivity.goHome, and HomeScreenCompose.onCreate (which
 *          also covers Android restoring the home screen after process death)
 *   clear  ProfileScreenActivity.onResume: the picker is on screen
 */
object ActiveProfile {
    @Volatile var id: Int? = null

    /** The header value: the profile id, or "none" when no profile is active. */
    fun headerValue(): String = id?.takeIf { it > 0 }?.toString() ?: "none"
}
