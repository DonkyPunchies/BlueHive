package com.example.bluehive.models

import com.google.gson.annotations.SerializedName

/**
 * Request/response bodies for BlueHive's own account endpoints
 * (/api/bluehive/auth/signup, /login, /refresh), served by bluehive-api.
 *
 * Note for future edits: Kotlin block comments NEST, unlike Java's. Writing the
 * path as "auth/" followed by a star opens an inner comment here, and the closing
 * marker below then terminates only that one — leaving this whole file inside an
 * unclosed comment, which fails as "Unresolved reference" on every type it
 * declares rather than pointing at the comment.
 *
 * These replace the platform-issued, host-injected token entirely. BlueHive now
 * signs its own identities, so nothing here touches platform-api and there is no
 * shared secret between the two backends.
 *
 * Field names mirror the Pydantic models in bluehive_plugin/accounts.py exactly.
 * A mismatch does NOT fail loudly — Gson leaves the field null and the failure
 * surfaces later as an unexplained 422 or a blank screen, so keep them in step.
 */

data class BhSignupRequest(
    val email: String,

    @SerializedName("phone_number")
    val phoneNumber: String?,

    @SerializedName("device_fingerprint")
    val deviceFingerprint: String,

    // Null when the device has no usable keystore. The backend accepts that —
    // a TV that cannot hold a key can still have an account.
    @SerializedName("public_key")
    val publicKey: String?,

    // Which app installed the APK. Reported, not proven; the backend stores it
    // as a label and derives host_installed from it.
    @SerializedName("install_source")
    val installSource: String?,

    @SerializedName("device_name")
    val deviceName: String?,
)

/**
 * Sign in with the host's signed pass rather than an email.
 *
 * Same device fields as [BhLoginRequest] — the server still needs to know which TV
 * this is — with the assertion standing in for the credential.
 */
data class BhHostProvisionRequest(
    @SerializedName("assertion")
    val assertion: String,

    @SerializedName("device_fingerprint")
    val deviceFingerprint: String,

    @SerializedName("public_key")
    val publicKey: String?,

    @SerializedName("install_source")
    val installSource: String?,

    @SerializedName("device_name")
    val deviceName: String?,
)

data class BhLoginRequest(
    val email: String,

    @SerializedName("device_fingerprint")
    val deviceFingerprint: String,

    @SerializedName("public_key")
    val publicKey: String?,

    @SerializedName("install_source")
    val installSource: String?,

    @SerializedName("device_name")
    val deviceName: String?,
)

data class BhRefreshRequest(
    @SerializedName("refresh_token")
    val refreshToken: String,
)

data class BhAuthResponse(
    // Public identifier, e.g. "BH-W93MLH". Safe to show in the UI or quote in a
    // support message — distinct from the internal numeric id.
    @SerializedName("user_id")
    val userId: String,

    @SerializedName("access_token")
    val accessToken: String,

    @SerializedName("refresh_token")
    val refreshToken: String,

    @SerializedName("expires_in")
    val expiresIn: Int,
)

/**
 * The signed assertion a host app passed us at launch.
 *
 * Opaque to this client on purpose. It is not parsed, trusted or acted on here -
 * only relayed, because only the backend holds the key that makes it meaningful.
 */
data class HostEntitlementRequest(
    @SerializedName("assertion")
    val assertion: String,

    /**
     * Is a host app installed on this device at all?
     *
     * A DIFFERENT QUESTION FROM "did the host give us a pass". The provider
     * returning nothing is ambiguous — the host may be installed and simply have
     * nothing to say yet. This answers the unambiguous one, asked straight of
     * Android's package manager.
     *
     * NULL MEANS "NOT SURE", AND NULL IS THE SAFE ANSWER. Gson omits it from the
     * body, and the server treats a missing field as no information at all. Only
     * an explicit false marks the machine as abandoned, and the relay only ever
     * sends false once it has seen the host missing several times over several
     * minutes — an app update or a storage unmount must never be mistaken for an
     * uninstall.
     */
    @SerializedName("host_present")
    val hostPresent: Boolean? = null,

    /**
     * Does the installed host still hold an account?
     *
     * THE THIRD QUESTION, and the one [hostPresent] cannot reach. Unpairing
     * leaves the package installed, so the presence check sees a perfectly
     * healthy machine while the television behind it is attached to nothing.
     * Before this field that device kept working off our mirrored pass until it
     * expired on its own, which is up to two days.
     *
     * NULL IS THE SAFE ANSWER, same as above and for the same reason. It covers
     * no host installed, a host too old to publish the column, and any read that
     * failed. Only an explicit false counts, and only a host that is present and
     * says outright that it holds no session produces one.
     *
     * An explicit false marks this machine abandoned — reversibly. Re-pairing
     * mints a newer pass and the mark lifts; replaying the old one cannot lift
     * it. The effect stops at this machine and never reaches the account's other
     * televisions or the licence's member accounts.
     */
    @SerializedName("host_paired")
    val hostPaired: Boolean? = null,
)

/**
 * What the backend made of the assertion we just handed it.
 *
 * The relay used to discard this body entirely (`Response<Unit>`), which meant
 * the client reported its standing and then had no idea what it was. Reading
 * `standing` is what lets the app act on the answer instead of only filing it.
 *
 * `standing` is the SERVER's verdict, not the token's claim: it has already
 * folded in expiry, so a stale pass that still reads good inside arrives here as
 * inactive. Trust this value over anything the client could work out locally.
 */
data class HostEntitlementResponse(
    @SerializedName("ok")
    val ok: Boolean = false,
    /** unknown | active | inactive | banned. Anything else must be treated as unknown. */
    @SerializedName("standing")
    val standing: String? = null,
    @SerializedName("expires_at")
    val expiresAt: String? = null,
)
