package com.example.bluehive.auth

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore

/**
 * Who this device is, and how it proves it.
 *
 * TWO DIFFERENT THINGS, and conflating them is the mistake this file exists to
 * avoid:
 *
 *   FINGERPRINT (ANDROID_ID) is a LABEL. The device owner can read it, so
 *   anyone can claim it. It is useful only to RECOGNISE a returning TV — never
 *   to authorise one. The backend treats it the same way: bluehive.devices has
 *   it as an ordinary column, not a credential.
 *
 *   KEY (Android Keystore) is a PROOF. The private half is generated inside the
 *   secure element and cannot be extracted, even by the device's owner. Only the
 *   public half is ever sent. A server challenge signed by that key is something
 *   a copied fingerprint cannot produce.
 *
 * WHAT SURVIVES WHAT
 *   reinstall       fingerprint survives, KEY IS DESTROYED (Android deletes
 *                   Keystore entries with the app) — so a reinstalled app
 *                   registers a new key and re-links by logging in.
 *   factory reset   both change. The device is genuinely new.
 *
 * That asymmetry is why the account link comes from the user's credentials and
 * not from the fingerprint: a stolen ANDROID_ID gets an attacker a device row
 * with no account attached, which is worth nothing.
 */
object DeviceIdentity {

    private const val TAG = "DeviceIdentity"
    private const val KEY_ALIAS = "bluehive_device_key"

    /**
     * Stable per device + per signing key, survives reinstall, changes on factory
     * reset. Scoped to our signing key since Android 8, so no other developer's
     * app sees the same value.
     *
     * Falls back to a random value only if ANDROID_ID is unreadable — that value
     * will NOT survive a reinstall, which is a degraded but functional state:
     * the user simply looks like a new device next time.
     */
    @SuppressLint("HardwareIds")
    fun fingerprint(context: Context): String {
        val id = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()

        if (id.isNullOrBlank() || id == "9774d56d682e549c") {
            // That literal is a well-known buggy constant shipped by a batch of
            // early devices, where every unit reported the same ANDROID_ID.
            // Treating it as unique would merge unrelated TVs into one device row.
            Log.w(TAG, "ANDROID_ID unusable — falling back to a non-persistent id")
            return "fallback-" + java.util.UUID.randomUUID().toString()
        }
        return id
    }

    /**
     * Public half of a hardware-backed keypair, base64 X.509, created on first
     * call and reused thereafter.
     *
     * Returns null when the device has no usable keystore. Low-end TV boxes
     * genuinely ship broken or absent TEEs, so this MUST degrade rather than
     * throw — a device that cannot hold a key still deserves to sign in. The
     * backend stores public_key as nullable for exactly this reason.
     */
    fun publicKeyBase64(): String? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        if (!ks.containsAlias(KEY_ALIAS)) {
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                // Deliberately NOT setUserAuthenticationRequired(true): a TV has
                // no lock screen in most living rooms, and requiring auth would
                // make the key unusable on the devices we actually ship to.
                .build()

            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply { initialize(spec) }
                .generateKeyPair()
            Log.i(TAG, "device keypair created in Keystore")
        }

        val cert = ks.getCertificate(KEY_ALIAS) ?: return@runCatching null
        Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }.onFailure {
        Log.w(TAG, "no usable keystore (${it.message}) — registering without a key")
    }.getOrNull()

    /**
     * Which app installed this APK, or null when nothing claimed it (adb, or an
     * installer that set no source).
     *
     * CLIENT-REPORTED, THEREFORE FORGEABLE. Patch the APK and this returns
     * anything. It is a label for the admin view — how did this user arrive —
     * and must never gate an entitlement decision. The backend derives
     * `host_installed` from it on the same understanding.
     *
     * getInstallSourceInfo() needs API 30; minSdk here is 25, hence the
     * deprecated fallback for older TV boxes.
     */
    @Suppress("DEPRECATION")
    fun installSource(context: Context): String? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            pm.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    /** Human label for the admin view, e.g. "onn 4K Streaming Box". */
    fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .trim()
            .ifBlank { "Android TV" }
            .take(255)
}
