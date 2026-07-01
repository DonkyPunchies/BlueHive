package com.example.bluehive.webview

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import org.mozilla.geckoview.*
import org.mozilla.geckoview.WebExtension.InstallException
import org.mozilla.geckoview.WebExtensionController.AddonManagerDelegate
import org.mozilla.geckoview.WebExtensionController.PromptDelegate
import java.io.File
import java.io.FileOutputStream

/**
 * GeckoWebViewManager - SESSION RECREATION STRATEGY
 *
 * ✅ Creates a NEW session each time video opens
 * ✅ Closes old session completely (kills all child processes)
 * ✅ No persistent session = no memory leaks
 */
object GeckoWebViewManager {

    private const val TAG = "GeckoWebViewManager"
    private const val UBO_ID = "uBlock0@raymondhill.net"

    private const val CHROME_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // ===== SINGLE RUNTIME (shared), NO PERSISTENT SESSION =====
    private var geckoRuntime: GeckoRuntime? = null
    private val sessionLock = Any()

    // ===== TRACK CURRENT SESSION FOR CLEANUP =====
    private var currentSession: GeckoSession? = null
    private var sessionOwner: String? = null

    // ===== METRICS =====
    private var sessionsCreated = 0
    private var sessionsClosed = 0

    private var isInitialized = false

    /**
     * Initialize GeckoRuntime (only once)
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.d(TAG, "✅ Already initialized")
            return
        }

        Log.d(TAG, "🚀 Initializing GeckoWebViewManager...")


        val configPath = ensureConfigFile(context)

        val rtSettings = GeckoRuntimeSettings.Builder()
            .consoleOutput(false)
            .debugLogging(false)
            .lowMemoryDetection(true)
            .aboutConfigEnabled(false)
            .extensionsWebAPIEnabled(true)
            .javaScriptEnabled(true)
            .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
            .remoteDebuggingEnabled(false)
            .configFilePath(configPath)
            .enterpriseRootsEnabled(true)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .antiTracking(ContentBlocking.AntiTracking.NONE)
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.NONE)
                    .strictSocialTrackingProtection(false)
                    .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_ALL)
                    .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_ALL)
                    .build()
            )
            .build()

        try {
            geckoRuntime = GeckoRuntime.create(context, rtSettings)
            Log.d(TAG, "✅ GeckoRuntime created")

            setupExtensionDelegates()
            installUBlockOrigin(context)

            isInitialized = true
            Log.d(TAG, "✅ Initialization complete")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize", e)
        }
    }

    /**
     * ✅ NEW: Get a FRESH session each time
     * This ensures no stale child processes
     */
    fun getNewSession(ownerId: String, context: Context): GeckoSession? {
        if (!isInitialized) {
            Log.e(TAG, "❌ Not initialized!")
            return null
        }

        synchronized(sessionLock) {
            Log.d(TAG, "🆕 Creating NEW session for: $ownerId")

            // Create brand new session
            val session = createNewSession()
            if (session == null) {
                Log.e(TAG, "❌ Failed to create session")
                return null
            }

            currentSession = session
            sessionOwner = ownerId
            sessionsCreated++

            Log.d(TAG, "✅ Session #$sessionsCreated created for: $ownerId")
            logSystemStatus(context)

            return session
        }
    }

    /**
     * ✅ NEW: Close session completely (kills child processes)
     */
    fun closeSession(requesterId: String, context: Context) {
        synchronized(sessionLock) {
            Log.d(TAG, "🔴 closeSession() called by: $requesterId")

            if (sessionOwner != requesterId) {
                Log.w(TAG, "⚠️ closeSession mismatch: caller=$requesterId, owner=$sessionOwner")
                return
            }

            // ✅ CRITICAL: Close the session completely
            // This kills all child processes automatically
            try {
                currentSession?.close()
                Log.d(TAG, "✅ Session closed (child processes killed)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error closing session", e)
            }

            currentSession = null
            sessionOwner = null
            sessionsClosed++

            // Force garbage collection
            //System.gc()

            Log.d(TAG, "✅ Session closed. Total: $sessionsCreated created, $sessionsClosed closed")
            logSystemStatus(context)
        }
    }

    /**
     * Create a new GeckoSession
     */
    private fun createNewSession(): GeckoSession? {
        try {
            val session = GeckoSession()

            val settings = session.settings
            settings.allowJavascript = true
            settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            settings.userAgentOverride = CHROME_USER_AGENT

            session.open(geckoRuntime!!)

            Log.d(TAG, "✅ Session configured with Chrome UA")

            return session
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create session", e)
            return null
        }
    }

