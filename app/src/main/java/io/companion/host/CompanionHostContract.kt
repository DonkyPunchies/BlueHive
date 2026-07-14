// src/main/java/io/companion/host/CompanionHostContract.kt
package io.companion.host

/**
 * PUBLISHED CONTRACT CONSTANTS — keep byte-identical in every companion and host.
 *
 * Pairs with ICompanionHost.aidl / ICompanionHostCallback.aidl. Defines the
 * launch surface (how a host starts a companion) and the bind surface (how a
 * companion connects back to the host's ICompanionHost service).
 */
object CompanionHostContract {

    /** Bump ONLY with append-only AIDL changes; gate new behavior on this. */
    const val CONTRACT_VERSION = 1

    // ── Launch surface (host -> companion, via Intent) ──────────────────────
    const val ACTION_LAUNCH      = "io.companion.host.action.LAUNCH"
    const val CATEGORY_COMPANION = "io.companion.host.category.COMPANION"

    // Optional launch extra (boolean). A host sets this to true to tell the
    // companion "I already checked for updates before launching you — skip your
    // own check." Absent/false is the safe default: the companion checks for
    // itself, which is correct for any host that doesn't orchestrate updates.
    // Added append-only; no CONTRACT_VERSION bump required.
    const val EXTRA_SKIP_UPDATE_CHECK = "io.companion.host.extra.SKIP_UPDATE_CHECK"

    // ── Bind surface (companion -> host, via bindService) ───────────────────
    // The host registers a Service with an intent-filter for this action and
    // returns an ICompanionHost binder from onBind().
    const val ACTION_BIND_HOST = "io.companion.host.action.BIND_HOST"

    // ── getIdentityState() return values ────────────────────────────────────
    const val IDENTITY_STATE_READY      = 0
    const val IDENTITY_STATE_NOT_PAIRED = 1
    const val IDENTITY_STATE_HOST_BUSY  = 2
}
