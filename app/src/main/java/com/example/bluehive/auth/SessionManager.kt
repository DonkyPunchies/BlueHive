package com.example.bluehive.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.util.UUID

/**
 * SessionManager
 *
 * Single source of truth for all auth state on the device.
 * Initialised once in BlueHiveApplication.onCreate() via [init].
 *
 * Dependency required in build.gradle (app):
 *   implementation "androidx.security:security-crypto:1.1.0-alpha06"
 *
 * Storage: EncryptedSharedPreferences backed by Android Keystore AES-256-GCM.
 * The raw refresh token is never readable outside this encrypted file.
 */
@SuppressLint("HardwareIds")
class SessionManager private constructor(context: Context) {

    private val prefs: SharedPreferences

    init {
        // MasterKey.Builder is the modern replacement for the deprecated MasterKeys object
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        // Generate and persist a stable device fingerprint on first run
        if (prefs.getString(KEY_DEVICE_FP, null) == null) {
            val androidId = Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: UUID.randomUUID().toString()

            val fp = MessageDigest.getInstance("SHA-256")
                .digest(androidId.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(64)

            prefs.edit().putString(KEY_DEVICE_FP, fp).apply()
            Log.d(TAG, "Device fingerprint generated and stored")
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    val accessToken:  String? get() = prefs.getString(KEY_ACCESS_TOKEN,  null)
    val refreshToken: String? get() = prefs.getString(KEY_REFRESH_TOKEN, null)
    val userId:       String? get() = prefs.getString(KEY_USER_ID,       null)
    val fullName:     String? get() = prefs.getString(KEY_FULL_NAME,     null)
    val email:        String? get() = prefs.getString(KEY_EMAIL,         null)

    /** The profile the user last selected on this device. -1 if none yet.
     *  Lets cold-start warm-up prefetch the right profile's rows without
     *  waiting on the profiles-list API to resolve. */
    val lastProfileId: Int get() = prefs.getInt(KEY_LAST_PROFILE_ID, -1)

    /** Stable SHA-256 fingerprint of ANDROID_ID. Generated once, survives reboots. */
    val deviceFingerprint: String
        get() = prefs.getString(KEY_DEVICE_FP, null)
            ?: error("DeviceFingerprint missing — was SessionManager.init() called in Application.onCreate()?")

    // PHASE 2: In the host model BlueHive holds no refresh token of its own —
    // the host owns the only device-bound refresh token. Authentication is
    // satisfied by having either a host-injected access token OR a legacy
    // standalone refresh token. This makes the property correct in both models.
    val isAuthenticated: Boolean
        get() = accessToken != null || refreshToken != null

    // ── Write ─────────────────────────────────────────────────────────────────

    fun saveSession(
        accessToken:  String,
        refreshToken: String,
        userId:       String,
        fullName:     String,
        email:        String,
    ) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN,  accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_USER_ID,       userId)
            .putString(KEY_FULL_NAME,     fullName)
            .putString(KEY_EMAIL,         email)
            .commit()
        Log.d(TAG, "Session saved for $userId")
    }

    /** Called after a silent token rotation — only updates the two token fields. */
    fun updateTokens(accessToken: String, refreshToken: String) {
        val success = prefs.edit()
            .putString(KEY_ACCESS_TOKEN,  accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .commit()
        if (success) {
            Log.d(TAG, "Tokens rotated and confirmed written to disk")
        } else {
            Log.e(TAG, "❌ Token write FAILED — storage may be full or corrupted")
        }
    }


    /**
     * PHASE 2 (host contract): inject an access token obtained from the host
     * over IPC, without a refresh token. In the host-driven model BlueHive does
     * NOT own a refresh token — the host owns it and BlueHive pulls short-lived
     * access tokens on demand. This writes ONLY the access token.
     *
     * Note: isAuthenticated is still refresh-token-based for now (legacy self-pair
     * path). Step 4 (identity port) redefines auth around host-provided tokens and
     * removes BlueHive's refresh token entirely.
     */
    fun setHostAccessToken(accessToken: String) {
        val success = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .commit()
        if (success) {
            Log.d(TAG, "Host-provided access token written to disk")
        } else {
            Log.e(TAG, "❌ Host access token write FAILED")
        }
        try {
            val payload = accessToken.split(".")[1]
            val decoded = String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE))
            val sub = org.json.JSONObject(decoded).optString("sub", null)
            if (!sub.isNullOrEmpty()) {
                // store sub as userId
                Log.d("SessionManager", "Host token userId decoded: $sub")
            }
        } catch (e: Exception) {
            Log.w("SessionManager", "Could not decode userId from host token: ${e.message}")
        }
    }

    /** Remember which profile was just selected, so the next cold-start
     *  warm-up can prefetch its personalized rows immediately. */
    fun setLastProfileId(profileId: Int) {
        prefs.edit().putInt(KEY_LAST_PROFILE_ID, profileId).apply()
        Log.d(TAG, "Last profile id set to $profileId")
    }

    /**
     * Wipes all session data except the device fingerprint.
     * The fingerprint must survive logout so re-pairing still works.
     */
    fun clearSession() {
        DeviceEventStream.stop()
        val fp = prefs.getString(KEY_DEVICE_FP, null)
        prefs.edit().clear().apply()
        if (fp != null) prefs.edit().putString(KEY_DEVICE_FP, fp).apply()
        Log.d(TAG, "Session cleared")
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG               = "SessionManager"
        private const val PREFS_FILE        = "bluehive_secure_prefs"
        private const val KEY_ACCESS_TOKEN  = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID       = "user_id"
        private const val KEY_FULL_NAME     = "full_name"
        private const val KEY_EMAIL         = "email"
        private const val KEY_DEVICE_FP     = "device_fingerprint"
        private const val KEY_LAST_PROFILE_ID = "last_profile_id"

        @Volatile private var INSTANCE: SessionManager? = null

        fun init(context: Context): SessionManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionManager(context).also { INSTANCE = it }
            }

        fun get(): SessionManager =
            INSTANCE ?: error("SessionManager not initialised — call SessionManager.init(context) in Application.onCreate()")
    }
}