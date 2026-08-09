// update/ServerConfig.kt
package com.example.bluehive.update

import com.example.bluehive.BuildConfig

/**
 * Single source of truth for BlueHive's update-server address.
 *
 * NOW READ FROM BuildConfig, not hardcoded here.
 *
 * This file used to define `const val HOST = "192.168.1.136"` and build
 * `"http://$HOST:8090"` itself, while BuildConfig.UPDATE_BASE_URL — set from
 * BLUEHIVE_PROD_UPDATE_URL / BLUEHIVE_DEV_UPDATE_URL in app/build.gradle.kts —
 * existed and was referenced by NOTHING but a comment. Two sources of truth for
 * one address, and the app used the one that could not be configured.
 *
 * Why that mattered more here than anywhere else:
 *
 *  * The scheme was baked in as "http://". Setting BLUEHIVE_PROD_UPDATE_URL to
 *    an https address would have changed nothing at all — the updater would have
 *    carried on fetching plaintext from a LAN IP.
 *  * The release network-security-config now switches to the strict, no-cleartext
 *    variant as soon as all three BuildConfig URLs are https. This file's http://
 *    URL is NOT one of those three, so a public build would have selected the
 *    strict config and then had the platform refuse this request — self-update
 *    silently stopping while everything else worked.
 *
 * Trailing slashes are trimmed because both call sites concatenate a path
 * directly (`updateBaseUrl + "/apk/"`, `updateBaseUrl + "/bluehive/version"`).
 * A configured value ending in "/" would produce a double slash. Note this is
 * the OPPOSITE normalisation to ApiClient, where Retrofit REQUIRES the trailing
 * slash — same class of bug, opposite fix, which is exactly why each is applied
 * where the URL is consumed rather than assumed once globally.
 */
object ServerConfig {

    /**
     * Update-server base URL, no trailing slash.
     *
     * Change it via Gradle, not by editing code:
     *   BLUEHIVE_PROD_UPDATE_URL=https://updates.<domain>
     */
    val updateBaseUrl: String
        get() = BuildConfig.UPDATE_BASE_URL.trimEnd('/')

    /**
     * True when the update channel is authenticated.
     *
     * The sha256 in the manifest only proves the APK matches what the SERVER
     * said — and over plaintext an attacker in the path supplies both the APK
     * and the hash, so the check passes against their own value. It verifies
     * integrity against corruption, never authenticity against interference.
     * TLS is what makes it mean anything, so the updater states the distinction
     * rather than leaving it implied.
     */
    val isSecureChannel: Boolean
        get() = updateBaseUrl.startsWith("https://", ignoreCase = true)
}
