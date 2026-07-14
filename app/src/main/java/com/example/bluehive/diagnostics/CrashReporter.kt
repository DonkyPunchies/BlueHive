// diagnostics/CrashReporter.kt
package com.example.bluehive.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.bluehive.BuildConfig
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.DeviceReportBody
import com.example.bluehive.auth.SessionManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.system.exitProcess

/**
 * BlueHive's crash-reporting pipeline. Self-hosted — reports go to YOUR
 * platform backend (POST /api/android/reports), never to a third party.
 *
 * Flow:
 *   1. install() wraps the default uncaught-exception handler. On any crash:
 *      capture a full report (exception + this process's own logcat + runtime
 *      snapshot) → write it to filesDir/crash_reports/ → hand off to the
 *      previous handler so Android's normal crash teardown still happens.
 *      Writing to DISK first (not the network) is what makes this reliable:
 *      a dying process can't be trusted to complete an HTTP call, but a
 *      2 ms file write practically always lands.
 *   2. flushPendingAsync() runs on the NEXT launch: uploads every pending
 *      file through the authenticated platformApi (Bearer + X-Refresh-Token
 *      attach automatically; the server links the report to this device and
 *      its owning user). Files are deleted only on a 2xx — offline launches
 *      simply retry next time. Reports older than 14 days are dropped as
 *      stale (the retention window on the server is the real archive).
 *
 * The Diagnostics screen reuses buildReport() with type='diagnostic' for its
 * Send Logs button — same capture, same pipeline, no exception fields.
 *
 * Log capture: `logcat -d --pid=<us>` reads the system's OWN buffer for this
 * process — every existing Log.d/i/w/e in the app is included with zero code
 * changes anywhere else. JWT-shaped strings are scrubbed before anything is
 * persisted or uploaded.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"

    private const val DIR_NAME     = "crash_reports"
    private const val MAX_PENDING  = 5      // newest N crash files kept on disk
    private const val MAX_AGE_DAYS = 14L    // older pending files are dropped, not uploaded
    private const val LOGCAT_LINES = 400    // tail size captured into each report
    private const val FLUSH_DELAY_MS = 8_000L  // let cold-start + token settle before uploading

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    @Volatile private var installed = false

    // ── 1. Crash capture ──────────────────────────────────────────────────────

    /** Call ONCE from Application.onCreate (main process only), as early as possible. */
    fun install(app: Application) {
        if (installed) return
        installed = true

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                persistCrash(app, thread, throwable)
            } catch (t: Throwable) {
                // The reporter must NEVER make a crash worse.
                Log.e(TAG, "Failed to persist crash report: ${t.message}")
            }
            // Chain to Android's default handler — the process still dies the
            // normal way (crash dialog semantics, ANR bookkeeping, etc.).
            previous?.uncaughtException(thread, throwable) ?: exitProcess(2)
        }
        Log.i(TAG, "Crash handler installed (v${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE})")
    }

    private fun persistCrash(context: Context, thread: Thread, t: Throwable) {
        val body = buildReport(
            context = context,
            reportType = "crash",
            exception = t,
            thread = thread,
        )

        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        // Keep the newest MAX_PENDING-1 so this write makes MAX_PENDING total.
        // A crash-looping app records its first few crashes (the informative
        // ones) without filling the disk.
        dir.listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_PENDING - 1)
            ?.forEach { it.delete() }

        val file = File(dir, "crash-${System.currentTimeMillis()}.json")
        file.writeText(gson.toJson(body))
        Log.e(TAG, "💥 Crash captured (${t.javaClass.simpleName}: ${t.message}) — " +
                "report persisted as ${file.name}, uploads next launch")
    }

    // ── 2. Upload on next launch ──────────────────────────────────────────────

    /** Fire-and-forget: call from Application.onCreate after SessionManager.init. */
    fun flushPendingAsync(context: Context) {
        ioScope.launch {
            delay(FLUSH_DELAY_MS)
            try {
                flushPending(context)
            } catch (t: Throwable) {
                Log.w(TAG, "flushPending failed: ${t.message}")
            }
        }
    }

    suspend fun flushPending(context: Context) = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, DIR_NAME)
        val files = dir.listFiles { f -> f.name.endsWith(".json") }
            ?.sortedBy { it.name }  // oldest first — chronological arrival server-side
            ?: return@withContext
        if (files.isEmpty()) return@withContext

        if (!SessionManager.get().isAuthenticated) {
            Log.i(TAG, "⏭ ${files.size} pending report(s) but not authenticated — retrying next launch")
            return@withContext
        }

        val staleCutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24 * 60 * 60 * 1000
        for (file in files) {
            if (file.lastModified() < staleCutoff) {
                Log.i(TAG, "🗑 Dropping stale report ${file.name} (> $MAX_AGE_DAYS days old)")
                file.delete()
                continue
            }
            try {
                val body = gson.fromJson(file.readText(), DeviceReportBody::class.java)
                val resp = ApiClient.bluehiveApi.submitDeviceReport(body)
                if (resp.isSuccessful) {
                    Log.i(TAG, "📤 Report uploaded: ${file.name} → server id=${resp.body()?.report_id}")
                    file.delete()
                } else {
                    Log.w(TAG, "Server rejected ${file.name} (HTTP ${resp.code()}) — keeping for retry")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Upload of ${file.name} failed (${e.message}) — keeping for retry")
            }
        }
    }

    // ── 3. Diagnostics (Send Logs button) ─────────────────────────────────────

    /**
     * Captures and uploads a 'diagnostic' report RIGHT NOW (no exception —
     * just logs + runtime snapshot). Returns the server-assigned report id.
     */
    suspend fun sendDiagnostic(context: Context, extraMeta: Map<String, Any?> = emptyMap()): Result<Long> =
        withContext(Dispatchers.IO) {
            try {
                val body = buildReport(context, reportType = "diagnostic", extraMeta = extraMeta)
                val resp = ApiClient.bluehiveApi.submitDeviceReport(body)
                val ack = resp.body()
                if (resp.isSuccessful && ack != null) Result.success(ack.report_id)
                else Result.failure(IllegalStateException("HTTP ${resp.code()}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── 4. Content-process death (Gecko onCrash / onKill) ─────────────────────

    /**
     * Reports a Gecko content-process death RIGHT NOW. When onCrash/onKill fires,
     * the MAIN app process is still alive — so, unlike a fatal Kotlin crash, we can
     * upload immediately instead of persisting for next launch. report_type splits
     * the two cases so they group separately in the dashboard:
     *   killed = true  → 'oom'           (system reaped the process; usually the LMK)
     *   killed = false → 'webview_crash' (the content process itself crashed)
     * The captured logcat tail + memory snapshot travel with it, so you can see what
     * the device looked like at the moment it happened — the visibility that a plain
     * OOM otherwise denies you (no exception is ever thrown).
     */
    fun reportContentProcessGone(
        context: Context,
        killed: Boolean,
        extraMeta: Map<String, Any?> = emptyMap(),
    ) {
        val reportType = if (killed) "oom" else "webview_crash"
        ioScope.launch {
            try {
                val body = buildReport(context, reportType = reportType, extraMeta = extraMeta).copy(
                    exception_class = if (killed) "GeckoContentProcessKilled" else "GeckoContentProcessCrashed",
                    exception_message = if (killed)
                        "The webview content process was killed by the system (low memory)."
                    else
                        "The webview content process crashed.",
                )
                val resp = ApiClient.bluehiveApi.submitDeviceReport(body)
                if (resp.isSuccessful) {
                    Log.i(TAG, "📤 Content-process '$reportType' reported (id=${resp.body()?.report_id})")
                } else {
                    Log.w(TAG, "Content-process report rejected: HTTP ${resp.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Content-process report failed: ${e.message}")
            }
        }
    }

    // ── Report assembly ───────────────────────────────────────────────────────

    private fun buildReport(
        context: Context,
        reportType: String,
        exception: Throwable? = null,
        thread: Thread? = null,
        extraMeta: Map<String, Any?> = emptyMap(),
    ): DeviceReportBody {
        val runtime = Runtime.getRuntime()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }

        val meta = buildMap<String, Any?> {
            put("uptime_ms",        SystemClock.uptimeMillis())
            put("heap_used_mb",     (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
            put("heap_max_mb",      runtime.maxMemory() / (1024 * 1024))
            put("device_mem_avail_mb", memInfo.availMem / (1024 * 1024))
            put("device_mem_total_mb", memInfo.totalMem / (1024 * 1024))
            put("device_low_memory",   memInfo.lowMemory)
            put("low_ram_device",   am?.isLowRamDevice ?: false)
            put("android_release",  Build.VERSION.RELEASE)
            put("manufacturer",     Build.MANUFACTURER)
            put("locale",           Locale.getDefault().toLanguageTag())
            put("timezone",         TimeZone.getDefault().id)
            put("last_profile_id",  runCatching { SessionManager.get().lastProfileId }.getOrDefault(-1))
            putAll(extraMeta)
        }

        return DeviceReportBody(
            report_type        = reportType,
            occurred_at_ms     = System.currentTimeMillis(),
            app_version_name   = BuildConfig.VERSION_NAME,
            app_version_code   = BuildConfig.VERSION_CODE,
            device_model       = Build.MODEL,
            android_sdk        = Build.VERSION.SDK_INT,
            device_fingerprint = runCatching { SessionManager.get().deviceFingerprint }.getOrNull(),
            exception_class    = exception?.javaClass?.name,
            exception_message  = exception?.message,
            stack_trace        = exception?.let { scrub(Log.getStackTraceString(it)) },
            thread_name        = thread?.name,
            log_lines          = captureLogcat(),
            meta               = meta,
        )
    }

    /**
     * Reads this process's OWN logcat buffer — the last LOGCAT_LINES lines of
     * every tag the app has logged, in threadtime format. No READ_LOGS
     * permission needed since Android only returns the caller's own lines.
     */
    private fun captureLogcat(): String = try {
        val pid = android.os.Process.myPid()
        val proc = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "threadtime", "-t", LOGCAT_LINES.toString(), "--pid", pid.toString())
        )
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        proc.destroy()
        scrub(text).ifBlank { "(logcat empty)" }
    } catch (e: Exception) {
        "(logcat capture failed: ${e.message})"
    }

    // JWTs are three base64url segments starting with 'eyJ' — access tokens
    // must never leave the device inside a log dump, even to our own backend.
    private val JWT_REGEX = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}")

    private fun scrub(text: String): String = JWT_REGEX.replace(text, "«jwt-redacted»")
}
