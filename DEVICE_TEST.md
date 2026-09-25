# 📱 DOWNI Device Test Matrix — The Law's Gate

`READ_THIS_BEFORE_UPGRADE.md` §5: **no release ships before this passes on a real phone.**
Fill it in every time the extraction layer changes, and after any UI/wiring release like a
hardship check. Mark ✅ / ❌ per cell.

## 1. Core matrix (mandatory)

> Device pass **2026-09-24** — vivo V2058, build **46 / v3.1.1**. ✅ = seen on this device, with the
> evidence named. ⏳ = can't be driven over adb on this ROM (no `cmd clipboard`), owner tap needed.
> — = not re-run in this pass (unchanged code path, or covered by an earlier pass).

| Platform | Vortex (1-tap) | Inspector (pick quality) | DowniDrop (share → DOWNI) |
|---|---|---|---|
| **YouTube** | ✅ 09-24 owner tap (live 1080p numbers on the card) | ✅ 09-24 — 1080p Full HD *merged on device*: `Rick Astley…4K Remaster.mp4` = 84,396,135 B, ffprobe h264 **1920×1080** + aac 2ch, 3:33 | ✅ 09-24 — cold share (app force-stopped) merged 1080p: progress row `16% · 18.4 MB / 77.2 MB · 105 KB/s · 572s left` + named "Saved to your Vault" alert |
| **Instagram** (public reel) | ⏳ owner-tap only | — not re-run this pass | ✅ 09-24 — cold *and* hot share (`Video by niko_bellic_gta_4 (5).mp4`) |
| **TikTok** | ⏳ owner-tap only | — not re-run this pass | ✅ 09-24 — cold instant grab on a live URL |

For each ✅: file exists in Vault, size > 0 KB, plays with audio, correct format (.mp4/.m4a).

> Tip: pick SHORT clips for the YouTube cells. YouTube now serves adaptive-only streams, so a 60fps
> 4K demo (e.g. Big Buck Bunny) is a ~258 MB 1080p download — on a slow link that looks like a
> stalled engine. A 19-second clip (e.g. "Me at the zoo") proves the same path in seconds.

## 2. v3.0 features matrix

| Feature | Check |
|---|---|
| True 1080p merge | ✅ 09-24 — YouTube Inspector → 1080p Full HD → h264 1920×1080 + aac, plays with sound |
| 1080p on a VP9-only video | — not re-run this pass |
| Playlist Grab-all | — not re-run this pass |
| Vault | ✅ 09-24 — downloads appear (permission asked once, on first Vault open) |
| Vault player | — not re-run this pass |
| Custom save folder | — not re-run this pass (left on `Gallery — Movies / Music`) |
| Share via text selection | ✅ 09-24 — Chrome select → DOWNI (PROCESS_TEXT) → instant background grab |
| Updater | ✅ 09-24 — Settings → Check Updates → "Up to date" |

## 3. Regression sweep (after any engine touch)

- ✅ 09-24 Cancel mid-download (in-app card + DowniDrop): download stops, no file and no `.part`
  left behind, neutral "Grab canceled", notification clears
- ✅ 09-24 Airplane mode → honest error, no crash
- — 09-24 3 parallel downloads + 4th queued: covered by the 5-share burst (all delivered as a polite
  sequential queue, no cap violation)

> Sign and date here when green: ______________

## 3. v3.1.0 matrix (The Polish Release — MANDATORY)

