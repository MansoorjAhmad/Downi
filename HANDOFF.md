# DOWNI — Handoff Brief for a New Chat

_Updated 2026-10-20 (v3.5.0, the scope reduction). Read this first in a fresh session._

---

## 0. Read this first — v3.5.0 changed what the app IS

**DOWNI is a downloader for YouTube, TikTok and Instagram. That is the whole product.** The Vault,
the Fetcher and the Core were removed on 2026-10-20 after users said the app had grown too much
around the thing it is actually for.

**If you are about to add a feature, the test is: does it make DOWNI *download better*?** If it
does not, it does not belong in the app. That is the precedent v3.5.0 set.

**Why 3.5.0 and not 4.0.0.** The extraction layer did not change, so the version name follows the
3.x line: less in the app, the same engine. Do not renumber it upward to make the removal look
bigger than it was.

**Still owed before v3.5.0 can ship:**

1. **A device run on the vivo V2058.** The build compiles, the JS gate passes and the debug APK is
   produced, but nothing has run on a real phone. `READ_THIS_BEFORE_UPGRADE.md` §6 truth
   re-checks are owed: the counters, the Queue badge, the grab ledger and the notifications all
   touch what was removed.
2. **`DEVICE_TEST.md` rows** for the removals, in the same commit as the change.
3. **Tag and publish.** `git tag v3.5.0 && git push origin v3.5.0` → CI builds the signed APK.
   The tag's commit message becomes the release notes, so it must read like the release, not
   like "wip".

**The removed work is not lost.** `feature/shield` (S2–S5) and `feature/shield-s6` (the
uncommitted S6 verification layer) hold the Shield feature. `git checkout feature/shield-s6`
brings it back. The release-key debug-signing patch that lets a debug build install over the
published one lives *outside* the repo, next to it:
`..\downi-dev-signing.patch`.

**What did NOT change:** the engine. `yt-dlp 2026.8.19`, the format cascade, the merge, the
3-parallel queue, the notifications, the updater, DowniDrop, the clipboard banner and the
Inspector are exactly as they were in 3.3.3. No extraction-layer change was made, so the full
platform × entry-point matrix in §5 was not required — only the §6 truth re-checks.

---

---

## 0. Kickoff checklist for a new session

1. Read, in this order: **`READ_THIS_BEFORE_UPGRADE.md`** (the Law), **`ROADMAP.md`**, **`DEVICE_TEST.md`**, **`IDENTITY_AUDIT.md`**, **`CHANGELOG.md`**.
2. Verify state:
   - `C:\Users\Manso\mingit\cmd\git.exe -C <repo> status --short` → both repos must be clean.
   - `git -C <repo> fetch origin && git -C <repo> rev-list --left-right --count origin/main...HEAD` → `0 0`.
3. Never guess a version: it lives in **three** places (see §6.1).

---

## 1. Project at a glance

| | Android (flagship) |
|---|---|
| Local path | `C:\Users\Manso\Documents\Codex\2026-09-06\bro-i-have-started-working-on\mobile-app` |
| Remote | github.com/MansoorjAhmad/Downi |
| Live channel | GitHub Releases → in-app updater |
| Current version | **v3.5.0** (versionCode 52), "the lean release": the Vault, the Fetcher and the Core are gone; DOWNI is a downloader and nothing else. **In the tree, not yet device-verified, not tagged.** |
| Previous | v3.3.3 (versionCode 51) — the last published release. |
| Tests | `.github/workflows/test.yml` (engine smoke + debug build, every push) |
| Release trigger | `git push origin vX.Y.Z` → `.github/workflows/release.yml` builds + publishes |

**Web companion: shipped 2026-09-24, frozen, out of the active plan** (owner ruling). Repo stays at
github.com/MansoorjAhmad/downi-web, live at getdowni.vercel.app (auto-deploy on push); local working
copy removed. No planned work — only revisit if the owner asks.

