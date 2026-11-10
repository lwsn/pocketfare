# TODO / ideas

Not needed for routine adapter syncs; kept out of `docs/ADAPTERS.md` to keep agent context small.

## Sites and features

- **AO3: check when it's back up.** It was down as of 2026-11-10 (expected for about a week). AO3 serves
  its own EPUB download (`/downloads/<id>/<title>.epub`), so an adapter may only need to fetch that
  rather than scrape chapters. Check FFF's `adapter_archiveofourownorg.py` / `base_otw_adapter.py` for
  login and adult-content handling.

- Update mode (only fetch new chapters) would need reading back the existing EPUB.

## Getting adapter fixes onto the phone (not implemented)

Today an adapter fix means rebuilding and reinstalling the APK by hand. Two complementary options:

**Option A: Auto-updating releases.**
- A GitHub Actions workflow runs `./gradlew :core:test :app:assembleRelease` on a version tag (or on
  merges touching `core/src/main/kotlin/pocketfare/core/sites/`) and attaches the signed APK to a
  GitHub Release.
- On the phone, [Obtainium](https://github.com/ImranR98/Obtainium) watches the repo's releases and
  installs updates.
- Prerequisites:
  - A dedicated release keystore. Release builds are currently signed with the debug key
    (`app/build.gradle.kts`).
  - The keystore and passwords go in GitHub Actions secrets with an offline backup, never in the repo.
  - A one-time uninstall/reinstall on the phone when switching from the debug key.
  - `versionCode` must increase every release (derive it from the tag or CI run number).
- Pairs with the `adapter-sync` agent: sync, then merge, then tag, and the phone updates itself.
- Covers every kind of change (logic, new sites, UI) with no runtime code loading.

**Option B: Signed remote selectors (data-driven adapters).**
- Move site-specific values out of the Kotlin adapters into a JSON config the app fetches (published
  with releases or served from the repo):
  - CSS selectors (e.g. `div.structItem--threadmark`, `article.message-body div.bbWrapper`,
    `div.chapter-inner.chapter-content`)
  - URL regexes
  - per-site values (`XenForoSites.all`)
  - bot-check markers (`detectChallenge()` in `core/.../Fetch.kt`)
- The bundled defaults stay in the app. The remote file only overrides them and is cached for offline use.
- The file must be signed (e.g. Ed25519, public key embedded in the app) and verified before use, with
  a `schemaVersion` and `minAppVersion` so old apps ignore incompatible files.
- Covers most upstream FanFicFare fixes (selector/regex tweaks) within minutes and with no reinstall.
  Logic changes and new sites still need Option A.
- Runs no code, so the security risk is low. `adapter-sync` could publish the override file after a sync.

**Suggested order:** A first (simpler, covers everything). Add B if waiting for a release proves too
slow for selector-only fixes.

**Rejected:** dex hot-loading (`DexClassLoader`). Downloaded code would run with access to the
encrypted login cookies, so it needs signature checks and adapter-API versioning. It adds the most
attack surface for little gain over A + B.
