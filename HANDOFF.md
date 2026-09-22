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

## 1. Projects at a glance

| | Android (flagship) | Web (companion) |
|---|---|---|
| Local path | `C:\Users\Manso\Documents\Codex\2026-09-06\bro-i-have-started-working-on\mobile-app` | `C:\Users\Manso\Documents\Codex\downi-web` |
| Remote | github.com/MansoorjAhmad/Downi | github.com/MansoorjAhmad/downi-web |
| Live channel | GitHub Releases → in-app updater | getdowni.vercel.app (auto-deploy on push) |
| Current version | **v3.0.3** (versionCode 43), signed APK `DOWNI-v3.0.3.apk` | **v2.1.1** (`WEB_VERSION` in index.html) |
| Tests | `.github/workflows/test.yml` (engine smoke + debug build, every push) | none yet (manual) |
| Release trigger | `git push origin vX.Y.Z` → `.github/workflows/release.yml` builds + publishes | `git push origin main` → Vercel |

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
- **Share intents**: `ACTION_SEND` + `ACTION_PROCESS_TEXT` → MainActivity → Inspector (`getSharedUrl` consumes the intent; warm start via `onShareReceived`).
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

**Web:**
1. Bump `WEB_VERSION` in `index.html` **and** the health string in `api/info.py`.
2. `git commit && git push origin main` → Vercel deploys (~1 min).
3. Optional rollback anchor: `git tag -a web-vX.Y.Z` + push tags.
4. Verify live: `GET /api/info?health=1` reports the new version.

---

## 6. Gotchas learned the hard way

1. **Version lives in 3 places** — `android/app/build.gradle` (`versionCode` + `versionName`), `www/index.html` (`installedVersion` / `installedVersionCode` fallbacks), and `DowniEnginePlugin.getAppInfo()` catch-fallback. Miss one and the updater UI lies.
2. **Stale bundled UI** — never edit `android/app/src/main/assets/public/`; it is generated by `cap sync`. The preBuild guard catches mistakes.
3. **`__pycache__` in `src/main/python`** — Chaquopy packages from there; stray `.pyc` files are dead weight. Delete after any local `py_compile`.
4. **Cancel semantics** — `_scaled_hook` raises on cancel → `_download_split` returns `None` → the Java layer stays silent when `job.cancelled` is set. Preserve that.
5. **TikTok** rides the external `tikwm.com` API (HD, no watermark) — third-party dependency, keep the yt-dlp fallback.
6. **YouTube on web** is intentionally refused (502 + honest message) because server subnets are bot-checked. Do not "fix" it with spoofing; the chip reads "YouTube → app".
7. **Web Vault** stores history, not files — "tap to grab again" is the honest affordance; true playback is not possible there.
8. **Quoting/escape hazards** cost real time in shell one-liners and edit payloads — rely on `node --check` / `py_compile` rather than eyeballing.

---

## 7. Work queue (prioritized)

**P0 — the only release gate left**
- [ ] Run `DEVICE_TEST.md` on the phone against **v3.0.2**: Vault shows downloads (permission asked once), text-selection share opens the Inspector, 1080p merge, playlist Grab-all. Report failures → v3.0.3.

**P1 — deferred, already agreed (see `ROADMAP.md`)**
- [ ] Pause + resume (needs `.part` support — deep change to the proven core; matrix required).
- [ ] TikTok photo-post saving (needs a multi-file save contract).
- [ ] Web: direct-to-CDN downloads where CORS allows (skip the proxy).
- [ ] Play Store track: AAB build, targetSdk 35+, ProGuard rules, listing assets. (`/privacy` already exists on web for the listing requirement.)

**P2 — candidate polish (not yet agreed with the user)**
- [ ] Scope the Vault to DOWNI's own folder + tracked items (today the query lists *all* device media — a privacy smell).
- [ ] Web: offline queue messaging, analytics (none today), per-release OG card refresh.
- [ ] APK size budget: release ≈ 39.8 MB — inspect what the Chaquopy/yt-dlp payload could shave.
- [ ] Unit tests for `Mp4Merger` interleave + `_safe_name` / `extractUrl` (CI covers only the smoke script today).

**Open questions for the user**
1. Play Store track — pursue (AAB + targetSdk bump + listing) or stay sideload-only?
2. Any analytics at all? (Zero tracking today — a selling point, but no visibility.)
3. Vault scoping (P2) — restrict to DOWNI downloads, or keep showing everything?

---

## 8. Verification recipes

- **Bundled UI freshness**: compare `www/index.html` size to `assets/public/index.html` inside the built APK zip — equal = good.
- **Engine smoke (CI tier)**: `python ci/smoke_test.py` (TikTok fast-path must pass; YouTube/Instagram are datacenter-IP sensitive → warnings only).
- **Web allow-list guard**: `python -c "import sys;sys.path.insert(0,'api');import info;print(info._url_allowed('https://evil.com/x'))"` → `False`; platform URLs and public HTTPS media links → `True`.
- **Live checks**: `https://getdowni.vercel.app/api/info?health=1` (version), `/robots.txt`, `/sitemap.xml`, GitHub `releases/latest` (asset name/size).