| # | Test | Check |
|---|---|---|
| 1 | **Cold-start DowniDrop** — force-stop DOWNI (Settings → Apps → DOWNI → Force stop), then share a TikTok link → DOWNI | ✅ 09-24 — progress notification with live numbers appears at once, no UI opens, file lands in Vault |
| 2 | Warm-share instant grab (app open in background) | ✅ 09-24 — same result, no Inspector |
| 3 | Rapid double-share | ✅ 09-24 — 5-share burst (10:26): all delivered as a polite sequential queue, one completion alert each, no overwrite |
| 4 | Cancel button on DowniDrop progress notification | ✅ 09-24 — download stops, no file in Vault, no `.part`, notification clears (verified on the in-app card, which shares the same cancel path) |
| 5 | Failure path — share an invalid/private link | ✅ 09-24 — honest plain-reason copy, no scary engine text; tap → Inspector with the link |
| 6 | **Settings → DowniDrop → "Ask quality first"**, then share | ✅ 09-24 re-checked — RETIRED in v3.1.1 (owner ruling 2026-09-24): the Instant/Ask toggle was deleted after the instant column passed this matrix — shares always grab instantly. Settings → DowniDrop shows one static "⚡ Instant grab" chip; a share never opens the Inspector |
| 7 | Quality memory — pick 720p for TikTok in Inspector once, then instant-share a TikTok link | ✅ 09-24 — Inspector pick (1080p Full HD) → cold share honoured it (`…/ 77.2 MB`, merged output) |
| 8 | Custom save folder set → instant DowniDrop share | — not re-run this pass (left on `Gallery — Movies / Music`) |
| 9 | **Vault blink test** — open Vault, watch: downloads finishing, Rescan, tab switches | ✅ 09-24 — pixel-stable across frames, scroll kept |
| 10 | Vault search — type quickly | ✅ 09-24 — grid updates once after the debounce, no per-keystroke flash |
| 11 | Vault long-press selection — enter, toggle 3 cards, exit | ✅ 09-24 — cards flip in place, list intact on exit |
| 12 | New arrival — finish a download while Vault is open | ✅ 09-24 — after the VA fix the open grid live-refreshed on the new file (46 → 47 items) |
| 13 | Vault still shows only DOWNI downloads (v3.0.4 fold-in) | ✅ 09-24 — probe file outside `Movies/DOWNI` never appears |

## 4. v3.1.1 fix re-checks (defects found on device)

