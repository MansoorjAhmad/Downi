# READ THIS BEFORE UPGRADE

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

> **Result:** All three platforms work from **all three entry points** – Vortex (Fast DL button), Inspect (manual quality picker), and DowniDrop (share‑intent receiver).

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
| **N8** | Terminal cards lingered **indefinitely** (a failed card from 12:47 still on screen at 13:05) because the snapshot was only pruned while a grab ran. | **Terminal state is honest but temporary** — 20 s, re‑pruned on **every read**, and failed cards must show failure copy with no Cancel. |
| **U5** | A moving progress bar **badged / vibrated / sounded** like an unread notification. | **Progress never badges, sounds or vibrates; only completion does.** (Android locks channel policy at creation — policy changes shape new installs only.) |
| **C** | The UI showed a **stale version** the build wasn't. | **No hardcoded version strings** — read them from the build. |

> **The contract in one line:** show only what is actually happening. When display and reality disagree, **reality wins** — even when the honest state looks worse.

---

## 7️⃣ Feature Freeze – Do NOT Put These In The Next Version

Scope is closed. These are the things that must **not** appear in a future version — each one either adds a new way to be false, or costs a full re‑verification pass for no user‑visible truth:

1. **Pause / resume with `.part` files** — nothing in the UI claims it. It means Range requests, a per‑job resumable state machine and a resumable merge: the highest‑risk possible source of **false live states** (exactly the N11/N12 class).
2. **TikTok photo‑post / carousel downloads** — new extractor surface, niche payoff, new falseness risk. The TikTok chip means *video*.
3. **Web‑side direct‑to‑CDN or "speed" tricks** — the engine already picks lanes; these only add failure modes.
4. **Any Cloud Boost–style relay** (see §4) — removed for cause. Reviving it revives the false claims.
5. **Light theme / theming rework** — deleted on purpose; re‑adding doubles the QA matrix.
6. **APK‑size or engine restructuring** — 42.6 MB is acceptable; churn with no user‑visible truth.
7. **Localization / multi‑language** — pure scope.
8. **Any new Android permission** — and know that `REQUEST_INSTALL_PACKAGES` (needed by the updater) is a **Play‑restricted** permission: harmless for sideload distribution, but it needs a declaration if DOWNI ever goes to Play. Never add `QUERY_ALL_PACKAGES` or `MANAGE_EXTERNAL_STORAGE`.

> **No defect, no release.** A new version number (3.1.2+) is only earned by a *proven* falseness found in the wild — never by a feature.

---


## 8️⃣ Known Limitations – State Them Honestly, Never Apply A Blind "Fix"

These are **not defects**. Each is a property of the platform or a deliberate decision — document it, and answer a report with this text instead of guessing at a patch.

1. **Logcat is unreadable on some ROMs** — on the vivo V2058 `adb logcat -s DOWNI:D` returns **nothing** even while grabs run with breadcrumbs enabled. Diagnostics = `dumpsys activity services` (service present = a grab is really live) plus the in‑app Queue. Never read "no log" as "no bug", and never ask a user for logcat.
2. **A terminal card can sit on screen while ACTIVE reads 0** — the 20 s window (N8) is *display*; the count is *reality* (N12). That is correct behaviour now, not a regression.
3. **Android locks notification channel policy at creation** — the U5 no‑badge policy therefore shapes **new installs**; an existing install keeps the old channel settings. It cannot be re‑"fixed" in code; only the user can change it.
4. **The header badge reads `v3.1` while Settings reads `3.1.1 (46)`** — deliberate major.minor short form. If it ever changes, it changes in one place — and no release note may describe the header as wrong.
5. **The Vault lists `Movies/DOWNI` + `Music/DOWNI` + tracked save names only** (v3.0.4) — a file the user moves elsewhere leaves the Vault. By design: never "fix" it by widening the storage query (§3).
6. **Extractors are engine‑supplied, and site‑side churn is not an app defect** — when a site changes its internals a grab can fail. The app must always say so plainly rather than deliver a wrong file.
7. **No unit tests exist for `Mp4Merger`, `_safe_name`, `extractUrl`** — device verification is the only net. If a future change touches one of them, its test ships **with** that change, never as its own release.
8. **Signing continuity is a promise, not a detail** — every release must be signed with the same key: `CN=Manso, O=OmniDownloader`, SHA‑256 `431131731d7b26dadd6dc6ffa3ef337853f30a63decbb863bcec2a61bb0785e5` (verify with `apksigner verify --print-certs` **before** tagging). A different key turns "install directly over the previous version — your settings and downloads are preserved" into a lie and forces every user to uninstall first.

---

## 9️⃣ Claims Discipline – What A Release Note May And May Not Say

- **"Verified" is a fact, not a mood.** A note may only say fixed/verified for cells **dated ✅ in `DEVICE_TEST.md`**. Cells still marked "— not re-run this pass" (1080p on a VP9-only video, Playlist Grab-all, Vault player, custom save folder, the 3-parallel + 4th-queued cap) must be described as **unchanged since 3.1.0**, or left out — never upgraded to "fixed".
- **The supported-site claim stays at three chips**: YouTube, TikTok, Instagram. Never "all sites", never "1000+ sites" — and the TikTok chip means **video**.
- **The offline claim must stay scoped**: *the UI* works with zero internet (Tailwind and the fonts are bundled in the APK). Downloads obviously need the network; the only outbound calls are the grab itself and the GitHub update check — so "no analytics / no tracking" is honest, but a bare "works offline" is not.
- **The tagged commit's message IS the published release notes** (`release.yml` uses `github.event.head_commit.message`). The tagged commit must carry the full summary — never tag a commit whose message reads "wip", "fixes", "bump" or "release vehicle".
- **Ship gates**: `test.yml` green (engine smoke + debug build), the `CHANGELOG.md` **and** `DEVICE_TEST.md` rows inside the same commit, and one post-release smoke on the **CI-built** APK — it installs over the previous version without an uninstall, and the Queue reads honestly (`ACTIVE` = running, ledger = history).

---

### 📌 Bottom Line


- Keep the **simple, certifi‑verified yt‑dlp options** as the source of truth.
- Never re‑add client‑spoofing, forced UAs, aggressive streaming hacks, or Instagram session harvesting.
- If a Cloud Boost or any external relay is ever reconsidered, wrap it in a safe fallback layer with health checks.
- Always run the **full Instagram / YouTube / TikTok × 3 entry‑point test suite** before any modification.

- **Show only what is actually happening** (v3.1.1, §6). If a change makes any tile, card, badge or notification say something the system is not doing, it is a blocker — not a polish item.
- **Any change touching live state, counters, notifications or the ledger must re-run the §6 re-checks (N8‑N13) on a real device** before it ships. A green build proves nothing about truthfulness.
- **Never re-introduce §7 by stealth** — no "small" pause button, no "just one" new permission.

*This file is a permanent reference; future contributors should read it before touching the download engine.*
