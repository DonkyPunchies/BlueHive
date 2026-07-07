package com.example.bluehive.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.example.bluehive.BuildConfig
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

    // Short timeouts: the manifest check runs BEFORE the app opens, so a down
    // server must not hang the launch. ~4s worst case, then we proceed normally.
    private val manifestHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    // Long timeouts: the APK is large (~200MB).
    private val downloadHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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
     * Downloads the APK with progress, then verifies sha256. Returns the verified
     * file, or null on any failure (network drop, hash mismatch). Progress is a
     * fraction 0f..1f, or -1f when the server didn't send a Content-Length.
     */
    suspend fun downloadAndVerify(
        context: Context,
        manifest: Manifest,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
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
                    return@withContext null
                }
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
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
                return@withContext null
            }
            Log.i(TAG, "sha256 verified.")
            out
        } catch (e: Exception) {
            Log.w(TAG, "Download/verify error: ${e.message}")
            out.delete()
            null
        }
    }

    /**
     * Hands the verified APK to PackageInstaller. The system shows one confirm
     * dialog (SelfUpdateInstallReceiver launches it). On success the process is
     * killed + replaced; PackageReplacedReceiver relaunches BlueHive.
     */
    fun install(context: Context, apk: File) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply { setSize(apk.length()) }

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
            UpdateInstallBus.post(PackageInstaller.STATUS_FAILURE)
        }
    }

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
            Log.w(TAG, "Manifest fetch error: ${e.message}")
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