# PocketFare

Save web fiction as EPUB on Android: share a story link from your browser, and the app downloads the
whole story into a single EPUB in a folder you choose.

Supported sites:
- SpaceBattles, Sufficient Velocity, Questionable Questing, AlternateHistory (XenForo forums: threadmarks, reader mode)
- Royal Road

Features:
- Share-sheet target ("Save as EPUB"), background downloads with progress notifications
- Log in to forums inside the app. Only session cookies are kept, encrypted with an Android Keystore key; your password is never stored.
- Cloudflare / Anubis bot checks are solved in a hidden WebView, falling back to a "tap to verify" notification
- Settings for images (include, shrink, grayscale), spoilers, threadmark categories, title page, and more

## Install

Build with `./gradlew :app:assembleDebug` (see Development) and install `app/build/outputs/apk/debug/app-debug.apk`
with `adb install` or by opening it on the phone.

## Development

Requires a JDK 17 or newer (output targets Java 17 bytecode) and the Android SDK with platform 37
and build tools 36.

```sh
./gradlew :core:test                 # offline unit tests + fixture replay tests
./gradlew :app:assembleDebug         # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Project layout:
- `core/`: plain Kotlin/JVM library: HTTP fetching with throttling and bot-check detection,
  site adapters (`core/src/main/kotlin/pocketfare/core/sites/`), and the EPUB writer.
  No Android dependencies, so adapters can be tested from the desktop.
- `app/`: the Android app (Jetpack Compose, WorkManager).

Command-line tool for trying adapters without a phone:

```sh
./gradlew :core:run --args="<story url> out --meta-only"          # index/metadata only
./gradlew :core:run --args="<story url> out --record"             # live; saves fetched pages to fixtures/
./gradlew :core:run --args="<story url> out --replay --no-images" # fully offline from fixtures/
```

`fixtures/` and `out/` are gitignored, since they contain third-party story text. Keep live testing
light: record once, then iterate with `--replay`.

Site adapters break when sites change their HTML. `python3 tools/fff-sync.py` shows what upstream
FanFicFare changed since the last sync (pinned in `docs/fff-upstream.lock.json`).
[`docs/ADAPTERS.md`](docs/ADAPTERS.md) records
where each adapter was ported from, how to diff upstream FanFicFare for fixes since the port, the
selectors and quirks for each site, and when each site was last confirmed working.

## License

Apache License 2.0. Site adapters are ported from [FanFicFare](https://github.com/JimmXinu/FanFicFare)
(Apache-2.0); see [NOTICE](NOTICE).

PocketFare is an independent project and is **not affiliated with or endorsed by FanFicFare** or its
maintainers. It is also not affiliated with any of the sites it downloads from.
