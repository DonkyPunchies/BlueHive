package com.example.bluehive.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.bluehive.api.ApiClient
import com.example.bluehive.models.HostEntitlementRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Carries the host's account-standing assertion to our own backend.
 *
 * THIS GATES ACCESS. It did not always, and the header here said the opposite for
 * longer than it was true. It began as pure telemetry, so the operator could
 * answer one question — how many BlueHive accounts sit behind a host subscription
 * that has lapsed. On 2026-08-21 the verdict it carries became the thing that
 * locks the app, and the server started enforcing the same value independently
 * (bh_auth.require_user answers 403 on 'inactive').
 *
 * We are still only the courier's last leg: the decision is the SERVER's, and
 * `standing` in its response is what we act on — never the token's contents,
 * which we cannot read and must not try to. What changed is that the answer now
 * has consequences, which is why the fail-open rule in applyStanding below is the
 * safety property of this whole file rather than a nicety.
 *
 * WHY THIS RUNS TWICE PER LAUNCH
 * ------------------------------
 * The host publishes its assertion through a ContentProvider that is deliberately
 * CACHE-ONLY — a provider call blocks the caller's thread, and that caller is our
 * cold start, so it must never touch the network. That is the right trade, but it
 * has a consequence worth stating plainly:
 *
 * When our provider query is what STARTS the host's process (its own process had
 * been reclaimed, or the TV rebooted), the host has not yet had a chance to learn
 * anything. It comes up, connects its event stream, and only then discovers the
 * account's current standing. Our query is serviced in the milliseconds before all
 * of that — so the very first read after a cold host process returns whatever was
 * last written to disk, which is precisely the value that is wrong if the account
 * changed while nothing was running.
 *
 * So we read again a few seconds later, once the host has had time to catch up.
 * By then its wallet holds the current assertion and the second report corrects
 * the first. Without this, a subscription that lapsed while the TV was off is
 * reported as still good until the user next opens the host app themselves.
 *
 * The second pass is cheap and usually a no-op: identical token, same verdict
 * written again. Correctness on the launch that matters is worth one extra POST.
 *
 * EVERY FAILURE IS SILENT. Offline, backend down, provider unreadable — all mean
 * "nothing to report", none is worth an error, and none may ever delay or block a
 * launch.
 *
 * "NO HOST INSTALLED" IS NO LONGER A FAILURE — IT IS A FINDING. It used to sit in
 * that list, and that is precisely why a machine could be stripped of its host and
 * carry on replaying a saved pass until the pass rotted on its own, and why making
 * a fresh account on that machine wiped the slate entirely. hostPresenceReport()
 * now asks Android directly and reports a CONFIRMED absence, which marks the
 * hardware rather than the account. Everything uncertain still falls back to
 * silence — that function is where the line between the two is drawn.
 */
object HostEntitlementRelay {

    private const val TAG = "HostRelay"

    /** Names a CAPABILITY, not a product. Any host implementing it works. */
    private const val PROVIDER_URI = "content://io.host.entitlement.provider/assertion"
    private const val COLUMN = "assertion"

    /**
     * Whether the host still holds a session. Published by hosts from
     * 2026-09-12; absent on older ones, which is why every read of it tolerates
     * a missing column rather than treating it as a negative.
     */
    private const val COLUMN_PAIRED = "paired"

    /** Authority only, for asking Android whether a host exists at all. */
    private const val PROVIDER_AUTHORITY = "io.host.entitlement.provider"

    private const val PREFS = "host_entitlement"
    private const val KEY   = "assertion"

    // The absence debounce that used to live here is gone, along with the two
    // preference keys that carried its state. It demanded three readings spanning
    // ten minutes before it would admit the host was missing, which in practice
    // meant three cold starts of this app before an uninstalled host had any
    // effect. Absence is now reported the moment it is observed; see
    // [hostPresenceReport] for why that trade is the right way round.

