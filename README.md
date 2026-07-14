# BlueHive

**A TMDB-powered media browser for Android TV.** Browse and discover anime, movies, and
TV — posters, details, ratings, and trailers — in a fast, D-pad-first interface built for
the living room.

> [!IMPORTANT]
> **BlueHive is a companion app.** It has no home-screen icon and cannot be opened on
> its own. It runs only when started by a **compatible host app** that signs you in and
> hands BlueHive your credentials. If you install BlueHive by itself, nothing appears in
> your launcher — that's expected. See **[Requirements](#requirements)** first.

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

- **Browse and discover** anime, movies, and TV — trending, search, genres, recommendations, and personalized rows, all from TMDB metadata
- **Rich detail pages** — posters, ratings, overviews, cast, and season/episode listings
- **Trailers** played inline while you browse
- **Per-user profiles** — each with its own favorites and history
- **Built for the TV** — leanback layout, D-pad navigation, poster preloading
- **Silent self-update** — keeps itself current without a store

## Requirements

To use BlueHive you need **all** of the following:

1. **An Android TV device** — Android 7.1 (API 25) or newer, with leanback (a TV box,
   streaming stick, or television). ARM is supported; phones and tablets are not.
2. **A compatible host app that you sign into.** BlueHive delegates *all* login and
   identity to a host. The host must implement the BlueHive contract **and be signed in**
   before BlueHive can do anything — this is by design.
3. **A compatible backend.** BlueHive fetches its catalog and authenticates against a
   specific backend API, which the host is paired with. The host and BlueHive must share a
   compatible backend.

> BlueHive is one piece of a small system: the **host** (owns your account), the
> **backend** (content + identity), and **BlueHive** (the TV experience). You need all
> three for a working setup.

## How it works — the companion model

BlueHive never manages accounts or passwords itself. Identity lives entirely in the
host, and BlueHive borrows it:

- **No launcher tile.** The only way in is the host starting BlueHive via the contract's
  launch intent.
- **The host owns your identity.** You sign in / pair *inside the host*. The host holds
  the long-lived credential and issues BlueHive short-lived access tokens.
- **BlueHive pulls tokens on demand.** On launch it binds to the host, checks an identity
  is ready, and requests a token for its API calls. Near expiry it asks for a fresh one.
- **Logout is instant and one-way.** Log out in the host and the next token request
  returns nothing, so BlueHive drops its session. It never sees your password or a
  refresh token — only the short-lived tokens the host hands it.

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

## Installing (users)

BlueHive needs a **host app** (which holds your account) plus its **backend**. Getting it
running is four steps:

1. **Get a compatible host app and sign in.** Install a host that implements the BlueHive
   contract, open it, and complete its **sign-in / pairing**. This is your actual login —
   BlueHive has none of its own, so the host must be signed in *before* BlueHive works.
2. **Enable sideloading** on your TV (once): *Settings → Apps → Security & restrictions →
   Install unknown apps*, and allow it for the app you'll install from.
3. **Install BlueHive** — add it inside the host's add-ons/installer if it has one, or
   sideload the APK from [Releases](../../releases):
   ```sh
   adb install BlueHive-release.apk
   ```
   It won't show in your launcher — that's expected. You open it *through* the host.
4. **Launch BlueHive from the host and approve it once.** The first time, the host asks
   *"Allow BlueHive to use your account?"* — approve it. BlueHive then opens, pulls a
   token from the host, and loads your content. You only approve once per device.

**If it's not working:**

| What you see | What it means |
| --- | --- |
| No icon / can't find BlueHive | Working as intended — launch it from the host, not the launcher. |
| *"Set up BlueHive in your host app first"*, then it closes | The host isn't signed in / paired yet. Finish the host's login (step 1). |
| Opens, but nothing loads or you get errors | The host is signed in, but its **backend** isn't one BlueHive is built for. The host and BlueHive must share a compatible backend. |

## Building a compatible host (developers)

An app becomes a BlueHive host by implementing the **`io.companion.host`** contract: a
**launch** intent, an exported **`ICompanionHost`** bound service that verifies the caller
and dispenses backend-signed JWTs, and a **backend** BlueHive's API runs against.

📘 **Full spec — the constants, method contracts, the security check, backend
requirements, and a copy-paste skeleton — is in
[HOST_INTEGRATION.md](HOST_INTEGRATION.md).**

## Building from source

**Prerequisites:** JDK 17, the Android SDK, and Android Studio (or Gradle directly).

**Debug build:**
```sh
./gradlew assembleDebug
```

**Release build** — release signing is required and has no fallback, so create a
`keystore.properties` at the project root (git-ignored; keep the keystore **outside** the
repo):
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

BlueHive checks a version manifest at launch and can update itself silently. A host may
also update it before launching (setting `EXTRA_SKIP_UPDATE_CHECK` so BlueHive skips its
own redundant check). Either path installs through the system package installer, so a
one-time "install unknown apps" grant is required.

## Built with

Kotlin · Jetpack Compose for TV · Media3 / ExoPlayer · GeckoView · NewPipe Extractor ·
Retrofit / OkHttp · Coil

## License

BlueHive is free software: you can redistribute it and/or modify it under the terms of
the **GNU General Public License v3.0** as published by the Free Software Foundation. The
full license text is in [LICENSE](LICENSE).

Copyright (C) 2026 DonkyPunchies

BlueHive is distributed in the hope that it will be useful, but **WITHOUT ANY WARRANTY**;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GNU General Public License for more details.

## Disclaimer

BlueHive is a media browser: it shows catalog metadata (titles, posters, ratings,
overviews) from **The Movie Database (TMDB)** and plays trailers from their original
source. It hosts and serves no video content of its own.

> This product uses the TMDB API but is not endorsed or certified by TMDB.

You remain responsible for ensuring your use complies with the laws and terms that apply
to you, including TMDB's terms of use.
