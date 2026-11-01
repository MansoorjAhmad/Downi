# READ THIS BEFORE UPGRADE

> **Last reviewed: 2026-11-01 (v3.8.0 / versionCode 60).** This is a living document, not an
> archive. Every feature, fix, engine constraint or newly found truth defect must update it **in
> the same commit** as the change (§9). If this file and the app disagree, the app is the fact and
> this file is the defect.

## 1️⃣ Proven Working Baseline (v2.6.2 – restored v1.3.6 core)

- **YouTube extraction**
  - Uses **yt‑dlp** with a minimal options set:
    ```python
    YoutubeDL({
        "ca_certs": certifi.where(),
        "outtmpl": os.path.join(target_dir, "%(title).120B-%(id)s.%(ext)s"),
        "windowsfilenames": True,
        "overwrites": True,
        "nopart": True,
        "retries": 3,
        "socket_timeout": 30,
        "progress_hooks": [_progress_hook],
        # format cascade – pure mp4 streams, no client spoofing
        "format": "best[height<=1080][ext=mp4][vcodec!=none]/best[height<=720][ext=mp4][vcodec!=none]/best[height<=480][ext=mp4][vcodec!=none]/best[ext=mp4][vcodec!=none]/best",
        "audio_format": "bestaudio[ext=m4a]/bestaudio/best",
    })
    ```
  - No `extractor_args` such as `player_client` and **no forced Android‑mobile User‑Agent**.
  - Directly passes `certifi.where()` for SSL verification – no monkey‑patched `ssl._create_unverified_context`.

- **Instagram extraction**
  - Calls the built‑in `instagram` extractor from `yt‑dlp` with the same simple options as above.
  - Relies on public, unauthenticated endpoints; **does not attempt to reuse a logged‑in session or cookies**.
  - Uses the same `outtmpl`, `retries`, and `socket_timeout` values, ensuring consistent behaviour with YouTube.

- **TikTok extraction**
  - Uses a lightweight direct‑API (`tikwm.com`) implementation that downloads the raw CDN URL.
  - Mirrors the same retry/timeout settings.

> **Result (the v2.6.2 baseline):** all three platforms worked from **all three entry points** –
> Vortex (Fast DL button), Inspect (manual quality picker), and DowniDrop (share‑intent receiver).
> This is history, not a claim about the current build. The current release's evidence lives in
> `DEVICE_TEST.md`; §9 forbids promoting a baseline claim to "verified".
>
> **Current rule (v3.8.0):** `nopart` is always `True` and `continuedl` is always `False`.
> DOWNI has no pause/resume (§7.1): every lane downloads straight to its final name and a
> cancelled transfer restarts from zero. The Android engine constraints (`js_runtimes: {}` and the
> non‑existent `ffmpeg_location`) are in §8.9; they are additions, not replacements for the
> baseline options.

---

## 2️⃣ What Broke It in v2.6.x and Why

| Change | Where it was introduced | Technical impact |
|--------|------------------------|------------------|
| **`player_client` extractor args** (e.g. `['ios','tv','web_safari']`, `['android_vr']`, `['tv_embedded']`) | `downloader.py` (v2.6.x) | Forces YouTube to request *GVS* “po_token” streams that only work with Google‑verified mobile clients. The Android‑mobile UA we forced prevented the token from being accepted, causing YouTube downloads to return *403* / empty output. |
| **Forced Android Mobile User‑Agent** (header override) | Same file, before calling `YoutubeDL` | Instagram detects the UA as a mobile app and returns a *login‑required* JSON payload or empty media list, so all Instagram entry points fail. |
| **Aggressive URL stripping / segmented multi‑connection streaming** (custom connection manager) | Complex download engine block (~800 lines) | Introduced race conditions and premature socket closures; YouTube and Instagram streams were truncated or aborted, leading to silent failures. |
| **Monkey‑patched `ssl._create_unverified_context`** | Early init code in v2.6.x | Bypassed proper certificate verification, which later conflicted with the `ca_certs` option and caused SSL handshake failures on newer devices. |

