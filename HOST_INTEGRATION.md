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

## 7. Verifying the platform JWT on your backend (the resource-server contract)

> This is the backend side of the token §6 relays. §6 tells the host to hand BlueHive a `Bearer` JWT that *"your backend validates"* — this section is **how** that validation works. In the canonical BlueHive deployment the host relays a **platform-signed** access token and your resource server (`bluehive-api`) verifies it **in-process** with a shared secret. If you're writing a backend that must trust a platform-issued Bearer token, this is your contract.

**Trust model (read this first).** The platform (`platform-api`) is the *only* issuer. It mints every access token and signs it with a shared secret. Your service **verifies that signature locally, in-process**, and trusts whatever the verified token says. The token *is* the trust boundary: you never call `platform-api`, never read the platform DB, never look up `platform_accounts`. A valid signature + unexpired `exp` is sufficient proof of identity.

### The shared secret

You need one secret: **`JWT_SECRET_KEY`**. It must be **byte-for-byte identical** to `platform-api`'s `JWT_SECRET_KEY` — same value, no trailing whitespace, same encoding — or every verify fails.

- Deliver it as an **environment variable**, read at process start.
- **Never commit it** and **never ship it inside an app binary or client bundle.** It is a symmetric (HS256) secret: anyone holding it can *forge* tokens, not just verify them. It belongs only on trusted server processes.
- Rotating it is a coordinated change: `platform-api` and every resource server flip to the new value together.

### Token claims

The platform signs with **HS256**. What each access token carries:

| Claim | Type | Meaning |
|---|---|---|
| `sub` | string (an int in disguise) | Platform `users.id`. **Always present.** Parse with `int(sub)`. |
| `email` | string | The account email. Always present. |
| `device_id` | int, or absent | Platform `devices.id`. Present on TV/device tokens; **absent for web** logins. |
| `ws` | int, or absent | `workspace_id` the token is scoped to. Emitted on TV tokens whose user has a workspace (added alongside `device_id`); absent otherwise. |
| `iat` | int (unix seconds) | Issued-at. |
| `exp` | int (unix seconds) | Expiry. Enforced by the JWT library on decode — an expired token is rejected automatically. |
| `ent` | *(roadmap)* | Entitlement / subscription hint. **Not present today** — treat as optional. |

Treat `sub`, `device_id`, and `ws` as **opaque integers** — foreign keys into the platform's world, meaningful to *you* only as stable identifiers to key your own rows by. Do not resolve them against the platform.

### What to implement

1. **Receive the token.** Read `Authorization: Bearer <jwt>` off the request header. (`bluehive-api` also accepts an `access_token` cookie for parity — optional.)
2. **Verify it.** `jwt.decode(token, JWT_SECRET_KEY, algorithms=["HS256"])`. This validates **both** the signature **and** `exp` in one call. Any failure → `401`. Pin `algorithms` to `["HS256"]`; never disable signature/exp verification.
3. **Read the ids.** Pull `sub` (→ `int`), `device_id` (int or `None`), `ws` (int or `None`) out of the decoded payload.
4. **Key your own data by them.** Store and look up *your* records under those ids and nothing else of the platform's.
5. **Enforce nothing against the platform.** No callback, no DB peek, no re-validation hop. The verified token is the final word for its lifetime.

### Minimal reference (Python / PyJWT)

Mirrors `bluehive_plugin/bh_auth.py::require_user` — the production implementation:

```python
import os, jwt
from dataclasses import dataclass
from fastapi import HTTPException, Request

def _secret() -> str:
    key = os.getenv("JWT_SECRET_KEY", "").strip()
    if not key:
        raise RuntimeError("JWT_SECRET_KEY must equal platform-api's, byte-for-byte.")
    return key

@dataclass
class Identity:
    user_id: int              # from `sub`
    device_id: int | None     # from `device_id`; None for web
    workspace_id: int | None  # from `ws`; None for web / pre-ws tokens

def require_user(request: Request) -> Identity:
    auth = request.headers.get("Authorization", "")
    token = auth[7:] if auth.startswith("Bearer ") else request.cookies.get("access_token")
    if not token:
        raise HTTPException(401, "Not authenticated")
    try:
        # Validates signature AND exp in one shot.
        p = jwt.decode(token, _secret(), algorithms=["HS256"])
        user_id = int(p["sub"])
    except Exception:
        raise HTTPException(401, "Not authenticated")
    dev = p.get("device_id")
    ws  = p.get("ws")
    return Identity(
        user_id=user_id,
        device_id=int(dev) if dev is not None else None,
        workspace_id=int(ws) if ws is not None else None,
    )
```

**This is language-agnostic.** Any HS256 JWT library works — Node (`jsonwebtoken`), Go (`golang-jwt`), Java (`jjwt`), etc. The only requirements: HS256, the shared `JWT_SECRET_KEY`, and that your library verifies both signature and `exp`.

### Revocation window (≤ 15 min — deliberate)

Because you validate **only the token** and never the platform DB, revocation is not instant. When a device is unpaired or a user is kicked, `platform-api` stops issuing new tokens — but the *already-issued* one keeps working on your service until its `exp` passes. Access tokens live **`JWT_ACCESS_MINUTES` (default 15 minutes)**, so that window is your worst-case staleness. It's the deliberate price of *not* re-coupling every resource server to the platform database on every request. Need tighter revocation? That's a platform-side decision (shorter lifetimes), not a callback here.

### DO / DON'T

**DO**
- Verify **locally**, in-process, with HS256 + the shared secret on every request.
- Key all your data by `sub` / `device_id` / `ws` as opaque ints.
- Load `JWT_SECRET_KEY` from the environment on trusted server processes only.
- Return `401` on any decode failure (bad signature, expired, malformed).

**DON'T**
- **Don't** call `platform-api` or read the platform DB to "confirm" a token — the signature already did that.
- **Don't** store the secret in an app binary, client bundle, or repo.
- **Don't** trust an unsigned / unverified token, and don't widen `algorithms` beyond `["HS256"]`.
- **Don't** expect sub-15-minute revocation — the token's `exp` is the boundary.

## Checklist

- [ ] 3 contract files copied verbatim
- [ ] `<queries>` + exported `BIND_HOST` service
- [ ] Launch via `ACTION_LAUNCH` + `CATEGORY_COMPANION`, `setPackage("com.bluehive.tv")`
- [ ] `verifyCaller()` (package **and** pinned cert) at the top of every method
- [ ] `getAccessToken()` returns a fresh backend-signed JWT (`exp` + `sub`), `null` on logout
- [ ] Backend implements BlueHive's API + device-bound pairing/refresh
- [ ] (Resource server) Verify platform JWTs in-process (HS256 + shared `JWT_SECRET_KEY`); key data by `sub` / `device_id` / `ws`, never call platform-api