    /**
     * Log system status
     */
    private fun logSystemStatus(context: Context) {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val totalMB = memoryInfo.totalMem / (1024.0 * 1024.0)
            val availableMB = memoryInfo.availMem / (1024.0 * 1024.0)
            val usedMB = totalMB - availableMB
            val percentUsed = ((usedMB / totalMB) * 100).toInt()

            Log.d(TAG, "╔═══════════════════════════════════════╗")
            Log.d(TAG, "║         SYSTEM STATUS                 ║")
            Log.d(TAG, "╠═══════════════════════════════════════╣")
            Log.d(TAG, "📊 SESSION STATUS:")
            Log.d(TAG, "  Sessions created: $sessionsCreated")
            Log.d(TAG, "  Sessions closed: $sessionsClosed")
            Log.d(TAG, "  Current owner: ${sessionOwner ?: "none"}")
            Log.d(TAG, "")
            Log.d(TAG, "💾 MEMORY:")
            Log.d(TAG, "  Total: %.2f MB".format(totalMB))
            Log.d(TAG, "  Used: %.2f MB ($percentUsed%%)".format(usedMB))
            Log.d(TAG, "  Available: %.2f MB".format(availableMB))

            val myPid = Process.myPid()
            val myProcesses = activityManager.runningAppProcesses
                ?.filter { it.processName.contains(context.packageName) }
                ?: emptyList()

            Log.d(TAG, "⚙️ PROCESSES:")
            Log.d(TAG, "  Total: ${myProcesses.size}")

            myProcesses.forEachIndexed { index, processInfo ->
                Log.d(TAG, "  💡 #${index + 1}: ${processInfo.processName} (PID: ${processInfo.pid})")
            }

            Log.d(TAG, "╚═══════════════════════════════════════╝")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error logging system status", e)
        }
    }

    /**
     * Setup extension delegates
     */
    private fun setupExtensionDelegates() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                setupExtensionDelegates()
            }
            return
        }

        geckoRuntime?.webExtensionController?.apply {
            promptDelegate = object : PromptDelegate {
                override fun onInstallPromptRequest(
                    p0: WebExtension,
                    p1: Array<out String>,
                    p2: Array<out String>,
                    p3: Array<out String>
                ): GeckoResult<WebExtension.PermissionPromptResponse?>? {
                    return GeckoResult.fromValue(
                        WebExtension.PermissionPromptResponse(true, true, true)
                    )
                }
            }

            setAddonManagerDelegate(object : AddonManagerDelegate {
                override fun onInstalling(ext: WebExtension) {}
                override fun onInstalled(ext: WebExtension) {}
                override fun onInstallationFailed(ext: WebExtension?, ex: InstallException) {}
                override fun onReady(ext: WebExtension) {}
            })
        }
    }


    /**
     * Copy gecko_config.yaml from assets to filesDir so GeckoRuntime can read it
     */
    private fun ensureConfigFile(context: Context): String {
        val outFile = File(context.filesDir, "gecko_config.yaml")
        // Always overwrite so APK updates pick up new config
        try {
            context.assets.open("gecko_config.yaml").use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "✅ gecko_config.yaml written to ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to write gecko_config.yaml", e)
        }
        return outFile.absolutePath
    }



    /**
     * Install uBlock Origin
     */
    @SuppressLint("WrongThread")
    private fun installUBlockOrigin(context: Context) {
        geckoRuntime?.webExtensionController?.list()?.accept { list ->
            if (list?.none { it.id == UBO_ID } == true) {
                try {
                    val assetPath = "ublock_origin-1.64.0.xpi"
                    val inFile = context.assets.open(assetPath)
                    val outFile = File(context.filesDir, "ublock_origin-1.64.0.xpi")

                    FileOutputStream(outFile).use { inFile.copyTo(it) }

                    geckoRuntime?.webExtensionController
                        ?.install("file://${outFile.absolutePath}")
                        ?.accept({ ext ->
                            if (ext != null) {
                                Log.i(TAG, "✅ uBlock Origin installed")
                            }
                        }, { err ->
                            Log.e(TAG, "❌ uBlock install failed", err)
                        })

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to install uBlock", e)
                }
            }
        }
    }

    fun getRuntime(): GeckoRuntime? = geckoRuntime
    fun isReady(): Boolean = isInitialized && geckoRuntime != null
}