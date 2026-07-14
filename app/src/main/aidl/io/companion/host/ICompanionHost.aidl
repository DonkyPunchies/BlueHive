// src/main/aidl/io/companion/host/ICompanionHost.aidl
package io.companion.host;

import io.companion.host.ICompanionHostCallback;

// PUBLISHED CONTRACT — must be byte-identical in every companion and host.
// A valid host exposes a bound service that implements this interface.
interface ICompanionHost {

    // EAGER READINESS GATE (cold-start). Called by the companion at bind time,
    // BEFORE any UI is rendered. Returns one of the IDENTITY_STATE_* constants
    // in CompanionHostContract:
    //   READY      -> proceed; call getAccessToken()
    //   NOT_PAIRED -> host has no identity yet; the companion shows a "set up in
    //                 your host app" screen and exits
    //   HOST_BUSY  -> host mid-setup; the companion waits and retries
    int getIdentityState();

    // LAZY TOKEN PROVIDER. Returns a CURRENT, backend-signed access token (JWT).
    // The host MUST refresh it against its identity backend if near expiry
    // BEFORE returning. Returns null/empty if identity is gone (logout/unpair) —
    // this is the mandatory PULL-PATH logout signal and the source of truth.
    // The companion forwards this token to its backend as a Bearer credential; it
    // never validates the signature itself and never sees a refresh token.
    String getAccessToken();

    // Contract version this host implements. The companion refuses hosts whose
    // version is below its required minimum (append-only contract evolution).
    int getHostContractVersion();

    // Stable, opaque id for the paired identity. If it changes between calls,
    // the host re-paired as a different account/device and the companion resets.
    String getHostIdentityFingerprint();

    // OPTIONAL. A host MAY implement this to push instant logout via the
    // callback. Returns true if registered. If false/unimplemented, the companion
    // relies on the pull-path (null token) instead.
    boolean registerCallback(in ICompanionHostCallback cb);
}
