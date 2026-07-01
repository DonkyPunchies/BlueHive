// src/main/aidl/io/bluehive/host/IBlueHiveHostCallback.aidl
package io.bluehive.host;

// PUBLISHED CONTRACT — must be byte-identical in BlueHive and every host.
//
// One-way: a slow or dead BlueHive must NEVER block the host's binder thread.
// The host fires this when the user logs out or the device is unpaired, so a
// currently-RUNNING BlueHive can drop its session immediately instead of only
// discovering it on the next getAccessToken() call.
//
// This is the OPTIONAL push-path. It is a latency optimization only — the
// mandatory pull-path (getAccessToken() returning null) remains the source of
// truth for logout. A host that doesn't implement registerCallback() still
// works; logout is just discovered slightly later.
interface IBlueHiveHostCallback {
    oneway void onIdentityRevoked();
}