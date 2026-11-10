# PocketFare

Android app: share a story link from the browser → scrape → EPUB saved locally. Site adapters are
Kotlin ports of FanFicFare (Apache-2.0, see NOTICE).

- `core/`: pure Kotlin/JVM (jsoup + OkHttp): `Fetcher` (throttling, Cloudflare/Anubis detection),
  `sites/` adapters, `Downloader`, streaming `EpubWriter`, `FixtureInterceptor` (record/replay), CLI.
- `app/`: Android (Compose, WorkManager). Share target → `DownloadWorker`; `WebActivity` handles login
  and bot checks; `SessionStore` keeps login cookies AES-GCM-encrypted with an Android Keystore key.

To sync adapters with upstream FanFicFare, use the `adapter-sync` agent (Sonnet). It runs
`tools/fff-sync.py`, ports, tests, rebuilds and pins.

**Before touching an adapter, read `docs/ADAPTERS.md`.** It has the sync procedure, selectors, and
site quirks. Update it whenever an adapter changes. The upstream pin lives only in
`docs/fff-upstream.lock.json` (set it with `tools/fff-sync.py --update-pin`).

Build: Gradle 9.9 wrapper (runs on the default JDK), AGP 9.5 with built-in Kotlin (no
`kotlin-android` plugin; don't add it back), Kotlin 2.4, compileSdk 37 / targetSdk 36. No JDK
toolchain pin: Gradle's own JDK compiles everything to Java 17 bytecode. SDK packages: `~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager`.

Commands:
```sh
./gradlew :core:test                      # offline unit + replay tests
./gradlew :core:run --args="<url> out --replay --no-images"   # offline from fixtures/
./gradlew :core:run --args="<url> out --record"               # live; saves pages to fixtures/
./gradlew :app:assembleDebug              # APK: app/build/outputs/apk/debug/app-debug.apk
```

Live-site testing: don't needlessly spam requests at live servers. These are community-run sites.
Prefer `--replay` against recorded fixtures and unit tests. When a live run is genuinely needed, keep it
to the minimum (`--meta-only` where possible) and always use `--record` so the pages can be replayed later.