Old companion desktop app (`desktop-app/`, `dist/`, `work/`) is **abandoned** — ignore unless the user asks.

**Installed users: 3.** The user is fine with being un-hardened against abuse (no rate limiting by decision). Do not re-introduce session harvesting, spoofing, or relays in its name.

---

## 2. The Law (inviolable — full text in `READ_THIS_BEFORE_UPGRADE.md`)

1. **No yt-dlp client spoofing** (`player_client`, `extractor_args`), **no forced User-Agent**, **no SSL monkey-patching**.
2. **Instagram session/cookie harvesting is permanently banned** — public endpoints only.
3. **No Cloud Boost / remote relay** for on-device builds. If ever revisited: health-check + graceful local fallback.
4. **Any extraction-layer change requires the full device matrix first**: Instagram + YouTube + TikTok × Vortex / Inspector / DowniDrop on a real phone (`DEVICE_TEST.md`).
5. **Never change `applicationId` (`com.omnidownloader.app`) or the signing key** — breaks in-place updates for installed users.
6. yt-dlp pin: bump monthly or on-break, each bump gated by the matrix. Currently `2026.8.19`.
7. **§7-bis (added 2026-10-20): a version may also be earned by an owner-ruled scope reduction** — a deliberate removal of a shipped feature. The removal must be named honestly, §6 truth re-checks are still owed on a device, §5 is only owed if the extraction layer changed, and **nothing removed comes back by stealth**.

---

## 3. Architecture in brief (Android)

- **Capacitor 6** (WebView `https://localhost`, assets bundled from `www/`) + **Chaquopy 3.11** (Python 3.11 + yt-dlp, on-device).
- Native bridge: **`DowniEnginePlugin`** (all `@PluginMethod`s; JS calls `Capacitor.Plugins.DowniEngine`).
- Notifications/foreground: **`DowniDownloadService`** (channels `downi_progress` LOW, `downi_alerts` DEFAULT; per-job ids).
- **True 1080p**: `downloader.py::_download_split` (H.264 video-only + M4A) → **`Mp4Merger`** (MediaMuxer, PTS-interleaved, 8 MB buffer; VP9 needs API 29+, AV1 API 34+).
- **Save path**: MediaStore insert straight into `Movies/` (or `Music/` for audio) — no DOWNI subfolder, because the subfolder only existed for the Vault to scope to. A custom SAF folder is still optional.
- **Save paths**: MediaStore `Movies/` `Music/` (or chosen SAF folder) — the custom folder is honored by the in-app path and DowniDrop.
- **Share intents (DowniDrop)**: `ACTION_SEND` + `ACTION_PROCESS_TEXT` → **DropActivity** (invisible, translucent, noHistory). Instant mode (default): toast + `DowniDownloadService.startShared()` — the service **self-starts Python** (`Python.isStarted()` → `Python.start()`, v2.6.4 pattern; never assume MainActivity warm-up) and saves via the ported gallery path (`Movies/` + `Music/` or a custom SAF folder). "Ask quality" mode: forwards to MainActivity → Inspector (`getSharedUrl` consumes the intent; warm start via `onShareReceived`). Per-platform quality memory lives in localStorage, mirrored to `downi_settings` prefs via `syncDropSettings`.
- **Updater**: GitHub `releases/latest` → `.apk` asset → download w/ progress → package installer (pre-checks "unknown apps").
- **Build guard**: `android/app/build.gradle` `preBuild` fails the build if `assets/public/index.html` ≠ `www/index.html`.

---

## 4. Environment & validated commands (this PC)

```
git    C:\Users\Manso\mingit\cmd\git.exe        (NOT in PATH)
gh     C:\Users\Manso\gh\gh.exe                 (authenticated as MansoorjAhmad, repo+workflow scopes)
node   C:\Program Files\nodejs\                 (npx.cmd 11.x; npm.cmd present)
python mobile-app\tools\python311\python.exe    (portable 3.11)
jdk    mobile-app\tools\jdk\jdk-21.0.12.1+1
sdk    mobile-app\android-sdk                   (referenced by android\local.properties)
```