    /** Last standing the SERVER returned. Survives restarts so the lock does. */
    private const val KEY_STANDING = "standing"

    /**
     * The one value that locks the app. Matched exactly, lowercased.
     *
     * Named as a constant rather than inlined so the fail-open rule is greppable:
     * every place that can lock a user out should reference this and nothing else.
     */
    const val STANDING_INACTIVE = "inactive"

    /** How long to wait before the corrective second read. */
    private const val SECOND_PASS_DELAY_MS = 6_000L

    /**
     * How long the retry button waits for the host to wake and re-mint before
     * reading its provider a second time.
     *
     * Shorter than SECOND_PASS_DELAY_MS because a human is watching this one:
     * the button says "Checking…" for the whole duration, and four seconds of
     * that is patience, ten is a hang.
     */
    private const val RECHECK_WAKE_MS = 4_000L

    /**
     * Minimum gap between navigation-triggered checks.
     *
     * WAS 45s, which was far too long and behaved like a bug. Opening a title and
     * backing out takes a few seconds, so returning to the home screen that way
     * was silently skipped, while slower trips through settings or watch history
     * cleared the floor and worked. It read as "entitlement updates from some
     * screens but not others", which is the worst kind of wrong: intermittent and
     * with no log line to explain it.
     *
     * The reasoning behind the long floor was also weaker than it looked. Reading
     * the provider does cold-start the host if Android reaped it, but that cost is
     * SELF-LIMITING — once the read starts the process it stays alive, and every
     * later read is a sub-millisecond call into memory. It is paid per reap, not
     * per check, so guarding it with a 45-second window bought very little.
     *
     * Three seconds now. Long enough to collapse a duplicate onResume, which
     * Android can deliver for a single user-visible return and which arrives
     * within milliseconds; short enough that no human will ever notice it.
     */
    private const val CHECK_FLOOR_MS = 3_000L

    /**
     * elapsedRealtime, NOT currentTimeMillis: monotonic, so an NTP correction or
     * a user changing the TV clock cannot make the floor expire early or, worse,
     * park it in the future and suppress every check until reboot.
     */
    @Volatile
    private var lastCheckAt = 0L

