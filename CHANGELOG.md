# DOWNI Changelog

Full release notes + signed APKs live on
[GitHub Releases](https://github.com/MansoorjAhmad/Downi/releases).

## V3.2.0 — The Fetcher (Downi Core), shipped 2026-09-25

**Phase A — the Core's face.** The Core's face exists and runs on the phone: a
`TYPE_ACCESSIBILITY_OVERLAY` window that draws idle / detected / pressed / dragging / snapped /
progress / paused / resuming / completing / complete / failed, at 48 / 56 / 64 dp. Phase A is
visual only — no touch handling (`FLAG_NOT_TOUCHABLE`), no detection, no download path — and it is
driven by the debug channel `fetch-spike/core.cmd`.

**Mark correction (owner ruling 2026-09-25).** The Core was drawing the *app* icon (the speed-D) —
wrong. The Fetcher's mark is the **design-sheet-2 identity mark** (glossy teal folded-ribbon
chevron), lifted pixel-exact out of the owner's own sheet by `tools/core_mark_from_sheet.py` into
`drawable-nodpi/downi_core_mark.png` + a graphite `_dark` variant — never redrawn, never re-traced.
Its size is measured rather than guessed: the sheets' own Cores stand 0.53 of the disc tall, the
phone reproduces 0.531 at 48 / 56 / 64 dp alike, so `CoreHost.DEFAULT_MARK_SCALE = 0.63`. The
speed-D stays the app's identity (launcher, splash, store) and no longer appears in the Core or in
the bubble.