**Sync + build (mandatory sequence):**
```powershell
cd <mobile-app>
& 'C:\Program Files\nodejs\npx.cmd' cap sync android          # MUST run before any build
cd android
$env:JAVA_HOME='<mobile-app>\tools\jdk\jdk-21.0.12.1+1'
.\gradlew.bat assembleDebug --console=plain                    # ~30s; output in android\app\build\outputs\apk\debug\
```

**Syntax gates used before every commit:**
- Python: `tools\python311\python.exe -m py_compile <file>` (then delete the `__pycache__` it creates).
- JS: extract each inline `<script>` block of `index.html` and run `node --check` on it.

**PowerShell caveat:** git writes warnings to stderr and PowerShell 5.1 treats that as a failure — run git through `cmd /c "…"` when scripted.

---

## 5. Release procedures

**Android (signed APK + updater feed):**
1. Bump **all three** version spots (§6.1) + add README/CHANGELOG entries.
2. `npx cap sync android`, build, run the JS/Python gates.
3. `git commit` (the commit message becomes the GitHub release notes), `git push origin main`.
4. `git tag -a vX.Y.Z -m "…"` → `git push origin vX.Y.Z` → CI publishes the signed APK.
5. Confirm: `gh run list --repo MansoorjAhmad/Downi` → both workflows green; `gh release view vX.Y.Z`.

---

## 6. Gotchas learned the hard way

1. **Version lives in 3 places** — `android/app/build.gradle` (`versionCode` + `versionName`), `www/index.html` (`installedVersion` / `installedVersionCode` fallbacks), and `DowniEnginePlugin.getAppInfo()` catch-fallback. Miss one and the updater UI lies.
2. **Stale bundled UI** — never edit `android/app/src/main/assets/public/`; it is generated by `cap sync`. The preBuild guard catches mistakes.
3. **`__pycache__` in `src/main/python`** — Chaquopy packages from there; stray `.pyc` files are dead weight. Delete after any local `py_compile`.
4. **Cancel semantics** — `_scaled_hook` raises on cancel → `_download_split` returns `None` → the Java layer stays silent when `job.cancelled` is set. Preserve that.
5. **TikTok** rides the external `tikwm.com` API (HD, no watermark) — third-party dependency, keep the yt-dlp fallback.
6. **YouTube on web** is intentionally refused (502 + honest message) because server subnets are bot-checked. Do not "fix" it with spoofing; the chip reads "YouTube → app".
7. **Web Vault** stores history, not files — "tap to grab again" is the honest affordance; true playback is not possible there.
9. **ADB over Wi-Fi was unreliable on the vivo V2058** — prefer the USB cable for device testing. Also: run `adb devices` alone first, split chained adb commands, never `adb exec-out` for binaries (use `pull`), and on PowerShell 5.1 wrap native commands in `cmd /c "... 2>&1"` (stderr lines otherwise surface as fake errors, and parentheses inside double-quoted strings get evaluated). The image reader tool can't read PNGs from `test_out\` — convert with `test_out\tojpg.ps1` (System.Drawing) first.