    /**
     * Process-level scope, NOT an Activity's.
     *
     * The second pass has to outlive AuthActivity: on a returning user that
     * activity decides in onCreate to skip straight through and finishes almost
     * immediately, which would cancel a lifecycleScope job long before the delay
     * elapsed — silently removing the entire fix.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Read the best assertion available and report it.
     *
     * @param incoming an assertion handed to us directly in an Intent extra, if a
     *        host launched us just now. Always preferred — it is the freshest
     *        thing that can exist, having been chosen at launch time.
     */
    fun submit(context: Context, incoming: String? = null) {
        val app = context.applicationContext
        val assertion = incoming?.takeIf { it.isNotBlank() }
            ?: readFromProvider(app)
            ?: stored(app)
            ?: return

        // Mirror it. If the host is later uninstalled or its cache is cleared we
        // can still re-report the last thing we saw, and the backend watches it
        // pass its own expiry rather than being told nothing forever.
        //
        // Safe to keep: it is signed, opaque, and meaningful only to the key
        // holder. There is nothing here to protect and nothing we could forge.
        if (assertion != stored(app)) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, assertion).apply()
        }

        scope.launch {
            runCatching {
                val resp = ApiClient.bluehiveApi.submitHostEntitlement(
                    HostEntitlementRequest(
                        assertion,
                        hostPresent = hostPresenceReport(app),
                        hostPaired  = readPairedFromProvider(app),
                    )
                )
                val standing = resp.body()?.standing?.trim()?.lowercase()
                if (resp.isSuccessful && standing != null) applyStanding(app, standing)
                else Log.w(TAG, "relay HTTP ${resp.code()} — standing unchanged")
            }.onFailure { Log.w(TAG, "relay failed: ${it.message}") }
        }
    }

    /**
     * Record the server's verdict and lock or unlock the app accordingly.
     *
     * FAILS OPEN, AND THAT IS THE WHOLE POINT OF THIS FUNCTION.
     *
     * ONLY the exact string "inactive" locks. Every other outcome — "active",
     * "unknown", "banned", a value a newer server invented, a null body, a
     * timeout, no host installed at all — leaves the user alone. That asymmetry
     * is not caution for its own sake:
     *
     *   - BlueHive can be installed WITHOUT the host, from a sideload. Those
     *     accounts are 'unknown' forever and have never been part of a
     *     subscription. Locking on anything but a definite negative would shut
     *     out the entire organic install base.
     *   - "I could not reach the server" and "the server says no" arrive here
     *     looking similar and mean opposite things. Only one of them is evidence.
     *
     * If you ever change this to lock on a broader set, you are choosing to lock
     * people out when the network hiccups. Do not.
     */
    private fun applyStanding(context: Context, standing: String) {
        val app = context.applicationContext
        val locked = standing == STANDING_INACTIVE
        val was = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STANDING, null)

        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STANDING, standing).apply()

        if (was != standing) Log.i(TAG, "host standing: ${was ?: "none"} -> $standing")

        // ANNOUNCE WHENEVER LOCKED, not only on the transition into it.
        //
        // Firing only on a change left a real hole. Android can restore a killed
        // task straight back to the home screen without going through
        // AuthActivity, so the launch gate never runs. The resume then re-reports,
        // the server says "inactive" again, and a transition-only check sees no
        // change and stays quiet — leaving the user inside an app that is supposed
        // to be locked, indefinitely.
        //
        // Repeating is safe: ServiceLockoutActivity.launch() no-ops while the
        // screen is already on top, so a user sitting on the lock screen is not
        // having it rebuilt underneath them. The idempotency lives there, which
        // is the right place for it — this function should not have to know
        // whether anyone is looking.
        if (locked) EntitlementLockBus.post()
    }

    /**
     * Application context, so code with no Context of its own can still reach
     * the relay. Set once from BlueHiveApplication.onCreate.
     *
     * Exists for the OkHttp interceptor: it sees the server's 403 from inside the
     * network stack, where there is no Activity and no Context to hand. Holding
     * the APPLICATION context specifically — never an Activity — so this cannot
     * leak a screen.
     */
    @Volatile
    private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /**
     * The server refused a request as not entitled. Treat that as authoritative.
     *
     * Called from the API layer on a 403 carrying code=entitlement_inactive. This
     * is the path that survives someone clearing app data: the local verdict is
     * gone, the app opens, and then the very first gated call comes back refused
     * and puts the lock straight back up.
     */
    fun markInactiveFromServer() {
        val ctx = appCtx
        if (ctx == null) {
            Log.w(TAG, "server reported inactive before init() — cannot lock yet")
            return
        }
        applyStanding(ctx, STANDING_INACTIVE)
    }

    /** Last verdict the server gave us, or null if it has never answered. */
    fun lastStanding(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STANDING, null)

    /**
     * Should the app refuse to open?
     *
     * Deliberately a positive test against one value rather than `!= active`:
     * a null (never reported), an unknown, or anything unrecognised must open
     * normally. See applyStanding for why.
     */
    fun isLockedOut(context: Context): Boolean =
        lastStanding(context) == STANDING_INACTIVE

    /** Forget the verdict. Used on sign-out so the next account starts clean. */
    fun clearStanding(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_STANDING).apply()
    }

    /**
     * Re-ask, and return the server's answer.
     *
     * Suspends, unlike everything else here, because the retry button needs to
     * wait for a verdict and show a result rather than fire and forget.
     *
     * Reads the host's provider FIRST. That read is also what starts the host's
     * process if Android reaped it, and on start the host resyncs and re-mints —
     * so pressing retry after resubscribing genuinely does go and ask the host,
     * rather than replaying whatever we last had on disk.
     */
    suspend fun recheckNow(context: Context): String? {
        val app = context.applicationContext

        var standing = postOnce(app, readFromProvider(app) ?: stored(app) ?: return null)

        // SECOND PASS, and the button barely works without it.
        //
        // The provider hands back the host's CACHED pass. When the host's process
        // is dead — which it usually is by the time someone is staring at this
        // screen — our read is what starts it, and only THEN does it resync and
        // mint a pass reflecting the new subscription. That happens a beat after
        // our first read was already answered from disk.
        //
        // So the obvious implementation fails on exactly the press that matters:
        // the user resubscribes, presses retry, and gets told no because we
        // replayed the stale pass we already had. Waiting once and re-reading
        // turns that into a working first press.
        //
        // Only re-posts when the token actually CHANGED, so a genuinely inactive
        // account costs one request, not two.
        if (standing == STANDING_INACTIVE) {
            delay(RECHECK_WAKE_MS)
            val fresh = readFromProvider(app)
            if (fresh != null && fresh != stored(app)) {
                Log.i(TAG, "host re-minted after wake — re-checking")
                standing = postOnce(app, fresh)
            }
        }
        return standing
    }

    /** One report. Persists the token, applies the verdict, returns it. */
    private suspend fun postOnce(context: Context, assertion: String): String? {
        val app = context.applicationContext
        if (assertion != stored(app)) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, assertion).apply()
        }
        return runCatching {
            val resp = ApiClient.bluehiveApi.submitHostEntitlement(
                HostEntitlementRequest(
                    assertion,
                    hostPresent = hostPresenceReport(app),
                    hostPaired  = readPairedFromProvider(app),
                )
            )
            val standing = resp.body()?.standing?.trim()?.lowercase()
            if (resp.isSuccessful && standing != null) {
                applyStanding(app, standing)
                standing
            } else null
        }.getOrNull()
    }

    /**
     * Report now, then again once the host has had time to come up.
     *
     * Call this on launch. The second pass is skipped when nothing changed in the
     * meantime, so a warm host costs exactly one POST.
     */
    fun submitWithCatchUp(context: Context, incoming: String? = null) {
        val app = context.applicationContext
        submit(app, incoming)

        scope.launch {
            delay(SECOND_PASS_DELAY_MS)
            // Stamp the floor from HERE, not from launch: the home screen resumes
            // moments after AuthActivity, and without this that resume would fire
            // a third read on top of these two for no possible gain.
            lastCheckAt = android.os.SystemClock.elapsedRealtime()
            val fresh = readFromProvider(app) ?: return@launch
            if (fresh == stored(app)) return@launch     // host had nothing newer
            Log.i(TAG, "host assertion changed after start — re-reporting")
            submit(app, fresh)
        }
    }

    /**
     * Re-check the host's standing. Call when the user lands on the home screen.
     *
     * THE HOLE THIS FILLS. Everything else here runs at launch, so sitting in the
     * app while the subscription changes left the recorded status wrong for the
     * whole session — until the user happened to quit and reopen.
     *
     * WHY ON NAVIGATION RATHER THAN A TIMER. This was once justified by "nothing
     * in BlueHive gates on this column, so a stale row costs the user nothing" —
     * which stopped being true on 2026-08-21, when the server began refusing
     * 'inactive' accounts. The conclusion outlives its premise, on better
     * reasoning: a timer polls hardest while the user is idle, which is exactly
     * when no gated request is being made and a fresher answer would change
     * nothing. Navigation is the opposite moment — someone about to hit gated
     * endpoints again — so the work is spent where it pays, hidden inside a
     * screen transition they already triggered.
     *
     * A STALE ROW IS CAUGHT FROM THE OTHER SIDE TOO. Stale-active is corrected by
     * the server's own 403 on the next gated call (see markInactiveFromServer);
     * stale-inactive is corrected by the lock screen's retry. This hook keeps the
     * row moving in the common case; it is not the only thing that can.
     *
     * ONE HOOK COVERS EVERY RETURN PATH. Sidebar pages, watch history, favorites,
     * settings, the media catalog, a movie or show detail page, the profile
     * picker — every one of them comes back to the home screen, so the home
     * screen resuming IS the event. It also catches the host's own lock screen
     * appearing over BlueHive and then dismissing.
     *
     * The read is a binder call into the host's memory, not a network request.
     * We only touch the network when the token has actually changed.
     *
     * IT ALSO SELF-HEALS A DEAD HOST PROCESS: if the host was reclaimed it got no
     * push and knows nothing, but reading its provider is what starts it, and on
     * start it resyncs and re-mints. The check that finds a stale token is often
     * the one that causes it to become fresh, and the next check reports it.
     */
    fun checkNow(context: Context) {
        val app = context.applicationContext
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastCheckAt < CHECK_FLOOR_MS) return
            lastCheckAt = now
        }
        // Off the main thread: a provider query blocks the caller, and if the
        // host's process is dead this is the call that starts it.
        scope.launch {
            // UNPAIR IS CHECKED FIRST, because the old order could never see it.
            //
            // This used to open with `readFromProvider(app) ?: return@launch`, on
            // the reasoning that no token means nothing to report. That is true
            // for a cold host and false for an unpaired one, and they produce the
            // identical empty read. So the single case this hook most needed to
            // catch was the one case it returned early on: a television unpaired
            // while BlueHive sat open would go on working through every screen
            // transition, because the check that was supposed to notice bailed
            // one line in.
            //
            // Asking the host outright separates them. Only an explicit false
            // gets here; a cold host, an older host with no such column, or a
            // failed read all yield null and fall through to the original
            // token-changed path untouched.
            if (readPairedFromProvider(app) == false) {
                Log.i(TAG, "host reports unpaired — reporting to lock this device")
                // stored(), because the provider has nothing left to give. The
                // mirrored pass is still a validly signed token, which is all the
                // server needs to identify the device; host_paired=false is what
                // it acts on, and it overrides whatever the token says inside.
                stored(app)?.let { submit(app, it) }
                return@launch
            }

            val fresh = readFromProvider(app) ?: return@launch
            if (fresh == stored(app)) return@launch
            Log.i(TAG, "host assertion changed — re-reporting")
            submit(app, fresh)
        }
    }

    /**
     * Is a host app installed on this device?
     *
     * ASKS ANDROID DIRECTLY rather than inferring it from an empty provider read.
     * Those are different questions and conflating them is what made this bug
     * possible: readFromProvider() returns null both when the host is missing AND
     * when it is present with nothing to say, so the relay could never tell "the
     * host is gone" from "the host has not caught up yet" and simply replayed its
     * saved pass in both cases.
     *
     * resolveContentProvider() answers the real question. It works because the
     * manifest declares the authority in <queries> — without that entry Android 11+
     * would hide the provider and this would report absent for everyone.
     *
     * Returns null if the lookup itself failed, which is not evidence of anything.
     */
    private fun hostPackageInstalled(context: Context): Boolean? = runCatching {
        context.packageManager.resolveContentProvider(PROVIDER_AUTHORITY, 0) != null
    }.getOrNull()

    /**
     * What we are willing to TELL THE SERVER about the host's presence.
     *
     *   true  — a host is installed
     *   false — no host on this machine, reported the moment it is observed
     *   null  — the lookup itself failed, so there is no evidence either way
     *
     * NULL IS THE DEFAULT AND THE SAFE ANSWER. It is omitted from the request
     * body, and the server treats a missing field as no information. Only one
     * case lands there now: the package lookup itself threw. That is the single
     * situation with no evidence either way, and it must never abandon anybody.
     *
     * The mark follows the hardware rather than the account, so it used to be
     * described as the one report here that must never be made on a guess. It
     * still is not a guess — resolveContentProvider either finds a host or does
     * not. What changed is that we no longer wait to see the same answer three
     * times before believing it.
     */
    private fun hostPresenceReport(context: Context): Boolean? {
        // ANSWERED ON THE SPOT. This used to require three separate readings
        // spanning ten minutes before it would say the host was gone, and the
        // readings only accrued on a cold start — so uninstalling the host meant
        // opening BlueHive three times over ten minutes before anything happened.
        // The rule is simply: no host, no access, decided the moment we look.
        //
        // The debounce existed because Android reports a package as missing while
        // it is being updated, so a badly timed launch could mark a healthy
        // machine. That risk is now carried somewhere better: the mark is
        // reversible, and pairing forces a fresh mint, so a host that comes back
        // clears it. Trading a ten-minute hole for a narrow update window is the
        // right way round, because the hole was permanent and the window is
        // seconds long and self-healing.
        //
        // NULL STILL MEANS "COULD NOT TELL", and only the lookup itself failing
        // produces it. That is the one case with no evidence either way, and it
        // must not abandon anybody.
        val installed = hostPackageInstalled(context) ?: return null
        if (!installed) Log.i(TAG, "host package not installed — reporting machine abandoned")
        return installed
    }

    private fun stored(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)?.takeIf { it.isNotBlank() }

    private fun readFromProvider(context: Context): String? = runCatching {
        context.contentResolver.query(Uri.parse(PROVIDER_URI), null, null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(COLUMN)) else null
            }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Does the host still hold an account, or has it been unpaired?
     *
     * THE QUESTION [readFromProvider] CANNOT ANSWER. An empty assertion means
     * either "I have been unpaired" or "my process just started and has not
     * loaded anything yet", and those want opposite responses. Guessing wrong
     * towards the first abandons a working television on every cold boot;
     * guessing wrong towards the second is what let an unpaired one keep working
     * off our mirrored pass until it expired. The host now states it outright.
     *
     * NULL IS THE DEFAULT AND THE SAFE ANSWER, returned for every uncertainty:
     * no host installed, a host too old to publish the column, a provider that
     * throws, a host that could not read its own store. Only a host that is
     * present and says plainly that it holds no session yields false, and only
     * false has consequences.
     */
    private fun readPairedFromProvider(context: Context): Boolean? = runCatching {
        context.contentResolver.query(Uri.parse(PROVIDER_URI), null, null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) return@use null
                // getColumnIndex, not getColumnIndexOrThrow: an older host has no
                // such column, and that is a supported configuration rather than
                // an error. -1 falls through to null.
                val i = c.getColumnIndex(COLUMN_PAIRED)
                if (i < 0 || c.isNull(i)) null else c.getInt(i) == 1
            }
    }.getOrNull()

    /**
     * The best pass available right now, for the ONE caller that needs the token
     * itself rather than the verdict: sign-in-by-host (AuthActivity).
     *
     * Same three sources and same order as [submit] — an extra handed over at
     * launch, then the host's provider, then our own mirrored copy. Deliberately
     * shares that ordering rather than re-deriving it, so the two can never drift
     * into disagreeing about which pass is current.
     *
     * A stale mirror is a fine thing to offer here. The server refuses an expired
     * pass for sign-in, so the worst case is a 401 and the email screen, which is
     * exactly where the user would have started anyway.
     */
    fun bestAssertion(context: Context, incoming: String? = null): String? {
        val app = context.applicationContext
        return incoming?.takeIf { it.isNotBlank() } ?: readFromProvider(app) ?: stored(app)
    }

    /** Pull the extra a host passes when it launches us directly. */
    fun extraFrom(intent: Intent?): String? =
        intent?.getStringExtra(AuthActivity.EXTRA_HOST_ASSERTION)?.takeIf { it.isNotBlank() }
}