These changes collectively broke the previously reliable **v1.3.6** core.

---

## 3️⃣ Instagram Session / Cookie Login Feature – **DO NOT RE‑INTRODUCE**

- A prototype was built that harvested a real logged‑in Instagram session (cookies & CSRF token) and replayed it in automated download requests.
- **Risks:**
  - Exposure of the user’s personal Instagram credentials to the app’s process.
  - Violates Instagram’s Terms of Service – automated replay of a logged‑in session is explicitly prohibited.
  - Can lead to account bans or legal issues.
- The feature was **removed** in v2.6.2 and must remain removed. Even if framed as “optional” or “user‑enabled”, it must **never** be reimplemented.

---

## 4️⃣ Cloud Boost Relay Issue – What Went Wrong & Future Fix

- **Problem:** In v2.6.x the Cloud Boost fallback silently failed. The code attempted to auto‑retry via a remote relay but:
  1. No health‑check was performed; the relay could be unreachable.
  2. Failure bubbled up as an unhandled exception, crashing the app instead of gracefully falling back to the local extractor.
- **Fix (implemented in v2.6.2):**
  - Remove the Cloud Boost path entirely for on‑device builds.
  - If a future Cloud Boost is needed, implement a **robust wrapper** that:
    - Performs a **quick ping / health‑check** before invoking the relay.
    - Falls back to the **local downloader** on any non‑200 response or timeout.
    - Reports the fallback to the UI with a non‑intrusive toast, never aborting the whole download flow.

---

## 5️⃣ Mandatory Test Protocol Before Any Extraction‑Layer Change

1. **Prepare a clean device build** (no leftover caches).
2. **Run the following matrix** (all tests must pass):
   - **Platforms:** Instagram, YouTube, TikTok.
   - **Entry Points:**
     - **Vortex** (Fast DL button)
     - **Inspect** (manual quality picker)
     - **DowniDrop** (share‑intent receiver)
   - **Checks for each combination:**
     - Download completes without error.
     - Resulting file size > 0 KB and matches the expected format (`.mp4` for video, `.m4a` for audio).
     - No crashes or unhandled exceptions in the Java‑Python bridge.
3. **Automated script** (optional but recommended):
   - Use a headless Android test runner (e.g., `adb shell am instrument`) that programmatically triggers each entry point with a known test URL.
   - Verify exit codes and file existence on the device storage.
4. **Only after the full matrix passes** should a change be merged.

---

## 6️⃣ Truth-Layer Defect Classes (v3.1.1) – Never Re‑Introduce

v3.1.1 earned the name **"The Truth Release"** by killing the ways the app contradicted itself *on a real device*. Every row below is a **pattern, not a one‑off bug**: if a future change makes the UI say something the system is not doing, it belongs in this table and it is a blocker.