8. **Quoting/escape hazards** cost real time in shell one-liners and edit payloads — rely on `node --check` / `py_compile` rather than eyeballing.
10. **Binary capture hygiene (session-killer, hit 2026-09-24).** PowerShell 5.1 `>` and `Out-File` rewrite native stdout as **UTF-16 text**: `adb exec-out screencap -p > test_out\shot1.png` produced a 737,666-byte file starting `FF FE` (not `89 50 4E 47`) — and it still passed a `Length -gt 10000` check because UTF-16 roughly doubles the size. Attaching that file to the model killed the session twice (`Upstream error from DeepInfra: Failed to load image: cannot identify image file <_io.BytesIO object>`, exit code 1) and **every later turn in that session failed too** — the bad image stays in context, so the only recovery is a fresh session. Rule: capture with `tools\safe_shot.ps1` (device-side `screencap` → `adb pull` → magic-byte verify, optional `-Jpg`), and run `tools\safe_shot.ps1 -Check <file>` on any image before `read_files`. Files killed this way: `test_out\shot1.png`, `11_before_cancel.png`, `inspector_card.png`.
11. **Free-model daily cap ends sessions.** `cline-free/deepseek-v4.1-flash` throws `{"error":{"code":"INFERENCE_CAP_ERROR","message":"Error 429: Daily free limit reached … Try again in Xh Ym"}}` mid-run and the session dies (exit 1). Device-matrix sessions (many tool calls + images) burn it fastest — start a fresh session per work block and keep each verification path short.

---

## 7. Work queue (prioritized)

**P0 — DONE (v3.1.0 shipped 2026-09-23)**
- [x] v3.1.0 (versionCode 45) "The Polish Release": DowniDrop 2.0 (instant background grabs restored, v2.6.4 invisible-grabber pattern + self-starting engine + Settings toggle) and Vault Phase 1 keyed-DOM fix. v3.0.4 (commit 8347585) folded in. Plan: `V3.1_PLAN.md`. Device matrix incl. cold-start share run on the vivo V2058.

**P0 — DONE (v3.0.3 shipped 2026-09-22)**
- [x] v3.0.3 (versionCode 43) released: all 8 fixes device-verified by the owner on the vivo V2058; tag `v3.0.3` pushed, CI green, signed `DOWNI-v3.0.3.apk` (37.9 MB) live on GitHub Releases → in-app updater feed active.
- Remaining DEVICE_TEST.md cells (playlist Grab-all, custom save folder, cancel/app-kill regression) were covered by the owner's blanket "all the things are working" — formally re-check only if a bug report comes in.

**P0 — v3.5.0 "the lean release" (in the tree, 2026-10-20 — NOT yet device-verified, NOT tagged)**

The scope reduction. The Vault, the Fetcher and the Core are gone; Shield is cancelled; the home
screen is one action; Settings is four cards; the app asks for no accessibility and no media
permission. The engine is untouched. Full detail and the measurements: `CHANGELOG.md` v3.5.0.

- [x] Vault, Fetcher, Core and the in-app player removed — 30 native classes, 21 test classes,
      20 resources/assets, 67 JS `function` definitions. 88 files changed, 71 of them deletions,
      **16,199 lines deleted** and about 600 added — the added count includes this checklist, so
      quote the deletions and the file counts. (An earlier draft said "24 native, 63 test
      classes, 2,150 lines" — guesses made mid-cut, never re-counted. The numbers above are from
      `git diff --shortstat` + a grep of `function name(` on v3.3.3 vs now.)
- [x] Permissions reduced: accessibility, media read and `WRITE_SECURE_SETTINGS` all gone.
- [x] Save path moved from `Movies/DOWNI/` to `Movies/` (the subfolder only existed for the Vault).
- [x] Home screen rebuilt around one action; Settings 7 cards → 4.
- [x] Version bumped in all three places → 3.5.0 / versionCode 52.
- [x] **The Core's leftovers removed** (Lottie runtime, `assets/core/`, the orb art, the Vault's
      loopback cleartext exception) — debug APK 46.7 MB → **42.1 MB**.
- [x] **A defect the cut introduced, caught before tagging**: `switchTab()` lost the two loops
      that hide the view you leave and un-light its nav item, so tabs stacked. Found by rendering
      the UI in a browser; fixed; **`tools/_tabcheck.js`** now fails the build on it and CI runs
      it. The tab-switch logic is the first thing a "delete what names the removed feature" patch
      breaks without breaking the build — check it again after any future cut.
- [ ] **Device run on the vivo V2058** — the §6 truth re-checks. Removing a tab touches the
      counters, the badge, the ledger and the notifications.
