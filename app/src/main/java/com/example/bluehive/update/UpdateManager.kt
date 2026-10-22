package com.example.bluehive.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.example.bluehive.BuildConfig
import com.example.bluehive.net.pinToLan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * BlueHive self-update (#5).
 *
 * Identity-independent by design: NO Bearer token, NO X-API-Key, NO version
 * header. Talks only to the public update server (BuildConfig.UPDATE_BASE_URL).
 * If a bug ever breaks token handling, the update path must still deliver the fix.
 *
 * This object no longer triggers itself. HostEntryActivity calls checkForUpdate()
 * at cold start; if an update exists it routes to SelfUpdateActivity, which drives
 * downloadAndVerify() + install() with an on-screen progress UI.
 */
object UpdateManager {

    private const val TAG = "SelfUpdate"

    /** Public so HostEntryActivity/SelfUpdateActivity can pass it around. */
    data class Manifest(
        val latestVersionCode: Int,
        val latestVersionName: String,
        val apkUrl: String,
        val sha256: String,
        val minSupported: Int
    )

    // THE MANIFEST CHECK BLOCKS THE LAUNCH, so this budget is a trade between two
    // failures, and 4s was tuned for the wrong one.
    //
    // It was written when the update server was 192.168.1.136:8090 -- one hop on
    // the LAN, where 4s is enormous. The server is a remote HTTPS host now, and
    // the same 4s has to cover DNS, a TLS handshake and the response, on a TV that
    // is cold-starting the app at the same time. MEASURED: three cold launches
    // with an update genuinely available found it twice and missed it once.
    //
    // A MISS IS SILENT AND COSTLY. The timeout throws, the exception is swallowed,
    // checkForUpdate() returns null, and the app carries on as if it were current
    // -- while the server logs a perfectly good 200 for the request the client had
    // already abandoned. That contradiction (server says 200, client says no
    // update) is what made this hard to see.
    //
    // callTimeout bounds the WHOLE call, which connect/read alone do not: they can
    // each be under budget while DNS + handshake + read together run far past it.
    // So the launch-path worst case is now one number, 8s, instead of an open-ended
    // sum -- and 8s only elapses against a black-holed network, since a refused or
    // unroutable connection still fails immediately.
    private val manifestHttp: OkHttpClient = OkHttpClient.Builder()
        // 4s, UNCHANGED FROM THE ORIGINAL, and deliberately so after measuring.
        //
        // This call still blocks the launch, and AuthActivity draws nothing while
        // it runs, so the budget IS the blank-window duration. Raising it to 8s was
        // tried and made things worse on both counts: still timed out (4 of 6 cold
        // launches) and doubled the stare. The failures are a slow path from this
        // device to the remote host, not a budget that is fractionally too small,
        // so no value here fixes them.
        //
        // The real fix is to take the check off the launch path entirely. That was
        // attempted and reverted -- it regressed detection to 0 of 6 and needs more
        // care than a same-day change. Left as the known-good original until then.
        // RAISED FROM 4s, because the premise of 4s no longer holds.
        //
        // Everything above this line was written when the manifest check ran on
        // the launch path and AuthActivity drew nothing while it waited, so the
        // timeout WAS the blank-window duration and had to stay small. That call
        // no longer exists: the check now runs from UpdateGate.prefetch() behind
        // the loading screen, which is already on screen and already animating.
        // Nothing stares at anything while this runs.
        //
        // With the cost of waiting gone, the small budget was pure downside — it
        // could not fit a cold DNS lookup plus a TLS handshake to a remote host,
        // so the check simply missed, silently, and the app carried on as if it
        // were current. The measurements recorded above (found twice, missed
        // once) were that failure, and it survived the move to the loading screen
        // because the budget came with it.
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        // ServerConfig :8090 is on the LAN — must not enter a VPN tunnel.
        .pinToLan(ServerConfig.updateBaseUrl)
        .build()

    // Long timeouts: the APK is large (~200MB).
    private val downloadHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Same LAN host as the manifest — the APK is served off :8090, not GitHub.
        .pinToLan(ServerConfig.updateBaseUrl)
        .build()

    /**
     * Returns a Manifest IFF an update should be offered (newer + verifiable).
     * Returns null on: already-current, unreachable server, or missing sha256.
     * Never throws — a null result always means "just launch the app normally".
     */
    suspend fun checkForUpdate(): Manifest? = withContext(Dispatchers.IO) {
        val m = fetchManifest() ?: return@withContext null
        val installed = BuildConfig.VERSION_CODE
        Log.i(TAG, "Installed=$installed, latest=${m.latestVersionCode} (${m.latestVersionName})")

        if (m.latestVersionCode <= installed) {
            Log.i(TAG, "Already up to date.")
            return@withContext null
        }
        if (m.sha256.isBlank()) {
            Log.w(TAG, "Manifest has no sha256 — refusing to offer unverifiable APK")
            return@withContext null
        }
        m
    }

    /**
     * Is this update one the user may NOT decline?
     *
     * min_supported is the manifest's floor: the oldest build the operator is
     * still willing to have talking to the backend. Below it the app must not be
     * usable, so the update screen refuses to hand control back.
     *
     * THE FIELD EXISTED AND DID NOTHING until now — parsed into Manifest, passed
     * as a literal 0 by SelfUpdateActivity, never compared anywhere. It read like
     * a working force-update policy while being inert, which is worse than not
     * having it: an operator could set it, watch nothing happen, and have no
     * reason to suspect the field rather than their own rollout.
     *
     * Deliberately a separate function rather than a flag on the manifest fetch:
     * "is there an update" and "may it be refused" are different questions, and
     * the callers that only care about the first should not have to think about
     * the second.
     */
    fun isMandatory(m: Manifest): Boolean = BuildConfig.VERSION_CODE < m.minSupported

    /**
     * Delete update APKs left in the cache by an earlier process.
     *
     * WHY THIS IS NEEDED AT ALL. The success path cannot clean up after itself:
     * PackageInstaller replaces the app and the OS kills this process the moment
     * it lands, so nothing after commit() ever runs. The ~170MB download was
     * therefore left sitting in cacheDir until the NEXT update happened to sweep
     * it — which on a device that updates monthly is a month of dead weight on a
     * 4.7GB partition. That is the same partition an install needs headroom on,
     * and running out of it is how an update fails with STATUS_FAILURE_STORAGE.
     *
     * DELETES EVERY MATCH, not just versions at or below the installed one.
     * Keeping a newer one would only be worth doing if a later run could reuse
     * it, and none can: the retry path in SelfUpdateActivity reads the in-memory
     * `downloadedApk` reference, which dies with the process, and never looks in
     * cacheDir by name. So at process start every one of these files is already
     * unreachable, whatever version it claims.
     *
     * SAFE TO CALL FROM Application.onCreate FOR THE SAME REASON. A download can
     * only begin after an Activity exists, so at process start there is never one
     * in flight to race — and [downloadAndVerify] would delete these anyway.
     *
     * Failures are ignored. Reclaiming cache is opportunistic, and a file that
     * will not delete is one the platform can still evict on its own.
     */
    suspend fun pruneStaleDownloads(context: Context) = withContext(Dispatchers.IO) {
        val stale = context.cacheDir.listFiles { f ->
            f.name.startsWith("update-") && f.name.endsWith(".apk")
        } ?: return@withContext

        var freed = 0L
        var count = 0
        stale.forEach { f ->
            val size = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                freed += size
                count++
            }
        }
        // Log.e: R8 strips v/d/i/w from release builds, and "did the box get its
        // 170MB back" is exactly the question asked when one runs out of space.
        if (count > 0) Log.e(TAG, "pruned $count stale update APK(s), freed ${freed / 1048576}MB")
    }

    /**
     * How a download ended. Three cases, because the caller must treat them
     * differently.
     *
     * [Reported] is the one worth explaining. A storage shortfall is announced on
     * [UpdateInstallBus], where the install path's existing handler already says
     * the right thing for a mandatory update (stay put, explain, offer a manual
     * retry) and for an optional one (into the app on the current version). A
     * caller that saw only "no file" and navigated on its own would race that
     * handler and walk the user off the screen that had just explained the
     * problem — so the outcome has to carry "already reported" as a distinct
     * state rather than folding it into a null.
     */
    sealed interface DownloadOutcome {
        data class Ok(val apk: File) : DownloadOutcome
        /** Already posted to [UpdateInstallBus]. The caller must NOT navigate. */
        data object Reported : DownloadOutcome
        /**
         * Network drop, bad HTTP status, hash mismatch. Nothing was posted, so
         * the caller owns the presentation.
         *
         * CARRIES THE REASON rather than just "no file". A download that fails
         * silently and drops the user into the app looks identical to one that
         * never ran, which is unsupportable for exactly the same argument
         * [UpdateInstallBus.Result] makes about install failures: a connection
         * that dropped, a server that 404'd and a corrupted transfer each need a
         * different response from whoever is standing in front of the TV.
         */
        data class Failed(val reason: String) : DownloadOutcome
    }

    /**
     * Downloads the APK with progress, then verifies sha256. Progress is a
     * fraction 0f..1f, or -1f when the server didn't send a Content-Length.
     */
    suspend fun downloadAndVerify(
        context: Context,
        manifest: Manifest,
        onProgress: (Float) -> Unit
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        // Clean stale downloads so cacheDir doesn't accumulate 200MB APKs.
        context.cacheDir.listFiles { f -> f.name.startsWith("update-") && f.name.endsWith(".apk") }
            ?.forEach { it.delete() }

        val out = File(context.cacheDir, "update-${manifest.latestVersionCode}.apk")
        // Rebuild the download URL from our configured base — the manifest's apk_url
        // is absolute with the server's baked-in host, wrong once the server moves.
        val apkUrl = ServerConfig.updateBaseUrl + "/apk/" + manifest.apkUrl.substringAfterLast('/')
        try {
            val req = Request.Builder().url(apkUrl).get().build()
            downloadHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "APK HTTP ${resp.code} from $apkUrl")
                    return@withContext DownloadOutcome.Failed(
                        "The update server refused the download.  (HTTP ${resp.code})"
                    )
                }
                val body = resp.body ?: return@withContext DownloadOutcome.Failed(
                    "The update server sent an empty response."
                )
                val total = body.contentLength()

                // ── ROOM FOR THE DOWNLOAD, CHECKED BEFORE A BYTE IS WRITTEN ──
                //
                // install() already asks this question, but it asks it AFTER the
                // whole ~170MB is on disk. A box too full to hold the file spent
                // the entire transfer to find that out, then failed in the
                // generic catch below — so the one message that would have told
                // the user what to do ("free some space") was the one message
                // they did not get.
                //
                // THIS IS THE EARLIEST THE SIZE IS KNOWN. The manifest carries no
                // length, so Content-Length is the first honest number available,
                // and it arrives with the response head — before the body has
                // cost anything.
                //
                // PERMISSIVE, for the same reasons install() is. getAllocatableBytes
                // counts cache the platform will evict as well as raw free space,
                // an unmeasurable -1 falls through rather than refusing, and a
                // server that sent no Content-Length falls through too. This stops
                // only a download that certainly cannot land.
                if (total > 0) {
                    val needed = total + SESSION_SLACK_BYTES
                    val available = allocatableBytesFor(context, needed)
                    Log.e(TAG, "download: apk=$total needed=$needed available=$available")
                    if (available in 0 until needed) {
                        UpdateInstallBus.post(
                            PackageInstaller.STATUS_FAILURE_STORAGE,
                            "needs ${needed / 1048576}MB to download, " +
                                "can free ${available / 1048576}MB"
                        )
                        return@withContext DownloadOutcome.Reported
                    }
                }
                body.byteStream().use { input ->
                    out.outputStream().use { sink ->
                        val buf = ByteArray(64 * 1024)
                        var readTotal = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            readTotal += n
                            onProgress(if (total > 0) (readTotal.toFloat() / total).coerceIn(0f, 1f) else -1f)
                        }
                    }
                }
            }
            onProgress(1f)
            Log.i(TAG, "Downloaded ${out.length()} bytes — verifying sha256")

            val actual = sha256Of(out).lowercase()
            if (actual != manifest.sha256.lowercase()) {
                Log.e(TAG, "sha256 MISMATCH — expected ${manifest.sha256}, got $actual. Deleting.")
                out.delete()
                return@withContext DownloadOutcome.Failed(
                    "The downloaded update was damaged and has been discarded."
                )
            }
            // States WHAT the hash proved, because that differs by channel and
            // the difference is easy to forget: over TLS the manifest came from
            // us, so a match means the APK is ours. Over plaintext an attacker
            // in the path serves both the APK and the hash, so a match only
            // proves the download was not corrupted in transit — it says nothing
            // about who sent it. Android's own installer still refuses any APK
            // not signed with the release key, which is the backstop; this line
            // exists so a plaintext channel is visible in the logs rather than
            // reading as a clean "verified".
            Log.i(TAG, if (ServerConfig.isSecureChannel)
                "sha256 verified (https — authenticated channel)."
            else
                "sha256 verified (PLAINTEXT channel — integrity only, NOT authenticity).")
            DownloadOutcome.Ok(out)
        } catch (e: Exception) {
            Log.w(TAG, "Download/verify error: ${e.message}")
            out.delete()
            // A DISK THAT FILLED DURING THE WRITE, which the pre-check above
            // cannot prevent: it measures once, and the platform is free to hand
            // that space to something else before the last byte lands. ENOSPC is
            // the kernel saying exactly which failure this is, and it earns the
            // storage message rather than the generic one — the user's action is
            // the same either way, so the wording is the whole value.
            if (isOutOfSpace(e)) {
                UpdateInstallBus.post(
                    PackageInstaller.STATUS_FAILURE_STORAGE,
                    "ran out of space while downloading"
                )
                DownloadOutcome.Reported
            } else {
                DownloadOutcome.Failed(
                    "The download did not finish.  " +
                        "Check this device's connection, then try again." +
                        (e.message?.let { "\n($it)" } ?: "")
                )
            }
        }
    }

    /**
     * Does this failure chain say the filesystem is full?
     *
     * Walks the causes because the write is wrapped: OkHttp and the stream
     * plumbing both re-throw, so the IOException carrying ENOSPC is rarely the
     * one that surfaces. Matching on the message is unavoidable — Java has no
     * distinct out-of-space exception type — so both the errno spelling and the
     * plain-English one are checked.
     */
    private fun isOutOfSpace(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { t ->
            val m = t.message ?: return@any false
            m.contains("ENOSPC", ignoreCase = true) ||
                m.contains("No space left", ignoreCase = true)
        }

    /**
     * Hands the verified APK to PackageInstaller. The system shows one confirm
     * dialog (SelfUpdateInstallReceiver launches it). On success the process is
     * killed + replaced; PackageReplacedReceiver relaunches BlueHive.
     */
    fun install(context: Context, apk: File) {
        try {
            // ROOM TO WORK -- ASKED FOR, NOT JUST MEASURED.
            //
            // The session takes its own copy of the APK, so the install needs the
            // file's size again on top of what the cached download already holds.
            // On a 4.7GB TV box near capacity that is not a formality: a shortfall
            // used to surface as a bare STATUS_FAILURE with no message at all.
            //
            // allocatableBytesFor() asks the platform how much it could provide
            // INCLUDING space it can reclaim from other apps' caches, and then
            // tells it to do the reclaiming. That is the number an installer
            // really gets, and it is much larger than free space on a TV where
            // the launcher alone sits on a few hundred megabytes of evictable
            // cache. Measuring raw free space instead reports a shortage that
            // does not exist.
            //
            // The bar is deliberately low -- just the session copy plus a little.
            // PackageInstaller is the real judge of whether the install fits, and
            // its STATUS_FAILURE_STORAGE is now classified and explained properly,
            // so erring permissive costs a clear error message. Erring strict
            // costs a device that refuses a perfectly installable update, and
            // with no way to decline an update that is a brick.
            val needed = apk.length() + SESSION_SLACK_BYTES
            val available = allocatableBytesFor(context, needed)
            Log.e(TAG, "install: apk=${apk.length()} needed=$needed available=$available")
            if (available in 0 until needed) {
                UpdateInstallBus.post(
                    PackageInstaller.STATUS_FAILURE_STORAGE,
                    "needs ${needed / 1048576}MB, can free ${available / 1048576}MB"
                )
                return
            }
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setSize(apk.length())

                // ── Skip Android's "Do you want to update this app?" dialog ────
                //
                // ASKING TWICE IS THE PROBLEM. The user has already been told the
                // update is required and pressed Accept on our own screen; the
                // system dialog then asks the same question in different words,
                // and on a TV it is easy to miss or dismiss by accident. A missed
                // dialog is the single commonest install failure here — it is why
                // the auto-retry in SelfUpdateActivity exists at all.
                //
                // API 31 added a legitimate way out, and BlueHive qualifies for
                // it WITHOUT device-owner provisioning or root, because the rule
                // is satisfied by an installer "updating itself" — which is
                // exactly what a self-update is. The full set of conditions:
                //
                //   * the session opts in, here
                //   * the app declares UPDATE_PACKAGES_WITHOUT_USER_ACTION
                //     (a normal permission, granted at install, no prompt)
                //   * the installer is updating itself, or updating an app it
                //     first installed
                //   * the app being installed targets a high enough API for the
                //     RUNNING platform: 29+ on Android 12, 30+ on 13, 31+ on 14,
                //     33+ on 15. This app targets 35, so it clears all of them.
                //
                // FAILS SOFT BY DESIGN, which is why there is no fallback branch.
                // If any condition is not met the platform ignores the request and
                // commits the session the old way — STATUS_PENDING_USER_ACTION,
                // SelfUpdateInstallReceiver raises the dialog, exactly as today.
                // The worst case is the behaviour we already have.
                if (Build.VERSION.SDK_INT >= 31) {
                    setRequireUserAction(
                        PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    )
                }

                // ── Claim ownership of BlueHive's own updates (API 34+) ────────
                //
                // THE FLAG ABOVE IS NOT ENOUGH ON ANDROID 14, AND THIS IS WHY.
                // MEASURED on the onn box, same build, same permission, same
                // target SDK, two consecutive self-updates:
                //
                //   230 -> 231   silent      installer of record was null
                //   231 -> 232   PROMPTED    installer of record was BlueHive
                //
                // Every documented precondition for USER_ACTION_NOT_REQUIRED held
                // in BOTH cases -- permission granted, call present in the shipped
                // dex, target 35, identical permission sets between the two APKs.
                // The only thing that changed is that the app now HAS a recorded
                // installer, and Android 14's update-ownership enforcement starts
                // caring at exactly that point: it wants an update OWNER, which is
                // a separate thing from an installer of record and is never set
                // implicitly. Nothing had ever claimed it, so there was nothing to
                // match and the platform fell back to asking the user.
                //
                // Ownership is claimed BY the install, so the first update after
                // this ships may still prompt; the one after it is the test that
                // matters.
                //
                // COSTS: once BlueHive owns its updates, any OTHER installer
                // touching this package makes the user confirm. That is the point
                // -- a sideloaded TV app should not be silently replaceable by
                // something else -- and adb keeps working because shell holds
                // INSTALL_PACKAGES and bypasses ownership entirely.
                if (Build.VERSION.SDK_INT >= 34) {
                    setRequestUpdateOwnership(true)
                }
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, SelfUpdateInstallReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            Log.i(TAG, "Install session committed — awaiting user confirm dialog.")
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller failed: ${e.message}", e)
            // The exception text travels with the status. Copying the APK into
            // the session is the step that throws when the disk fills, and that
            // message is the only thing that says so.
            UpdateInstallBus.post(PackageInstaller.STATUS_FAILURE, e.message)
        }
    }

    /**
     * How many bytes the system could give us, and a request that it try.
     *
     * getAllocatableBytes counts free space PLUS cache the platform is willing
     * to evict from other apps, which is what an install actually has available
     * -- raw free space understates it badly on a TV where the launcher holds
     * hundreds of megabytes of reclaimable cache. allocateBytes then performs
     * that eviction, so the space is really there when the session asks for it.
     *
     * Returns -1 when the figure cannot be obtained, which callers treat as
     * "proceed and let PackageInstaller decide" rather than as a shortage.
     *
     * ANDROID 7.1 ALWAYS GETS -1, because all three calls are Android 8 (API 26)
     * and minSdk is 25. Called there, the missing method throws
     * NoSuchMethodError -- an Error, which the catch below never sees -- and it
     * took the updater down as the download began. Lint's NewApi check found it
     * on 2026-10-21, beside the API 29 call that crashed Accept on Android 8-9.
     */
    private fun allocatableBytesFor(context: Context, wanted: Long): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return -1L
        return try {
            val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
            val uuid = sm.getUuidForPath(context.cacheDir)
            val allocatable = sm.getAllocatableBytes(uuid)
            if (allocatable >= wanted) {
                // Ask the platform to actually free it. Without this the cache is
                // merely evictable in principle and the session can still hit ENOSPC.
                runCatching { sm.allocateBytes(uuid, wanted) }
                    .onFailure { Log.e(TAG, "allocateBytes declined: ${it.message}") }
            }
            allocatable
        } catch (e: Exception) {
            Log.e(TAG, "could not measure allocatable bytes: ${e.message}")
            -1L
        }
    }

    /**
     * Slack beyond the APK's own size before an install is even attempted.
     *
     * Covers the session's staging copy overhead only. It is NOT an estimate of
     * the whole install -- see the comment at the call site for why this is
     * deliberately permissive.
     */
    private const val SESSION_SLACK_BYTES = 64L * 1024 * 1024

    private fun fetchManifest(): Manifest? {
        val url = ServerConfig.updateBaseUrl + "/bluehive/version"
        return try {
            val req = Request.Builder().url(url).get().build()
            manifestHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Manifest HTTP ${resp.code} from $url")
                    return null
                }
                // Strip a leading UTF-8 BOM if the server ever emits one — org.json
                // does not skip it and would otherwise throw on an otherwise-valid manifest.
                val body = (resp.body?.string() ?: return null).removePrefix("﻿")
                val j = JSONObject(body)
                Manifest(
                    latestVersionCode = j.getInt("latest_version_code"),
                    latestVersionName = j.optString("latest_version_name", "?"),
                    apkUrl = j.getString("apk_url"),
                    sha256 = j.optString("sha256", ""),
                    minSupported = j.optInt("min_supported", 0)
                )
            }
        } catch (e: Exception) {
            // Log.e, NOT Log.w. Release builds strip v/d/i/w via
            // -assumenosideeffects, so every previous failure here was invisible on
            // exactly the builds users run -- and this is the failure that silently
            // stops a device ever updating. It has to survive.
            Log.e(TAG, "Manifest fetch failed (device will not update this launch): ${e.message}")
            null
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}