Verified: `:app:compileDebugJavaWithJavac` + `:app:assembleDebug` = BUILD SUCCESSFUL; `CoreLookTest`
11 + `CoreMarkSpecTest` 7 = **18 tests / 0 failures** (the mark's solved scale, its fit at 48/56/64 dp,
the shipped asset's PNG header + SHA-256 so the mark cannot be swapped or resized silently, and the
error tint's single owner — FAILED — so PAUSED can never read as an error); on-device evidence
`test_out/core_visual/core_mark_*.png` with `_review_mark_ondevice.jpg` (sheets vs device, every disc
normalised to 130 px), and the final `-Default` run passed (no `mark` command sent, so the baked 0.63
drew itself at 48/56/64 dp alike).

**State spectrum + idle cost (cells K-A3/K-A4/K-A5, 2026-09-25).** The 17-shot state sweep is no
longer only an eye-test: `tools/core_review_sheets.ps1` builds the review strips and then runs
`tools/core_state_audit.py` over the same shots, so the owner sees each state's rim colour, mark,
PAUSED bars and lit perimeter as numbers next to the picture (`_gate_kA3_audit.log`,
`_gate_kA4_audit.log`). Result — 17/17 states match `CoreLook` (teal rim except FAILED,
`hidden` absent), and the progress sweep paints 0 / 88 / 182 / 274 / 360 deg for a named
0/25/50/75/100%: the perimeter *is* the progress, no percent text anywhere. Idle cost:
**frames drawn +0, no wake locks over 10 minutes** (`_core_idle_cost.log`) — with the honest caveat
that this ROM kills the app about two minutes in, so the zero-frame window is ~2 minutes with a live
process. Three open questions for the owner are listed in `V3.2_PLAN.md` (disc material, lit vs logo
glyph, does PAUSED hide the mark), alongside the K-A3/K-A4/K-A5 judgement.

**Phase B — Physical interaction: ACCEPTED on device (2026-09-25).** The Core is live, touchable,
persistent, and one tap fetches — with no bubble anywhere in the chain. The overlay is no longer
`FLAG_NOT_TOUCHABLE`; `DowniCore` owns press/drag/release and persists the position.

| Cell | On-device evidence (vivo V2058, Android 13, TikTok) | Verdict |
|---|---|---|
| K-B1 one tap = one grab | `spike_20260925-194207.log`: `CORE_TOUCH down` → `CORE_TOUCH up dragging=false` → `CORE_TAP` → `CHAIN_DELIVER_OK route=clipboard tap=1 url=https://vt.tiktok.com/ZSbLoParN/` — **1/1/1**, and `BUBBLE_*` = **0**, `CHAIN_ERR`/`CHAIN_STEP_ERR` = **0** | PASS |
| K-B2 drag moves it, drag ≠ tap | `spike_20260925-194828.log`: 1500 ms swipe → `CORE_TOUCH up dragging=true` → `CORE_MOVED x=236 y=1653`; WMS frame moved `[500,1000][676,1176]` → `[236,1653][412,1829]`, `HAS_DRAWN`/`isOnScreen=true`, and **no `CORE_TAP` fired** | PASS |
| K-B3 position memory | `hide` → `CORE_HIDE` (window `mViewVisibility=0x8`, `isVisible=false`, nothing on screen); `show` → Core returns at the **dragged** `[236,1653]`, not the original; after a full service rebind the new process logged `CORE_ATTACH x=236 y=1653` | PASS |
| K-B4 authoritative bounds | WMS: `mAttrs={(500,1000)(176x176) ty=2032}` + `CORE_ATTACH x=500 y=1000 size=176px size_dp=64 touchable=1`; the predicted box held 2 099 core-coloured px (0 before the service was re-armed) and the disc renders in `test_out/core_live_crop.png` | PASS |
| K-B5 on-screen/clamped | Dragged Core renders fully inside the display in `test_out/after_drag_crop.png` | PASS |

`handoff=false` stayed in effect for the whole run (each bind proved
`SERVICE_CONNECTED sdk=33 handoff=false`), so the tap chain was exercised without automatic handoff —
as intended, `handoff` gates the hand-off, not the tap.

**Known issue, not fixed by this release — the vivo force-stop.** This ROM still kills the process
and wipes `enabled_accessibility_services` back to `null` with no crash marker and no Java exception.
Three measured runs this session: **150 s**, **210 s**, and **5 s** of uptime
(`test_out/kill_analysis/run{1,2,3}.log`), so it is not a fixed two-minute timer. The Core recovers
cleanly every time — re-arming via `tools/fetch_diag.ps1 -Arm` rebinds, re-attaches and restores the
saved position — but the sample is still too small to call the platform win, and the user-side
exemption walkthrough (Phase E) is still owed. The 10-minute idle-cost result in the previous entry
carries the same caveat for the same reason.

**Build + tests (2026-09-25).** `:app:testDebugUnitTest --rerun-tasks` = **BUILD SUCCESSFUL**,
83/83 tasks executed, **26 tests / 0 failures / 0 errors** (`CoreLookTest` 11, `CoreMarkSpecTest` 7,
`MediaUrlTest` 7, `ExampleUnitTest` 1). Device left **disarmed**: a11y binding deleted,
`accessibility_enabled=0`, process stopped, `spike_config.properties` = `handoff=false`.

**Pre-tag forensic audit (2026-09-25, after the release commit — before the tag).** A full end-to-end
review of the Fetcher (detection → Core → tap → resolver → `startShared` → dedup → lifecycle) found
five latent defects, all fixed with minimal diffs; none had ever fired in a captured device log, and
the accepted Phase A/B behaviour is unchanged. **D-i** — a second tap inside the first run's
clipboard-retry window (~2.4 s) raced the previous run's still-pending reader (a stale URL could be
delivered for the new tap, or the new run's delivery suppressed): delayed steps now carry a run
generation and stand down (`CHAIN_CLIP_STALE`), and each run starts from a non-focusable Core.
**D-j** — bench `chain.cmd click` runs bypassed the one-delivery discipline (the original D-a
duplicate shape, bench mode only): they now go through the same `beginChainRun()` as a tap.
**D-k** — two rapid taps on the same video downloaded it twice (`Video.mp4` + `Video (1).mp4`):
new pure `fetcher/DeliveryGuard` suppresses the same URL within 8 s (`CHAIN_DELIVER_DUP
suppressed=same_url_within_8s`), 6 deterministic JVM tests. **D-l** — rotation could strand a shown
Core off-screen (B5's clamp only ran on show): `DowniCore.ensureOnScreen()` re-clamps from the
existing 900 ms tick without clobbering the saved position. **D-m** — a rebind without `onDestroy`
doubled all four polling loops: `pollersArmed` posts them once per service instance. Also audited
and left alone: the sheet-2 mark (byte-exact, SHA-locked), version sync (48/3.2.0 in all three
spots), the D-a/D-b/D-d/D-h guards, the FGS/focus-retry design. Validated: `:app:testDebugUnitTest`
+ `:app:assembleDebug` = **BUILD SUCCESSFUL, 32 tests / 0 failures** (the 26 above + `DeliveryGuardTest`
6); `aapt dump badging` = versionCode 48 / 3.2.0. **Device-verified on the vivo V2058 the same evening
(build installed over the owner's 3.1.2, prod-signed, gate `handoff=false`):** two rapid Core taps on
two different TikTok videos delivered one video per tap with zero cross-run interference (twice —
21:32 and 21:38, the second time with TikTok's feed auto-advancing between taps); both downloads
landed in `Movies/DOWNI` (end-to-end: tap → resolver → engine → Vault); a tap during a dead session
logged `CORE_TAP_NO_SESSION` instead of pretending; rotation under TikTok is structurally impossible
(portrait-locked, `ROTATION_0`) and the re-clamp verified no-op-safe. The vivo ABE killer was
reproduced twice (~195 s and ~210 s uptime, binding wiped) — the known B6 platform blocker, recovered
by re-arming each time. Details in `DEVICE_TEST.md` §5 rows D-i…D-m.

**The living Core — master-package pass (2026-09-25, late evening).** The owner shipped the full
feature definition (`DOWNI_FETCHER_MASTER_PACKAGE.md`: ONE CORE, ONE IDENTITY, ONE JOB, ONE FILE,
MANY STATES, ZERO UNINTENTIONAL DOWNLOADS) and the missing half of the feature is now built:
- **Detection is awareness** (§2/§9): the Core now WAKES from real evidence — the watching signals
  Phase 0 measured (`video_player_progress` …) drive `CORE_DETECT detected=true` → WAKE → DETECTED
  when a video is on screen, honest IDLE on profiles/settings, 1.2 s flip debounce. Detection still
  never downloads; the tap remains the only trigger.
- **The Core is the job's living face** (§11/§12/§30/§31): new read-only `downicore/CoreJobBinding`
  watches the download service's own job snapshot (the same `dropLive` rows the in-app Queue
  renders, written on the service's 800 ms floor) and turns the real job into Core states —
  perimeter = the real percent, done → the restrained completion pulse, failed → the restrained
  error state, canceled → quiet idle, terminal rows age out on the same 20 s TTL the app uses.
  Zero engine modification (ruling 4 intact). 7 new JVM tests (39 total).
- **One object, many states, honest precedence** (§7): a state arbiter (job > detection > idle)
  owns the Core's base state; press/drag still physically override but return to the arbiter's
  truth instead of a hardcoded idle — a Core that is mid-download no longer forgets its job when
  dragged.
- **Duplicate prevention before job creation** (§32/§33): a tap whose URL already has a RUNNING
  job ADOPTS it (the Core binds to the live job, `CHAIN_DELIVER_ADOPT`); one completed seconds ago
  is refused (`suppressed=already_saved`); failures are retried freely.
- **Failure is finally visible** (§15): every resolver dead-end — no share row, no copy-link row,
  empty clipboard, rejected URL, refused delivery — shows the restrained error state ("Something
  went wrong.", retry-ready) for 3 s, then returns to whatever is actually true.
- **Pause/resume (§13/§31) is the one capability deliberately NOT faked:** real pause needs
  engine surgery (`.part` continuation — the TikTok fast path opens the file `'wb'` and yt-dlp
  runs `nopart:True`), which The Law reserves for the full device matrix. PAUSED/RESUMING stay in
  the vocabulary; nothing drives them until the D3 device test passes.
Validated: 39 tests / 0 failures; `:app:assembleDebug` BUILD SUCCESSFUL. Mark unchanged: the Core
still wears the design-sheet-2 chevron, never the app logo.

**The face rebuilt to the sheets — visual-identity pass (2026-09-25, night).** The owner's verdict
on the first living-Core build was "it still looks the same" — correct: the MD's most *visible*
layers were still the Phase-A simplification. Rebuilt to the master package:
- **Material (§5):** deep obsidian body with real depth shading, a teal bounce light from below,
  and a soft specular gloss top-left — premium from shape + proportion + material + lighting, not
  effects.
- **Silhouette (§6):** the body is now a softly lobed organic gel shape with fixed asymmetry —
  never a "mathematically perfect generic circle".
- **Size (§6/§19):** the visible pebble is 0.80 of its window (~50 px class at the 64 dp window —
  inside the spec's 40–60 px read) while the touch target stays the full 48/56/64 dp window:
  small visual footprint, comfortable interaction area. The mark scale re-solved for the smaller
  disc: 0.63 → **0.65** (the fixed dp insets are a larger share; ±1.3% worst at 40 px, tests
  updated to the new measured bake and dynamic neighbours).
- **Idle/detected (§8/§9):** idle is almost dormant (dim halo/rim); DETECTED is unmistakable —
  full bright gel rim + strong bloom, no text, the "Downi found something" read.
- **Energy flow (§12/§20/§25):** while a real job is in PROGRESS the perimeter sheen slowly
  rotates and the halo breathes — active process feel; the animator exists only during a download,
  so idle cost stays zero (K-A5).
Device-verified on the vivo V2058 over a live Reel: `test_out/core_v2_zoom.jpg` (DETECTED),
`core_v2_dl_zoom.jpg` (downloading at ~27% with the arc at the perimeter's top), 39/39 tests green.
The mark is still the design-sheet-2 chevron, byte-identical asset — never the app icon.

**Fetcher self-recovery after the vivo wipe (2026-09-25, night).** The vivo power manager doesn't
just kill the process — it **clears `enabled_accessibility_services`**, so the Core never came
back on its own and every death needed a manual re-arm (measured again tonight: process dead,
setting null, no unbind marker). Now, with the one-time adb grant
`pm grant com.omnidownloader.app android.permission.WRITE_SECURE_SETTINGS`, DOWNI re-applies its
own binding the moment the app is opened: **recovery = open DOWNI**. Guarded so it can never
surprise anyone: it only re-arms a service the user themselves enabled once (`wasArmed`, set by
the service on connect), only with the grant present, and only when the binding is actually
missing. Proven on device: binding wiped by hand → DOWNI relaunched → binding restored → Core
attached over Instagram (`test_out/core_v3_showing.png`). The full fix for the killing itself
remains the user-side vivo exemption (Autostart allow + battery unrestricted + lock in Recents) —
no code can reach that.

**Wave 1 — the Attention Ledger + instant taps + living physics (2026-09-26, early hours).** The
next-level pass from the product vision, executed:
- **The Attention Ledger** (`fetcher/AttentionLedger`, pure + 8 JVM tests): the Fetcher now
  maintains a confidence-scored model of what the user is looking at. A candidate becomes READY
  only when it was visible on screen, dwelled ≥800 ms, survived without the feed paging (scroll
  transitions demote everything pre-scroll), hasn't gone missing from consecutive dumps, and is
  fresh. **Instagram taps are now INSTANT on READY candidates** — the URL is served from the
  ledger (`route=ledger`), no share sheet, no flash, no clipboard dance. TikTok's tree is opaque
  (61/61 clean dumps, measured), so TikTok taps keep the verified copy-link chain. Low-confidence
  candidates are structurally unable to serve a download — the dangerous guess cannot happen.
- **Strategy Ledger** (`fetcher/StrategyLedger`, 4 tests): per-platform/per-route success rates
  over a rolling 20-attempt window, logged as `STRATEGY <platform> <route> NN% (n/N)` — the
  resolver's self-awareness: when a platform update breaks a route, the log (and one day the
  settings card) knows which route and since when.
- **Event-driven resolver waits:** the chain now polls for the share surface's own window
  (~250 ms cadence, deadline fallback for same-window OEM sheets) instead of sleeping a fixed
  1300 ms — step 2 starts as soon as the sheet actually exists; the post-copy wait drops
  1600→1200 ms under the retry loop's cover.
- **RESOLVING state + the energy law (§M-1):** new Core state `resolving` (rim-orbit light,
  resolution has visible progress); while a job downloads, **the mark lends its light to the
  perimeter** (dims to 60%, the ring's comet-head leads the arc); at completion the ring
  collapses inward and the mark blooms back to full. Pressing sinks the mark 0.8 dp into the
  gel. Pure-table tests lock the law (mark dims, light returns, sink depth).
- **Gel physics (V-3):** interior slosh — the mark trails the container during drags and springs
  home on release; edge squash — the body flattens against the edge it snaps to and settles.
  Drag-only/snap-only, so idle still draws nothing (K-A5 intact).
- **Haptic voice** (`downicore/CoreHaptics`): detected = one soft tick, complete = quick double
  tick, failed = low dull pulse. **The Core never makes sound** — it would compete with the
  video's audio — and never vibrates while hidden or the screen is off.
- **Screen-off discipline (§E2):** when the screen is dark: zero dumps, zero polls, zero
  heartbeats, Core hidden — the Fetcher costs nothing at night and starves vivo's "excessive
  power" trigger.
Validated: **54 tests / 0 failures**; `:app:assembleDebug` BUILD SUCCESSFUL; installed
prod-signed. On-device instant-tap verification pending (phone locked for the night — the ledger
gates are JVM-proven; live proof lands with the next unlocked session).

**Device session 2026-09-26 morning — the resolver learns patience, and gets faster.**
- **Honest finding on the instant-tap premise:** during normal Reels watching, Instagram's tree
  carried **no URL in 21/21 dumps** — the URL only appears while the share sheet is open. The
  Wave-1 passive ledger is therefore structurally inert on fresh reels (and correctly so: it
  falls back to the proven chain rather than guessing). The ledger stays as the confidence gate
  for when the platform *does* leak (it auto-upgrades if a future IG version exposes URLs again).
- **Event-driven waits verified:** the chain now polls for the share surface's real window
  (`CHAIN_SURFACE_OPEN after_ms=300–550`) instead of sleeping 1300 ms — tap→deliver measured
  **2.74 s** (first run under the ≤3 s C5 target) and 3.3 s on TikTok.
- **New defect caught + fixed (TikTok, `no_copy_link`):** the sheet *window* opens before its
  *content* loads — an early scan read the feed's buttons and failed. The wait now requires the
  sheet's own content (a copy-link candidate present) with a ~3.7 s deadline; TikTok then
  delivered cleanly at 3.3 s (`content=ready`, ACTION-route click).
- **Sheet-tree fast lane shipped as opportunistic:** chainStep2 scans the share surface's own
  window (com.vivo.upslide) plus the platform tree for a media URL and delivers straight from
  the sheet (skipping the copy-link click and the focus dance) — but this IG build never leaks
  the URL, so the clipboard lane remains the workhorse. Cost when silent: one 350 ms IG-only
  retry. Auto-upgrades if the platform starts leaking again.
- Close-panel logic split from the copy-click flag (`chainSheetNeedsClose`), so every route that
  opens a sheet closes it — including future ones.
54 tests / 0 failures; both platforms verified live on the V2058.

## Unreleased — V3.3 (in progress)

_(nothing yet)_

## Released

| Version | Code | Highlights |
|---|---|---|
| **3.2.0** | 48 | **The Fetcher (Downi Core) — Phase A + B, accepted on device.** The Core is live, touchable and persistent: a `TYPE_ACCESSIBILITY_OVERLAY` window drawing idle / detected / pressed / dragging / snapped / progress / paused / resuming / completing / complete / failed at 48/56/64 dp, wearing the **design-sheet-2 identity mark** (glossy teal folded-ribbon chevron, lifted pixel-exact by `tools/core_mark_from_sheet.py` — never redrawn, never re-traced, and no longer wearing the app's speed-D icon). One tap on the Core now fetches: on the vivo V2058 / Android 13 / TikTok a single tap produced exactly `1 × CORE_TOUCH down → 1 × CORE_TOUCH up → 1 × CORE_TAP → 1 × CHAIN_DELIVER_OK route=clipboard tap=1` with **zero** `BUBBLE_*` events and zero chain errors. Drag moves it and never fires a grab (`dragging=true` + `CORE_MOVED`, no `CORE_TAP`); hide/show and a full service rebind both restore the saved position, which survives process death. The Core is drawn once and only short transitions animate — **frames drawn +0, no wake locks** while idle. `handoff=false` is the default and is proven live on every bind, so nothing auto-downloads: the tap is the whole UX. Known and not fixed here: this vivo ROM still force-stops the process and wipes the accessibility binding (measured at 150 s / 210 s / 5 s across three runs); the Core re-attaches and restores position on every re-arm, and the user-side exemption walkthrough is owed |
| **3.1.2** | 47 | **The Stay-Put Release.** Share → DOWNI while DOWNI sits in recents no longer rips you out of TikTok/YouTube/Instagram into the DOWNI app: the invisible DropActivity now lives in its own throwaway task (`taskAffinity=""`) and removes that task on the way out (`finishAndRemoveTask`), so warm shares behave exactly like cold ones — toast, silent background grab, you never leave the platform app (found + confirmed fixed on-device). DowniDrop failures now diagnose themselves: a failed grab writes its plain-language reason onto the in-app failed card and parks the raw engine text in `dropLastError`, returned by `getDropJobs().lastError` — the "fails twice, works on the 3rd try" report can now be named from the app itself, no PC or cable needed.
| **3.1.2** | 47 | **The Stay-Put Release.** Share → DOWNI while DOWNI sits in recents no longer rips you out of TikTok/YouTube/Instagram into the DOWNI app: the invisible DropActivity now lives in its own throwaway task (`taskAffinity=""`) and removes that task on the way out (`finishAndRemoveTask`), so warm shares behave exactly like cold ones — toast, silent background grab, you never leave the platform app (found + confirmed fixed on-device). DowniDrop failures now diagnose themselves: a failed grab writes its plain-language reason onto the in-app failed card and parks the raw engine text in `dropLastError`, returned by `getDropJobs().lastError` — the "fails twice, works on the 3rd try" report can now be named from the app itself, no adb needed. |
| **3.1.1** | 46 | **The Truth Release.** Notifications finally match reality: live rows carry real % · size · speed · ETA, background Share → DOWNI grabs show up inside the app (Grab + Queue tabs) with working Cancel, the "Saved" alert fires only on real completion, tapping any notification lands on the Queue, and a one-time dismissible hint card offers notification permission only when it is actually off. New: the Vault live-refreshes when a grab lands while you watch it (keyed diff, only the new card animates). Job-card Cancel buttons get full 44dp tap targets, live numbers leave the 10px floor. Copy hygiene: raw engine text becomes plain reasons ("That link isn't a video." / "Can't reach the network — try again."), non-video links are rejected honestly at the Inspector, user cancels show a neutral "Grab canceled" instead of a scary failure, and logcat gains DOWNI breadcrumbs. Finished background-grab cards clear themselves a few seconds after landing (defect N8). One grab now shows exactly one notification row (defect N10): the foreground slot has a single owner, a finishing, failing or cancelled grab releases it and hands it to the next live grab, and progress ticks only ever write to the owning row — so a grab can no longer post two copies of itself, and a second concurrent grab gets its own row instead. A grab that no longer exists can no longer be shown as live (defect N11): killing the app mid-grab used to leave a frozen "running" card — and an ACTIVE count plus Queue badge — for a grab that was gone, so live snapshot entries now expire on liveness (the service's own live-job list) instead of never, while finished/failed entries keep their short age window. Share → DOWNI is always instant — the Instant/Ask-quality toggle is retired (owner ruling; the Inspector stays for in-app picks). Also fixes the stale version fallback (defect C). Queue accounting is honest (defect N9): "MB grabbed" and the history list now include the grabs that finished while the app was shut — the service keeps its own ledger — and every completion reports the real size of the file that landed. Two more truth fixes from the device pass: a finished grab can no longer be counted as running (defect N12) — the 20 s "Saved" card is no longer counted in the ACTIVE tile or the Queue badge dot, so nothing lights up while the service is already stopped — and "MB grabbed" no longer sits a grab behind (defect N13): the service's ledger is folded in the moment a grab completes, not only on the next app start. |
| **3.1.0** | 45 | **The Polish Release.** DowniDrop 2.0: Share → DOWNI grabs instantly in the background again (invisible DropActivity revived from the v2.6.4 golden era, self-starting engine — no app warm-up needed, per-platform quality memory honored headless, Cancel button on the progress notification, rich saved/failed notifications with tap-to-retry) — with an "Ask quality first" toggle in Settings for the v3.0 Inspector flow. Vault blink fixed at the root: keyed-DOM reconciliation (cards are created once and only ever moved/added/removed — never rebuilt), in-place selection toggles, no-op refresh detection, 200ms search debounce, single-card new-arrival animation. Also folds in v3.0.4 (DOWNI-only Vault folders) |
| **3.0.4** | 44 | Vault is now DOWNI-only: downloads save into their own `Movies/DOWNI/` + `Music/DOWNI/` folders (a clean DOWNI album in gallery apps), and the Vault query is scoped to those folders plus the app's tracked save names — the whole device gallery no longer leaks into the Vault, and existing users' downloads stay listed. Vault blinking eliminated for good: the entrance animation plays once, later refreshes update silently (no per-refresh animation replay) |
| **3.0.3** | 43 | YouTube fixed for adaptive-only streams (every video lane falls back to the proven split + on-device MediaMuxer merge; merge progress now reports real percentages); warm-share Inspector fixed (Capacitor hands the payload on the event itself, not `e.detail` — DowniDrop works from a running app); the Vortex and clipboard banner honor the freshest copied link; Vault lists your downloads again on Android 10+ (RELATIVE_PATH was read but never projected); the Vault grid and the live download cards no longer blink (per-tick DOM rebuilds replayed the entrance animation); Engine health probes a downloadable lane instead of metadata only |
| **3.0.2** | 42 | Fix dead text-selection share (PROCESS_TEXT), fix double Inspector on cold-share, Vault media permission fix (downloads always listed), VP9/AV1 1080p codec gates with honest errors, monochrome QS-tile icon, device test matrix doc |
| **3.0.1** | 41 | Hardening release — B1–B20 fixed + identity cleanup (Omni → Downi) |
| **3.0.0** | 40 | Design system, Vault 2.0, Player 2.0, true 1080p on-device merge, playlist batch, engine warm-up |
| 2.6.6–2.6.7 | 35–36 | Perfection pass: build-sync guard, honest quality picker, custom save folder end-to-end, updater + manifest hardening, haptics |
| 2.6.5 | 34 | In-app Vault player, complete light theme |
| 2.6.2–2.6.3 | 32–33 | Restored proven v1.3.6 core; light theme toggle, Vault UI |
| 2.5.x–2.6.1 | ≤31 | Cloud Boost era (removed), branding, updater |

Web app history lives in the [downi-web repo](https://github.com/MansoorjAhmad/downi-web) (own semver: v1.0.0 → v2.1.x).