- [ ] `DEVICE_TEST.md` §4 rows filled in (4-1 … 4-16).
- [ ] Tag `v3.5.0`, push, CI publishes. The tag's commit message IS the release notes.

**P1 — the download path itself**

The only thing that earns a place in the app now is making the download better.

- [ ] Make the one-tap path as good as it can be: share → DOWNI, and the clipboard banner.
- [ ] The grab-from-inside-the-platform flow is **gone** with the Fetcher. If it ever comes back
      it must clear a higher bar than before: the accessibility permission is the reason it was
      removed, and the app is now better for its absence.
- [ ] APK size: release ≈ 38 MB. The Chaquopy/yt-dlp payload is the bulk of it.

**P2 — candidate polish (not yet agreed with the user)**
- [ ] Unit tests for `Mp4Merger` interleave + `_safe_name` / `extractUrl` (CI covers only the
      smoke script today). **Ship only with the change they cover** — never as their own release
      (READ_THIS_BEFORE_UPGRADE §8).
- [ ] The JVM suite is nearly empty now that the Fetcher's 21 test classes are gone — 1 test
      remains. Anything that earns its place deserves a test that runs on the JVM.

**Permanently out (locked rulings — do not re-open)**
- [ ] ~~Pause + resume (needs `.part` support — deep change to the proven core; matrix required).~~ **Out of scope (locked).**
- [ ] ~~TikTok photo-post saving (needs a multi-file save contract).~~ **Out of scope (locked).**
- [ ] ~~Web: direct-to-CDN downloads where CORS allows (skip the proxy).~~ **Web frozen 2026-09-24.**
- [ ] ~~Play Store track: AAB build, targetSdk 35+, ProGuard rules, listing assets.~~ **Rejected permanently.**
- [ ] ~~The Vault, the Fetcher, the Core.~~ **Removed in v3.5.0 (2026-10-20). Nothing removed
      comes back by stealth — see READ_THIS_BEFORE_UPGRADE §7-bis.**
- [ ] ~~Shield.~~ **Cancelled before it shipped (2026-10-20). The work is preserved on the
      `feature/shield` and `feature/shield-s6` branches.**

**Owner decisions**
1. Play Store track — **rejected permanently**. Sideload-only via GitHub Releases. (2026-09-22)
2. Analytics — **rejected permanently**. Zero tracking is a core value ("we provide value, we don't take their data"). (2026-09-22)
3. ~~Vault scoping~~ — **moot**: the Vault was removed in v3.5.0. (2026-09-22 → 2026-10-20)
4. Brand/identity — **frozen**. No rebrands. (2026-09-22)
5. Priority order — **DOWNI is a downloader.** Everything else was removed. A feature that does
   not make the *download* better does not belong in the app. (2026-10-20)
6. DowniDrop — **instant background grab is the default**, "Ask quality" is opt-in via Settings.
   This is now one of only two ways to grab, so it must stay solid. (2026-09-23)
7. **The scope reduction (2026-10-20)** — the Vault, the Fetcher and the Core are removed; Shield
   is cancelled. The app asks for no accessibility and no media permission. **Nothing removed
   comes back by stealth** (READ_THIS_BEFORE_UPGRADE §7-bis).
8. **The home screen is one action** (2026-10-20) — paste a link, or share one to DOWNI. The
   tap-the-orb hero is gone; the clipboard banner and DowniDrop are the one-tap paths.
9. **Settings is four cards** (2026-10-20) — App, Engine, Appearance, Behavior & storage. If a
   setting does not change how DOWNI downloads, it does not belong here.

---

## 8. Verification recipes

- **Bundled UI freshness**: compare `www/index.html` size to `assets/public/index.html` inside the built APK zip — equal = good.
- **Engine smoke (CI tier)**: `python ci/smoke_test.py` (TikTok fast-path must pass; YouTube/Instagram are datacenter-IP sensitive → warnings only).
- **Live checks**: GitHub `releases/latest` (asset name/size).

