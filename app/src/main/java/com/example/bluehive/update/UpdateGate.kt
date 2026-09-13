package com.example.bluehive.update

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.example.bluehive.IdleWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one place that decides whether this TV may keep using the app.
 *
 * MODELLED ON HostEntitlementRelay.checkNow, deliberately and closely. That
 * mechanism answers the same shape of question — "has something changed on the
 * server that should stop this device?" — and it has been correct on this
 * hardware for months. Same hook (HomeScreenCompose.onResume), same rate floor,
 * same off-the-main-thread shape, same rule of acting only on a definite answer.
 *
 * WHY NOT ON THE LAUNCH PATH, WHERE IT USED TO LIVE
 * -------------------------------------------------
 * AuthActivity used to run this check before it would route anywhere, which put
 * a network call in front of every cold start. Two failures came out of that,
 * both measured on real hardware:
 *
 *   1. It timed out and silently skipped the update on 4 of 6 cold launches. The
 *      budget had to stay small precisely because it blocked the launch, and no
 *      value works: bigger misses fewer updates but lengthens the wait, smaller
 *      does the reverse.
 *
 *   2. It raced the other checker. ProcessLifecycleOwner.onStart fires during the
 *      same cold start, so two checks ran, both found the update, and both
 *      launched the updater with NEW_TASK|CLEAR_TASK — flags that target the
 *      caller's OWN task. The second one destroyed the task the first had just
 *      rooted, leaving a window that never drew while 163MB downloaded unseen.
 *      To the user the app icon simply did nothing.
 *
 * Off the launch path, neither failure is possible. Nothing waits on the network,
 * so a slow answer costs a slightly later prompt instead of a missed update or a
 * blank screen; and there is exactly one checker, so there is nothing to race.
 *
 * THE GATE IS THE HOME SCREEN, and that one hook is enough for the same reason
 * the entitlement check gives: every route back into the app — the profile
 * picker, a detail page, settings, the TV launcher — resumes HomeScreenCompose.
 * A cold start reaches it too, after the loading screen, which is exactly where
 * an update prompt belongs.
 */
object UpdateGate {

    private const val TAG = "UpdateGate"

    /**
     * Minimum gap between checks. Mirrors HostEntitlementRelay's floor and exists
     * for the same reason: onResume can fire twice for one user-visible return,
     * and bouncing in and out of a detail page must not cost a request each time.
     */
    private const val CHECK_FLOOR_MS = 3_000L

    /**
     * How long the home screen is allowed to settle before anything can replace
     * it.
     *
     * Not cosmetic. Landing on the home screen and having it yanked away in the
     * same frame reads as a crash, and on a cold start this fires while the
     * shelves are still populating. A couple of seconds lets the user see where
     * they are before being told they have to update — the difference between an
     * app that interrupted you and one that appeared to fail.
     */
    private const val SETTLE_MS = 2_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastCheckAt = 0L

    /**
     * True once we have routed to the updater in this process, so a second
     * onResume cannot stack a second copy on top of the first.
     */
    @Volatile private var routed = false

    /**
     * The in-flight check, awaitable exactly once per process.
     *
     * A Deferred rather than a result field, so the loading screen can WAIT for
     * the answer. When this was a plain field the only thing a caller could do
     * was look at it, and a look that arrives before the network does is a race
     * — one this gate lost often enough to send users past the profile picker
     * and only then to the updater.
     */
    @Volatile private var inflight: Deferred<UpdateManager.Manifest?>? = null

    /**
     * Start a NEW launch's check. Call once, when the loading screen appears.
     *
     * The manifest fetch overlaps time the user is already spending rather than
     * adding any of its own. Nothing waits on it here; awaitAndRoute() does the
     * waiting once the loading animation is done.
     *
     * THIS USED TO BE `if (routed || inflight != null) return`, AND THAT IS WHY
     * THE LOADING SCREEN NEVER CAUGHT AN UPDATE.
     *
     * This is an `object`, so both fields live as long as the PROCESS. A launch
     * is not a process: closing BlueHive runs finishAffinity() and deliberately
     * does NOT kill it (see performClose — the Back button leaves it warm on
     * purpose, so coming straight back is not a cold start). MEASURED on the onn
     * box: back out, reopen, same pid.
     *
     * So on the second and every later open in that warm process, `inflight` was
     * still the FINISHED Deferred from the first open. This returned early, no
     * fetch happened, and awaitAndRoute() then awaited an already-completed job
     * and got the previous launch's answer instantly. When that answer was "no
     * update" — which it always is, because the first open is normally before the
     * release exists — the loading screen waved the user through every single
     * time. HomeScreenCompose's checkNow() calls checkForUpdate() directly rather
     * than reading this field, so it was the only path doing a real fetch, and
     * therefore the only one that ever found anything. The symptom was that the
     * home screen "fallback" caught 100% of updates and the launch path caught
     * none, which reads as a timing problem and is not one.
     *
     * ONLY AN IN-FLIGHT CHECK MAY BE REUSED. isActive is the whole distinction:
     * still running means this same launch already asked, so collapse the call;
     * completed means the answer belongs to a launch that is over, and reusing it
     * is answering a new question with an old reply.
     *
     * `routed` IS CLEARED FOR THE SAME REASON. suppressNext() sets it to stop an
     * updater loop, and its own docs say "the next launch is a fresh process and
     * checks again" — an assumption this warm process breaks. Without clearing
     * it, declining one optional update stopped every check, on every screen,
     * until something finally killed the process.
     */
    fun prefetch() {
        val current = inflight
        if (current != null && current.isActive) return
        routed = false
        // HELD AS A Deferred, not written to a field on completion. The loading
        // screen needs to AWAIT this answer, and a plain field can only be
        // sampled -- which is what made the gate a race it sometimes lost, and
        // sent the user to the profile picker before the update screen.
        inflight = scope.async {
            val m = runCatching { UpdateManager.checkForUpdate() }
                .onFailure { Log.e(TAG, "prefetch threw: ${it.message}") }
                .getOrNull()
            // Log.e both ways: R8 keeps only e in release, and "did the check
            // land, and what did it say" is the single most useful line here.
            // checkForUpdate() returns null for BOTH "up to date" and "could not
            // reach the server" -- fetchManifest swallows its own errors -- so
            // this cannot distinguish them, but it does prove the call finished.
            Log.e(TAG, if (m == null) "prefetch: no update (or unreachable)"
                       else "prefetch: ${m.latestVersionCode} available")
            m
        }
    }