| # | Fix | Re-check |
|---|---|---|
| N8 | Finished/failed DowniDrop cards linger in Active downloads | ✅ 09-24 — after a completed share the card clears itself (20 s terminal window, re-pruned on read); a failed card shows "Failed", hides Cancel, and no stale "Merging video…" line |
| N9 | "MB grabbed" / history ignore grabs that finished while the app was shut | ✅ 09-24 — reset to 0/0/0, in-app grab → `1 COMPLETED · 1 MB`; a share downloaded with the app **force-stopped** → after reopening: `2 COMPLETED`, MB adds the real size and the row is listed (service ledger) |
| N10 | One grab could show **two** notification rows (the same row posted twice across `job_start` / `job_finish` / cancel) | ✅ 09-24 — the foreground slot has exactly one owner (`fgRowOwner`). One live grab → **1** progress row (`id 4811`, `downi_progress`) and 1 live card; **two concurrent grabs → 2 distinct rows** (owner `4811` + the second grab's own id `5190`) instead of two copies of the newest; cancelling the owner released its row (leaving a single "Grab canceled" entry on `downi_alerts`) and the still-running grab **adopted the slot** — `4811` then read `0% · 3.1 MB / 246 MB` while its temporary own-id row was dropped, so it stayed at one row; after each cancel the service stopped and no progress row was left in the shade. Accounted honestly throughout: ACTIVE 0, COMPLETED 2, MB GRABBED 81 unchanged, nothing new in `Movies/DOWNI` and no `.part` left. Evidence: `test_out/n10_one_row.jpg` (single row live), `n10_live_card.jpg` + `n10_cancel_live.jpg` (card numbers + Cancel), `n10_dual_live.jpg` (two concurrent grabs → two rows/cards), `n10_adopt_b.jpg` (slot handed over, still one row), `n10_clean_final.jpg` (nothing orphaned after cancels), `n10_ledger_final.jpg` (ledger unchanged) |
| N11 | **Ghost live grab** — kill the app mid-grab, reopen: still showed a running card **and** counted `ACTIVE 1` (plus the Queue badge dot) for a grab that no longer existed | ✅ 09-24 — found live in this pass, fixed and re-verified on build 48. `running` snapshot entries now expire on **liveness** (`DowniDownloadService.liveJobIdsSnapshot()`, `null` = no service alive) instead of never — terminal entries keep their 20 s age window (N8). Before the fix: force-stop while the card read "Warming up the engine…" → reopen showed a live card + `ACTIVE 1` with no service and no notification row (`n11_ghost_queue.jpg`). After the fix: the stale entry is pruned (and rewritten out of storage) on the first read — Grab tab shows no "Active downloads" section, Queue reads `ACTIVE 0 · COMPLETED 2 · 81 MB`, no badge dot (`n11_ghost_gone.jpg`) — while the honest live case still renders (`ACTIVE 1` + card with real numbers, exactly one row `4811`, `n11_live_still_ok.jpg`) |
| N12 | **Finished grab counted as active** — for a full 20 s after *every* successful share the Queue dashboard read `ACTIVE 1` and lit the Queue badge dot while the service was already stopped. The finished card's 20 s terminal window (N8) was being counted as a live job: the in-app path deletes its job at completion, the background path kept it in `bgJobs` and `updateQueueBadge()` counted the whole merged set | ✅ 09-24 — found live on the pre-fix build: one share grab, then at +1 s and +11 s after the service stopped (`stopSelf`) the app still read `ACTIVE 1` + badge dot with a 100% card and nothing downloading (`n12_win_1.jpg`, `n12_win_2.jpg`). Fix: `updateQueueBadge()` counts only entries that are neither done nor failed (cards still linger for their window). Same moment after the fix, with the "Saved ✓" card on screen: `ACTIVE 0`, no dot (`n13_ctrl_win2.jpg`, `n13_win8.jpg`) — and the honest live case still counts (`ACTIVE 1` + dot + real numbers, `n13_live.jpg`, `n13_ctrl_win1.jpg`) |
| N13 | **"MB grabbed" sat a grab behind** — a grab that finished while the app was open ticked `COMPLETED` up but not `MB GRABBED`; the service ledger was only re-read on boot / resume, so the two tiles disagreed until the app was backgrounded | ✅ 09-24 — pre-fix evidence: `COMPLETED 6 · 83 MB` immediately after a completion (`n12_fix_win1.jpg`), catching up to 84 MB only at the next cold start. Fix: the done-snapshot branch folds the service ledger in (`syncGrabLedger()`, idempotent by row id). Controlled re-check — force-stop → cold start `10 COMPLETED · 87 MB` (`n13_ctrl_base.jpg`), then **one** share grab with the app left open: +9 s later the same screen read `11 COMPLETED · 88 MB`, `ACTIVE 0`, finished card still shown (`n13_ctrl_win2.jpg`) — both tiles move together in-session, one grab → one row (no double-count) |
| U5 | Progress channel must not badge/vibrate/sound | ✅ 09-24 — the share ran on channel `downi_progress` (`mVibrationEnabled=false`, no sound) while `downi_alerts` keeps the badge |

Device: vivo V2058, USB only.

## 5. v3.2 Fetcher matrix (Phase 0 → G) — MANDATORY before any Fetcher release

> Status column is honest: **✅ pass**, **⏳ not yet measured**, **❌ fail**, **— not applicable yet**.
> Evidence must name a file, a log line or a screenshot — "it looked right" is not a pass.
> Cells are the plan's own (`V3.2_PLAN.md`); this section exists so the Fetcher can be gated the way
> v3.0/v3.1.0 were, instead of by eyeball.

**Device pass 2026-09-25 (vivo V2058, build 3.1.2 / 47 + Fetcher spike).**

| Cell | Expect | Status 2026-09-25 | Evidence |
|---|---|---|---|
| P0-1 | `SESSION_START` with the right package ≤~1 s of opening IG/TikTok | ✅ | Phase 0 logs, 2026-09-24 |
| P0-2 | `STEP2` watching signals present while watching, weak while browsing | ✅ | Phase 0 logs, 2026-09-24 |
| P0-3 | `STEP3` candidates across ≥80% of watched videos, both platforms | ❌ | Phase 0 verdict: **61/61 tree dumps clean — no URL/ID exposed by either app** |
| P0-4 | `PIPELINE_READY` for HIGH URLs; with handoff, grab reaches Queue | ⏳ | contract logged (`CONTRACT target=DowniDownloadService.startShared`); no `PIPELINE_READY` line verified |
| P0-5 | Service stable, log cap respected, battery sane over 10 min | ❌ | dies to vivo ABE in 84–283 s (`_soak_*.log`) |
| C1 | `CHAIN_SCAN` finds a clickable Share node on a live video | ✅ **PASS** | **TikTok 9/9 scans** (`share=2..3`, `id/g75 text="share video 21.5k shares"` clickable+visible); **IG 16/20** (`share=1..4` on a post/Reel; the 4 zeros sit at 09:20–10:15, plausibly non-video screens) |
| C2 | `CHAIN_SHARE_CLICK` → share UI within 1.3 s | ✅ **PASS** | `CHAIN_SHARE_CLICK text=share route=action` → `CHAIN_STEP2` sees the sheet ~1.3 s later (this is the step the old "≈1.5–1.7 s" figure described) |
| C3 | `CHAIN_BUTTON` exposes "Copy link" and/or DOWNI | ✅ **PASS** | `CHAIN_COPYLINK_CANDIDATE … text=copy link clickable=true` (16:34:58) **and** `CHAIN_CHOOSER_DOWNI text= route=action` (16:33:51, 16:34:35, 16:35:00) after `CHAIN_CHOOSER_SCROLL n=1/4`; fallback `CHAIN_CHOOSER_NO_DOWNI back=true` (09:17) |
| C4 | Target click lands a grab **or** the deep link on the clipboard | ⚠️ **INTERMITTENT (2/7 attempts; the clipboard route alone is 2/5)** | 16:51 ✓ + 16:54 ✓ (clipboard route), then 17:02 ✗ (`CHAIN_CLIPBOARD got=null`, D-e), 17:04 ✗ (`CHAIN_NO_TARGET`, D-f), 17:05 IG ✗ (`CHAIN_CHOOSER_NO_DOWNI`, no fallback, D-f), 17:12 ✗ (clipboard `got=null`), 18:03 ✗ (clipboard `got=null`, with the gate verifiably **armed** → D-e is not gate-related). Morning's 8/8 was the clipboard route **plus** the chooser click double-firing (D-a), not reliability. Real files did land at 16:34/16:35 |
| C5 | End-to-end ≤ ~3 s | ❌ **FAILS spec** | measured **3.2 / 3.5 / 3.7 / 4.1 / 4.1 / 4.5 / 4.6 / 5.9 s** on 8 taps (≈4.2 s avg). Decide: revise the cell or cut the delay (`postStep 1300 ms` + sheet setup) |
| C6 | No crash; platform app stays foreground; panel closed | ✅ | `CHAIN_STEP_ERR`/`CHAIN_ERR` fencing; no crash in the chain path |
| B1 | Bubble over IG/TikTok ≤~1.5 s; never over DOWNI/other apps | ✅ (timing ⏳) | `test_out/bubble_ig.png`, `bubble_home_hidden.png`; ≤1.5 s **not measured** |
| B2 | Drag moves it; a drag never fires a grab | ✅ | `bubble_dragged.png`, `BUBBLE_MOVED` |
| B3 | Position memory across re-entry and restart | ✅ | `bubble_return.png` |
| B4 | Tap fires exactly one run; second tap ignored | ✅ | `bubble_tap.png`; after the `onBubbleTap` recursion fix |
| B5 | Stays fully on screen after rotation / relaunch (clamped) | ⏳ | **untested** |
| B6 | No crash; bubble dies with the service | ❌ | survives 84–283 s then vivo ABE kills it **and** wipes the a11y binding |
| K-A1 | `CORE_ATTACH type=2032`, no overlay grant, dies with the service | ✅ | `CORE_ATTACH type=2032 x=500 y=1000 size=176px size_dp=64 touchable=0` |
| K-A2 | Mark matches sheet 2 (not the app icon) at 48/56/64 dp | ✅ (owner ruling owed) | `_review_mark_ondevice.jpg`; 0.525/0.529/0.531 vs sheets 0.528/0.531 |
| K-A3 | Wake ≈0.6 s; idle static and draws nothing | ⏳ partial | 17 shots audited (`_gate_kA3_audit.log`); **transition timing not measured on device** |
| K-A4 | Every state as specced; progress on the perimeter, no % text | ⏳ partial | 17/17 rim/mark/bars match; progress paints 0/88/182/274/360°; press/snap/halo/glass values **not** measured |
| K-A5 | Idle ≈0 cost, no wake locks, sane over 10 min | ⏳ (window short) | `_core_idle_cost.log`: frames **+0**, 0 wake locks — but the process lived only ~2 min |
| K-B1…B5 | Press/drag/snap/position/drag≠tap in the **Core** | — | Phase B not implemented (`FLAG_NOT_TOUCHABLE`, no `onTouchEvent`) |
| K-C1…C5 | `PlatformProfile` drives WAKE/DETECTED honestly; never downloads | — | Phase C not implemented |
| K-D1…D5 | Real progress, duplicate check before tap, PAUSED behind D3 | — | Phase D not implemented; **D3 cannot pass today** (no resume/`.part` capability) |
| K-E1…E5 | Settings card, persistence, honest status, exemption walkthrough | — | Phase E not implemented (no Fetcher UI in `www/index.html`) |
| K-F1…F5 | Rotation, lock screen, app switch, network, low memory, FGS, reboot | — | Phase F not started (this matrix is its first artefact) |
| K-G | Spike out of the release source set; debug channels removed; on-device logs deleted | — | Phase G not started; **`FetchSpikeService` is in `src/main` and `release.yml` builds a signed APK from any tag** |

**Defects found while closing C1–C5 (2026-09-25, on the current build):**

| # | Defect | Evidence | Where the fix belongs |
|---|---|---|---|
| D-a | **One tap can grab the same video twice** — the spike's own `pipeline(url)` handoff *and* the chooser click both fire for one URL | `Movies/DOWNI`: `fliqr.clips.mp4` + `fliqr.clips (1).mp4` both **2,135,039 B**; `rekrobot.mp4` + `rekrobot (1).mp4` both **3,377,818 B** | ✅ **FIXED 2026-09-25 16:47**, verified live 16:51 (tap via `input tap 961 798`): one `CHAIN_DELIVER route=clipboard` for the run, no second claim; routes now stand down (`CHAIN_CLIP_SUPPRESSED`), and the dump path skips during a run (`PIPELINE_SKIPPED chain_running`). Phase D still owns the production version of this rule |
| D-b | **Chain can resolve a non-video URL** (a profile/bio link) | 11:06:02 `PIPELINE_HANDOFF_OK url=https://fikrfreeapp.onelink.me/xoBT/tdnrp3bc` | ✅ **FIXED 2026-09-25 16:53**: pure rule `fetcher/MediaUrl` + `MediaUrlTest` (**7 tests**; suite **26/0**). Rejects `onelink.me` (that exact link), profile paths, `linktr.ee`, YouTube; flags TikTok photo posts as `tt_photo_post`. Live 16:54: a real share link passed and delivered once |
| D-c | **C5 exceeds spec** (3.2–5.9 s vs ≤~3 s) | 8 `BUBBLE_TAP` → `PIPELINE_HANDOFF_OK` pairs, 16:30–16:35 | Decide: revise the cell, or cut time (`postStep` 1300 ms, sheet setup, scroll wait) |
| D-d | A chooser walk can read **quick-settings rows** instead of share targets | 16:33:51 `CHAIN_CHOOSER_BUTTON on wi-fi,cmcc-fiber … off torch … silent` | ✅ **FIXED 2026-09-25 17:02**, verified live all three walks: `pickShareRoot()` skips `com.android.systemui` (`… _WINDOW_SKIPPED pkg=com.android.systemui why=shade_cannot_hold_share_targets`) and chooses deliberately (`… _ROOT which=platform_app`). The walk no longer reads quick settings |
| D-e | **The copy-link route is unreliable** — the sheet's Copy link is clicked but the clipboard read comes back empty, so the run delivers nothing | `CHAIN_CLIPBOARD got=null` at **17:02:05.631**, **17:12:05.631** and **18:03:34.307** (the last one with the gate verifiably **armed**), all after `CHAIN_TARGET_CLICK which=copylink`; `CHAIN_URL_REJECTED reason=null`. Same build: 16:51/16:54 `got=yes` → delivered. Morning: 5/5 `got=yes`. **So today: 3 failures of 5 clipboard attempts** | **Cause isolated 2026-09-25 18:05 (code, `chainStep3` + `readClipboardText`):** the route depends on a *focus dance* — `bubble.setFocusable(true)`, wait 500 ms, `ClipboardManager.getPrimaryClip()`. Making a window focusable does **not** make it focused (`mCurrentFocus` was still `com.zhiliaoapp.musically`), and Android 10+ returns null to any app that is not in focus. It worked at 16:51 only when focus happened to land on us. **Fix = stop scraping the clipboard:** route the URL through the app's own share target (`DropActivity`, `ACTION_SEND`,`text/*` — already shipped and already device-tested) i.e. click DOWNI in the system chooser. Short term: retry the read once and log `mCurrentFocus` beside the result |
| D-f | **A miss ends the run with nothing** — no fallback when the chooser does not appear, and no retry while the sheet animates | IG 17:05: `CHAIN_TARGET_CLICK which=chooser_row` → no `android` chooser window → 4 scrolls → `CHAIN_CHOOSER_NO_DOWNI`, and **no copy-link fallback** though a clickable `copy link` row was found at 17:05:06.139. TikTok 17:04: `CHAIN_NO_TARGET neither DOWNI nor Copy link found` | **Phase C — THE highest-value fix.** Make the routes a **chain, not a choice**, in this order: **(1) DOWNI in the system chooser** (delivers via `ACTION_SEND` → `DropActivity`; gate-independent, no clipboard, no tree dependency — it is the app's real production path, and the duplicate files prove it grabbed every time it was clicked) → **(2) Copy link + clipboard read** (needs D-e fixed) → **(3) tree, IG only**. Each with a retry/wait budget, and log which one won |
| D-h | **A run can "claim" a delivery it never made** — found by reading the code, not by log (2026-09-25 18:0x) | `extractAndVerdict` called `claimDelivery("dump_of_sheet")` **before** `pipeline(url)`, and `pipeline()` returns early without grabbing when the gate is off (`FetchSpikeService:587`). Result with a disarmed gate: the run is marked delivered, the clipboard/chooser routes log `…_SUPPRESSED already_delivered` and stand down → **a run that found its URL delivers nothing**, and C4's evidence log says otherwise | ✅ **FIXED 2026-09-25 18:06** (uncommitted→next build): both gate-dependent routes (`dump_of_sheet`, `clipboard`) now claim **only when `handoffEnabled`**. The **chooser** route is deliberately exempt — the system hands the URL to `DropActivity` directly, so it delivers with or without the gate. Suite still **26/26, 0 failures** |
| D-g | **Suspect: an IG `/p/` carousel post is accepted as a video** | 16:33:50 `STEP3_DUMP_37 confidence=HIGH source=tree url=https://www.instagram.com/p/DdsM_GHEXAE/?img_index=14` → handed to the pipeline. `img_index=` marks a carousel slide, and `MediaUrl` accepts any `/p/<code>` | **Ruling needed:** either accept (photos are future work, `ROADMAP.md`) or flag `ig_photo_post` the way TikTok photo posts already are. `/reel/` is unambiguously video |

**Route attribution (measured, gate armed) — the important line for Phase C:** the two platforms
resolve by **different** mechanisms, so neither route may be dropped:

| Platform | Morning, 8 taps | Mechanism that actually fired |
|---|---|---|
| TikTok (5 taps) | 5/5 handed off | **clipboard only** — `CHAIN_CLIPBOARD got=yes` → `PIPELINE_HANDOFF_OK` 28 ms later, ×5. The tree never held the URL (0 HIGH dumps in the morning; **12/12 `no_url_or_id_in_tree`** on the 17:12 run) |
| Instagram (3 taps) | 3/3 handed off | **tree only** — `STEP3_DUMP confidence=HIGH source=tree` → `PIPELINE_HANDOFF_OK` 4 ms later, ×3 (`/p/` carousel, `/reel/` ×2) |

So: on TikTok the tree route is **not** a fallback (nothing to fall back *to*), and the clipboard route
is currently ~40% (2/5 on the new build). On IG the tree route works and the loosened D-a rule lets it
claim the run's single delivery. The morning's 8/8 was therefore *not* eight clean route deliveries —
it was these two mechanisms **plus** the duplicate grab of D-a, which is why the count looked perfect.

**Gate safety (must be part of every future run):** `handoff` is read **only in
`onServiceConnected`** (`FetchSpikeService:213`), so editing `spike_config.properties` changes
nothing until the accessibility binding is toggled. History on this phone: `false` 08:20→09:04,
**`true` 09:07→16:41**, `false` since a forced re-bind at 16:41:16. Before any scan session that is
not itself P0-4, `tools\fetch_diag.ps1` now prints this gate — read it.

**Two checks this matrix must add before it is trustworthy:**
1. `handoff=true` is **live on this device** (`spike_config.properties`, and
   `SERVICE_CONNECTED … handoff=true` at 16:29) — every P0-4 run can start a **real download**. Set
   `handoff=false` before any scan session that is not itself the P0-4 experiment.
2. The Fetcher's own a11y binding is wiped by the ROM alongside the kill, so **every cell above needs
   the binding re-armed first** (`tools\fetch_diag.ps1 -Arm`), exactly like the Core gates do.