| # | Falseness pattern (never again) | The rule that replaces it |
|---|---|---|
| **N12** | A **finished** grab was counted as running — the 20 s "Saved ✓" card was counted in the ACTIVE tile **and** lit the Queue badge dot after every share, with the service already stopped. | **Counters speak only for what is RUNNING.** Cards may linger (N8); counts may not. Display and count are two different questions — never feed one from the other. |
| **N11** | A grab that no longer existed was shown as **live** (killed mid‑grab, reopen → frozen "running" card + `ACTIVE 1` + badge dot). | **Live state expires on liveness, never on a timer or optimism.** Ask the service (`liveJobIdsSnapshot()`, `null` = nothing alive). Terminal state keeps its short age window; running state is only as alive as the service is. |
| **N10** | **One** real grab produced **two** notification rows (the same row posted again across start / finish / cancel). | **One grab = one identity = one row.** The foreground slot has a single owner; a finishing, failing or cancelled grab releases it and hands it to the next live grab; progress ticks write only to the owning row. |
| **N9** | Work finished while the app was shut **disappeared** from the totals and the history. | **The ledger belongs to the service and is read on every state change**, not only at boot — and it is idempotent by row id. |
| **N13** | The ledger sat **one grab behind** (`COMPLETED` ticked up, `MB GRABBED` didn't) until the next resume. | **No stale totals, ever:** fold the service ledger in the moment state changes. Two tiles that disagree are a bug, not a refresh delay. |
| **N14** | The diagnostics reported **"All clear" with zero checks** — `JSArray.from(JSONArray)` returned `null`, the `results` key vanished, and an empty box was read as health (v3.6.2, 2026-10-22). | **An empty result set is never a pass.** Render every check the engine returned; if it returned none, say *"The engine returned no checks"* and never *"All clear"*. Absence of evidence is not evidence of health. |
| **N15** | The YouTube diagnostic **called a working network blocked** because it tested a section cut (`download_ranges`) that needs ffmpeg — a tool this app does not have (v3.6.2, 2026-10-22). | **A diagnostic must test the claim it makes.** Media reachability is a real Range GET (64 KB video + 16 KB audio) on the resolved URL, not a metadata read and not a tool‑dependent cut. If the instrument cannot run, say so; never report it as the site's verdict. |
| **N16** | **Unknown duration was rendered as a real zero** — the Instagram inspector showed `0:00` for `reel/DcZTAe4jKBp`; the downloaded file is **18.97 s** (ffprobe; the first pass measured 18.83 s). `index.html` always wrote `formatDuration(info.duration)`, and `formatDuration(0)` returned `"0:00"` (v3.6.2 matrix, 2026-10-22). | **Missing is not zero.** If `duration` is absent/0, hide the duration badge or show an explicit unknown (`—`); never invent a time. The subtitle already omits it (`if (info.duration)`); the thumbnail badge must do the same. **Fixed in the 3.6.2 build (55) and verified on-device 2026-10-22:** the Instagram badge is hidden when duration is absent/0; YouTube/TikTok still show real durations. |
| **N17** | **A quality label promised a resolution the file did not have** — the Instagram sheet preselected **“Up to 1080p Full HD”**; ffprobe of the delivered file reports **720×1280**. `downloader.py` gives every non‑YouTube platform a generic 1080p/720p/480p list without reading `info['formats']` (v3.6.2 matrix, 2026-10-22). | **A quality label is a claim about the file.** Derive the rows from the extractor's actual formats, or offer only **Best available** when the real height is unknown. "Up to 1080p" is not permission to deliver 720p under a 1080p‑selected button. **Fixed in the 3.6.2 build (55) and verified on-device 2026-10-22:** non-YouTube offers only **Best Available Quality**; Instagram's 1080p is video-only DASH and is no longer offered. |
| **N8** | Terminal cards lingered **indefinitely** (a failed card from 12:47 still on screen at 13:05) because the snapshot was only pruned while a grab ran. | **Terminal state is honest but temporary** — 20 s, re‑pruned on **every read**, and failed cards must show failure copy with no Cancel. |
| **U5** | A moving progress bar **badged / vibrated / sounded** like an unread notification. | **Progress never badges, sounds or vibrates; only completion does.** (Android locks channel policy at creation — policy changes shape new installs only.) |
| **C** | The UI showed a **stale version** the build wasn't. | **No hardcoded version strings** — read them from the build. |

> **The contract in one line:** show only what is actually happening. When display and reality disagree, **reality wins** — even when the honest state looks worse.

---

## 7️⃣ Feature Freeze – Do NOT Put These In The Next Version

Scope is closed. These are the things that must **not** appear in a future version — each one either adds a new way to be false, or costs a full re‑verification pass for no user‑visible truth:

1. **Pause / resume, `.part` continuation, and resumable job state** — **removed in 3.8.0 by
   owner ruling; permanently banned.** History: ruled out on 2026-09-23 (`0fd4001`), implemented
   by mistake in `c631514`, shipped in v3.6.2, and finally deleted in 3.8.0. The removal covered
   the notification **Pause / Resume** actions, the Queue **▶ Resume** control, the
   `shared_pause` / `shared_resume` / `shared_resume_latest` service actions, `resumeDropJob`,
   `PausedError`, the `pausedJobs` / `pausedGrabs` state, `DownloadProgressListener.isPaused` /
   `supportsPause`, and the `.part` continuation path. Do **not** reintroduce it under any name:
   no "resume from where you left off", no resumable state machine, no new pause surface. A
   cancelled or interrupted transfer restarts from zero. See `DECISIONS.md`.
2. **TikTok photo‑post / carousel downloads** — new extractor surface, niche payoff, new falseness risk. The TikTok chip means *video*.
3. **Web‑side direct‑to‑CDN or "speed" tricks** — the engine already picks lanes; these only add failure modes.
4. **Any Cloud Boost–style relay** (see §4) — removed for cause. Reviving it revives the false claims.
5. **Light theme / theming rework** — deleted on purpose; re‑adding doubles the QA matrix.
6. **APK‑size or engine restructuring** — 42.6 MB is acceptable; churn with no user‑visible truth.
7. **Localization / multi‑language** — pure scope.
8. **Any new Android permission** — and know that `REQUEST_INSTALL_PACKAGES` (needed by the updater) is a **Play‑restricted** permission: harmless for sideload distribution, but it needs a declaration if DOWNI ever goes to Play. Never add `QUERY_ALL_PACKAGES` or `MANAGE_EXTERNAL_STORAGE`.

> **No defect, no release.** A new version number (3.1.2+) is only earned by a *proven* falseness found in the wild — never by a feature.

---


---

## 7️⃣-bis. Owner-Ruled Releases — Scope Reductions (v3.5.0) and Identity Changes (v3.6.1)

The rule in §7 still stands: **"no defect, no release."** It has one amendment, made on
2026-10-20 when DOWNI 3.5.0 removed the Vault, the Fetcher and the Core.

**A new version number may also be earned by an owner-ruled scope reduction** — a deliberate
removal of a shipped feature, made because users said the app had grown too much around the thing
it is actually for.

**What that means, exactly:**

1. The removal must be **named honestly** in the release notes: what went, why, and what the
   user loses. A release note that calls a removal a "simplification" and hides the loss is
   exactly the kind of thing §9 forbids.
2. **§6 still applies in full.** Removing a tab touches counters, badges, the ledger and
   notifications, so the truth re-checks must be re-run on a real device before the release.
   "It built and it launched" is not evidence.
3. **§5 does not apply** when the extraction layer is untouched — which is the normal case for a
   scope reduction. If the engine changed, the full matrix is owed as usual.
4. **Nothing removed comes back by stealth.** A feature that was ruled out stays out until the
   owner rules it in again. This is the §7 principle applied to removals: no "just one" button,
   no "small" re-add.

**The precedent it sets:** DOWNI is a downloader. If a feature does not make it *download better*,
it does not belong in the app. That is the test every future proposal is measured against.

### v3.6.1 — the owner-ruled identity release (2026-10-21)

**A second owner ruling, 2026-10-21 (v3.6.1).** The owner explicitly authorized a release for the
launcher icon, the interface face (Hubot Sans) and the progress card. It is recorded here as an
**owner ruling for that release**, not as a precedent: a contributor‑proposed feature still cannot
earn a version under §7. (The progress card also fixed a real truth defect — three clocks showing
three different numbers — which is why it was defensible even under the old rule.)

## 8️⃣ Known Limitations – State Them Honestly, Never Apply A Blind "Fix"

These are **not defects**. Each is a property of the platform or a deliberate decision — document it, and answer a report with this text instead of guessing at a patch.

1. **Logcat is unreadable on some ROMs** — on the vivo V2058 `adb logcat -s DOWNI:D` returns **nothing** even while grabs run with breadcrumbs enabled. Diagnostics = `dumpsys activity services` (service present = a grab is really live) plus the in‑app Queue. Never read "no log" as "no bug", and never ask a user for logcat.
2. **A terminal card can sit on screen while ACTIVE reads 0** — the 20 s window (N8) is *display*; the count is *reality* (N12). That is correct behaviour now, not a regression.
3. **Android locks notification channel policy at creation** — the U5 no‑badge policy therefore shapes **new installs**; an existing install keeps the old channel settings. It cannot be re‑"fixed" in code; only the user can change it.
4. **The header badge reads `v3.6` while Settings reads `3.6.2 (55)`** — deliberate major.minor short form. If it ever changes, it changes in one place — and no release note may describe the header as wrong.
5. **The Vault is gone (v3.5.0)** — the old limitation (it listed `Movies/DOWNI` + `Music/DOWNI` + tracked save names only, so a moved file disappeared from it) is **historical**. Do not reintroduce the Vault. If a file list ever returns, it must state its scope; never widen the storage query just to make a moved file appear.
6. **Extractors are engine‑supplied, and site‑side churn is not an app defect** — when a site changes its internals a grab can fail. The app must always say so plainly rather than deliver a wrong file.
7. **No unit tests exist for `Mp4Merger`, `_safe_name`, `extractUrl`** — device verification is the only net. If a future change touches one of them, its test ships **with** that change, never as its own release.
8. **Signing continuity is a promise, not a detail** — every release must be signed with the same key: `CN=Manso, O=OmniDownloader`, SHA‑256 `431131731d7b26dadd6dc6ffa3ef337853f30a63decbb863bcec2a61bb0785e5` (verify with `apksigner verify --print-certs` **before** tagging). A different key turns "install directly over the previous version — your settings and downloads are preserved" into a lie and forces every user to uninstall first.
9. **Chaquopy cannot spawn a subprocess — and yt‑dlp must never be allowed to try.** `js_runtimes: {}` is mandatory (an empty dict disables the JS runtime probe). `ffmpeg_location` must be a path that **does not exist** (the app uses `/nonexistent/downi-no-ffmpeg`). **`False` is not "off"**: it reaches `os.path.exists(False)` inside yt‑dlp's ffmpeg postprocessor and raises `TypeError: expected str, bytes or os.PathLike object, not bool` during extraction, before a single quality is listed (this is the v3.6.2 Inspect failure). **`None` is worse**: it re‑enables the probe, which SIGSEGVs on this platform. The merge is Android `MediaMuxer` in Java, never ffmpeg. Never "fix" a missing ffmpeg by pointing at a real binary, and never remove the key. `download_ranges` also needs ffmpeg; diagnostics must use direct Range GETs instead.

---

## 9️⃣ Claims Discipline – What A Release Note May And May Not Say

- **"Verified" is a fact, not a mood.** A note may only say fixed/verified for cells **dated ✅ in `DEVICE_TEST.md`**. For v3.6.2 the six TikTok/Instagram cells are dated ✅ (row 6-13) and file-checked with ffprobe (row 6-14); the Instagram **duration** and **quality label** rows are now ✅ (6‑15/6‑16) and the grab-card pan is ✅ (6‑17) on the fixed build (same 3.6.2 / 55, hash `AFB29A0D…`). Pause/resume was removed in 3.8.0 by owner ruling and is permanently banned (§7.1).
- **The supported-site claim stays at three chips**: YouTube, TikTok, Instagram. Never "all sites", never "1000+ sites" — and the TikTok chip means **video**.
- **The offline claim must stay scoped**: *the UI* works with zero internet (Tailwind and the fonts are bundled in the APK). Downloads obviously need the network; the only outbound calls are the grab itself and the GitHub update check — so "no analytics / no tracking" is honest, but a bare "works offline" is not.
- **The tagged commit's message IS the published release notes** (`release.yml` uses `github.event.head_commit.message`). The tagged commit must carry the full summary — never tag a commit whose message reads "wip", "fixes", "bump" or "release vehicle".
- **Ship gates**: `test.yml` green (engine smoke + debug build), the `CHANGELOG.md` **and** `DEVICE_TEST.md` rows inside the same commit, and one post-release smoke on the **CI-built** APK — it installs over the previous version without an uninstall, and the Queue reads honestly (`ACTIVE` = running, ledger = history).
- **This file ships with the change.** Every feature, fix, engine constraint or newly discovered truth defect updates `READ_THIS_BEFORE_UPGRADE.md` **in the same commit** as the code. A release whose lesson is not recorded here is not finished.

---

## 🔟 v3.6.2 — The Truth Release Case Notes (2026-10-22, versionCode 55)

Ten lessons from this release. Each one is a line of code that looks harmless and is not:

1. **`ffmpeg_location: False` is not "off".** yt‑dlp's ffmpeg postprocessor calls
   `os.path.exists(False)` and raises `TypeError: expected str, bytes or os.PathLike object, not
   bool` during extraction — before the inspector can list a single quality. The app now passes a
   path that does not exist (`/nonexistent/downi-no-ffmpeg`); `None` re‑enables the probe and
   SIGSEGVs. The 1080p merge is Android `MediaMuxer` in Java (verified: 84,396,135 B). See §8.9.
2. **`JSArray.from(JSONArray)` returns `null`.** Its constructor accepts primitive arrays and
   Collections only, so the `results` key vanished and the diagnostics box said *"All clear"* with
   nothing in it. The bridge now parses `new JSArray(rows.toString())`, and an empty list renders
   *"The engine returned no checks"* with a **No results** toast. See N14.
3. **`download_ranges` needs ffmpeg.** The YouTube diagnostic used it to "prove" media
   reachability, so a missing tool could be reported as a blocked site. It now performs a real
   Range GET — 64 KB of video and 16 KB of audio from the resolved URLs. See N15.
4. **The clipboard banner lives exactly as long as the clipboard holds a link.** The old code hid
   it on "grab started" and only re‑showed it when the URL changed. Now it shows whenever a link is
   present (polling every 4 s while the app is visible), updates to **Already grabbed / Grab
   again**, and leaves only when the clipboard has no link or detection is switched off. Never
   hide it on a grab event again.
5. **One save path, one album.** The foreground inspector wrote to `Movies/DOWNI/` while the
   headless share path wrote to `Movies/`; both now land in the gallery root, as Settings says.
6. **One classifier, eight causes.** `android/app/src/main/python/truth.py` is the only place
   allowed to classify a failure — `blocked`, `rate_limited`, `private`, `geo`, `format`, `link`,
   `network`, `unknown` — and `unknown` never blames the user's video. The Java bridge and the UI
   carry the raw reason untouched. `tools/test_truth.py` covers 17 classifications.
7. **The engine pin is deliberate.** yt‑dlp `2026.10.10.233141`, with **no** `player_client`
   cascade and **no** forced mobile User‑Agent — both are documented causes of 403s and Instagram
   breakage (§2). A newer nightly is a release decision, not an automatic upgrade.
8. **Unknown duration is not `0:00`.** The Instagram inspector rendered the thumbnail badge with
   `formatDuration(info.duration)` unconditionally; `formatDuration(0)` returns `"0:00"`. The
   downloaded file was **18.97 s**. The fixed build hides the badge while scanning and whenever
   the extractor has no duration, and restores it only for a real positive value; YouTube **3:33**
   and TikTok **0:24** still show. Verified on-device 2026-10-22. See N16.
9. **The generic quality list is a promise the extractor did not make.** Non‑YouTube platforms
   were offered 1080p / 720p / 480p rows regardless of `info['formats']`; the selected “Up to
   1080p Full HD” lane delivered a **720×1280** file. The fixed build offers only **Best Available
   Quality** for Instagram, with a note that higher resolutions may exist only as separate streams
   it does not merge. Instagram's 1080p is **video-only DASH**; the playable single file is 720p.
   YouTube's **1080p Full HD (merged on device)** row is unchanged. Verified on-device 2026-10-22;
   the delivered Instagram file is H.264 720×1280 + AAC, 4,603,266 B. See N17.
10. **A hidden overflow container is still scrollable.** The grab card's `overflow: hidden` plus
    its `::before` spotlight (`right: -20%`) made the card a horizontal scroll container. When the
    URL input was long-pressed, WebView scrolled the card to reveal the caret/selection and left it
    panned: `NEW GRAB` clipped to `AB`, the field/button clipped, while the header, banner and other
    cards stayed put. Force‑stop/relaunch cleared it; it reproduced on TikTok and Instagram. This is
    a pre‑existing UI defect, not a v3.6.2 extraction change. **Fixed in the 3.6.2 build (55):**
    `.grab-card` now has `overflow: hidden; overflow: clip;` plus an `onscroll="this.scrollLeft=0"`
    guard for WebViews that ignore `clip`. Verified on-device 2026-10-22: long-press opened the
    real selection menu with the URL highlighted, the card stayed aligned, and **Copy** still
    updated the clipboard banner. See `DEVICE_TEST.md` 6-17.

11. **The Video Uniquifier: Container Remuxing & Entropy Injection.**
    In v3.7.0, an experimental remuxer attempted arbitrary timestamp clamping (`sampleTime = lastTime + 1000`). In v3.8.0, this was superseded by Anti-Repost Shield S2 (see §11), which removes arbitrary timestamp clamping, restricts remuxing strictly to MP4/H.264/AAC, and preserves original files on validation failure. The effect of these file modifications on platform duplicate detection is untested.
12. **Android 13 Foreground Service Notification Cancellation Lock.**
    On Android 12 & 13 (API 31+ / vivo FuntouchOS), calling NotificationManagerCompat.cancel(FG_NOTIFICATION_ID) while the service is actively running as a Foreground Service (startForeground) is silently ignored by the Android framework. Calling deprecated stopForeground(true) without specifying STOP_FOREGROUND_REMOVE and without cancelling after stopping the foreground state causes the progress notification ('DOWNI is grabbing... 100%') to stay stuck indefinitely in the notification shade. The service teardown must use stopForeground(STOP_FOREGROUND_REMOVE) and explicitly invoke cancel(FG_NOTIFICATION_ID) post-teardown.

**Release status at the time of writing (2026-10-22, after the fixed-build retest):** build 55 is
device‑verified for the three reported faults, the YouTube 1080p path, and all six TikTok /
Instagram cells (rows 6‑1 … 6‑17 in `DEVICE_TEST.md`). The delivered files are real media
(ffprobe: TikTok HEVC 1080×1920 / 24.40 s; Instagram H.264 720×1280 / 18.97 s), and all three
Instagram paths delivered the same SHA-256 `9438E8E8…`. **The two truth defects and the UI defect
are fixed and dated ✅** (6‑15 / N16, 6‑16 / N17, 6‑17): Instagram offers only **Best Available
Quality**, unknown duration is hidden, and long-press selection no longer pans the card. The APK
is signed with the §8.8 key and **local only** — no tag, push or release until the owner says go.
Pause/resume was removed in 3.8.0 by owner ruling and is permanently banned (§7.1).

---

## 1️⃣1️⃣ v3.8.0 — The Anti-Repost Shield & Engine Parity Release (2026-11-01, versionCode 60)

Key architectural rulings and fixes in 3.8.0:

1. **Pause / Resume is permanently removed and banned (D-001).** All pause/resume surfaces (notification actions, Queue buttons, service maps, listener methods, Python `PausedError` and `.part` continuation) are deleted. Cancel is final; subsequent downloads restart from 0%.
2. **Anti-Repost Shield S2 (D-003).** Replaces experimental remuxing with a verified S2 implementation:
   - Performs software sample-by-sample remuxing via `android.media.MediaExtractor` and `android.media.MediaMuxer` (no hardware encoding/decoding) strictly for MP4 containers with 1 H.264 video track (profile <= High) and 0 or 1 AAC audio track (`VideoUniquifier.java` lines 158–190, 202–307). Preserves orientation hints (`KEY_ROTATION`, lines 227–235) and interleaves samples by presentation timestamp without artificial clamping.
   - Appends an ISO-14496-12 `free` box with 48 bytes of random entropy from `SecureRandom` (`VideoUniquifier.java` line 297, `IsoBmff.java` lines 85–98).
   - Identity lane fallback: for non-remuxable containers/codecs or upon remux failure, copies input bytes unchanged and appends an ISO `free` box (MP4) or EBML `Void` element (WebM) (`IsoBmff.java` lines 175–222). For MP4 files, overwrites `creation_time` and `modification_time` fields in the `moov.mvhd` atom (`IsoBmff.java` lines 229–296). It does not parse or strip individual metadata tags.
   - Post-remux validation: uses `android.media.MediaExtractor` (`VideoUniquifier.java` lines 328–330) to check track count match (line 331), dimensions match (lines 345–349), duration tolerance within ±150ms (lines 352–356), and checks that the first video sample has `MediaExtractor.SAMPLE_FLAG_SYNC` set (lines 359–365). Does NOT use `MediaMetadataRetriever` and does not decode video frames.
   - Original file preservation: on validation failure or if Shield is skipped, preserves original files untouched and reports an honest error without generating duplicate files (`VideoUniquifier.java` lines 146–149, `DowniEnginePlugin.java` lines 242–245).
   - Platform effect: The effect of container remuxing, timestamp rewriting, or entropy injection on any platform's ingest, processing, duplicate detection, or account standing is completely untested. The code makes no claim to defeat, bypass, or evade detection on any service.
3. **Engine Pin & CI Drift Verification.** `yt-dlp==2026.10.29.232701.dev0` and `certifi==2026.10.23` are pinned in `android/app/build.gradle` as the single source of truth, verified by cryptographic PyPI wheel SHA-256 matching via `ci/verify_pin_parity.py`.
4. **Network Diagnostics Integrity (Engine Settings Option B).** Removed misleading mock engine health badges, fake version checks, and dead notifications. Replaced with real DNS, TLS, and TCP reachability tests with hard timeouts in worker threads and sanitized native clipboard export.
5. **Single Source of Truth for App Version (D-005).** `build.gradle` is the sole source for `versionName` and `versionCode`. `DowniEnginePlugin` reads directly from `BuildConfig`, eliminating hardcoded fallbacks like `3.6.2`/`55L`. The web UI reads version info dynamically via native bridge calls. `ci/verify_version_parity.py` validates this on every commit.

---

### 📌 Bottom Line


- Keep the **simple, certifi‑verified yt‑dlp options** as the source of truth.
- Never re‑add client‑spoofing, forced UAs, aggressive streaming hacks, or Instagram session harvesting.
- If a Cloud Boost or any external relay is ever reconsidered, wrap it in a safe fallback layer with health checks.
- Always run the **full Instagram / YouTube / TikTok × 3 entry‑point test suite** before any modification.

- **Show only what is actually happening** (v3.1.1, §6). If a change makes any tile, card, badge or notification say something the system is not doing, it is a blocker — not a polish item.
- **A missing value is not a zero, and a quality label is a claim** (N16/N17): hide an unknown duration, and derive quality rows from the formats the extractor will actually deliver (or offer only **Best Available**). A hidden overflow container is still scrollable; `overflow: clip` plus a scroll reset fixes the card pan, and long-press text selection must be re-tested on a real device.
- **Any change touching live state, counters, notifications or the ledger must re-run the §6 re-checks (N8‑N13) on a real device** before it ships. A green build proves nothing about truthfulness.
- **Never re-introduce §7 by stealth** — no "just one" new permission, no new pause surface. Pause/resume was removed in 3.8.0 and is **permanently banned** (§7.1); do not extend, revive or rename it.
- **Chaquopy cannot spawn a process** — `js_runtimes: {}` and a non‑existent `ffmpeg_location` stay; no ffmpeg, no subprocess, no "quick fix" that enables either (§8.9).

*This file is a permanent reference; future contributors should read it before touching the download engine.*
