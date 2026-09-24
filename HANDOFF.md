# DOWNI — Handoff Brief for a New Chat

_Last updated: 2026-09-22 (after v3.0.2 / web v2.1.1). Read this first in a fresh session._

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
| Current version | **v3.1.0** (versionCode 45), "Polish Release": DowniDrop 2.0 instant background grabs + Vault blink fixed at the root |
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

---

## 3. Architecture in brief (Android)

- **Capacitor 6** (WebView `https://localhost`, assets bundled from `www/`) + **Chaquopy 3.11** (Python 3.11 + yt-dlp, on-device).
- Native bridge: **`DowniEnginePlugin`** (all `@PluginMethod`s; JS calls `Capacitor.Plugins.DowniEngine`).
- Notifications/foreground: **`DowniDownloadService`** (channels `downi_progress` LOW, `downi_alerts` DEFAULT; per-job ids).
- **True 1080p**: `downloader.py::_download_split` (H.264 video-only + M4A) → **`Mp4Merger`** (MediaMuxer, PTS-interleaved, 8 MB buffer; VP9 needs API 29+, AV1 API 34+).
- **Vault**: MediaStore query (needs runtime media permission, asked on first Vault open) + SAF custom-folder files via `listCustomFiles`.
- **Save paths**: MediaStore `Movies/` `Music/` (or chosen SAF folder) — the custom folder is honored by the in-app path and DowniDrop.
- **Share intents (DowniDrop 2.0, v3.1.0)**: `ACTION_SEND` + `ACTION_PROCESS_TEXT` → **DropActivity** (invisible, translucent, noHistory). Instant mode (default): toast + `DowniDownloadService.startShared()` — the service **self-starts Python** (`Python.isStarted()` → `Python.start()`, v2.6.4 pattern; never assume MainActivity warm-up) and saves via the ported gallery path (Movies/DOWNI + Music/DOWNI or custom SAF folder). "Ask quality" mode: forwards to MainActivity → Inspector (`getSharedUrl` consumes the intent; warm start via `onShareReceived`). Toggle + per-platform quality memory live in localStorage, mirrored to `downi_settings` prefs via `syncDropSettings`.
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

**P1 — none.** (All four candidates are closed by locked owner rulings: Pause/resume + TikTok photo posts = *permanently out of scope*; web items = *frozen 2026-09-24*; Play Store track = *rejected permanently*. Nothing sits in deferred.)
- [ ] ~~Pause + resume (needs `.part` support — deep change to the proven core; matrix required).~~ **Out of scope (locked).**
- [ ] ~~TikTok photo-post saving (needs a multi-file save contract).~~ **Out of scope (locked).**
- [ ] ~~Web: direct-to-CDN downloads where CORS allows (skip the proxy).~~ **Web frozen 2026-09-24.**
- [ ] ~~Play Store track: AAB build, targetSdk 35+, ProGuard rules, listing assets.~~ **Rejected permanently.**

**P2 — candidate polish (not yet agreed with the user)**
- [x] ~~Scope the Vault to DOWNI's own folder + tracked items~~ — **DONE in v3.0.4** (user-confirmed: Vault must show only DOWNI downloads).
- [ ] ~~Web: offline queue messaging, analytics (none today), per-release OG card refresh.~~ **Web frozen 2026-09-24.**
- [ ] APK size budget: release ≈ 38 MB — inspect what the Chaquopy/yt-dlp payload could shave. (Only if the owner asks; no safe-shelf release without measured results.)
- [ ] Unit tests for `Mp4Merger` interleave + `_safe_name` / `extractUrl` (CI covers only the smoke script today). **Ship only with the change they cover** — never as their own release (READ_THIS_BEFORE_UPGRADE §8).

**Owner decisions (locked 2026-09-22)**
1. Play Store track — **rejected permanently**. Sideload-only via GitHub Releases.
2. Analytics — **rejected permanently**. Zero tracking is a core value ("we provide value, we don't take their data").
3. Vault scoping — **done in v3.0.4**: only DOWNI downloads, never the whole gallery.
4. Brand/identity — **frozen**. No rebrands, no redesign pivots; polish the current experience only.
5. Priority order — APK Vault perfection first (v3.0.4). Web app shipped 2026-09-24 and is **frozen — out of the active plan** (owner ruling 2026-09-24).
6. DowniDrop (locked 2026-09-23) — **instant background grab is the default again** (v2.6.4 pattern restored); "Ask quality" is opt-in via Settings. Vault Phase 2 polish (shimmer, content-visibility, press-scale) **parked for v3.2** — never ship new paint-timing code in the same release as the paint-timing bug fix.

---

## 8. Verification recipes

- **Bundled UI freshness**: compare `www/index.html` size to `assets/public/index.html` inside the built APK zip — equal = good.
- **Engine smoke (CI tier)**: `python ci/smoke_test.py` (TikTok fast-path must pass; YouTube/Instagram are datacenter-IP sensitive → warnings only).
- **Live checks**: GitHub `releases/latest` (asset name/size).

