# 🧭 MISSION A→Z — The Complete Story, Goal & Playbook

> **Read this first in any fresh chat.** This file is the single source of truth for where we are,
> how we got here, and exactly how we move forward. Written 2026-09-23, immediately after the
> deliberate rollback of main to **v3.1.0**. Nothing here is aspirational — it reflects the
> actual git state.

---

## A — Who We Are & What This Is

- **Project:** DOWNI (formerly "Omni Downloader") — an Android video downloader app. (The web companion shipped 2026-09-24 and is **frozen — out of the active plan**; repo stays at `github.com/MansoorjAhmad/downi-web`.)
- **Repo:** `github.com/MansoorjAhmad/Downi`, branch `main`.
- **Owner/tester:** Mansoor — tests every build himself on a **vivo V2058** (physical device, USB cable; adb-over-WiFi is unreliable on it).
- **Core values (locked owner decisions):**
  1. **Sideload-only** via GitHub Releases — Play Store track rejected permanently.
  2. **Zero analytics/tracking** — "we provide value, we don't take their data".
  3. **Brand/identity frozen** — polish the current experience, no rebrands or redesign pivots.
  4. **Vault shows only DOWNI downloads** — never the whole gallery.
  5. **DowniDrop = instant background grab by default** (v2.6.4 pattern); "Ask quality" is opt-in via Settings.

## B — The Goal

**Make the mobile app rock-solid.** The web app shipped 2026-09-24 (full APK parity + native iOS
downloads) and is **frozen — not part of this plan** (owner ruling).

Not "add features" — *solid*. The owner's benchmark is the old v2.6.x DowniDrop: boring,
deterministic, never surprised him. The current v3.x machinery is more powerful (1080p merge,
quality memory) but has repeatedly broken trust on-device. We rebuild that trust one verified
fix at a time.

## C — The Story So Far (timeline)

| Version | What happened |
|---|---|
| v1.3.6 | Original reliable core. |
| v2.5.4 / v2.6.x | The "very solid" era — simple single-path downloads. Then v2.6.x experiments (client spoofing, forced UA, SSL monkey-patch, custom streaming engine) **broke everything**. |
| v2.6.2 | **The Sacred Baseline** — restored the proven v1.3.6 core. Rules frozen in `READ_THIS_BEFORE_UPGRADE.md`. |
| v2.6.3–v2.6.7 | Stability fixes on top of the baseline. DowniDrop (instant background share-grab) was beloved here. |
| v3.0.0–v3.0.3 | Big rebuild: design system, Grab/Inspector/Queue, Vault 2.0, Player 2.0, true 1080p via split DASH + MediaMuxer merge, playlist batch. v3.0.2/3.0.3 hardened it. |
| v3.0.4 | Vault scoped to DOWNI-only folders + blink fixes. |
| **v3.1.0** (45) | "The Polish Release" — DowniDrop 2.0 (invisible DropActivity, self-starting engine, per-platform quality memory, rich notifications) + Vault keyed-DOM fix. **← CURRENT MAIN.** |
| v3.1.1–v3.1.4 | ⚠️ **ROLLED BACK.** Speed Release (segmented TikTok, task-riding fix, cancel fixes) + v3.1.4 tail fixes (B15 H.264-only selector, quality-miss fallback) were built, but too many things got mixed at once and device behavior felt fragile. Owner ordered a full reset to v3.1.0 to redo it cleanly. Preserved at branch **`backup/pre-rollback-v3.1.4`** (`a4c995c`) — reference only, never blind cherry-pick. |

## D — Current Exact State (2026-09-23)

- `main` (local + origin) = **`51701da` = tag `v3.1.0`** — versionCode **45**, versionName **"3.1.0"**.
- No v3.1.1–3.1.4 tags, no GitHub Release for them — nothing to clean up remotely.
- Backup branch `backup/pre-rollback-v3.1.4` exists locally **and** on origin.
- The v3.1.4 APK and build log were **deleted**. Any 3.1.4 install on the phone must be replaced
  with a clean v3.1.0 build before testing (device must always match main).
