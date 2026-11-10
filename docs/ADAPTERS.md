# Site adapter maintenance notes

Adapters scrape HTML, so they break when sites change their markup. This file records where each
adapter came from, what it depends on, and how to re-sync it with FanFicFare (FFF), which tracks
site changes actively.

Upstream: https://github.com/JimmXinu/FanFicFare (core is Apache-2.0; see `NOTICE`).
The upstream commit our ports match, and which upstream file maps to which Kotlin file, are pinned in
[`fff-upstream.lock.json`](fff-upstream.lock.json). Don't record the commit anywhere else.

### Syncing with upstream

```sh
python3 tools/fff-sync.py              # what changed upstream since the pin (exit 10 = relevant changes)
python3 tools/fff-sync.py --summary    # commit list + files only
python3 tools/fff-sync.py --update-pin # after porting (or after confirming nothing applies)
```

The script keeps a blobless clone of FanFicFare in `.fff-upstream/` (gitignored). It shows only the
files we ported from, and only the `defaults.ini` sections our adapters use (`ini_sections` in the
lock file). Add new upstream files to the lock file's `files` map when porting a new adapter.

The whole procedure (triage, port, test, rebuild, pin, commit on a branch) is automated by the
`adapter-sync` agent (`.claude/agents/adapter-sync.md`, runs on Sonnet): ask Claude Code to "sync
the adapters". It escalates structural changes instead of guessing and never fetches live story
sites on its own.

Also check the FFF wiki "ProblematicSites" page for bot-check changes:
https://github.com/JimmXinu/FanFicFare/wiki/ProblematicSites

## Verification status

| Site | Last confirmed working | How |
|---|---|---|
| Sufficient Velocity | 2026-11-10 | CLI (recorded fixtures) |
| SpaceBattles | 2026-11-10 | on device; Cloudflare passed by the hidden WebView |
| Questionable Questing | 2026-11-10 | on device; bot check needed manual interaction |
| Royal Road | 2026-11-10 | on device |
| AlternateHistory | not yet tested | |

## Testing without hammering sites

- `./gradlew :core:run --args="<url> out --record"` fetches live **and saves every HTML page** to
  `fixtures/<host>/` (gitignored, since it holds third-party story text).
- `./gradlew :core:run --args="<url> out --replay --no-images"` re-runs fully offline from `fixtures/`.
  Pages that weren't recorded return 404 (images are never recorded).
- `CoreTest.replaySufficientVelocity` replays a recorded SV thread and is skipped if fixtures are absent.
  Add similar replay tests per site once fixtures exist.
- From a datacenter/office IP, SpaceBattles and Royal Road answer with a Cloudflare challenge (403),
  so record those from a home connection. SV and QQ answered plain HTML as of 2026-11-10.
- Be gentle: the Fetcher throttles per host (default 1.5 s). Prefer `--meta-only` when only
  checking index parsing.

## Architecture (what an adapter must do)

`core/src/main/kotlin/pocketfare/core/`:
- `SiteAdapter.loadMetadata()` returns a `Story` with the chapter list.
- `SiteAdapter.loadChapter(story, i)` returns the content `Element`. The `Downloader` clones it,
  strips scripts/attributes, inlines images, and serializes it to XHTML. Adapters shouldn't sanitize.
- Register new adapters in `sites/Sites.kt` (`adapterFor`, `isSupported`; add a `LoginSite` if the site
  supports login).
- Throw `LoginRequiredException` / `ChallengeException`; the app turns these into "tap to log in" or
  "tap to pass the bot check" notifications. `Fetcher` already detects Cloudflare/Anubis pages.
- Options (`Story.kt` `Options`) mirror FFF `personal.ini` names in comments. Add new user-facing
  options there, in `app/.../Support.kt` `Settings.toMap`/`fromMap` (used for both SharedPreferences
  and the per-download JSON overrides), and in the `DownloadOptions` composable (`MainActivity.kt`),
  which is shared by the Settings page and the "EPUB with options…" share screen.

## XenForo 2 (SpaceBattles, Sufficient Velocity, Questionable Questing, AlternateHistory)

File: `sites/XenForo2Adapter.kt`. Port of `base_xenforo2forum_adapter.py` and the per-site subclasses.
Per-site differences live in `XenForoSites.all`:

| Site | domain | aliases | prefix | notes |
|---|---|---|---|---|
| SpaceBattles | forums.spacebattles.com | forum., bare | `/` | Cloudflare from non-residential IPs |
| Sufficient Velocity | forums.sufficientvelocity.com | forum., bare | `/` | threadmark "..." fetcher sometimes outside container |
| Questionable Questing | forum.questionablequesting.com | bare | `/` | 403 when logged out on many threads; threadmarks_per_page 400, reader_posts_per_page 30. As of 2026-11-10 its bot check needed manual interaction on a home connection (the hidden WebView didn't pass it); SpaceBattles' passed without interaction |
| AlternateHistory | www.alternatehistory.com | bare | `/forum/` | FFF uses `div.message-inner` for post dates (only used without threadmarks) |

Not ported (FFF has them): althistory.com, the-sietch.com (`/index.php?` prefix; reader URL lacks category).

Flow:
1. Any thread/post URL is normalized to the thread. `posts/N/` redirects to the thread page, and we
   always download the whole thread (shared links are often mid-thread).
2. Threadmark categories: `div.block-outer-recent-threadmarks a.menuTrigger[href*=threadmarks]`;
   category number from `threadmark_category=`.
3. Each category: `<threadmarks url>?per_page=N`, items `div.structItem--threadmark`, link
   `a[data-tp-primary=on]`. Items without a link are collapsed ranges: fetch
   `div[data-xf-click=threadmark-fetcher]@data-fetchurl` recursively. Pagination via
   `ul.pageNav-main li.pageNav-page a` (`...page=N`).
4. Category order: Threadmarks, Sidestory, Apocrypha, Omake, Media, Informational, Staff Post,
   then the rest alphabetically (FFF default `threadmark_category_order`).
5. Chapters: reader mode (`<thread>/reader/page-P?threadmark_category=C`, P from the index in the
   category / posts-per-page, trying offsets 0, +1, -1, +2, -2 like FFF) caches up to 10/30 posts per request.
   Fallback: fetch `posts/N/`. Posts are cached by `article.message--post@data-content` ("post-N").
6. Post body: `article.message-body div.bbWrapper`.
7. Cleanup (FFF make_soup): lazyload `data-src`/`data-url` → `src`; drop `noscript`, `iframe`,
   expand/shrink links; Cloudflare email decode; `div.bbCodeBlock-expandContent` → `blockquote`;
   spoilers `div.bbCodeSpoiler` per `SpoilerMode`; s9e media embeds → link + thumbnail;
   smilies → alt text.
8. Metadata: title `h1.p-title-value` (prefix labels `span.label` → tags), author from first
   non-sticky post `section.message-user a.username`, tags `div.p-description a.tagItem`,
   threadmark header `div.threadmarkListingHeader` (description, Status / Index progress, cover icon),
   word count from `span.collapseTrigger.collapseTrigger--block` "(N words)".

Login / session:
- Login happens in a WebView. `LoginSite.sessionCookies()` detects the XenForo cookie prefix from
  `<prefix>user` = "<id>,<token>" (needs "Stay logged in" ticked) and keeps `<prefix>user`,
  `<prefix>session`, `<prefix>tfa_trust`.
- Logged-out detection: `<html data-logged-in="false">`. If we hold a session and see this, it
  expired: `LoginRequiredException(expired = true)`. Logged-out 403s → `LoginRequiredException`.
- If a board changes its prefix, detection should still work. If XenForo changes the cookie value
  format, update the regex in `LoginSite.sessionCookies`.

Not ported from FFF: `include_dice_rolls`, `author_avatar_cover`, `always_use_forumtags` (we always
add forum tags), `minimum_threadmarks` (we use threadmarks whenever there is at least 1),
`threadmark_category_order` / `order_threadmarks_by_date_categories` customization,
`capitalize_forumtags`, `use_threadmark_wordcounts`.

## Royal Road

File: `sites/RoyalRoadAdapter.kt`. Port of `adapter_royalroadcom.py`, **redesign UI path only**
(FFF still carries a legacy-UI path; port it if RR reverts).

- Story metadata: `script[type=application/ld+json]` (name, author, description, image, dates).
- Chapters: `window.chapters = [...]` JSON in page source; skip entries with non-empty `subscriptionTiers`.
- Tags: `div#chapterHeroData a[href^=/fictions/search?tagsAdd=]`; status labels `span.bg-accent`.
- Chapter body: `div.chapter-inner.chapter-content`. Author notes: nearest `div.author-note-portlet`
  before/after it in document order.
- Anti-piracy: RR injects hidden sentences whose random class has `display:none` in a `<style>`.
  We collect those classes and remove matching elements (FFF does the same). If stolen-content
  notices start appearing in output, this is what broke.
- Bot check: RR added **Anubis** (proof-of-work) in 2026 and sometimes sits behind Cloudflare.
  `Fetcher` detects both; the app solves them in a hidden WebView (`Net.solveHeadless`) and shares
  cookies via `AppCookieJar` (OkHttp UA = WebView default UA, which the cookies are tied to).
  If Anubis changes markers, update `detectChallenge()` in `Fetch.kt`.
- Login isn't implemented (not needed for free chapters).

## Ideas / TODO

- **AO3: check when it's back up.** It was down as of 2026-11-10 (expected for about a week). AO3 serves
  its own EPUB download (`/downloads/<id>/<title>.epub`), so an adapter may only need to fetch that
  rather than scrape chapters. Check FFF's `adapter_archiveofourownorg.py` / `base_otw_adapter.py` for
  login and adult-content handling.

- AO3: it serves its own EPUB download; an adapter could fetch that directly.
- Update mode (only fetch new chapters) would need reading back the existing EPUB.