    /**
     * Wait for the prefetched answer, then route if this device is behind.
     *
     * REPLACES A GLANCE WITH A WAIT. The previous version read whatever had
     * already landed and returned false if nothing had, which made the gate a
     * race against the loading animation — and when it lost, the user reached the
     * profile picker and got pulled to the updater afterwards, which is the whole
     * thing this was meant to stop.
     *
     * Waiting here is cheap in a way that waiting on the launch path was not: the
     * loading screen is drawn, animating, and indistinguishable from any other
     * moment of loading. [timeoutMs] is a backstop for a black-holed network, not
     * the normal path — the fetch starts at onCreate and has usually finished
     * several seconds before this is called.
     *
     * @return true when the updater has been started; the caller must not
     *         continue to its normal destination.
     */
    suspend fun awaitAndRoute(activity: Activity, timeoutMs: Long): Boolean {
        if (routed) return false
        val job = inflight ?: return false

        val update = withTimeoutOrNull(timeoutMs) { job.await() }
        if (update == null) {
            // Either no update, or we could not find out in time. Both mean the
            // same thing here: carry on. HomeScreenCompose still checks, so a
            // slow answer costs a later prompt rather than a missed update.
            Log.e(TAG, "no update to route at the loading screen")
            return false
        }

        if (IdleWatcher.isPlaying()) {
            Log.e(TAG, "update available but something is playing — deferring")
            return false
        }

        // Same rule as checkNow: the latch follows the start, it does not precede
        // it. Much safer here -- this runs on the main thread from a loading
        // screen that is on screen right now -- but two routing paths that latch
        // by different rules is how one of them quietly drifts wrong.
        if (!launchUpdater(activity, update)) {
            Log.e(TAG, "updater start failed at the loading screen — leaving the gate armed")
            return false
        }
        routed = true
        Log.e(TAG, "update ${update.latestVersionCode} required — routed before the profile picker")
        return true
    }

    /**
     * Check, and if this device is behind, hand it to the updater.
     *
     * Safe to call from onResume on every return to the home screen; it is rate
     * limited, runs off the main thread, and does nothing at all in the common
     * case where the device is already current.
     *
     * @param immediate skip the settle delay — used by the operator push, where
     *                  the whole point is that it acts now.
     */
    fun checkNow(activity: Activity, immediate: Boolean = false) {
        if (routed) return

        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (!immediate && now - lastCheckAt < CHECK_FLOOR_MS) return
            lastCheckAt = now
        }

        // HELD WEAKLY. `scope` is process-wide and never cancelled, and the work
        // below spans the settle delay plus a manifest fetch that is allowed 20s.
        // A strong capture would pin this Activity, and everything it retains, for
        // that whole window even after the user has navigated away from it.
        val hostRef = java.lang.ref.WeakReference(activity)