- Next version number: **v3.1.1** (versionCode **46**) — earned only through the test loop below.

## E — The Law (non-negotiable, from `READ_THIS_BEFORE_UPGRADE.md`)

The v2.6.2 yt-dlp baseline is sacred:

1. `ca_certs=certifi.where()` — **never** monkey-patch `ssl._create_unverified_context`.
2. Plain mp4 format cascade — **no** `extractor_args`/`player_client` spoofing, **no** forced Android/mobile User-Agent.
3. No Instagram session/cookie harvesting — banned permanently (ToS + account-ban risk).
4. No Cloud Boost relay without health-check + graceful local fallback.
5. **Any extraction-layer change requires the full device matrix before merge:**
   Instagram / YouTube / TikTok × Vortex / Inspect / DowniDrop — completes, file >0 KB, correct ext, no bridge crashes.
6. TikTok rides the third-party `tikwm.com` API (HD, no watermark) — keep the yt-dlp fallback path.
7. YouTube on the **web** app is intentionally refused (502, honest message) — server IPs get bot-checked. Never "fix" with spoofing.

## F — Architecture Map (how a share becomes a file)

```
Share from TikTok/YouTube/etc.
  → DropActivity (invisible grabber)
  → DowniDownloadService (Java): drop-mode, per-platform remembered quality,
    shared queue/executor, rich notifications + Cancel action
  → DowniEnginePlugin (Chaquopy bridge) → downloader.py (yt-dlp)
      ├─ combined path:  best[ext=mp4][vcodec!=none][acodec!=none] / ...
      └─ split path:     _download_lanes → _download_split (H.264 video-only + M4A)
                         → Mp4Merger.java (MediaMuxer, no ffmpeg)
  → MediaStore → Vault (Movies/DOWNI, Music/DOWNI)
```

Other entry points: **Vortex** (Fast DL button), **Inspect** (manual quality picker) — same engine.

## G — Why Old Was Solid & New Is Fragile (the diagnosis)

**v2.6.4/2.6.5 DowniDrop had exactly ONE path to success:**
`download(url, work, "best")` → combined progressive stream only → single file → gallery.
No probe, no quality memory, no lanes, no split, no merge. Nothing to misconfigure.

**v3.1.x has ~6 moving parts that must all agree:** remembered quality → lane selector →
combined-miss → split selector → codec check → MediaMuxer merge → quality-miss retry.
Each is a failure surface.

**Redo principle:** keep the v2.6.4 simplicity as the *spine* — one boring deterministic path
that always works — and add v3.x features only as layers that degrade gracefully back to the
spine, never as hard gates.

## H — Known Landmines (confirmed on-device last cycle; fixes exist in backup branch)

Watch for these during testing. When the owner says "that's the known one", port the fix
cleanly onto v3.1.0:

1. **B15 — 1080p merge death:** if a video has no H.264 (avc1) video-only stream, the bare
   `bestvideo[height<=H]` fallback picks VP9 → MediaMuxer dies with
   `Unsupported mime video/x-vnd.on2.vp9`. (Fix in backup: H.264-only selector, height as preference.)
2. **Quality memory as hard gate:** remembered lane (e.g. 1080p) missing for a video → DowniDrop
   aborted instead of falling back to best. (Fix in backup: retry with 'best', say so in notification.)
3. **Task-riding regression:** instant share yanks you out of the platform app into DOWNI.
   (Fix in backup: DropActivity singleTask removal, v2.6.4 pattern restored.)
4. **Cancel-from-notification flashes an error card** instead of cancelling silently.
5. **B8 — drop-history FIFO race:** believed already eliminated in code (DROP_HISTORY_LOCK);
   device-proof = rapid 5-share test.

## I — The Test Loop (our flow, locked by the owner)

