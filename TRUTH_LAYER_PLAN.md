# TRUTH LAYER + FIX PLAN — DOWNI v3.7.0 (draft)

> **Status: plan only. Nothing in this file has been executed.**
> Written 2026-10-21, after the v3.6.1 commit (`8b5ed68` / `65b4267`). Nothing pushed.

## 0. The problem, in one paragraph

The error sheet people kept reporting ("The media stream could not be downloaded. The video may be
private, restricted, or the connection dropped.") is not one error — it is the app's **catch-all**,
used whenever the raw engine text doesn't match four keyword buckets. The real cause is destroyed in
three places before the user can see it (the engine's split swallows every exception, the inspector
swallows every exception, and Java rewrites the raw text so the JS's specific buckets can no longer
match). The catch-all then **blames the user's video** for what is usually a platform-side block or a
stale extraction layer. That violates two rules this repo already wrote down: `READ_THIS_BEFORE_UPGRADE.md`
§8.6 ("the app must always say so plainly") and §6 ("reality wins — even when the honest state looks
worse").

## 1. The contract this plan enforces

> **The raw engine reason survives to every surface, is classified once, and every surface phrases
> it in its own words — without ever naming a cause we did not verify.**

Layers and their single job:

| Layer | File | Job after this change |
|---|---|---|
| Engine (Python) | `android/app/src/main/python/downloader.py` | **Never swallow a cause.** Raise the real reason, classified once. |
| Bridge (Java) | `DowniEnginePlugin.java`, `DowniDownloadService.java` | **Transport, don't rewrite.** Pass cause + raw text through untouched. |
| Surface (JS) | `www/index.html` | **Say what happened.** Phrase the cause; show the raw text under "View details". |

## 2. The cause taxonomy (one table, used by every surface)

Codes are matched against the **raw** text, case-insensitively, first match wins.

| code | matches (raw text contains) | the honest sentence |
|---|---|---|
| `blocked` | `403`, `forbidden`, `sign in to confirm`, `not a bot`, `confirm you are not a bot` | "The platform refused DOWNI's request (HTTP 403). This is not your video's fault, and retrying now won't help." |
| `rate_limited` | `429`, `too many requests`, `rate-limit` | "The platform is rate-limiting this device. Wait a few minutes, then try again." |
| `network` | `getaddrinfo`, `name resolution`, `connection refused`, `connection reset`, `timed out`, `timeout`, `network is unreachable`, `ssl`, `certificate`, `unable to download webpage` | "DOWNI couldn't reach the network." |
| `private` | `private video`, `login required`, `sign in to confirm your`, `members-only`, `age-restricted`, `requires an account` | "This video needs an account, or it is private." |
| `geo` | `not available in your country`, `geo restricted`, `geo-restricted` | "This video isn't available in your country." |
| `format` | `requested format is not available`, `no video formats`, `no separate` | "That quality isn't offered for this video. Try Best Available." |
| `link` | `unsupported url`, `url parsing`, `is not a valid url` | "DOWNI couldn't read a video from that link." |
| `cancelled` | `cancel` | (never shown — the cancel path toasts instead) |
| `unknown` | everything else | "DOWNI's engine couldn't fetch this stream. The exact reason is under View details." |

**Rules that come with the table:**
- `unknown` **never blames the video**. No "private", no "restricted", no "may be".
- The platform is named when it is known ("YouTube is blocking this device") — the surface knows the
  platform from the job.
- `blocked` / `rate_limited` are **not retryable** → the sheet hides "Try again" for them.
- The quick-fix paragraph is **cause-driven**: the Wi-Fi advice only appears for `network`; a block
  gets "this is on the platform's side" instead; `format` gets "try another quality"; when there is
  no useful advice, the whole paragraph hides.

## 3. Work packages

### WP1 — The truth layer (honest errors). *The core of this release.*

**WP1.1 — `downloader.py`: stop swallowing causes**

1. `_download_split` (line 140-204): the bare `except Exception:` currently deletes the partials and
   returns `None`, which the caller turns into the **false** message *"This video has no separate
   1080p stream"*. Change: capture the exception, and on failure **raise** it classified. Return
   `None` **only** for the one case that is genuinely that message — the extraction succeeded but
   produced no separate video/audio files.
2. `_download_lanes` (line 445-449): the 1080p branch must raise the split's real reason, not the
   "no separate 1080p stream" sentence.
3. `download()` (line 556-571): when the "requested format is not available" fallback runs and the
   split fails, re-raise **the split's** error (the format error is stale by then).
4. `inspect` (line 365-369): the bare `except Exception: info = None` fabricates a card ("YouTube
   Video", 0:00, no thumbnail) that the Java layer then rejects as *"That link isn't a video."* —
   a false cause for a good link. Change: raise the real reason classified. (The TikTok fast path
   keeps its current silent fallback.)
5. New helper `_classify(raw) -> code` and `_engine_error(raw) -> str`: the raised message becomes
   `"[cause:blocked] ERROR: [youtube] …"`. The prefix travels across the Chaquopy bridge as plain
   text, so **no new Java/Python plumbing is needed**.

**WP1.2 — Java: transport, don't rewrite**

6. `DowniEnginePlugin.friendlyError()` (line 1073-1087) and `DowniDownloadService.friendlyError()`
   (line 1004-1018) are **duplicated** and both rewrite the text. Replace with one
   `parseEngineError(raw)` → `{cause, raw}`: strip the `[cause:x]` prefix, keep the raw text
   verbatim. The raw text is what reaches the JS.
7. `runJob`'s catch (plugin line ~108-110): put `cause` **and** the raw text in the progress event
   and in the rejection message, so the sheet can classify without re-parsing prose.
8. `extract` (plugin line ~689-697): the guard *"That link isn't a video."* is only correct when the
   extraction genuinely yielded nothing. When the engine raised, reject with **its** reason.
9. The headless (DowniDrop) path: the notification's short text becomes a 9-line cause→phrase map
   (native text, no prose-rich sentences). `dropLastError` keeps `{cause, raw, friendly}` so the app
   can show the same truth in the failed card.
10. The plugin's `friendlyError` currently returns `"Download failed: " + detail` — that prefix is
    fine, but the **cause must survive it** ✓.

**WP1.3 — `www/index.html`: say what happened**

11. `onDownloadError(errStr, cause)` (line 1299): one mapper, cause → {title, message, quickfix,
    retryable}. The four keyword buckets are deleted (they are the double-mapper's wreckage).
12. `showErrorModal(title, message, rawDetail)` → takes the cause; the message comes from the
    mapper; the raw text stays under "View details" and "Copy details" ✓ (unchanged behaviour).
13. The static "Quick fixes" paragraph (line ~478-486) becomes **populated per cause** and hides
    when there is nothing useful to say.
14. The "Try again" button hides for `blocked` / `rate_limited`.
15. The one-tap fallback chain (line 1181-1188): **only** fall back to the next quality when the
    cause is `format`. For `blocked` / `network` / `rate_limited`, stop immediately — today it burns
    one attempt per quality on a failure that cannot be fixed by a different quality.

**WP1 gates:** `_jscheck.py`, `node --check`, `_tabcheck.js` (source **and** synced copy),
`npx cap sync android` before the build, and a new **pure-Python unit test for `_classify`**
(READ_THIS §8.7: a test ships **with** the change it covers ✓).

### WP2 — The fixes that actually stop the failures

**WP2.1 — the yt-dlp pin** (`android/app/build.gradle:13`)

- Today: `install("yt-dlp==2026.8.19")` — the newest **stable**, unchanged since v2.1.0 (2026-09-19).
  Five **nightly** builds have shipped since (2026.8.29, 8.30, 9.16, 9.27, **10.10**); nightlies are
  where yt-dlp ships the fixes for a platform that broke.
- The change is one line. The **consequence** is not: the build needs PyPI access once (the Chaquopy
  pip step), and the new engine must pass the whole §5 matrix.
- **Not to be done** (READ_THIS §2, owner-ruled): re-introducing the `player_client` cascade or the
  forced Android Mobile UA — those caused exactly these 403s and the Instagram breakage. The engine
  deliberately has zero `extractor_args` / `player_client` / `impersonate` / `cookies` ✓.

**WP2.2 — the save-path duplication** (a known, separate defect)

The headless path writes to the gallery root, the inspector path to the legacy `Movies/DOWNI/`, so
the same title lands twice. It is a *different class* of change (it changes where files land) and it
must **not** ride with the truth-layer release. Its own package, its own device pass.

### WP3 — Diagnostics that can see the truth

16. `diagnose()` (line 629-698): the YouTube probe resolves a format but never fetches a byte
    (`skip_download=True`), so a media-side block stays invisible and Settings says **HEALTHY**
    while every grab fails. Change: pull a **64 KB range** of the resolved media URL
    (`download_ranges`) into a temp file, then delete it. "HEALTHY" must mean *bytes flow*.
17. Add an **engine freshness** check: the engine reports its bundled `yt_dlp_version` (it already
    does — `engine_info()`), and a `KNOWN_GOOD` constant in the engine lets the app say
    "Your engine is behind the version this release ships — update DOWNI" without any network call.
18. The Settings engine card renders the cause-aware verdict + the freshness line.

### WP4 — Verification

| Gate | What it proves |
|---|---|
| `_jscheck.py` / `node --check` / `_tabcheck.js` | the JS and the tab contract are intact |
| the new `_classify` unit test | every cause code maps from a representative raw string |
| **the §5 matrix** (3 platforms × 3 entry points, clean build, file size + format + no crash) | **mandatory for WP2.1** (the yt-dlp bump) |
| the **honest-error sheet** (one forced failure per cause) | the sheet never blames the video and never names an unverified cause |
| the device matrix rows in `DEVICE_TEST.md` §6 | the record |

Forced-failure cases for the error sheet: a private video (private), a deliberately bad link (link),
a dead host (network), airplane mode (network), and — for `blocked` — whatever the device produces
naturally, or a locally injected raw error string (the classifier is pure, so it can be driven from
a test string without breaking a real site).

### WP5 — Release

- Version: **3.7.0** if WP2.1 ships (the engine changed), **3.6.2** if it doesn't.
- `CHANGELOG.md` — the truth layer gets its own section; the release notes must say the error
  messages changed and that the engine version changed (claims discipline, READ_THIS §9).
- `README.md` — one line: the app now names the real reason instead of guessing.
- `DEVICE_TEST.md` §6 — the new matrix.
- The tag + `gh release create` (asset `DOWNI-v<ver>.apk`) **only on the owner's go**.

## 4. The order, and where I stop

1. **WP1** (truth layer) → build → a focused device pass (the 1080p lane once, one forced failure
   per cause). No §5 matrix needed: the download **outcomes** don't change, only the reported
   reason ✓.
2. **WP3** (diagnostics) → the diagnostics pass on the device (the media-real probe + freshness).
3. **WP2.1** (the engine bump) → **the full §5 matrix** → if any platform regresses, **revert the pin
   and keep WP1 + WP3** — the truth layer is valuable with or without the bump ✓.
4. **WP2.2** (the save paths) → its own release.

## 5. The decisions this plan needs from the owner

1. **The yt-dlp channel**: stay on the newest *stable* (`2026.8.19`) or move to the newest *nightly*
   (`2026.10.10.233141.dev0`)? My recommendation: **try the nightly on the device, matrix it, ship
   it only if the matrix is green** — it is the only thing that can beat YouTube blocking us, and the
   truth layer makes a failure survivable either way.
2. **"Try again" on a block**: hide it (my recommendation — retrying cannot help) or keep it?
3. **Naming the platform**: "YouTube is blocking this device" (my recommendation — specific beats
   vague) or stay platform-neutral?
4. **The version**: 3.7.0 (with the engine bump) or 3.6.2 (truth layer only)?
5. **The save-path fix**: in this release, or a separate one? (My recommendation: separate.)