        scope.launch {
            if (!immediate) delay(SETTLE_MS)

            val update = runCatching { UpdateManager.checkForUpdate() }
                .onFailure {
                    // Log.e: R8 strips v/d/i/w from release builds, and a device
                    // that can never see an update is precisely the failure an
                    // operator needs to be able to find.
                    Log.e(TAG, "update check failed — will retry on next return: ${it.message}")
                }
                .getOrNull() ?: return@launch

            // NOT DURING PLAYBACK. The home-screen hook makes this nearly
            // unreachable — you cannot be on the home screen and watching — but
            // the operator push can arrive at any moment, and taking the screen
            // away from someone mid-film to install an update is the one outcome
            // worse than shipping late. IdleWatcher already tracks this.
            if (IdleWatcher.isPlaying()) {
                Log.e(TAG, "update available but something is playing — deferring")
                return@launch
            }

            // THE WINDOW MAY BE GONE BY NOW. Between arriving on the home screen
            // and reaching this line, SETTLE_MS plus the whole manifest fetch have
            // elapsed, and the user can leave the app inside that window. An
            // activity start from a backgrounded app is dropped SILENTLY, with no
            // exception to catch, so this has to be checked up front.
            val host = hostRef.get()
            if (!canStartFrom(host)) {
                // DELIBERATELY DOES NOT LATCH. Latching here is exactly how an
                // update goes missing for the rest of the process: the start
                // evaporates, nothing reaches the screen, and every later return
                // hits `if (routed) return` and does nothing. Leaving the gate
                // armed costs one more check on the next return, which is the
                // cheap direction to be wrong in.
                Log.e(TAG, "window gone before routing — leaving the gate armed")
                return@launch
            }

            // LATCHED ONLY ON A START THAT ACTUALLY HAPPENED. This used to be set
            // before launchUpdater ran, on the assumption that the start could not
            // fail. It can, and when it did the gate stayed shut for the life of
            // the process with nothing on screen to show for it.
            val started = withContext(Dispatchers.Main) { launchUpdater(host!!, update) }
            if (started) {
                routed = true
                Log.e(TAG, "update ${update.latestVersionCode} required — routed to updater")
            } else {
                Log.e(TAG, "updater start failed — leaving the gate armed for the next return")
            }
        }
    }

    /**
     * Start SelfUpdateActivity. Main thread only.
     *
     * STARTED FROM THE ACTIVITY, not the Application, and with no task flags.
     * Both matter. An Activity start carries a visible window, which is what
     * keeps it inside Android 14's background-activity rules; and
     * NEW_TASK|CLEAR_TASK would target the caller's OWN task and tear it down,
     * which is the bug that made launches invisible.
     *
     * finish() is left to the caller, because the right answer differs. The
     * home-screen path keeps its activity alive underneath — the updater sits on
     * top of a screen worth returning to. The loading screen finishes itself:
     * there is nothing to go back to, and leaving it in the stack would flash it
     * again on the way out.
     */
    private fun launchUpdater(activity: Activity, update: UpdateManager.Manifest): Boolean =
        runCatching {
            activity.startActivity(
                Intent(activity, SelfUpdateActivity::class.java).apply {
                    putExtra(SelfUpdateActivity.EXTRA_VERSION_CODE, update.latestVersionCode)
                    putExtra(SelfUpdateActivity.EXTRA_VERSION_NAME, update.latestVersionName)
                    putExtra(SelfUpdateActivity.EXTRA_APK_URL, update.apkUrl)
                    putExtra(SelfUpdateActivity.EXTRA_SHA256, update.sha256)
                    putExtra(SelfUpdateActivity.EXTRA_MIN_SUPPORTED, update.minSupported)
                }
            )
            true
        }.getOrElse {
            // GUARDED, AND IT REPORTS RATHER THAN THROWS. checkNow runs this from
            // a bare CoroutineScope(SupervisorJob() + Dispatchers.IO) with no
            // exception handler, so anything escaping here reaches the thread's
            // default handler and kills the process. Every other call in that
            // coroutine was already inside runCatching; this one was the hole.
            //
            // The Boolean is what lets the caller avoid latching `routed` on a
            // start that never happened.
            Log.e(TAG, "could not start the updater: ${it.message}")
            false
        }

    /**
     * Is this Activity still able to legally start another one?
     *
     * Android only allows an activity start from an app with a visible window
     * (BAL_ALLOW_VISIBLE_WINDOW). A start from a backgrounded app is dropped
     * SILENTLY -- no exception, no failure, nothing to catch -- so the only way
     * to avoid latching the gate on a start that will evaporate is to check the
     * window is still there first.
     *
     * STARTED, not RESUMED, because that is the state that actually maps to
     * "has a visible window": an activity behind the system install dialog is
     * STARTED but not RESUMED, and starts from it are still permitted.
     */
    private fun canStartFrom(activity: Activity?): Boolean {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return false
        val owner = activity as? LifecycleOwner ?: return true
        return owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    /**
     * Do not route again for the life of this process.
     *
     * Called when the updater hands control back — SelfUpdateActivity re-enters
     * AuthActivity with EXTRA_SKIP_UPDATE_CHECK after an install the user
     * declined or that failed. Without this the very next home screen would route
     * straight back to the updater and the user would be in a loop they could not
     * leave except by killing the app.
     *
     * ONLY REACHABLE FOR AN OPTIONAL UPDATE. A required one never hands control
     * back: SelfUpdateActivity stays put and says so, which is the hard block
     * working as intended. The next launch is a fresh process and checks again.
     */
    fun suppressNext() {
        routed = true
        // Drop the in-flight answer too. Without this the loading screen on the
        // way back in would await a stale "update available" and route straight
        // back to the updater the user just came out of.
        inflight = null
    }
}