```
Owner tests current main on vivo V2058
  → reports issues (one message each: what you did, what happened, screenshot if any)
  → I diagnose from code, fix ONE issue at a time (minimal diff, The Law untouched)
  → owner confirms each fix on device
  → when the batch is green: bump to v3.1.1 (46), build signed APK, owner installs & retests
  → tag + GitHub Release only after the full matrix passes
  → repeat (v3.1.2, v3.1.3…) until the app is fully solid
```

**Never again:** no batched mega-commits, no releasing untested bumps, no version-label-only
confusion, no reintroducing banned hacks, no shipping new paint-timing code in the same
release as a paint-timing fix.



## J — Environment & Procedures (copy-paste truths)

- Repo root: `C:\Users\Manso\Documents\Codex\2026-09-06\bro-i-have-started-working-on\mobile-app`
- **Version lives in 3 places** — bump ALL: `android/app/build.gradle` (versionCode+versionName),
  `www/index.html` (installedVersion/installedVersionCode fallbacks), `DowniEnginePlugin.getAppInfo()` catch-fallback.
- Build signed APK: `cd android && .\gradlew.bat assembleRelease` → `android\app\build\outputs\apk\release\app-release.apk` (release key already configured).
- Verify: `py_compile` (via `tools\python311\python.exe`, delete `__pycache__` after),
  `node --check` on extracted inline JS, `npx cap sync android` before builds.
- Never edit `android/app/src/main/assets/public/` — generated by `cap sync` (preBuild guard catches it).
- PowerShell 5.1 quirk: git/adb write progress to stderr → surfaces as fake errors;
  wrap in `cmd /c "... 2>&1"` when scripted. Verify real state with `git status`/`ls-remote`.
- Device: prefer USB cable on the vivo V2058; `adb devices` alone first; split chained adb commands.
- Release: commit (message = release notes) → push main → `git tag -a vX.Y.Z` → push tag →
  CI publishes signed APK → confirm `gh run list` + `gh release view`.

## K — What Comes After Solid (v3.2.0 "Power Release" — review-only until then)

**Owner ruling 2026-09-24 — the stale plan is deleted.** `V3.2_PLAN.md` existed only on
`backup/pre-rollback-v3.1.4`; it was removed there in commit `f04af40` (pushed, `a4c995c..f04af40`) and it
never existed on `main`. It assumed context that has since changed — if v3.2 planning ever restarts, it
starts fresh. Git history still contains it in `0fd4001` (normal; no history rewrite on a frozen branch).

Two items were pulled **forward into the v3.1.1 stabilization pass** (owner ruling, user demand):
1. **Light theme removed entirely** — dark/AMOLED only, **AMOLED as the default**; hard-delete the code,
   leave nothing dormant. This also retires defect F (light × AMOLED precedence bug becomes impossible).
2. **"Ask quality first" removed from DowniDrop** — ⚠️ **only after instant mode passes the full device
   matrix clean in this same pass.** If instant mode is still shaky afterwards, the toggle stays for now
   and we revisit later.

**Permanently out of scope** (owner ruling): Pause/Resume, TikTok photo posts. The web app is also
**frozen — out of the active plan** (owner ruling 2026-09-24): shipped complete, lives on GitHub/Vercel,
no planned work. Focus stays on the APK.

## L — How To Start a Fresh Chat (for the AI)

1. Read this file, then `READ_THIS_BEFORE_UPGRADE.md`, then `HANDOFF.md` (gotchas + work queue).
2. Run `git status -sb && git log --oneline -3` — confirm main matches §D (or the latest shipped version).
3. Ask the owner what device testing found. Fix one issue at a time. Obey The Law (§E).
4. Before any tag/release: full device matrix (§E.5) green on the vivo V2058.

---

*Owner's words: "i will never give up bro." Neither will we. One fix at a time, verified on
device, until it's bulletproof.* 🚀

