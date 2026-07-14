# BlueHive — Host Integration

BlueHive is a **companion app**: no launcher icon, no login of its own. It runs only
when a **host** app launches it, then binds back to the host over AIDL to get its
identity. The host owns the account and hands BlueHive short-lived access tokens.

**Flow:** host launches BlueHive → BlueHive binds → `getIdentityState()` → if `READY`,
`getAccessToken()` → BlueHive uses that token against its backend. On 401/expiry it calls
`getAccessToken()` again; a `null` return means logged out.

BlueHive's application id is **`com.bluehive.tv`**.

---

## 1. Copy the contract (verbatim)

Three files define the wire contract — keep them byte-identical in the host and BlueHive:

```
java/io/companion/host/CompanionHostContract.kt
aidl/io/companion/host/ICompanionHost.aidl
aidl/io/companion/host/ICompanionHostCallback.aidl
```

Constants (`CompanionHostContract`):

```
ACTION_LAUNCH           = "io.companion.host.action.LAUNCH"            // host → companion (Intent)
CATEGORY_COMPANION      = "io.companion.host.category.COMPANION"
EXTRA_SKIP_UPDATE_CHECK = "io.companion.host.extra.SKIP_UPDATE_CHECK"  // optional launch extra
ACTION_BIND_HOST        = "io.companion.host.action.BIND_HOST"         // companion → host (bindService)
CONTRACT_VERSION        = 1
IDENTITY_STATE_READY=0   IDENTITY_STATE_NOT_PAIRED=1   IDENTITY_STATE_HOST_BUSY=2
```

## 2. Manifest

```xml
<!-- see + launch BlueHive on Android 11+ (or query the LAUNCH action to host any companion) -->
<queries><package android:name="com.bluehive.tv" /></queries>

<!-- the token-dispensing service: exported (BlueHive is another process), verified in code (§5) -->
<service android:name=".YourHostService" android:exported="true">
    <intent-filter><action android:name="io.companion.host.action.BIND_HOST" /></intent-filter>
</service>
```

## 3. Launch BlueHive

```kotlin
startActivity(Intent(ACTION_LAUNCH).apply {
    addCategory(CATEGORY_COMPANION)
    setPackage("com.bluehive.tv")
    // putExtra(EXTRA_SKIP_UPDATE_CHECK, true)  // set only if the host already updated it
})
```

This intent is the **only** entry point — BlueHive has no launcher icon.

## 4. Implement `ICompanionHost`

Return an `ICompanionHost.Stub` from `onBind()`. Every method must `verifyCaller()` first
(see §5). Calls arrive on a binder thread.

| Method | Returns | Notes |
|---|---|---|
| `getIdentityState()` | `READY` / `NOT_PAIRED` / `HOST_BUSY` | Called first, before any UI. Return `READY` only if you can produce a token. |
| `getAccessToken()` | JWT `String`, or `null`/empty | **Refresh if near expiry before returning.** `null`/empty = logged out (this is the logout signal). |
| `getHostContractVersion()` | `1` | |
| `getHostIdentityFingerprint()` | stable opaque id | If it changes between calls, BlueHive resets. |
| `registerCallback(cb)` | `boolean` | Optional. Call `cb.onIdentityRevoked()` on logout for instant push; else BlueHive discovers it via the `null` token. |

## 5. Verify the caller (mandatory — this is the security)

The service is exported, so **any** app can bind. Dispense a token only to the real BlueHive,
or you leak the user's account:

```kotlin
fun verifyCaller(): Boolean {
    val pkgs = packageManager.getPackagesForUid(Binder.getCallingUid()) ?: return false
    if ("com.bluehive.tv" !in pkgs) return false           // 1. right package
    return signingCertSha256("com.bluehive.tv") == PINNED_CERT   // 2. right signer (pin the cert)
}
```

Package ids can be squatted; a **pinned signing cert cannot**. (A host that accepts arbitrary
companions can instead pin the cert on first use behind an explicit user-consent prompt.)

## 6. Backend requirements

The host only *relays* a token — BlueHive calls the backend itself — so:

- **Host and BlueHive share the same backend.** BlueHive sends the token as
  `Authorization: Bearer …` to its own configured endpoints (profiles, watch history,
  streaming, `/api/me`, an events stream, …). The backend must implement those routes.
- **The token is a JWT** with `exp` and `sub` claims. BlueHive reads them locally and does
  **not** verify the signature — sign it however you like; your backend validates it.
- **You own pairing + refresh.** The host pairs the device and holds the refresh token; the
  refresh is bound to the host's device fingerprint (which is why BlueHive can't self-refresh
  and returns to the host on every 401).
- **`null` = logout.** Return `null`/empty from `getAccessToken()` the moment identity is gone.

## Checklist

- [ ] 3 contract files copied verbatim
- [ ] `<queries>` + exported `BIND_HOST` service
- [ ] Launch via `ACTION_LAUNCH` + `CATEGORY_COMPANION`, `setPackage("com.bluehive.tv")`
- [ ] `verifyCaller()` (package **and** pinned cert) at the top of every method
- [ ] `getAccessToken()` returns a fresh backend-signed JWT (`exp` + `sub`), `null` on logout
- [ ] Backend implements BlueHive's API + device-bound pairing/refresh
