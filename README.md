# BlueHive

**A streaming companion app for Android TV.** Anime, movies, and shows on the big
screen, wrapped in a fast, D‑pad‑first interface built for the living room.

> [!IMPORTANT]
> **BlueHive is a companion app.** It has no home‑screen icon and cannot be opened
> on its own. It runs only when started by a **compatible host app** that signs you
> in and hands BlueHive your credentials. If you install BlueHive by itself, nothing
> will appear in your launcher — that's expected. See **[Requirements](#requirements)**
> before installing.

---

## Contents

- [What it does](#what-it-does)
- [Requirements](#requirements)
- [How it works](#how-it-works--the-companion-model)
- [Installing (users)](#installing-users)
- [Building a compatible host (developers)](#building-a-compatible-host-developers)
- [Building from source](#building-from-source)
- [Updates](#updates)
- [Built with](#built-with)
- [License](#license)
- [Disclaimer](#disclaimer)

---

## What it does

- **Browse anime, movies, and TV** with trending, search, genres, and personalized rows
- **Multiple streaming sources** with in‑app extraction and playback
- **Latest trailers**, played inline while you browse
- **Per‑user profiles** — each with its own watch history, favorites, and continue‑watching
- **Live TV**
- **Subtitles** with on‑the‑fly sync adjustment
- **Built for the TV** — leanback layout, D‑pad navigation, and poster preloading for smooth scrolling
- **Silent self‑update** — keeps itself current without a store

## Requirements

To use BlueHive you need **all** of the following:

1. **An Android TV device** — Android 7.1 (API 25) or newer, with leanback support
   (a TV box, streaming stick, or television). ARM devices are supported. Phones and
   tablets are not.
2. **A compatible host app.** BlueHive delegates *all* sign‑in and identity to a host.
   Without a host installed and set up, BlueHive has no way to authenticate and no way
   to launch — this is by design. Any app that implements the **BlueHive host contract**
   (see [Building a compatible host](#building-a-compatible-host-developers)) can act as
   the host.
3. **Network access** to the backend services BlueHive streams from and authenticates
   against. These endpoints are configured at build time (see
   [Building from source](#building-from-source)).

> BlueHive is one piece of a small system: the **host** (which owns your account),
> the **backend** (content + identity), and **BlueHive** itself (the TV experience).
> You need all three for a working setup.

## How it works — the companion model

BlueHive never manages accounts or passwords itself. Identity lives entirely in the
host, and BlueHive borrows it:

- **No launcher tile.** The only way in is the host starting BlueHive through the
  contract's launch intent.
- **The host owns your identity.** You sign in or pair *inside the host*. The host
  holds the long‑lived credential and issues BlueHive short‑lived access tokens.
- **BlueHive pulls tokens on demand.** On launch it binds to the host, checks that an
  identity is ready, and requests an access token, which it uses for its own API calls.
  When the token nears expiry, it asks the host for a fresh one.
- **Logout is instant and one‑way.** When you log out in the host, the next token
  request returns nothing and BlueHive drops its session. BlueHive never sees your
  password or a refresh token — only the short‑lived tokens the host hands it.

```
  Host app                              BlueHive
    │  you sign in / pair here             │
    │  (host holds your account)           │
    │                                      │
    ├──────  launches BlueHive  ─────────► │   (no icon of its own)
    │                                      │
    │ ◄─────  binds back: "ready?"  ────── │
    │ ──────  yes → access token  ───────► │   loads your content
    │                                      │
    │ ◄─────  token expired, refresh  ──── │
    │ ──────  fresh token  ──────────────► │
    │                                      │
    │ ──────  you log out → no token  ───► │   session dropped
```

BlueHive names no host in particular — it connects to whichever compatible host
launched it. That keeps it a self‑contained companion that any conforming host can
adopt.

## Installing (users)

1. **Enable sideloading** on your TV: *Settings → Apps → Security & restrictions →
   Install unknown apps*, and allow it for whatever app you'll install from.
2. **Install a compatible host app** and complete its setup / sign‑in.
3. **Install BlueHive.** Use your host's add‑on installer if it has one, or sideload
   the APK from [Releases](../../releases):
   ```sh
   adb install BlueHive-release.apk
   ```
4. **Open BlueHive from the host.** There is no separate BlueHive icon — you launch it
   through the host, which passes it your credentials.

## Building a compatible host (developers)

An app becomes a BlueHive host by implementing the published **`io.bluehive.host`**
contract. The contract source lives in this repo and is meant to be kept byte‑identical
in BlueHive and every host:

- `app/src/main/aidl/io/bluehive/host/IBlueHiveHost.aidl`
- `app/src/main/aidl/io/bluehive/host/IBlueHiveHostCallback.aidl`
- `app/src/main/java/io/bluehive/host/BlueHiveHostContract.kt`

A valid host must provide two surfaces:

**1. Launch surface** — start BlueHive with an explicit intent:

| Constant | Value |
| --- | --- |
| `ACTION_LAUNCH` | `io.bluehive.host.action.LAUNCH` |
| `CATEGORY_COMPANION` | `io.bluehive.host.category.COMPANION` |
| `EXTRA_SKIP_UPDATE_CHECK` *(optional)* | `io.bluehive.host.extra.SKIP_UPDATE_CHECK` — set `true` if the host already checked for a BlueHive update before launching |

**2. Bind surface** — expose an **exported** service whose intent filter answers
`io.bluehive.host.action.BIND_HOST` and returns an `IBlueHiveHost` binder from
`onBind()`. The interface:

```aidl
interface IBlueHiveHost {
    // Readiness gate, called at bind time before any UI:
    //   0 READY, 1 NOT_PAIRED, 2 HOST_BUSY
    int getIdentityState();

    // A current, backend‑signed access token (JWT). The host MUST refresh it
    // if near expiry before returning. Return null/empty to signal logout —
    // this is the source of truth for "identity gone."
    String getAccessToken();

    // Contract version this host implements (current: 1).
    int getHostContractVersion();

    // Stable, opaque id for the paired identity. Changing it means the host
    // re‑paired as a different account/device and BlueHive resets.
    String getHostIdentityFingerprint();

    // Optional push‑logout registration. If unimplemented, BlueHive relies on
    // getAccessToken() returning null instead.
    boolean registerCallback(in IBlueHiveHostCallback cb);
}
```

**Host responsibilities:**

- Own the user account and the long‑lived credential; do the sign‑in / pairing.
- Mint and refresh **backend‑signed** access tokens (BlueHive never validates the
  signature itself and never receives a refresh token).
- **Verify the caller** (package, and ideally signing certificate) before dispensing a
  token — the bind service is exported and unguarded by permission on purpose, so the
  security check belongs in code.
- Return an empty/null token the moment identity is lost, so a running BlueHive drops
  its session.

Bump `CONTRACT_VERSION` only for append‑only additions, and gate new behavior on it so
older hosts keep working.

## Building from source

**Prerequisites:** JDK 17, the Android SDK, and Android Studio (or Gradle directly).

**Debug build:**
```sh
./gradlew assembleDebug
```

**Release build** — release signing is required and has no fallback, so create a
`keystore.properties` at the project root (it is git‑ignored; keep your keystore
**outside** the repo):
```properties
RELEASE_STORE_FILE=/absolute/path/to/your-release.jks
RELEASE_STORE_PASSWORD=your-store-password
RELEASE_KEY_ALIAS=your-key-alias
RELEASE_KEY_PASSWORD=your-key-password
```
then:
```sh
./gradlew assembleRelease
```

**Backend endpoints** are injected into `BuildConfig` at build time. Point them at your
own services via Gradle properties (in `~/.gradle/gradle.properties`, a project
`gradle.properties`, or `-P` flags) — use HTTPS in production:
```properties
BLUEHIVE_DEV_BASE_URL=https://content-api.example.com/
BLUEHIVE_DEV_API_KEY=your-content-api-key
BLUEHIVE_DEV_PLATFORM_URL=https://identity.example.com
BLUEHIVE_DEV_UPDATE_URL=https://updates.example.com
```

The app version is the single source of truth in `app/version.properties`
(`versionCode` / `versionName`); bump it there for each release.

## Updates

BlueHive checks a version manifest at launch and can update itself silently. A host
may also orchestrate the update before launching (and set `EXTRA_SKIP_UPDATE_CHECK` so
BlueHive skips its own redundant check). Either path installs through the system
package installer, so a one‑time "install unknown apps" grant is required.

## Built with

Kotlin · Jetpack Compose for TV · Media3 / ExoPlayer · GeckoView · NewPipe Extractor ·
Retrofit / OkHttp · Coil

## License

BlueHive is free software: you can redistribute it and/or modify it under the terms of
the **GNU General Public License v3.0** as published by the Free Software Foundation.
The full license text is in [LICENSE](LICENSE).

Copyright (C) 2026 DonkyPunchies

BlueHive is distributed in the hope that it will be useful, but **WITHOUT ANY
WARRANTY**; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for more details.

## Disclaimer

BlueHive hosts no content of its own. It is a client interface to third‑party sources,
and you are responsible for ensuring your use complies with the laws and terms that
apply to you.
