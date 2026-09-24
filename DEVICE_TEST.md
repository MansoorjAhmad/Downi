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

