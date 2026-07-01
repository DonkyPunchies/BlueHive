// src/main/java/io/bluehive/host/BlueHiveHostContract.kt
package io.bluehive.host

/**
 * PUBLISHED CONTRACT CONSTANTS — keep byte-identical in BlueHive and every host.
 *
 * Pairs with IBlueHiveHost.aidl / IBlueHiveHostCallback.aidl. Defines the
 * launch surface (how a host starts BlueHive) and the bind surface (how
 * BlueHive connects back to the host's IBlueHiveHost service).
 */
object BlueHiveHostContract {

    /** Bump ONLY with append-only AIDL changes; gate new behavior on this. */
    const val CONTRACT_VERSION = 1

    // ── Launch surface (host -> BlueHive, via Intent) ───────────────────────
    const val ACTION_LAUNCH      = "io.bluehive.host.action.LAUNCH"
    const val CATEGORY_COMPANION = "io.bluehive.host.category.COMPANION"

    // Optional launch extra (boolean). A host sets this to true to tell
    // BlueHive "I already checked for updates before launching you — skip
    // your own check." Absent/false is the safe default: BlueHive checks for
    // itself, which is correct for any host that doesn't orchestrate updates.
    // Added append-only; no CONTRACT_VERSION bump required.
    const val EXTRA_SKIP_UPDATE_CHECK = "io.bluehive.host.extra.SKIP_UPDATE_CHECK"

    // ── Bind surface (BlueHive -> host, via bindService) ────────────────────
    // The host registers a Service with an intent-filter for this action and
    // returns an IBlueHiveHost binder from onBind().
    const val ACTION_BIND_HOST = "io.bluehive.host.action.BIND_HOST"

    // ── getIdentityState() return values ────────────────────────────────────
    const val IDENTITY_STATE_READY      = 0
    const val IDENTITY_STATE_NOT_PAIRED = 1
    const val IDENTITY_STATE_HOST_BUSY  = 2
}